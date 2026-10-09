package dev.ultimatecombat.modules.stattrak;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Weapon kill counters (stattrak.yml). Each StatTrak weapon has a unique id, an owner and a
 * signature over id, owner and kill count. Items whose data was edited (forged) are rejected.
 */
public final class StatTrakModule extends Module<StatTrakModule.Settings> implements KillHandler {

    public record Settings(Set<Material> materials, double registerCost, double resetCost, double transferCost, boolean countPlayers,
                           boolean countMobs, boolean ownerOnlyCounts, boolean ownerOnlyUse, boolean lore, String killSound,
                           boolean notifyKill, String secret) {
    }

    private NamespacedKey idKey;
    private NamespacedKey ownerKey;
    private NamespacedKey ownerNameKey;
    private NamespacedKey killsKey;
    private NamespacedKey sigKey;
    private NamespacedKey loreKey;
    private volatile byte[] secretBytes;
    private boolean registeredHandler;

    public StatTrakModule(UltimateCombatPlugin plugin) {
        super(plugin, "stattrak", "stattrak.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.materialSet("allowed-materials"),
                r.decimal("costs.register", 0, 0, Double.MAX_VALUE),
                r.decimal("costs.reset", 0, 0, Double.MAX_VALUE),
                r.decimal("costs.transfer", 0, 0, Double.MAX_VALUE),
                r.bool("count.players", true),
                r.bool("count.mobs", false),
                r.bool("owner-only-counts", true),
                r.bool("owner-only-use", false),
                r.bool("lore", true),
                r.string("sound", "stattrak-kill"),
                r.bool("notify-kill", true),
                r.string("secret", ""));
    }

    @Override
    protected void onEnable() {
        idKey = new NamespacedKey(plugin, "st_id");
        ownerKey = new NamespacedKey(plugin, "st_owner");
        ownerNameKey = new NamespacedKey(plugin, "st_owner_name");
        killsKey = new NamespacedKey(plugin, "st_kills");
        sigKey = new NamespacedKey(plugin, "st_sig");
        loreKey = new NamespacedKey(plugin, "st_lore_lines");
        secretBytes = loadSecret();
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
    }

    @Override
    protected void onReload(Settings previous) {
        secretBytes = loadSecret();
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return NOTIFY - 10;
    }

