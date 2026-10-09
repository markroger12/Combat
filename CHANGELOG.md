# Changelog

## 1.0.0 – unreleased

First version.

### Added

- Module framework with on/off switches (`features.yml`), per-world overrides (`worlds.yml`),
  validated configuration, live reload, backups, migrations, `/uc config explain|get|set` and an
  in-game configuration editor (`/uc editor`).
- Combat tagging (PvP and PvE) with damage attribution for projectiles, pets, TNT, crystals,
  potions, fire and lava; action bar, boss bar and glowing displays.
- Restrictions (27 rules and a command blacklist/whitelist), combat-log punishments with
  history, ghost players, combat borders with built-in zones, classic 1.8 combat, custom
  knockback, PvP intervals and command warmups.
- Toggle PvP, grace protection, bounties, kill streaks, statistics with fixed or ELO points and
  ranks, leaderboards (chat, menu, holograms, signs, tab list), a rewards engine, anti-abuse with
  hashed IP checks and an economy module.
- Loot protection, corpses, head trophies, StatTrak weapons, kill recaps, custom death messages,
  damage indicators, health bars, potion/apple/totem/enchantment rules, custom combat tools,
  spawn points and `/deathback`.
- SQLite, MySQL and MariaDB storage with data groups and optional Redis synchronisation.
- English and German language files plus `custom.yml` overrides.
- Hooks for Vault, PlaceholderAPI, WorldGuard, Towny, Factions, Parties, SuperiorSkyblock2,
  EssentialsX, LibsDisguises, Citizens, MythicMobs, MMOItems, AdvancedEnchantments and
  ProtocolLib.
- Bukkit and Folia schedulers.
- 83 automated tests (JUnit 6 and MockBukkit).

### Known limitations

- Not yet tested on a live server, on Folia, with MySQL/MariaDB, with Redis or with any of the
  integrated plugins. See [FEATURE_PARITY.md](FEATURE_PARITY.md).
- Minecraft 1.21+ and Java 21 only.
- No Sentinel integration (ghost NPCs do not fight back) and no addon system.
