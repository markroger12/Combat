package dev.ultimatecombat.modules.customtools;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.action.ActionExecutor;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Effects;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Custom combat tools (custom-tools.yml): weapons recognised by material, name, custom model
 * data or a custom id that trigger effects when they hit.
 *
 * <p>Damage changes are applied at {@link EventPriority#HIGH}; side effects (potions, fire,
 * lightning, actions) only at {@link EventPriority#MONITOR} when no other plugin cancelled the
 * hit, so protection plugins are always respected.</p>
 */
public final class CustomToolModule extends Module<CustomToolModule.Settings> {

    public enum Trigger { MELEE, PROJECTILE, BOTH }

    public enum Targets { PLAYERS, MOBS, ALL }

    public record ItemTemplate(Material material, String name, List<String> lore, int customModelData, Map<Enchantment, Integer> enchants,
                               boolean unbreakable, boolean hideFlags) {
    }

    public record Tool(String id, boolean enabled, Set<Material> materials, String nameContains, int customModelData, String customId,
                       String permission, Trigger trigger, Targets targets, double chance, long cooldownMillis,
                       List<PotionEffect> victimEffects, List<PotionEffect> attackerEffects, double extraDamage, double damageMultiplier,
                       int igniteTicks, boolean lightning, double knockback, double lifesteal, List<String> actions, String sound,
                       String particle, boolean notifyAttacker, boolean notifyVictim, ItemTemplate item) {

        boolean matchesTarget(LivingEntity victim) {
            return targets == Targets.ALL || (targets == Targets.PLAYERS) == (victim instanceof Player);
        }
    }

    public record Settings(Map<String, Tool> tools, WorldRules worlds) {
    }

    private record Pending(EntityDamageByEntityEvent event, Tool tool, Player attacker, LivingEntity victim) {
    }

    private final ThreadLocal<Pending> pending = new ThreadLocal<>();
    private final NamespacedKey idKey;
    private final NamespacedKey projectileKey;

    public CustomToolModule(UltimateCombatPlugin plugin) {
        super(plugin, "custom-tools", "custom-tools.yml");
        this.idKey = new NamespacedKey(plugin, "custom_tool");
        this.projectileKey = new NamespacedKey(plugin, "custom_tool_projectile");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (String id : r.keys("tools")) {
            ConfigReader t = r.section("tools." + id);
            Set<Material> materials = t.materialSet("match.materials");
            String customId = t.string("match.custom-id", "");
            String name = Text.strip(Text.color(t.string("match.name-contains", "")));
            int cmd = t.integer("match.custom-model-data", 0, 0, Integer.MAX_VALUE);
            if (materials.isEmpty() && customId.isEmpty()) {
                t.warn("match", "", "A tool needs match.materials or match.custom-id (tool ignored)", "see the example tool", "tool skipped");
                continue;
            }
            tools.put(id.toLowerCase(Locale.ROOT), new Tool(
                    id.toLowerCase(Locale.ROOT),
                    t.bool("enabled", true),
                    materials, name, cmd, customId,
                    t.string("permission", ""),
                    t.enumValue("trigger", Trigger.class, Trigger.MELEE),
                    t.enumValue("targets", Targets.class, Targets.ALL),
                    t.percent("chance", 100),
                    t.durationMillis("cooldown", 0, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000L),
                    Effects.parse(t, "on-hit.victim-effects"),
                    Effects.parse(t, "on-hit.attacker-effects"),
                    t.decimal("on-hit.extra-damage", 0, 0, 1000),
                    t.decimal("on-hit.damage-multiplier", 1.0, 0, 100),
                    (int) t.ticks("on-hit.ignite-ticks", 0, 0, 2400),
                    t.bool("on-hit.lightning-effect", false),
                    t.decimal("on-hit.knockback", 0, 0, 10),
                    t.percent("on-hit.lifesteal-percent", 0),
                    ActionExecutor.validate(t, "on-hit.actions", t.stringList("on-hit.actions")),
                    t.string("sound", "custom-tool"),
                    t.string("particle", "custom-tool"),
                    t.bool("notify.attacker", true),
                    t.bool("notify.victim", false),
                    template(t, materials)));
        }
        return new Settings(Map.copyOf(tools), WorldRules.read(r, "worlds"));
    }

    private static ItemTemplate template(ConfigReader t, Set<Material> matchMaterials) {
        Material def = matchMaterials.size() == 1 ? matchMaterials.iterator().next() : Material.IRON_SWORD;
        Map<Enchantment, Integer> enchants = new HashMap<>();
        for (String line : t.stringList("item.enchantments")) {
            String[] parts = line.trim().split("\\s+");
            Enchantment e = parts.length > 0 ? Registries.enchantment(parts[0]) : null;
            Integer level = parts.length > 1 ? Numbers.parseInt(parts[1]) : Integer.valueOf(1);
            if (e == null || level == null || level < 1 || level > 255) {
                t.warn("item.enchantments", line, "Unknown enchantment or invalid level (entry ignored)", "\"SHARPNESS 5\"", "entry skipped");
                continue;
            }
            enchants.put(e, level);
        }
        return new ItemTemplate(t.material("item.material", def), t.string("item.name", ""), t.stringList("item.lore"),
                t.integer("item.custom-model-data", 0, 0, Integer.MAX_VALUE), Map.copyOf(enchants), t.bool("item.unbreakable", false),
                t.bool("item.hide-flags", false));
    }

    @Override
    protected void onDisable() {
        pending.remove();
    }

    // ------------------------------------------------------------------------------ matching

    /**
     * @return the first enabled tool matching the item, or {@code null}
     */
    public Tool match(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String customId = meta == null ? null : meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        for (Tool tool : settings().tools().values()) {
            if (!tool.enabled()) {
                continue;
            }
            if (!tool.customId().isEmpty()) {
                if (tool.customId().equalsIgnoreCase(customId)) {
                    return tool;
                }
                continue;
            }
            if (!tool.materials().contains(item.getType())) {
                continue;
            }
            if (!tool.nameContains().isEmpty()) {
                String name = meta != null && meta.hasDisplayName() ? Text.strip(displayName(meta)) : "";
                if (!name.toLowerCase(Locale.ROOT).contains(tool.nameContains().toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            if (tool.customModelData() > 0 && (meta == null || !hasModelData(meta, tool.customModelData()))) {
                continue;
            }
            return tool;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String displayName(ItemMeta meta) {
        return meta.getDisplayName();
    }

    @SuppressWarnings("deprecation")
    private static boolean hasModelData(ItemMeta meta, int value) {
        return meta.hasCustomModelData() && meta.getCustomModelData() == value;
    }

    // ------------------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter) || !settings(shooter.getWorld()).worlds().allows(shooter.getWorld())) {
            return;
        }
        Tool tool = match(shooter.getInventory().getItemInMainHand());
        if (tool == null) {
            tool = match(shooter.getInventory().getItemInOffHand());
        }
        if (tool != null && tool.trigger() != Trigger.MELEE) {
            projectile.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, tool.id());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld())) {
            return;
        }
        Player attacker;
        Tool tool;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
            tool = match(p.getInventory().getItemInMainHand());
            if (tool != null && tool.trigger() == Trigger.PROJECTILE) {
                return;
            }
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            attacker = p;
            String id = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
            tool = id == null ? null : s.tools().get(id);
            if (tool == null || !tool.enabled() || tool.trigger() == Trigger.MELEE) {
                return;
            }
        } else {
            return;
        }
        if (tool == null || attacker == victim || !tool.matchesTarget(victim)
                || !tool.permission().isEmpty() && !attacker.hasPermission(tool.permission())) {
            return;
        }
        String cooldownKey = "custom-tool:" + tool.id();
        if (plugin.cooldowns().remaining(attacker, cooldownKey) > 0 || !Numbers.roll(tool.chance())) {
            return;
        }
        if (tool.damageMultiplier() != 1.0 || tool.extraDamage() > 0) {
            event.setDamage(event.getDamage() * tool.damageMultiplier() + tool.extraDamage());
        }
        pending.set(new Pending(event, tool, attacker, victim));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHitApplied(EntityDamageByEntityEvent event) {
        Pending p = pending.get();
        if (p == null || p.event() != event) {
            return;
        }
        pending.remove();
        if (event.isCancelled()) {
            return; // another plugin (protection, anti-cheat) cancelled the hit after us
        }
        Tool tool = p.tool();
        Player attacker = p.attacker();
        LivingEntity victim = p.victim();
        plugin.cooldowns().start(attacker, "custom-tool:" + tool.id(), tool.cooldownMillis(), toolName(tool), null);
        for (PotionEffect effect : tool.victimEffects()) {
            victim.addPotionEffect(effect);
        }
        if (!tool.attackerEffects().isEmpty() || tool.lifesteal() > 0) {
            double heal = event.getFinalDamage() * tool.lifesteal();
            plugin.scheduler().executeEntity(attacker, () -> {
                for (PotionEffect effect : tool.attackerEffects()) {
                    attacker.addPotionEffect(effect);
                }
                if (heal > 0 && !attacker.isDead()) {
                    var attr = Registries.maxHealth();
                    var inst = attr == null ? null : attacker.getAttribute(attr);
                    double max = inst == null ? 20 : inst.getValue();
                    attacker.setHealth(Math.min(max, attacker.getHealth() + heal));
                }
            });
        }
        if (tool.igniteTicks() > 0) {
            victim.setFireTicks(Math.max(victim.getFireTicks(), tool.igniteTicks()));
        }
        if (tool.lightning()) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
        }
        if (tool.knockback() > 0) {
            Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() > 1.0E-4) {
                Vector kb = direction.normalize().multiply(tool.knockback()).setY(Math.min(0.5, 0.15 + tool.knockback() * 0.1));
                plugin.scheduler().runEntityLater(victim, () -> victim.setVelocity(victim.getVelocity().add(kb)), 1L);
            }
        }
        plugin.effects().spawnParticle(victim.getLocation(), tool.particle());
        plugin.effects().playSound(victim.getLocation(), tool.sound());
        String victimName = KillService.entityName(victim);
        if (!tool.actions().isEmpty()) {
            Map<String, String> ph = new HashMap<>();
            ph.put("player", attacker.getName());
            ph.put("attacker", attacker.getName());
            ph.put("victim", victimName);
            ph.put("tool", tool.id());
            plugin.scheduler().executeEntity(attacker, () -> plugin.actions().run(attacker, tool.actions(), ph));
        }
        if (tool.notifyAttacker()) {
            plugin.scheduler().executeEntity(attacker, () ->
                    plugin.messages().send(attacker, "custom-tools.triggered", "tool", toolName(tool), "victim", victimName));
        }
        if (tool.notifyVictim() && victim instanceof Player pv) {
            plugin.messages().send(pv, "custom-tools.hit-by", "tool", toolName(tool), "attacker", attacker.getName());
        }
        plugin.debug().log("custom-tools", attacker.getName() + " triggered " + tool.id() + " on " + victimName);
    }

    private String toolName(Tool tool) {
        String name = tool.item().name();
        return name.isEmpty() ? KillService.pretty(tool.id().replace('-', '_')) : Text.color(name);
    }

    // ------------------------------------------------------------------------------ items

    /**
     * Creates the configured item of a tool, marked with its custom id.
     */
    public ItemStack createItem(Tool tool, int amount) {
        ItemTemplate t = tool.item();
        ItemBuilder builder = new ItemBuilder(t.material()).amount(amount);
        if (!t.name().isEmpty()) {
            builder.name(t.name());
        }
        if (!t.lore().isEmpty()) {
            builder.lore(t.lore());
        }
        if (t.customModelData() > 0) {
            builder.customModelData(t.customModelData());
        }
        builder.edit(meta -> {
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING,
                    tool.customId().isEmpty() ? tool.id() : tool.customId());
            meta.setUnbreakable(t.unbreakable());
            if (t.hideFlags()) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
            }
            for (Map.Entry<Enchantment, Integer> e : t.enchants().entrySet()) {
                meta.addEnchant(e.getKey(), e.getValue(), true);
            }
        });
        return builder.build();
    }

    @Override
    public void registerCommands() {
        SimpleCommand cmd = new SimpleCommand("customtools", "customtools <list|give> [player] [tool] [amount]",
                "ultimatecombat.customtools.admin", false, this::command, ctx -> switch (ctx.size()) {
                    case 1 -> List.of("list", "give");
                    case 2 -> ctx.arg(0).equalsIgnoreCase("give") ? UCCommand.onlineNames() : List.of();
                    case 3 -> new ArrayList<>(settings().tools().keySet());
                    default -> List.of();
                });
        plugin.commands().root().child(cmd);
    }

    private void command(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String sub = ctx.argOr(0, "list").toLowerCase(Locale.ROOT);
        if (sub.equals("give")) {
            Player target = ctx.onlinePlayer(1);
            if (target == null) {
                return;
            }
            Tool tool = settings().tools().get(ctx.argOr(2, "").toLowerCase(Locale.ROOT));
            if (tool == null) {
                ctx.reply("custom-tools.unknown", "tool", ctx.argOr(2, ""));
                return;
            }
            Integer amount = ctx.size() > 3 ? ctx.intArg(3, 1, 64) : Integer.valueOf(1);
            if (amount == null) {
                return;
            }
            ItemStack item = createItem(tool, amount);
            plugin.scheduler().executeEntity(target, () -> {
                for (ItemStack rest : target.getInventory().addItem(item).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), rest);
                }
            });
            ctx.reply("custom-tools.given", "tool", toolName(tool), "player", target.getName(), "amount", amount);
            return;
        }
        if (settings().tools().isEmpty()) {
            ctx.reply("custom-tools.none");
            return;
        }
        ctx.reply("custom-tools.list-header", "count", settings().tools().size());
        for (Tool tool : settings().tools().values()) {
            ctx.reply("custom-tools.list-entry", "id", tool.id(), "name", toolName(tool), "status", plugin.messages().bool(tool.enabled()),
                    "trigger", tool.trigger().name().toLowerCase(Locale.ROOT), "chance", Math.round(tool.chance() * 100));
        }
    }
}
