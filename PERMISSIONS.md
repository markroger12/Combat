# Permissions

| Group permission | Default | Includes |
|---|---|---|
| `ultimatecombat.*` | operators | `ultimatecombat.admin` and `ultimatecombat.user` (no bypasses) |
| `ultimatecombat.user` | everyone | every player permission below |
| `ultimatecombat.admin` | operators | every administrator permission below and `ultimatecombat.user` |
| `ultimatecombat.bypass.*` | nobody | every bypass permission |

**Bypass permissions are never granted automatically**, not even to operators or holders of
`ultimatecombat.*`. Give them explicitly (for example with LuckPerms). `permissions.yml` can
let operators bypass everything (`operators-bypass`), switch all bypasses off
(`bypass-permissions-enabled: false`, for example for events) or disable single bypasses
(`disabled-bypasses`).

## Player permissions

| Permission | Default | Description |
|---|---|---|
| `ultimatecombat.command` | everyone | Use /ultimatecombat (/uc) and see its help |
| `ultimatecombat.stats` | everyone | View own PvP statistics (/pvpstats) |
| `ultimatecombat.points` | everyone | View own PvP points (/pvppoints) |
| `ultimatecombat.leaderboard` | everyone | View leaderboards (/pvptop) |
| `ultimatecombat.togglepvp` | everyone | Toggle own PvP (/togglepvp) |
| `ultimatecombat.grace` | everyone | View and end own grace period (/grace) |
| `ultimatecombat.bounty` | everyone | View bounties (/bounty) |
| `ultimatecombat.bounty.set` | everyone | Place bounties |
| `ultimatecombat.bounty.track` | everyone | Track bounty targets with a compass |
| `ultimatecombat.deathback` | everyone | Return to the last death location (/deathback) |
| `ultimatecombat.killrecaps` | everyone | View own kill recaps (/killrecaps) |
| `ultimatecombat.deathmessages.toggle` | everyone | Hide or show death messages (/deathmessages) |
| `ultimatecombat.combat.status` | everyone | View own combat status (/uc combat) |
| `ultimatecombat.interval` | everyone | View the PvP interval schedule (/pvp-interval) |
| `ultimatecombat.sellhead` | everyone | Sell player heads (/sellhead) |
| `ultimatecombat.stattrak` | everyone | Use /stattrak |
| `ultimatecombat.stattrak.register` | everyone | Add StatTrak to a weapon |
| `ultimatecombat.oldpvp.toggle` | everyone | Switch between classic and modern combat (/oldpvp) |
| `ultimatecombat.potionstack` | everyone | Stack potions (/potionstack) |

## Administrator permissions

