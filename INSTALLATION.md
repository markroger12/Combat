# Installation

## Requirements

| | |
|---|---|
| Java | 21 or newer |
| Server | Paper, Spigot or Folia, Minecraft 1.21 or newer (developed against 1.21.11) |
| Optional | Vault + an economy plugin (money features), PlaceholderAPI, WorldGuard and the other plugins listed in [INTEGRATIONS.md](INTEGRATIONS.md) |

Older Minecraft versions (1.8 – 1.20) are not supported by this build.

## Installing

1. Stop the server.
2. Copy `UltimateCombat-<version>.jar` into `plugins/`.
3. Start the server. On the first start the server downloads three libraries from Maven Central
   (HikariCP, MariaDB Connector/J, Jedis). Servers without internet access need them in the
   server's library folder. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md#libraries-cannot-be-downloaded).
4. `plugins/UltimateCombat/` now contains every configuration file, `languages/`, `data/`
   (the SQLite database and the StatTrak secret), `backups/` and, when enabled, `logs/`.
5. Open `features.yml`, switch on the modules you want and run `/uc reload`.

The console prints a summary on start, for example
`UltimateCombat 1.0.0 enabled on PAPER 1.21.11 in 850ms (21/35 modules active, storage: SQLITE)`.
Configuration problems are reported there and with `/uc validate`.

## Storage

`storage.yml` selects the database. **Every change in this file needs a full restart.**

### SQLite (default)

Nothing to set up. Data is stored in `plugins/UltimateCombat/data/database.db`. To back it up,
copy the file while the server is stopped.

### MySQL / MariaDB

```yaml
type: mysql            # or mariadb
data-group: default
table-prefix: uc_
mysql:
  host: db.example.com
  port: 3306
  database: minecraft
  username: minecraft
  password: "secret"
  use-ssl: false
```

The tables are created automatically (schema version 1). With `fallback-to-sqlite: true` (the
default), the server uses the local SQLite file when the database cannot be reached on start-up,
and logs a clear warning. MySQL and MariaDB support is implemented but has **not been tested**
against a real database server yet.

### Several servers (networks)

- Point every server to the same MySQL/MariaDB database.
- Servers with the same `data-group` share statistics, bounties, PvP states and pending
  combat-log punishments. Use different groups to keep, for example, survival and practice apart.
- Statistics are saved as increments (`kills = kills + 1`), so servers never overwrite each
  other's changes.
- Optional: enable `redis` in storage.yml on every server, with the same channel and a different
  `server-id` per server. Servers then tell each other immediately when a bounty or a profile
  changed, instead of waiting for the next load. The Redis transport has **not been tested**
  against a real Redis server.

## Upgrading

1. Make a backup of `plugins/UltimateCombat/` (or run `/uc backup`).
2. Replace the JAR and restart.
3. New settings that your files do not contain yet use their default values. The start-up
   summary and `/uc validate` say how many are missing. `/uc migrate preview` shows what would
   be added, and `/uc migrate` adds them, after taking a backup into `backups/`.
   Your values and comments are never overwritten.

## Building from source

```
git clone <repository>
cd <repository>
./gradlew build
```

- The JAR is created at `build/libs/UltimateCombat-<version>.jar`.
- `./gradlew test` runs the 83 automated tests (MockBukkit).
- Java 21 is required.

Gradle downloads `paper-api` from `https://repo.papermc.io` and everything else from Maven
Central. If your network blocks repo.papermc.io, you can publish the Paper API to your local Maven
repository (`~/.m2`), and the build picks it up from there. The project was developed this way:
the API was built from the `ver/1.21.11` branch of
[PaperMC/Paper](https://github.com/PaperMC/Paper) with a minimal Gradle build for the `paper-api`
sources and published with `publishToMavenLocal`. This is an advanced workaround. Using
repo.papermc.io is the normal way.

The `stubs` sub-project contains small, hand-written declarations of the Vault, PlaceholderAPI
and ProtocolLib API types that UltimateCombat uses. They are needed only for compiling and are
never put into the plugin JAR. At runtime the real plugins provide these classes.
