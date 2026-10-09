package dev.ultimatecombat.modules.combatlog;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.event.CombatLogEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.pending.PendingActions;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.CommandRunner;
import dev.ultimatecombat.core.util.Locations;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combat.CombatTag;
import dev.ultimatecombat.modules.ghosts.GhostModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punishes players who disconnect while combat tagged (combatlog.yml).
 */
public final class CombatLogModule extends Module<CombatLogSettings> {

    static final String PENDING_REJOIN = "COMBATLOG_REJOIN";

    private final Map<UUID, String> kicks = new ConcurrentHashMap<>();
    private final Map<UUID, CombatLogSettings.Profile> deathProfiles = new ConcurrentHashMap<>();

    public CombatLogModule(UltimateCombatPlugin plugin) {
        super(plugin, "combat-log", "combatlog.yml");
    }

    @Override
    protected CombatLogSettings parse(ConfigReader reader) {
        return CombatLogSettings.parse(reader);
    }

    @Override
    protected void onEnable() {
        plugin.pending().handler(PENDING_REJOIN, this::applyRejoin);
    }

    @Override
    protected void onDisable() {
        plugin.pending().removeHandler(PENDING_REJOIN);
        kicks.clear();
        deathProfiles.clear();
    }

    // ---------------------------------------------------------------------------------- quit

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        @SuppressWarnings("deprecation")
        String reason = event.getReason();
        kicks.put(event.getPlayer().getUniqueId(), reason == null ? "" : reason);
    }

    private String quitReason(PlayerQuitEvent event) {
        String kick = kicks.remove(event.getPlayer().getUniqueId());
        String paperReason = "";
        try {
            // Paper only; compared by name so Spigot never loads the enum.
            paperReason = event.getReason().name();
        } catch (LinkageError e) {
            // Spigot: rely on the kick event
        }
        if (paperReason.equals("KICKED")) {
            return "kick:" + (kick == null ? "" : kick);
        }
        if (paperReason.equals("TIMED_OUT") || paperReason.equals("ERRONEOUS_STATE")) {
            return "timeout";
        }
        return kick != null ? "kick:" + kick : "quit";
    }

    private static boolean serverStopping() {
        try {
            return Bukkit.isStopping();
        } catch (NoSuchMethodError e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String reason = quitReason(event);
        CombatModule combat = plugin.active(CombatModule.class);
        if (combat == null || serverStopping()) {
            return;
        }
        CombatTag tag = combat.tag(player);
        if (tag == null || tag.remainingMillis() <= 0) {
            return;
        }
        CombatLogSettings s = settings(player.getWorld());
        if (tag.remainingMillis() < s.minimumTagMillis()) {
            return;
        }
        if (reason.startsWith("kick:")) {
            if (!s.punishKick()) {
                return;
            }
            String text = reason.substring(5).toLowerCase(Locale.ROOT);
            for (String ignored : s.ignoredKickReasons()) {
                if (!ignored.isEmpty() && text.contains(ignored)) {
                    return;
                }
            }
        } else if (reason.equals("timeout") && !s.punishTimeout() || reason.equals("quit") && !s.punishQuit()) {
            return;
        }
        if (!s.worlds().allows(player.getWorld()) || plugin.bypasses(player, "combat-log")) {
            return;
        }
        if (!s.excludedRegions().isEmpty()) {
            for (String region : plugin.integrations().regionsAt(player.getLocation())) {
                if (s.excludedRegions().contains(region)) {
                    return;
                }
            }
        }
        CombatLogSettings.Profile profile = tag.type() == CombatType.PVP ? s.pvp() : s.pve();
        String simpleReason = reason.startsWith("kick:") ? "kick" : reason;
        CombatLogEvent apiEvent = new CombatLogEvent(player, tag.type(), profile.name(), simpleReason);
        Bukkit.getPluginManager().callEvent(apiEvent);
        if (apiEvent.isCancelled()) {
            return;
        }
        Player opponent = tag.lastOpponent() == null ? null : Bukkit.getPlayer(tag.lastOpponent());
        punish(player, profile, tag, opponent, s, event);
    }

    private void punish(Player player, CombatLogSettings.Profile profile, CombatTag tag, Player opponent, CombatLogSettings s,
                        PlayerQuitEvent event) {
        Location loc = player.getLocation();
        Object[] ph = placeholders(player, opponent, loc, tag);
        plugin.debug().log("combatlog", player.getName() + " logged out in " + tag.type() + " combat; profile " + profile.name());
        plugin.debug().count("combatlog.punishments");

        if (s.customQuitMessage()) {
            String msg = plugin.messages().text("combatlog.quit-message", ph);
            event.setQuitMessage(msg.isEmpty() ? null : msg);
        }
        if (profile.broadcast()) {
            plugin.messages().broadcast(profile.broadcastLocation() ? "combatlog.broadcast-location" : "combatlog.broadcast", ph);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != player) {
                    plugin.effects().playSound(p, profile.broadcastSound());
                }
            }
        }
        if (s.notifyOpponents()) {
            for (UUID id : tag.opponents()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p != player) {
                    plugin.messages().send(p, "combatlog.opponent-logged", ph);
                }
            }
        }
        applyMoneyPenalty(player, profile, opponent, ph);
        CommandRunner.console(plugin, player, profile.consoleCommands(), ph);
        recordHistory(player, loc, tag, opponent, profile);

        boolean ghostSpawned = false;
        GhostModule ghosts = plugin.active(GhostModule.class);
        if (profile.useGhost() && ghosts != null) {
            ghostSpawned = ghosts.spawn(player, profile, opponent);
        }
        if (!ghostSpawned) {
            if (profile.killOnRejoin()) {
                // Death is applied when the player comes back.
            } else {
                switch (profile.killMode()) {
                    case DEATH -> killNow(player, profile);
                    case DROP_AND_CLEAR -> dropAndClear(player, profile, opponent);
                    case NONE -> {
                        // Only messages, money and commands.
                    }
                }
            }
        }
        if (profile.rejoinMessage() || profile.killOnRejoin() || !profile.rejoinConsoleCommands().isEmpty()
                || !profile.rejoinPlayerCommands().isEmpty() || profile.rejoinBroadcast()) {
            String payload = profile.name() + "|" + System.currentTimeMillis() + "|" + (opponent == null ? "" : opponent.getName())
                    + "|" + (profile.killOnRejoin() && !ghostSpawned ? "kill" : "") + "|" + Locations.encode(loc);
            plugin.pending().add(player.getUniqueId(), PENDING_REJOIN, payload).exceptionally(t -> {
                plugin.getLogger().warning("Could not store the rejoin punishment of " + player.getName() + ": " + Database.rootMessage(t));
                return -1L;
            });
        }
    }

    private Object[] placeholders(Player player, Player opponent, Location loc, CombatTag tag) {
        return new Object[]{"player", player.getName(), "opponent", opponent == null ? tag.lastOpponentName() : opponent.getName(),
                "world", loc.getWorld() == null ? "?" : loc.getWorld().getName(), "x", loc.getBlockX(), "y", loc.getBlockY(),
                "z", loc.getBlockZ(), "type", tag.type().name(), "time", Math.max(1, tag.remainingMillis() / 1000)};
    }

    private void applyMoneyPenalty(Player player, CombatLogSettings.Profile profile, Player opponent, Object[] ph) {
        if (!profile.moneyEnabled() || !plugin.economy().available()) {
            return;
        }
        double amount = plugin.economy().computePenalty(player, profile.moneyPercent(),
                profile.moneyPercent() ? profile.moneyAmount() / 100.0 : profile.moneyAmount(), profile.moneyMaximum());
        if (amount <= 0) {
            return;
        }
        boolean ok;
        if (profile.moneyToOpponent() && opponent != null) {
            ok = plugin.economy().transfer(player, opponent, amount, "combat log penalty");
            if (ok) {
                plugin.messages().send(opponent, "combatlog.money-received", "amount", plugin.economy().format(amount),
                        "player", player.getName());
            }
        } else {
            ok = plugin.economy().withdraw(player, amount, "combat log penalty");
        }
        if (ok) {
            plugin.debug().log("combatlog", "Money penalty " + amount + " for " + player.getName());
        }
    }

    private void killNow(Player player, CombatLogSettings.Profile profile) {
        if (player.isDead() || player.getHealth() <= 0) {
            return;
        }
        deathProfiles.put(player.getUniqueId(), profile);
        plugin.kills().markCombatLogDeath(player.getUniqueId(), profile.creditKill(), profile.countAsDeath());
        player.setHealth(0.0);
    }

    private void dropAndClear(Player player, CombatLogSettings.Profile profile, Player opponent) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        PlayerInventory inv = player.getInventory();
        if (profile.dropInventory() && world != null) {
            for (ItemStack item : inv.getContents()) {
                if (item != null && !item.getType().isAir()) {
                    world.dropItemNaturally(loc, item.clone());
                }
            }
        }
        inv.clear();
        int level = player.getLevel();
        if (profile.dropExperience() && world != null) {
            int xp = Math.min(level * 7, 100);
            if (xp > 0) {
                world.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(xp));
            }
        }
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        if (profile.countAsDeath()) {
            plugin.kills().processSynthetic(player, opponent, profile.creditKill());
        }
    }

    // ---------------------------------------------------------------------------------- death tweaks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player p && plugin.kills().isCombatLogDeathPending(p.getUniqueId())) {
            event.setCancelled(true); // totems never save a combat logger
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        CombatLogSettings.Profile profile = deathProfiles.remove(player.getUniqueId());
        if (profile == null) {
            return;
        }
        if (profile.ignoreKeepInventory() && event.getKeepInventory()) {
            event.setKeepInventory(false);
            event.getDrops().clear();
            if (profile.dropInventory()) {
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        event.getDrops().add(item.clone());
                    }
                }
            }
        } else if (!profile.dropInventory()) {
            event.getDrops().clear();
        }
        if (profile.ignoreKeepInventory()) {
            event.setKeepLevel(false);
        }
        event.setDroppedExp(profile.dropExperience() ? Math.min(player.getLevel() * 7, 100) : 0);
        @SuppressWarnings("deprecation")
        String msg = plugin.messages().text("combatlog.death-message", "player", player.getName());
        if (!msg.isEmpty()) {
            event.setDeathMessage(msg);
        }
    }

    // ---------------------------------------------------------------------------------- rejoin

    private void applyRejoin(Player player, PendingActions.Action action) {
        String[] parts = action.payload().split("\\|", -1);
        String profileName = parts.length > 0 ? parts[0] : "pvp";
        long at = parts.length > 1 ? parseLong(parts[1]) : action.createdAt();
        String opponent = parts.length > 2 ? parts[2] : "";
        boolean kill = parts.length > 3 && parts[3].equals("kill");
        CombatLogSettings s = settings(player.getWorld());
        CombatLogSettings.Profile profile = profileName.equals("pve") ? s.pve() : s.pvp();
        String ago = plugin.messages().duration(System.currentTimeMillis() - at);
        Object[] ph = {"player", player.getName(), "opponent", opponent.isEmpty() ? plugin.messages().settings().none() : opponent,
                "ago", ago};
        if (profile.rejoinMessage()) {
            plugin.messages().send(player, kill ? "combatlog.rejoin-killed" : "combatlog.rejoin", ph);
        }
        if (profile.rejoinBroadcast()) {
            plugin.messages().broadcast(p -> p != player, "combatlog.rejoin-broadcast", ph);
        }
        CommandRunner.console(plugin, player, profile.rejoinConsoleCommands(), ph);
        CommandRunner.player(plugin, player, profile.rejoinPlayerCommands(), ph);
        if (kill && !player.isDead()) {
            deathProfiles.put(player.getUniqueId(), profile);
            plugin.kills().markCombatLogDeath(player.getUniqueId(), false, true);
            player.setHealth(0.0);
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    // ---------------------------------------------------------------------------------- history

    private void recordHistory(Player player, Location loc, CombatTag tag, Player opponent, CombatLogSettings.Profile profile) {
        if (!settings().history() || !plugin.storageReady()) {
            return;
        }
        Database db = plugin.database();
        String world = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        String opp = opponent == null ? tag.lastOpponentName() : opponent.getName();
        String punishment = profile.useGhost() ? "ghost" : profile.killOnRejoin() ? "kill-on-rejoin" : profile.killMode().name().toLowerCase(Locale.ROOT);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("combat_log_history")
                    + " (data_group, uuid, name, created_at, world, x, y, z, combat_type, opponent, punishment) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, db.group());
                ps.setString(2, player.getUniqueId().toString());
                ps.setString(3, player.getName());
                ps.setLong(4, System.currentTimeMillis());
                ps.setString(5, world);
                ps.setDouble(6, loc.getX());
                ps.setDouble(7, loc.getY());
                ps.setDouble(8, loc.getZ());
                ps.setString(9, tag.type().name());
                ps.setString(10, opp == null ? "" : opp);
                ps.setString(11, punishment);
                ps.executeUpdate();
            }
        });
    }

    // ---------------------------------------------------------------------------------- commands

    @Override
    public void registerCommands() {
        UCCommand group = new UCCommand("combatlog", "ultimatecombat.combatlog.admin", false) {
            @Override
            public String usage() {
                return "combatlog <history|pending|clear> <player>";
            }
        };
        group.child(new SimpleCommand("history", "history <player>", "ultimatecombat.combatlog.admin", false,
                this::history, ctx -> UCCommand.onlineNames()));
        group.child(new SimpleCommand("pending", "pending <player>", "ultimatecombat.combatlog.admin", false,
                ctx -> pendingList(ctx, false), ctx -> UCCommand.onlineNames()));
        group.child(new SimpleCommand("clear", "clear <player>", "ultimatecombat.combatlog.admin", false,
                ctx -> pendingList(ctx, true), ctx -> UCCommand.onlineNames()));
        plugin.commands().root().child(group);
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    private void history(CommandContext ctx) {
        if (!guard(ctx) || ctx.size() < 1) {
            if (isEnabled()) {
                ctx.reply("general.usage", "usage", "/uc combatlog history <player>");
            }
            return;
        }
        if (!plugin.storageReady()) {
            ctx.reply("general.storage-unavailable");
            return;
        }
        OfflinePlayer target = offline(ctx.arg(0));
        Database db = plugin.database();
        db.supply(c -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT created_at, world, x, y, z, combat_type, opponent, punishment FROM "
                    + db.t("combat_log_history") + " WHERE data_group = ? AND uuid = ? ORDER BY created_at DESC LIMIT 10")) {
                ps.setString(1, db.group());
                ps.setString(2, target.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    while (rs.next()) {
                        rows.add(new String[]{f.format(new Date(rs.getLong(1))), rs.getString(2),
                                (int) rs.getDouble(3) + ", " + (int) rs.getDouble(4) + ", " + (int) rs.getDouble(5),
                                rs.getString(6), rs.getString(7), rs.getString(8)});
                    }
                }
            }
            return rows;
        }).thenAccept(rows -> {
            ctx.reply("combatlog.history.header", "player", ctx.arg(0), "count", rows.size());
            for (String[] r : rows) {
                ctx.reply("combatlog.history.entry", "date", r[0], "world", r[1], "location", r[2], "type", r[3],
                        "opponent", r[4], "punishment", r[5]);
            }
        });
    }

    private void pendingList(CommandContext ctx, boolean clear) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc combatlog " + (clear ? "clear" : "pending") + " <player>");
            return;
        }
        OfflinePlayer target = offline(ctx.arg(0));
        plugin.pending().pending(target.getUniqueId()).thenAccept(actions -> {
            int count = 0;
            for (PendingActions.Action a : actions) {
                if (!a.type().equals(PENDING_REJOIN) && !a.type().startsWith("GHOST")) {
                    continue;
                }
                count++;
                if (clear) {
                    plugin.pending().cancel(a.id());
                } else {
                    ctx.reply("combatlog.pending.entry", "type", a.type(), "date",
                            new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(a.createdAt())));
                }
            }
            ctx.reply(clear ? "combatlog.pending.cleared" : "combatlog.pending.total", "player", ctx.arg(0), "count", count);
        });
    }
}
