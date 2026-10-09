# Commands

All commands are sub-commands of `/ultimatecombat` (alias `/uc`). Many player commands also have
a short form (`/togglepvp`, `/pvptop` ...). Short forms are configured in `commands.yml`: they can
be renamed, given aliases or switched off, and they never replace a command of another plugin
(the short form is skipped with a console notice and stays available as `/ultimatecombat:<name>`).

`<>` = required, `[]` = optional. A command of a disabled module answers that the feature is
disabled. Every command has tab completion.

| Command | Short form | Permission | Description |
|---|---|---|---|
| `/uc tag <player> [seconds] [pvp\|pve]` | `/tag` | `ultimatecombat.tag` | Put a player into combat |
| `/uc untag <player\|all>` |  | `ultimatecombat.untag` | Remove a player from combat |
| `/uc combat [player]` |  | `ultimatecombat.combat.status` | Show combat status |
| `/uc combatlog <history\|pending\|clear> <player>` |  | `ultimatecombat.combatlog.admin` | Combat log history and pending punishments |
| `/uc combatlog history <player>` |  | `ultimatecombat.combatlog.admin` | see above |
| `/uc combatlog pending <player>` |  | `ultimatecombat.combatlog.admin` | see above |
| `/uc combatlog clear <player>` |  | `ultimatecombat.combatlog.admin` | see above |
| `/uc ghosts <list\|remove\|halt\|resume>` |  | `ultimatecombat.ghosts.admin` | Manage ghost players |
| `/uc ghosts list` |  | `ultimatecombat.ghosts.admin` | see above |
| `/uc ghosts remove <player>` |  | `ultimatecombat.ghosts.admin` | see above |
| `/uc ghosts halt` |  | `ultimatecombat.ghosts.admin` | see above |
| `/uc ghosts resume` |  | `ultimatecombat.ghosts.admin` | see above |
| `/uc border <pos1\|pos2\|create\|delete\|list>` |  | `ultimatecombat.border.admin` | Manage built-in no-PvP zones |
| `/uc border pos1` |  | `ultimatecombat.border.admin` | see above |
| `/uc border pos2` |  | `ultimatecombat.border.admin` | see above |
| `/uc border create <name>` |  | `ultimatecombat.border.admin` | see above |
| `/uc border delete <name>` |  | `ultimatecombat.border.admin` | see above |
| `/uc border list` |  | `ultimatecombat.border.admin` | see above |
| `/uc oldpvp [on\|off]` | `/oldpvp` | `ultimatecombat.oldpvp.toggle` | Switch between classic and modern combat |
| `/uc togglepvp [on\|off\|confirm\|status]` | `/togglepvp` | `ultimatecombat.togglepvp` | Turn your PvP on or off |
| `/uc pvp <player> <on\|off\|reset\|force-on\|force-off\|unforce\|status>` |  | `ultimatecombat.togglepvp.admin` | Admin PvP state management |
| `/uc grace [status\|disable\|confirm\|escape\|give\|remove\|check]` | `/grace` | `ultimatecombat.grace` | New player protection |
| `/uc abuse <player>` |  | `ultimatecombat.antiabuse.review` | Anti-abuse review |
| `/uc stats [player]` | `/pvpstats` | `ultimatecombat.stats` | Show PvP statistics |
| `/uc points [player]` | `/pvppoints` | `ultimatecombat.points` | Show PvP points |
| `/uc resetstats <player\|all> [confirm]` |  | `ultimatecombat.stats.reset` | Reset statistics |
| `/uc exportstats` |  | `ultimatecombat.stats.export` | Export statistics to a CSV file |
| `/uc rewards trigger <player> <custom-trigger>` |  | `ultimatecombat.rewards.admin` | Trigger reward rules |
| `/uc spawnpoint <create\|delete\|list\|tp\|menu>` |  | `ultimatecombat.spawnpoint.admin` | Manage spawn points |
| `/uc spawnpoint create <name> [permission]` |  | `ultimatecombat.spawnpoint.admin` | see above |
| `/uc spawnpoint delete <name>` |  | `ultimatecombat.spawnpoint.admin` | see above |
| `/uc spawnpoint list` |  | `ultimatecombat.spawnpoint.admin` | see above |
| `/uc spawnpoint tp <name>` |  | `ultimatecombat.spawnpoint.admin` | see above |
| `/uc spawnpoint menu` |  | `ultimatecombat.spawnpoint.admin` | see above |
| `/uc deathback` | `/deathback` | `ultimatecombat.deathback` | Return to your last death location |
| `/uc potionstack` | `/potionstack` | `ultimatecombat.potionstack` | Stack the potions in your inventory |
| `/uc enchantment` |  | `ultimatecombat.enchantment.admin` | Enchantment restrictions |
| `/uc apple recipe <golden\|enchanted>` |  | `ultimatecombat.apple.admin` | Golden apple settings |
| `/uc pvp-interval` | `/pvp-interval` | `ultimatecombat.interval` | Show PvP time windows |
| `/uc sellhead` | `/sellhead` | `ultimatecombat.sellhead` | Sell a head trophy |
| `/uc bounty [set\|list\|top\|info\|track\|remove]` | `/bounty` | `ultimatecombat.bounty` | Player bounties |
| `/uc corpses <list\|remove>` |  | `ultimatecombat.corpse.admin` | Manage corpses |
| `/uc corpses list` |  | `ultimatecombat.corpse.admin` | see above |
| `/uc corpses remove <player> [drop]` |  | `ultimatecombat.corpse.admin` | see above |
| `/uc stattrak <register\|inspect\|reset\|delete\|transfer>` | `/stattrak` | `ultimatecombat.stattrak` | Weapon kill counters |
| `/uc killrecaps [view <id>]` | `/killrecaps` | `ultimatecombat.killrecaps` | Show your recent kill recaps |
| `/uc deathmessages [on\|off]` | `/deathmessages` | `ultimatecombat.deathmessages.toggle` | Hide or show death messages |
| `/uc customtools <list\|give> [player] [tool] [amount]` |  | `ultimatecombat.customtools.admin` | List and give custom combat tools |
| `/uc top [board] [page]` | `/pvptop` | `ultimatecombat.leaderboard` | Show the leaderboards |
| `/uc leaderboard <refresh\|hologram\|sign> ...` |  | `ultimatecombat.leaderboard.admin` | Manage leaderboard holograms and signs |
| `/uc editor [file]` |  | `ultimatecombat.admin.editor` | Open the in-game configuration editor |
| `/uc help` |  | `ultimatecombat.command` | Show this help |
| `/uc reload [all\|<module>\|<file>]` |  | `ultimatecombat.reload` | Reload configuration files |
| `/uc validate` |  | `ultimatecombat.validate` | Check every configuration file for problems |
| `/uc config <explain\|get\|set> <file:path> [value]` |  | `ultimatecombat.config` | Read, explain or change a setting |
| `/uc config explain <file:path>` |  | `ultimatecombat.config` | see above |
| `/uc config get <file:path>` |  | `ultimatecombat.config` | see above |
| `/uc config set <file:path> <value>` |  | `ultimatecombat.config.set` | see above |
| `/uc migrate [preview]` |  | `ultimatecombat.migrate` | Add new settings to your files (backup first) |
| `/uc backup` |  | `ultimatecombat.backup` | Back up every configuration file |
| `/uc status` |  | `ultimatecombat.status` | Show plugin status |
| `/uc version` |  | `ultimatecombat.command` | Show the plugin version |
| `/uc debug [toggle\|counters]` |  | `ultimatecombat.debug` | Toggle live debug output |
| `/uc integrations` |  | `ultimatecombat.integrations` | Show detected plugin integrations |

## Notes

- `/uc reload` accepts `all` (default), `messages`, a module id (`combat-tag`) or a file name
  (`combat.yml`).
- `/uc config set <file:path> <value>` validates the value, keeps a backup and restores the old
  value when the new one is rejected. Lists are written as comma separated values, `[]` empties a
  list.
- `/uc tag <player> [seconds] [pvp|pve]` and `/uc untag <player|all>` work from the console.
- `/uc ghosts halt` stops ghost players from spawning (for example during maintenance) until
  `/uc ghosts resume`.
- `/uc leaderboard hologram create <id> <board> [lines]` places a hologram two blocks above you;
  `/uc leaderboard sign create <board> <position>` turns the sign you look at into a
  leaderboard sign. Both are saved in `leaderboards.yml`.
- `/uc border pos1`, `/uc border pos2` and `/uc border create <name>` create a no-PvP zone in
  `borders.yml`.
- `/bounty set <player> <amount> [anonymous]` places a bounty, `/bounty track <player>` gives a
  tracking compass.
- `/uc rewards trigger <player> <custom-trigger>` runs reward rules with `trigger: custom` (for
  other plugins or command blocks).
