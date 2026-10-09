package dev.ultimatecombat.modules.oldpvp;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.api.event.CombatUntagEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.SoundFilterHook;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Classic 1.8 combat (old-pvp.yml): no attack cooldown, legacy weapon damage, sharpness,
 * strength, armour, regeneration, durability, fire, no offhand/shields/sweeping and optional
 * removal of modern combat sounds (ProtocolLib).
 */
public final class OldPvpModule extends Module<OldPvpSettings> {

    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRegen = new ConcurrentHashMap<>();
    private NamespacedKey preferenceKey;

    public OldPvpModule(UltimateCombatPlugin plugin) {
        super(plugin, "old-pvp", "old-pvp.yml");
    }

    @Override
    protected OldPvpSettings parse(ConfigReader reader) {
        return OldPvpSettings.parse(reader);
    }

    @Override
    protected void onEnable() {
        preferenceKey = new NamespacedKey(plugin, "old_pvp");
        refreshAll();
        updateSoundFilter();
    }

    @Override
    protected void onReload(OldPvpSettings previous) {
        refreshAll();
        updateSoundFilter();
    }

    @Override
    protected void onDisable() {
        SoundFilterHook filter = plugin.integrations().soundFilter();
        if (filter != null) {
            filter.clearFilter();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (active.contains(p.getUniqueId())) {
                plugin.scheduler().executeEntity(p, () -> setAttackSpeed(p, settings().modernAttackSpeed()));
            }
        }
        active.clear();
        lastRegen.clear();
    }

    private void updateSoundFilter() {
        SoundFilterHook filter = plugin.integrations().soundFilter();
        if (filter == null) {
            return;
        }
        OldPvpSettings s = settings();
        if (s.disableSounds() && !s.blockedSounds().isEmpty()) {
            filter.setFilter(s.blockedSounds(), active::contains);
        } else {
            filter.clearFilter();
        }
    }

    // ---------------------------------------------------------------------------- activation

