package dev.ultimatecombat.modules.bounties;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.CombatType;
import dev.ultimatecombat.api.TagCause;
import dev.ultimatecombat.api.event.BountyEvent;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.command.UCCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.gui.Menu;
import dev.ultimatecombat.core.gui.MenuTemplate;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.WorldRules;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.rewards.RewardsModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Player bounties paid with Vault money (bounties.yml).
 */
public final class BountyModule extends Module<BountyModule.Settings> implements KillHandler {

    public record Settings(double minAmount, double maxAmount, double maxTotalPerTarget, double taxPercent, double taxFixed,
                           boolean allowAnonymous, double anonymousFeePercent, boolean allowMultiple, boolean allowSelf,
                           boolean setterCanClaim, long placeCooldownMillis, int maxActivePerSetter, boolean expiryEnabled,
                           long expiryMillis, boolean refundOnExpiry, double expiryFeePercent, double claimPercent,
                           boolean announcePlace, boolean announceClaim, boolean announceExpire, boolean glow, boolean particles,
                           String particlePreset, boolean tagTargetOnPlace, boolean compassEnabled, double compassCost,
                           long compassUpdateTicks, boolean compassSameWorldOnly, String placeSound, String claimSound,
                           WorldRules worlds) {
    }

    private final Map<UUID, List<Bounty>> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> placeCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> trackers = new ConcurrentHashMap<>();
    private final Set<UUID> glowing = ConcurrentHashMap.newKeySet();
    private BountyRepository repository;
    private NamespacedKey compassKey;
    private ScheduledTask expiryTask;
    private ScheduledTask visualTask;
    private ScheduledTask compassTask;
    private boolean registeredHandler;

    public BountyModule(UltimateCombatPlugin plugin) {
        super(plugin, "bounties", "bounties.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.decimal("amount.minimum", 100, 0, Double.MAX_VALUE),
                r.decimal("amount.maximum", 1_000_000, 0, Double.MAX_VALUE),
                r.decimal("amount.maximum-total-per-target", 0, 0, Double.MAX_VALUE),
                r.decimal("tax.percent", 5, 0, 100),
                r.decimal("tax.fixed", 0, 0, Double.MAX_VALUE),
                r.bool("anonymous.enabled", true),
                r.decimal("anonymous.extra-fee-percent", 10, 0, 1000),
                r.bool("allow-multiple", true),
                r.bool("allow-self", false),
                r.bool("setter-can-claim", false),
                r.durationMillis("place-cooldown-seconds", 60_000, ConfigReader.TimeUnitHint.SECONDS, 0, 86_400_000),
                r.integer("max-active-per-setter", 5, 0, 10_000),
                r.bool("expiry.enabled", true),
                r.durationMillis("expiry.days", 7L * 86_400_000L, ConfigReader.TimeUnitHint.DAYS, 60_000, 365L * 86_400_000L),
                r.bool("expiry.refund", true),
                r.decimal("expiry.fee-percent", 10, 0, 100),
                r.decimal("claim.payout-percent", 100, 0, 100),
                r.bool("announce.placed", true),
                r.bool("announce.claimed", true),
                r.bool("announce.expired", false),
                r.bool("target.glow", false),
                r.bool("target.particles.enabled", true),
                r.string("target.particles.preset", "bounty-target"),
                r.bool("target.tag-on-place", false),
                r.bool("tracking-compass.enabled", true),
                r.decimal("tracking-compass.cost", 0, 0, Double.MAX_VALUE),
                r.ticks("tracking-compass.update-interval-ticks", 40, 5, 1200),
                r.bool("tracking-compass.same-world-only", true),
                r.string("sounds.placed", "bounty-placed"),
                r.string("sounds.claimed", "bounty-claimed"),
                WorldRules.read(r, "worlds"));
    }

