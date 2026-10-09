package dev.ultimatecombat.modules.rewards;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.UntagReason;
import dev.ultimatecombat.api.event.CombatLogEvent;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.api.event.CombatUntagEvent;
import dev.ultimatecombat.core.action.ActionExecutor;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Cooldowns;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Bukkit;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rule based rewards (rewards.yml).
 */
public final class RewardsModule extends Module<RewardsModule.Settings> implements KillHandler {

    public enum Trigger {
        PLAYER_KILL, PLAYER_DEATH, ASSIST, MOB_KILL, ANIMAL_KILL, MONSTER_KILL, CUSTOM_ENTITY_KILL, KILLSTREAK, COMBAT_START,
        COMBAT_END, COMBAT_LOG, BOUNTY_CLAIM, CUSTOM
    }

    /**
     * One reward rule.
     */
    public record Rule(String id, Trigger trigger, double chance, long cooldownMillis, String permission, WorldRules worlds,
                       int minStreak, int maxStreak, Set<EntityType> entityTypes, Set<String> customMobIds, String customTrigger,
                       List<String> actions) {
    }

    public record Settings(List<Rule> rules) {
    }

    private final Cooldowns cooldowns = new Cooldowns();
    private boolean registeredHandler;

    public RewardsModule(UltimateCombatPlugin plugin) {
        super(plugin, "rewards", "rewards.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        List<Rule> rules = new ArrayList<>();
        for (String id : r.keys("rules")) {
            ConfigReader rule = r.section("rules." + id);
            // disabled rules are still parsed so their mistakes are reported before they are enabled
            boolean enabled = rule.bool("enabled", true);
            ConfigReader c = rule.section("conditions");
            Rule parsed = new Rule(id,
                    rule.enumValue("trigger", Trigger.class, Trigger.PLAYER_KILL),
                    rule.percent("chance", 100),
                    rule.durationMillis("cooldown-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 31L * 86_400_000L),
                    rule.string("permission", ""),
                    WorldRules.read(rule, "worlds"),
                    c.integer("min-killstreak", 0, 0, 1_000_000),
                    c.integer("max-killstreak", 0, 0, 1_000_000),
                    c.entityTypes("entity-types"),
                    c.lowerCaseSet("custom-mob-ids"),
                    c.string("custom-trigger", "").toLowerCase(Locale.ROOT),
                    ActionExecutor.validate(rule, "actions", rule.stringList("actions")));
            if (enabled) {
                rules.add(parsed);
            }
        }
        return new Settings(rules);
    }

    @Override
    protected void onEnable() {
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
    }

    @Override
    protected void onDisable() {
        cooldowns.clearAll();
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return REWARDS;
    }

    // ------------------------------------------------------------------------------ triggering

    /**
     * Fires a trigger for a player. Extra placeholders are available to every action.
     *
     * @return number of rules that ran
     */
    public int trigger(Trigger trigger, Player player, Map<String, String> placeholders, int streak, LivingEntity entity, String customId) {
        if (!isEnabled() || player == null) {
            return 0;
        }
        Map<String, String> ph = new LinkedHashMap<>(placeholders);
        ph.putIfAbsent("player", player.getName());
        ph.putIfAbsent("world", player.getWorld().getName());
        ph.putIfAbsent("streak", Integer.toString(streak));
        int ran = 0;
        for (Rule rule : settings(player.getWorld()).rules()) {
            if (rule.trigger() != trigger) {
                continue;
            }
            if (trigger == Trigger.CUSTOM && !rule.customTrigger().equalsIgnoreCase(customId == null ? "" : customId)) {
                continue;
            }
            if (!rule.worlds().allows(player.getWorld())) {
                continue;
            }
            if (!rule.permission().isEmpty() && !player.hasPermission(rule.permission())) {
                continue;
            }
            if (rule.minStreak() > 0 && streak < rule.minStreak() || rule.maxStreak() > 0 && streak > rule.maxStreak()) {
                continue;
            }
            if (entity != null) {
                if (!rule.entityTypes().isEmpty() && !rule.entityTypes().contains(entity.getType())) {
                    continue;
                }
                if (!rule.customMobIds().isEmpty()) {
                    String id = plugin.integrations().customEntityId(entity);
                    if (id == null || !rule.customMobIds().contains(id.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                }
            }
            String cooldownKey = "reward:" + rule.id();
            if (rule.cooldownMillis() > 0 && cooldowns.active(player.getUniqueId(), cooldownKey)) {
                continue;
            }
            if (!Numbers.roll(rule.chance())) {
                continue;
            }
            cooldowns.set(player.getUniqueId(), cooldownKey, rule.cooldownMillis());
            plugin.actions().run(player, rule.actions(), ph);
            plugin.debug().log("rewards", "Rule " + rule.id() + " (" + trigger + ") ran for " + player.getName());
            ran++;
        }
        return ran;
    }

    public int trigger(Trigger trigger, Player player, Map<String, String> placeholders) {
        return trigger(trigger, player, placeholders, streakOf(player), null, null);
    }

    private int streakOf(Player player) {
        return plugin.players().profile(player).stats().streak();
    }

    @Override
    public void onPlayerDeath(KillContext ctx) {
        Player victim = ctx.victim();
        Player killer = ctx.killer();
        Map<String, String> ph = new HashMap<>();
        ph.put("victim", victim.getName());
        ph.put("killer", killer == null ? plugin.messages().settings().none() : killer.getName());
        ph.put("weapon", ctx.weapon() == null ? "" : KillService.itemName(ctx.weapon()));
        ph.put("points", Long.toString(ctx.killerPointsChange()));
        if (!ctx.combatLogDeath()) {
            Map<String, String> victimPh = new HashMap<>(ph);
            victimPh.put("player", victim.getName());
            trigger(Trigger.PLAYER_DEATH, victim, victimPh, ctx.victimStreakBefore(), null, null);
        }
        if (!ctx.isPvp() || ctx.rewardsSuppressed()) {
            return;
        }
        Map<String, String> killerPh = new HashMap<>(ph);
        killerPh.put("player", killer.getName());
        trigger(Trigger.PLAYER_KILL, killer, killerPh, ctx.killerStreakAfter(), null, null);
        for (UUID id : ctx.assists()) {
            Player assistant = Bukkit.getPlayer(id);
            if (assistant != null) {
                Map<String, String> aPh = new HashMap<>(ph);
                aPh.put("player", assistant.getName());
                trigger(Trigger.ASSIST, assistant, aPh, streakOf(assistant), null, null);
            }
        }
    }

    @Override
    public void onMobKill(Player killer, LivingEntity entity, EntityDeathEvent event) {
        Map<String, String> ph = new HashMap<>();
        ph.put("entity", KillService.entityName(entity));
        ph.put("entity_type", entity.getType().name().toLowerCase(Locale.ROOT));
        int streak = streakOf(killer);
        trigger(Trigger.MOB_KILL, killer, ph, streak, entity, null);
        if (plugin.integrations().customEntityId(entity) != null) {
            trigger(Trigger.CUSTOM_ENTITY_KILL, killer, ph, streak, entity, null);
        } else if (entity instanceof Enemy) {
            trigger(Trigger.MONSTER_KILL, killer, ph, streak, entity, null);
        } else if (entity instanceof Animals) {
            trigger(Trigger.ANIMAL_KILL, killer, ph, streak, entity, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        if (!event.isRefresh()) {
            Map<String, String> ph = new HashMap<>();
            ph.put("opponent", event.getOpponent() == null ? plugin.messages().settings().none()
                    : KillService.entityName(event.getOpponent()));
            ph.put("type", event.getType().name());
            trigger(Trigger.COMBAT_START, event.getPlayer(), ph);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(CombatUntagEvent event) {
        if (event.getReason() != UntagReason.QUIT && event.getReason() != UntagReason.PLUGIN_DISABLE) {
            Map<String, String> ph = new HashMap<>();
            ph.put("type", event.getType().name());
            ph.put("reason", event.getReason().name().toLowerCase(Locale.ROOT));
            trigger(Trigger.COMBAT_END, event.getPlayer(), ph);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatLog(CombatLogEvent event) {
        Map<String, String> ph = new HashMap<>();
        ph.put("type", event.getType().name());
        trigger(Trigger.COMBAT_LOG, event.getPlayer(), ph);
    }

    // ------------------------------------------------------------------------------ command

    @Override
    public void registerCommands() {
        plugin.commands().root().child(new SimpleCommand("rewards", "rewards trigger <player> <custom-trigger>",
                "ultimatecombat.rewards.admin", false, this::command, ctx -> switch (ctx.size()) {
                    case 1 -> List.of("trigger");
                    case 2 -> UCCommand.onlineNames();
                    default -> List.of();
                }));
    }

    private void command(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 3 || !ctx.arg(0).equalsIgnoreCase("trigger")) {
            ctx.reply("general.usage", "usage", "/uc rewards trigger <player> <custom-trigger>");
            return;
        }
        Player target = ctx.onlinePlayer(1);
        if (target == null) {
            return;
        }
        int ran = trigger(Trigger.CUSTOM, target, new HashMap<>(), streakOf(target), null, ctx.arg(2));
        ctx.reply("rewards.triggered", "trigger", ctx.arg(2), "player", target.getName(), "count", ran);
    }
}