    /**
     * @return whether 1.8 combat currently applies to the player
     */
    public boolean isActive(Player player) {
        OldPvpSettings s = settings(player.getWorld());
        if (s.mode() == OldPvpSettings.Mode.NEVER || !s.worlds().allows(player.getWorld())) {
            return false;
        }
        if (s.playerToggle()) {
            Byte pref = player.getPersistentDataContainer().get(preferenceKey, PersistentDataType.BYTE);
            boolean wants = pref == null ? s.toggleDefault() : pref == 1;
            if (!wants) {
                return false;
            }
        }
        if (s.mode() == OldPvpSettings.Mode.IN_COMBAT) {
            CombatModule combat = plugin.active(CombatModule.class);
            return combat != null && combat.isTagged(player);
        }
        return true;
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().executeEntity(p, () -> refresh(p));
        }
    }

    private void refresh(Player player) {
        if (!player.isOnline()) {
            return;
        }
        boolean want = isActive(player);
        OldPvpSettings s = settings(player.getWorld());
        if (want) {
            active.add(player.getUniqueId());
            setAttackSpeed(player, s.attackSpeed());
            if (s.disableOffhand() && s.moveOffhandItems()) {
                ItemStack off = player.getInventory().getItemInOffHand();
                if (!off.getType().isAir()) {
                    player.getInventory().setItemInOffHand(null);
                    for (ItemStack rest : player.getInventory().addItem(off).values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                }
            }
        } else if (active.remove(player.getUniqueId())) {
            setAttackSpeed(player, s.modernAttackSpeed());
        }
    }

    private static void setAttackSpeed(Player player, double value) {
        Attribute attackSpeed = Registries.attackSpeed();
        if (attackSpeed == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attackSpeed);
        if (instance != null && instance.getBaseValue() != value) {
            instance.setBaseValue(value);
        }
    }

    private boolean isActiveFast(Player player) {
        return active.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.scheduler().runEntityLater(p, () -> refresh(p), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (active.remove(p.getUniqueId())) {
            // Restore vanilla so the player is not stuck with legacy attack speed if old-pvp is later removed.
            setAttackSpeed(p, settings(p.getWorld()).modernAttackSpeed());
        }
        lastRegen.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        plugin.scheduler().runEntityLater(p, () -> refresh(p), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        if (settings().mode() == OldPvpSettings.Mode.IN_COMBAT) {
            Player p = event.getPlayer();
            plugin.scheduler().runEntityLater(p, () -> refresh(p), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(CombatUntagEvent event) {
        if (settings().mode() == OldPvpSettings.Mode.IN_COMBAT) {
            Player p = event.getPlayer();
            plugin.scheduler().runEntityLater(p, () -> refresh(p), 1L);
        }
    }

    // ---------------------------------------------------------------------------- damage

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK && damager instanceof Player p
                && isActiveFast(p) && settings(p.getWorld()).disableSweep()) {
            event.setCancelled(true);
            return;
        }
        if (damager instanceof Player attacker && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && isActiveFast(attacker)) {
            OldPvpSettings s = settings(attacker.getWorld());
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            Double base = s.damageEnabled() ? s.weaponDamage().get(weapon.getType()) : null;
            if (base != null) {
                double damage = legacyDamage(attacker, event, weapon, base, s);
                event.setDamage(Math.max(0, damage));
            }
        }
        if (event.getEntity() instanceof Player victim && isActiveFast(victim) && settings(victim.getWorld()).legacyArmor()
                && event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) {
            Attribute armorAttr = Registries.armor();
            AttributeInstance armor = armorAttr == null ? null : victim.getAttribute(armorAttr);
            double points = armor == null ? 0 : Math.min(20, armor.getValue());
            double base = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
            event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, -(base * points * 0.04));
        }
    }

    private double legacyDamage(Player attacker, EntityDamageByEntityEvent event, ItemStack weapon, double base, OldPvpSettings s) {
        double damage = base;
        PotionEffectType strengthType = Registries.potionEffect("strength");
        PotionEffectType weaknessType = Registries.potionEffect("weakness");
        PotionEffect strength = strengthType == null ? null : attacker.getPotionEffect(strengthType);
        PotionEffect weakness = weaknessType == null ? null : attacker.getPotionEffect(weaknessType);
        if (strength != null) {
            int level = strength.getAmplifier() + 1;
            damage = s.legacyStrength() ? damage * (1 + 1.3 * level) : damage + 3 * level;
        }
        if (weakness != null) {
            int level = weakness.getAmplifier() + 1;
            damage -= s.legacyStrength() ? 0.5 * level : 4 * level;
        }
        Entity target = event.getEntity();
        Enchantment sharpness = Registries.enchantment("sharpness");
        Enchantment smite = Registries.enchantment("smite");
        Enchantment bane = Registries.enchantment("bane_of_arthropods");
        int sharp = sharpness == null ? 0 : weapon.getEnchantmentLevel(sharpness);
        if (sharp > 0) {
            damage += s.legacySharpness() ? 1.25 * sharp : 0.5 * sharp + 0.5;
        }
        if (smite != null && target instanceof LivingEntity living && isUndead(living)) {
            damage += 2.5 * weapon.getEnchantmentLevel(smite);
        }
        if (bane != null && isArthropod(target)) {
            damage += 2.5 * weapon.getEnchantmentLevel(bane);
        }
        if (isCritical(attacker, event)) {
            damage *= s.criticalMultiplier();
        }
        return damage;
    }

    private static boolean isArthropod(Entity entity) {
        String n = entity.getType().name();
        return n.equals("SPIDER") || n.equals("CAVE_SPIDER") || n.equals("SILVERFISH") || n.equals("ENDERMITE") || n.equals("BEE");
    }

    private static boolean isUndead(LivingEntity entity) {
        String n = entity.getType().name();
        return n.contains("ZOMBIE") || n.contains("SKELETON") || n.equals("WITHER") || n.equals("PHANTOM") || n.equals("DROWNED")
                || n.equals("HUSK") || n.equals("STRAY") || n.equals("ZOGLIN") || n.equals("BOGGED");
    }

    @SuppressWarnings("deprecation")
    private static boolean isCritical(Player attacker, EntityDamageByEntityEvent event) {
        try {
            return event.isCritical();
        } catch (NoSuchMethodError e) {
            return attacker.getFallDistance() > 0 && !attacker.isOnGround() && !attacker.isInsideVehicle() && !attacker.isSprinting()
                    && !attacker.isInWater();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFireTick(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK && event.getEntity() instanceof Player p && isActiveFast(p)) {
            event.setDamage(settings(p.getWorld()).fireTickDamage());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Player p && isActiveFast(p)) {
            double mult = settings(p.getWorld()).burnDurationMultiplier();
            if (mult != 1.0) {
                event.setDuration((float) (event.getDuration() * mult));
            }
        }
    }

    // ---------------------------------------------------------------------------- regeneration

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED || !(event.getEntity() instanceof Player p)
                || !isActiveFast(p)) {
            return;
        }
        OldPvpSettings s = settings(p.getWorld());
        if (!s.regenEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRegen.get(p.getUniqueId());
        if (p.getFoodLevel() < s.regenMinFood() || last != null && now - last < s.regenIntervalMillis()) {
            event.setCancelled(true);
            return;
        }
        lastRegen.put(p.getUniqueId(), now);
        event.setAmount(s.regenAmount());
        float exhaustion = s.regenExhaustion();
        plugin.scheduler().runEntity(p, () -> p.setExhaustion(Math.min(40f, p.getExhaustion() + exhaustion)));
    }

    // ---------------------------------------------------------------------------- durability

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Player p = event.getPlayer();
        if (!isActiveFast(p)) {
            return;
        }
        OldPvpSettings s = settings(p.getWorld());
        String n = event.getItem().getType().name();
        boolean armor = n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
        double mult = armor ? s.armorDurabilityMultiplier() : s.weaponDurabilityMultiplier();
        if (mult == 1.0) {
            return;
        }
        double scaled = event.getDamage() * mult;
        int whole = (int) Math.floor(scaled);
        if (ThreadLocalRandom.current().nextDouble() < scaled - whole) {
            whole++;
        }
        if (whole <= 0) {
            event.setCancelled(true);
        } else {
            event.setDamage(whole);
        }
    }

    // ---------------------------------------------------------------------------- offhand / shields

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isActiveFast(event.getPlayer()) && settings(event.getPlayer().getWorld()).disableOffhand()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOffhandClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !isActiveFast(p) || !settings(p.getWorld()).disableOffhand()) {
            return;
        }
        boolean offhandSlot = event.getClickedInventory() != null && event.getClickedInventory().getType() == InventoryType.PLAYER
                && event.getSlot() == 40;
        if (offhandSlot || event.getClick() == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onShield(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player p = event.getPlayer();
        if (!isActiveFast(p) || !settings(p.getWorld()).disableShields()) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.SHIELD) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || event.getRecipe().getResult().getType() != Material.SHIELD) {
            return;
        }
        if (!settings().blockShieldCrafting() || settings().mode() == OldPvpSettings.Mode.NEVER) {
            return;
        }
        event.getInventory().setResult(null);
    }

    // ---------------------------------------------------------------------------- command

    @Override
    public void registerCommands() {
        SimpleCommand toggle = new SimpleCommand("oldpvp", "oldpvp [on|off]", "ultimatecombat.oldpvp.toggle", true, this::toggle,
                ctx -> List.of("on", "off"));
        plugin.commands().root().child(toggle);
        plugin.commands().standalone("oldpvp", toggle);
    }

    private void toggle(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player p = ctx.player();
        OldPvpSettings s = settings(p.getWorld());
        if (!s.playerToggle()) {
            ctx.reply("oldpvp.toggle-disabled");
            return;
        }
        Byte pref = p.getPersistentDataContainer().get(preferenceKey, PersistentDataType.BYTE);
        boolean current = pref == null ? s.toggleDefault() : pref == 1;
        boolean next = ctx.size() > 0 ? ctx.arg(0).equalsIgnoreCase("on") : !current;
        p.getPersistentDataContainer().set(preferenceKey, PersistentDataType.BYTE, (byte) (next ? 1 : 0));
        refresh(p);
        ctx.reply(next ? "oldpvp.enabled" : "oldpvp.disabled");
    }
}