    @Override
    protected void onEnable() {
        compassKey = new NamespacedKey(plugin, "bounty_compass");
        if (!registeredHandler) {
            plugin.kills().register(this);
            plugin.sync().on("bounty", args -> {
                if (args.length > 0 && isEnabled()) {
                    reload(UUID.fromString(args[0]));
                }
            });
            registeredHandler = true;
        }
        if (!plugin.storageReady()) {
            plugin.getLogger().warning("bounties.yml: storage unavailable - bounties are disabled until the database works.");
            return;
        }
        repository = new BountyRepository(plugin.database());
        plugin.database().supply(c -> repository.loadActive(c, null)).thenAccept(list -> {
            active.clear();
            for (Bounty b : list) {
                active.computeIfAbsent(b.target(), k -> new CopyOnWriteArrayList<>()).add(b);
            }
            plugin.scheduler().runGlobal(this::refreshGlow);
        });
        expiryTask = plugin.scheduler().runGlobalTimer(this::expire, 20L * 30, 20L * 60);
        visualTask = plugin.scheduler().runGlobalTimer(this::visuals, 40L, 40L);
        compassTask = plugin.scheduler().runGlobalTimer(this::updateCompasses, 40L, settings().compassUpdateTicks());
    }

    @Override
    protected void onDisable() {
        for (ScheduledTask t : new ScheduledTask[]{expiryTask, visualTask, compassTask}) {
            if (t != null) {
                t.cancel();
            }
        }
        for (UUID id : glowing) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.scheduler().executeEntity(p, () -> p.setGlowing(false));
            }
        }
        glowing.clear();
        trackers.clear();
    }

    @Override
    public boolean activeHandler() {
        return isEnabled() && repository != null;
    }

    @Override
    public int killPriority() {
        return ECONOMY + 5;
    }

    // ------------------------------------------------------------------------------ queries

    public List<Bounty> bounties(UUID target) {
        List<Bounty> list = active.get(target);
        return list == null ? List.of() : List.copyOf(list);
    }

    public double total(UUID target) {
        double sum = 0;
        for (Bounty b : bounties(target)) {
            sum += b.amount();
        }
        return sum;
    }

    /**
     * @return name of the most recent non-anonymous setter, or {@code null}
     */
    public String initiator(UUID target) {
        Bounty latest = null;
        for (Bounty b : bounties(target)) {
            if (!b.anonymous() && (latest == null || b.createdAt() > latest.createdAt())) {
                latest = b;
            }
        }
        return latest == null ? null : latest.setterName();
    }

    private void reload(UUID target) {
        if (repository == null) {
            return;
        }
        plugin.database().supply(c -> repository.loadActive(c, target)).thenAccept(list -> {
            if (list.isEmpty()) {
                active.remove(target);
            } else {
                active.put(target, new CopyOnWriteArrayList<>(list));
            }
            plugin.scheduler().runGlobal(this::refreshGlow);
        });
    }

    private void removeLocal(Bounty b) {
        List<Bounty> list = active.get(b.target());
        if (list != null) {
            list.removeIf(x -> x.id() == b.id());
            if (list.isEmpty()) {
                active.remove(b.target());
            }
        }
    }

    // ------------------------------------------------------------------------------ placing

    @SuppressWarnings("deprecation")
    private void place(CommandContext ctx) {
        Player setter = ctx.player();
        if (ctx.size() < 3) {
            ctx.reply("general.usage", "usage", "/bounty set <player> <amount> [anonymous]");
            return;
        }
        if (repository == null || !plugin.economy().available()) {
            ctx.reply("bounties.unavailable");
            return;
        }
        Settings s = settings(setter.getWorld());
        Player onlineTarget = Bukkit.getPlayerExact(ctx.arg(1));
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(ctx.arg(1));
        if (onlineTarget == null && !target.hasPlayedBefore()) {
            ctx.reply("general.player-not-found", "player", ctx.arg(1));
            return;
        }
        if (target.getUniqueId().equals(setter.getUniqueId()) && !s.allowSelf()) {
            ctx.reply("bounties.no-self");
            return;
        }
        if (onlineTarget != null && plugin.bypasses(onlineTarget, "bounty")) {
            ctx.reply("bounties.target-immune", "player", onlineTarget.getName());
            return;
        }
        Double amount = ctx.doubleArg(2, s.minAmount(), s.maxAmount());
        if (amount == null) {
            return;
        }
        amount = Numbers.round(amount, 2);
        boolean anonymous = ctx.size() > 3 && ctx.arg(3).equalsIgnoreCase("anonymous");
        if (anonymous && (!s.allowAnonymous() || !setter.hasPermission("ultimatecombat.bounty.anonymous"))) {
            ctx.reply("bounties.no-anonymous");
            return;
        }
        List<Bounty> existing = bounties(target.getUniqueId());
        if (!s.allowMultiple() && !existing.isEmpty()) {
            ctx.reply("bounties.already-has", "player", target.getName());
            return;
        }
        if (s.maxTotalPerTarget() > 0 && total(target.getUniqueId()) + amount > s.maxTotalPerTarget()) {
            ctx.reply("bounties.total-limit", "max", plugin.economy().format(s.maxTotalPerTarget()));
            return;
        }
        long mine = active.values().stream().flatMap(List::stream).filter(b -> setter.getUniqueId().equals(b.setter())).count();
        if (s.maxActivePerSetter() > 0 && mine >= s.maxActivePerSetter()) {
            ctx.reply("bounties.setter-limit", "max", s.maxActivePerSetter());
            return;
        }
        Long lastPlace = placeCooldowns.get(setter.getUniqueId());
        if (lastPlace != null && System.currentTimeMillis() - lastPlace < s.placeCooldownMillis() && !plugin.bypasses(setter, "bounty-cooldown")) {
            ctx.reply("bounties.cooldown", "time", plugin.messages().duration(s.placeCooldownMillis() - (System.currentTimeMillis() - lastPlace)));
            return;
        }
        double tax = Numbers.round(amount * s.taxPercent() / 100.0 + s.taxFixed(), 2);
        double anonFee = anonymous ? Numbers.round(amount * s.anonymousFeePercent() / 100.0, 2) : 0;
        double cost = amount + tax + anonFee;
        BountyEvent event = new BountyEvent(BountyEvent.Action.PLACE, target, setter, amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        if (plugin.economy().balance(setter) < cost || !plugin.economy().withdraw(setter, cost, "bounty placement")) {
            ctx.reply("bounties.not-enough-money", "cost", plugin.economy().format(cost));
            return;
        }
        placeCooldowns.put(setter.getUniqueId(), System.currentTimeMillis());
        long now = System.currentTimeMillis();
        long expires = s.expiryEnabled() ? now + s.expiryMillis() : 0;
        String targetName = target.getName() == null ? ctx.arg(1) : target.getName();
        Bounty pendingBounty = new Bounty(-1, target.getUniqueId(), targetName, setter.getUniqueId(), setter.getName(), amount, tax + anonFee,
                anonymous, now, expires);
        double finalAmount = amount;
        plugin.database().supply(c -> repository.insert(c, pendingBounty)).whenComplete((id, error) -> {
            if (error != null || id == null || id < 0) {
                plugin.scheduler().runGlobal(() -> plugin.economy().deposit(setter, cost, "bounty placement refund"));
                ctx.reply("bounties.failed");
                return;
            }
            Bounty b = new Bounty(id, pendingBounty.target(), pendingBounty.targetName(), pendingBounty.setter(), pendingBounty.setterName(),
                    pendingBounty.amount(), pendingBounty.tax(), pendingBounty.anonymous(), pendingBounty.createdAt(), pendingBounty.expiresAt());
            active.computeIfAbsent(b.target(), k -> new CopyOnWriteArrayList<>()).add(b);
            plugin.sync().publish("bounty", b.target().toString());
            plugin.scheduler().runGlobal(() -> {
                ctx.reply("bounties.placed", "player", targetName, "amount", plugin.economy().format(finalAmount),
                        "cost", plugin.economy().format(cost));
                if (s.announcePlace()) {
                    plugin.messages().broadcast(anonymous ? "bounties.announce-placed-anonymous" : "bounties.announce-placed",
                            "player", targetName, "setter", setter.getName(), "amount", plugin.economy().format(finalAmount),
                            "total", plugin.economy().format(total(b.target())));
                }
                Player t = Bukkit.getPlayer(b.target());
                if (t != null) {
                    plugin.effects().playSound(t, s.placeSound());
                    if (s.tagTargetOnPlace()) {
                        CombatModule combat = plugin.active(CombatModule.class);
                        if (combat != null) {
                            plugin.scheduler().executeEntity(t, () -> combat.tag(t, null, CombatType.PVP, TagCause.BOUNTY, 0));
                        }
                    }
                }
                refreshGlow();
            });
        });
    }

    // ------------------------------------------------------------------------------ claiming

    @Override
    public void onPlayerDeath(KillContext ctx) {
        if (!ctx.isPvp() || ctx.rewardsSuppressed() || repository == null) {
            return;
        }
        Player victim = ctx.victim();
        Player killer = ctx.killer();
        Settings s = settings(victim.getWorld());
        if (!s.worlds().allows(victim.getWorld())) {
            return;
        }
        List<Bounty> claimable = new ArrayList<>();
        for (Bounty b : bounties(victim.getUniqueId())) {
            if (!s.setterCanClaim() && killer.getUniqueId().equals(b.setter())) {
                continue;
            }
            claimable.add(b);
        }
        if (claimable.isEmpty()) {
            return;
        }
        UUID killerId = killer.getUniqueId();
        plugin.database().supply(c -> {
            double sum = 0;
            List<Bounty> claimed = new ArrayList<>();
            for (Bounty b : claimable) {
                if (repository.finish(c, b.id(), "CLAIMED", killerId)) {
                    sum += b.amount();
                    claimed.add(b);
                }
            }
            return new Object[]{sum, claimed};
        }).thenAccept(result -> {
            double sum = (double) result[0];
            @SuppressWarnings("unchecked")
            List<Bounty> claimed = (List<Bounty>) result[1];
            claimed.forEach(this::removeLocal);
            if (claimed.isEmpty()) {
                return;
            }
            plugin.sync().publish("bounty", victim.getUniqueId().toString());
            double payout = Numbers.round(sum * s.claimPercent() / 100.0, 2);
            plugin.scheduler().runEntity(killer, () -> {
                if (payout > 0) {
                    plugin.economy().deposit(killer, payout, "bounty claim");
                }
                plugin.players().profile(killer).stats().addBountyClaim(payout);
                plugin.messages().send(killer, "bounties.claimed", "player", victim.getName(), "amount", plugin.economy().format(payout));
                plugin.effects().playSound(killer, s.claimSound());
                Bukkit.getPluginManager().callEvent(new BountyEvent(BountyEvent.Action.CLAIM, victim, killer, payout));
                RewardsModule rewards = plugin.active(RewardsModule.class);
                if (rewards != null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("victim", victim.getName());
                    ph.put("bounty", plugin.economy().format(payout));
                    rewards.trigger(RewardsModule.Trigger.BOUNTY_CLAIM, killer, ph);
                }
            });
            if (s.announceClaim()) {
                plugin.messages().broadcast("bounties.announce-claimed", "player", victim.getName(), "killer", killer.getName(),
                        "amount", plugin.economy().format(payout));
            }
            plugin.scheduler().runGlobal(this::refreshGlow);
        });
    }

    // ------------------------------------------------------------------------------ expiry / removal

    private void expire() {
        if (repository == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Bounty> due = new ArrayList<>();
        for (List<Bounty> list : active.values()) {
            for (Bounty b : list) {
                if (b.expiresAt() > 0 && b.expiresAt() <= now) {
                    due.add(b);
                }
            }
        }
        if (!due.isEmpty()) {
            finishAll(due, "EXPIRED", settings().refundOnExpiry(), settings().expiryFeePercent());
        }
    }

    /**
     * Ends bounties (expiry or admin removal) and refunds setters exactly once.
     */
    private void finishAll(List<Bounty> list, String state, boolean refund, double feePercent) {
        plugin.database().supply(c -> {
            List<Bounty> done = new ArrayList<>();
            for (Bounty b : list) {
                if (repository.finish(c, b.id(), state, null)) {
                    done.add(b);
                }
            }
            return done;
        }).thenAccept(done -> {
            for (Bounty b : done) {
                removeLocal(b);
                plugin.sync().publish("bounty", b.target().toString());
                double back = refund ? Numbers.round(b.amount() * (100 - feePercent) / 100.0, 2) : 0;
                plugin.scheduler().runGlobal(() -> {
                    if (back > 0 && b.setter() != null) {
                        OfflinePlayer setter = Bukkit.getOfflinePlayer(b.setter());
                        plugin.economy().deposit(setter, back, "bounty refund");
                        Player online = setter.getPlayer();
                        if (online != null) {
                            plugin.messages().send(online, state.equals("EXPIRED") ? "bounties.expired-refund" : "bounties.removed-refund",
                                    "player", b.targetName(), "amount", plugin.economy().format(back));
                        }
                    }
                    if (state.equals("EXPIRED") && settings().announceExpire()) {
                        plugin.messages().broadcast("bounties.announce-expired", "player", b.targetName(),
                                "amount", plugin.economy().format(b.amount()));
                    }
                    BountyEvent.Action action = state.equals("EXPIRED") ? BountyEvent.Action.EXPIRE : BountyEvent.Action.REMOVE;
                    Bukkit.getPluginManager().callEvent(new BountyEvent(action, Bukkit.getOfflinePlayer(b.target()), null, b.amount()));
                });
            }
            plugin.scheduler().runGlobal(this::refreshGlow);
        });
    }

    // ------------------------------------------------------------------------------ visuals

    private void refreshGlow() {
        boolean glow = isEnabled() && settings().glow();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean should = glow && !bounties(p.getUniqueId()).isEmpty();
            if (should && glowing.add(p.getUniqueId())) {
                plugin.scheduler().executeEntity(p, () -> p.setGlowing(true));
            } else if (!should && glowing.remove(p.getUniqueId())) {
                plugin.scheduler().executeEntity(p, () -> p.setGlowing(false));
            }
        }
    }

    private void visuals() {
        Settings s = settings();
        if (!s.particles()) {
            return;
        }
        for (UUID target : active.keySet()) {
            Player p = Bukkit.getPlayer(target);
            if (p != null && !p.isDead() && s.worlds().allows(p.getWorld())) {
                plugin.scheduler().executeEntity(p, () -> plugin.effects().spawnParticle(p.getLocation(), s.particlePreset()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.scheduler().runGlobalLater(this::refreshGlow, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        glowing.remove(event.getPlayer().getUniqueId());
        trackers.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------ tracking compass

    private void track(CommandContext ctx) {
        Player hunter = ctx.player();
        Settings s = settings(hunter.getWorld());
        if (!s.compassEnabled()) {
            ctx.reply("bounties.compass-disabled");
            return;
        }
        Player target = ctx.size() > 1 ? Bukkit.getPlayerExact(ctx.arg(1)) : null;
        if (target == null || bounties(target.getUniqueId()).isEmpty()) {
            ctx.reply("bounties.track-no-target", "player", ctx.argOr(1, "?"));
            return;
        }
        if (s.compassCost() > 0) {
            if (!plugin.economy().available() || !plugin.economy().withdraw(hunter, s.compassCost(), "bounty compass")) {
                ctx.reply("bounties.not-enough-money", "cost", plugin.economy().format(s.compassCost()));
                return;
            }
        }
        ItemStack compass = new ItemBuilder(Material.COMPASS)
                .name(plugin.messages().text(hunter, "bounties.compass-name", "player", target.getName()))
                .lore(plugin.messages().lines(hunter, "bounties.compass-lore", "player", target.getName()))
                .edit(meta -> meta.getPersistentDataContainer().set(compassKey, PersistentDataType.STRING, target.getUniqueId().toString()))
                .build();
        for (ItemStack rest : hunter.getInventory().addItem(compass).values()) {
            hunter.getWorld().dropItemNaturally(hunter.getLocation(), rest);
        }
        trackers.put(hunter.getUniqueId(), target.getUniqueId());
        ctx.reply("bounties.tracking", "player", target.getName());
    }

    private void updateCompasses() {
        for (Map.Entry<UUID, UUID> e : trackers.entrySet()) {
            Player hunter = Bukkit.getPlayer(e.getKey());
            Player target = Bukkit.getPlayer(e.getValue());
            if (hunter == null) {
                trackers.remove(e.getKey());
                continue;
            }
            if (target == null || bounties(target.getUniqueId()).isEmpty()) {
                continue;
            }
            plugin.scheduler().executeEntity(target, () -> {
                Location where = target.getLocation();
                plugin.scheduler().executeEntity(hunter, () -> pointCompasses(hunter, target.getUniqueId(), where));
            });
        }
    }

    private void pointCompasses(Player hunter, UUID target, Location where) {
        Settings s = settings(hunter.getWorld());
        if (s.compassSameWorldOnly() && !hunter.getWorld().equals(where.getWorld())) {
            return;
        }
        ItemStack[] contents = hunter.getInventory().getContents();
        boolean any = false;
        for (ItemStack item : contents) {
            if (item == null || item.getType() != Material.COMPASS || !(item.getItemMeta() instanceof CompassMeta meta)) {
                continue;
            }
            String id = meta.getPersistentDataContainer().get(compassKey, PersistentDataType.STRING);
            if (id == null || !id.equals(target.toString())) {
                continue;
            }
            meta.setLodestone(where);
            meta.setLodestoneTracked(false);
            item.setItemMeta(meta);
            any = true;
        }
        if (!any) {
            trackers.remove(hunter.getUniqueId());
        }
    }

    // ------------------------------------------------------------------------------ commands & GUI

    @Override
    public void registerCommands() {
        SimpleCommand bounty = new SimpleCommand("bounty", "bounty [set|list|top|info|track|remove]", "ultimatecombat.bounty", false,
                this::command, ctx -> switch (ctx.size()) {
                    case 1 -> List.of("set", "list", "top", "info", "track", "remove");
                    case 2 -> UCCommand.onlineNames();
                    case 3 -> List.of("100", "1000", "10000");
                    case 4 -> List.of("anonymous");
                    default -> List.of();
                }, "bounties");
        plugin.commands().root().child(bounty);
        plugin.commands().standalone("bounty", bounty);
    }

    private void command(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String sub = ctx.argOr(0, "").toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (ctx.isPlayer()) {
                    openMenu(ctx.player(), 0);
                } else {
                    list(ctx);
                }
            }
            case "set" -> {
                if (!ctx.isPlayer()) {
                    ctx.reply("general.player-only");
                } else if (!ctx.has("ultimatecombat.bounty.set")) {
                    ctx.reply("general.no-permission");
                } else {
                    place(ctx);
                }
            }
            case "list" -> list(ctx);
            case "top" -> top(ctx);
            case "info" -> info(ctx);
            case "track" -> {
                if (!ctx.isPlayer()) {
                    ctx.reply("general.player-only");
                } else if (!ctx.has("ultimatecombat.bounty.track")) {
                    ctx.reply("general.no-permission");
                } else {
                    track(ctx);
                }
            }
            case "remove" -> remove(ctx);
            default -> ctx.reply("general.usage", "usage", "/bounty [set|list|top|info|track|remove]");
        }
    }

    private List<Map.Entry<UUID, Double>> sortedTotals() {
        List<Map.Entry<UUID, Double>> totals = new ArrayList<>();
        for (UUID target : active.keySet()) {
            totals.add(Map.entry(target, total(target)));
        }
        totals.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
        return totals;
    }

    private String nameOf(UUID target) {
        List<Bounty> list = bounties(target);
        return list.isEmpty() ? target.toString().substring(0, 8) : list.get(0).targetName();
    }

    private void list(CommandContext ctx) {
        List<Map.Entry<UUID, Double>> totals = sortedTotals();
        ctx.reply("bounties.list-header", "count", totals.size());
        for (Map.Entry<UUID, Double> e : totals) {
            ctx.reply("bounties.list-entry", "player", nameOf(e.getKey()), "amount", plugin.economy().format(e.getValue()),
                    "count", bounties(e.getKey()).size());
        }
    }

    private void top(CommandContext ctx) {
        List<Map.Entry<UUID, Double>> totals = sortedTotals();
        ctx.reply("bounties.top-header");
        int rank = 1;
        for (Map.Entry<UUID, Double> e : totals) {
            if (rank > 10) {
                break;
            }
            ctx.reply("bounties.top-entry", "rank", rank++, "player", nameOf(e.getKey()), "amount", plugin.economy().format(e.getValue()));
        }
    }

    @SuppressWarnings("deprecation")
    private void info(CommandContext ctx) {
        if (ctx.size() < 2) {
            ctx.reply("general.usage", "usage", "/bounty info <player>");
            return;
        }
        Player online = Bukkit.getPlayerExact(ctx.arg(1));
        UUID target = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(ctx.arg(1)).getUniqueId();
        List<Bounty> list = bounties(target);
        if (list.isEmpty()) {
            ctx.reply("bounties.none", "player", ctx.arg(1));
            return;
        }
        ctx.reply("bounties.info-header", "player", ctx.arg(1), "total", plugin.economy().format(total(target)));
        for (Bounty b : list) {
            ctx.reply("bounties.info-entry", "setter", b.anonymous() ? plugin.messages().text("bounties.anonymous-name") : b.setterName(),
                    "amount", plugin.economy().format(b.amount()),
                    "expires", b.expiresAt() == 0 ? "-" : plugin.messages().duration(b.expiresAt() - System.currentTimeMillis()));
        }
    }

    @SuppressWarnings("deprecation")
    private void remove(CommandContext ctx) {
        if (!ctx.has("ultimatecombat.bounty.admin")) {
            ctx.reply("general.no-permission");
            return;
        }
        if (ctx.size() < 2) {
            ctx.reply("general.usage", "usage", "/bounty remove <player>");
            return;
        }
        Player online = Bukkit.getPlayerExact(ctx.arg(1));
        UUID target = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(ctx.arg(1)).getUniqueId();
        List<Bounty> list = bounties(target);
        if (list.isEmpty()) {
            ctx.reply("bounties.none", "player", ctx.arg(1));
            return;
        }
        finishAll(list, "REMOVED", true, 0);
        ctx.reply("bounties.removed", "player", ctx.arg(1), "count", list.size());
    }

    private void openMenu(Player viewer, int page) {
        MenuTemplate template = plugin.menus().template("bounties");
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> plugin.scheduler().runEntity(p, p::closeInventory));
        handlers.put("top", (p, c) -> {
            p.closeInventory();
            p.performCommand("bounty top");
        });
        Menu menu = plugin.menus().build(viewer, "bounties", handlers, "count", active.size());
        List<Map.Entry<UUID, Double>> totals = sortedTotals();
        plugin.menus().paginate(menu, viewer, template, totals, page, e -> {
            String initiator = initiator(e.getKey());
            return new ItemBuilder(Material.PLAYER_HEAD)
                    .skull(Bukkit.getOfflinePlayer(e.getKey()))
                    .name(plugin.messages().text(viewer, "bounties.menu-item-name", "player", nameOf(e.getKey())))
                    .lore(plugin.messages().lines(viewer, "bounties.menu-item-lore", "amount", plugin.economy().format(e.getValue()),
                            "count", bounties(e.getKey()).size(),
                            "initiator", initiator == null ? plugin.messages().text("bounties.anonymous-name") : initiator))
                    .build();
        }, (e, click) -> {
            if (click == ClickType.LEFT && settings().compassEnabled()) {
                viewer.closeInventory();
                viewer.performCommand("bounty track " + nameOf(e.getKey()));
            } else {
                viewer.closeInventory();
                viewer.performCommand("bounty info " + nameOf(e.getKey()));
            }
        }, p -> openMenu(viewer, p));
        menu.open(viewer);
    }
}
