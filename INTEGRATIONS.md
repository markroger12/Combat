# Integrations

All integrations are optional. A hook activates only when the other plugin is installed and the
hook is enabled in `integrations.yml`. Plugins that are installed or enabled later (for example
by a plugin manager) are hooked automatically. A missing, disabled or failing hook never breaks
UltimateCombat; the feature that needs it simply stays inactive. `/uc integrations` shows the
status of every hook, including the reason when a hook failed.

UltimateCombat never downloads or installs other plugins.

## Verification status

**None of the hooks has been tested with the real plugin.** They were written against the public
API of each plugin and compile, and the hook framework (detection, late loading, switching off,
failure isolation) is covered by automated tests. Vault was exercised through its API with an
in-memory test economy. Please report problems together with the output of `/uc integrations`.

| Plugin | Hook id | What UltimateCombat uses it for | How |
|---|---|---|---|
| Vault (+ an economy plugin) | `vault` | Money: bounties, kill money, penalties, head selling, StatTrak costs | Vault economy API |
| PlaceholderAPI | `placeholderapi` | `%placeholders%` in messages, and the `%ultimatecombat_...%` expansion | PlaceholderAPI API |
| WorldGuard 7 | `worldguard` | `pvp` flag (combat borders, pre-hit checks) and region ids for region based settings | Reflection on the WorldGuard 7 API |
| Towny | `towny` | Town/world PvP status and allies (town/nation) | Reflection on TownyAPI and CombatUtil |
| Factions (FactionsUUID / SaberFactions) | `factions` | Allies by relation (`ally-relations`, default MEMBER and ALLY) | Reflection on the FPlayers API |
| Parties | `parties` | Party members are allies | Reflection on the Parties API |
| SuperiorSkyblock2 | `superiorskyblock2` | Island members are allies | Reflection on SuperiorSkyblockAPI |
| EssentialsX | `essentials` | God mode and vanish state (restrictions) | Reflection on the Essentials user API |
| LibsDisguises | `libsdisguises` | Detect and remove disguises (restrictions) | Reflection on DisguiseAPI |
| Citizens | `citizens` | Player-shaped ghost NPCs, kept in a temporary in-memory registry that is never saved | Reflection on CitizensAPI |
| MythicMobs 5 | `mythicmobs` | Custom mob ids for PvE tagging, rewards and filters; configurable boss types | Reflection on MythicBukkit |
| MMOItems | `mmoitems` | Custom item ids (`type:id`) for item restrictions | Reflection on MMOItems |
| AdvancedEnchantments | `advancedenchantments` | Custom enchantments in enchantment limits (`custom:<name>`) | Reflection on AEAPI |
| ProtocolLib | `protocollib` | Hiding modern combat sounds for classic-combat players | ProtocolLib packet listener |

"Reflection" means UltimateCombat calls the other plugin's public API by name at runtime instead
of compiling against it. Each hook checks the API when it is enabled and disables itself with a
console message if the API does not match (for example after a breaking change in that plugin).

## Allies

`combat.yml: pvp.exclude-allies` uses the ally hooks (Towny, Factions, Parties,
SuperiorSkyblock2). Whether allies can hurt each other at all is decided by those plugins
themselves.

## Plugins without a dedicated hook

| Plugin | Notes |
|---|---|
| Lands, GriefPrevention, GriefDefender, HuskClaims and other claim plugins | They cancel damage in protected areas themselves, and UltimateCombat ignores cancelled damage, so nobody is tagged there. For features that must ask *before* a hit (knockback changes, pre-hit checks) enable `generic-protection-check` in `integrations.yml`: it asks all plugins with a harmless test damage event. |
| Sentinel | Not integrated. Ghost NPCs do not fight back. |
| CMI | No dedicated hook. Vanish is only detected if CMI sets the standard `vanished` metadata (not verified). |
| SuperVanish, PremiumVanish | Detected through the standard `vanished` metadata (`vanish-metadata` in integrations.yml). |
| LuckPerms and other permission plugins | Standard permissions; no hook needed. |
| Multiverse and other world managers | Worlds are referenced by name; no hook needed. |
| Hologram plugins (DecentHolograms ...) | Not needed: leaderboard holograms and corpse labels use built-in text displays. |
| EcoEnchants and other plugins that register real enchantments | Use their namespaced key in `enchantments.yml` (for example `someplugin:lifesteal`). |
| mcMMO, anti-cheat plugins | No hook. With `generic-protection-check` enabled they may see the test damage events. |

## For developers

UltimateCombat fires Bukkit events in `dev.ultimatecombat.api.event`:

| Event | Cancellable | When |
|---|---|---|
| `CombatTagEvent` | yes | A player is tagged or the tag is refreshed. The duration can be changed. |
| `CombatUntagEvent` | no | A tag ends (with an `UntagReason`) |
| `CombatLogEvent` | yes | A tagged player disconnects and would be punished |
| `PvPToggleEvent` | yes | A player's PvP preference changes |
| `GraceChangeEvent` | no | Grace starts or ends |
| `BountyEvent` | placing only | A bounty is placed (cancelling stops it), claimed, expired or removed (information only) |
| `CombatKillEvent` | no | A player death was processed (killer, assists, whether rewards counted) |
| `KillstreakEvent` | no | A kill streak changes |
