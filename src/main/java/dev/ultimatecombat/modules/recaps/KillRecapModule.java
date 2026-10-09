package dev.ultimatecombat.modules.recaps;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.gui.Menu;
import dev.ultimatecombat.core.gui.MenuTemplate;
import dev.ultimatecombat.core.kill.DamageTracker;
import dev.ultimatecombat.core.kill.KillContext;
import dev.ultimatecombat.core.kill.KillHandler;
import dev.ultimatecombat.core.kill.KillService;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.ItemSerializer;
import dev.ultimatecombat.core.util.Locations;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.core.util.Registries;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kill recaps (kill-recaps.yml): an interactive summary of how a fight went.
 */
public final class KillRecapModule extends Module<KillRecapModule.Settings> implements KillHandler {

    public record Settings(boolean pvpOnly, int maxPerPlayer, long maxAgeMillis, boolean persist, boolean notifyVictim,
                           boolean notifyKiller, boolean inventorySnapshots, long fightWindowMillis) {
    }

    /**
     * Everything shown in a recap. Stored as YAML text in the database.
     */
    public record Recap(String key, UUID killer, String killerName, UUID victim, String victimName, long at, double damageDealt,
                        double damageReceived, int hitsDealt, int hitsReceived, double killerHealth, List<String> assists,
                        List<String> killerPotions, List<String> victimPotions, String location, String cause, String weapon,
                        long durationMillis, String killerInventory, String victimInventory) {

        String toYaml() {
            YamlConfiguration y = new YamlConfiguration();
            y.set("key", key);
            y.set("killer", killer == null ? null : killer.toString());
            y.set("killer-name", killerName);
            y.set("victim", victim.toString());
            y.set("victim-name", victimName);
            y.set("at", at);
            y.set("damage-dealt", damageDealt);
            y.set("damage-received", damageReceived);
            y.set("hits-dealt", hitsDealt);
            y.set("hits-received", hitsReceived);
            y.set("killer-health", killerHealth);
            y.set("assists", assists);
            y.set("killer-potions", killerPotions);
            y.set("victim-potions", victimPotions);
            y.set("location", location);
            y.set("cause", cause);
            y.set("weapon", weapon);
            y.set("duration", durationMillis);
            y.set("killer-inventory", killerInventory);
            y.set("victim-inventory", victimInventory);
            return y.saveToString();
        }

        static Recap fromYaml(String text) throws InvalidConfigurationException {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(text);
            String killer = y.getString("killer");
            return new Recap(y.getString("key"), killer == null ? null : UUID.fromString(killer), y.getString("killer-name", "-"),
                    UUID.fromString(y.getString("victim")), y.getString("victim-name", "-"), y.getLong("at"), y.getDouble("damage-dealt"),
                    y.getDouble("damage-received"), y.getInt("hits-dealt"), y.getInt("hits-received"), y.getDouble("killer-health"),
                    y.getStringList("assists"), y.getStringList("killer-potions"), y.getStringList("victim-potions"),
                    y.getString("location", ""), y.getString("cause", ""), y.getString("weapon", ""), y.getLong("duration"),
                    y.getString("killer-inventory", ""), y.getString("victim-inventory", ""));
        }
    }

    private record PotionUse(String name, long at) {
    }

    private final Map<UUID, Deque<Recap>> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, Recap> byKey = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<PotionUse>> potions = new ConcurrentHashMap<>();
    private boolean registeredHandler;

    public KillRecapModule(UltimateCombatPlugin plugin) {
        super(plugin, "kill-recaps", "kill-recaps.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        return new Settings(
                r.bool("pvp-only", true),
                r.integer("retention.max-per-player", 10, 1, 1000),
                r.durationMillis("retention.max-age-hours", 24 * 3_600_000L, ConfigReader.TimeUnitHint.HOURS, 60_000, 365L * 86_400_000L),
                r.bool("retention.store-in-database", true),
                r.bool("notify.victim", true),
                r.bool("notify.killer", true),
                r.bool("inventory-snapshots", true),
                r.durationMillis("fight-window-seconds", 60_000, ConfigReader.TimeUnitHint.SECONDS, 1000, 3_600_000));
    }

    @Override
    protected void onEnable() {
        if (!registeredHandler) {
            plugin.kills().register(this);
            registeredHandler = true;
        }
    }

    @Override
    protected void onDisable() {
        potions.clear();
    }

    @Override
    public boolean activeHandler() {
        return isEnabled();
    }

    @Override
    public int killPriority() {
        return NOTIFY;
    }

