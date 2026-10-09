package dev.ultimatecombat.modules.restrictions;

import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.util.WorldRules;

import java.util.EnumSet;
import java.util.Set;

/**
 * One configurable restriction (restrictions.yml -> rules.&lt;id&gt;).
 *
 * @param id               rule id, for example {@code ender-pearls}
 * @param enabled          whether the rule is active
 * @param when             when the action is restricted
 * @param action           block the action or only rate-limit it with a cooldown
 * @param combatTypes      combat types that count as "in combat" for this rule
 * @param cooldownMillis   cooldown used when {@code action} is COOLDOWN
 * @param permission       permission required to use the action at all (empty = none)
 * @param bypassPermission players with this permission ignore the rule
 * @param worlds           worlds where the rule applies
 * @param message          custom text, or empty to use restrictions.blocked.&lt;id&gt;
 * @param sound            sound preset played when blocked
 */
public record RestrictionRule(String id, boolean enabled, When when, Action action, Set<CombatType> combatTypes, long cooldownMillis,
                              String permission, String bypassPermission, WorldRules worlds, String message, String sound) {

    public enum When { IN_COMBAT, OUT_OF_COMBAT, ALWAYS }

    public enum Action { BLOCK, COOLDOWN }

    static RestrictionRule read(String id, ConfigReader r, boolean defaultEnabled, When defaultWhen) {
        Set<CombatType> types = r.has("combat-types") ? r.enumSet("combat-types", CombatType.class) : EnumSet.of(CombatType.PVP);
        if (types.isEmpty()) {
            types = EnumSet.of(CombatType.PVP);
        }
        return new RestrictionRule(id,
                r.bool("enabled", defaultEnabled),
                r.enumValue("when", When.class, defaultWhen),
                r.enumValue("action", Action.class, Action.BLOCK),
                types,
                r.durationMillis("cooldown-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.string("permission", ""),
                r.string("bypass-permission", "ultimatecombat.bypass.restriction." + id),
                WorldRules.read(r, "worlds"),
                r.string("message", ""),
                r.string("sound", "denied"));
    }
}
