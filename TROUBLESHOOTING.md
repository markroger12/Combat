# Troubleshooting

Start with these three commands:

| Command | Shows |
|---|---|
| `/uc status` | Platform, storage, active and disabled modules, configuration problems, loaded players |
| `/uc validate` | Every configuration problem with file, path, value and the value used instead |
| `/uc integrations` | Every hook: active, not installed, disabled or failed (with the reason) |

`/uc debug` shows live debug messages in your chat. `config.yml: debug` writes them to the console
or to `logs/debug-<date>.log` and can be limited to categories (for example `combat`,
`combatlog`, `kills`, `integrations`).

## The plugin does not enable

- **Java version**: Java 21 is required. The console then shows an `UnsupportedClassVersionError`.
- **Minecraft version**: 1.21 or newer is required (`api-version: 1.21`).
- <a id="libraries-cannot-be-downloaded"></a>**Libraries cannot be downloaded**: on the first
  start the server downloads HikariCP, MariaDB Connector/J and Jedis from Maven Central into its
  `libraries/` folder. Servers without internet access must get these files once from a machine
  with access, using the same folder layout (versions are listed under `libraries` in plugin.yml).

## Nobody gets combat tagged

Check in this order:

1. `features.yml: modules.combat-tag` is `true` and `combat.yml: pvp.enabled` is `true`.
2. **New players are protected by grace** (`grace.yml`, 30 minutes of playtime by default).
   Attacks on them are cancelled, so nobody is tagged. Check with `/grace check <player>`.
3. One of the players has PvP turned off (`/togglepvp`, `pvp.yml`).
4. The players are in a no-PvP area: a zone in `borders.yml`, a WorldGuard region with
   `pvp deny`, a claim, or a PvP interval (`pvp-intervals.yml`) that closes PvP.
5. The game mode is listed in `combat.yml: exempt-gamemodes` (creative and spectator by
   default).
6. The world is filtered out (`combat.yml: worlds`).
7. Another plugin cancels the damage. UltimateCombat never tags for cancelled hits.

## A player was punished for logging out although they were not fighting

`/uc combatlog history <player>` shows every punishment with the opponent and the remaining
combat time. `combatlog.yml: minimum-remaining-seconds` ignores logouts just before the tag ends,
and `punish-on` decides which disconnect reasons count (quit, kick, timeout). Kicks containing
`ignored-kick-reasons` (for example "server closed") are never punished, and server shutdowns
never punish anybody.

## Short commands like /togglepvp do not work

Another plugin already uses that name. UltimateCombat never replaces commands of other plugins.
The console says which plugin owns the name at start-up. Use `/ultimatecombat:togglepvp` or
`/uc togglepvp`, rename the short form in `commands.yml`, or set `override-existing: true` there.

## Money features do nothing

Bounties, kill money, penalties and head selling need Vault **and** an economy plugin
(EssentialsX, CMI ...). `/uc integrations` must show `vault` as active. With Vault but no economy
plugin, the console says "no economy plugin is registered with it".

## Placeholders show as text

- PlaceholderAPI must be installed, and `/uc integrations` must show `placeholderapi` as active.
- `features.yml: modules.placeholders` must be `true`.
- The identifier is `ultimatecombat` unless changed in `placeholders.yml`, for example
  `%ultimatecombat_kills%`.
- Statistics placeholders show `values.empty` for offline players (their data is not loaded).

## Database problems

- MySQL/MariaDB connection failures are logged with the reason. With
  `fallback-to-sqlite: true` the server keeps running on the local SQLite file. The console says
  so in red, and statistics are then not shared with other servers.
- Every change in `storage.yml` needs a restart. `/uc reload` tells you when the file changed.
- Saving errors are retried. The console logs "Could not save data of <player> (will retry)".

## Leaderboards are empty

- `features.yml: modules.stats` and `modules.leaderboards` must be on.
- Boards refresh every `refresh-interval-seconds` (60 by default). `/uc leaderboard refresh`
  refreshes immediately.
- Players with fewer than `kdr-minimum-kills` kills do not appear on the KDR board.

## Holograms, corpses or ghost players are missing

They are temporary entities that are never saved in the world. They appear again when their chunk
loads. Ghost players and corpse visuals are removed on restart. Corpse items are stored in the
database and come back, and pending combat-log punishments are applied on rejoin.

## Below-name health does not show

- On Folia, the scoreboard API does not exist, so below-name health is disabled and a warning is
  logged. Boss bar and action bar health still work.
- Scoreboard or tab plugins that rebuild player scoreboards can remove the objective. Set
  `healthbars.yml: below-name.custom-scoreboards: true`, or disable the feature in one of the
  plugins.

## A YAML file has an error

The console and `/uc validate` show the line and column. During `/uc reload` the last working
version of the file stays active, so nothing breaks. On a fresh start the bundled defaults of that
file are used until it is fixed. UltimateCombat never modifies a broken file.

## Reporting a problem

Please include the server version (`/version`), `/uc status`, `/uc integrations`,
`/uc validate`, and the console log from the start of the server until the problem.
