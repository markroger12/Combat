package dev.ultimatecombat.modules.grace;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.GraceChangeEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * New player protection (grace.yml).
 */
public final class GraceModule extends Module<GraceModule.Settings> {

    private final Map<UUID, Long> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> escapeCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> messageGuard = new ConcurrentHashMap<>();
    private ScheduledTask ticker;
    private boolean hookRegistered;

    /**
     * grace.yml snapshot.
     */
    public record Settings(boolean onFirstJoin, long durationMillis, boolean realTime, boolean protectPvp, boolean protectAllDamage,
                           boolean protectFire, boolean protectMobs, boolean endOnAttack, List<String> blockedCommands,
                           boolean blockItemPickup, WorldRules worlds, boolean pauseInDisabledWorlds, Set<String> excludedRegions,
                           boolean voluntaryRemoval, boolean confirmRemoval, long confirmWindowMillis, boolean escapeEnabled,
                           long escapeCooldownMillis, String escapeCommand, List<Long> warningsMillis, boolean actionbar,
                           String endSound, String endParticle, String startSound) {
    }

    public GraceModule(UltimateCombatPlugin plugin) {
        super(plugin, "grace", "grace.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        List<String> blocked = new ArrayList<>();
        for (String c : r.stringList("blocked-commands")) {
            blocked.add(c.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim());
        }
        List<Long> warnings = new ArrayList<>();
        for (String w : r.stringList("warnings-minutes")) {
            try {
                warnings.add((long) (Double.parseDouble(w) * 60_000));
            } catch (NumberFormatException e) {
                r.warn("warnings-minutes", w, "Not a number (entry ignored)", "minutes such as 5 or 0.5", "entry skipped");
            }
        }
        ConfigReader p = r.section("protection");
        return new Settings(
                r.bool("first-join.enabled", true),
                r.durationMillis("first-join.duration-minutes", 30 * 60_000L, ConfigReader.TimeUnitHint.MINUTES, 1000, 365L * 86_400_000L),
                r.choice("count-mode", "playtime", "playtime", "real-time").equals("real-time"),
                p.bool("pvp", true), p.bool("all-damage", false), p.bool("fire-and-lava", true), p.bool("mobs", false),
                r.bool("end-on-attack", true),
                blocked,
                r.bool("block-item-pickup", false),
                WorldRules.read(r, "worlds"),
                r.bool("pause-in-disabled-worlds", true),
                r.lowerCaseSet("excluded-regions"),
                r.bool("voluntary-removal.enabled", true),
                r.bool("voluntary-removal.confirm", true),
                r.durationMillis("voluntary-removal.confirm-window-seconds", 15_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 600_000),
                r.bool("escape.enabled", false),
                r.durationMillis("escape.cooldown-seconds", 300_000, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.string("escape.command", "spawn"),
                warnings,
                r.bool("actionbar", false),
                r.string("sounds.end", "grace-end"),
                r.string("particles.end", "grace-end"),
                r.string("sounds.start", "grace-start"));
    }

    @Override
    protected void onEnable() {
        if (!hookRegistered) {
            plugin.players().onProfileJoin(this::onProfile);
            hookRegistered = true;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            onProfile(plugin.players().profile(p));
        }
        ticker = plugin.scheduler().runGlobalTimer(this::tick, 20L, 20L);
    }

    @Override
    protected void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        confirmations.clear();
        escapeCooldown.clear();
    }

    // ------------------------------------------------------------------------------ state

    public boolean hasGrace(Player player) {
        PlayerProfile p = plugin.players().online(player.getUniqueId());
        return p != null && p.graceActive() && p.graceRemaining() > 0;
    }

    public long remaining(Player player) {
        PlayerProfile p = plugin.players().online(player.getUniqueId());
        return p == null || !p.graceActive() ? 0 : p.graceRemaining();
    }

    /**
     * @return whether grace currently protects the player at their location
     */
    public boolean protects(Player player) {
        if (!isEnabled() || !hasGrace(player)) {
            return false;
        }
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld())) {
            return false;
        }
        if (!s.excludedRegions().isEmpty()) {
            for (String region : plugin.integrations().regionsAt(player.getLocation())) {
                if (s.excludedRegions().contains(region)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void onProfile(PlayerProfile profile) {
        if (!isEnabled()) {
            return;
        }
        Player player = Bukkit.getPlayer(profile.uuid());
        if (player == null || profile.loadFailed()) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (!profile.graceInitialized()) {
            profile.graceInitialized(true);
            if (s.onFirstJoin() && !plugin.bypasses(player, "grace")) {
                start(player, profile, s.durationMillis(), GraceChangeEvent.Reason.FIRST_JOIN);
            }
            return;
        }
        if (profile.graceActive() && s.realTime() && profile.lastSeen() > 0) {
            // Real-time grace keeps running while offline.
            long offline = Math.max(0, System.currentTimeMillis() - profile.lastSeen());
            profile.graceRemaining(profile.graceRemaining() - offline);
        }
    }

    private void start(Player player, PlayerProfile profile, long millis, GraceChangeEvent.Reason reason) {
        profile.graceRemaining(millis);
        profile.graceActive(true);
        Settings s = settings(player.getWorld());
        plugin.scheduler().executeEntity(player, () -> {
            plugin.messages().send(player, "grace.started", "time", plugin.messages().duration(millis));
            plugin.effects().playSound(player, s.startSound());
        });
        Bukkit.getPluginManager().callEvent(new GraceChangeEvent(player, true, reason));
    }

    public void end(Player player, GraceChangeEvent.Reason reason) {
        PlayerProfile profile = plugin.players().profile(player);
        if (!profile.graceActive()) {
            return;
        }
        profile.graceActive(false);
        profile.graceRemaining(0);
        Settings s = settings(player.getWorld());
        String key = switch (reason) {
            case EXPIRED -> "grace.expired";
            case VOLUNTARY -> "grace.removed-voluntary";
            case ATTACKED_SOMEONE -> "grace.removed-attack";
            case ADMIN_REMOVE -> "grace.removed-admin";
            default -> "grace.expired";
        };
        plugin.scheduler().executeEntity(player, () -> {
            plugin.messages().send(player, key);
            plugin.effects().playSound(player, s.endSound());
            plugin.effects().spawnParticle(player.getLocation(), s.endParticle());
        });
        Bukkit.getPluginManager().callEvent(new GraceChangeEvent(player, false, reason));
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = plugin.players().online(player.getUniqueId());
            if (profile == null || !profile.graceActive()) {
                continue;
            }
            Settings s = settings(player.getWorld());
            boolean paused = !s.realTime() && s.pauseInDisabledWorlds() && !s.worlds().allows(player.getWorld());
            if (paused) {
                continue;
            }
            long before = profile.graceRemaining();
            long after = before - 1000;
            profile.graceRemaining(after);
            if (after <= 0) {
                end(player, GraceChangeEvent.Reason.EXPIRED);
                continue;
            }
            for (long warning : s.warningsMillis()) {
                if (before > warning && after <= warning) {
                    plugin.scheduler().executeEntity(player, () -> plugin.messages().send(player, "grace.warning",
                            "time", plugin.messages().duration(after)));
                }
            }
            if (s.actionbar()) {
                plugin.scheduler().executeEntity(player, () -> plugin.messages().actionBar(player,
                        plugin.messages().text(player, "grace.actionbar", "time", plugin.messages().duration(after))));
            }
        }
    }

    // ------------------------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (IntegrationManager.isSimulating()) {
            return;
        }
        if (event.getEntity() instanceof Player victim && protects(victim)) {
            Settings s = settings(victim.getWorld());
            Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                    ? plugin.kills().attribution().resolve(byEntity.getDamager()) : plugin.kills().attribution().attacker(event);
            boolean block = s.protectAllDamage()
                    || attacker != null && attacker != victim && s.protectPvp()
                    || s.protectFire() && switch (event.getCause()) {
                        case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> true;
                        default -> false;
                    }
                    || s.protectMobs() && event instanceof EntityDamageByEntityEvent && attacker == null;
            if (block) {
                event.setCancelled(true);
                if (attacker != null && attacker != victim && messageAllowed(attacker)) {
                    plugin.messages().send(attacker, "grace.target-protected", "player", victim.getName(),
                            "time", plugin.messages().duration(remaining(victim)));
                }
                return;
            }
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getEntity() instanceof Player victim) {
            Player attacker = plugin.kills().attribution().resolve(byEntity.getDamager());
            if (attacker != null && attacker != victim && hasGrace(attacker) && isEnabled()) {
                Settings s = settings(attacker.getWorld());
                if (s.endOnAttack()) {
                    end(attacker, GraceChangeEvent.Reason.ATTACKED_SOMEONE);
                } else if (s.protectPvp()) {
                    event.setCancelled(true);
                    if (messageAllowed(attacker)) {
                        plugin.messages().send(attacker, "grace.cannot-attack");
                    }
                }
            }
        }
    }

