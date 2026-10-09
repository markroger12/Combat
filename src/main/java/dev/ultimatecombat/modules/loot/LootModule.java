package dev.ultimatecombat.modules.loot;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loot protection (loot.yml): items dropped on a PvP death can only be picked up by allowed
 * players for a while.
 */
public final class LootModule extends Module<LootModule.Settings> {

    public enum Mode { KILLER_ONLY, VICTIM_ONLY, KILLER_AND_VICTIM, ASSISTANTS, UNTAGGED, EVERYONE }

    public record Settings(Mode mode, long durationMillis, boolean pvpOnly, boolean blockHoppers, boolean actionbar, WorldRules worlds) {
    }

    private NamespacedKey ownersKey;
    private NamespacedKey untilKey;
    private final Map<UUID, Long> messageGuard = new ConcurrentHashMap<>();

    public LootModule(UltimateCombatPlugin plugin) {
        super(plugin, "loot-protection", "loot.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.enumValue("mode", Mode.class, Mode.KILLER_ONLY),
                r.durationMillis("duration-seconds", 30_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 3_600_000),
                r.bool("pvp-deaths-only", true),
                r.bool("block-hoppers", true),
                r.bool("actionbar", true),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onEnable() {
        ownersKey = new NamespacedKey(plugin, "loot_owners");
        untilKey = new NamespacedKey(plugin, "loot_until");
    }

    /**
     * @return players allowed to loot a death of {@code victim} (empty = everyone)
     */
    public Set<UUID> allowedLooters(Player victim, Player killer, Set<UUID> assists, World world) {
        Settings s = settings(world);
        Set<UUID> owners = new LinkedHashSet<>();
        switch (s.mode()) {
            case KILLER_ONLY -> {
                if (killer != null) owners.add(killer.getUniqueId());
            }
            case VICTIM_ONLY -> owners.add(victim.getUniqueId());
            case KILLER_AND_VICTIM -> {
                owners.add(victim.getUniqueId());
                if (killer != null) owners.add(killer.getUniqueId());
            }
            case ASSISTANTS -> {
                if (killer != null) owners.add(killer.getUniqueId());
                owners.addAll(assists);
            }
            case UNTAGGED, EVERYONE -> {
                // handled in canLoot
            }
        }
        return owners;
    }

    /**
     * Checks whether a player may take protected loot.
     */
    public boolean canLoot(Player player, Set<UUID> owners, long until) {
        if (!isEnabled() || System.currentTimeMillis() > until || plugin.bypasses(player, "loot-protection")) {
            return true;
        }
        Settings s = settings(player.getWorld());
        if (s.mode() == Mode.EVERYONE) {
            return true;
        }
        if (s.mode() == Mode.UNTAGGED) {
            CombatModule combat = plugin.active(CombatModule.class);
            return combat == null || !combat.isTagged(player);
        }
        return owners.isEmpty() || owners.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld()) || event.getDrops().isEmpty() || s.mode() == Mode.EVERYONE) {
            return;
        }
        KillService kills = plugin.kills();
        Player killer = kills.creditedKiller(victim, victim.getLastDamageCause());
        if (s.pvpOnly() && killer == null) {
            return;
        }
        Set<UUID> owners = allowedLooters(victim, killer, kills.assists(victim, killer), victim.getWorld());
        if (owners.isEmpty() && s.mode() != Mode.UNTAGGED) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        Location loc = victim.getLocation();
        long until = System.currentTimeMillis() + s.durationMillis();
        StringBuilder ownerText = new StringBuilder();
        for (UUID id : owners) {
            if (!ownerText.isEmpty()) ownerText.append(',');
            ownerText.append(id);
        }
        String ownersValue = ownerText.toString();
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            victim.getWorld().dropItemNaturally(loc, drop, item -> protect(item, ownersValue, until));
        }
    }

    /**
     * Marks an item entity as protected loot.
     */
    public void protect(Item item, String owners, long until) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(ownersKey, PersistentDataType.STRING, owners);
        pdc.set(untilKey, PersistentDataType.LONG, until);
    }

    private Set<UUID> owners(Item item) {
        String raw = item.getPersistentDataContainer().get(ownersKey, PersistentDataType.STRING);
        Set<UUID> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split(",")) {
            try {
                out.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // corrupt entry
            }
        }
        return out;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        Long until = item.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (until == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            if (System.currentTimeMillis() < until) {
                event.setCancelled(true); // mobs cannot steal protected loot
            }
            return;
        }
        if (canLoot(player, owners(item), until)) {
            return;
        }
        event.setCancelled(true);
        long left = until - System.currentTimeMillis();
        Long last = messageGuard.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (settings(player.getWorld()).actionbar() && (last == null || now - last > 1000)) {
            messageGuard.put(player.getUniqueId(), now);
            plugin.messages().actionBar(player, plugin.messages().text(player, "loot.protected-actionbar",
                    "time", Math.max(1, left / 1000)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        Long until = event.getItem().getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (until != null && System.currentTimeMillis() < until && settings(event.getItem().getWorld()).blockHoppers()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        messageGuard.remove(event.getPlayer().getUniqueId());
    }
}
