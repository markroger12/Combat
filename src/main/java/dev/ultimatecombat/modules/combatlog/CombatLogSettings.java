package dev.ultimatecombat.modules.combatlog;

import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.WorldRules;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable snapshot of combatlog.yml.
 */
public record CombatLogSettings(boolean punishQuit, boolean punishKick, boolean punishTimeout, List<String> ignoredKickReasons,
                                Profile pvp, Profile pve, boolean customQuitMessage, boolean notifyOpponents, boolean history,
                                WorldRules worlds, Set<String> excludedRegions, long minimumTagMillis) {

    public enum KillMode { DEATH, DROP_AND_CLEAR, NONE }

    public record Profile(String name, KillMode killMode, boolean killOnRejoin, boolean ignoreKeepInventory, boolean dropInventory,
                          boolean dropExperience, boolean creditKill, boolean countAsDeath, boolean moneyEnabled, boolean moneyPercent,
                          double moneyAmount, double moneyMaximum, boolean moneyToOpponent, List<String> consoleCommands,
                          boolean broadcast, boolean broadcastLocation, String broadcastSound, boolean useGhost,
                          boolean rejoinMessage, boolean rejoinBroadcast, List<String> rejoinConsoleCommands,
                          List<String> rejoinPlayerCommands) {

        static Profile read(String name, ConfigReader r, KillMode defaultMode) {
            ConfigReader money = r.section("money-penalty");
            ConfigReader rejoin = r.section("rejoin");
            return new Profile(name,
                    r.enumValue("kill-mode", KillMode.class, defaultMode),
                    r.bool("kill-on-rejoin", false),
                    r.bool("ignore-keep-inventory", true),
                    r.bool("drop-inventory", true),
                    r.bool("drop-experience", true),
                    r.bool("give-kill-to-opponent", true),
                    r.bool("count-as-death", true),
                    money.bool("enabled", false),
                    money.choice("mode", "percent", "percent", "fixed").equals("percent"),
                    money.decimal("amount", 10, 0, Double.MAX_VALUE),
                    money.decimal("maximum", 0, 0, Double.MAX_VALUE),
                    money.bool("give-to-opponent", true),
                    r.stringList("commands"),
                    r.bool("broadcast.enabled", true),
                    r.bool("broadcast.show-location", false),
                    r.string("broadcast.sound", "combat-log-broadcast"),
                    r.bool("ghost-player", false),
                    rejoin.bool("message", true),
                    rejoin.bool("broadcast", false),
                    rejoin.stringList("console-commands"),
                    rejoin.stringList("player-commands"));
        }
    }

    public static CombatLogSettings parse(ConfigReader r) {
        ConfigReader on = r.section("punish-on");
        List<String> ignored = on.stringList("ignored-kick-reasons").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return new CombatLogSettings(
                on.bool("quit", true),
                on.bool("kick", false),
                on.bool("timeout", true),
                ignored,
                Profile.read("pvp", r.section("profiles.pvp"), KillMode.DEATH),
                Profile.read("pve", r.section("profiles.pve"), KillMode.DEATH),
                r.bool("custom-quit-message", true),
                r.bool("notify-opponents", true),
                r.bool("history.enabled", true),
                WorldRules.read(r, "worlds"),
                r.lowerCaseSet("excluded-regions"),
                r.durationMillis("minimum-remaining-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 3_600_000));
    }
}
