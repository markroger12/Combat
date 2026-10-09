package dev.ultimatecombat.modules.heads;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * Head trophies (heads.yml): a victim's head can drop on death and carry the money the victim lost,
 * which the owner receives with /sellhead. Each head can be sold exactly once (database claim).
 */
public final class HeadModule extends Module<HeadModule.Settings> implements KillHandler {

    public record Settings(double dropChance, boolean pvpOnly, boolean moneyEnabled, boolean moneyPercent, double moneyAmount,
                           double moneyMaximum, double baseValue, boolean directToKiller, boolean sellEnabled, WorldRules worlds) {
    }

    private NamespacedKey idKey;
    private NamespacedKey valueKey;
    private NamespacedKey victimKey;
    private boolean registeredHandler;

    public HeadModule(UltimateCombatPlugin plugin) {
        super(plugin, "head-trophies", "heads.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.percent("drop-chance", 25),
                r.bool("pvp-only", true),
                r.bool("money-penalty.enabled", true),
                r.choice("money-penalty.mode", "percent", "percent", "fixed").equals("percent"),
                r.decimal("money-penalty.amount", 5, 0, Double.MAX_VALUE),
                r.decimal("money-penalty.maximum", 0, 0, Double.MAX_VALUE),
                r.decimal("sell.base-value", 0, 0, Double.MAX_VALUE),
                r.bool("give-directly-to-killer", false),
                r.bool("sell.enabled", true),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onEnable() {
        idKey = new NamespacedKey(plugin, "head_id");
        valueKey = new NamespacedKey(plugin, "head_value");
        victimKey = new NamespacedKey(plugin, "head_victim");
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return ECONOMY + 10;
    }

    @Override
    public void onPlayerDeath(KillContext ctx) {
        Player victim = ctx.victim();
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld()) || s.pvpOnly() && !ctx.isPvp() || ctx.rewardsSuppressed()
                || plugin.bypasses(victim, "headdrop") || !Numbers.roll(s.dropChance())) {
            return;
        }
        if (!plugin.storageReady()) {
            return; // heads must be registered to be sellable
        }
        double value = s.baseValue();
        double taken = 0;
        if (s.moneyEnabled() && plugin.economy().available()) {
            double penalty = plugin.economy().computePenalty(victim, s.moneyPercent(),
                    s.moneyPercent() ? s.moneyAmount() / 100.0 : s.moneyAmount(), s.moneyMaximum());
            if (penalty > 0 && plugin.economy().withdraw(victim, penalty, "head trophy")) {
                taken = penalty;
                value += penalty;
                plugin.messages().send(victim, "heads.money-lost", "amount", plugin.economy().format(penalty));
            }
        }
        String id = UUID.randomUUID().toString();
        Player killer = ctx.killer();
        ItemStack head = createHead(victim, killer, id, value);
        double refund = taken;
        double headValue = value;
        Database db = plugin.database();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("heads")
                    + " (id, data_group, victim_uuid, victim_name, killer_uuid, value, created_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, db.group());
                ps.setString(3, victim.getUniqueId().toString());
                ps.setString(4, victim.getName());
                ps.setString(5, killer == null ? null : killer.getUniqueId().toString());
                ps.setDouble(6, headValue);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }).exceptionally(t -> {
            // The head can never be sold without its record: give the victim's money back.
            if (refund > 0) {
                plugin.scheduler().runGlobal(() -> plugin.economy().deposit(victim, refund, "head trophy refund"));
            }
            return null;
        });
        if (s.directToKiller() && killer != null) {
            plugin.scheduler().executeEntity(killer, () -> {
                for (ItemStack rest : killer.getInventory().addItem(head).values()) {
                    killer.getWorld().dropItemNaturally(killer.getLocation(), rest);
                }
                plugin.messages().send(killer, "heads.received", "victim", victim.getName());
            });
        } else {
            plugin.scheduler().executeAt(ctx.location(), () -> ctx.location().getWorld().dropItemNaturally(ctx.location(), head));
        }
        plugin.effects().playSound(ctx.location(), "head-drop");
        plugin.messages().broadcast("heads.dropped", "victim", victim.getName(),
                "killer", killer == null ? plugin.messages().settings().none() : killer.getName(), "value", plugin.economy().format(value));
    }

    private ItemStack createHead(Player victim, Player killer, String id, double value) {
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Object[] ph = {"victim", victim.getName(), "killer", killer == null ? plugin.messages().settings().none() : killer.getName(),
                "value", plugin.economy().format(value), "date", date};
        return new ItemBuilder(Material.PLAYER_HEAD)
                .skull(victim)
                .name(plugin.messages().text("heads.item-name", ph))
                .lore(plugin.messages().lines(null, "heads.item-lore", ph))
                .edit(meta -> {
                    PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(idKey, PersistentDataType.STRING, id);
                    pdc.set(valueKey, PersistentDataType.DOUBLE, value);
                    pdc.set(victimKey, PersistentDataType.STRING, victim.getUniqueId().toString());
                })
                .build();
    }

    // ------------------------------------------------------------------------------ selling

    @Override
    public void registerCommands() {
        SimpleCommand sell = new SimpleCommand("sellhead", "sellhead", "ultimatecombat.sellhead", true, this::sell);
        plugin.commands().root().child(sell);
        plugin.commands().standalone("sellhead", sell);
    }

    private void sell(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player player = ctx.player();
        if (!settings(player.getWorld()).sellEnabled()) {
            ctx.reply("heads.sell-disabled");
            return;
        }
        if (!plugin.storageReady() || !plugin.economy().available()) {
            ctx.reply("heads.sell-unavailable");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = hand.getItemMeta() == null ? null
                : hand.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        Double value = hand.getItemMeta() == null ? null
                : hand.getItemMeta().getPersistentDataContainer().get(valueKey, PersistentDataType.DOUBLE);
        if (hand.getType() != Material.PLAYER_HEAD || id == null || value == null) {
            ctx.reply("heads.not-a-trophy");
            return;
        }
        Database db = plugin.database();
        long now = System.currentTimeMillis();
        db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("heads")
                    + " SET sold_by = ?, sold_at = ? WHERE id = ? AND data_group = ? AND sold_by IS NULL")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setLong(2, now);
                ps.setString(3, id);
                ps.setString(4, db.group());
                return ps.executeUpdate() == 1;
            }
        }).thenAccept(claimed -> plugin.scheduler().runEntity(player, () -> {
            if (!claimed) {
                ctx.reply("heads.already-sold");
                return;
            }
            ItemStack current = player.getInventory().getItemInMainHand();
            String currentId = current.getItemMeta() == null ? null
                    : current.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (!id.equals(currentId)) {
                // The head left the hand meanwhile: release the claim so nothing is lost.
                db.run(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.t("heads") + " SET sold_by = NULL, sold_at = NULL WHERE id = ?")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                });
                ctx.reply("heads.not-a-trophy");
                return;
            }
            current.setAmount(current.getAmount() - 1);
            if (value > 0) {
                plugin.economy().deposit(player, value, "head trophy sale");
            }
            ctx.reply("heads.sold", "value", plugin.economy().format(value));
        }));
    }
}
