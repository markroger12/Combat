# Configuration

Every option is in a YAML file in `plugins/UltimateCombat/`. Every file starts with an
explanation, and every option has a comment with its purpose, the allowed values and the
default. This page explains the shared conventions and lists the files. For a single option, the
quickest help is in the game:

```
/uc config explain combat.yml:pvp.duration-seconds
```

## Files

### Core files

| File | Purpose |
|---|---|
| `config.yml` | Configuration management (auto-migration, backups), debug output, performance |
| `features.yml` | The on/off switch of every module |
| `worlds.yml` | Per-world overrides for any setting of any module file |
| `storage.yml` | SQLite / MySQL / MariaDB, data groups, Redis (restart required) |
| `messages.yml` | Prefix, language, per-player language, text format, time units |
| `languages/en_US.yml`, `languages/de_DE.yml` | All texts. Do not edit these, use `custom.yml` |
| `languages/custom.yml` | Your text overrides. Never touched by updates |
| `sounds.yml`, `particles.yml` | Named sound and particle presets used by every other file |
| `cooldowns.yml` | How cooldowns are shown and when they reset |
| `menus.yml` | Layout of every menu, plus the options of the in-game editor |
| `commands.yml` | Names, aliases and on/off switches of the short commands |
| `permissions.yml` | Bypass behaviour (operators, master switch, disabled bypasses) |
| `integrations.yml` | On/off switch of every third-party hook |
| `placeholders.yml` | PlaceholderAPI identifier and value formats |

### Module files

| File | Module (features.yml) | Purpose |
|---|---|---|
| `combat.yml` | `combat-tag` | PvP/PvE tagging, durations, display, commands, anti kill-steal |
| `restrictions.yml` | `restrictions` | 27 restriction rules and the command blacklist/whitelist |
| `combatlog.yml` | `combat-log` | Punishments for logging out in combat (PvP and PvE profiles) |
| `ghosts.yml` | `ghost-players` | Killable copies of combat loggers |
| `borders.yml` | `combat-borders` | Safe zones, walls, pushback |
| `old-pvp.yml` | `old-pvp` | Classic 1.8 combat |
| `knockback.yml` | `knockback` | Custom knockback |
| `pvp.yml` | `toggle-pvp` | `/togglepvp` rules, forced worlds, indirect protection |
| `grace.yml` | `grace` | New player protection |
| `bounties.yml` | `bounties` | Bounties, tax, expiry, tracking |
| `stats.yml` | `stats` | Statistics, kill credit, assists, points (fixed/ELO), ranks |
| `leaderboards.yml` | `leaderboards` | Boards, holograms, signs, tab list |
| `rewards.yml` | `rewards` | Rule based rewards |
| `killstreaks.yml` | `killstreaks` | Streak milestones |
| `anti-abuse.yml` | `anti-abuse` | Kill farming protection, IP checks (hashed) |
| `economy.yml` | `economy` | Kill money, death penalty, money safety rules |
| `loot.yml` | `loot-protection` | Protected death drops |
| `corpses.yml` | `corpses` | Lootable corpses |
| `death.yml` | `death` | Auto respawn, void rescue, item spread, `/deathback` |
| `death-messages.yml` | `death-messages` | Death message behaviour (texts are in the language files) |
| `kill-recaps.yml` | `kill-recaps` | Fight recaps |
| `spawnpoints.yml` | `spawnpoints` | Named respawn points, respawn protection |
| `potions.yml` | `potions` | Potion rules, cooldowns, `/potionstack` |
| `apples.yml` | `golden-apples` | Golden apple cooldowns, effects, recipes |
| `totems.yml` | `totems` | Totem rules and cooldowns |
| `enchantments.yml` | `enchantments` | Enchantment limits |
| `custom-tools.yml` | `custom-tools` | Weapons with on-hit effects |
| `stattrak.yml` | `stattrak` | Weapon kill counters |
| `heads.yml` | `head-trophies` | Head drops and `/sellhead` |
| `damage-indicators.yml` | `damage-indicators` | Floating damage numbers |
| `healthbars.yml` | `healthbars` | Opponent health displays |
| `pvp-intervals.yml` | `pvp-intervals` | Scheduled PvP windows |
| `warmups.yml` | `command-warmups` | Command warmups |
| `placeholders.yml` | `placeholders` | PlaceholderAPI expansion |
| — (menus.yml `editor`) | `admin-menu` | In-game configuration editor |

