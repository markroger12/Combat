package dev.ultimatecombat.modules.intervals;

import dev.ultimatecombat.UltimateCombatPlugin;
import dev.ultimatecombat.api.UntagReason;
import dev.ultimatecombat.core.command.CommandContext;
import dev.ultimatecombat.core.command.SimpleCommand;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.integration.IntegrationManager;
import dev.ultimatecombat.core.module.Module;
import dev.ultimatecombat.core.scheduler.ScheduledTask;
import dev.ultimatecombat.modules.combat.CombatModule;
import dev.ultimatecombat.modules.combat.CombatTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scheduled PvP windows (pvp-intervals.yml) by real clock time or Minecraft world time.
 */
public final class PvpIntervalModule extends Module<PvpIntervalModule.Settings> {

    public enum Mode { ENABLE_DURING, DISABLE_DURING }

    public enum Clock { REAL, WORLD }

    public record Interval(String name, Set<String> worlds, Set<DayOfWeek> days, int startMinute, int endMinute) {
        boolean appliesTo(World world) {
            return worlds.isEmpty() || worlds.contains(world.getName().toLowerCase(Locale.ROOT));
        }

        /**
         * @param minute minute of the day (0-1439)
         */
        boolean contains(int minute, DayOfWeek day, DayOfWeek previousDay) {
            if (startMinute <= endMinute) {
                return (days.isEmpty() || days.contains(day)) && minute >= startMinute && minute < endMinute;
            }
            // Spans midnight: the part after midnight belongs to the day it started on.
            if (minute >= startMinute) {
                return days.isEmpty() || days.contains(day);
            }
            return minute < endMinute && (days.isEmpty() || days.contains(previousDay));
        }
    }

    public record Settings(Mode mode, Clock clock, ZoneId zone, List<Interval> intervals, Set<String> regions, Set<String> worlds,
                           boolean untagOnClose, List<Integer> warningMinutes, boolean titles, String soundStart, String soundEnd,
                           String soundWarning) {
    }

    private final Map<String, Boolean> lastState = new HashMap<>();
    private final Map<String, Integer> lastWarning = new HashMap<>();
    private ScheduledTask ticker;

    public PvpIntervalModule(UltimateCombatPlugin plugin) {
        super(plugin, "pvp-intervals", "pvp-intervals.yml");
    }

    @Override
    protected Settings parse(ConfigReader r) {
        ZoneId zone;
        String zoneText = r.string("time-zone", "system");
        try {
            zone = zoneText.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(zoneText);
        } catch (RuntimeException e) {
            r.warn("time-zone", zoneText, "Unknown time zone", "system or an id such as Europe/Berlin, UTC, America/New_York",
                    "system time zone");
            zone = ZoneId.systemDefault();
        }
        List<Interval> intervals = new ArrayList<>();
        for (String name : r.keys("intervals")) {
            ConfigReader i = r.section("intervals." + name);
            Integer start = minuteOf(i, "start");
            Integer end = minuteOf(i, "end");
            if (start == null || end == null || start.equals(end)) {
                if (start != null && start.equals(end)) {
                    i.warn("end", i.string("end", ""), "Start and end are equal (interval ignored)", "different times", "interval skipped");
                }
                continue;
            }
            intervals.add(new Interval(name, i.lowerCaseSet("worlds"), days(i), start, end));
        }
        List<Integer> warnings = new ArrayList<>();
        for (String w : r.stringList("announcements.warn-minutes-before")) {
            try {
                warnings.add(Integer.parseInt(w.trim()));
            } catch (NumberFormatException e) {
                r.warn("announcements.warn-minutes-before", w, "Not a whole number (entry ignored)", "minutes such as 5", "entry skipped");
            }
        }
        return new Settings(
                r.enumValue("mode", Mode.class, Mode.ENABLE_DURING),
                r.enumValue("clock", Clock.class, Clock.REAL),
                zone, intervals,
                r.lowerCaseSet("regions"),
                r.lowerCaseSet("worlds"),
                r.bool("untag-when-pvp-closes", true),
                warnings,
                r.bool("announcements.titles", true),
                r.string("announcements.sounds.start", "interval-start"),
                r.string("announcements.sounds.end", "interval-end"),
                r.string("announcements.sounds.warning", "interval-warning"));
    }

