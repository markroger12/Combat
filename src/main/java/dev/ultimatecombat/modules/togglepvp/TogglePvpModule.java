package dev.ultimatecombat.modules.togglepvp;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatUntagEvent;
import dev.ultimatecombat.api.event.PvPToggleEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player PvP toggle (pvp.yml).
 */
public final class TogglePvpModule extends Module<TogglePvpModule.Settings> {

    private final Map<UUID, Long> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> messageGuard = new ConcurrentHashMap<>();

    /**
     * pvp.yml snapshot.
     */
    public record Settings(boolean defaultState, long cooldownMillis, long afterCombatMillis, boolean blockInCombat,
                           boolean confirmDisable, long confirmWindowMillis, Map<String, Boolean> forcedWorlds, WorldRules worlds,
                           boolean protectIndirect, double indirectRadius, boolean blockRodPull, String soundOn, String soundOff,
                           boolean attackerMessage) {
    }

    public TogglePvpModule(UltimateCombatPlugin plugin) {
        super(plugin, "toggle-pvp", "pvp.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        Map<String, Boolean> forced = new HashMap<>();
        for (String world : r.keys("forced-worlds")) {
            forced.put(world.toLowerCase(Locale.ROOT), r.bool("forced-worlds." + world, true));
        }
        return new Settings(
                r.bool("default-state", true),
                r.durationMillis("toggle-cooldown-seconds", 30_000, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.durationMillis("cooldown-after-combat-seconds", 60_000, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.bool("block-while-in-combat", true),
                r.bool("confirm-disable.enabled", true),
                r.durationMillis("confirm-disable.window-seconds", 15_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 600_000),
                forced,
                WorldRules.read(r, "worlds"),
                r.bool("indirect-protection.enabled", true),
                r.decimal("indirect-protection.radius", 5, 0, 32),
                r.bool("block-fishing-rod-pull", true),
                r.string("sounds.enabled", "pvp-enabled"),
                r.string("sounds.disabled", "pvp-disabled"),
                r.bool("attacker-message", true));
    }

    @Override
    protected void onDisable() {
        confirmations.clear();
        lastCombat.clear();
        messageGuard.clear();
    }

    // ---------------------------------------------------------------------------- state

    /**
     * @return whether the player can fight other players right now
     */
    public boolean pvpEnabled(Player player) {
        Settings s = settings(player.getWorld());
        Boolean forcedWorld = s.forcedWorlds().get(player.getWorld().getName().toLowerCase(Locale.ROOT));
        if (forcedWorld != null) {
            return forcedWorld;
        }
        PlayerProfile profile = plugin.players().profile(player);
        if (profile.pvpForced() != null) {
            return profile.pvpForced();
        }
        if (!s.worlds().allows(player.getWorld())) {
            return true;
        }
        Boolean own = profile.pvpEnabled();
        return own == null ? s.defaultState() : own;
    }

    private boolean guardMessage(Player player) {
        long now = System.currentTimeMillis();
        Long last = messageGuard.get(player.getUniqueId());
        if (last != null && now - last < 1500) {
            return false;
        }
        messageGuard.put(player.getUniqueId(), now);
        return true;
    }

    /**
     * @return {@code true} when the attack must be blocked (and tells the attacker why)
     */
    private boolean blocked(Player attacker, Player victim) {
        if (attacker == victim || victim.hasMetadata("NPC")) {
            return false;
        }
        if (!pvpEnabled(attacker)) {
            if (settings(attacker.getWorld()).attackerMessage() && guardMessage(attacker)) {
                plugin.messages().send(attacker, "pvp.you-disabled");
            }
            return true;
        }
        if (!pvpEnabled(victim)) {
            if (settings(attacker.getWorld()).attackerMessage() && guardMessage(attacker)) {
                plugin.messages().send(attacker, "pvp.target-disabled", "player", victim.getName());
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        if (attacker != null && blocked(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIndirectDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA, BLOCK_EXPLOSION, POISON, WITHER -> {
                Player attacker = plugin.kills().attribution().attacker(event);
                if (attacker != null && attacker != victim && (!pvpEnabled(attacker) || !pvpEnabled(victim))) {
                    event.setCancelled(true);
                }
            }
            default -> {
                // not player-attributable
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower)) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && victim != thrower && (!pvpEnabled(thrower) || !pvpEnabled(victim))
                    && !isBeneficial(event)) {
                event.setIntensity(victim, 0);
            }
        }
    }

    private static boolean isBeneficial(PotionSplashEvent event) {
        for (var effect : event.getPotion().getEffects()) {
            String key = effect.getType().getKey().getKey();
            if (List.of("poison", "wither", "slowness", "weakness", "instant_damage", "blindness", "hunger", "nausea",
                    "mining_fatigue", "levitation", "darkness").contains(key)) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof Player caught
                && settings(caught.getWorld()).blockRodPull() && blocked(event.getPlayer(), caught)) {
            event.setCancelled(true);
        }
    }

    private Player protectedNear(Player placer, Location loc) {
        Settings s = settings(loc.getWorld());
        if (!s.protectIndirect() || s.indirectRadius() <= 0) {
            return null;
        }
        double r2 = s.indirectRadius() * s.indirectRadius();
        for (Player p : loc.getWorld().getPlayers()) {
            if (p != placer && p.getLocation().distanceSquared(loc) <= r2 && (!pvpEnabled(p) || !pvpEnabled(placer))
                    && !p.hasMetadata("NPC")) {
                return p;
            }
        }
        return null;
    }

    private void denyIndirect(Player placer, Player near, org.bukkit.event.Cancellable event) {
        event.setCancelled(true);
        if (guardMessage(placer)) {
            plugin.messages().send(placer, "pvp.indirect-blocked", "player", near.getName());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.LAVA_BUCKET) {
            return;
        }
        Player near = protectedNear(event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation());
        if (near != null) {
            denyIndirect(event.getPlayer(), near, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player placer = event.getPlayer();
        if (placer == null) {
            return;
        }
        Player near = protectedNear(placer, event.getBlock().getLocation());
        if (near != null) {
            denyIndirect(placer, near, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (type != Material.FIRE && type != Material.SOUL_FIRE && type != Material.TNT && type != Material.LAVA) {
            return;
        }
        Player near = protectedNear(event.getPlayer(), event.getBlockPlaced().getLocation());
        if (near != null) {
            denyIndirect(event.getPlayer(), near, event);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onExplosiveUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Material type = event.getClickedBlock().getType();
        boolean bed = type.name().endsWith("_BED") && event.getClickedBlock().getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL;
        boolean anchor = type == Material.RESPAWN_ANCHOR && event.getClickedBlock().getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER;
        if (!bed && !anchor) {
            return;
        }
        Player near = protectedNear(event.getPlayer(), event.getClickedBlock().getLocation());
        if (near != null) {
            event.setCancelled(true);
            if (guardMessage(event.getPlayer())) {
                plugin.messages().send(event.getPlayer(), "pvp.indirect-blocked", "player", near.getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(CombatUntagEvent event) {
        lastCombat.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        confirmations.remove(id);
        messageGuard.remove(id);
    }

    // ---------------------------------------------------------------------------- toggling

    /**
     * Changes a player's own PvP preference after every rule was checked.
     *
     * @return whether the state was changed
     */
    public boolean set(Player player, boolean enabled, boolean byAdmin) {
        PvPToggleEvent event = new PvPToggleEvent(player, enabled, byAdmin);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        PlayerProfile profile = plugin.players().profile(player);
        profile.pvpEnabled(enabled);
        Settings s = settings(player.getWorld());
        if (!byAdmin) {
            profile.pvpCooldownUntil(System.currentTimeMillis() + s.cooldownMillis());
        }
        plugin.effects().playSound(player, enabled ? s.soundOn() : s.soundOff());
        plugin.sync().publish("pvp-toggle", player.getUniqueId().toString(), Boolean.toString(enabled));
        return true;
    }

    @Override
    public void registerCommands() {
        SimpleCommand toggle = new SimpleCommand("togglepvp", "togglepvp [on|off|confirm|status]", "ultimatecombat.togglepvp", true,
                this::toggleCommand, ctx -> List.of("on", "off", "confirm", "status"));
        plugin.commands().root().child(toggle);
        plugin.commands().standalone("togglepvp", toggle);

        UCCommand admin = new SimpleCommand("pvp", "pvp <player> <on|off|reset|force-on|force-off|unforce|status>",
                "ultimatecombat.togglepvp.admin", false, this::adminCommand, ctx -> switch (ctx.size()) {
                    case 1 -> UCCommand.onlineNames();
                    case 2 -> List.of("on", "off", "reset", "force-on", "force-off", "unforce", "status");
                    default -> List.of();
                });
        plugin.commands().root().child(admin);
    }

    private void toggleCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player player = ctx.player();
        Settings s = settings(player.getWorld());
        PlayerProfile profile = plugin.players().profile(player);
        if (ctx.size() == 0) {
            // plain /togglepvp switches the state
            requestChange(ctx, player, s, profile, !pvpEnabled(player));
            return;
        }
        String arg = ctx.arg(0).toLowerCase(Locale.ROOT);
        switch (arg) {
            case "on" -> requestChange(ctx, player, s, profile, true);
            case "off" -> requestChange(ctx, player, s, profile, false);
            case "confirm" -> {
                Long at = confirmations.remove(player.getUniqueId());
                if (at == null || System.currentTimeMillis() - at > s.confirmWindowMillis()) {
                    ctx.reply("pvp.nothing-to-confirm");
                    return;
                }
                apply(ctx, player, false);
            }
            default -> ctx.reply("pvp.status", "state", plugin.messages().bool(pvpEnabled(player)));
        }
    }

    private void requestChange(CommandContext ctx, Player player, Settings s, PlayerProfile profile, boolean target) {
        Boolean forcedWorld = s.forcedWorlds().get(player.getWorld().getName().toLowerCase(Locale.ROOT));
        if (forcedWorld != null || profile.pvpForced() != null) {
            ctx.reply("pvp.forced", "state", plugin.messages().bool(forcedWorld != null ? forcedWorld : profile.pvpForced()));
            return;
        }
        if (!s.worlds().allows(player.getWorld())) {
            ctx.reply("pvp.world-not-allowed");
            return;
        }
        if (target == pvpEnabled(player)) {
            ctx.reply(target ? "pvp.already-enabled" : "pvp.already-disabled");
            return;
        }
        if (!plugin.bypasses(player, "togglepvp-cooldown")) {
            CombatModule combat = plugin.active(CombatModule.class);
            if (s.blockInCombat() && combat != null && combat.isTagged(player)) {
                ctx.reply("pvp.in-combat");
                return;
            }
            long now = System.currentTimeMillis();
            if (profile.pvpCooldownUntil() > now) {
                ctx.reply("pvp.cooldown", "time", plugin.messages().duration(profile.pvpCooldownUntil() - now));
                return;
            }
            Long combatEnd = lastCombat.get(player.getUniqueId());
            if (!target && combatEnd != null && now - combatEnd < s.afterCombatMillis()) {
                ctx.reply("pvp.recent-combat", "time", plugin.messages().duration(s.afterCombatMillis() - (now - combatEnd)));
                return;
            }
        }
        if (!target && s.confirmDisable()) {
            confirmations.put(player.getUniqueId(), System.currentTimeMillis());
            ctx.reply("pvp.confirm", "seconds", s.confirmWindowMillis() / 1000);
            return;
        }
        apply(ctx, player, target);
    }

    private void apply(CommandContext ctx, Player player, boolean target) {
        if (set(player, target, false)) {
            ctx.reply(target ? "pvp.enabled" : "pvp.disabled");
        }
    }

    @SuppressWarnings("deprecation")
    private void adminCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 2) {
            ctx.reply("general.usage", "usage", "/uc pvp <player> <on|off|reset|force-on|force-off|unforce|status>");
            return;
        }
        String name = ctx.arg(0);
        String action = ctx.arg(1).toLowerCase(Locale.ROOT);
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && action.equals("status")) {
            ctx.reply("pvp.admin-status", "player", online.getName(), "state", plugin.messages().bool(pvpEnabled(online)));
            return;
        }
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(name);
        java.util.function.Consumer<PlayerProfile> change = switch (action) {
            case "on" -> p -> p.pvpEnabled(true);
            case "off" -> p -> p.pvpEnabled(false);
            case "reset" -> p -> {
                p.pvpEnabled(null);
                p.pvpForced(null);
                p.pvpCooldownUntil(0);
            };
            case "force-on" -> p -> p.pvpForced(true);
            case "force-off" -> p -> p.pvpForced(false);
            case "unforce" -> p -> p.pvpForced(null);
            default -> null;
        };
        if (change == null) {
            ctx.reply("general.usage", "usage", "/uc pvp <player> <on|off|reset|force-on|force-off|unforce|status>");
            return;
        }
        if (online != null && (action.equals("on") || action.equals("off"))) {
            if (!set(online, action.equals("on"), true)) {
                ctx.reply("pvp.admin-cancelled", "player", online.getName());
                return;
            }
            ctx.reply("pvp.admin-updated", "player", online.getName(), "action", action);
            plugin.messages().send(online, action.equals("on") ? "pvp.enabled-by-admin" : "pvp.disabled-by-admin");
            return;
        }
        plugin.players().modify(target.getUniqueId(), change).thenAccept(ok -> {
            if (ok) {
                ctx.reply("pvp.admin-updated", "player", name, "action", action);
                plugin.sync().publish("pvp-toggle", target.getUniqueId().toString(), action);
            } else {
                ctx.reply("general.player-not-found", "player", name);
            }
        });
    }
}
