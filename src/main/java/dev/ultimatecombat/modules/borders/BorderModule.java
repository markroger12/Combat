package dev.ultimatecombat.modules.borders;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.api.event.CombatUntagEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.CommandRunner;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combat.CombatTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat borders (borders.yml): keeps tagged players inside PvP areas with client-side walls
 * and/or an invisible push-back. Client-side blocks are only ever sent to the player; real world
 * blocks are never changed and every visual is reverted on untag, quit, reload and disable.
 */
public final class BorderModule extends Module<BorderSettings> {

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, CachedState> safeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Location[]> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Long> messageGuard = new ConcurrentHashMap<>();
    private final Map<UUID, Long> escapeGuard = new ConcurrentHashMap<>();

    private record CachedState(boolean safe, long at) {
    }

    private record Pos(int x, int y, int z) {
    }

    private static final class Session {
        final Set<Pos> shown = new HashSet<>();
        volatile ScheduledTask task;
        volatile UUID world;
    }

    public BorderModule(UltimateCombatPlugin plugin) {
        super(plugin, "combat-borders", "borders.yml");
    }

    @Override
    protected BorderSettings parse(ConfigReader reader) {
        int max = plugin.configs().file("config.yml").reader().integer("performance.max-border-scan-radius", 16, 2, 32);
        return BorderSettings.parse(reader, max);
    }

    @Override
    protected void onEnable() {
        CombatModule combat = plugin.active(CombatModule.class);
        if (combat != null) {
            for (CombatTag tag : combat.tags()) {
                Player p = Bukkit.getPlayer(tag.player());
                if (p != null) {
                    start(p);
                }
            }
        }
    }

