package dev.ultimatecombat.modules.apples;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Effects;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Golden apple and enchanted golden apple settings (apples.yml): cooldowns, custom effects,
 * instant eating and custom crafting recipes with an in-game recipe editor.
 */
public final class AppleModule extends Module<AppleModule.Settings> {

    public record Apple(String id, Material material, boolean enabled, long cooldownMillis, boolean cooldownOnlyInCombat,
                        boolean replaceVanilla, List<PotionEffect> effects, boolean instantEat, boolean recipeEnabled, List<String> shape,
                        Map<Character, Material> ingredients, WorldRules worlds) {
    }

    public record Settings(Apple golden, Apple enchanted) {
    }

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int SAVE_SLOT = 24;
    private static final int CANCEL_SLOT = 25;

    private final Map<UUID, Material> eating = new ConcurrentHashMap<>();
    private final Set<NamespacedKey> registeredRecipes = ConcurrentHashMap.newKeySet();

    public AppleModule(UltimateCombatPlugin plugin) {
        super(plugin, "golden-apples", "apples.yml");
    }

    private static Apple apple(String id, Material material, ConfigReader r) {
        Map<Character, Material> ingredients = new LinkedHashMap<>();
        for (String key : r.keys("recipe.ingredients")) {
            if (key.length() != 1) {
                r.warn("recipe.ingredients." + key, key, "Ingredient keys must be one character", "a single letter such as G", "skipped");
                continue;
            }
            Material m = r.material("recipe.ingredients." + key, null);
            if (m != null) {
                ingredients.put(key.charAt(0), m);
            }
        }
        List<String> shape = r.stringList("recipe.shape");
        boolean recipe = r.bool("recipe.enabled", false);
        if (recipe && (shape.isEmpty() || shape.size() > 3 || shape.stream().anyMatch(s -> s.isEmpty() || s.length() > 3))) {
            r.warn("recipe.shape", String.valueOf(shape), "Invalid recipe shape (recipe disabled)", "1-3 rows of 1-3 characters", "no recipe");
            recipe = false;
        }
        return new Apple(id, material,
                r.bool("enabled", true),
                r.durationMillis("cooldown.seconds", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.bool("cooldown.only-in-combat", false),
                r.bool("effects.replace-vanilla", false),
                Effects.parse(r, "effects.list"),
                r.bool("instant-eat", false),
                recipe, shape, ingredients,
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(apple("golden", Material.GOLDEN_APPLE, r.section("golden-apple")),
                apple("enchanted", Material.ENCHANTED_GOLDEN_APPLE, r.section("enchanted-golden-apple")));
    }

    @Override
    protected void onEnable() {
        registerRecipes();
    }

    @Override
    protected void onReload(Settings previous) {
        registerRecipes();
    }

    @Override
    protected void onDisable() {
        removeRecipes();
        eating.clear();
    }

    private Apple config(Material material, Player player) {
        Settings s = settings(player.getWorld());
        return material == Material.GOLDEN_APPLE ? s.golden() : material == Material.ENCHANTED_GOLDEN_APPLE ? s.enchanted() : null;
    }

    // ------------------------------------------------------------------------------ recipes

    private void removeRecipes() {
        for (NamespacedKey key : registeredRecipes) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipes.clear();
    }

    private void registerRecipes() {
        removeRecipes();
        Settings s = settings();
        for (Apple apple : List.of(s.golden(), s.enchanted())) {
            if (!apple.enabled() || !apple.recipeEnabled()) {
                continue;
            }
            NamespacedKey key = new NamespacedKey(plugin, apple.id() + "_apple");
            ShapedRecipe recipe = new ShapedRecipe(key, new ItemStack(apple.material()));
            recipe.shape(apple.shape().toArray(new String[0]));
            boolean ok = true;
            for (String row : apple.shape()) {
                for (char c : row.toCharArray()) {
                    if (c == ' ') {
                        continue;
                    }
                    Material m = apple.ingredients().get(c);
                    if (m == null) {
                        plugin.getLogger().warning("apples.yml: recipe of " + apple.id() + " uses '" + c + "' without an ingredient; recipe skipped");
                        ok = false;
                        break;
                    }
                    recipe.setIngredient(c, m);
                }
            }
            if (ok && Bukkit.addRecipe(recipe)) {
                registeredRecipes.add(key);
            }
        }
        try {
            Bukkit.updateRecipes();
        } catch (NoSuchMethodError ignored) {
            // Spigot: clients see new recipes after relog
        }
    }

    // ------------------------------------------------------------------------------ eating

    private boolean inCombat(Player p) {
        CombatModule combat = plugin.active(CombatModule.class);
        return combat != null && combat.isTagged(p);
    }

    private boolean checkCooldown(Player player, Apple apple) {
        if (apple.cooldownMillis() <= 0 || apple.cooldownOnlyInCombat() && !inCombat(player) || plugin.bypasses(player, "apples")) {
            return true;
        }
        String key = "apple:" + apple.id();
        String display = plugin.messages().text(player, "apples.names." + apple.id());
        if (!plugin.cooldowns().ready(player, key, display)) {
            return false;
        }
        plugin.cooldowns().start(player, key, apple.cooldownMillis(), display, apple.material());
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Apple apple = config(event.getItem().getType(), player);
        if (apple == null || !apple.enabled() || !apple.worlds().allows(player.getWorld())) {
            return;
        }
        if (!checkCooldown(player, apple)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsumed(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Apple apple = config(event.getItem().getType(), player);
        if (apple == null || !apple.enabled() || !apple.worlds().allows(player.getWorld())) {
            return;
        }
        eating.put(player.getUniqueId(), apple.material());
        plugin.scheduler().runEntityLater(player, () -> {
            eating.remove(player.getUniqueId());
            for (PotionEffect effect : apple.effects()) {
                player.addPotionEffect(effect);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodEffect(EntityPotionEffectEvent event) {
        if (event.getCause() != EntityPotionEffectEvent.Cause.FOOD || !(((org.bukkit.event.entity.EntityEvent) event).getEntity() instanceof Player player)) {
            return;
        }
        Material eaten = eating.get(player.getUniqueId());
        if (eaten == null) {
            return;
        }
        Apple apple = config(eaten, player);
        if (apple != null && apple.replaceVanilla()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInstantEat(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        Apple apple = config(item.getType(), player);
        if (apple == null || !apple.enabled() || !apple.instantEat() || !apple.worlds().allows(player.getWorld())) {
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
        PlayerInventory inv = player.getInventory();
        ItemStack held = hand == EquipmentSlot.OFF_HAND ? inv.getItemInOffHand() : inv.getItemInMainHand();
        held.setAmount(held.getAmount() - 1);
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + 4));
        player.setSaturation(Math.min(player.getFoodLevel(), player.getSaturation() + 9.6f));
        if (!apple.replaceVanilla()) {
            for (PotionEffect effect : vanillaEffects(apple.material())) {
                player.addPotionEffect(effect);
            }
        }
        player.playSound(player.getLocation(), "minecraft:entity.player.burp", 0.5f, 1.0f);
    }

    private static List<PotionEffect> vanillaEffects(Material material) {
        List<PotionEffect> out = new ArrayList<>();
        if (material == Material.GOLDEN_APPLE) {
            add(out, "regeneration", 100, 1);
            add(out, "absorption", 2400, 0);
        } else {
            add(out, "regeneration", 400, 1);
            add(out, "absorption", 2400, 3);
            add(out, "resistance", 6000, 0);
            add(out, "fire_resistance", 6000, 0);
        }
        return out;
    }

    private static void add(List<PotionEffect> out, String key, int ticks, int amp) {
        var type = Registries.potionEffect(key);
        if (type != null) {
            out.add(new PotionEffect(type, ticks, amp));
        }
    }

    // ------------------------------------------------------------------------------ recipe editor

    private final class Editor implements InventoryHolder {
        final Apple apple;
        final Inventory inventory;

        @SuppressWarnings("deprecation")
        Editor(Apple apple, Player viewer) {
            this.apple = apple;
            this.inventory = Bukkit.createInventory(this, 45, plugin.messages().text(viewer, "apples.editor-title",
                    "apple", plugin.messages().text(viewer, "apples.names." + apple.id())));
            ItemStack filler = dev.ultimatecombat.core.util.ItemBuilder.named(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < 45; i++) {
                inventory.setItem(i, filler);
            }
            for (int i = 0; i < GRID.length; i++) {
                inventory.setItem(GRID[i], null);
                int row = i / 3;
                int col = i % 3;
                if (row < apple.shape().size() && col < apple.shape().get(row).length()) {
                    Material m = apple.ingredients().get(apple.shape().get(row).charAt(col));
                    if (m != null) {
                        inventory.setItem(GRID[i], new ItemStack(m));
                    }
                }
            }
            inventory.setItem(SAVE_SLOT, dev.ultimatecombat.core.util.ItemBuilder.named(Material.LIME_CONCRETE,
                    plugin.messages().text(viewer, "apples.editor-save")));
            inventory.setItem(CANCEL_SLOT, dev.ultimatecombat.core.util.ItemBuilder.named(Material.RED_CONCRETE,
                    plugin.messages().text(viewer, "apples.editor-cancel")));
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static boolean isGrid(int slot) {
        for (int g : GRID) {
            if (g == slot) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEditorClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Editor editor) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == editor.inventory) {
            int slot = event.getSlot();
            if (slot == SAVE_SLOT) {
                event.setCancelled(true);
                save(player, editor);
            } else if (slot == CANCEL_SLOT) {
                event.setCancelled(true);
                player.closeInventory();
            } else if (!isGrid(slot)) {
                event.setCancelled(true);
            }
        } else if (event.isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEditorDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Editor) {
            for (int raw : event.getRawSlots()) {
                if (raw < 45 && !isGrid(raw)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onEditorClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Editor editor && event.getPlayer() instanceof Player player) {
            // Give back every item placed in the grid so nothing is lost.
            for (int slot : GRID) {
                ItemStack item = editor.inventory.getItem(slot);
                if (item != null && !item.getType().isAir()) {
                    editor.inventory.setItem(slot, null);
                    for (ItemStack rest : player.getInventory().addItem(item).values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                }
            }
        }
    }

    private void save(Player player, Editor editor) {
        Map<Material, Character> letters = new LinkedHashMap<>();
        List<String> shape = new ArrayList<>();
        char next = 'A';
        for (int row = 0; row < 3; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 3; col++) {
                ItemStack item = editor.inventory.getItem(GRID[row * 3 + col]);
                if (item == null || item.getType().isAir()) {
                    line.append(' ');
                    continue;
                }
                Character c = letters.get(item.getType());
                if (c == null) {
                    c = next++;
                    letters.put(item.getType(), c);
                }
                line.append(c);
            }
            shape.add(line.toString());
        }
        while (!shape.isEmpty() && shape.get(shape.size() - 1).isBlank()) {
            shape.remove(shape.size() - 1);
        }
        while (!shape.isEmpty() && shape.get(0).isBlank()) {
            shape.remove(0);
        }
        if (shape.isEmpty()) {
            plugin.messages().send(player, "apples.editor-empty");
            return;
        }
        Map<String, Object> ingredients = new LinkedHashMap<>();
        letters.forEach((m, c) -> ingredients.put(String.valueOf(c), m.name()));
        String base = editor.apple.id().equals("golden") ? "golden-apple" : "enchanted-golden-apple";
        ConfigFile file = plugin.configs().file("apples.yml");
        plugin.configs().newBackupSession();
        boolean ok = file.write(base + ".recipe.shape", shape) && file.write(base + ".recipe.ingredients", ingredients)
                && file.write(base + ".recipe.enabled", true);
        player.closeInventory();
        if (ok) {
            plugin.reload("apples.yml");
            plugin.messages().send(player, "apples.editor-saved");
        } else {
            plugin.messages().send(player, "admin.config.write-failed", "file", "apples.yml");
        }
    }

    @Override
    public void registerCommands() {
        plugin.commands().root().child(new SimpleCommand("apple", "apple recipe <golden|enchanted>", "ultimatecombat.apple.admin", true,
                this::appleCommand, ctx -> ctx.size() == 1 ? List.of("recipe") : List.of("golden", "enchanted")));
    }

    private void appleCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (ctx.size() < 2 || !ctx.arg(0).equalsIgnoreCase("recipe")) {
            ctx.reply("general.usage", "usage", "/uc apple recipe <golden|enchanted>");
            return;
        }
        Apple apple = ctx.arg(1).equalsIgnoreCase("golden") ? settings().golden() : settings().enchanted();
        Editor editor = new Editor(apple, ctx.player());
        ctx.player().openInventory(editor.inventory);
    }
}