    // ------------------------------------------------------------------------------ tracking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() == Material.POTION || item.getType() == Material.GOLDEN_APPLE || item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            recordPotion(event.getPlayer().getUniqueId(), potionName(item));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof ThrownPotion potion && potion.getShooter() instanceof Player p) {
            recordPotion(p.getUniqueId(), potionName(potion.getItem()));
        }
    }

    private String potionName(ItemStack item) {
        if (item.getItemMeta() instanceof PotionMeta meta && meta.getBasePotionType() != null) {
            return KillService.pretty(item.getType().name()) + " (" + Registries.key(meta.getBasePotionType()) + ")";
        }
        return KillService.pretty(item.getType().name());
    }

    private void recordPotion(UUID player, String name) {
        Deque<PotionUse> deque = potions.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new PotionUse(name, System.currentTimeMillis()));
            while (deque.size() > 32) {
                deque.removeFirst();
            }
        }
    }

    private List<String> potionsSince(UUID player, long since) {
        Deque<PotionUse> deque = potions.get(player);
        List<String> out = new ArrayList<>();
        if (deque == null) {
            return out;
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        synchronized (deque) {
            for (PotionUse p : deque) {
                if (p.at() >= since) {
                    counts.merge(p.name(), 1, Integer::sum);
                }
            }
        }
        counts.forEach((name, count) -> out.add(count > 1 ? name + " x" + count : name));
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        potions.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------ creation

    @Override
    public void onPlayerDeath(KillContext ctx) {
        Settings s = settings(ctx.victim().getWorld());
        if (s.pvpOnly() && !ctx.isPvp()) {
            return;
        }
        Player victim = ctx.victim();
        Player killer = ctx.killer();
        long since = ctx.fightStart() > 0 ? ctx.fightStart() : System.currentTimeMillis() - s.fightWindowMillis();
        DamageTracker tracker = plugin.kills().tracker();
        double dealt = 0;
        int hitsDealt = 0;
        for (DamageTracker.Hit hit : tracker.recent(victim.getUniqueId(), s.fightWindowMillis())) {
            if (killer != null && killer.getUniqueId().equals(hit.attacker())) {
                dealt += hit.damage();
                hitsDealt++;
            }
        }
        double received = 0;
        int hitsReceived = 0;
        if (killer != null) {
            for (DamageTracker.Hit hit : tracker.recent(killer.getUniqueId(), s.fightWindowMillis())) {
                if (victim.getUniqueId().equals(hit.attacker())) {
                    received += hit.damage();
                    hitsReceived++;
                }
            }
        }
        List<String> assists = new ArrayList<>();
        for (UUID id : ctx.assists()) {
            Player p = Bukkit.getPlayer(id);
            assists.add(p == null ? id.toString().substring(0, 8) : p.getName());
        }
        String killerInv = "";
        String victimInv = "";
        if (s.inventorySnapshots()) {
            victimInv = ItemSerializer.serializeArray(victim.getInventory().getContents());
            if (killer != null && plugin.scheduler().isOwnedByCurrentThread(killer)) {
                killerInv = ItemSerializer.serializeArray(killer.getInventory().getContents());
            }
        }
        Recap recap = new Recap(UUID.randomUUID().toString(), killer == null ? null : killer.getUniqueId(),
                killer == null ? KillService.pretty(ctx.cause().name()) : killer.getName(), victim.getUniqueId(), victim.getName(),
                System.currentTimeMillis(), dealt, received, hitsDealt, hitsReceived, killer == null ? 0 : killer.getHealth(), assists,
                killer == null ? List.of() : potionsSince(killer.getUniqueId(), since), potionsSince(victim.getUniqueId(), since),
                Locations.format(ctx.location()), KillService.pretty(ctx.cause().name()),
                ctx.weapon() == null ? "" : KillService.itemName(ctx.weapon()), ctx.fightStart() > 0 ? System.currentTimeMillis() - ctx.fightStart() : 0,
                killerInv, victimInv);
        store(recap, s);
        if (s.notifyVictim()) {
            sendLink(victim, recap);
        }
        if (s.notifyKiller() && killer != null) {
            sendLink(killer, recap);
        }
    }

    private void store(Recap recap, Settings s) {
        byKey.put(recap.key(), recap);
        for (UUID id : new UUID[]{recap.victim(), recap.killer()}) {
            if (id == null) {
                continue;
            }
            Deque<Recap> deque = byPlayer.computeIfAbsent(id, k -> new ArrayDeque<>());
            synchronized (deque) {
                deque.addFirst(recap);
                while (deque.size() > s.maxPerPlayer()) {
                    Recap removed = deque.removeLast();
                    byKey.remove(removed.key());
                }
            }
        }
        if (s.persist() && plugin.storageReady()) {
            Database db = plugin.database();
            String yaml = recap.toYaml();
            db.run(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("kill_recaps")
                        + " (recap_key, data_group, killer_uuid, victim_uuid, created_at, payload) VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, recap.key());
                    ps.setString(2, db.group());
                    ps.setString(3, recap.killer() == null ? null : recap.killer().toString());
                    ps.setString(4, recap.victim().toString());
                    ps.setLong(5, recap.at());
                    ps.setString(6, yaml);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.t("kill_recaps") + " WHERE data_group = ? AND created_at < ?")) {
                    ps.setString(1, db.group());
                    ps.setLong(2, System.currentTimeMillis() - s.maxAgeMillis());
                    ps.executeUpdate();
                }
            });
        }
    }

    private void sendLink(Player player, Recap recap) {
        Object[] ph = {"killer", recap.killerName(), "victim", recap.victimName()};
        TextComponent line = new TextComponent(TextComponent.fromLegacyText(plugin.messages().prefix().isEmpty() ? ""
                : dev.ultimatecombat.core.message.Text.color(plugin.messages().prefix())));
        for (var part : TextComponent.fromLegacyText(plugin.messages().text(player, "killrecaps.notify", ph) + " ")) {
            line.addExtra(part);
        }
        TextComponent button = new TextComponent(TextComponent.fromLegacyText(plugin.messages().text(player, "killrecaps.button")));
        button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ultimatecombat killrecaps view " + recap.key()));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(plugin.messages().text(player, "killrecaps.button-hover"))));
        line.addExtra(button);
        player.spigot().sendMessage(line);
    }

    // ------------------------------------------------------------------------------ viewing

    private CompletableFuture<Recap> find(String key) {
        Recap cached = byKey.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        if (!plugin.storageReady()) {
            return CompletableFuture.completedFuture(null);
        }
        Database db = plugin.database();
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM " + db.t("kill_recaps") + " WHERE recap_key = ? AND data_group = ?")) {
                ps.setString(1, key);
                ps.setString(2, db.group());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    try {
                        return Recap.fromYaml(rs.getString(1));
                    } catch (InvalidConfigurationException e) {
                        return null;
                    }
                }
            }
        });
    }

    private CompletableFuture<List<Recap>> recent(UUID player) {
        Deque<Recap> deque = byPlayer.get(player);
        if (deque != null && !deque.isEmpty()) {
            synchronized (deque) {
                return CompletableFuture.completedFuture(new ArrayList<>(deque));
            }
        }
        if (!plugin.storageReady()) {
            return CompletableFuture.completedFuture(List.of());
        }
        Database db = plugin.database();
        int limit = settings().maxPerPlayer();
        return db.supply(c -> {
            List<Recap> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM " + db.t("kill_recaps")
                    + " WHERE data_group = ? AND (victim_uuid = ? OR killer_uuid = ?) ORDER BY created_at DESC LIMIT ?")) {
                ps.setString(1, db.group());
                ps.setString(2, player.toString());
                ps.setString(3, player.toString());
                ps.setInt(4, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            out.add(Recap.fromYaml(rs.getString(1)));
                        } catch (InvalidConfigurationException ignored) {
                            // skip damaged entry
                        }
                    }
                }
            }
            return out;
        });
    }

    private boolean mayView(Player player, Recap recap) {
        return player.getUniqueId().equals(recap.victim()) || player.getUniqueId().equals(recap.killer())
                || player.hasPermission("ultimatecombat.killrecaps.others");
    }

    @Override
    public void registerCommands() {
        SimpleCommand cmd = new SimpleCommand("killrecaps", "killrecaps [view <id>]", "ultimatecombat.killrecaps", true, this::command,
                ctx -> List.of("view"), "killrecap", "viewrecap");
        plugin.commands().root().child(cmd);
        plugin.commands().standalone("killrecaps", cmd);
    }

    private void command(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Player player = ctx.player();
        if (ctx.size() >= 2 && ctx.arg(0).equalsIgnoreCase("view")) {
            find(ctx.arg(1)).thenAccept(recap -> plugin.scheduler().runEntity(player, () -> {
                if (recap == null) {
                    ctx.reply("killrecaps.not-found");
                } else if (!mayView(player, recap)) {
                    ctx.reply("general.no-permission");
                } else {
                    openRecap(player, recap);
                }
            }));
            return;
        }
        UUID target = player.getUniqueId();
        if (ctx.size() >= 1 && !ctx.arg(0).equalsIgnoreCase("view")) {
            if (!ctx.has("ultimatecombat.killrecaps.others")) {
                ctx.reply("general.no-permission");
                return;
            }
            Player other = Bukkit.getPlayerExact(ctx.arg(0));
            if (other == null) {
                ctx.reply("general.player-not-found", "player", ctx.arg(0));
                return;
            }
            target = other.getUniqueId();
        }
        recent(target).thenAccept(list -> plugin.scheduler().runEntity(player, () -> openList(player, list, 0)));
    }

    private void openList(Player viewer, List<Recap> recaps, int page) {
        if (recaps.isEmpty()) {
            plugin.messages().send(viewer, "killrecaps.none");
            return;
        }
        MenuTemplate template = plugin.menus().template("kill-recaps");
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> p.closeInventory());
        Menu menu = plugin.menus().build(viewer, "kill-recaps", handlers);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        plugin.menus().paginate(menu, viewer, template, recaps, page,
                r -> new ItemBuilder(Material.PAPER)
                        .name(plugin.messages().text(viewer, "killrecaps.entry-name", "killer", r.killerName(), "victim", r.victimName()))
                        .lore(plugin.messages().lines(viewer, "killrecaps.entry-lore", "date", format.format(new Date(r.at())),
                                "cause", r.cause(), "location", r.location()))
                        .build(),
                (r, click) -> openRecap(viewer, r), p -> openList(viewer, recaps, p));
        menu.open(viewer);
    }

    private void openRecap(Player viewer, Recap r) {
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> p.closeInventory());
        handlers.put("killer-inventory", (p, c) -> openSnapshot(p, r, r.killerInventory(), r.killerName()));
        handlers.put("victim-inventory", (p, c) -> openSnapshot(p, r, r.victimInventory(), r.victimName()));
        String none = plugin.messages().settings().none();
        Menu menu = plugin.menus().build(viewer, "kill-recap", handlers,
                "killer", r.killerName(), "victim", r.victimName(),
                "damage_dealt", Numbers.twoDecimals(r.damageDealt()), "damage_received", Numbers.twoDecimals(r.damageReceived()),
                "hits_dealt", r.hitsDealt(), "hits_received", r.hitsReceived(),
                "health", Numbers.twoDecimals(r.killerHealth()), "hearts", Numbers.twoDecimals(r.killerHealth() / 2),
                "assists", r.assists().isEmpty() ? none : String.join(", ", r.assists()),
                "killer_potions", r.killerPotions().isEmpty() ? none : String.join(", ", r.killerPotions()),
                "victim_potions", r.victimPotions().isEmpty() ? none : String.join(", ", r.victimPotions()),
                "location", r.location(), "cause", r.cause(), "weapon", r.weapon().isEmpty() ? none : r.weapon(),
                "duration", r.durationMillis() > 0 ? plugin.messages().duration(r.durationMillis()) : plugin.messages().settings().unknown(),
                "date", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.at())));
        MenuTemplate template = plugin.menus().template("kill-recap");
        decorateHead(menu, template, "killer", r.killer());
        decorateHead(menu, template, "victim", r.victim());
        menu.open(viewer);
    }

    private void decorateHead(Menu menu, MenuTemplate template, String key, UUID owner) {
        MenuTemplate.ItemSpec spec = template.item(key);
        if (spec == null || owner == null || spec.material() != Material.PLAYER_HEAD) {
            return;
        }
        for (int slot : spec.slots()) {
            ItemStack current = menu.getInventory().getItem(slot);
            if (current != null) {
                menu.getInventory().setItem(slot, new ItemBuilder(current).skull(Bukkit.getOfflinePlayer(owner)).build());
            }
        }
    }

    private void openSnapshot(Player viewer, Recap recap, String data, String owner) {
        if (data == null || data.isEmpty()) {
            plugin.messages().send(viewer, "killrecaps.no-snapshot");
            return;
        }
        ItemStack[] items;
        try {
            items = ItemSerializer.deserializeArray(data);
        } catch (RuntimeException e) {
            plugin.messages().send(viewer, "killrecaps.no-snapshot");
            return;
        }
        Menu menu = new Menu(6, plugin.messages().text(viewer, "killrecaps.snapshot-title", "player", owner));
        for (int i = 0; i < items.length && i < 54; i++) {
            if (items[i] != null) {
                menu.set(i, items[i]);
            }
        }
        menu.set(53, ItemBuilder.named(Material.ARROW, plugin.messages().text(viewer, "killrecaps.back")), (p, c) -> openRecap(p, recap));
        menu.open(viewer);
    }
}
