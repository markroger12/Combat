package dev.ultimatecombat.modules.placeholders;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.ExpansionHost;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.core.player.PlayerStats;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.modules.bounties.BountyModule;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combat.CombatTag;
import dev.ultimatecombat.modules.grace.GraceModule;
import dev.ultimatecombat.modules.intervals.PvpIntervalModule;
import dev.ultimatecombat.modules.leaderboards.LeaderboardModule;
import dev.ultimatecombat.modules.stats.StatsModule;
import dev.ultimatecombat.modules.togglepvp.TogglePvpModule;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * PlaceholderAPI placeholders (placeholders.yml). The expansion itself is registered by the
 * PlaceholderAPI integration and delegates to {@link #resolve(OfflinePlayer, String)}.
 */
public final class PlaceholderModule extends Module<PlaceholderModule.Settings> {

    public record Settings(String identifier, String trueValue, String falseValue, String emptyValue, String topEmptyName,
                           String topEmptyValue) {
    }

    public PlaceholderModule(UltimateCombatPlugin plugin) {
        super(plugin, "placeholders", "placeholders.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        String id = r.string("identifier", "ultimatecombat").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            r.warn("identifier", id, "Invalid identifier", "lower-case letters, digits, _ or - (max 32)", "ultimatecombat");
            id = "ultimatecombat";
        }
        return new Settings(id, r.string("values.true", "true"), r.string("values.false", "false"), r.string("values.empty", ""),
                r.string("values.top-empty-name", "---"), r.string("values.top-empty-value", "0"));
    }

    @Override
    protected void onEnable() {
        reRegister();
    }

    @Override
    protected void onReload(Settings previous) {
        if (!previous.identifier().equals(settings().identifier())) {
            reRegister();
        }
    }

    @Override
    protected void onDisable() {
        for (IntegrationManager.State state : plugin.integrations().states()) {
            if (state.instance() instanceof ExpansionHost hook) {
                hook.unregisterExpansion();
            }
        }
    }

    private void reRegister() {
        for (IntegrationManager.State state : plugin.integrations().states()) {
            if (state.instance() instanceof ExpansionHost hook) {
                hook.registerExpansion();
            }
        }
    }

    private String bool(boolean value) {
        Settings s = settings();
        return Text.color(value ? s.trueValue() : s.falseValue());
    }

    /**
     * Resolves {@code %<identifier>_<params>%}.
     *
     * @return the value, or {@code null} for unknown placeholders
     */
    public String resolve(OfflinePlayer offline, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        Settings s = settings();
        if (p.startsWith("top_")) {
            return top(p.substring(4), s);
        }
        Player player = offline == null ? null : offline.getPlayer();
        if (p.startsWith("position")) {
            LeaderboardModule boards = plugin.active(LeaderboardModule.class);
            if (boards == null || offline == null) {
                return s.emptyValue();
            }
            LeaderboardModule.Board board = p.equals("position") ? boards.settings().defaultBoard()
                    : LeaderboardModule.Board.parse(p.substring("position_".length()));
            if (board == null) {
                return null;
            }
            int pos = boards.position(board, offline.getUniqueId());
            return pos == 0 ? s.emptyValue() : Integer.toString(pos);
        }
        String value = statValue(offline, p, s);
        if (value != null) {
            return value;
        }
        if (player == null) {
            return isPlayerPlaceholder(p) ? s.emptyValue() : null;
        }
        return playerValue(player, p, s);
    }

    private static boolean isPlayerPlaceholder(String p) {
        return switch (p) {
            case "in_combat", "combat_time", "combat_time_formatted", "combat_opponent", "combat_opponents", "combat_type",
                 "pvp_enabled", "grace_active", "grace_time", "grace_time_formatted", "interval_pvp", "interval_next" -> true;
            default -> false;
        };
    }

    private String playerValue(Player player, String p, Settings s) {
        switch (p) {
            case "in_combat" -> {
                CombatModule combat = plugin.active(CombatModule.class);
                return bool(combat != null && combat.isTagged(player));
            }
            case "combat_time", "combat_time_formatted" -> {
                CombatModule combat = plugin.active(CombatModule.class);
                long left = combat == null ? 0 : combat.remainingMillis(player);
                return p.equals("combat_time") ? Long.toString((left + 999) / 1000) : plugin.messages().duration(left);
            }
            case "combat_opponent", "combat_opponents", "combat_type" -> {
                CombatModule combat = plugin.active(CombatModule.class);
                CombatTag tag = combat == null ? null : combat.tag(player);
                if (tag == null) {
                    return p.equals("combat_opponents") ? "0" : s.emptyValue();
                }
                return switch (p) {
                    case "combat_opponents" -> Integer.toString(tag.opponents().size());
                    case "combat_type" -> tag.type().name().toLowerCase(Locale.ROOT);
                    default -> tag.lastOpponentName() == null ? s.emptyValue() : tag.lastOpponentName();
                };
            }
            case "pvp_enabled" -> {
                TogglePvpModule toggle = plugin.active(TogglePvpModule.class);
                return bool(toggle == null || toggle.pvpEnabled(player));
            }
            case "grace_active", "grace_time", "grace_time_formatted" -> {
                GraceModule grace = plugin.active(GraceModule.class);
                long left = grace == null ? 0 : grace.remaining(player);
                return switch (p) {
                    case "grace_active" -> bool(left > 0);
                    case "grace_time" -> Long.toString((left + 999) / 1000);
                    default -> plugin.messages().duration(left);
                };
            }
            case "interval_pvp" -> {
                PvpIntervalModule intervals = plugin.active(PvpIntervalModule.class);
                return bool(intervals == null || intervals.pvpAllowed(player.getWorld()));
            }
            case "interval_next" -> {
                PvpIntervalModule intervals = plugin.active(PvpIntervalModule.class);
                int minutes = intervals == null ? -1 : intervals.minutesUntilChange(player.getWorld());
                return minutes < 0 ? s.emptyValue() : Integer.toString(minutes);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Statistics and bounty values; available for online players (offline players are not loaded).
     */
    private String statValue(OfflinePlayer offline, String p, Settings s) {
        boolean bounty = p.startsWith("bounty_") || p.equals("has_bounty");
        if (bounty) {
            BountyModule bounties = plugin.active(BountyModule.class);
            if (bounties == null || offline == null) {
                return p.equals("has_bounty") ? bool(false) : s.emptyValue();
            }
            double total = bounties.total(offline.getUniqueId());
            return switch (p) {
                case "has_bounty" -> bool(total > 0);
                case "bounty_value" -> plugin.economy().format(total);
                case "bounty_value_raw" -> Numbers.twoDecimals(total);
                case "bounty_count" -> Integer.toString(bounties.bounties(offline.getUniqueId()).size());
                case "bounty_initiator" -> {
                    String name = bounties.initiator(offline.getUniqueId());
                    yield name == null ? s.emptyValue() : name;
                }
                default -> null;
            };
        }
        if (!isStat(p)) {
            return null;
        }
        PlayerProfile profile = offline == null ? null : plugin.players().online(offline.getUniqueId());
        if (profile == null) {
            return s.emptyValue();
        }
        PlayerStats st = profile.stats();
        StatsModule stats = plugin.module(StatsModule.class);
        return switch (p) {
            case "kills" -> Integer.toString(st.kills());
            case "deaths" -> Integer.toString(st.deaths());
            case "kdr" -> Numbers.twoDecimals(st.kdr());
            case "streak", "killstreak" -> Integer.toString(st.streak());
            case "best_streak" -> Integer.toString(st.bestStreak());
            case "points" -> Long.toString(stats == null ? st.points() : stats.displayPoints(st));
            case "rank" -> stats == null ? s.emptyValue() : Text.color(stats.rankName(st));
            case "combatlogs" -> Integer.toString(st.combatLogs());
            case "assists" -> Integer.toString(st.assists());
            case "mob_kills" -> Integer.toString(st.mobKills());
            case "bounties_claimed" -> Integer.toString(st.bountiesClaimed());
            case "bounty_earnings" -> plugin.economy().format(st.bountyEarnings());
            case "damage_dealt" -> Numbers.twoDecimals(st.damageDealt());
            case "damage_taken" -> Numbers.twoDecimals(st.damageTaken());
            default -> null;
        };
    }

    private static boolean isStat(String p) {
        return switch (p) {
            case "kills", "deaths", "kdr", "streak", "killstreak", "best_streak", "points", "rank", "combatlogs", "assists",
                 "mob_kills", "bounties_claimed", "bounty_earnings", "damage_dealt", "damage_taken" -> true;
            default -> false;
        };
    }

    /**
     * {@code top_<board>_<position>_<name|value>}; board ids may use - or _.
     */
    private String top(String rest, Settings s) {
        int last = rest.lastIndexOf('_');
        if (last < 0) {
            return null;
        }
        String field = rest.substring(last + 1);
        String head = rest.substring(0, last);
        int numberAt = head.lastIndexOf('_');
        if (numberAt < 0) {
            return null;
        }
        Integer position = Numbers.parseInt(head.substring(numberAt + 1));
        LeaderboardModule.Board board = LeaderboardModule.Board.parse(head.substring(0, numberAt));
        if (position == null || board == null || !field.equals("name") && !field.equals("value")) {
            return null;
        }
        LeaderboardModule boards = plugin.active(LeaderboardModule.class);
        LeaderboardModule.Entry entry = boards == null ? null : boards.entry(board, position);
        if (entry == null) {
            return field.equals("name") ? s.topEmptyName() : s.topEmptyValue();
        }
        return field.equals("name") ? entry.name() : boards.format(board, entry.value());
    }
}
