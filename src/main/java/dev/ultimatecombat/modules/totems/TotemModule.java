package dev.ultimatecombat.modules.totems;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Effects;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.restrictions.RestrictionsModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Totem of Undying rules (totems.yml): cooldowns, using totems from the inventory and custom
 * effects. Blocking totems completely in combat is done in restrictions.yml.
 */
public final class TotemModule extends Module<TotemModule.Settings> {

    public record Settings(long cooldownMillis, boolean cooldownOnlyInCombat, boolean fromInventory, boolean replaceVanilla,
                           List<PotionEffect> effects, String sound, String particle, boolean message, WorldRules worlds) {
    }

    private final Set<UUID> resurrecting = ConcurrentHashMap.newKeySet();

    public TotemModule(UltimateCombatPlugin plugin) {
        super(plugin, "totems", "totems.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.durationMillis("cooldown.seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.bool("cooldown.only-in-combat", false),
                r.bool("use-from-inventory", false),
                r.bool("effects.replace-vanilla", false),
                Effects.parse(r, "effects.list"),
                r.string("sound", "none"),
                r.string("particle", "totem"),
                r.bool("message", true),
                WorldRules.read(r, "worlds"));
    }

    private boolean inCombat(Player p) {
        CombatModule combat = plugin.active(CombatModule.class);
        return combat != null && combat.isTagged(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld()) || plugin.kills().isCombatLogDeathPending(player.getUniqueId())) {
            return;
        }
        boolean fromInventory = false;
        if (event.isCancelled()) {
            // No totem in the hands (or another plugin blocked it): try the inventory if allowed.
            RestrictionsModule restrictions = plugin.active(RestrictionsModule.class);
            boolean restricted = restrictions != null && restrictions.restricts(player, "totems");
            if (!s.fromInventory() || restricted || hasTotemInHands(player) || !hasTotemInInventory(player)) {
                return;
            }
            fromInventory = true;
        }
        if (s.cooldownMillis() > 0 && (!s.cooldownOnlyInCombat() || inCombat(player)) && !plugin.bypasses(player, "totems")) {
            String display = plugin.messages().text(player, "totems.name");
            if (plugin.cooldowns().remaining(player, "totem") > 0) {
                event.setCancelled(true);
                plugin.messages().send(player, "totems.on-cooldown", "time",
                        dev.ultimatecombat.core.message.Text.formatSeconds(plugin.cooldowns().remaining(player, "totem"), true));
                plugin.effects().playSound(player, "totem-blocked");
                return;
            }
            plugin.cooldowns().start(player, "totem", s.cooldownMillis(), display, Material.TOTEM_OF_UNDYING);
        }
        if (fromInventory) {
            event.setCancelled(false);
            consumeFromInventory(player);
        }
        resurrecting.add(player.getUniqueId());
    }

    private static boolean hasTotemInHands(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    private static boolean hasTotemInInventory(Player p) {
        return p.getInventory().contains(Material.TOTEM_OF_UNDYING);
    }

    private static void consumeFromInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    contents[i] = null;
                }
                inv.setStorageContents(contents);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTotemEffect(EntityPotionEffectEvent event) {
        if (event.getCause() == EntityPotionEffectEvent.Cause.TOTEM && ((org.bukkit.event.entity.EntityEvent) event).getEntity() instanceof Player player
                && resurrecting.contains(player.getUniqueId()) && settings(player.getWorld()).replaceVanilla()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResurrected(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || !resurrecting.contains(player.getUniqueId())) {
            return;
        }
        if (event.isCancelled()) {
            resurrecting.remove(player.getUniqueId());
            return;
        }
        Settings s = settings(player.getWorld());
        plugin.scheduler().runEntityLater(player, () -> {
            resurrecting.remove(player.getUniqueId());
            for (PotionEffect effect : s.effects()) {
                player.addPotionEffect(effect);
            }
            plugin.effects().playSound(player, s.sound());
            plugin.effects().spawnParticle(player.getLocation(), s.particle());
            if (s.message()) {
                plugin.messages().send(player, "totems.used");
            }
        }, 1L);
    }
}
