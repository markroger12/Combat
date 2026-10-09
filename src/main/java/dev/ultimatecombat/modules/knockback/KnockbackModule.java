package dev.ultimatecombat.modules.knockback;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.util.Registries;
import dev.ultimatecombat.core.util.WorldRules;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityKnockbackByEntityEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;

/**
 * Custom knockback (knockback.yml): classic melee knockback and projectile knockback.
 */
public final class KnockbackModule extends Module<KnockbackModule.Settings> {

    /**
     * knockback.yml snapshot.
     */
    public record Settings(boolean meleeEnabled, boolean onlyPlayers, double horizontal, double vertical, double verticalLimit,
                           double extraHorizontal, double extraVertical, double friction, boolean netheriteResistance,
                           ProjectileKb snowball, ProjectileKb egg, ProjectileKb pearl, ProjectileKb rod, boolean rodPreventPull,
                           WorldRules worlds) {
    }

    /**
     * Knockback of one projectile type.
     */
    public record ProjectileKb(boolean enabled, double horizontal, double vertical, double damage, boolean hurtEffect) {
        static ProjectileKb read(ConfigReader r, boolean def) {
            return new ProjectileKb(r.bool("enabled", def), r.decimal("horizontal", 0.4, 0, 5), r.decimal("vertical", 0.3, 0, 5),
                    r.decimal("damage", 0.0, 0, 100), r.bool("hurt-effect", true));
        }
    }

    public KnockbackModule(UltimateCombatPlugin plugin) {
        super(plugin, "knockback", "knockback.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        ConfigReader m = r.section("melee");
        ConfigReader p = r.section("projectiles");
        return new Settings(
                m.bool("enabled", true),
                m.bool("only-players", true),
                m.decimal("horizontal", 0.4, 0, 5),
                m.decimal("vertical", 0.4, 0, 5),
                m.decimal("vertical-limit", 0.4, 0, 5),
                m.decimal("extra-horizontal", 0.5, 0, 5),
                m.decimal("extra-vertical", 0.1, 0, 5),
                m.decimal("friction", 2.0, 1, 10),
                m.bool("knockback-resistance", true),
                ProjectileKb.read(p.section("snowball"), true),
                ProjectileKb.read(p.section("egg"), true),
                ProjectileKb.read(p.section("ender-pearl"), false),
                ProjectileKb.read(p.section("fishing-rod"), true),
                p.bool("fishing-rod.prevent-pulling-players", false),
                WorldRules.read(r, "worlds"));
    }

    // ---------------------------------------------------------------------------- melee

    // The Bukkit knockback event is the only knockback event on Spigot; Paper still fires it.
    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackByEntityEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(event.getSourceEntity() instanceof Player attacker)) {
            return;
        }
        Settings s = settings(victim.getWorld());
        if (!s.meleeEnabled() || !s.worlds().allows(victim.getWorld()) || s.onlyPlayers() && !(victim instanceof Player)) {
            return;
        }
        EntityKnockbackEvent.KnockbackCause cause = event.getCause();
        if (cause == EntityKnockbackEvent.KnockbackCause.ENTITY_ATTACK) {
            // Sprint / enchantment bonus: already included in the classic calculation below.
            event.setCancelled(true);
            return;
        }
        if (cause != EntityKnockbackEvent.KnockbackCause.DAMAGE) {
            return;
        }
        event.setFinalKnockback(classic(victim, attacker, s));
    }

    private Vector classic(LivingEntity victim, Player attacker, Settings s) {
        Vector velocity = victim.getVelocity();
        Location v = victim.getLocation();
        Location a = attacker.getLocation();
        double dx = a.getX() - v.getX();
        double dz = a.getZ() - v.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-4) {
            dx = (Math.random() - Math.random()) * 0.01;
            dz = (Math.random() - Math.random()) * 0.01;
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        double resistance = 0;
        if (s.netheriteResistance()) {
            var attr = Registries.attribute("knockback_resistance", "generic.knockback_resistance");
            var inst = attr == null ? null : victim.getAttribute(attr);
            resistance = inst == null ? 0 : Math.max(0, Math.min(1, inst.getValue()));
        }
        double strength = s.horizontal() * (1 - resistance);
        double x = velocity.getX() / s.friction() - dx / dist * strength;
        double y = Math.min(s.verticalLimit(), velocity.getY() / s.friction() + s.vertical() * (1 - resistance));
        double z = velocity.getZ() / s.friction() - dz / dist * strength;
        int bonus = attacker.isSprinting() ? 1 : 0;
        Enchantment kb = Registries.enchantment("knockback");
        if (kb != null) {
            bonus += attacker.getInventory().getItemInMainHand().getEnchantmentLevel(kb);
        }
        if (bonus > 0) {
            double yaw = Math.toRadians(a.getYaw());
            x += -Math.sin(yaw) * bonus * s.extraHorizontal() * (1 - resistance);
            z += Math.cos(yaw) * bonus * s.extraHorizontal() * (1 - resistance);
            y += s.extraVertical();
        }
        return new Vector(x, y, z);
    }

    // ---------------------------------------------------------------------------- projectiles

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity hit = event.getHitEntity();
        if (!(hit instanceof Player victim)) {
            return;
        }
        org.bukkit.entity.Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter) || shooter == victim) {
            return;
        }
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld())) {
            return;
        }
        ProjectileKb cfg = projectile instanceof Snowball ? s.snowball() : projectile instanceof Egg ? s.egg()
                : projectile instanceof EnderPearl ? s.pearl() : projectile instanceof FishHook ? s.rod() : null;
        if (cfg == null || !cfg.enabled()) {
            return;
        }
        if (!plugin.integrations().canAttack(shooter, victim)) {
            return;
        }
        Vector dir = projectile.getVelocity().setY(0);
        if (dir.lengthSquared() < 1.0E-6) {
            dir = victim.getLocation().toVector().subtract(shooter.getLocation().toVector()).setY(0);
        }
        if (dir.lengthSquared() < 1.0E-6) {
            return;
        }
        Vector push = dir.normalize().multiply(cfg.horizontal()).setY(cfg.vertical());
        plugin.scheduler().executeEntity(victim, () -> {
            if (cfg.damage() > 0) {
                victim.damage(cfg.damage(), shooter);
            }
            victim.setVelocity(victim.getVelocity().multiply(0.5).add(push));
            if (cfg.hurtEffect()) {
                try {
                    victim.playHurtAnimation(0f);
                } catch (NoSuchMethodError ignored) {
                    // Spigot: no hurt animation API
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || !(event.getCaught() instanceof Player)) {
            return;
        }
        Settings s = settings(event.getPlayer().getWorld());
        if (s.rodPreventPull() && s.worlds().allows(event.getPlayer().getWorld())) {
            event.setCancelled(true);
            event.getHook().remove();
        }
    }
}
