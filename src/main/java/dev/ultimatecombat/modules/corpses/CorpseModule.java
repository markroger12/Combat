package dev.ultimatecombat.modules.corpses;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.ItemSerializer;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.grace.GraceModule;
import dev.ultimatecombat.modules.loot.LootModule;
import dev.ultimatecombat.modules.spawn.SpawnpointModule;
import dev.ultimatecombat.modules.togglepvp.TogglePvpModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lootable corpses and gravestones (corpses.yml). Items are kept in a virtual inventory and in the
 * database (restart-safe); the visuals are display entities and never change world blocks.
 */
public final class CorpseModule extends Module<CorpseModule.Settings> {

    public enum Appearance { MANNEQUIN, ARMOR_STAND, HEAD, CHEST }

    public enum OnExpire { DROP_ITEMS, DELETE_ITEMS }

    public record Settings(Appearance appearance, boolean pvpOnly, long durationMillis, OnExpire onExpire, boolean removeWhenEmpty,
                           boolean shiftClickOnly, boolean storeExperience, boolean hologram, boolean ownerCanAlwaysLoot,
                           boolean denyGrace, boolean denyPvpDisabled, boolean denySpawnProtected, boolean allowDepositing,
                           String openSound, WorldRules worlds) {
    }

    private final class Corpse implements InventoryHolder {
        final String id;
        final UUID owner;
        final String ownerName;
        final Location location;
        final long expiresAt;
        final UUID killer;
        final Set<UUID> looters;
        final long protectUntil;
        final Inventory inventory;
        volatile int xp;
        volatile boolean dirty;
        volatile boolean removed;
        final List<Entity> visuals = new ArrayList<>();
        volatile Interaction hitbox;
        volatile TextDisplay hologram;

        @SuppressWarnings("deprecation")
        Corpse(String id, UUID owner, String ownerName, Location location, long expiresAt, UUID killer, Set<UUID> looters, long protectUntil,
               ItemStack[] items, int xp) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.location = location;
            this.expiresAt = expiresAt;
            this.killer = killer;
            this.looters = looters;
            this.protectUntil = protectUntil;
            this.xp = xp;
            int size = Math.min(54, Math.max(9, (items.length + 8) / 9 * 9));
            this.inventory = Bukkit.createInventory(this, size, plugin.messages().text("corpses.inventory-title", "player", ownerName));
            for (int i = 0; i < items.length && i < size; i++) {
                inventory.setItem(i, items[i]);
            }
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<String, Corpse> corpses = new ConcurrentHashMap<>();
    private final Map<UUID, Corpse> byHitbox = new ConcurrentHashMap<>();
    private NamespacedKey corpseKey;
    private ScheduledTask ticker;