    private static Integer minuteOf(ConfigReader r, String path) {
        String raw = r.string(path, "");
        try {
            LocalTime t = LocalTime.parse(raw.length() == 4 ? "0" + raw : raw);
            return t.getHour() * 60 + t.getMinute();
        } catch (DateTimeParseException e) {
            r.warn(path, raw, "Invalid time (interval ignored)", "HH:mm such as 18:00", "interval skipped");
            return null;
        }
    }

    private static Set<DayOfWeek> days(ConfigReader r) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String d : r.stringList("days")) {
            try {
                out.add(DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                r.warn("days", d, "Unknown day (entry ignored)", "MONDAY ... SUNDAY", "entry skipped");
            }
        }
        return out;
    }

    @Override
    protected void onEnable() {
        lastState.clear();
        ticker = plugin.scheduler().runGlobalTimer(this::tick, 20L, 20L);
    }

    @Override
    protected void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        lastState.clear();
        lastWarning.clear();
    }

    // ------------------------------------------------------------------------------ state

    private int minuteNow(World world, Settings s) {
        if (s.clock() == Clock.WORLD) {
            long ticks = world.getTime() % 24000L;
            // Minecraft 0 ticks = 06:00.
            return (int) (((ticks / 1000.0) + 6) % 24 * 60);
        }
        LocalDateTime now = ZonedDateTime.now(s.zone()).toLocalDateTime();
        return now.getHour() * 60 + now.getMinute();
    }

    private boolean inInterval(World world, Settings s) {
        int minute = minuteNow(world, s);
        DayOfWeek day = ZonedDateTime.now(s.zone()).getDayOfWeek();
        for (Interval i : s.intervals()) {
            // Day filters always use the real calendar day (also with the world clock).
            if (i.appliesTo(world) && i.contains(minute, day, day.minus(1))) {
                return true;
            }
        }
        return false;
    }

    private boolean managed(World world, Settings s) {
        return s.worlds().isEmpty() || s.worlds().contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * @return whether intervals allow PvP in the world right now (ignoring regions)
     */
    public boolean pvpAllowed(World world) {
        Settings s = settings(world);
        if (!managed(world, s)) {
            return true;
        }
        boolean in = inInterval(world, s);
        return s.mode() == Mode.ENABLE_DURING ? in : !in;
    }

    /**
     * @return whether PvP is allowed at the location (regions limit where intervals apply)
     */
    public boolean pvpAllowed(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return true;
        }
        Settings s = settings(world);
        if (!s.regions().isEmpty()) {
            boolean inRegion = false;
            for (String region : plugin.integrations().regionsAt(location)) {
                if (s.regions().contains(region)) {
                    inRegion = true;
                    break;
                }
            }
            if (!inRegion) {
                return true;
            }
        }
        return pvpAllowed(world);
    }

    /**
     * @return minutes until the PvP state of the world changes next, or -1 if it never changes
     */
    public int minutesUntilChange(World world) {
        Settings s = settings(world);
        if (s.intervals().isEmpty() || s.clock() == Clock.WORLD) {
            return -1;
        }
        boolean current = inInterval(world, s);
        ZonedDateTime now = ZonedDateTime.now(s.zone());
        for (int m = 1; m <= 7 * 24 * 60; m++) {
            ZonedDateTime t = now.plusMinutes(m);
            int minute = t.getHour() * 60 + t.getMinute();
            boolean state = false;
            for (Interval i : s.intervals()) {
                if (i.appliesTo(world) && i.contains(minute, t.getDayOfWeek(), t.getDayOfWeek().minus(1))) {
                    state = true;
                    break;
                }
            }
            if (state != current) {
                return m;
            }
        }
        return -1;
    }

    private void tick() {
        for (World world : Bukkit.getWorlds()) {
            Settings s = settings(world);
            if (!managed(world, s)) {
                continue;
            }
            boolean allowed = pvpAllowed(world);
            Boolean previous = lastState.put(world.getName(), allowed);
            if (previous != null && previous != allowed) {
                announce(world, allowed, s);
            }
        }
        warnings();
    }

    private void warnings() {
        for (World world : Bukkit.getWorlds()) {
            Settings s = settings(world);
            if (!managed(world, s) || s.warningMinutes().isEmpty() || s.clock() == Clock.WORLD || pvpAllowed(world)) {
                continue;
            }
            int left = minutesUntilChange(world);
            if (left > 0 && s.warningMinutes().contains(left)) {
                Integer last = lastWarning.get(world.getName());
                if (last == null || last != left) {
                    lastWarning.put(world.getName(), left);
                    plugin.messages().broadcast(p -> p.getWorld().equals(world), "intervals.warning", "minutes", left,
                            "world", world.getName());
                    for (Player p : world.getPlayers()) {
                        plugin.effects().playSound(p, s.soundWarning());
                    }
                }
            }
        }
    }

    private void announce(World world, boolean allowed, Settings s) {
        lastWarning.remove(world.getName());
        String key = allowed ? "intervals.started" : "intervals.ended";
        for (Player p : world.getPlayers()) {
            plugin.scheduler().executeEntity(p, () -> {
                plugin.messages().send(p, key, "world", world.getName());
                if (s.titles()) {
                    plugin.messages().send(p, allowed ? "intervals.title-start" : "intervals.title-end", "world", world.getName());
                }
                plugin.effects().playSound(p, allowed ? s.soundStart() : s.soundEnd());
            });
        }
        if (!allowed && s.untagOnClose()) {
            CombatModule combat = plugin.active(CombatModule.class);
            if (combat != null) {
                for (CombatTag tag : new ArrayList<>(combat.tags())) {
                    Player p = Bukkit.getPlayer(tag.player());
                    if (p != null && p.getWorld().equals(world)) {
                        plugin.scheduler().executeEntity(p, () -> combat.untag(p, UntagReason.PVP_INTERVAL, true));
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (IntegrationManager.isSimulating() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = plugin.kills().attribution().resolve(event.getDamager());
        if (attacker == null || attacker == victim) {
            return;
        }
        if (!pvpAllowed(victim.getLocation()) || !pvpAllowed(attacker.getLocation())) {
            event.setCancelled(true);
            plugin.messages().send(attacker, "intervals.pvp-closed");
        }
    }

    // ------------------------------------------------------------------------------ command

    @Override
    public void registerCommands() {
        SimpleCommand cmd = new SimpleCommand("pvp-interval", "pvp-interval", "ultimatecombat.interval", false, this::status);
        plugin.commands().root().child(cmd);
        plugin.commands().standalone("pvp-interval", cmd);
    }

    private void status(CommandContext ctx) {
        if (!guard(ctx)) {
            return;
        }
        World world = ctx.isPlayer() ? ctx.player().getWorld() : Bukkit.getWorlds().get(0);
        boolean allowed = pvpAllowed(world);
        int minutes = minutesUntilChange(world);
        ctx.reply(allowed ? "intervals.status-open" : "intervals.status-closed", "world", world.getName(),
                "time", minutes < 0 ? plugin.messages().settings().unknown() : plugin.messages().duration(minutes * 60_000L));
        for (Interval i : settings(world).intervals()) {
            ctx.reply("intervals.entry", "name", i.name(), "start", String.format(Locale.ROOT, "%02d:%02d", i.startMinute() / 60, i.startMinute() % 60),
                    "end", String.format(Locale.ROOT, "%02d:%02d", i.endMinute() / 60, i.endMinute() % 60),
                    "days", i.days().isEmpty() ? "*" : i.days().toString());
        }
    }
}
