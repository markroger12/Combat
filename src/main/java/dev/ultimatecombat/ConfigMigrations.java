package dev.ultimatecombat;

import dev.ultimatecombat.core.config.ConfigService;

/**
 * Structural configuration migrations between plugin versions. Each migration moves or renames
 * keys while keeping the administrator's values; files are backed up before any change.
 *
 * <p>Version 1 is the initial layout, so there is nothing to migrate yet. Future versions add
 * entries such as:</p>
 * <pre>
 * configs.addMigration("combat.yml", new ConfigService.Migration(2, "renamed pvp.time to pvp.duration-seconds", cfg -> {
 *     if (cfg.isSet("pvp.time")) {
 *         cfg.set("pvp.duration-seconds", cfg.get("pvp.time"));
 *         cfg.set("pvp.time", null);
 *     }
 * }));
 * </pre>
 */
final class ConfigMigrations {

    private ConfigMigrations() {
    }

    static void register(ConfigService configs) {
        // Initial release: configuration layout version 1.
    }
}