    private boolean messageAllowed(Player player) {
        long now = System.currentTimeMillis();
        Long last = messageGuard.get(player.getUniqueId());
        if (last != null && now - last < 1500) {
            return false;
        }
        messageGuard.put(player.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!hasGrace(player)) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (s.blockedCommands().isEmpty()) {
            return;
        }
        String msg = event.getMessage().toLowerCase(Locale.ROOT).substring(1).trim();
        for (String blocked : s.blockedCommands()) {
            if (msg.equals(blocked) || msg.startsWith(blocked + " ")) {
                event.setCancelled(true);
                plugin.messages().send(player, "grace.command-blocked", "command", "/" + blocked);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && hasGrace(player) && settings(player.getWorld()).blockItemPickup()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        confirmations.remove(id);
        messageGuard.remove(id);
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        SimpleCommand grace = new SimpleCommand("grace", "grace [status|disable|confirm|escape|give|remove|check]",
                "ultimatecombat.grace", false, this::graceCommand, ctx -> switch (ctx.size()) {
                    case 1 -> List.of("status", "disable", "confirm", "escape", "give", "remove", "check");
                    case 2 -> UCCommand.onlineNames();
                    case 3 -> List.of("10m", "30m", "1h", "1d");
                    default -> List.of();
                });
        plugin.commands().root().child(grace);
        plugin.commands().standalone("grace", grace);
    }

    private void graceCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String sub = ctx.argOr(0, "status").toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                if (!ctx.isPlayer()) {
                    ctx.reply("general.player-only");
                    return;
                }
                long left = remaining(ctx.player());
                ctx.reply(left > 0 ? "grace.status-active" : "grace.status-none", "time", plugin.messages().duration(left));
            }
            case "check" -> check(ctx);
            case "disable" -> {
                if (!ctx.isPlayer()) {
                    ctx.reply("general.player-only");
                    return;
                }
                Settings s = settings(ctx.player().getWorld());
                if (!s.voluntaryRemoval()) {
                    ctx.reply("grace.removal-disabled");
                    return;
                }
                if (!hasGrace(ctx.player())) {
                    ctx.reply("grace.status-none");
                    return;
                }
                if (s.confirmRemoval()) {
                    confirmations.put(ctx.player().getUniqueId(), System.currentTimeMillis());
                    ctx.reply("grace.confirm", "seconds", s.confirmWindowMillis() / 1000);
                } else {
                    end(ctx.player(), GraceChangeEvent.Reason.VOLUNTARY);
                }
            }
            case "confirm" -> {
                if (!ctx.isPlayer()) {
                    ctx.reply("general.player-only");
                    return;
                }
                Long at = confirmations.remove(ctx.player().getUniqueId());
                if (at == null || System.currentTimeMillis() - at > settings(ctx.player().getWorld()).confirmWindowMillis()) {
                    ctx.reply("grace.nothing-to-confirm");
                    return;
                }
                end(ctx.player(), GraceChangeEvent.Reason.VOLUNTARY);
            }
            case "escape" -> escape(ctx);
            case "give" -> give(ctx);
            case "remove" -> remove(ctx);
            default -> ctx.reply("general.usage", "usage", "/grace [status|disable|confirm|escape|give|remove|check]");
        }
    }

    private void check(CommandContext ctx) {
        if (!ctx.has("ultimatecombat.grace.check.others")) {
            ctx.reply("general.no-permission");
            return;
        }
        Player target = ctx.size() > 1 ? Bukkit.getPlayerExact(ctx.arg(1)) : null;
        if (target == null) {
            ctx.reply("general.player-not-found", "player", ctx.argOr(1, "?"));
            return;
        }
        long left = remaining(target);
        ctx.reply(left > 0 ? "grace.check-active" : "grace.check-none", "player", target.getName(), "time", plugin.messages().duration(left));
    }

    private void escape(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.reply("general.player-only");
            return;
        }
        Player player = ctx.player();
        Settings s = settings(player.getWorld());
        if (!s.escapeEnabled() || !hasGrace(player)) {
            ctx.reply("grace.escape-unavailable");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = escapeCooldown.get(player.getUniqueId());
        if (last != null && now - last < s.escapeCooldownMillis()) {
            ctx.reply("grace.escape-cooldown", "time", plugin.messages().duration(s.escapeCooldownMillis() - (now - last)));
            return;
        }
        escapeCooldown.put(player.getUniqueId(), now);
        if (s.escapeCommand().isBlank()) {
            plugin.scheduler().teleport(player, player.getWorld().getSpawnLocation());
        } else {
            String cmd = s.escapeCommand().replace("{player}", player.getName());
            plugin.scheduler().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        ctx.reply("grace.escaped");
    }

    @SuppressWarnings("deprecation")
    private void give(CommandContext ctx) {
        if (!ctx.has("ultimatecombat.grace.give")) {
            ctx.reply("general.no-permission");
            return;
        }
        if (ctx.size() < 3) {
            ctx.reply("general.usage", "usage", "/grace give <player> <duration e.g. 30m>");
            return;
        }
        long millis = parseDuration(ctx.arg(2));
        if (millis <= 0) {
            ctx.reply("general.invalid-number", "value", ctx.arg(2), "min", "1s", "max", "365d");
            return;
        }
        Player online = Bukkit.getPlayerExact(ctx.arg(1));
        if (online != null) {
            start(online, plugin.players().profile(online), millis, GraceChangeEvent.Reason.ADMIN_GIVE);
            ctx.reply("grace.given", "player", online.getName(), "time", plugin.messages().duration(millis));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(ctx.arg(1));
        plugin.players().modify(target.getUniqueId(), p -> {
            p.graceRemaining(millis);
            p.graceActive(true);
            p.graceInitialized(true);
        }).thenAccept(ok -> ctx.reply(ok ? "grace.given" : "general.player-not-found", "player", ctx.arg(1),
                "time", plugin.messages().duration(millis)));
    }

    @SuppressWarnings("deprecation")
    private void remove(CommandContext ctx) {
        if (!ctx.has("ultimatecombat.grace.remove")) {
            ctx.reply("general.no-permission");
            return;
        }
        if (ctx.size() < 2) {
            ctx.reply("general.usage", "usage", "/grace remove <player>");
            return;
        }
        Player online = Bukkit.getPlayerExact(ctx.arg(1));
        if (online != null) {
            end(online, GraceChangeEvent.Reason.ADMIN_REMOVE);
            ctx.reply("grace.removed", "player", online.getName());
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(ctx.arg(1));
        plugin.players().modify(target.getUniqueId(), p -> {
            p.graceActive(false);
            p.graceRemaining(0);
        }).thenAccept(ok -> ctx.reply(ok ? "grace.removed" : "general.player-not-found", "player", ctx.arg(1)));
    }

    static long parseDuration(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        long total = 0;
        StringBuilder num = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                num.append(c);
                continue;
            }
            if (num.isEmpty()) {
                return -1;
            }
            double v = Double.parseDouble(num.toString());
            num.setLength(0);
            total += (long) (v * switch (c) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1L;
            });
        }
        if (!num.isEmpty()) {
            total += (long) (Double.parseDouble(num.toString()) * 60_000L); // plain numbers are minutes
        }
        return total;
    }
}