## Conventions

### Values

| Kind | Format | Example |
|---|---|---|
| Durations | A number in the unit named by the option, or text with units `ms`, `t` (ticks), `s`, `m`, `h`, `d`, `w`, which can be combined | `duration-seconds: 20`, `duration-seconds: "1m30s"` |
| Percentages | 0 – 100 | `chance: 25` |
| Materials | Bukkit material names, wildcards allowed | `"*_SWORD"`, `DIAMOND_AXE` |
| Entity types | Bukkit entity type names | `ZOMBIE` |
| Potion effects | `EFFECT seconds level` | `"SLOWNESS 3 2"` |
| Enchantments | Vanilla names, namespaced keys of plugin enchantments, or `custom:<name>` (AdvancedEnchantments) | `sharpness`, `custom:lifesteal` |
| Sounds / particles | Name of a preset from `sounds.yml` / `particles.yml`, or `none` | `sound: combat-start` |
| Colours | `&a`, hex `&#FF5555`, MiniMessage tags on Paper | `"&cIn combat"` |

### World filters

Most features have a `worlds` section:

```yaml
worlds:
  mode: blacklist   # blacklist = everywhere except the list, whitelist = only the list
  list: []
```

### Per-world overrides

`worlds.yml` changes any setting of any module file in one world. The file name without `.yml`
is the key:

```yaml
overrides:
  world_nether:
    combat:
      pvp:
        duration-seconds: 30
    restrictions:
      rules:
        flight:
          enabled: false
```

Overrides are validated like the normal value. Module switches and storage cannot be overridden
per world.

### Lists of named entries

Sections marked `@user-map` in the default files (for example `rewards.yml: rules`,
`custom-tools.yml: tools`, `borders.yml: zones`) belong to you entirely. Once you define the
section, only your entries count, so deleting an example really deletes it.

### Actions

Rewards, killstreak milestones, custom tools and menus use action lines:

```
[console] <command>      run as console        [player] <command>   run as the player
[message] <text>         chat to the player    [broadcast] <text>   chat to everyone
[actionbar] <text>       [title] title;subtitle;fadeIn;stay;fadeOut  [subtitle] <text>
[sound] <preset>         [particle] <preset>   [money] <amount>     [points] <amount>
[item] MATERIAL amount [name:<display name>]   [effect] EFFECT seconds level
```

## Validation and reloading

- Invalid values never stop the plugin. The default value is used instead, and the problem is
  reported with the file, the path, the bad value, what is expected and what is used.
  `/uc validate` lists every problem.
- A YAML syntax error during `/uc reload` keeps the last working version of that file. Your file
  is not changed.
- `/uc reload` reloads everything. `/uc reload combat.yml` (or a module id such as
  `/uc reload combat-tag`) reloads one file. `/uc reload messages` reloads the texts.
  `storage.yml` needs a restart.
- `/uc config set combat.yml:pvp.duration-seconds 15` changes a value, takes a backup, reloads,
  and restores the old value if the new one is invalid.
- `/uc editor` opens the in-game editor. It uses exactly the same validated path. Click a file,
  open sections, toggle booleans, change numbers with left/right click, and type text or lists in
  chat. Shift-click a module file to switch the module on or off.

## Backups and migrations

- Every change made by the plugin (config set, editor, `/uc migrate`, zone/hologram/sign
  commands) first copies the affected file to `backups/<date-time>/`. `config.yml:
  config-management.keep-backups` limits how many backup folders are kept.
- New settings from updates use their default values until you add them. `/uc migrate preview`
  lists them, and `/uc migrate` writes them into your files.

## Messages

- All texts are in `languages/`. Choose the language in `messages.yml`, or enable
  `per-player-language` to use each player's client language when a file exists.
- Put changes into `languages/custom.yml` with the same structure as `en_US.yml`. An empty text
  (`""`) disables a message.
- A message can be a single line, a list of lines, or a section with `chat`, `actionbar`, `title`,
  `subtitle`, `sound` and `prefix`.
- The `{placeholders}` available for each message are listed above it in `en_US.yml`. With
  PlaceholderAPI installed, `%placeholders%` work in every message.
