package dev.ultimatecombat.modules.leaderboards;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.gui.Menu;
import dev.ultimatecombat.core.gui.MenuTemplate;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.player.PlayerProfile;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.core.storage.Database;
import dev.ultimatecombat.core.util.ItemBuilder;
import dev.ultimatecombat.core.util.Locations;
import dev.ultimatecombat.core.util.Numbers;
import dev.ultimatecombat.modules.stats.StatsModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leaderboards (leaderboards.yml): periodically cached top lists from the database, shown in chat,
 * a menu, text-display holograms, signs (with heads) and optionally the tab list.
 */
public final class LeaderboardModule extends Module<LeaderboardModule.Settings> {

    public enum Board {
        KILLS("kills"), DEATHS("deaths"), KDR(null), POINTS("points"), BEST_STREAK("best_streak"), STREAK("streak"),
        COMBAT_LOGS("combat_logs"), ASSISTS("assists"), MOB_KILLS("mob_kills"), BOUNTIES_CLAIMED("bounties_claimed"),
        BOUNTY_EARNINGS("bounty_earnings"), DAMAGE_DEALT("damage_dealt");

        private final String column;

        Board(String column) {
            this.column = column;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public static Board parse(String text) {
            if (text == null) {
                return null;
            }
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public record Entry(int position, UUID uuid, String name, double value) {
    }

    public record Hologram(String id, Board board, Location location, int lines) {
    }

    public record SignSpec(String id, Board board, int position, Location location) {
    }

    public record Settings(long refreshTicks, int size, List<Board> boards, Board defaultBoard, int kdrMinKills, Set<String> excluded,
                           int chatPageSize, boolean gui, boolean tablist, Map<String, Hologram> holograms, float hologramScale,
                           boolean hologramShadow, boolean hologramBackground, Map<String, SignSpec> signs, boolean signHeads) {
    }

    private volatile Map<Board, List<Entry>> cache = Map.of();
    private volatile Map<Board, Map<UUID, Integer>> positions = Map.of();
    private volatile long lastRefresh;
    private final Map<String, TextDisplay> displays = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private ScheduledTask timer;

    public LeaderboardModule(UltimateCombatPlugin plugin) {
        super(plugin, "leaderboards", "leaderboards.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        List<Board> boards = new ArrayList<>();
        for (String id : r.stringList("boards")) {
            Board b = Board.parse(id);
            if (b == null) {
                r.warn("boards", id, "Unknown leaderboard (entry ignored)", boardList(), "entry skipped");
            } else if (!boards.contains(b)) {
                boards.add(b);
            }
        }
        if (boards.isEmpty()) {
            boards.add(Board.POINTS);
        }
        Board def = Board.parse(r.string("default-board", "points"));
        if (def == null || !boards.contains(def)) {
            r.warn("default-board", r.string("default-board", "points"), "Must be one of the enabled boards", boards.toString(),
                    boards.get(0).id());
            def = boards.get(0);
        }
        Map<String, Hologram> holograms = new LinkedHashMap<>();
        for (String id : r.keys("holograms")) {
            ConfigReader h = r.section("holograms." + id);
            Board board = Board.parse(h.string("board", "points"));
            Location loc = Locations.decode(h.string("location", ""));
            if (board == null || loc == null) {
                h.warn("location", h.string("location", ""), "Invalid hologram (unknown board or world)", "created with /uc leaderboard",
                        "hologram skipped");
                continue;
            }
            holograms.put(id, new Hologram(id, board, loc, h.integer("lines", 10, 1, 50)));
        }
        Map<String, SignSpec> signs = new LinkedHashMap<>();
        for (String id : r.keys("signs")) {
            ConfigReader sg = r.section("signs." + id);
            Board board = Board.parse(sg.string("board", "points"));
            Location loc = Locations.decode(sg.string("location", ""));
            if (board == null || loc == null) {
                sg.warn("location", sg.string("location", ""), "Invalid sign (unknown board or world)", "created with /uc leaderboard",
                        "sign skipped");
                continue;
            }
            signs.put(id, new SignSpec(id, board, sg.integer("position", 1, 1, 1000), loc));
        }
        return new Settings(
                r.durationMillis("refresh-interval-seconds", 60_000, ConfigReader.TimeUnitHint.SECONDS, 5_000, 3_600_000) / 50L,
                r.integer("cached-entries", 100, 10, 1000),
                List.copyOf(boards), def,
                r.integer("kdr-minimum-kills", 10, 0, 1_000_000),
                r.lowerCaseSet("excluded-players"),
                r.integer("chat.page-size", 10, 1, 50),
                r.bool("menu.enabled", true),
                r.bool("tablist.enabled", false),
                Map.copyOf(holograms),
                (float) r.decimal("hologram-settings.scale", 1.0, 0.1, 5),
                r.bool("hologram-settings.text-shadow", true),
                r.bool("hologram-settings.background", true),
                Map.copyOf(signs),
                r.bool("sign-settings.update-heads", true));
    }

    private static String boardList() {
        List<String> out = new ArrayList<>();
        for (Board b : Board.values()) {
            out.add(b.id());
        }
        return String.join(", ", out);
    }

    @Override
    protected void onEnable() {
        Settings s = settings();
        timer = plugin.scheduler().runGlobalTimer(this::refresh, 40L, s.refreshTicks());
    }

    @Override
    protected void onReload(Settings previous) {
        if (timer != null) {
            timer.cancel();
        }
        removeDisplays();
        if (previous.tablist() && !settings().tablist()) {
            resetTablist();
        }
        timer = plugin.scheduler().runGlobalTimer(this::refresh, 20L, settings().refreshTicks());
    }

    @Override
    protected void onDisable() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        removeDisplays();
        if (settings().tablist()) {
            resetTablist();
        }
        cache = Map.of();
        positions = Map.of();
    }

    private void removeDisplays() {
        for (TextDisplay display : new ArrayList<>(displays.values())) {
            plugin.scheduler().executeEntity(display, display::remove);
        }
        displays.clear();
    }

    // ------------------------------------------------------------------------------ data

    /**
     * @return the cached entries of a board (best first); empty before the first refresh
     */
    public List<Entry> top(Board board) {
        return cache.getOrDefault(board, List.of());
    }

    /**
     * @return the entry at a 1-based position, or {@code null}
     */
    public Entry entry(Board board, int position) {
        List<Entry> list = top(board);
        return position >= 1 && position <= list.size() ? list.get(position - 1) : null;
    }

    /**
     * @return the 1-based position of a player within the cached entries, or 0 when not listed
     */
    public int position(Board board, UUID player) {
        Map<UUID, Integer> map = positions.get(board);
        Integer p = map == null ? null : map.get(player);
        return p == null ? 0 : p;
    }

    public long lastRefresh() {
        return lastRefresh;
    }

    /**
     * Reloads every enabled board from the database (asynchronously) and updates all displays.
     */
    public void refresh() {
        if (!plugin.storageReady() || !refreshing.compareAndSet(false, true)) {
            return;
        }
        Settings s = settings();
        plugin.players().saveDirty();
        StatsModule stats = plugin.module(StatsModule.class);
        long offset = stats == null ? 0 : stats.pointsOffset();
        plugin.database().supply(c -> {
            Map<Board, List<Entry>> out = new EnumMap<>(Board.class);
            for (Board board : s.boards()) {
                out.put(board, query(c, board, s, board == Board.POINTS ? offset : 0));
            }
            return out;
        }).whenComplete((result, error) -> {
            refreshing.set(false);
            if (error != null) {
                plugin.getLogger().warning("[leaderboards] Refresh failed: " + Database.rootMessage(error));
                return;
            }
            Map<Board, Map<UUID, Integer>> pos = new EnumMap<>(Board.class);
            for (Map.Entry<Board, List<Entry>> e : result.entrySet()) {
                Map<UUID, Integer> m = new HashMap<>();
                for (Entry entry : e.getValue()) {
                    m.put(entry.uuid(), entry.position());
                }
                pos.put(e.getKey(), m);
            }
            cache = Collections.unmodifiableMap(result);
            positions = Collections.unmodifiableMap(pos);
            lastRefresh = System.currentTimeMillis();
            plugin.scheduler().runGlobal(this::updateDisplays);
        });
    }

    private List<Entry> query(Connection c, Board board, Settings s, long offset) throws SQLException {
        String stats = plugin.database().t("stats");
        String order = board == Board.KDR
                ? "(kills * 1.0) / (CASE WHEN deaths = 0 THEN 1 ELSE deaths END)"
                : board.column;
        String where = "data_group = ? AND name <> ''" + (board == Board.KDR ? " AND kills >= ?" : "")
                + (board == Board.DEATHS || board == Board.COMBAT_LOGS ? " AND " + board.column + " > 0" : "");
        String sql = "SELECT uuid, name, " + order + " AS value FROM " + stats + " WHERE " + where + " ORDER BY value DESC LIMIT ?";
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, plugin.database().group());
            if (board == Board.KDR) {
                ps.setInt(i++, s.kdrMinKills());
            }
            ps.setInt(i, s.size() + s.excluded().size());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < s.size()) {
                    String name = rs.getString("name");
                    if (s.excluded().contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString("uuid"));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    out.add(new Entry(out.size() + 1, uuid, name, rs.getDouble("value") + offset));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Formats a board value (decimals for KDR/damage, currency for earnings).
     */
    public String format(Board board, double value) {
        return switch (board) {
            case KDR, DAMAGE_DEALT -> Numbers.twoDecimals(value);
            case BOUNTY_EARNINGS -> plugin.economy().format(value);
            default -> Long.toString(Math.round(value));
        };
    }

    public String boardName(Player viewer, Board board) {
        return plugin.messages().text(viewer, "leaderboard.boards." + board.id());
    }

    // ------------------------------------------------------------------------------ displays

    private void updateDisplays() {
        Settings s = settings();
        for (Hologram h : s.holograms().values()) {
            plugin.scheduler().runAt(h.location(), () -> renderHologram(h, s));
        }
        for (SignSpec sign : s.signs().values()) {
            plugin.scheduler().runAt(sign.location(), () -> renderSign(sign, s));
        }
        if (s.tablist()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.scheduler().executeEntity(p, () -> updateTablist(p));
            }
        }
    }

    private String hologramText(Hologram h) {
        StringBuilder sb = new StringBuilder(plugin.messages().text("leaderboard.hologram-header", "board", boardName(null, h.board())));
        for (int i = 1; i <= h.lines(); i++) {
            Entry e = entry(h.board(), i);
            sb.append('\n');
            if (e == null) {
                sb.append(plugin.messages().text("leaderboard.hologram-empty", "position", i));
            } else {
                sb.append(plugin.messages().text("leaderboard.hologram-line", "position", i, "player", e.name(),
                        "value", format(h.board(), e.value())));
            }
        }
        String footer = plugin.messages().text("leaderboard.hologram-footer", "board", boardName(null, h.board()));
        if (!footer.isEmpty()) {
            sb.append('\n').append(footer);
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private void renderHologram(Hologram h, Settings s) {
        Location loc = h.location();
        if (loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        String text = hologramText(h);
        TextDisplay display = displays.get(h.id());
        if (display == null || !display.isValid()) {
            display = loc.getWorld().spawn(loc, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(s.hologramShadow());
                d.setDefaultBackground(s.hologramBackground());
                if (!s.hologramBackground()) {
                    d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                }
                d.setLineWidth(400);
                var t = d.getTransformation();
                d.setTransformation(new org.bukkit.util.Transformation(t.getTranslation(), t.getLeftRotation(),
                        new org.joml.Vector3f(s.hologramScale(), s.hologramScale(), s.hologramScale()), t.getRightRotation()));
            });
            displays.put(h.id(), display);
        }
        display.setText(text);
    }

    @SuppressWarnings("deprecation")
    private void renderSign(SignSpec spec, Settings s) {
        Location loc = spec.location();
        if (loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }
        Entry e = entry(spec.board(), spec.position());
        List<String> lines = e == null
                ? plugin.messages().lines(null, "leaderboard.sign-empty", "position", spec.position(), "board", boardName(null, spec.board()))
                : plugin.messages().lines(null, "leaderboard.sign", "position", spec.position(), "player", e.name(),
                "value", format(spec.board(), e.value()), "board", boardName(null, spec.board()));
        for (int i = 0; i < 4; i++) {
            sign.getSide(Side.FRONT).setLine(i, i < lines.size() ? lines.get(i) : "");
        }
        sign.update(false, false);
        if (s.signHeads() && e != null) {
            for (Block candidate : headCandidates(block)) {
                BlockState state = candidate.getState();
                if (state instanceof Skull skull) {
                    if (skull.getOwningPlayer() == null || !e.uuid().equals(skull.getOwningPlayer().getUniqueId())) {
                        skull.setOwningPlayer(Bukkit.getOfflinePlayer(e.uuid()));
                        skull.update(false, false);
                    }
                    break;
                }
            }
        }
    }

    private static List<Block> headCandidates(Block sign) {
        List<Block> out = new ArrayList<>(2);
        out.add(sign.getRelative(BlockFace.UP));
        if (sign.getBlockData() instanceof WallSign wall) {
            out.add(sign.getRelative(wall.getFacing().getOppositeFace()).getRelative(BlockFace.UP));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private void updateTablist(Player player) {
        if (!player.isOnline()) {
            return;
        }
        StatsModule stats = plugin.module(StatsModule.class);
        PlayerProfile profile = plugin.players().online(player.getUniqueId());
        String rank = stats == null || profile == null ? "" : stats.rankName(profile.stats());
        int pos = position(settings().defaultBoard(), player.getUniqueId());
        player.setPlayerListName(plugin.messages().text(player, "leaderboard.tablist", "player", player.getName(), "rank", rank,
                "position", pos == 0 ? "-" : pos));
    }

    @SuppressWarnings("deprecation")
    private void resetTablist() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().executeEntity(p, () -> p.setPlayerListName(null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (settings().tablist()) {
            Player p = event.getPlayer();
            plugin.scheduler().runEntityLater(p, () -> updateTablist(p), 40L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Settings s = settings();
        for (Hologram h : s.holograms().values()) {
            Location l = h.location();
            if (event.getWorld().equals(l.getWorld()) && l.getBlockX() >> 4 == event.getChunk().getX()
                    && l.getBlockZ() >> 4 == event.getChunk().getZ()) {
                plugin.scheduler().runAtLater(l, () -> renderHologram(h, s), 2L);
            }
        }
    }

    // ------------------------------------------------------------------------------ commands

    @Override
    public void registerCommands() {
        SimpleCommand top = new SimpleCommand("top", "top [board] [page]", "ultimatecombat.leaderboard", false, this::topCommand,
                ctx -> ctx.size() == 1 ? boardIds() : List.of());
        plugin.commands().root().child(top);
        plugin.commands().standalone("pvptop", top);
        plugin.commands().root().child(new SimpleCommand("leaderboard",
                "leaderboard <refresh|hologram|sign> ...", "ultimatecombat.leaderboard.admin", false, this::adminCommand,
                ctx -> switch (ctx.size()) {
                    case 1 -> List.of("refresh", "hologram", "sign");
                    case 2 -> ctx.arg(0).equalsIgnoreCase("hologram") ? List.of("create", "delete", "move", "list")
                            : ctx.arg(0).equalsIgnoreCase("sign") ? List.of("create", "remove", "list") : List.of();
                    case 3 -> ctx.arg(1).equalsIgnoreCase("create") && ctx.arg(0).equalsIgnoreCase("sign") ? boardIds()
                            : ctx.arg(0).equalsIgnoreCase("hologram") && !ctx.arg(1).equalsIgnoreCase("create")
                            ? new ArrayList<>(settings().holograms().keySet()) : List.of();
                    case 4 -> ctx.arg(0).equalsIgnoreCase("hologram") && ctx.arg(1).equalsIgnoreCase("create") ? boardIds()
                            : List.of("1", "2", "3");
                    default -> List.of();
                }));
    }

    private List<String> boardIds() {
        List<String> out = new ArrayList<>();
        for (Board b : settings().boards()) {
            out.add(b.id());
        }
        return out;
    }

    private void topCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        Settings s = settings();
        Board board = ctx.size() > 0 ? Board.parse(ctx.arg(0)) : s.defaultBoard();
        if (board == null || !s.boards().contains(board)) {
            ctx.reply("leaderboard.unknown-board", "board", ctx.argOr(0, ""), "boards", String.join(", ", boardIds()));
            return;
        }
        if (cache.isEmpty()) {
            ctx.reply("leaderboard.not-ready");
            refresh();
            return;
        }
        if (ctx.isPlayer() && s.gui() && ctx.size() < 2) {
            openMenu(ctx.player(), board, 0);
            return;
        }
        Integer page = ctx.size() > 1 ? ctx.intArg(1, 1, 1000) : Integer.valueOf(1);
        if (page == null) {
            return;
        }
        List<Entry> entries = top(board);
        int pages = Math.max(1, (entries.size() + s.chatPageSize() - 1) / s.chatPageSize());
        int current = Math.min(page, pages);
        Player viewer = ctx.isPlayer() ? ctx.player() : null;
        ctx.reply("leaderboard.header", "board", boardName(viewer, board), "page", current, "pages", pages);
        if (entries.isEmpty()) {
            ctx.reply("leaderboard.empty");
        }
        for (int i = (current - 1) * s.chatPageSize(); i < Math.min(entries.size(), current * s.chatPageSize()); i++) {
            Entry e = entries.get(i);
            ctx.reply("leaderboard.entry", "position", e.position(), "player", e.name(), "value", format(board, e.value()));
        }
        if (viewer != null) {
            int own = position(board, viewer.getUniqueId());
            ctx.reply("leaderboard.own-position", "position", own == 0 ? "-" : own, "board", boardName(viewer, board));
        }
        ctx.reply("leaderboard.footer", "board", board.id(), "page", current, "pages", pages, "next", Math.min(pages, current + 1));
    }

    private void openMenu(Player viewer, Board board, int page) {
        MenuTemplate template = plugin.menus().template("leaderboard");
        Map<String, Menu.ClickHandler> handlers = new HashMap<>();
        handlers.put("close", (p, c) -> plugin.scheduler().runEntity(p, p::closeInventory));
        for (Board b : settings().boards()) {
            handlers.put("board-" + b.id(), (p, c) -> plugin.scheduler().runEntity(p, () -> openMenu(p, b, 0)));
        }
        int own = position(board, viewer.getUniqueId());
        Menu menu = plugin.menus().build(viewer, "leaderboard", handlers, "board", boardName(viewer, board),
                "position", own == 0 ? "-" : own);
        plugin.menus().paginate(menu, viewer, template, top(board), page, e -> new ItemBuilder(Material.PLAYER_HEAD)
                .skull(Bukkit.getOfflinePlayer(e.uuid()))
                .name(plugin.messages().text(viewer, "leaderboard.menu-item-name", "position", e.position(), "player", e.name()))
                .lore(plugin.messages().lines(viewer, "leaderboard.menu-item-lore", "position", e.position(), "player", e.name(),
                        "value", format(board, e.value()), "board", boardName(viewer, board)))
                .build(), (e, click) -> {
        }, p -> openMenu(viewer, board, p));
        menu.open(viewer);
    }

    private void adminCommand(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        String sub = ctx.argOr(0, "").toLowerCase(Locale.ROOT);
        switch (sub) {
            case "refresh" -> {
                refresh();
                ctx.reply("leaderboard.refreshing");
            }
            case "hologram" -> hologramCommand(ctx);
            case "sign" -> signCommand(ctx);
            default -> ctx.reply("general.usage", "usage", "/uc leaderboard <refresh|hologram|sign>");
        }
    }

    private void hologramCommand(CommandContext ctx) {
        String action = ctx.argOr(1, "list").toLowerCase(Locale.ROOT);
        ConfigFile file = plugin.configs().file("leaderboards.yml");
        Settings s = settings();
        switch (action) {
            case "list" -> {
                ctx.reply("leaderboard.hologram-list", "count", s.holograms().size());
                for (Hologram h : s.holograms().values()) {
                    ctx.reply("leaderboard.hologram-list-entry", "id", h.id(), "board", h.board().id(), "location",
                            Locations.format(h.location()));
                }
            }
            case "create", "move" -> {
                if (!ctx.isPlayer()) {
                    ctx.reply("general.player-only");
                    return;
                }
                String id = ctx.arg(2);
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) {
                    ctx.reply("general.usage", "usage", "/uc leaderboard hologram " + action + " <id>"
                            + (action.equals("create") ? " <board> [lines]" : ""));
                    return;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                if (action.equals("create")) {
                    Board board = Board.parse(ctx.argOr(3, s.defaultBoard().id()));
                    if (board == null || !s.boards().contains(board)) {
                        ctx.reply("leaderboard.unknown-board", "board", ctx.argOr(3, ""), "boards", String.join(", ", boardIds()));
                        return;
                    }
                    Integer lines = ctx.size() > 4 ? ctx.intArg(4, 1, 50) : Integer.valueOf(10);
                    if (lines == null) {
                        return;
                    }
                    data.put("board", board.id());
                    data.put("lines", lines);
                } else {
                    Hologram old = s.holograms().get(id);
                    if (old == null) {
                        ctx.reply("leaderboard.hologram-unknown", "id", id);
                        return;
                    }
                    data.put("board", old.board().id());
                    data.put("lines", old.lines());
                }
                data.put("location", Locations.encode(ctx.player().getLocation().add(0, 2, 0)));
                plugin.configs().newBackupSession();
                if (file.write("holograms." + id, data)) {
                    plugin.reload("leaderboards.yml");
                    refresh();
                    ctx.reply("leaderboard.hologram-saved", "id", id);
                } else {
                    ctx.reply("admin.config.write-failed", "file", file.name());
                }
            }
            case "delete" -> {
                String id = ctx.argOr(2, "");
                if (!s.holograms().containsKey(id)) {
                    ctx.reply("leaderboard.hologram-unknown", "id", id);
                    return;
                }
                plugin.configs().newBackupSession();
                if (file.write("holograms." + id, null)) {
                    plugin.reload("leaderboards.yml");
                    ctx.reply("leaderboard.hologram-deleted", "id", id);
                }
            }
            default -> ctx.reply("general.usage", "usage", "/uc leaderboard hologram <create|move|delete|list>");
        }
    }

    private void signCommand(CommandContext ctx) {
        String action = ctx.argOr(1, "list").toLowerCase(Locale.ROOT);
        ConfigFile file = plugin.configs().file("leaderboards.yml");
        Settings s = settings();
        if (action.equals("list")) {
            ctx.reply("leaderboard.sign-list", "count", s.signs().size());
            for (SignSpec sign : s.signs().values()) {
                ctx.reply("leaderboard.sign-list-entry", "id", sign.id(), "board", sign.board().id(), "position", sign.position(),
                        "location", Locations.format(sign.location()));
            }
            return;
        }
        if (!ctx.isPlayer()) {
            ctx.reply("general.player-only");
            return;
        }
        Block target = ctx.player().getTargetBlockExact(6);
        if (target == null || !(target.getState() instanceof Sign)) {
            ctx.reply("leaderboard.sign-look");
            return;
        }
        String encoded = Locations.encode(target.getLocation());
        String existing = null;
        for (SignSpec sign : s.signs().values()) {
            if (Locations.sameWorld(sign.location(), target.getLocation()) && sign.location().getBlockX() == target.getX()
                    && sign.location().getBlockY() == target.getY() && sign.location().getBlockZ() == target.getZ()) {
                existing = sign.id();
            }
        }
        plugin.configs().newBackupSession();
        if (action.equals("remove")) {
            if (existing == null) {
                ctx.reply("leaderboard.sign-unknown");
                return;
            }
            if (file.write("signs." + existing, null)) {
                plugin.reload("leaderboards.yml");
                ctx.reply("leaderboard.sign-removed");
            }
            return;
        }
        if (!action.equals("create")) {
            ctx.reply("general.usage", "usage", "/uc leaderboard sign <create|remove|list>");
            return;
        }
        Board board = Board.parse(ctx.argOr(2, s.defaultBoard().id()));
        if (board == null || !s.boards().contains(board)) {
            ctx.reply("leaderboard.unknown-board", "board", ctx.argOr(2, ""), "boards", String.join(", ", boardIds()));
            return;
        }
        Integer position = ctx.size() > 3 ? ctx.intArg(3, 1, 1000) : Integer.valueOf(1);
        if (position == null) {
            return;
        }
        String id = existing != null ? existing : "sign-" + Long.toString(System.currentTimeMillis(), 36);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("board", board.id());
        data.put("position", position);
        data.put("location", encoded);
        if (file.write("signs." + id, data)) {
            plugin.reload("leaderboards.yml");
            refresh();
            ctx.reply("leaderboard.sign-created", "board", boardName(ctx.player(), board), "position", position);
        } else {
            ctx.reply("admin.config.write-failed", "file", file.name());
        }
    }

    /**
     * @return all board ids (for placeholders and documentation)
     */
    public static Set<String> allBoardIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Board b : Board.values()) {
            out.add(b.id());
        }
        return out;
    }
}
