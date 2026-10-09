package dev.ultimatecombat.modules.combat;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.TagCause;
import dev.ultimatecombat.api.UntagReason;
import dev.ultimatecombat.core.integration.IntegrationManager;
import org.bukkit.entity.Animals;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Boss;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Turns damage into combat tags. Runs at MONITOR priority and ignores cancelled events, so damage
 * that another plugin (or UltimateCombat's own protections) cancelled never tags anybody.
 */
final class CombatListener implements Listener {

    private static final Set<String> HARMFUL_EFFECTS = Set.of("poison", "wither", "slowness", "weakness", "instant_damage",
            "blindness", "hunger", "nausea", "mining_fatigue", "levitation", "darkness", "bad_omen", "unluck", "infested",
            "oozing", "weaving", "wind_charged");

    private final UltimateCombatPlugin plugin;
    private final CombatModule module;

    CombatListener(UltimateCombatPlugin plugin, CombatModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private enum SourceKind { MELEE, PROJECTILE, POTION, EXPLOSION, FIRE_LAVA, TAMED, OTHER }

    private static SourceKind kind(Entity damager, Player attacker) {
        if (damager == attacker) {
            return SourceKind.MELEE;
        }
        if (damager instanceof ThrownPotion || damager instanceof AreaEffectCloud) {
            return SourceKind.POTION;
        }
        if (damager instanceof TNTPrimed || damager instanceof EnderCrystal || damager instanceof Explosive) {
            return SourceKind.EXPLOSION;
        }
        if (damager instanceof Projectile) {
            return SourceKind.PROJECTILE;
        }
        if (damager instanceof Tameable) {
            return SourceKind.TAMED;
        }
        return SourceKind.OTHER;
    }

    private static boolean allowed(CombatSettings.Pvp pvp, SourceKind kind) {
        return switch (kind) {
            case MELEE -> pvp.melee();
            case PROJECTILE -> pvp.projectiles();
            case POTION -> pvp.potions();
            case EXPLOSION -> pvp.explosions();
            case FIRE_LAVA -> pvp.fireAndLava();
            case TAMED -> pvp.tamedAnimals();
            case OTHER -> pvp.other();
        };
    }

    // ------------------------------------------------------------------------ third-party rules

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInterference(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        if (attacker == null || attacker == victim) {
            return;
        }
        CombatSettings.ThirdParty tp = module.settings(victim.getWorld()).thirdParty();
        CombatTag victimTag = module.tag(victim);
        if (victimTag == null || victimTag.type() != CombatType.PVP || victimTag.opponents().isEmpty()) {
            return;
        }
        if (victimTag.opponents().contains(attacker.getUniqueId())) {
            return; // the attacker is already part of this fight
        }
        if (tp.blockInterference()) {
            event.setCancelled(true);
            plugin.messages().send(attacker, "combat.third-party-blocked", "player", victim.getName());
            return;
        }
        if (tp.antiKillSteal()) {
            double max = maxHealth(victim);
            boolean low = max <= 0 || victim.getHealth() / max <= tp.antiKillStealHealthPercent();
            long cutoff = System.currentTimeMillis() - tp.antiKillStealMillis();
            boolean recentFight = victimTag.opponentHits().values().stream().anyMatch(t -> t >= cutoff);
            if (low && recentFight) {
                event.setCancelled(true);
                plugin.messages().send(attacker, "combat.kill-steal-blocked", "player", victim.getName());
            }
        }
    }

    private static double maxHealth(Player p) {
        var attr = dev.ultimatecombat.core.util.Registries.maxHealth();
        if (attr == null) {
            return 20.0;
        }
        var inst = p.getAttribute(attr);
        return inst == null ? 20.0 : inst.getValue();
    }

    // ------------------------------------------------------------------------ tagging

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating()) {
            return;
        }
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();
        Player attacker = plugin.kills().attribution().resolve(damager);

        if (victim instanceof Player victimPlayer && attacker != null && attacker != victimPlayer) {
            handlePvp(attacker, victimPlayer, kind(damager, attacker), event.getFinalDamage());
            return;
        }
        if (attacker != null && victim instanceof LivingEntity living && !(victim instanceof Player)) {
            handlePlayerHitsMob(attacker, living, event.getFinalDamage());
            return;
        }
        if (victim instanceof Player victimPlayer && attacker == null) {
            LivingEntity mob = mobOf(damager);
            if (mob != null && !(mob instanceof Player)) {
                handleMobHitsPlayer(victimPlayer, mob, event.getFinalDamage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIndirectDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent || IntegrationManager.isSimulating()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        SourceKind kind = switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA -> SourceKind.FIRE_LAVA;
            case BLOCK_EXPLOSION -> SourceKind.EXPLOSION;
            case POISON, WITHER -> SourceKind.POTION;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        Player attacker = plugin.kills().attribution().attacker(event);
        if (attacker != null && attacker != victim) {
            handlePvp(attacker, victim, kind, event.getFinalDamage());
        }
    }

    private static LivingEntity mobOf(Entity damager) {
        if (damager instanceof LivingEntity l) {
            return l;
        }
        if (damager instanceof Projectile p) {
            ProjectileSource shooter = p.getShooter();
            if (shooter instanceof LivingEntity l) {
                return l;
            }
        }
        return null;
    }

    private void handlePvp(Player attacker, Player victim, SourceKind kind, double finalDamage) {
        CombatSettings s = module.settings(victim.getWorld());
        CombatSettings.Pvp pvp = s.pvp();
        if (!pvp.enabled() || !allowed(pvp, kind)) {
            return;
        }
        if (pvp.successfulHitOnly() && finalDamage <= 0) {
            return;
        }
        if (s.ignoreNpcs() && (attacker.hasMetadata("NPC") || victim.hasMetadata("NPC"))) {
            return;
        }
        if (pvp.excludeAllies() && plugin.integrations().areAllies(attacker, victim)) {
            return;
        }
        if (pvp.tagAttacker()) {
            plugin.scheduler().executeEntity(attacker, () -> module.tag(attacker, victim, CombatType.PVP, TagCause.ATTACKER, 0));
        }
        if (pvp.tagVictim()) {
            module.tag(victim, attacker, CombatType.PVP, TagCause.VICTIM, 0);
        }
    }

    private boolean pveEligible(CombatSettings.Pve pve, LivingEntity mob) {
        if (pve.blacklist().contains(mob.getType())) {
            return false;
        }
        String customId = plugin.integrations().customEntityId(mob);
        if (customId != null && pve.customBlacklist().contains(customId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        @SuppressWarnings("deprecation")
        String name = mob.getCustomName();
        if (name != null && !pve.nameExclusions().isEmpty()) {
            String plain = dev.ultimatecombat.core.message.Text.strip(name);
            for (var pattern : pve.nameExclusions()) {
                if (pattern.matcher(plain).find()) {
                    return false;
                }
            }
        }
        if (!pve.whitelist().isEmpty()) {
            return pve.whitelist().contains(mob.getType());
        }
        if (customId != null) {
            return pve.custom();
        }
        if (mob instanceof Boss || mob instanceof ElderGuardian || mob instanceof Warden || plugin.integrations().isCustomBoss(mob)) {
            return pve.bosses();
        }
        if (mob instanceof Enemy) {
            return pve.hostile();
        }
        if (mob instanceof Animals) {
            return pve.animals();
        }
        return pve.other();
    }

    private void handlePlayerHitsMob(Player attacker, LivingEntity mob, double finalDamage) {
        CombatSettings s = module.settings(attacker.getWorld());
        CombatSettings.Pve pve = s.pve();
        if (!pve.enabled() || !pve.tagWhenPlayerAttacks()) {
            return;
        }
        if (pve.successfulHitOnly() && finalDamage <= 0) {
            return;
        }
        if (mob.hasMetadata("NPC") || mob instanceof org.bukkit.entity.ArmorStand) {
            return;
        }
        var ghosts = plugin.active(dev.ultimatecombat.modules.ghosts.GhostModule.class);
        if (ghosts != null && ghosts.isGhost(mob)) {
            return; // hitting a ghost player is PvP, handled by the ghost module
        }
        if (!pveEligible(pve, mob)) {
            return;
        }
        plugin.scheduler().executeEntity(attacker, () -> module.tag(attacker, mob, CombatType.PVE, TagCause.ATTACKER, 0));
    }

    private void handleMobHitsPlayer(Player victim, LivingEntity mob, double finalDamage) {
        CombatSettings s = module.settings(victim.getWorld());
        CombatSettings.Pve pve = s.pve();
        if (!pve.enabled() || !pve.tagWhenMobAttacks()) {
            return;
        }
        if (pve.successfulHitOnly() && finalDamage <= 0) {
            return;
        }
        if (!pveEligible(pve, mob)) {
            return;
        }
        module.tag(victim, mob, CombatType.PVE, TagCause.VICTIM, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower)) {
            return;
        }
        if (!isHarmful(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && victim != thrower && event.getIntensity(victim) > 0) {
                handlePvp(thrower, victim, SourceKind.POTION, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower)) {
            return;
        }
        boolean harmful = false;
        try {
            harmful = isHarmful(cloud.getCustomEffects()) || cloud.getBasePotionType() != null
                    && isHarmful(cloud.getBasePotionType().getPotionEffects());
        } catch (NoSuchMethodError | RuntimeException ignored) {
            harmful = isHarmful(cloud.getCustomEffects());
        }
        if (!harmful) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && victim != thrower) {
                handlePvp(thrower, victim, SourceKind.POTION, 1);
            }
        }
    }

    private static boolean isHarmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            PotionEffectType type = effect.getType();
            if (HARMFUL_EFFECTS.contains(type.getKey().getKey())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------ untagging

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        CombatTag tag = module.tag(victim);
        if (tag != null) {
            CombatSettings s = module.settings(victim.getWorld());
            boolean untag = tag.type() == CombatType.PVP ? s.pvp().untagOnDeath() : s.pve().untagOnDeath();
            if (untag) {
                module.untag(victim, UntagReason.DEATH, true);
            }
        }
        module.opponentGone(victim.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (module.tag(id) != null) {
            module.untag(player, UntagReason.QUIT, false);
        }
        module.opponentGone(id, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!module.isTagged(player)) {
            return;
        }
        CombatSettings s = module.settings(player.getWorld());
        if (s.untagOnWorldChange() || !s.worlds().allows(player.getWorld())) {
            module.untag(player, UntagReason.WORLD_CHANGE, true);
        }
    }
}
