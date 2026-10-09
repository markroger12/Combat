package dev.ultimatecombat.modules.restrictions;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.event.CombatTagEvent;
import dev.ultimatecombat.api.event.CombatUntagEvent;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combat.CombatTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurable restrictions (restrictions.yml): commands, movement, items, blocks, inventories and
 * player states, each with its own conditions, cooldown, permissions and message.
 */
public final class RestrictionsModule extends Module<RestrictionsSettings> {

    private final Set<UUID> restoreFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> spamGuard = new ConcurrentHashMap<>();

    public RestrictionsModule(UltimateCombatPlugin plugin) {
        super(plugin, "restrictions", "restrictions.yml");
    }

    @Override
    protected RestrictionsSettings parse(ConfigReader reader) {
        return RestrictionsSettings.parse(reader);
    }

    @Override
    protected void onDisable() {
        for (UUID id : restoreFlight) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.scheduler().runEntity(p, () -> restoreFlight(p));
            }
        }
        restoreFlight.clear();
        spamGuard.clear();
    }

    // ------------------------------------------------------------------------------ rule logic

    private CombatModule combat() {
        return plugin.active(CombatModule.class);
    }

    private boolean inCombatFor(Player player, RestrictionRule rule) {
        CombatModule combat = combat();
        if (combat == null) {
            return false;
        }
        CombatTag tag = combat.tag(player);
        return tag != null && tag.remainingMillis() > 0 && rule.combatTypes().contains(tag.type());
    }

    private boolean bypasses(Player player, RestrictionRule rule) {
        String def = "ultimatecombat.bypass.restriction." + rule.id();
        if (rule.bypassPermission().isEmpty()) {
            return false;
        }
        if (rule.bypassPermission().equals(def)) {
            return plugin.bypasses(player, "restriction." + rule.id()) || plugin.bypasses(player, "restrictions");
        }
        return player.hasPermission(rule.bypassPermission()) || plugin.bypasses(player, "restrictions");
    }

    /**
     * @return whether the rule currently restricts the player (ignoring cooldown state)
     */
    public boolean restricts(Player player, String ruleId) {
        RestrictionRule rule = settings(player.getWorld()).rules().get(ruleId);
        if (rule == null || !rule.enabled() || !rule.worlds().allows(player.getWorld()) || bypasses(player, rule)) {
            return false;
        }
        boolean inCombat = inCombatFor(player, rule);
        return switch (rule.when()) {
            case IN_COMBAT -> inCombat;
            case OUT_OF_COMBAT -> !inCombat;
            case ALWAYS -> true;
        };
    }

    /**
     * Full check of a usage attempt including permission, cooldown and feedback.
     *
     * @return {@code true} if the action may proceed
     */
    public boolean attempt(Player player, String ruleId, Material cooldownIcon) {
        RestrictionRule rule = settings(player.getWorld()).rules().get(ruleId);
        if (rule == null || !rule.enabled() || !rule.worlds().allows(player.getWorld()) || bypasses(player, rule)) {
            return true;
        }
        if (!rule.permission().isEmpty() && !player.hasPermission(rule.permission())) {
            deny(player, rule, "restrictions.missing-permission");
            return false;
        }
        boolean inCombat = inCombatFor(player, rule);
        boolean applies = switch (rule.when()) {
            case IN_COMBAT -> inCombat;
            case OUT_OF_COMBAT -> !inCombat;
            case ALWAYS -> true;
        };
        if (!applies) {
            return true;
        }
        if (rule.action() == RestrictionRule.Action.COOLDOWN) {
            String key = "restriction:" + ruleId;
            String display = displayName(player, ruleId);
            if (!plugin.cooldowns().ready(player, key, display)) {
                plugin.effects().playSound(player, rule.sound());
                return false;
            }
            plugin.cooldowns().start(player, key, rule.cooldownMillis(), display, cooldownIcon);
            return true;
        }
        deny(player, rule, null);
        return false;
    }

    private String displayName(Player player, String ruleId) {
        return plugin.messages().text(player, "restrictions.names." + ruleId);
    }

    private void deny(Player player, RestrictionRule rule, String overrideKey) {
        String guardKey = player.getUniqueId() + ":" + rule.id();
        long now = System.currentTimeMillis();
        Long last = spamGuard.get(guardKey);
        if (last != null && now - last < 1000) {
            return;
        }
        spamGuard.put(guardKey, now);
        Object[] ph = {"player", player.getName(), "action", displayName(player, rule.id()),
                "time", combat() == null ? "0" : combat().seconds(combat().remainingMillis(player))};
        if (overrideKey != null) {
            plugin.messages().send(player, overrideKey, ph);
        } else if (!rule.message().isEmpty()) {
            plugin.messages().sendRaw(player, rule.message(), ph);
        } else {
            plugin.messages().send(player, "restrictions.blocked." + rule.id(), ph);
        }
        plugin.effects().playSound(player, rule.sound());
        plugin.debug().log("restrictions", player.getName() + " blocked by rule " + rule.id());
    }

    // ------------------------------------------------------------------------------ commands

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        RestrictionsSettings.CommandRules rules = settings(player.getWorld()).commands();
        if (!rules.enabled() || !rules.worlds().allows(player.getWorld())) {
            return;
        }
        if (plugin.bypasses(player, "commands") || plugin.bypasses(player, "restrictions")) {
            return;
        }
        String typed = RestrictionsSettings.normalize(event.getMessage());
        List<String> forms = commandForms(typed);
        boolean blockedAlways = matchesAny(forms, rules.blockedAlways());
        boolean blocked = blockedAlways;
        if (!blocked) {
            CombatModule combat = combat();
            CombatTag tag = combat == null ? null : combat.tag(player);
            if (tag == null || tag.remainingMillis() <= 0 || !rules.combatTypes().contains(tag.type())) {
                return;
            }
            boolean listed = matchesAny(forms, rules.list());
            blocked = rules.whitelist() != listed;
        }
        if (!blocked) {
            return;
        }
        event.setCancelled(true);
        String label = typed.contains(" ") ? typed.substring(0, typed.indexOf(' ')) : typed;
        Object[] ph = {"command", "/" + label, "time", combat() == null ? "0" : combat().seconds(combat().remainingMillis(player))};
        if (!rules.message().isEmpty()) {
            plugin.messages().sendRaw(player, rules.message(), ph);
        } else {
            plugin.messages().send(player, blockedAlways ? "restrictions.command-disabled" : "restrictions.command-blocked", ph);
        }
        plugin.effects().playSound(player, rules.sound());
    }

    /**
     * @return the typed command plus the same command with its real name and every alias
     */
    private static List<String> commandForms(String typed) {
        List<String> forms = new ArrayList<>();
        forms.add(typed);
        int space = typed.indexOf(' ');
        String label = space < 0 ? typed : typed.substring(0, space);
        String rest = space < 0 ? "" : typed.substring(space);
        try {
            Command cmd = Bukkit.getCommandMap().getCommand(label);
            if (cmd != null) {
                forms.add(cmd.getName().toLowerCase(Locale.ROOT) + rest);
                for (String alias : cmd.getAliases()) {
                    forms.add(alias.toLowerCase(Locale.ROOT) + rest);
                }
            }
        } catch (RuntimeException | NoSuchMethodError ignored) {
            // command map not available
        }
        return forms;
    }

    private static boolean matchesAny(List<String> forms, List<String> entries) {
        for (String entry : entries) {
            if (entry.equals("*")) {
                return true;
            }
            for (String form : forms) {
                if (form.equals(entry) || form.startsWith(entry + " ")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------ tag start/end

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTag(CombatTagEvent event) {
        if (event.isRefresh() && !event.getType().equals(CombatType.PVP)) {
            return;
        }
        Player player = event.getPlayer();
        RestrictionsSettings s = settings(player.getWorld());
        RestrictionsSettings.Options o = s.options();
        plugin.scheduler().executeEntity(player, () -> {
            if (o.flightDisableOnTag() && wouldRestrictInCombat(player, s.rules().get("flight"), event.getType())
                    && (player.getAllowFlight() || player.isFlying())
                    && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                if (o.flightRestoreAfterCombat() && player.getAllowFlight()) {
                    restoreFlight.add(player.getUniqueId());
                }
                player.setFlying(false);
                player.setAllowFlight(false);
                plugin.messages().send(player, "restrictions.flight-disabled");
            }
            if (o.elytraStopGlidingOnTag() && player.isGliding() && wouldRestrictInCombat(player, s.rules().get("elytra"), event.getType())) {
                player.setGliding(false);
            }
            if (o.creativeForceSurvivalOnTag() && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)
                    && wouldRestrictInCombat(player, s.rules().get("creative"), event.getType())) {
                player.setGameMode(GameMode.SURVIVAL);
                plugin.messages().send(player, "restrictions.gamemode-reset");
            }
            if (o.godModeDisableOnTag() && wouldRestrictInCombat(player, s.rules().get("god-mode"), event.getType())
                    && plugin.integrations().isGodMode(player)) {
                plugin.integrations().disableGodMode(player);
                plugin.messages().send(player, "restrictions.godmode-disabled");
            }
            if (o.vanishUnvanishOnTag() && wouldRestrictInCombat(player, s.rules().get("vanish"), event.getType())
                    && plugin.integrations().isVanished(player)) {
                plugin.integrations().setVanished(player, false);
                plugin.messages().send(player, "restrictions.vanish-disabled");
            }
            if (o.disguiseUndisguiseOnTag() && wouldRestrictInCombat(player, s.rules().get("disguise"), event.getType())
                    && plugin.integrations().isDisguised(player)) {
                plugin.integrations().undisguise(player);
                plugin.messages().send(player, "restrictions.disguise-removed");
            }
        });
    }

    private boolean wouldRestrictInCombat(Player player, RestrictionRule rule, CombatType type) {
        if (rule == null || !rule.enabled() || !rule.worlds().allows(player.getWorld()) || bypasses(player, rule)) {
            return false;
        }
        return rule.when() != RestrictionRule.When.OUT_OF_COMBAT && rule.combatTypes().contains(type);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(CombatUntagEvent event) {
        Player player = event.getPlayer();
        if (restoreFlight.remove(player.getUniqueId())) {
            plugin.scheduler().executeEntity(player, () -> restoreFlight(player));
        }
    }

    private void restoreFlight(Player player) {
        if (player.isOnline() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
            player.setAllowFlight(true);
            plugin.messages().send(player, "restrictions.flight-restored");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        restoreFlight.remove(event.getPlayer().getUniqueId());
        String prefix = event.getPlayer().getUniqueId() + ":";
        spamGuard.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ------------------------------------------------------------------------------ movement / state

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying() && event.getPlayer().getGameMode() != GameMode.CREATIVE
                && event.getPlayer().getGameMode() != GameMode.SPECTATOR && !attempt(event.getPlayer(), "flight", null)) {
            event.setCancelled(true);
            event.getPlayer().setFlying(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player player && !attempt(player, "elytra", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        GameMode mode = event.getNewGameMode();
        if ((mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR)
                && settings(event.getPlayer().getWorld()).options().creativeBlockGameModeChange()
                && !attempt(event.getPlayer(), "creative", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause.name().equals("CHORUS_FRUIT") || cause.name().equals("CONSUMABLE_EFFECT")) {
            if (!attempt(player, "chorus-fruit", null)) {
                event.setCancelled(true);
            }
            return;
        }
        RestrictionsSettings.Options o = settings(player.getWorld()).options();
        if (!o.teleportCauses().contains(cause)) {
            return;
        }
        if (o.teleportAllowDistance() > 0 && event.getTo() != null && event.getFrom().getWorld() == event.getTo().getWorld()
                && event.getFrom().distanceSquared(event.getTo()) <= o.teleportAllowDistance() * o.teleportAllowDistance()) {
            return;
        }
        if (!attempt(player, "teleport", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        if (!attempt(event.getPlayer(), "sleeping", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        if (restricts(event.getPlayer(), "mending")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player && !attempt(player, "totems", null)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------ interactions

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Material type = item == null ? Material.AIR : item.getType();
        RestrictionsSettings.Options o = settings(player.getWorld()).options();

        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.TNT
                && (type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE) && !attempt(player, "tnt", null)) {
            event.setCancelled(true);
            return;
        }
        if (item == null) {
            return;
        }
        String rule = null;
        if (type == Material.ENDER_PEARL) {
            rule = "ender-pearls";
        } else if (type.name().equals("WIND_CHARGE")) {
            rule = "wind-charges";
        } else if (type == Material.FIREWORK_ROCKET && player.isGliding() && o.elytraBlockFireworkBoost()) {
            rule = "elytra";
        } else if (type == Material.TRIDENT && hasRiptide(item)) {
            rule = "riptide";
        } else if (type == Material.SPLASH_POTION && o.potionsSplash() || type == Material.LINGERING_POTION && o.potionsLingering()) {
            rule = "potions";
        } else if (type == Material.SHIELD || isShieldUse(player, type)) {
            rule = "shields";
        } else if (isArmor(type) && action == Action.RIGHT_CLICK_AIR) {
            rule = "armor-switching";
        } else if (matchesCustomItem(item, o)) {
            rule = "custom-items";
        }
        if (rule != null && !attempt(player, rule, type)) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    private static boolean isShieldUse(Player player, Material mainHand) {
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off.getType() != Material.SHIELD) {
            return false;
        }
        return mainHand == Material.AIR || mainHand.name().endsWith("_SWORD") || mainHand.name().endsWith("_AXE");
    }

    private static boolean hasRiptide(ItemStack item) {
        Enchantment riptide = Registries.enchantment("riptide");
        return riptide != null && item.getEnchantmentLevel(riptide) > 0;
    }

    static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || m == Material.ELYTRA;
    }

    private boolean matchesCustomItem(ItemStack item, RestrictionsSettings.Options o) {
        if (o.customItems().isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        for (RestrictionsSettings.ItemMatcher m : o.customItems()) {
            if (m.material() != null && item.getType() != m.material()) {
                continue;
            }
            if (!m.nameContains().isEmpty()) {
                @SuppressWarnings("deprecation")
                String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
                if (!dev.ultimatecombat.core.message.Text.strip(name).toLowerCase(Locale.ROOT).contains(m.nameContains())) {
                    continue;
                }
            }
            if (m.customModelData() > 0) {
                @SuppressWarnings("deprecation")
                boolean ok = meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == m.customModelData();
                if (!ok) {
                    continue;
                }
            }
            if (!m.customId().isEmpty()) {
                String id = plugin.integrations().customItemId(item);
                if (id == null || !id.equalsIgnoreCase(m.customId())) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material type = event.getItem().getType();
        RestrictionsSettings.Options o = settings(player.getWorld()).options();
        String rule = switch (type) {
            case CHORUS_FRUIT -> "chorus-fruit";
            case GOLDEN_APPLE -> "golden-apples";
            case ENCHANTED_GOLDEN_APPLE -> "enchanted-golden-apples";
            case POTION -> o.potionsDrinkable() ? "potions" : null;
            default -> matchesCustomItem(event.getItem(), o) ? "custom-items" : null;
        };
        if (rule != null && !attempt(player, rule, type)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (target instanceof Player) {
            return;
        }
        Set<org.bukkit.entity.EntityType> types = settings(event.getPlayer().getWorld()).options().entityTypes();
        if (!types.isEmpty() && !types.contains(target.getType())) {
            return;
        }
        if (!attempt(event.getPlayer(), "entity-interactions", null)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (settings(event.getPlayer().getWorld()).options().blockBreakExempt().contains(event.getBlock().getType())) {
            return;
        }
        if (!attempt(event.getPlayer(), "block-break", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Material type = event.getBlockPlaced().getType();
        if (type == Material.TNT && !attempt(player, "tnt", Material.TNT)) {
            event.setCancelled(true);
            return;
        }
        if (settings(player.getWorld()).options().blockPlaceExempt().contains(type)) {
            return;
        }
        if (!attempt(player, "block-place", null)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------ inventories

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof dev.ultimatecombat.core.gui.Menu) {
            return;
        }
        InventoryType type = event.getInventory().getType();
        RestrictionsSettings.Options o = settings(player.getWorld()).options();
        String rule = o.containerTypes().contains(type) ? "containers" : o.enchantingTypes().contains(type) ? "enchanting" : null;
        if (rule != null && !attempt(player, rule, null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof dev.ultimatecombat.core.gui.Menu) {
            return;
        }
        boolean ownInventory = event.getView().getTopInventory().getType() == InventoryType.CRAFTING;
        boolean armorClick = event.getSlotType() == InventoryType.SlotType.ARMOR
                || event.isShiftClick() && event.getCurrentItem() != null && isArmor(event.getCurrentItem().getType()) && ownInventory;
        if (armorClick && restricts(player, "armor-switching")) {
            if (!attempt(player, "armor-switching", null)) {
                event.setCancelled(true);
                return;
            }
        }
        if (ownInventory && event.getClickedInventory() != null && restricts(player, "inventory")
                && !attempt(player, "inventory", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (settings(event.getPlayer().getWorld()).options().inventoryIncludeDropping() && !attempt(event.getPlayer(), "inventory", null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof Player player && restricts(player, "armor-switching")) {
            event.setCancelled(true);
        }
    }
}
