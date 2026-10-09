package dev.ultimatecombat.modules.indicators;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floating damage numbers (damage-indicators.yml) using client-friendly text display entities that
 * are never saved and always removed.
 */
public final class DamageIndicatorModule extends Module<DamageIndicatorModule.Settings> {

    public enum Visibility { EVERYONE, ATTACKER, ATTACKER_AND_VICTIM }

    public record Settings(boolean playersHittingPlayers, boolean playersHittingMobs, boolean mobsHittingPlayers, Set<EntityType> blacklist,
                           long durationTicks, double rise, int decimals, RoundingMode rounding, Visibility visibility,
                           boolean criticals, String criticalParticle, boolean headshots, double headshotMultiplier,
                           TreeMap<Double, String> colors, int maxActive, String hitParticle, WorldRules worlds) {
    }

    private final Set<TextDisplay> active = ConcurrentHashMap.newKeySet();

    public DamageIndicatorModule(UltimateCombatPlugin plugin) {
        super(plugin, "damage-indicators", "damage-indicators.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        TreeMap<Double, String> colors = new TreeMap<>();
        for (String key : r.keys("colors")) {
            try {
                colors.put(Double.parseDouble(key), r.string("colors." + key, "&f"));
            } catch (NumberFormatException e) {
                r.warn("colors." + key, key, "Colour thresholds must be numbers (entry ignored)", "\"0\", \"5\", \"10\" ...", "entry skipped");
            }
        }
        if (colors.isEmpty()) {
            colors.put(0.0, "&f");
        }
        return new Settings(
                r.bool("show.players-hitting-players", true),
                r.bool("show.players-hitting-mobs", true),
                r.bool("show.mobs-hitting-players", false),
                r.entityTypes("entity-blacklist"),
                r.ticks("duration-ticks", 20, 5, 200),
                r.decimal("rise", 0.8, 0, 5),
                r.integer("decimals", 1, 0, 3),
                RoundingMode.valueOf(r.choice("rounding", "half-up", "half-up", "floor", "ceiling").toUpperCase(Locale.ROOT).replace('-', '_')),
                r.enumValue("visibility", Visibility.class, Visibility.EVERYONE),
                r.bool("critical-hits.enabled", true),
                r.string("critical-hits.particle", "critical-hit"),
                r.bool("headshots.enabled", false),
                r.decimal("headshots.damage-multiplier", 1.0, 0, 10),
                colors,
                r.integer("max-active", 64, 1, 1000),
                r.string("hit-particle", "none"),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onDisable() {
        for (TextDisplay display : new ArrayList<>(active)) {
            plugin.scheduler().executeEntity(display, display::remove);
        }
        active.clear();
    }

    private static boolean isHeadshot(Projectile projectile, LivingEntity victim) {
        double eye = victim.getEyeLocation().getY();
        return projectile.getLocation().getY() >= eye - 0.35;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeadshotDamage(EntityDamageByEntityEvent event) {
        Settings s = settings(event.getEntity().getWorld());
        if (s.headshots() && s.headshotMultiplier() != 1.0 && event.getDamager() instanceof Projectile p
                && p.getShooter() instanceof Player && event.getEntity() instanceof LivingEntity victim && isHeadshot(p, victim)) {
            event.setDamage(event.getDamage() * s.headshotMultiplier());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof LivingEntity victim) || event.getFinalDamage() <= 0) {
            return;
        }
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld()) || s.blacklist().contains(victim.getType()) || active.size() >= s.maxActive()) {
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        boolean victimPlayer = victim instanceof Player;
        boolean show = attacker != null && victimPlayer && s.playersHittingPlayers()
                || attacker != null && !victimPlayer && s.playersHittingMobs()
                || attacker == null && victimPlayer && s.mobsHittingPlayers();
        if (!show || attacker == victim) {
            return;
        }
        boolean critical = false;
        try {
            critical = s.criticals() && event.isCritical();
        } catch (NoSuchMethodError ignored) {
            // Spigot: no critical information
        }
        boolean headshot = s.headshots() && event.getDamager() instanceof Projectile p && isHeadshot(p, victim);
        double damage = BigDecimal.valueOf(event.getFinalDamage()).setScale(s.decimals(), s.rounding()).doubleValue();
        String number = s.decimals() == 0 ? Long.toString((long) damage) : Double.toString(damage);
        String color = s.colors().floorEntry(damage) == null ? "&f" : s.colors().floorEntry(damage).getValue();
        String key = headshot ? "indicators.headshot" : critical ? "indicators.critical" : "indicators.normal";
        String text = plugin.messages().text(key, "damage", number, "color", color);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location at = victim.getLocation().add(rnd.nextDouble(-0.4, 0.4), victim.getHeight() * 0.8 + rnd.nextDouble(0, 0.3),
                rnd.nextDouble(-0.4, 0.4));
        if (critical) {
            plugin.effects().spawnParticle(victim.getLocation(), s.criticalParticle());
        }
        plugin.effects().spawnParticle(victim.getLocation(), s.hitParticle());
        spawn(at, text, s, attacker, victim);
    }

    private void spawn(Location at, String text, Settings s, Player attacker, Entity victim) {
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setText(text);
            if (s.visibility() != Visibility.EVERYONE) {
                d.setVisibleByDefault(false);
            }
        });
        if (s.visibility() != Visibility.EVERYONE) {
            if (attacker != null) {
                plugin.scheduler().executeEntity(attacker, () -> attacker.showEntity(plugin, display));
            }
            if (s.visibility() == Visibility.ATTACKER_AND_VICTIM && victim instanceof Player pv) {
                pv.showEntity(plugin, display);
            }
        }
        active.add(display);
        plugin.scheduler().runEntityLater(display, () -> {
            Transformation t = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration((int) s.durationTicks());
            display.setTransformation(new Transformation(new Vector3f(0, (float) s.rise(), 0), t.getLeftRotation(), t.getScale(),
                    t.getRightRotation()));
        }, 1L);
        plugin.scheduler().runEntityLater(display, () -> {
            active.remove(display);
            display.remove();
        }, s.durationTicks() + 1);
    }

    /**
     * Number of indicators currently visible (for /uc status and tests).
     */
    public int activeCount() {
        return active.size();
    }
}
