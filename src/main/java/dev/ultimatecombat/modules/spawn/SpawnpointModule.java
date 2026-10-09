package dev.ultimatecombat.modules.spawn;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.gui.Menu;
import dev.ultimatecombat.core.gui.MenuTemplate;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Named spawn points, respawn rules and respawn protection (spawnpoints.yml).
 */
public final class SpawnpointModule extends Module<SpawnpointModule.Settings> {

    public enum Mode { NEAREST, NEAREST_ACCESSIBLE, RANDOM, FIRST }

    public record Point(String name, String world, double x, double y, double z, float yaw, float pitch, String permission) {
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    public record WorldRule(List<String> points, String fallback) {
    }

    public record Settings(Map<String, Point> points, boolean respawnEnabled, Mode mode, boolean sameWorldOnly, boolean respectBeds,
                           Map<String, WorldRule> worldRules, boolean protectionEnabled, long protectionMillis,
                           boolean protectionEndsOnAttack, String sound, String particle, WorldRules worlds) {
    }

    private final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();

    public SpawnpointModule(UltimateCombatPlugin plugin) {
        super(plugin, "spawnpoints", "spawnpoints.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        Map<String, Point> points = new LinkedHashMap<>();
        for (String name : r.keys("points")) {
            ConfigReader p = r.section("points." + name);
            points.put(name.toLowerCase(Locale.ROOT), new Point(name, p.string("world", "world"), p.decimal("x", 0), p.decimal("y", 64),
                    p.decimal("z", 0), (float) p.decimal("yaw", 0), (float) p.decimal("pitch", 0), p.string("permission", "")));
        }
        Map<String, WorldRule> rules = new HashMap<>();
        for (String world : r.keys("respawn.world-rules")) {
            ConfigReader w = r.section("respawn.world-rules." + world);
            List<String> list = new ArrayList<>();
            for (String s : w.stringList("points")) {
                list.add(s.toLowerCase(Locale.ROOT));
            }
            rules.put(world.toLowerCase(Locale.ROOT), new WorldRule(list, w.choice("fallback", "vanilla", "vanilla", "world-spawn")));
        }
        return new Settings(points,
                r.bool("respawn.enabled", true),
                r.enumValue("respawn.mode", Mode.class, Mode.NEAREST_ACCESSIBLE),
                r.bool("respawn.same-world-only", true),
                r.bool("respawn.respect-beds", true),
                rules,
                r.bool("protection.enabled", true),
                r.durationMillis("protection.duration-seconds", 5000, ConfigReader.TimeUnitHint.SECONDS, 0, 600_000),
                r.bool("protection.ends-on-attack", true),
                r.string("sound", "respawn"),
                r.string("particle", "respawn"),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onDisable() {
        protectedUntil.clear();
    }

    // ------------------------------------------------------------------------------ respawning

    /**
     * Chooses a spawn point for a player near {@code near}, or {@code null} for vanilla behaviour.
     */
    public Location respawnLocation(Player player, Location near) {
        Settings s = settings(near.getWorld());
        WorldRule rule = near.getWorld() == null ? null : s.worldRules().get(near.getWorld().getName().toLowerCase(Locale.ROOT));
        List<Point> candidates = new ArrayList<>();
        for (Point p : s.points().values()) {
            if (rule != null && !rule.points().isEmpty() && !rule.points().contains(p.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Location l = p.location();
            if (l == null) {
                continue;
            }
            if (s.sameWorldOnly() && rule == null && !l.getWorld().equals(near.getWorld())) {
                continue;
            }
            if (s.mode() != Mode.NEAREST && !p.permission().isEmpty() && !player.hasPermission(p.permission())) {
                continue;
            }
            candidates.add(p);
        }
        if (candidates.isEmpty()) {
            if (rule != null && rule.fallback().equals("world-spawn")) {
                return near.getWorld().getSpawnLocation();
            }
            return null;
        }
        return switch (s.mode()) {
            case FIRST -> candidates.get(0).location();
            case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).location();
            case NEAREST, NEAREST_ACCESSIBLE -> {
                Point best = null;
                double bestDist = Double.MAX_VALUE;
                for (Point p : candidates) {
                    Location l = p.location();
                    double d = l.getWorld().equals(near.getWorld()) ? l.distanceSquared(near) : Double.MAX_VALUE / 2;
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
                yield best == null ? null : best.location();
            }
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location death = player.getLocation();
        Settings s = settings(death.getWorld());
        if (s.respawnEnabled() && s.worlds().allows(death.getWorld()) && !(s.respectBeds() && (event.isBedSpawn() || event.isAnchorSpawn()))) {
            Location target = respawnLocation(player, death);
            if (target != null) {
                event.setRespawnLocation(target);
            }
        }
        if (s.protectionEnabled() && s.protectionMillis() > 0) {
            protectedUntil.put(player.getUniqueId(), System.currentTimeMillis() + s.protectionMillis());
            plugin.scheduler().runEntityLater(player, () -> plugin.messages().send(player, "spawnpoints.protected",
                    "time", s.protectionMillis() / 1000), 1L);
        }
        plugin.scheduler().runEntityLater(player, () -> {
            plugin.effects().playSound(player, s.sound());
            plugin.effects().spawnParticle(player.getLocation(), s.particle());
        }, 2L);
    }

    public boolean isProtected(Player player) {
        Long until = protectedUntil.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            protectedUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (IntegrationManager.isSimulating()) {
            return;
        }
        if (event.getEntity() instanceof Player victim && isProtected(victim)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = plugin.kills().attribution().resolve(byEntity.getDamager());
            if (attacker != null && isProtected(attacker) && settings(attacker.getWorld()).protectionEndsOnAttack()) {
                protectedUntil.remove(attacker.getUniqueId());
                plugin.messages().send(attacker, "spawnpoints.protection-ended");
            }
        }
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        String perm = "ultimatecombat.spawnpoint.admin";
        UCCommand group = new UCCommand("spawnpoint", perm, false, "spawnpoints") {
            @Override
            public String usage() {
                return "spawnpoint <create|delete|list|tp|menu>";
            }
        };
        group.child(new SimpleCommand("create", "create <name> [permission]", perm, true, this::create));
        group.child(new SimpleCommand("delete", "delete <name>", perm, false, this::delete, ctx -> names()));
        group.child(new SimpleCommand("list", "list", perm, false, this::list));
        group.child(new SimpleCommand("tp", "tp <name>", perm, true, this::teleport, ctx -> names()));
        group.child(new SimpleCommand("menu", "menu", perm, true, ctx -> {
            if (guard(ctx)) {
                openMenu(ctx.player(), 0);
            }
        }));
        plugin.commands().root().child(group);
    }

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (Point p : settings().points().values()) {
            out.add(p.name());
        }
        return out;
    }

    private void create(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String name = ctx.arg(0);
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) {
            ctx.reply("general.usage", "usage", "/uc spawnpoint create <name> [permission]");
            return;
        }
        Location l = ctx.player().getLocation();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("world", l.getWorld().getName());
        point.put("x", round(l.getX()));
        point.put("y", round(l.getY()));
        point.put("z", round(l.getZ()));
        point.put("yaw", round(l.getYaw()));
        point.put("pitch", round(l.getPitch()));
        point.put("permission", ctx.argOr(1, ""));
        ConfigFile file = plugin.configs().file("spawnpoints.yml");
        plugin.configs().newBackupSession();
        if (file.write("points." + name, point)) {
            plugin.reload("spawnpoints.yml");
            ctx.reply("spawnpoints.created", "name", name);
        } else {
            ctx.reply("admin.config.write-failed", "file", file.name());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void delete(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Point p = ctx.arg(0) == null ? null : settings().points().get(ctx.arg(0).toLowerCase(Locale.ROOT));
        if (p == null) {
            ctx.reply("spawnpoints.unknown", "name", ctx.argOr(0, "?"));
            return;
        }
        ConfigFile file = plugin.configs().file("spawnpoints.yml");
        plugin.configs().newBackupSession();
        if (file.write("points." + p.name(), null)) {
            plugin.reload("spawnpoints.yml");
            ctx.reply("spawnpoints.deleted", "name", p.name());
        }
    }

    private void list(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        ctx.reply("spawnpoints.list-header", "count", settings().points().size());
        for (Point p : settings().points().values()) {
            ctx.reply("spawnpoints.list-entry", "name", p.name(), "world", p.world(), "x", (int) p.x(), "y", (int) p.y(), "z", (int) p.z(),
                    "permission", p.permission().isEmpty() ? plugin.messages().settings().none() : p.permission());
        }
    }

    private void teleport(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Point p = ctx.arg(0) == null ? null : settings().points().get(ctx.arg(0).toLowerCase(Locale.ROOT));
        Location l = p == null ? null : p.location();
        if (l == null) {
            ctx.reply("spawnpoints.unknown", "name", ctx.argOr(0, "?"));
            return;
        }
        plugin.scheduler().teleport(ctx.player(), l);
        ctx.reply("spawnpoints.teleported", "name", p.name());
    }

    private void openMenu(Player viewer, int page) {
        MenuTemplate template = plugin.menus().template("spawnpoints");
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> plugin.scheduler().runEntity(p, p::closeInventory));
        Menu menu = plugin.menus().build(viewer, "spawnpoints", handlers);
        List<Point> points = new ArrayList<>(settings().points().values());
        plugin.menus().paginate(menu, viewer, template, points, page,
                p -> new ItemBuilder(Material.RED_BED)
                        .name(plugin.messages().text(viewer, "spawnpoints.menu-item-name", "name", p.name()))
                        .lore(plugin.messages().lines(viewer, "spawnpoints.menu-item-lore", "world", p.world(), "x", (int) p.x(),
                                "y", (int) p.y(), "z", (int) p.z(),
                                "permission", p.permission().isEmpty() ? plugin.messages().settings().none() : p.permission()))
                        .build(),
                (p, click) -> {
                    if (click == ClickType.LEFT) {
                        Location l = p.location();
                        if (l != null) {
                            viewer.closeInventory();
                            plugin.scheduler().teleport(viewer, l);
                        }
                    } else if (click == ClickType.SHIFT_RIGHT) {
                        viewer.performCommand("uc spawnpoint delete " + p.name());
                        plugin.scheduler().runEntityLater(viewer, () -> openMenu(viewer, page), 2L);
                    }
                },
                newPage -> openMenu(viewer, newPage));
        menu.open(viewer);
    }
}
