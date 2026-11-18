package dev.ultimatecombat.modules.potions;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Potion rules (potions.yml): effect blacklist, level limits, invisibility in combat, cooldowns,
 * instant drinking and /potionstack.
 */
public final class PotionModule extends Module<PotionModule.Settings> {

    public record Settings(Set<PotionEffectType> blacklist, boolean blacklistOnlyInCombat, Map<PotionEffectType, Integer> maxLevels,
                           boolean reduceInsteadOfBlock, boolean disableInvisibilityInCombat, boolean cooldownOnlyInCombat,
                           long drinkCooldownMillis, long splashCooldownMillis, long lingeringCooldownMillis, boolean stackEnabled,
                           int stackSize, boolean instantDrink, WorldRules worlds) {
    }

    private static final Set<EntityPotionEffectEvent.Cause> POTION_CAUSES = EnumSet.of(EntityPotionEffectEvent.Cause.POTION_DRINK,
            EntityPotionEffectEvent.Cause.POTION_SPLASH, EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD, EntityPotionEffectEvent.Cause.ARROW);

    public PotionModule(UltimateCombatPlugin plugin) {
        super(plugin, "potions", "potions.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        Set<PotionEffectType> blacklist = new HashSet<>();
        for (String s : r.stringList("blacklist.effects")) {
            PotionEffectType t = Registries.potionEffect(s);
            if (t == null) {
                r.warn("blacklist.effects", s, "Unknown effect (entry ignored)", "an effect such as INVISIBILITY", "entry skipped");
            } else {
                blacklist.add(t);
            }
        }
        Map<PotionEffectType, Integer> max = new HashMap<>();
        for (String key : r.keys("max-levels.effects")) {
            PotionEffectType t = Registries.potionEffect(key);
            if (t == null) {
                r.warn("max-levels.effects." + key, key, "Unknown effect (entry ignored)", "an effect such as STRENGTH", "entry skipped");
                continue;
            }
            max.put(t, r.integer("max-levels.effects." + key, 1, 1, 255));
        }
        return new Settings(blacklist,
                r.bool("blacklist.only-in-combat", false),
                max,
                r.choice("max-levels.action", "reduce", "reduce", "block").equals("reduce"),
                r.bool("disable-invisibility-in-combat", false),
                r.bool("cooldowns.only-in-combat", true),
                r.durationMillis("cooldowns.drinkable-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 3_600_000),
                r.durationMillis("cooldowns.splash-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 3_600_000),
                r.durationMillis("cooldowns.lingering-seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 3_600_000),
                r.bool("stacking.enabled", true),
                r.integer("stacking.max-stack-size", 16, 1, 99),
                r.bool("instant-drinking.enabled", false),
                WorldRules.read(r, "worlds"));
    }

    private boolean inCombat(Player p) {
        CombatModule combat = plugin.active(CombatModule.class);
        return combat != null && combat.isTagged(p);
    }

    // ------------------------------------------------------------------------------ effects

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (!(((org.bukkit.event.entity.EntityEvent) event).getEntity() instanceof Player player) || event.getNewEffect() == null) {
            return;
        }
        Settings s = settings(player.getWorld());
        if (!s.worlds().allows(player.getWorld()) || plugin.bypasses(player, "potions")) {
            return;
        }
        PotionEffect effect = event.getNewEffect();
        PotionEffectType type = effect.getType();
        boolean potionCause = POTION_CAUSES.contains(event.getCause());
        if (type.equals(PotionEffectType.INVISIBILITY) && s.disableInvisibilityInCombat() && inCombat(player)) {
            event.setCancelled(true);
            plugin.messages().send(player, "potions.invisibility-blocked");
            return;
        }
        if (!potionCause) {
            return;
        }
        if (s.blacklist().contains(type) && (!s.blacklistOnlyInCombat() || inCombat(player))) {
            event.setCancelled(true);
            plugin.messages().send(player, "potions.effect-blocked", "effect", Registries.key(type));
            return;
        }
        Integer max = s.maxLevels().get(type);
        if (max != null && effect.getAmplifier() + 1 > max) {
            event.setCancelled(true);
            if (s.reduceInsteadOfBlock()) {
                PotionEffect reduced = new PotionEffect(type, effect.getDuration(), max - 1, effect.isAmbient(), effect.hasParticles(),
                        effect.hasIcon());
                plugin.scheduler().runEntity(player, () -> player.addPotionEffect(reduced));
                plugin.messages().send(player, "potions.effect-reduced", "effect", Registries.key(type), "level", max);
            } else {
                plugin.messages().send(player, "potions.effect-blocked", "effect", Registries.key(type));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        Player player = event.getPlayer();
        if (settings(player.getWorld()).disableInvisibilityInCombat() && player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            plugin.scheduler().executeEntity(player, () -> {
                player.removePotionEffect(PotionEffectType.INVISIBILITY);
                plugin.messages().send(player, "potions.invisibility-removed");
            });
        }
    }

    // ------------------------------------------------------------------------------ cooldowns

    private boolean cooldownApplies(Player player, Settings s) {
        return !s.cooldownOnlyInCombat() || inCombat(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() != Material.POTION) {
            return;
        }
        Player player = event.getPlayer();
        Settings s = settings(player.getWorld());
        if (!checkCooldown(player, s, "potion:drink", s.drinkCooldownMillis(), Material.POTION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion potion) || !(potion.getShooter() instanceof Player player)) {
            return;
        }
        Settings s = settings(player.getWorld());
        boolean lingering = potion.getItem().getType() == Material.LINGERING_POTION;
        String key = lingering ? "potion:lingering" : "potion:splash";
        long millis = lingering ? s.lingeringCooldownMillis() : s.splashCooldownMillis();
        if (!checkCooldown(player, s, key, millis, potion.getItem().getType())) {
            event.setCancelled(true);
        }
    }

    private boolean checkCooldown(Player player, Settings s, String key, long millis, Material icon) {
        if (millis <= 0 || !cooldownApplies(player, s) || plugin.bypasses(player, "potions")) {
            return true;
        }
        String display = plugin.messages().text(player, "potions.names." + key.substring(7));
        if (!plugin.cooldowns().ready(player, key, display)) {
            return false;
        }
        plugin.cooldowns().start(player, key, millis, display, icon);
        return true;
    }

    // ------------------------------------------------------------------------------ instant drinking

    @EventHandler(priority = EventPriority.HIGH)
    public void onInstantDrink(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.POTION || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        Settings s = settings(player.getWorld());
        if (!s.instantDrink() || !s.worlds().allows(player.getWorld())) {
            return;
        }
        event.setCancelled(true);
        EquipmentSlot hand = event.getHand();
        ItemStack single = item.clone();
        single.setAmount(1);
        PlayerItemConsumeEvent consume = new PlayerItemConsumeEvent(player, single, hand);
        Bukkit.getPluginManager().callEvent(consume);
        if (consume.isCancelled()) {
            return;
        }
        List<PotionEffect> effects = new ArrayList<>();
        if (single.getItemMeta() instanceof PotionMeta meta) {
            if (meta.getBasePotionType() != null) {
                effects.addAll(meta.getBasePotionType().getPotionEffects());
            }
            effects.addAll(meta.getCustomEffects());
        }
        PlayerInventory inv = player.getInventory();
        ItemStack held = hand == EquipmentSlot.OFF_HAND ? inv.getItemInOffHand() : inv.getItemInMainHand();
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            for (ItemStack rest : inv.addItem(new ItemStack(Material.GLASS_BOTTLE)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        } else if (hand == EquipmentSlot.OFF_HAND) {
            inv.setItemInOffHand(new ItemStack(Material.GLASS_BOTTLE));
        } else {
            inv.setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
        }
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
    }

    // ------------------------------------------------------------------------------ stacking

    @Override
    public void registerCommands() {
        SimpleCommand stack = new SimpleCommand("potionstack", "potionstack", "ultimatecombat.potionstack", true, this::stack);
        plugin.commands().root().child(stack);
        plugin.commands().standalone("potionstack", stack);
    }

    private void stack(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player player = ctx.player();
        Settings s = settings(player.getWorld());
        if (!s.stackEnabled()) {
            ctx.reply("potions.stack-disabled");
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int merged = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack base = contents[i];
            if (!isPotion(base)) {
                continue;
            }
            base = withMaxStack(base, s.stackSize());
            contents[i] = base;
            for (int j = i + 1; j < contents.length && base.getAmount() < s.stackSize(); j++) {
                ItemStack other = contents[j];
                if (!isPotion(other)) {
                    continue;
                }
                ItemStack normalized = withMaxStack(other, s.stackSize());
                if (!normalized.isSimilar(base)) {
                    continue;
                }
                int move = Math.min(normalized.getAmount(), s.stackSize() - base.getAmount());
                base.setAmount(base.getAmount() + move);
                if (normalized.getAmount() - move <= 0) {
                    contents[j] = null;
                } else {
                    normalized.setAmount(normalized.getAmount() - move);
                    contents[j] = normalized;
                }
                merged += move;
            }
        }
        ItemStack off = inv.getItemInOffHand();
        inv.setStorageContents(contents);
        if (isPotion(off)) {
            inv.setItemInOffHand(withMaxStack(off, s.stackSize()));
        }
        ctx.reply(merged > 0 ? "potions.stacked" : "potions.nothing-to-stack", "count", merged);
    }

    private static boolean isPotion(ItemStack item) {
        return item != null && (item.getType() == Material.POTION || item.getType() == Material.SPLASH_POTION
                || item.getType() == Material.LINGERING_POTION);
    }

    private static ItemStack withMaxStack(ItemStack item, int size) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            try {
                meta.setMaxStackSize(size);
                copy.setItemMeta(meta);
            } catch (NoSuchMethodError | IllegalArgumentException ignored) {
                // pre-1.20.5: cannot change the stack size
            }
        }
        return copy;
    }
}