| Permission | Default | Description |
|---|---|---|
| `ultimatecombat.reload` | operators | Reload configuration files (/uc reload) |
| `ultimatecombat.validate` | operators | Validate configuration files (/uc validate) |
| `ultimatecombat.config` | operators | Explain and read settings (/uc config explain|get) |
| `ultimatecombat.config.set` | operators | Change settings (/uc config set and the editor) |
| `ultimatecombat.migrate` | operators | Run configuration migrations (/uc migrate) |
| `ultimatecombat.backup` | operators | Back up configuration files (/uc backup) |
| `ultimatecombat.status` | operators | View plugin status (/uc status) |
| `ultimatecombat.debug` | operators | Toggle debug logging (/uc debug) |
| `ultimatecombat.integrations` | operators | View integration status (/uc integrations) |
| `ultimatecombat.admin.editor` | operators | Open the in-game configuration editor (/uc editor) |
| `ultimatecombat.tag` | operators | Combat-tag players (/tag) |
| `ultimatecombat.untag` | operators | Remove combat tags (/uc untag) |
| `ultimatecombat.combat.status.others` | operators | View other players' combat status |
| `ultimatecombat.stats.others` | operators | View other players' statistics |
| `ultimatecombat.points.others` | operators | View other players' points |
| `ultimatecombat.stats.reset` | operators | Reset statistics (/uc resetstats) |
| `ultimatecombat.stats.export` | operators | Export statistics to CSV (/uc exportstats) |
| `ultimatecombat.togglepvp.admin` | operators | Change other players' PvP state |
| `ultimatecombat.grace.give` | operators | Give grace periods |
| `ultimatecombat.grace.remove` | operators | Remove grace periods |
| `ultimatecombat.grace.check.others` | operators | Check other players' grace periods |
| `ultimatecombat.bounty.admin` | operators | Manage all bounties |
| `ultimatecombat.bounty.anonymous` | operators | Place anonymous bounties |
| `ultimatecombat.killrecaps.others` | operators | View other players' kill recaps |
| `ultimatecombat.deathmessages.alerts` | operators | Receive death messages that are hidden from you |
| `ultimatecombat.leaderboard.admin` | operators | Manage leaderboard holograms and signs |
| `ultimatecombat.customtools.admin` | operators | List and give custom tools |
| `ultimatecombat.corpse.admin` | operators | Manage corpses |
| `ultimatecombat.corpse.loot.all` | operators | Loot any corpse |
| `ultimatecombat.ghosts.admin` | operators | Manage ghost players (/uc ghosts) |
| `ultimatecombat.combatlog.admin` | operators | View combat-log history and pending punishments |
| `ultimatecombat.border.admin` | operators | Manage combat border zones (/uc border) |
| `ultimatecombat.spawnpoint.admin` | operators | Manage spawn points (/uc spawnpoint) |
| `ultimatecombat.rewards.admin` | operators | Manage and test rewards (/uc rewards) |
| `ultimatecombat.apple.admin` | operators | Edit golden apple recipes (/uc apple) |
| `ultimatecombat.enchantment.admin` | operators | View enchantment rules (/uc enchantment) |
| `ultimatecombat.antiabuse.review` | operators | Review anti-abuse logs (/uc abuse) |
| `ultimatecombat.stattrak.admin` | operators | Reset or transfer any StatTrak weapon |
| `ultimatecombat.stattrak.delete` | operators | Remove StatTrak from weapons |
| `ultimatecombat.stattrak.free` | operators | Use StatTrak actions without paying |

## Bypass permissions

| Permission | Default | Description |
|---|---|---|
| `ultimatecombat.bypass.*` | nobody | Every bypass permission. Never granted automatically, not even to operators (see permissions.yml) |
| `ultimatecombat.bypass.anti-abuse` | nobody | Kills are never checked by anti-abuse |
| `ultimatecombat.bypass.apples` | nobody | Ignore golden apple cooldowns and limits |
| `ultimatecombat.bypass.bounty` | nobody | Cannot receive bounties |
| `ultimatecombat.bypass.bounty-cooldown` | nobody | Ignore the bounty placement cooldown |
| `ultimatecombat.bypass.combat-borders` | nobody | Pass through combat borders |
| `ultimatecombat.bypass.combat-log` | nobody | Never punished for combat logging |
| `ultimatecombat.bypass.combat-tag` | nobody | Never combat-tagged |
| `ultimatecombat.bypass.commands` | nobody | Use blocked commands in combat |
| `ultimatecombat.bypass.deathback` | nobody | Ignore /deathback cooldown and combat block |
| `ultimatecombat.bypass.enchantments` | nobody | Ignore enchantment restrictions |
| `ultimatecombat.bypass.grace` | nobody | Ignore grace protection rules |
| `ultimatecombat.bypass.headdrop` | nobody | Never drop a head on death |
| `ultimatecombat.bypass.loot-protection` | nobody | Pick up protected loot |
| `ultimatecombat.bypass.potions` | nobody | Ignore potion restrictions |
| `ultimatecombat.bypass.restrictions` | nobody | Ignore every combat restriction |
| `ultimatecombat.bypass.togglepvp-cooldown` | nobody | Ignore the /togglepvp cooldown |
| `ultimatecombat.bypass.totems` | nobody | Ignore totem restrictions |
| `ultimatecombat.bypass.warmups` | nobody | Skip command warmups |

