package dev.ultimatecombat.modules.deathmessages;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.message.Text;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.WorldRules;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Custom death messages (death-messages.yml): random messages per death category, weapon
 * tooltips, visibility rules, a word filter for item names, admin alerts and a per-player toggle.
 *
 * <p>Message pools live in the language files under {@code death-messages.<category>}.</p>
 */
public final class DeathMessageModule extends Module<DeathMessageModule.Settings> {

    public enum Visibility { EVERYONE, WORLD, RADIUS, INVOLVED, NONE }

    public record Settings(boolean replaceVanilla, boolean pvpOnly, Visibility visibility, double radius, boolean weaponHover,
                           boolean filterEnabled, List<Pattern> filterWords, String filterReplacement, boolean filterPlayerNames,
                           boolean adminAlerts, boolean console, boolean playerToggle, String sound, String particle,
                           WorldRules worlds) {
    }

    /** Placeholder for the weapon while building hover components (private-use character). */
    private static final String WEAPON_MARKER = "";

    private final NamespacedKey hiddenKey;

    public DeathMessageModule(UltimateCombatPlugin plugin) {
        super(plugin, "death-messages", "death-messages.yml");
        this.hiddenKey = new NamespacedKey(plugin, "hide_death_messages");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        List<Pattern> words = new ArrayList<>();
        for (String word : r.stringList("word-filter.words")) {
            if (!word.isBlank()) {
                words.add(Pattern.compile(Pattern.quote(word.trim()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
        }
        return new Settings(
                r.bool("replace-vanilla", true),
                r.bool("pvp-only", false),
                r.enumValue("visibility", Visibility.class, Visibility.EVERYONE),
                r.decimal("radius", 100, 1, 10_000),
                r.bool("weapon-hover", true),
                r.bool("word-filter.enabled", true),
                List.copyOf(words),
                r.string("word-filter.replacement", "***"),
                r.bool("word-filter.player-names", false),
                r.bool("admin-alerts", true),
                r.bool("console", true),
                r.bool("player-toggle", true),
                r.string("sound", "none"),
                r.string("particle", "none"),
                WorldRules.read(r, "worlds"));
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        SimpleCommand cmd = new SimpleCommand("deathmessages", "deathmessages [on|off]", "ultimatecombat.deathmessages.toggle", true,
                this::toggleCommand, ctx -> ctx.size() == 1 ? List.of("on", "off") : List.of());
        plugin.commands().root().child(cmd);
        plugin.commands().standalone("deathmessages", cmd);
    }

    private void toggleCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        if (!settings().playerToggle()) {
            ctx.reply("death-messages.toggle-disabled");
            return;
        }
        Player player = ctx.player();
        boolean hidden = isHidden(player);
        String arg = ctx.argOr(0, "").toLowerCase(Locale.ROOT);
        boolean hide = switch (arg) {
            case "on" -> false;
            case "off" -> true;
            default -> !hidden;
        };
        if (hide) {
            player.getPersistentDataContainer().set(hiddenKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(hiddenKey);
        }
        ctx.reply(hide ? "death-messages.toggled-off" : "death-messages.toggled-on");
    }

    /**
     * @return whether the player hid death messages of other players
     */
    public boolean isHidden(Player player) {
        return player.getPersistentDataContainer().has(hiddenKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------------------ deaths

    private record Resolved(String category, Player killer, Entity mob, ItemStack weapon, String weaponName) {
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.hasMetadata("NPC") || plugin.kills().isCombatLogDeathPending(victim.getUniqueId())) {
            return; // NPCs have no death messages; combat-log deaths use combatlog.death-message
        }
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld())) {
            return;
        }
        Resolved r = resolve(victim);
        if (s.pvpOnly() && r.killer() == null) {
            if (s.replaceVanilla()) {
                event.setDeathMessage(null);
            }
            return;
        }
        String key = pickKey(r.category());
        if (key == null) {
            if (s.replaceVanilla()) {
                event.setDeathMessage(null);
            }
            return; // no custom message: keep the vanilla one (or none)
        }
        event.setDeathMessage(null);
        List<String> pool = plugin.messages().rawLines(null, key);
        if (pool.isEmpty()) {
            return; // category explicitly disabled with [] or ""
        }
        int index = ThreadLocalRandom.current().nextInt(pool.size());
        Object[] placeholders = placeholders(victim, r, s);
        Location at = victim.getLocation();
        plugin.effects().spawnParticle(at, s.particle());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().executeEntity(viewer, () -> deliverTo(viewer, victim, r, key, index, placeholders, at, s));
        }
        if (s.console()) {
            String line = pool.get(index);
            Bukkit.getConsoleSender().sendMessage(plugin.messages().apply(null, line, withWeapon(placeholders, filtered(r.weaponName(), s))));
        }
    }

    private void deliverTo(Player viewer, Player victim, Resolved r, String key, int index, Object[] placeholders, Location at,
                           Settings s) {
        if (!viewer.isOnline()) {
            return;
        }
        boolean involved = viewer.equals(victim) || viewer.equals(r.killer());
        boolean visible = involved || (!s.playerToggle() || !isHidden(viewer)) && visibleFor(viewer, at, s);
        String prefix = "";
        if (!visible) {
            if (!s.adminAlerts() || !viewer.hasPermission("ultimatecombat.deathmessages.alerts")) {
                return;
            }
            prefix = plugin.messages().text(viewer, "death-messages.alert-prefix");
        }
        List<String> pool = plugin.messages().rawLines(viewer, key);
        if (pool.isEmpty()) {
            return;
        }
        String line = pool.get(Math.min(index, pool.size() - 1));
        String weaponName = filtered(r.weaponName(), s);
        if (s.weaponHover() && r.weapon() != null && line.contains("{weapon}")) {
            String formatted = prefix + plugin.messages().apply(viewer, line, withWeapon(placeholders, WEAPON_MARKER));
            viewer.spigot().sendMessage(withHover(formatted, weaponName, r.weapon(), s));
        } else {
            viewer.sendMessage(prefix + plugin.messages().apply(viewer, line, withWeapon(placeholders, weaponName)));
        }
        if (visible) {
            plugin.effects().playSound(viewer, s.sound());
        }
    }

    private static boolean visibleFor(Player viewer, Location at, Settings s) {
        return switch (s.visibility()) {
            case EVERYONE -> true;
            case WORLD -> viewer.getWorld().equals(at.getWorld());
            case RADIUS -> viewer.getWorld().equals(at.getWorld()) && viewer.getLocation().distanceSquared(at) <= s.radius() * s.radius();
            case INVOLVED, NONE -> false;
        };
    }

    private Resolved resolve(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        DamageCause cause = last == null ? DamageCause.CUSTOM : last.getCause();
        Entity damager = last instanceof EntityDamageByEntityEvent e ? e.getDamager() : null;
        Player killer = plugin.kills().creditedKiller(victim, last);
        if (killer != null) {
            ItemStack weapon = plugin.kills().lastWeapon(killer.getUniqueId());
            if (damager == killer && plugin.scheduler().isOwnedByCurrentThread(killer)) {
                weapon = killer.getInventory().getItemInMainHand().clone();
            }
            boolean hasWeapon = weapon != null && !weapon.getType().isAir();
            String category;
            if (damager instanceof Projectile) {
                category = "player-projectile";
            } else if (cause == DamageCause.ENTITY_EXPLOSION || cause == DamageCause.BLOCK_EXPLOSION) {
                category = "player-explosion";
            } else if (cause == DamageCause.FALL) {
                category = "player-fall";
            } else if (cause == DamageCause.VOID) {
                category = "player-void";
            } else if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK || cause == DamageCause.LAVA
                    || cause == DamageCause.HOT_FLOOR) {
                category = "player-fire";
            } else if (cause == DamageCause.MAGIC || cause == DamageCause.POISON || cause == DamageCause.WITHER
                    || cause == DamageCause.DRAGON_BREATH) {
                category = "player-magic";
            } else if (damager == killer) {
                category = hasWeapon ? "player-melee" : "player-fist";
            } else {
                category = "player-other";
            }
            String weaponName = hasWeapon ? KillService.itemName(weapon) : damager instanceof Projectile p ? KillService.entityName(p) : "";
            return new Resolved(category, killer, null, hasWeapon ? weapon : null, weaponName);
        }
        if (damager != null) {
            Entity source = damager instanceof Projectile p && p.getShooter() instanceof LivingEntity shooter ? shooter : damager;
            String category = cause == DamageCause.ENTITY_EXPLOSION ? "mob-explosion"
                    : damager instanceof Projectile ? "mob-projectile" : "mob";
            return new Resolved(category, null, source, null, "");
        }
        return new Resolved(cause.name().toLowerCase(Locale.ROOT).replace('_', '-'), null, null, null, "");
    }

    /**
     * Finds the language key for a category, falling back from specific to general categories.
     */
    private String pickKey(String category) {
        List<String> chain = new ArrayList<>(3);
        chain.add(category);
        if (category.startsWith("player-")) {
            chain.add("player-other");
        } else if (category.startsWith("mob-")) {
            chain.add("mob");
        } else if (!category.equals("mob")) {
            chain.add("default");
        }
        for (String c : chain) {
            String key = "death-messages.messages." + c;
            if (plugin.messages().exists(key)) {
                return key;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private Object[] placeholders(Player victim, Resolved r, Settings s) {
        Location l = victim.getLocation();
        Player killer = r.killer();
        String distance = "";
        double killerHealth = 0;
        if (killer != null) {
            killerHealth = killer.getHealth();
            if (killer.getWorld().equals(victim.getWorld())) {
                distance = Long.toString(Math.round(killer.getLocation().distance(l)));
            }
        }
        String victimName = filteredName(victim.getName(), s);
        String killerName = killer == null ? "" : filteredName(killer.getName(), s);
        return new Object[]{
                "victim", victimName,
                "victim_display", filteredName(victim.getDisplayName(), s),
                "killer", killerName,
                "killer_display", killer == null ? "" : filteredName(killer.getDisplayName(), s),
                "mob", r.mob() == null ? "" : KillService.entityName(r.mob()),
                "distance", distance,
                "killer_health", Numbers.twoDecimals(killerHealth),
                "killer_hearts", Numbers.twoDecimals(killerHealth / 2),
                "cause", r.category(),
                "world", l.getWorld() == null ? "" : l.getWorld().getName(),
                "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ(),
                // weapon is appended per delivery (marker or name)
        };
    }

    private static Object[] withWeapon(Object[] placeholders, String weapon) {
        Object[] out = java.util.Arrays.copyOf(placeholders, placeholders.length + 2);
        out[placeholders.length] = "weapon";
        out[placeholders.length + 1] = weapon;
        return out;
    }

    private String filteredName(String name, Settings s) {
        return s.filterPlayerNames() ? filter(name, s) : name;
    }

    private String filtered(String weaponName, Settings s) {
        return filter(weaponName, s);
    }

    /**
     * Replaces filtered words (case-insensitive) in item or player names.
     */
    static String filter(String text, Settings s) {
        if (text == null || text.isEmpty() || !s.filterEnabled() || s.filterWords().isEmpty()) {
            return text == null ? "" : text;
        }
        String out = text;
        String plain = Text.strip(text);
        for (Pattern p : s.filterWords()) {
            if (p.matcher(plain).find()) {
                // colours could split a word: fall back to the plain text when a word was found
                out = p.matcher(plain).replaceAll(java.util.regex.Matcher.quoteReplacement(s.filterReplacement()));
                plain = out;
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private BaseComponent[] withHover(String formatted, String weaponName, ItemStack weapon, Settings s) {
        List<BaseComponent> parts = new ArrayList<>();
        String[] pieces = formatted.split(Pattern.quote(WEAPON_MARKER), -1);
        String carry = "";
        for (int i = 0; i < pieces.length; i++) {
            String piece = carry + pieces[i];
            java.util.Collections.addAll(parts, TextComponent.fromLegacyText(piece));
            carry = org.bukkit.ChatColor.getLastColors(piece);
            if (i < pieces.length - 1) {
                TextComponent item = new TextComponent(TextComponent.fromLegacyText(carry + weaponName));
                item.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new net.md_5.bungee.api.chat.hover.content.Text(TextComponent.fromLegacyText(tooltip(weapon, weaponName)))));
                parts.add(item);
            }
        }
        return parts.toArray(new BaseComponent[0]);
    }

    @SuppressWarnings("deprecation")
    private String tooltip(ItemStack item, String name) {
        StringBuilder sb = new StringBuilder(name.isEmpty() ? KillService.pretty(item.getType().name()) : name);
        if (item.getAmount() > 1) {
            sb.append(Text.color(" &7x")).append(item.getAmount());
        }
        for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
            sb.append('\n').append(Text.color("&7")).append(KillService.pretty(e.getKey().getKey().getKey()))
                    .append(' ').append(Numbers.roman(e.getValue()));
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            for (String lore : meta.getLore()) {
                sb.append('\n').append(filter(lore, settings()));
            }
        }
        return sb.toString();
    }
}
