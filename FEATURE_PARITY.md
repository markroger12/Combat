# Feature parity

UltimateCombat is an original implementation. It aims at functional parity with the publicly
documented features of the commercial plugin DeluxeCombat, but it shares no code, assets or
branding with it and does not need it to be installed.

## How this list was made

The DeluxeCombat documentation (GitBook, SpigotMC wiki, BuiltByBit/Polymart pages) could not be
opened from the build environment. The features below were collected from search-engine
summaries of those pages, from public changelog snippets and from the requirements of this
project. Feature names describe behaviour and are not copied text. Where the reference behaviour
was unclear, UltimateCombat's own behaviour is documented instead.

## Status legend

| Status | Meaning |
|---|---|
| **Tested** | Implemented and covered by automated tests (JUnit 6 + MockBukkit). |
| **Implemented** | Implemented and compiled, but not covered by an automated test. |
| **Partial** | Some parts are implemented. The missing parts are listed. |
| **Not implemented** | Not available in this version. |

**No feature has been tested on a live Minecraft server yet.** The tests run on MockBukkit, a
simulated server (it reports itself as Spigot 1.21.11). MockBukkit does not simulate everything:
packets, real entity AI, real chunks, Folia threading and network databases are not covered. See
[Verification](#verification) below.

## Combat

| Feature | UltimateCombat | Files / commands | Status |
|---|---|---|---|
| PvP combat tag for attacker and victim | `combat-tag` module | combat.yml, `/uc tag`, `/uc untag`, `/uc combat` | **Tested** (two players, several attackers, projectiles, cancelled hits, expiry, per-world duration) |
| PvE combat tag | `combat-tag` (pve section, off by default) | combat.yml | **Tested** |
| Indirect damage attribution (projectiles, TNT, crystals, pets, potions, fire, lava) | KillService + DamageAttribution | stats.yml `kill-credit` | **Partial test**: projectiles are tested; the other sources are implemented but not tested |
| Combat display: action bar, boss bar, progress bar, glowing | `combat-tag` display | combat.yml `display` | **Implemented** |
| Commands on tag start/end | `combat-tag` | combat.yml `pvp.commands` | **Implemented** |
| Anti kill-steal and third-party interference blocking | `combat-tag` | combat.yml `third-party` | **Implemented** |
| Combat-log punishment (kill, drop and clear, punishment on rejoin, money, commands, broadcast) | `combat-log` | combatlog.yml, `/uc combatlog` | **Tested** (kill and credit, drop once, rejoin punishment applied once, no double kill credit) |
| Combat-log history | `combat-log` | `/uc combatlog history <player>` | **Tested** (history row stored) |
| Ghost player (killable copy of a combat logger) | `ghost-players` | ghosts.yml, `/uc ghosts` | **Tested** with a vanilla entity (no item duplication). Citizens NPCs: **Implemented**, not tested |
| Ghost NPC fights back (Sentinel) | — | — | **Not implemented** |
| Halt ghost spawning | `ghost-players` | `/uc ghosts halt/resume` | **Implemented** (the commands run without errors in the command test) |
| Command, flight, elytra, teleport, ender pearl, build and other restrictions in combat | `restrictions` (27 rules + command blacklist/whitelist) | restrictions.yml | **Partial test**: command blacklist, ender pearls, custom items and flight are tested; the other rules are implemented |
| Restriction cooldowns (e.g. ender pearl cooldown) | `restrictions` (rule `action: cooldown`) | restrictions.yml | **Implemented** |
| Combat borders / visual walls around safe zones | `combat-borders` | borders.yml, `/uc border` | **Tested**: pushback and pearl/teleport blocking. Client-side wall blocks: **Implemented** |
| Built-in no-PvP zones (no region plugin needed) | `combat-borders` zones | borders.yml | **Tested** |
| Legacy 1.8 combat (attack speed, sweep, legacy damage, regeneration, armour, shields, offhand) | `old-pvp` | old-pvp.yml, `/oldpvp` | **Partial test**: attack speed and sweep are tested; the other mechanics are implemented |
| Hide modern combat sounds | `old-pvp` + ProtocolLib | old-pvp.yml | **Implemented** (needs ProtocolLib, not tested) |
| Custom knockback | `knockback` | knockback.yml | **Implemented** |
| PvP intervals (timed PvP windows) | `pvp-intervals` | pvp-intervals.yml, `/pvp-interval` | **Implemented** |
| Command warmups | `command-warmups` | warmups.yml | **Implemented** |

## Player features

| Feature | UltimateCombat | Files / commands | Status |
|---|---|---|---|
| Toggle own PvP | `toggle-pvp` | pvp.yml, `/togglepvp`, `/uc pvp` | **Tested** (damage blocked both ways, confirmation, no toggling in combat) |
| Forced PvP worlds, indirect damage protection | `toggle-pvp` | pvp.yml | **Implemented** |
| New player protection (grace) | `grace` | grace.yml, `/grace` | **Tested** (protection, playtime expiry, ending on attack) |
| Grace escape, give/remove/check | `grace` | `/grace escape/give/remove/check` | **Implemented** |
| Bounties (Vault money, tax, anonymous, expiry, refund, top list, menu) | `bounties` | bounties.yml, `/bounty` | **Tested**: placing, payout once, refund once, not enough money. Expiry, menu and tracking: **Implemented** |
| Bounty tracking compass | `bounties` | `/bounty track` | **Implemented** |
| Kill streaks with milestones and announcements | `killstreaks` | killstreaks.yml | **Tested** (count, reset, best streak) |
| Statistics (kills, deaths, KDR, streaks, assists, mob kills, damage) | `stats` | stats.yml, `/pvpstats` | **Tested** |
| PvP points (fixed or ELO) and ranks | `stats` | stats.yml, `/pvppoints` | **Tested** for fixed points and ranks. ELO: **Implemented** |
| Statistics export and reset | `stats` | `/uc exportstats`, `/uc resetstats` | **Implemented** (`/uc exportstats` runs without errors in the command test) |
| Leaderboards: chat, menu, holograms, signs (with heads), tab list | `leaderboards` | leaderboards.yml, `/pvptop`, `/uc leaderboard` | **Tested** for ranking data. Holograms, signs, menu and tab list: **Implemented** |
| Rewards engine (commands, items, money, effects, points per trigger) | `rewards` | rewards.yml | **Implemented** (runs during the kill tests without assertions) |
| Anti-abuse (repeated kills, same network, alt accounts, respawn kills) | `anti-abuse` | anti-abuse.yml, `/uc abuse` | **Tested** for repeated kills and same network. Alt accounts and respawn kills: **Implemented** |
| Privacy-friendly IP checks (salted hashes only) | `anti-abuse` + storage | anti-abuse.yml `ip-checks` | **Tested** (only 64-character hashes are stored) |
| Money for kills, death penalty | `economy` | economy.yml | **Tested** for kill money. Death penalty: **Implemented** |
| Loot protection | `loot-protection` | loot.yml | **Tested** |
| Corpses / lootable graves | `corpses` | corpses.yml, `/uc corpses` | **Tested** (items stored once, looting, drop on expiry) |
| Auto respawn, void rescue, item spread, `/deathback` | `death` | death.yml | **Tested** for `/deathback`. The rest: **Implemented** |
| Custom death messages (per cause, weapon tooltips, visibility, word filter, toggle) | `death-messages` | death-messages.yml, `/deathmessages` | **Tested** (broadcast and toggle) |
| Kill recaps (clickable message, menu with inventory snapshots) | `kill-recaps` | kill-recaps.yml, `/killrecaps` | **Tested** for storage. Menu and links: **Implemented** |
| Spawn points and respawn protection | `spawnpoints` | spawnpoints.yml, `/uc spawnpoint` | **Implemented** |
| Potion restrictions, cooldowns, `/potionstack` | `potions` | potions.yml | **Implemented** |
| Golden apple cooldowns, effects, recipes (with in-game recipe editor) | `golden-apples` | apples.yml, `/uc apple` | **Tested** for cooldowns. The rest: **Implemented** |
| Totem rules and cooldowns | `totems` | totems.yml | **Implemented** |
| Enchantment limits and blocking | `enchantments` | enchantments.yml, `/uc enchantment` | **Implemented** |
| Custom combat tools (on-hit effects) | `custom-tools` | custom-tools.yml, `/uc customtools` | **Tested** |
| StatTrak weapons (tamper-protected kill counters) | `stattrak` | stattrak.yml, `/stattrak` | **Tested** |
| Head trophies and `/sellhead` | `head-trophies` | heads.yml | **Tested** (drop, money penalty, sold once) |
| Damage indicators | `damage-indicators` | damage-indicators.yml | **Tested** (shown, then removed) |
| Health bars (below name, boss bar, action bar) | `healthbars` | healthbars.yml | **Implemented**. Below-name health does not work on Folia (no scoreboard API there) |

## Administration and platform

| Feature | UltimateCombat | Status |
|---|---|---|
| Every option configurable and documented in YAML | 49 commented default files | **Tested**: every default option is read by the code, all files parse, and the defaults produce no warnings |
| Validation with readable warnings, safe fallbacks | ConfigReader, `/uc validate` | **Tested** |
| Live reload of single files or modules | `/uc reload [all\|module\|file]` | **Tested** |
| Read, explain and change settings in game | `/uc config explain/get/set` (with rollback) | **Tested** |
| In-game configuration editor | `/uc editor` | **Tested** (toggling a setting through the menu) |
| Per-world overrides | worlds.yml | **Tested** |
| Module on/off switches | features.yml | **Tested** |
| Backups and migrations | `/uc backup`, `/uc migrate` | **Implemented** (commands run in the command test) |
| Messages in language files (en_US, de_DE, custom overrides) | languages/ | **Tested**: every used key exists and German has the same keys and placeholders |
| PlaceholderAPI placeholders | placeholders.yml | **Tested** for the resolver. Registration with real PlaceholderAPI: **Implemented**, not tested |
| SQLite storage | storage.yml | **Tested** (persistence, plugin restart, offline lookups, quick rejoin while saving) |
| MySQL / MariaDB storage with data groups | storage.yml | **Implemented**, not tested (no database server available) |
| Cross-server synchronisation over Redis | storage.yml `redis` | **Partial test**: message handling is tested. The Redis transport is not tested |
| Folia support | Scheduler abstraction, `folia-supported: true` | **Implemented**, not tested on Folia |
| Every command and permission | plugin.yml, commands.yml | **Tested**: every command path runs for console, operator and player; admin commands refuse players; permissions are declared; bypass permissions are never default |
| Stand-alone short commands that never replace other plugins' commands | commands.yml | **Tested** |
| Addon system | — | **Not implemented** |
| Minecraft 1.8 – 1.20 | — | **Not supported**. This build needs Java 21 and Minecraft 1.21+ (api-version 1.21) |

## Integrations

See [INTEGRATIONS.md](INTEGRATIONS.md). In short: Vault, PlaceholderAPI, WorldGuard, Towny,
Factions, Parties, SuperiorSkyblock2, EssentialsX, LibsDisguises, Citizens, MythicMobs,
MMOItems, AdvancedEnchantments and ProtocolLib hooks are implemented. None of them has been tested
with the real plugin. Vault was exercised through its API with a test economy. Lands,
GriefPrevention, GriefDefender, HuskClaims, Sentinel, CMI, mcMMO and anti-cheat plugins have no
dedicated hook.

## Known differences

- Commands live under `/ultimatecombat` (`/uc`). Placeholders use `%ultimatecombat_...%`. Some
  alternative placeholder names exist for easier switching (`combat_mode`, `has_pvp_enabled`,
  `has_protection`, `protection_time_remaining`, `bounty_prefix`, `cooldown_<key>`).
- Permission nodes start with `ultimatecombat.`. Bypass permissions are never granted
  automatically, not even to operators (see `permissions.yml`).
- Only Minecraft 1.21+ on Java 21 is targeted.

## Verification

| Platform | How it was checked | Result |
|---|---|---|
| Paper API 1.21.11 | Compiled against it (`./gradlew build`) | Compiles |
| MockBukkit 4.116.3 (simulated server, reports Spigot 1.21.11) | 83 automated tests | All pass |
| Real Paper / Spigot 1.21.x server | — | **Unverified** |
| Folia | — | **Unverified** |
| MySQL / MariaDB, Redis | — | **Unverified** |
| Third-party plugins | — | **Unverified** (see INTEGRATIONS.md) |