## Restriction rule bypasses

Each rule in `restrictions.yml` has its own bypass permission (`bypass-permission`, default `ultimatecombat.bypass.restriction.<rule>`). `ultimatecombat.bypass.restriction.*` and `ultimatecombat.bypass.restrictions` bypass every rule.

| Permission | Default | Description |
|---|---|---|
| `ultimatecombat.bypass.restriction.*` | nobody | Bypass every individual restriction rule (restrictions.yml) |
| `ultimatecombat.bypass.restriction.flight` | nobody | Bypass the 'flight' restriction |
| `ultimatecombat.bypass.restriction.elytra` | nobody | Bypass the 'elytra' restriction |
| `ultimatecombat.bypass.restriction.riptide` | nobody | Bypass the 'riptide' restriction |
| `ultimatecombat.bypass.restriction.creative` | nobody | Bypass the 'creative' restriction |
| `ultimatecombat.bypass.restriction.god-mode` | nobody | Bypass the 'god-mode' restriction |
| `ultimatecombat.bypass.restriction.vanish` | nobody | Bypass the 'vanish' restriction |
| `ultimatecombat.bypass.restriction.disguise` | nobody | Bypass the 'disguise' restriction |
| `ultimatecombat.bypass.restriction.teleport` | nobody | Bypass the 'teleport' restriction |
| `ultimatecombat.bypass.restriction.containers` | nobody | Bypass the 'containers' restriction |
| `ultimatecombat.bypass.restriction.block-break` | nobody | Bypass the 'block-break' restriction |
| `ultimatecombat.bypass.restriction.block-place` | nobody | Bypass the 'block-place' restriction |
| `ultimatecombat.bypass.restriction.sleeping` | nobody | Bypass the 'sleeping' restriction |
| `ultimatecombat.bypass.restriction.armor-switching` | nobody | Bypass the 'armor-switching' restriction |
| `ultimatecombat.bypass.restriction.inventory` | nobody | Bypass the 'inventory' restriction |
| `ultimatecombat.bypass.restriction.mending` | nobody | Bypass the 'mending' restriction |
| `ultimatecombat.bypass.restriction.enchanting` | nobody | Bypass the 'enchanting' restriction |
| `ultimatecombat.bypass.restriction.potions` | nobody | Bypass the 'potions' restriction |
| `ultimatecombat.bypass.restriction.ender-pearls` | nobody | Bypass the 'ender-pearls' restriction |
| `ultimatecombat.bypass.restriction.chorus-fruit` | nobody | Bypass the 'chorus-fruit' restriction |
| `ultimatecombat.bypass.restriction.golden-apples` | nobody | Bypass the 'golden-apples' restriction |
| `ultimatecombat.bypass.restriction.enchanted-golden-apples` | nobody | Bypass the 'enchanted-golden-apples' restriction |
| `ultimatecombat.bypass.restriction.totems` | nobody | Bypass the 'totems' restriction |
| `ultimatecombat.bypass.restriction.shields` | nobody | Bypass the 'shields' restriction |
| `ultimatecombat.bypass.restriction.tnt` | nobody | Bypass the 'tnt' restriction |
| `ultimatecombat.bypass.restriction.wind-charges` | nobody | Bypass the 'wind-charges' restriction |
| `ultimatecombat.bypass.restriction.custom-items` | nobody | Bypass the 'custom-items' restriction |
| `ultimatecombat.bypass.restriction.entity-interactions` | nobody | Bypass the 'entity-interactions' restriction |

## Permissions from configuration

Some settings take a permission of your choice, for example `permission` of a reward rule, a
custom tool, a spawn point or a restriction rule, and menu items (`menus.yml`). Those are
checked as written and do not need to be declared anywhere.
