package dev.ultimatecombat.modules.combat;

import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.GameMode;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable snapshot of combat.yml.
 */
public record CombatSettings(Pvp pvp, Pve pve, Display display, ThirdParty thirdParty, WorldRules worlds,
                             Set<String> excludedRegions, Set<GameMode> exemptGameModes, boolean untagOnWorldChange,
                             boolean ignoreNpcs) {

    public record Commands(List<String> consoleOnTag, List<String> playerOnTag, List<String> consoleOnUntag, List<String> playerOnUntag) {
        static Commands read(ConfigReader r) {
            return new Commands(r.stringList("on-tag.console"), r.stringList("on-tag.player"),
                    r.stringList("on-untag.console"), r.stringList("on-untag.player"));
        }
    }

    public record Pvp(boolean enabled, long durationMillis, boolean refreshOnHit, boolean successfulHitOnly, boolean tagAttacker,
                      boolean tagVictim, boolean melee, boolean projectiles, boolean potions, boolean explosions, boolean fireAndLava,
                      boolean tamedAnimals, boolean other, boolean excludeAllies, boolean untagOnDeath, boolean untagOnKill,
                      boolean notifyStart, boolean notifyEnd, String soundStart, String soundEnd, String particleStart,
                      Commands commands) {
    }

    public record Pve(boolean enabled, long durationMillis, boolean refreshOnHit, boolean successfulHitOnly, boolean tagWhenPlayerAttacks,
                      boolean tagWhenMobAttacks, boolean hostile, boolean animals, boolean bosses, boolean custom, boolean other,
                      Set<EntityType> whitelist, Set<EntityType> blacklist, List<Pattern> nameExclusions, Set<String> customBlacklist,
                      boolean untagOnDeath, boolean notifyStart, boolean notifyEnd, String soundStart, String soundEnd,
                      boolean pvpOverridesPve, boolean pveRefreshesPvp, Commands commands) {
    }

    public record Display(boolean actionbar, boolean actionbarOnEnd, boolean bossbar, BarColor pvpBarColor, BarColor pveBarColor,
                          BarStyle barStyle, boolean bossbarProgress, boolean glowing, int barLength, String filledChar, String emptyChar,
                          String filledColor, String emptyColor, long updateTicks) {
    }

    public record ThirdParty(boolean blockInterference, boolean antiKillSteal,
                             long antiKillStealMillis, double antiKillStealHealthPercent) {
    }

    public static CombatSettings parse(ConfigReader r, long updateTicks) {
        ConfigReader p = r.section("pvp");
        ConfigReader src = p.section("sources");
        Pvp pvp = new Pvp(
                p.bool("enabled", true),
                p.durationMillis("duration-seconds", 20_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 3_600_000),
                p.bool("refresh-on-hit", true),
                p.choice("tag-mode", "damage", "damage", "successful-hit").equals("successful-hit"),
                p.bool("tag-attacker", true),
                p.bool("tag-victim", true),
                src.bool("melee", true), src.bool("projectiles", true), src.bool("potions", true), src.bool("explosions", true),
                src.bool("fire-and-lava", true), src.bool("tamed-animals", true), src.bool("other", true),
                p.bool("exclude-allies", true),
                p.bool("untag-on-death", true),
                p.bool("untag-on-kill", true),
                p.bool("notify-start", true),
                p.bool("notify-end", true),
                p.string("sounds.start", "combat-start"),
                p.string("sounds.end", "combat-end"),
                p.string("particles.start", "combat-start"),
                Commands.read(p.section("commands")));

        ConfigReader e = r.section("pve");
        ConfigReader ent = e.section("entities");
        List<Pattern> patterns = new ArrayList<>();
        for (String s : ent.stringList("name-exclusions")) {
            try {
                patterns.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException ex) {
                ent.warn("name-exclusions", s, "Invalid pattern (entry ignored)", "a name or regular expression", "entry skipped");
            }
        }
        Pve pve = new Pve(
                e.bool("enabled", false),
                e.durationMillis("duration-seconds", 10_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 3_600_000),
                e.bool("refresh-on-hit", true),
                e.choice("tag-mode", "damage", "damage", "successful-hit").equals("successful-hit"),
                e.bool("tag-when-player-attacks", true),
                e.bool("tag-when-mob-attacks", true),
                ent.bool("hostile", true), ent.bool("animals", false), ent.bool("bosses", true), ent.bool("custom", true),
                ent.bool("other", false),
                ent.entityTypes("whitelist"), ent.entityTypes("blacklist"), patterns, ent.lowerCaseSet("custom-blacklist"),
                e.bool("untag-on-death", true),
                e.bool("notify-start", true),
                e.bool("notify-end", true),
                e.string("sounds.start", "combat-start"),
                e.string("sounds.end", "combat-end"),
                e.bool("pvp-overrides-pve", true),
                e.bool("pve-hit-refreshes-pvp-tag", false),
                Commands.read(e.section("commands")));

        ConfigReader d = r.section("display");
        Display display = new Display(
                d.bool("actionbar.enabled", true),
                d.bool("actionbar.show-end-message", true),
                d.bool("bossbar.enabled", false),
                d.enumValue("bossbar.pvp-color", BarColor.class, BarColor.RED),
                d.enumValue("bossbar.pve-color", BarColor.class, BarColor.YELLOW),
                d.enumValue("bossbar.style", BarStyle.class, BarStyle.SEGMENTED_20),
                d.bool("bossbar.show-progress", true),
                d.bool("glowing.enabled", false),
                d.integer("progress-bar.length", 20, 1, 100),
                d.string("progress-bar.filled-char", "|"),
                d.string("progress-bar.empty-char", "|"),
                d.string("progress-bar.filled-color", "&c"),
                d.string("progress-bar.empty-color", "&7"),
                updateTicks);

        ConfigReader t = r.section("third-party");
        ThirdParty thirdParty = new ThirdParty(
                t.bool("block-interference", false),
                t.bool("anti-kill-steal.enabled", false),
                t.durationMillis("anti-kill-steal.protect-seconds", 10_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 600_000),
                t.percent("anti-kill-steal.only-below-health-percent", 100));

        Set<GameMode> exempt = r.enumSet("exempt-gamemodes", GameMode.class);
        Set<String> regions = new java.util.HashSet<>();
        for (String s : r.stringList("excluded-regions")) {
            regions.add(s.toLowerCase(Locale.ROOT));
        }
        return new CombatSettings(pvp, pve, display, thirdParty, WorldRules.read(r, "worlds"), regions, exempt,
                r.bool("untag-on-world-change", false), r.bool("ignore-npcs", true));
    }
}
