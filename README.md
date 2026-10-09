# UltimateCombat

A modular combat plugin for Paper, Spigot and Folia (Minecraft 1.21+). Every feature can be
switched on or off, and every option lives in commented YAML files.

UltimateCombat is an original project. Its features are modelled on the publicly documented
features of the commercial plugin DeluxeCombat, but it contains no code or assets from it, is not
affiliated with it and does not need it.

## Features

- **Combat tagging** for PvP and PvE, with action bar, boss bar, glowing and commands on start/end.
  Damage from projectiles, pets, TNT, crystals, potions and fire is credited to the right player.
- **Combat logging punishments**: kill, drop and clear, punishment on rejoin, money, commands,
  broadcasts and history. Optional **ghost players** keep a killable copy of the logger in the
  world, without ever duplicating items.
- **Restrictions in combat**: 27 rules (flight, elytra, teleport, ender pearls, containers,
  totems, custom items ...) plus a command blacklist or whitelist.
- **Combat borders** with built-in no-PvP zones, client-side walls and pushback.
- **Classic 1.8 combat**, custom knockback, PvP intervals and command warmups.
- **Toggle PvP**, **new player protection (grace)**, **bounties**, **kill streaks**,
  **statistics with points/ELO and ranks**, **leaderboards** (chat, menu, holograms, signs,
  tab list) and a **rewards engine**.
- **Anti-abuse** against kill farming. IP checks store only salted hashes.
- Loot protection, corpses, head trophies, StatTrak weapons, kill recaps, custom death messages,
  damage indicators, health bars, potion/apple/totem/enchantment rules and custom combat tools.
- **Storage** in SQLite, MySQL or MariaDB, with data groups and optional Redis synchronisation
  across servers.
- **Administration**: `/uc validate`, `/uc reload`, `/uc config explain|get|set`, an in-game
  configuration editor, backups, migrations, per-world overrides and an English and German
  language file.
- **Integrations**: Vault, PlaceholderAPI, WorldGuard, Towny, Factions, Parties,
  SuperiorSkyblock2, EssentialsX, LibsDisguises, Citizens, MythicMobs, MMOItems,
  AdvancedEnchantments and ProtocolLib. All of them are optional.

## Status

Version 1.0.0 compiles against Paper API 1.21.11 and passes 83 automated tests on MockBukkit, a
simulated server. **It has not been tested on a live server yet**, nor with MySQL/MariaDB,
Redis, Folia or any third-party plugin. [FEATURE_PARITY.md](FEATURE_PARITY.md) lists exactly
what is tested and what is not.

## Requirements

- Java 21
- Paper, Spigot or Folia for Minecraft 1.21 or newer (developed against 1.21.11)
- Internet access on the first start: the server downloads HikariCP, the MariaDB driver and Jedis
  from Maven Central (see `libraries` in plugin.yml)

## Quick start

1. Put `UltimateCombat-1.0.0.jar` into `plugins/` and start the server.
2. Look at `plugins/UltimateCombat/features.yml` and switch modules on or off.
3. Change settings in the other files, then run `/uc validate` and `/uc reload`.
4. Grant permissions (see [PERMISSIONS.md](PERMISSIONS.md)). Bypass permissions are never given
   automatically, not even to operators.

## Documentation

| File | Contents |
|---|---|
| [INSTALLATION.md](INSTALLATION.md) | Installing, databases, networks, upgrading, building from source |
| [CONFIGURATION.md](CONFIGURATION.md) | Every configuration file and the shared conventions |
| [COMMANDS.md](COMMANDS.md) | All commands |
| [PERMISSIONS.md](PERMISSIONS.md) | All permissions |
| [INTEGRATIONS.md](INTEGRATIONS.md) | Supported plugins and verification status |
| [FEATURE_PARITY.md](FEATURE_PARITY.md) | Feature list with implementation and test status |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common problems |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

## Building

```
./gradlew build
```

The JAR is written to `build/libs/UltimateCombat-<version>.jar`. The build runs all tests. Use
`./gradlew build -x test` to skip them. See [INSTALLATION.md](INSTALLATION.md#building-from-source).

## Project layout

```
src/main/java/dev/ultimatecombat/
  UltimateCombatPlugin.java   plugin lifecycle and wiring
  api/                        public events and enums for other plugins
  commands/                   core /uc commands
  core/                       configuration, messages, storage, scheduler (Bukkit/Folia),
                              commands, menus, kill pipeline, economy, integrations framework
  integrations/               third-party hooks (loaded only when the plugin is present)
  modules/                    one package per feature module
src/main/resources/           plugin.yml, default configuration and language files
src/test/java/                JUnit + MockBukkit tests
stubs/                        compile-only API declarations for Vault, PlaceholderAPI and
                              ProtocolLib (never shipped in the JAR)
```

## API

Other plugins can listen to `CombatTagEvent`, `CombatUntagEvent`, `CombatLogEvent`,
`PvPToggleEvent`, `GraceChangeEvent`, `BountyEvent`, `CombatKillEvent` and `KillstreakEvent`
(package `dev.ultimatecombat.api.event`). Tagging, combat-log, PvP toggle and bounty placement
events can be cancelled.