    private byte[] loadSecret() {
        String configured = settings().secret();
        if (!configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        File file = plugin.dataFile("data/stattrak-secret.txt");
        try {
            if (file.exists()) {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
            }
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String hex = HexFormat.of().formatHex(random);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            Files.writeString(file.toPath(), hex, StandardCharsets.UTF_8);
            return hex.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("StatTrak secret could not be stored (" + e.getMessage() + "); signatures change on restart.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return random;
        }
    }

    private String sign(String id, String owner, int kills) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((id + "|" + owner + "|" + kills).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * StatTrak data read from an item.
     */
    record Data(String id, UUID owner, String ownerName, int kills, boolean valid) {
    }

    private Data read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(idKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        Integer kills = pdc.get(killsKey, PersistentDataType.INTEGER);
        String sig = pdc.get(sigKey, PersistentDataType.STRING);
        String name = pdc.get(ownerNameKey, PersistentDataType.STRING);
        boolean valid = owner != null && kills != null && sig != null
                && MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), sign(id, owner, kills).getBytes(StandardCharsets.UTF_8));
        UUID ownerId = null;
        try {
            ownerId = owner == null ? null : UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            valid = false;
        }
        return new Data(id, ownerId, name == null ? "?" : name, kills == null ? 0 : kills, valid);
    }

    private void write(ItemStack item, String id, UUID owner, String ownerName, int kills) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, id);
        pdc.set(ownerKey, PersistentDataType.STRING, owner.toString());
        pdc.set(ownerNameKey, PersistentDataType.STRING, ownerName);
        pdc.set(killsKey, PersistentDataType.INTEGER, kills);
        pdc.set(sigKey, PersistentDataType.STRING, sign(id, owner.toString(), kills));
        applyLore(meta, pdc, ownerName, kills);
        item.setItemMeta(meta);
    }

    @SuppressWarnings("deprecation")
    private void applyLore(ItemMeta meta, PersistentDataContainer pdc, String ownerName, int kills) {
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        Integer ours = pdc.get(loreKey, PersistentDataType.INTEGER);
        if (ours != null && ours > 0 && lore.size() >= ours) {
            lore = new ArrayList<>(lore.subList(0, lore.size() - ours));
        }
        if (settings().lore()) {
            List<String> added = plugin.messages().lines(null, "stattrak.lore", "kills", kills, "owner", ownerName);
            lore.addAll(added);
            pdc.set(loreKey, PersistentDataType.INTEGER, added.size());
        } else {
            pdc.remove(loreKey);
        }
        meta.setLore(lore.isEmpty() ? null : lore);
    }

    @SuppressWarnings("deprecation")
    private static void setLore(ItemMeta meta, List<String> lore) {
        meta.setLore(lore.isEmpty() ? null : lore);
    }

    private void strip(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer ours = pdc.get(loreKey, PersistentDataType.INTEGER);
        @SuppressWarnings("deprecation")
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        if (ours != null && ours > 0 && lore.size() >= ours) {
            lore = new ArrayList<>(lore.subList(0, lore.size() - ours));
        }
        for (NamespacedKey k : List.of(idKey, ownerKey, ownerNameKey, killsKey, sigKey, loreKey)) {
            pdc.remove(k);
        }
        setLore(meta, lore);
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------------------ counting

    @Override
    public void onPlayerDeath(KillContext ctx) {
        if (!ctx.isPvp() || ctx.statsSuppressed() || !settings().countPlayers()) {
            return;
        }
        count(ctx.killer(), ctx.weapon(), ctx.victim().getName());
    }

    @Override
    public void onMobKill(Player killer, LivingEntity entity, EntityDeathEvent event) {
        if (settings().countMobs()) {
            count(killer, killer.getInventory().getItemInMainHand(), dev.ultimatecombat.core.kill.KillService.entityName(entity));
        }
    }

    private void count(Player killer, ItemStack weaponSnapshot, String victimName) {
        Data snapshot = read(weaponSnapshot);
        if (snapshot == null) {
            return;
        }
        plugin.scheduler().executeEntity(killer, () -> {
            ItemStack live = find(killer.getInventory(), snapshot.id());
            if (live == null) {
                return;
            }
            Data data = read(live);
            if (data == null) {
                return;
            }
            if (!data.valid()) {
                strip(live);
                plugin.messages().send(killer, "stattrak.forged");
                return;
            }
            if (settings().ownerOnlyCounts() && !killer.getUniqueId().equals(data.owner())) {
                return;
            }
            int kills = data.kills() + 1;
            write(live, data.id(), data.owner(), data.ownerName(), kills);
            persistKills(data.id(), kills);
            if (settings().notifyKill()) {
                plugin.messages().actionBar(killer, plugin.messages().text(killer, "stattrak.kill-actionbar", "kills", kills, "victim", victimName));
            }
            plugin.effects().playSound(killer, settings().killSound());
        });
    }

    private ItemStack find(PlayerInventory inv, String id) {
        List<ItemStack> candidates = new ArrayList<>();
        candidates.add(inv.getItemInMainHand());
        candidates.add(inv.getItemInOffHand());
        for (ItemStack item : inv.getContents()) {
            candidates.add(item);
        }
        for (ItemStack item : candidates) {
            Data d = read(item);
            if (d != null && d.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    private void persistKills(String id, int kills) {
        if (!plugin.storageReady()) {
            return;
        }
        Database db = plugin.database();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("stattrak") + " SET kills = ? WHERE item_id = ?")) {
                ps.setInt(1, kills);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !settings().ownerOnlyUse()) {
            return;
        }
        Data data = read(attacker.getInventory().getItemInMainHand());
        if (data != null && data.valid() && !attacker.getUniqueId().equals(data.owner())
                && !attacker.hasPermission("ultimatecombat.stattrak.admin")) {
            event.setCancelled(true);
            plugin.messages().send(attacker, "stattrak.not-owner", "owner", data.ownerName());
        }
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        SimpleCommand cmd = new SimpleCommand("stattrak", "stattrak <register|inspect|reset|delete|transfer>", "ultimatecombat.stattrak",
                true, this::command, ctx -> switch (ctx.size()) {
                    case 1 -> List.of("register", "inspect", "reset", "delete", "transfer");
                    case 2 -> UCCommand.onlineNames();
                    default -> List.of();
                });
        plugin.commands().root().child(cmd);
        plugin.commands().standalone("stattrak", cmd);
    }

    private boolean pay(Player player, double cost) {
        if (cost <= 0 || player.hasPermission("ultimatecombat.stattrak.free")) {
            return true;
        }
        if (!plugin.economy().available() || !plugin.economy().withdraw(player, cost, "stattrak")) {
            plugin.messages().send(player, "stattrak.not-enough-money", "cost", plugin.economy().format(cost));
            return false;
        }
        return true;
    }

    private void command(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player player = ctx.player();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String sub = ctx.argOr(0, "inspect").toLowerCase(java.util.Locale.ROOT);
        Data data = read(hand);
        switch (sub) {
            case "register" -> {
                if (!ctx.has("ultimatecombat.stattrak.register")) {
                    ctx.reply("general.no-permission");
                    return;
                }
                if (hand.getType().isAir() || !settings().materials().contains(hand.getType())) {
                    ctx.reply("stattrak.not-allowed");
                    return;
                }
                if (data != null) {
                    ctx.reply("stattrak.already");
                    return;
                }
                if (!pay(player, settings().registerCost())) {
                    return;
                }
                String id = UUID.randomUUID().toString();
                write(hand, id, player.getUniqueId(), player.getName(), 0);
                insert(id, player);
                ctx.reply("stattrak.registered");
            }
            case "inspect" -> {
                if (data == null) {
                    ctx.reply("stattrak.none");
                    return;
                }
                if (!data.valid()) {
                    ctx.reply("stattrak.forged");
                    return;
                }
                ctx.reply("stattrak.inspect", "owner", data.ownerName(), "kills", data.kills(), "id", data.id().substring(0, 8));
                if (plugin.storageReady()) {
                    known(data.id()).thenAccept(ok -> {
                        if (!ok) {
                            ctx.reply("stattrak.unregistered");
                        }
                    });
                }
            }
            case "reset" -> {
                if (data == null || !data.valid()) {
                    ctx.reply("stattrak.none");
                    return;
                }
                boolean admin = ctx.has("ultimatecombat.stattrak.admin");
                if (!admin && !player.getUniqueId().equals(data.owner())) {
                    ctx.reply("stattrak.not-owner", "owner", data.ownerName());
                    return;
                }
                if (!admin && !pay(player, settings().resetCost())) {
                    return;
                }
                write(hand, data.id(), data.owner(), data.ownerName(), 0);
                persistKills(data.id(), 0);
                ctx.reply("stattrak.reset");
            }
            case "delete" -> {
                if (!ctx.has("ultimatecombat.stattrak.delete")) {
                    ctx.reply("general.no-permission");
                    return;
                }
                if (data == null) {
                    ctx.reply("stattrak.none");
                    return;
                }
                if (!ctx.has("ultimatecombat.stattrak.admin") && data.valid() && !player.getUniqueId().equals(data.owner())) {
                    ctx.reply("stattrak.not-owner", "owner", data.ownerName());
                    return;
                }
                strip(hand);
                markDeleted(data.id());
                ctx.reply("stattrak.deleted");
            }
            case "transfer" -> {
                if (data == null || !data.valid()) {
                    ctx.reply("stattrak.none");
                    return;
                }
                boolean admin = ctx.has("ultimatecombat.stattrak.admin");
                if (!admin && !player.getUniqueId().equals(data.owner())) {
                    ctx.reply("stattrak.not-owner", "owner", data.ownerName());
                    return;
                }
                Player target = ctx.size() > 1 ? Bukkit.getPlayerExact(ctx.arg(1)) : null;
                if (target == null) {
                    ctx.reply("general.player-not-found", "player", ctx.argOr(1, "?"));
                    return;
                }
                if (!admin && !pay(player, settings().transferCost())) {
                    return;
                }
                write(hand, data.id(), target.getUniqueId(), target.getName(), data.kills());
                updateOwner(data.id(), target);
                ctx.reply("stattrak.transferred", "player", target.getName());
            }
            default -> ctx.reply("general.usage", "usage", "/stattrak <register|inspect|reset|delete|transfer <player>>");
        }
    }

    private void insert(String id, Player owner) {
        if (!plugin.storageReady()) {
            return;
        }
        Database db = plugin.database();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("stattrak")
                    + " (item_id, data_group, owner_uuid, owner_name, kills, created_at, deleted) VALUES (?,?,?,?,0,?,0)")) {
                ps.setString(1, id);
                ps.setString(2, db.group());
                ps.setString(3, owner.getUniqueId().toString());
                ps.setString(4, owner.getName());
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    private void markDeleted(String id) {
        if (!plugin.storageReady()) {
            return;
        }
        Database db = plugin.database();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("stattrak") + " SET deleted = 1 WHERE item_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
    }

    private void updateOwner(String id, Player owner) {
        if (!plugin.storageReady()) {
            return;
        }
        Database db = plugin.database();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("stattrak") + " SET owner_uuid = ?, owner_name = ? WHERE item_id = ?")) {
                ps.setString(1, owner.getUniqueId().toString());
                ps.setString(2, owner.getName());
                ps.setString(3, id);
                ps.executeUpdate();
            }
        });
    }

    /**
     * @return whether the database knows the item and it was not deleted (async check for admins)
     */
    public java.util.concurrent.CompletableFuture<Boolean> known(String id) {
        Database db = plugin.database();
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT deleted FROM " + db.t("stattrak") + " WHERE item_id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 0;
                }
            }
        });
    }
}
