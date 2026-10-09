package dev.ultimatecombat.modules.combat;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.TagCause;
import dev.ultimatecombat.api.UntagReason;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.api.event.CombatUntagEvent;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.CommandRunner;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat tagging for PvP and PvE (combat.yml). Owns the tag state, timers and the combat displays.
 * Other modules query it through {@link #isTagged(Player)} and friends.
 */
public final class CombatModule extends Module<CombatSettings> {

    private final Map<UUID, CombatTag> tags = new ConcurrentHashMap<>();
    private ScheduledTask globalTicker;
    private CombatListener listener;

    public CombatModule(UltimateCombatPlugin plugin) {
        super(plugin, "combat-tag", "combat.yml");
    }

    @Override
    protected CombatSettings parse(ConfigReader reader) {
        long ticks = plugin.configs().file("config.yml").reader().integer("performance.combat-update-interval-ticks", 2, 1, 20);
        return CombatSettings.parse(reader, ticks);
    }

    @Override
    protected void onEnable() {
        listener = new CombatListener(plugin, this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        startTicker();
    }

    @Override
    public void registerCommands() {
        CombatCommands.register(plugin, this);
    }

    @Override
    protected void onReload(CombatSettings previous) {
        if (previous == null || previous.display().updateTicks() != settings().display().updateTicks()) {
            restartTickers();
        }
        for (CombatTag tag : tags.values()) {
            Player p = Bukkit.getPlayer(tag.player());
            if (p != null) {
                plugin.scheduler().runEntity(p, () -> refreshDisplayMode(p, tag));
            }
        }
    }

    @Override
    protected void onDisable() {
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (globalTicker != null) {
            globalTicker.cancel();
            globalTicker = null;
        }
        for (UUID id : new ArrayList<>(tags.keySet())) {
            Player p = Bukkit.getPlayer(id);
            CombatTag tag = tags.remove(id);
            if (tag != null) {
                cancelTicker(tag);
                if (p != null) {
                    cleanupDisplay(p, tag);
                    Bukkit.getPluginManager().callEvent(new CombatUntagEvent(p, tag.type(), UntagReason.PLUGIN_DISABLE));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------ API

    public boolean isTagged(Player player) {
        return player != null && isTagged(player.getUniqueId());
    }

    public boolean isTagged(UUID player) {
        CombatTag tag = tags.get(player);
        return tag != null && tag.remainingMillis() > 0;
    }

    public boolean isTagged(Player player, CombatType type) {
        CombatTag tag = player == null ? null : tags.get(player.getUniqueId());
        return tag != null && tag.remainingMillis() > 0 && tag.type() == type;
    }

    public CombatTag tag(Player player) {
        return tags.get(player.getUniqueId());
    }

    public CombatTag tag(UUID player) {
        return tags.get(player);
    }

    public long remainingMillis(Player player) {
        CombatTag tag = tags.get(player.getUniqueId());
        return tag == null ? 0 : tag.remainingMillis();
    }

    public Collection<CombatTag> tags() {
        return tags.values();
    }

    /**
     * Checks world, game mode, region and bypass rules.
     */
    public boolean canBeTagged(Player player) {
        CombatSettings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld())) {
            return false;
        }
        if (s.exemptGameModes().contains(player.getGameMode())) {
            return false;
        }
        if (s.ignoreNpcs() && player.hasMetadata("NPC")) {
            return false;
        }
        if (plugin.bypasses(player, "combat-tag")) {
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

    /**
     * Tags or refreshes a player.
     *
     * @param opponent         the other side (player or mob), may be {@code null}
     * @param durationOverride custom duration in millis, or {@code <= 0} for the configured one
     * @return whether the player is (still) tagged afterwards
     */
    public boolean tag(Player player, Entity opponent, CombatType type, TagCause cause, long durationOverride) {
        CombatSettings s = settings(player.getWorld());
        boolean pvp = type == CombatType.PVP;
        if (pvp ? !s.pvp().enabled() : !s.pve().enabled()) {
            if (cause != TagCause.COMMAND && cause != TagCause.API) {
                return false;
            }
        }
        if (cause != TagCause.COMMAND && !canBeTagged(player)) {
            return false;
        }
        long duration = durationOverride > 0 ? durationOverride : (pvp ? s.pvp().durationMillis() : s.pve().durationMillis());
        CombatTag existing = tags.get(player.getUniqueId());
        boolean refresh = existing != null && existing.remainingMillis() > 0;

        if (refresh && !pvp && existing.type() == CombatType.PVP) {
            // PvE hit while in PvP combat: PvP keeps precedence.
            if (s.pve().pveRefreshesPvp() && cause != TagCause.COMMAND) {
                existing.extendTo(duration);
            }
            return true;
        }
        if (refresh && cause != TagCause.COMMAND && cause != TagCause.API
                && !(pvp ? s.pvp().refreshOnHit() : s.pve().refreshOnHit())
                && existing.type() == type) {
            // Refreshing disabled: only remember the opponent.
            rememberOpponent(existing, opponent, type);
            return true;
        }

        CombatTagEvent event = new CombatTagEvent(player, opponent, type, cause, refresh, duration);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return refresh;
        }
        duration = event.getDurationMillis();
        if (duration <= 0) {
            return refresh;
        }
        if (refresh) {
            boolean upgraded = existing.type() == CombatType.PVE && pvp && s.pve().pvpOverridesPve();
            if (upgraded) {
                existing.type(CombatType.PVP);
                existing.refresh(duration);
                refreshDisplayMode(player, existing);
            } else if (existing.type() == type) {
                existing.refresh(duration);
            } else {
                existing.extendTo(duration);
            }
            rememberOpponent(existing, opponent, type);
            if (upgraded) {
                announceStart(player, existing, opponent, cause);
            }
            return true;
        }
        CombatTag tag = new CombatTag(player.getUniqueId(), type, duration);
        rememberOpponent(tag, opponent, type);
        tags.put(player.getUniqueId(), tag);
        plugin.debug().log("combat", player.getName() + " tagged (" + type + ", " + cause + ") for " + duration + "ms");
        plugin.debug().count("combat.tags");
        startPlayerTicker(player, tag);
        plugin.scheduler().executeEntity(player, () -> refreshDisplayMode(player, tag));
        announceStart(player, tag, opponent, cause);
        return true;
    }

    private void rememberOpponent(CombatTag tag, Entity opponent, CombatType type) {
        if (opponent == null) {
            return;
        }
        if (opponent instanceof Player p) {
            tag.addOpponent(p.getUniqueId(), p.getName());
        } else if (type == CombatType.PVE) {
            tag.pveSource(KillService.entityName(opponent));
        } else {
            tag.addOpponent(opponent.getUniqueId(), KillService.entityName(opponent));
        }
    }

    private void announceStart(Player player, CombatTag tag, Entity opponent, TagCause cause) {
        CombatSettings s = settings(player.getWorld());
        boolean pvp = tag.type() == CombatType.PVP;
        String opponentName = opponent == null ? plugin.messages().settings().none() : KillService.entityName(opponent);
        Object[] ph = {"player", player.getName(), "opponent", opponentName, "time", seconds(tag.remainingMillis()),
                "type", tag.type().name()};
        if (pvp ? s.pvp().notifyStart() : s.pve().notifyStart()) {
            String key = pvp ? (cause == TagCause.VICTIM ? "combat.pvp.start-victim" : "combat.pvp.start-attacker") : "combat.pve.start";
            plugin.messages().send(player, key, ph);
        }
        plugin.effects().playSound(player, pvp ? s.pvp().soundStart() : s.pve().soundStart());
        if (pvp) {
            plugin.effects().spawnParticle(player.getLocation(), s.pvp().particleStart());
        }
        CombatSettings.Commands commands = pvp ? s.pvp().commands() : s.pve().commands();
        CommandRunner.console(plugin, player, commands.consoleOnTag(), ph);
        CommandRunner.player(plugin, player, commands.playerOnTag(), ph);
    }

    /**
     * Ends combat for a player.
     *
     * @param notify whether to send the "combat ended" message / sound
     */
    public void untag(Player player, UntagReason reason, boolean notify) {
        CombatTag tag = tags.remove(player.getUniqueId());
        if (tag == null) {
            return;
        }
        cancelTicker(tag);
        plugin.scheduler().executeEntity(player, () -> cleanupDisplay(player, tag));
        Bukkit.getPluginManager().callEvent(new CombatUntagEvent(player, tag.type(), reason));
        plugin.debug().log("combat", player.getName() + " untagged (" + reason + ")");
        if (reason == UntagReason.QUIT) {
            return;
        }
        CombatSettings s = settings(player.getWorld());
        boolean pvp = tag.type() == CombatType.PVP;
        Object[] ph = {"player", player.getName(), "type", tag.type().name(), "opponent", tag.lastOpponentName()};
        if (notify && (pvp ? s.pvp().notifyEnd() : s.pve().notifyEnd())) {
            plugin.messages().send(player, reason == UntagReason.EXPIRED ? "combat.end" : "combat.end-other", ph);
            if (s.display().actionbar() && s.display().actionbarOnEnd()) {
                plugin.messages().actionBar(player, plugin.messages().text(player, "combat.actionbar.end", ph));
            }
            plugin.effects().playSound(player, pvp ? s.pvp().soundEnd() : s.pve().soundEnd());
        }
        CombatSettings.Commands commands = pvp ? s.pvp().commands() : s.pve().commands();
        CommandRunner.console(plugin, player, commands.consoleOnUntag(), ph);
        CommandRunner.player(plugin, player, commands.playerOnUntag(), ph);
    }

    /**
     * Removes an opponent from everyone's list (after death or quit). Players left without any
     * opponent are untagged when untag-on-kill is enabled.
     */
    void opponentGone(UUID gone, boolean byDeath) {
        for (CombatTag tag : tags.values()) {
            if (!tag.opponentHits().containsKey(gone)) {
                continue;
            }
            tag.removeOpponent(gone);
            Player p = Bukkit.getPlayer(tag.player());
            if (p == null) {
                continue;
            }
            CombatSettings s = settings(p.getWorld());
            if (byDeath && tag.type() == CombatType.PVP && s.pvp().untagOnKill() && tag.opponents().isEmpty()) {
                plugin.scheduler().executeEntity(p, () -> untag(p, UntagReason.OPPONENT_DIED, true));
            }
        }
    }

    void forget(UUID player) {
        CombatTag tag = tags.remove(player);
        if (tag != null) {
            cancelTicker(tag);
        }
    }

    // ------------------------------------------------------------------------------------ timers

    private void startTicker() {
        if (plugin.platform().isFolia()) {
            return; // Folia: one entity task per tagged player (see startPlayerTicker)
        }
        long period = settings().display().updateTicks();
        globalTicker = plugin.scheduler().runGlobalTimer(this::tickAll, period, period);
    }

    private void restartTickers() {
        if (globalTicker != null) {
            globalTicker.cancel();
            globalTicker = null;
        }
        if (plugin.platform().isFolia()) {
            for (CombatTag tag : tags.values()) {
                Player p = Bukkit.getPlayer(tag.player());
                cancelTicker(tag);
                if (p != null) {
                    startPlayerTicker(p, tag);
                }
            }
        } else {
            startTicker();
        }
    }

    private void startPlayerTicker(Player player, CombatTag tag) {
        if (!plugin.platform().isFolia()) {
            return;
        }
        long period = settings().display().updateTicks();
        tag.ticker = plugin.scheduler().runEntityTimer(player, () -> tick(player, tag), period, period);
    }

    private static void cancelTicker(CombatTag tag) {
        ScheduledTask t = tag.ticker;
        if (t != null) {
            t.cancel();
            tag.ticker = null;
        }
    }

    private void tickAll() {
        for (CombatTag tag : tags.values()) {
            Player p = Bukkit.getPlayer(tag.player());
            if (p == null) {
                forget(tag.player());
                continue;
            }
            tick(p, tag);
        }
    }

    private void tick(Player player, CombatTag tag) {
        if (tags.get(player.getUniqueId()) != tag) {
            cancelTicker(tag);
            return;
        }
        if (tag.remainingMillis() <= 0) {
            untag(player, UntagReason.EXPIRED, true);
            return;
        }
        updateDisplay(player, tag);
    }

    // ------------------------------------------------------------------------------------ display

    public String seconds(long millis) {
        return Text.formatSeconds(millis, plugin.messages().settings().timerDecimals());
    }

    public String progressBar(CombatTag tag, CombatSettings.Display d) {
        return Text.progressBar(tag.progress(), d.barLength(), d.filledChar(), d.emptyChar(), d.filledColor(), d.emptyColor());
    }

    private void updateDisplay(Player player, CombatTag tag) {
        CombatSettings s = settings(player.getWorld());
        CombatSettings.Display d = s.display();
        boolean pvp = tag.type() == CombatType.PVP;
        Object[] ph = {"time", seconds(tag.remainingMillis()), "bar", progressBar(tag, d), "opponent", tag.lastOpponentName(),
                "type", tag.type().name(), "player", player.getName()};
        if (d.actionbar()) {
            plugin.messages().actionBar(player, plugin.messages().text(player, pvp ? "combat.actionbar.pvp" : "combat.actionbar.pve", ph));
        }
        BossBar bar = tag.bossBar;
        if (d.bossbar() && bar != null) {
            bar.setTitle(plugin.messages().text(player, pvp ? "combat.bossbar.pvp" : "combat.bossbar.pve", ph));
            bar.setProgress(d.bossbarProgress() ? tag.progress() : 1.0);
            bar.setColor(pvp ? d.pvpBarColor() : d.pveBarColor());
        }
    }

    /**
     * Creates or removes boss bar and glowing according to current settings.
     */
    void refreshDisplayMode(Player player, CombatTag tag) {
        if (!tags.containsKey(player.getUniqueId())) {
            return;
        }
        CombatSettings.Display d = settings(player.getWorld()).display();
        if (d.bossbar() && tag.bossBar == null) {
            BarColor color = tag.type() == CombatType.PVP ? d.pvpBarColor() : d.pveBarColor();
            BossBar bar = Bukkit.createBossBar(" ", color, d.barStyle());
            bar.addPlayer(player);
            tag.bossBar = bar;
        } else if (!d.bossbar() && tag.bossBar != null) {
            tag.bossBar.removeAll();
            tag.bossBar = null;
        }
        boolean wantGlow = d.glowing() && tag.type() == CombatType.PVP;
        if (wantGlow && !tag.glowingApplied) {
            tag.wasGlowing = player.isGlowing();
            player.setGlowing(true);
            tag.glowingApplied = true;
        } else if (!wantGlow && tag.glowingApplied) {
            player.setGlowing(tag.wasGlowing);
            tag.glowingApplied = false;
        }
        updateDisplay(player, tag);
    }

    private void cleanupDisplay(Player player, CombatTag tag) {
        BossBar bar = tag.bossBar;
        if (bar != null) {
            bar.removeAll();
            tag.bossBar = null;
        }
        if (tag.glowingApplied) {
            player.setGlowing(tag.wasGlowing);
            tag.glowingApplied = false;
        }
    }

    /**
     * @return names of the current opponents (for placeholders)
     */
    public List<String> opponentNames(Player player) {
        CombatTag tag = tags.get(player.getUniqueId());
        List<String> out = new ArrayList<>();
        if (tag == null) {
            return out;
        }
        for (UUID id : tag.opponents()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                out.add(p.getName());
            }
        }
        if (out.isEmpty() && !tag.pveSource().isEmpty()) {
            out.add(tag.pveSource());
        }
        return out;
    }
}
