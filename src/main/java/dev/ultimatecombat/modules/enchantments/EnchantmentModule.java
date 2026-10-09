package dev.ultimatecombat.modules.enchantments;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enchantment restrictions (enchantments.yml): blocked enchantments and maximum levels for vanilla
 * and third-party enchantments, enforced on items in use and at enchanting tables/anvils.
 */
public final class EnchantmentModule extends Module<EnchantmentModule.Settings> {

    public enum When { ALWAYS, IN_COMBAT }

    public enum OnViolation { DOWNGRADE, BLOCK_USE }

    public record Settings(When when, OnViolation onViolation, Set<Enchantment> blocked, Map<Enchantment, Integer> maxLevels,
                           Set<String> blockedCustom, Map<String, Integer> maxCustom, boolean restrictEnchanting, WorldRules worlds) {
    }

    public EnchantmentModule(UltimateCombatPlugin plugin) {
        super(plugin, "enchantments", "enchantments.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        Set<Enchantment> blocked = new HashSet<>();
        Set<String> blockedCustom = new HashSet<>();
        for (String s : r.stringList("blocked")) {
            if (s.toLowerCase(Locale.ROOT).startsWith("custom:")) {
                blockedCustom.add(s.substring(7).toLowerCase(Locale.ROOT));
                continue;
            }
            Enchantment e = Registries.enchantment(s);
            if (e == null) {
                r.warn("blocked", s, "Unknown enchantment (entry ignored)", "an enchantment such as sharpness, or custom:<id>", "entry skipped");
            } else {
                blocked.add(e);
            }
        }
        Map<Enchantment, Integer> max = new HashMap<>();
        Map<String, Integer> maxCustom = new HashMap<>();
        for (String key : r.keys("max-levels")) {
            int level = r.integer("max-levels." + key, 1, 0, 255);
            if (key.toLowerCase(Locale.ROOT).startsWith("custom:")) {
                maxCustom.put(key.substring(7).toLowerCase(Locale.ROOT), level);
                continue;
            }
            Enchantment e = Registries.enchantment(key);
            if (e == null) {
                r.warn("max-levels." + key, key, "Unknown enchantment (entry ignored)", "an enchantment such as sharpness", "entry skipped");
            } else {
                max.put(e, level);
            }
        }
        return new Settings(
                r.enumValue("when", When.class, When.ALWAYS),
                r.enumValue("on-violation", OnViolation.class, OnViolation.DOWNGRADE),
                blocked, max, blockedCustom, maxCustom,
                r.bool("restrict-enchanting", true),
                WorldRules.read(r, "worlds"));
    }

    private boolean applies(Player player) {
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld()) || plugin.bypasses(player, "enchantments")) {
            return false;
        }
        if (s.when() == When.IN_COMBAT) {
            CombatModule combat = plugin.active(CombatModule.class);
            return combat != null && combat.isTagged(player);
        }
        return true;
    }

    /**
     * @return {@code true} when the item violates the rules
     */
    private boolean violates(ItemStack item, Settings s) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
            if (s.blocked().contains(e.getKey())) {
                return true;
            }
            Integer max = s.maxLevels().get(e.getKey());
            if (max != null && e.getValue() > max) {
                return true;
            }
        }
        if (!s.blockedCustom().isEmpty() || !s.maxCustom().isEmpty()) {
            for (Map.Entry<String, Integer> e : plugin.integrations().customEnchants(item).entrySet()) {
                String id = e.getKey().toLowerCase(Locale.ROOT);
                if (s.blockedCustom().contains(id)) {
                    return true;
                }
                Integer max = s.maxCustom().get(id);
                if (max != null && e.getValue() > max) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes blocked enchantments and lowers levels above the limits.
     */
    private ItemStack downgrade(ItemStack item, Settings s) {
        ItemStack result = item;
        for (Map.Entry<Enchantment, Integer> e : new HashMap<>(item.getEnchantments()).entrySet()) {
            if (s.blocked().contains(e.getKey())) {
                result.removeEnchantment(e.getKey());
                continue;
            }
            Integer max = s.maxLevels().get(e.getKey());
            if (max != null && e.getValue() > max) {
                result.removeEnchantment(e.getKey());
                if (max > 0) {
                    result.addUnsafeEnchantment(e.getKey(), max);
                }
            }
        }
        for (Map.Entry<String, Integer> e : plugin.integrations().customEnchants(result).entrySet()) {
            String id = e.getKey().toLowerCase(Locale.ROOT);
            Integer max = s.maxCustom().get(id);
            if (s.blockedCustom().contains(id) || max != null && e.getValue() > max) {
                result = plugin.integrations().removeCustomEnchant(result, e.getKey());
            }
        }
        return result;
    }

    private boolean enforceHand(Player player) {
        if (!applies(player)) {
            return true;
        }
        Settings s = settings(player.getWorld());
        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (!violates(hand, s)) {
            return true;
        }
        if (s.onViolation() == OnViolation.DOWNGRADE) {
            inv.setItemInMainHand(downgrade(hand, s));
            plugin.messages().send(player, "enchantments.downgraded");
            return true;
        }
        plugin.messages().send(player, "enchantments.blocked-use");
        return false;
    }

    private void enforceArmor(Player player) {
        if (!applies(player)) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (s.onViolation() != OnViolation.DOWNGRADE) {
            return;
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            if (violates(armor[i], s)) {
                armor[i] = downgrade(armor[i], s);
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setArmorContents(armor);
            plugin.messages().send(player, "enchantments.downgraded");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && !enforceHand(attacker)) {
            event.setCancelled(true);
        }
        if (event.getEntity() instanceof Player victim) {
            enforceArmor(victim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        plugin.scheduler().runEntityLater(player, () -> enforceHand(player), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.scheduler().runEntityLater(player, () -> {
            enforceHand(player);
            enforceArmor(player);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Settings s = settings(event.getEnchanter().getWorld());
        if (!s.restrictEnchanting() || !s.worlds().allows(event.getEnchanter().getWorld())) {
            return;
        }
        Map<Enchantment, Integer> toAdd = event.getEnchantsToAdd();
        toAdd.keySet().removeIf(s.blocked()::contains);
        for (Map.Entry<Enchantment, Integer> e : toAdd.entrySet()) {
            Integer max = s.maxLevels().get(e.getKey());
            if (max != null && e.getValue() > max) {
                e.setValue(Math.max(1, max));
            }
        }
        if (toAdd.isEmpty()) {
            event.setCancelled(true);
            plugin.messages().send(event.getEnchanter(), "enchantments.blocked-enchanting");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (!s.restrictEnchanting() || !s.worlds().allows(player.getWorld())) {
            return;
        }
        if (violates(event.getResult(), s)) {
            event.setResult(null);
        }
    }

    @Override
    public void registerCommands() {
        plugin.commands().root().child(new SimpleCommand("enchantment", "enchantment", "ultimatecombat.enchantment.admin", false,
                this::list, ctx -> List.of()));
    }

    private void list(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Settings s = settings();
        ctx.reply("enchantments.rules-header", "when", s.when().name().toLowerCase(Locale.ROOT),
                "action", s.onViolation().name().toLowerCase(Locale.ROOT));
        for (Enchantment e : s.blocked()) {
            ctx.reply("enchantments.rule-blocked", "enchantment", Registries.key(e));
        }
        for (Map.Entry<Enchantment, Integer> e : s.maxLevels().entrySet()) {
            ctx.reply("enchantments.rule-max", "enchantment", Registries.key(e.getKey()), "level", e.getValue());
        }
        for (String id : s.blockedCustom()) {
            ctx.reply("enchantments.rule-blocked", "enchantment", "custom:" + id);
        }
        for (Map.Entry<String, Integer> e : s.maxCustom().entrySet()) {
            ctx.reply("enchantments.rule-max", "enchantment", "custom:" + e.getKey(), "level", e.getValue());
        }
    }
}