    @Override
    protected void onReload(BorderSettings previous) {
        safeCache.clear();
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                stop(p);
                start(p);
            }
        }
    }

    @Override
    protected void onDisable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                stop(p);
            } else {
                Session s = sessions.remove(id);
                if (s != null && s.task != null) {
                    s.task.cancel();
                }
            }
        }
        safeCache.clear();
    }

    // ------------------------------------------------------------------------------- safe zones

    /**
     * @return whether PvP is disabled at the block (built-in zones or protection plugins)
     */
    public boolean isSafe(World world, int x, int y, int z) {
        long key = ((long) world.getUID().hashCode() * 31L) ^ (((long) x & 0x3FFFFFL) << 42) ^ (((long) z & 0x3FFFFFL) << 20) ^ ((long) y & 0xFFFFFL);
        long now = System.currentTimeMillis();
        CachedState cached = safeCache.get(key);
        if (cached != null && now - cached.at() < 2000) {
            return cached.safe();
        }
        boolean safe = computeSafe(world, x, y, z);
        if (safeCache.size() > 50_000) {
            safeCache.clear();
        }
        safeCache.put(key, new CachedState(safe, now));
        return safe;
    }

    private boolean computeSafe(World world, int x, int y, int z) {
        BorderSettings s = settings(world);
        for (BorderSettings.Zone zone : s.zones()) {
            if (zone.blockPvp() && zone.contains(world.getName(), x, y, z)) {
                return true;
            }
        }
        if (!s.useProtectionPlugins()) {
            return false;
        }
        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        if (plugin.integrations().isPvpAllowedAt(loc)) {
            return false;
        }
        if (!s.ignoredRegions().isEmpty()) {
            for (String region : plugin.integrations().regionsAt(loc)) {
                if (s.ignoredRegions().contains(region)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean affected(Player player) {
        CombatModule combat = plugin.active(CombatModule.class);
        if (combat == null) {
            return false;
        }
        CombatTag tag = combat.tag(player);
        if (tag == null || tag.remainingMillis() <= 0) {
            return false;
        }
        BorderSettings s = settings(player.getWorld());
        return s.combatTypes().contains(tag.type()) && s.worlds().allows(player.getWorld()) && !plugin.bypasses(player, "combat-borders");
    }

    // ------------------------------------------------------------------------------- sessions

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        Player player = event.getPlayer();
        plugin.scheduler().runEntityLater(player, () -> {
            if (!sessions.containsKey(player.getUniqueId()) && affected(player)) {
                start(player);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(CombatUntagEvent event) {
        Player player = event.getPlayer();
        plugin.scheduler().executeEntity(player, () -> stop(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null && s.task != null) {
            s.task.cancel();
        }
        selections.remove(event.getPlayer().getUniqueId());
        messageGuard.remove(event.getPlayer().getUniqueId());
        escapeGuard.remove(event.getPlayer().getUniqueId());
    }

    private void start(Player player) {
        BorderSettings s = settings(player.getWorld());
        if (!s.visualBlocks() && !s.particles()) {
            sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
            return;
        }
        Session session = new Session();
        Session previous = sessions.put(player.getUniqueId(), session);
        if (previous != null && previous.task != null) {
            previous.task.cancel();
        }
        session.task = plugin.scheduler().runEntityTimer(player, () -> update(player, session), 1L, s.updateTicks());
    }

    private void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        restore(player, session, session.shown);
        session.shown.clear();
    }

    private void restore(Player player, Session session, Set<Pos> positions) {
        World world = player.getWorld();
        if (session.world != null && !world.getUID().equals(session.world)) {
            return; // the client already discarded those fake blocks when changing world
        }
        for (Pos pos : positions) {
            if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                Location loc = new Location(world, pos.x(), pos.y(), pos.z());
                player.sendBlockChange(loc, world.getBlockAt(loc).getBlockData());
            }
        }
    }

    private void update(Player player, Session session) {
        if (!player.isOnline()) {
            return;
        }
        if (!affected(player)) {
            stop(player);
            return;
        }
        World world = player.getWorld();
        if (session.world != null && !session.world.equals(world.getUID())) {
            session.shown.clear();
        }
        session.world = world.getUID();
        BorderSettings s = settings(world);
        Location base = player.getLocation();
        int px = base.getBlockX();
        int py = base.getBlockY();
        int pz = base.getBlockZ();
        int r = s.radius();
        Set<Pos> wanted = new HashSet<>();
        List<Location> particleSpots = new ArrayList<>();
        BlockData fake = s.material().createBlockData();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                int x = px + dx;
                int z = pz + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                if (!isSafe(world, x, py, z)) {
                    continue;
                }
                boolean edge = !isSafe(world, x + 1, py, z) || !isSafe(world, x - 1, py, z)
                        || !isSafe(world, x, py, z + 1) || !isSafe(world, x, py, z - 1);
                if (!edge) {
                    continue;
                }
                if (s.particles() && particleSpots.size() < 24) {
                    particleSpots.add(new Location(world, x + 0.5, py, z + 0.5));
                }
                if (!s.visualBlocks()) {
                    continue;
                }
                for (int dy = -s.height() + 1; dy <= s.height(); dy++) {
                    int y = py + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Location loc = new Location(world, x, y, z);
                    if (!plugin.scheduler().isOwnedByCurrentThread(loc)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (s.onlyReplaceable() && !(block.getType().isAir() || block.isLiquid() || block.isPassable())) {
                        continue;
                    }
                    wanted.add(new Pos(x, y, z));
                }
            }
        }
        Set<Pos> removed = new HashSet<>(session.shown);
        removed.removeAll(wanted);
        restore(player, session, removed);
        for (Pos pos : wanted) {
            if (!session.shown.contains(pos)) {
                player.sendBlockChange(new Location(world, pos.x(), pos.y(), pos.z()), fake);
            }
        }
        session.shown.clear();
        session.shown.addAll(wanted);
        for (Location spot : particleSpots) {
            plugin.effects().spawnParticle(player, spot, s.particlePreset());
        }
    }

    // ------------------------------------------------------------------------------- push back

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ() && from.getBlockY() == to.getBlockY()) {
            return;
        }
        Player player = event.getPlayer();
        BorderSettings s = settings(player.getWorld());
        if (!s.pushback() || !affected(player) || from.getWorld() != to.getWorld()) {
            return;
        }
        World world = to.getWorld();
        if (!isSafe(world, to.getBlockX(), to.getBlockY(), to.getBlockZ()) || isSafe(world, from.getBlockX(), from.getBlockY(), from.getBlockZ())) {
            return;
        }
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        event.setTo(back);
        Vector push = from.toVector().subtract(to.toVector()).setY(0);
        if (push.lengthSquared() < 1.0E-4) {
            push = player.getLocation().getDirection().multiply(-1).setY(0);
        }
        if (push.lengthSquared() > 1.0E-4) {
            push.normalize().multiply(s.pushStrength()).setY(s.pushVertical());
            Vector velocity = push;
            plugin.scheduler().runEntity(player, () -> player.setVelocity(velocity));
        }
        escapeAttempt(player, s);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        BorderSettings s = settings(player.getWorld());
        boolean pearl = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
        if (pearl ? !s.blockEnderPearls() : !s.blockTeleports()) {
            return;
        }
        if (!affected(player)) {
            return;
        }
        if (isSafe(to.getWorld(), to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            event.setCancelled(true);
            escapeAttempt(player, s);
        }
    }

    private void escapeAttempt(Player player, BorderSettings s) {
        long now = System.currentTimeMillis();
        Long last = messageGuard.get(player.getUniqueId());
        if (last == null || now - last > 1500) {
            messageGuard.put(player.getUniqueId(), now);
            if (s.message()) {
                plugin.messages().send(player, "borders.blocked");
            }
            plugin.effects().playSound(player, s.sound());
        }
        if (!s.escapeCommands().isEmpty()) {
            Long lastEscape = escapeGuard.get(player.getUniqueId());
            if (lastEscape == null || now - lastEscape >= s.escapeCommandCooldownMillis()) {
                escapeGuard.put(player.getUniqueId(), now);
                Location l = player.getLocation();
                CommandRunner.console(plugin, player, s.escapeCommands(), "player", player.getName(), "world", l.getWorld().getName(),
                        "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ());
            }
        }
        plugin.debug().log("borders", player.getName() + " tried to leave combat area");
    }

    // ------------------------------------------------------------------------------- zone PvP

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onZonePvp(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        if (attacker == null || attacker == victim) {
            return;
        }
        BorderSettings s = settings(victim.getWorld());
        if (s.zones().isEmpty()) {
            return;
        }
        if (inPvpBlockingZone(victim.getLocation(), s) || inPvpBlockingZone(attacker.getLocation(), s)) {
            event.setCancelled(true);
            plugin.messages().send(attacker, "borders.zone-no-pvp");
        }
    }

    private static boolean inPvpBlockingZone(Location l, BorderSettings s) {
        if (l.getWorld() == null) {
            return false;
        }
        for (BorderSettings.Zone zone : s.zones()) {
            if (zone.blockPvp() && zone.contains(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------- commands

    @Override
    public void registerCommands() {
        String perm = "ultimatecombat.border.admin";
        UCCommand group = new UCCommand("border", perm, true, "borders") {
            @Override
            public String usage() {
                return "border <pos1|pos2|create|delete|list>";
            }
        };
        group.child(new SimpleCommand("pos1", "pos1", perm, true, ctx -> select(ctx, 0)));
        group.child(new SimpleCommand("pos2", "pos2", perm, true, ctx -> select(ctx, 1)));
        group.child(new SimpleCommand("create", "create <name>", perm, true, this::create));
        group.child(new SimpleCommand("delete", "delete <name>", perm, false, this::delete,
                ctx -> settings().zones().stream().map(BorderSettings.Zone::name).toList()));
        group.child(new SimpleCommand("list", "list", perm, false, this::list));
        plugin.commands().root().child(group);
    }

    private void select(CommandContext ctx, int index) {
        if (!guard(ctx)) {
            return;
        }
        Location loc = ctx.player().getLocation().getBlock().getLocation();
        selections.computeIfAbsent(ctx.player().getUniqueId(), k -> new Location[2])[index] = loc;
        ctx.reply("borders.position-set", "number", index + 1, "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ());
    }

    private void create(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String name = ctx.arg(0);
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            ctx.reply("general.usage", "usage", "/uc border create <name>");
            return;
        }
        Location[] sel = selections.get(ctx.player().getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null || sel[0].getWorld() != sel[1].getWorld()) {
            ctx.reply("borders.selection-incomplete");
            return;
        }
        ConfigFile file = plugin.configs().file("borders.yml");
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("world", sel[0].getWorld().getName());
        zone.put("min", sel[0].getBlockX() + "," + sel[0].getBlockY() + "," + sel[0].getBlockZ());
        zone.put("max", sel[1].getBlockX() + "," + sel[1].getBlockY() + "," + sel[1].getBlockZ());
        zone.put("block-pvp", true);
        plugin.configs().newBackupSession();
        if (file.write("zones." + name, zone)) {
            plugin.reload("borders.yml");
            ctx.reply("borders.zone-created", "name", name);
        } else {
            ctx.reply("admin.config.write-failed", "file", file.name());
        }
    }

    private void delete(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String name = ctx.arg(0);
        ConfigFile file = plugin.configs().file("borders.yml");
        if (name == null || file.rawValue("zones." + name) == null) {
            ctx.reply("borders.zone-unknown", "name", String.valueOf(name));
            return;
        }
        plugin.configs().newBackupSession();
        if (file.write("zones." + name, null)) {
            plugin.reload("borders.yml");
            ctx.reply("borders.zone-deleted", "name", name);
        }
    }

    private void list(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        List<BorderSettings.Zone> zones = settings().zones();
        ctx.reply("borders.zone-list-header", "count", zones.size());
        for (BorderSettings.Zone z : zones) {
            ctx.reply("borders.zone-list-entry", "name", z.name(), "world", z.world(),
                    "min", z.minX() + "," + z.minY() + "," + z.minZ(), "max", z.maxX() + "," + z.maxY() + "," + z.maxZ());
        }
    }
}