    public CorpseModule(UltimateCombatPlugin plugin) {
        super(plugin, "corpses", "corpses.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.enumValue("appearance", Appearance.class, Appearance.ARMOR_STAND),
                r.bool("pvp-deaths-only", false),
                r.durationMillis("duration-seconds", 300_000, ConfigReader.TimeUnitHint.SECONDS, 5000, 7L * 86_400_000L),
                r.enumValue("on-expire", OnExpire.class, OnExpire.DROP_ITEMS),
                r.bool("remove-when-empty", true),
                r.bool("shift-click-only", false),
                r.bool("store-experience", true),
                r.bool("hologram", true),
                r.bool("access.owner-can-always-loot", true),
                r.bool("access.deny.grace-players", true),
                r.bool("access.deny.pvp-disabled-players", true),
                r.bool("access.deny.spawn-protected-players", true),
                r.bool("allow-depositing", false),
                r.string("sound", "corpse-open"),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onEnable() {
        corpseKey = new NamespacedKey(plugin, "corpse_id");
        ticker = plugin.scheduler().runGlobalTimer(this::tick, 20L, 20L);
        loadFromDatabase();
    }

    @Override
    protected void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        List<java.util.concurrent.CompletableFuture<Void>> saves = new ArrayList<>();
        for (Corpse corpse : new ArrayList<>(corpses.values())) {
            for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(corpse.inventory.getViewers())) {
                viewer.closeInventory();
            }
            saves.add(saveItems(corpse));
            removeVisuals(corpse);
        }
        try {
            java.util.concurrent.CompletableFuture.allOf(saves.toArray(new java.util.concurrent.CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Not every corpse could be saved: " + Database.rootMessage(e));
        }
        corpses.clear();
        byHitbox.clear();
    }

    // ------------------------------------------------------------------------------ creation

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld()) || event.getKeepInventory() || event.getDrops().isEmpty() || !plugin.storageReady()) {
            return;
        }
        Player killer = plugin.kills().creditedKiller(victim, victim.getLastDamageCause());
        if (s.pvpOnly() && killer == null) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack item : event.getDrops()) {
            if (item != null && !item.getType().isAir()) {
                drops.add(item.clone());
            }
        }
        event.getDrops().clear();
        int xp = 0;
        if (s.storeExperience()) {
            xp = event.getDroppedExp();
            event.setDroppedExp(0);
        }
        Set<UUID> looters = new LinkedHashSet<>();
        long protectUntil = 0;
        LootModule loot = plugin.active(LootModule.class);
        if (loot != null) {
            looters.addAll(loot.allowedLooters(victim, killer, plugin.kills().assists(victim, killer), victim.getWorld()));
            protectUntil = System.currentTimeMillis() + loot.settings(victim.getWorld()).durationMillis();
        }
        Location loc = victim.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        loc.setYaw(victim.getLocation().getYaw());
        Corpse corpse = new Corpse(UUID.randomUUID().toString(), victim.getUniqueId(), victim.getName(), loc,
                System.currentTimeMillis() + s.durationMillis(), killer == null ? null : killer.getUniqueId(), looters, protectUntil,
                drops.toArray(new ItemStack[0]), xp);
        corpses.put(corpse.id, corpse);
        spawnVisuals(corpse, victim);
        insert(corpse);
        plugin.messages().send(victim, "corpses.created", "world", loc.getWorld().getName(), "x", loc.getBlockX(), "y", loc.getBlockY(),
                "z", loc.getBlockZ());
    }

    /**
     * @return copies of the items currently stored in the player's active corpses
     */
    public List<ItemStack> contents(UUID owner) {
        List<ItemStack> out = new ArrayList<>();
        for (Corpse corpse : corpses.values()) {
            if (!corpse.removed && corpse.owner.equals(owner)) {
                for (ItemStack item : corpse.inventory.getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        out.add(item.clone());
                    }
                }
            }
        }
        return out;
    }

    private void spawnVisuals(Corpse corpse, Player template) {
        World world = corpse.location.getWorld();
        if (world == null || !world.isChunkLoaded(corpse.location.getBlockX() >> 4, corpse.location.getBlockZ() >> 4)) {
            return;
        }
        plugin.scheduler().executeAt(corpse.location, () -> {
            if (corpse.removed || corpse.hitbox != null && corpse.hitbox.isValid()) {
                return;
            }
            Settings s = settings(world);
            Location loc = corpse.location;
            ItemStack head = new ItemBuilder(Material.PLAYER_HEAD).skull(Bukkit.getOfflinePlayer(corpse.owner)).build();
            try {
                switch (s.appearance()) {
                    case MANNEQUIN -> {
                        if (template != null && plugin.platform().isPaperBased()
                                && dev.ultimatecombat.core.scheduler.Platform.classExists("org.bukkit.entity.Mannequin")) {
                            corpse.visuals.add(CorpseMannequins.spawn(loc, template));
                        } else {
                            corpse.visuals.add(spawnStand(loc, head, template));
                        }
                    }
                    case ARMOR_STAND -> corpse.visuals.add(spawnStand(loc, head, template));
                    case HEAD -> corpse.visuals.add(world.spawn(loc.clone().add(0, 0.25, 0), ItemDisplay.class, d -> {
                        d.setItemStack(head);
                        d.setPersistent(false);
                    }));
                    case CHEST -> corpse.visuals.add(world.spawn(loc.clone().add(-0.5, 0, -0.5), BlockDisplay.class, d -> {
                        d.setBlock(Material.CHEST.createBlockData());
                        d.setPersistent(false);
                    }));
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.debug().log("corpses", "Corpse visual failed: " + e);
            }
            Interaction hitbox = world.spawn(loc, Interaction.class, i -> {
                i.setInteractionWidth(1.2f);
                i.setInteractionHeight(1.2f);
                i.setResponsive(true);
                i.setPersistent(false);
                i.getPersistentDataContainer().set(corpseKey, PersistentDataType.STRING, corpse.id);
            });
            corpse.hitbox = hitbox;
            byHitbox.put(hitbox.getUniqueId(), corpse);
            if (s.hologram()) {
                corpse.hologram = world.spawn(loc.clone().add(0, 1.9, 0), TextDisplay.class, t -> {
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setPersistent(false);
                    t.setText(holoText(corpse));
                });
            }
            for (Entity e : corpse.visuals) {
                e.setPersistent(false);
                e.getPersistentDataContainer().set(corpseKey, PersistentDataType.STRING, corpse.id);
            }
        });
    }

    private ArmorStand spawnStand(Location loc, ItemStack head, Player template) {
        return loc.getWorld().spawn(loc.clone().add(0, -1.2, 0), ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setBasePlate(false);
            stand.setPersistent(false);
            EntityEquipment eq = stand.getEquipment();
            if (eq != null) {
                eq.setHelmet(head);
                if (template != null && template.getEquipment() != null) {
                    eq.setChestplate(clone(template.getEquipment().getChestplate()));
                    eq.setLeggings(clone(template.getEquipment().getLeggings()));
                    eq.setBoots(clone(template.getEquipment().getBoots()));
                }
            }
        });
    }

    private static ItemStack clone(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private String holoText(Corpse corpse) {
        long left = Math.max(0, corpse.expiresAt - System.currentTimeMillis());
        return plugin.messages().text("corpses.hologram", "player", corpse.ownerName, "time", plugin.messages().duration(left));
    }

    private void removeVisuals(Corpse corpse) {
        Runnable remove = () -> {
            for (Entity e : corpse.visuals) {
                if (e.isValid()) {
                    e.remove();
                }
            }
            corpse.visuals.clear();
            Interaction hitbox = corpse.hitbox;
            if (hitbox != null) {
                byHitbox.remove(hitbox.getUniqueId());
                if (hitbox.isValid()) {
                    hitbox.remove();
                }
            }
            corpse.hitbox = null;
            TextDisplay holo = corpse.hologram;
            if (holo != null && holo.isValid()) {
                holo.remove();
            }
            corpse.hologram = null;
        };
        if (plugin.isEnabled() && isEnabled()) {
            plugin.scheduler().executeAt(corpse.location, remove);
        } else {
            remove.run();
        }
    }

    // ------------------------------------------------------------------------------ access

    private boolean canOpen(Player player, Corpse corpse) {
        Settings s = settings(player.getWorld());
        if (player.hasPermission("ultimatecombat.corpse.loot.all")) {
            return true;
        }
        if (s.ownerCanAlwaysLoot() && player.getUniqueId().equals(corpse.owner)) {
            return true;
        }
        GraceModule grace = plugin.active(GraceModule.class);
        if (s.denyGrace() && grace != null && grace.hasGrace(player)) {
            plugin.messages().send(player, "corpses.denied-grace");
            return false;
        }
        TogglePvpModule pvp = plugin.active(TogglePvpModule.class);
        if (s.denyPvpDisabled() && pvp != null && !pvp.pvpEnabled(player)) {
            plugin.messages().send(player, "corpses.denied-pvp");
            return false;
        }
        SpawnpointModule spawns = plugin.active(SpawnpointModule.class);
        if (s.denySpawnProtected() && spawns != null && spawns.isProtected(player)) {
            plugin.messages().send(player, "corpses.denied-protected");
            return false;
        }
        LootModule loot = plugin.active(LootModule.class);
        if (loot != null && !loot.canLoot(player, corpse.looters, corpse.protectUntil)) {
            plugin.messages().send(player, "corpses.protected", "time",
                    Math.max(1, (corpse.protectUntil - System.currentTimeMillis()) / 1000));
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Corpse corpse = byHitbox.get(event.getRightClicked().getUniqueId());
        if (corpse == null) {
            String id = event.getRightClicked().getPersistentDataContainer().get(corpseKey, PersistentDataType.STRING);
            corpse = id == null ? null : corpses.get(id);
        }
        if (corpse == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Settings s = settings(player.getWorld());
        if (s.shiftClickOnly() && !player.isSneaking()) {
            plugin.messages().actionBar(player, plugin.messages().text(player, "corpses.shift-hint"));
            return;
        }
        if (corpse.removed || !canOpen(player, corpse)) {
            return;
        }
        if (corpse.xp > 0) {
            int xp = corpse.xp;
            corpse.xp = 0;
            corpse.dirty = true;
            corpse.location.getWorld().spawn(corpse.location, ExperienceOrb.class, orb -> orb.setExperience(xp));
        }
        player.openInventory(corpse.inventory);
        plugin.effects().playSound(player, s.openSound());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Corpse corpse)) {
            return;
        }
        if (settings(event.getWhoClicked().getWorld()).allowDepositing()) {
            corpse.dirty = true;
            return;
        }
        boolean top = event.getClickedInventory() == corpse.inventory;
        InventoryAction action = event.getAction();
        boolean placing = top && (action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME || action == InventoryAction.SWAP_WITH_CURSOR || action == InventoryAction.HOTBAR_SWAP)
                || !top && action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (placing) {
            event.setCancelled(true);
            return;
        }
        corpse.dirty = true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Corpse corpse && !settings(event.getWhoClicked().getWorld()).allowDepositing()) {
            for (int raw : event.getRawSlots()) {
                if (raw < corpse.inventory.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Corpse corpse)) {
            return;
        }
        saveItems(corpse);
        if (isEmpty(corpse) && settings(corpse.location.getWorld()).removeWhenEmpty() && corpse.inventory.getViewers().size() <= 1) {
            finish(corpse, "LOOTED", false);
        }
    }

    private static boolean isEmpty(Corpse corpse) {
        for (ItemStack item : corpse.inventory.getContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return corpse.xp <= 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Corpse corpse : corpses.values()) {
            Location l = corpse.location;
            if (l.getWorld() == event.getWorld() && l.getBlockX() >> 4 == event.getChunk().getX() && l.getBlockZ() >> 4 == event.getChunk().getZ()
                    && (corpse.hitbox == null || !corpse.hitbox.isValid())) {
                plugin.scheduler().runAtLater(l, () -> spawnVisuals(corpse, null), 2L);
            }
        }
    }

    // ------------------------------------------------------------------------------ lifecycle

    private void tick() {
        long now = System.currentTimeMillis();
        for (Corpse corpse : new ArrayList<>(corpses.values())) {
            if (corpse.expiresAt <= now) {
                plugin.scheduler().executeAt(corpse.location, () -> expire(corpse));
                continue;
            }
            TextDisplay holo = corpse.hologram;
            if (holo != null) {
                plugin.scheduler().executeAt(corpse.location, () -> {
                    if (holo.isValid()) {
                        holo.setText(holoText(corpse));
                    }
                });
            }
            if (corpse.dirty && corpse.inventory.getViewers().isEmpty()) {
                saveItems(corpse);
            }
        }
    }

    private void expire(Corpse corpse) {
        if (corpse.removed) {
            return;
        }
        for (org.bukkit.entity.HumanEntity viewer : new ArrayList<>(corpse.inventory.getViewers())) {
            viewer.closeInventory();
        }
        boolean drop = settings(corpse.location.getWorld()).onExpire() == OnExpire.DROP_ITEMS;
        finish(corpse, "EXPIRED", drop);
    }

    /**
     * Ends a corpse. Items are only dropped after the database marked the corpse finished, so a
     * crash can never produce both a corpse and dropped items.
     */
    private void finish(Corpse corpse, String state, boolean dropItems) {
        if (corpse.removed) {
            return;
        }
        corpse.removed = true;
        corpses.remove(corpse.id);
        removeVisuals(corpse);
        ItemStack[] contents = corpse.inventory.getContents().clone();
        int xp = corpse.xp;
        Database db = plugin.database();
        db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("corpses") + " SET state = ?, items = '' WHERE id = ? AND state = 'ACTIVE'")) {
                ps.setString(1, state);
                ps.setString(2, corpse.id);
                return ps.executeUpdate() == 1;
            }
        }).thenAccept(ok -> {
            if (!ok || !dropItems) {
                return;
            }
            plugin.scheduler().runAt(corpse.location, () -> {
                World world = corpse.location.getWorld();
                if (world == null) {
                    return;
                }
                for (ItemStack item : contents) {
                    if (item != null && !item.getType().isAir()) {
                        world.dropItemNaturally(corpse.location, item);
                    }
                }
                if (xp > 0) {
                    world.spawn(corpse.location, ExperienceOrb.class, orb -> orb.setExperience(xp));
                }
            });
        });
    }

    // ------------------------------------------------------------------------------ persistence

    private void insert(Corpse corpse) {
        Database db = plugin.database();
        String items = ItemSerializer.serializeArray(corpse.inventory.getContents());
        Location l = corpse.location;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("corpses")
                    + " (id, data_group, owner_uuid, owner_name, world, x, y, z, yaw, created_at, expires_at, items, xp, killer_uuid, state)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE')")) {
                ps.setString(1, corpse.id);
                ps.setString(2, db.group());
                ps.setString(3, corpse.owner.toString());
                ps.setString(4, corpse.ownerName);
                ps.setString(5, l.getWorld().getName());
                ps.setDouble(6, l.getX());
                ps.setDouble(7, l.getY());
                ps.setDouble(8, l.getZ());
                ps.setFloat(9, l.getYaw());
                ps.setLong(10, System.currentTimeMillis());
                ps.setLong(11, corpse.expiresAt);
                ps.setString(12, items);
                ps.setInt(13, corpse.xp);
                ps.setString(14, corpse.killer == null ? null : corpse.killer.toString());
                ps.executeUpdate();
            }
        });
    }

    private java.util.concurrent.CompletableFuture<Void> saveItems(Corpse corpse) {
        if (corpse.removed) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        corpse.dirty = false;
        String items = ItemSerializer.serializeArray(corpse.inventory.getContents());
        int xp = corpse.xp;
        Database db = plugin.database();
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("corpses") + " SET items = ?, xp = ? WHERE id = ? AND state = 'ACTIVE'")) {
                ps.setString(1, items);
                ps.setInt(2, xp);
                ps.setString(3, corpse.id);
                ps.executeUpdate();
            }
        });
    }

    private void loadFromDatabase() {
        if (!plugin.storageReady()) {
            return;
        }
        Database db = plugin.database();
        db.supply(c -> {
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + db.t("corpses") + " WHERE data_group = ? AND state = 'ACTIVE'")) {
                ps.setString(1, db.group());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[]{rs.getString("id"), rs.getString("owner_uuid"), rs.getString("owner_name"), rs.getString("world"),
                                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getLong("expires_at"),
                                rs.getString("items"), rs.getInt("xp"), rs.getString("killer_uuid")});
                    }
                }
            }
            return rows;
        }).thenAccept(rows -> plugin.scheduler().runGlobal(() -> {
            for (Object[] r : rows) {
                World world = Bukkit.getWorld((String) r[3]);
                if (world == null) {
                    continue;
                }
                ItemStack[] items;
                try {
                    items = ItemSerializer.deserializeArray((String) r[9]);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Corpse " + r[0] + " has unreadable items and was skipped: " + e.getMessage());
                    continue;
                }
                Location loc = new Location(world, (double) r[4], (double) r[5], (double) r[6], (float) r[7], 0f);
                String killer = (String) r[11];
                Corpse corpse = new Corpse((String) r[0], UUID.fromString((String) r[1]), (String) r[2], loc, (long) r[8],
                        killer == null ? null : UUID.fromString(killer), new LinkedHashSet<>(), 0, items, (int) r[10]);
                corpses.put(corpse.id, corpse);
                spawnVisuals(corpse, null);
            }
            if (!rows.isEmpty()) {
                plugin.getLogger().info("Restored " + rows.size() + " corpse(s) from the database.");
            }
        }));
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        UCCommand group = new UCCommand("corpses", "ultimatecombat.corpse.admin", false) {
            @Override
            public String usage() {
                return "corpses <list|remove>";
            }
        };
        group.child(new SimpleCommand("list", "list", "ultimatecombat.corpse.admin", false, this::list));
        group.child(new SimpleCommand("remove", "remove <player> [drop]", "ultimatecombat.corpse.admin", false, this::remove,
                ctx -> ctx.size() == 1 ? corpses.values().stream().map(c -> c.ownerName).distinct().toList() : List.of("drop")));
        plugin.commands().root().child(group);
    }

    private void list(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        ctx.reply("corpses.list-header", "count", corpses.size());
        for (Corpse c : corpses.values()) {
            ctx.reply("corpses.list-entry", "player", c.ownerName, "world", c.location.getWorld().getName(), "x", c.location.getBlockX(),
                    "y", c.location.getBlockY(), "z", c.location.getBlockZ(),
                    "time", plugin.messages().duration(c.expiresAt - System.currentTimeMillis()));
        }
    }

    private void remove(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 1) {
            ctx.reply("general.usage", "usage", "/uc corpses remove <player> [drop]");
            return;
        }
        boolean drop = "drop".equalsIgnoreCase(ctx.arg(1));
        int count = 0;
        for (Corpse c : new ArrayList<>(corpses.values())) {
            if (c.ownerName.equalsIgnoreCase(ctx.arg(0))) {
                plugin.scheduler().executeAt(c.location, () -> finish(c, "REMOVED", drop));
                count++;
            }
        }
        ctx.reply("corpses.removed", "player", ctx.arg(0), "count", count);
    }
}
