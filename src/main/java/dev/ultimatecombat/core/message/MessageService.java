package dev.ultimatecombat.core.message;

import dev.ultimatecombat.core.config.ConfigFile;
import dev.ultimatecombat.core.config.ConfigReader;
import dev.ultimatecombat.core.config.ConfigService;
import dev.ultimatecombat.core.effect.EffectService;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Loads language files and delivers every player-facing text. No player-facing English text is
 * hard-coded anywhere else; missing keys fall back to the bundled English file.
 *
 * <p>Lookup order: {@code languages/custom.yml} → selected language (or the player's client
 * locale when enabled) → bundled {@code en_US}.</p>
 */
public final class MessageService {

    private final ConfigService configs;
    private final EffectService effects;
    private final Map<String, ConfigFile> languages = new ConcurrentHashMap<>();
    private final Map<String, Message> cache = new ConcurrentHashMap<>();
    private volatile ConfigFile selected;
    private volatile ConfigFile fallback;
    private volatile ConfigFile custom;
    private volatile String prefix = "";
    private volatile boolean perPlayerLocale;
    private volatile boolean usePlaceholderApi = true;
    private volatile BiFunction<Player, String, String> externalPlaceholders;
    private volatile Settings settings = new Settings("d", "h", "m", "s", "true", "false", "none", "-", true);

    /**
     * Global formatting options from messages.yml.
     */
    public record Settings(String day, String hour, String minute, String second, String yes, String no,
                           String none, String unknown, boolean timerDecimals) {
    }

    public MessageService(ConfigService configs, EffectService effects) {
        this.configs = configs;
        this.effects = effects;
    }

    public void load() {
        cache.clear();
        ConfigReader r = configs.file("messages.yml").reader();
        prefix = r.string("prefix", "&8[&cUltimateCombat&8] &r");
        String language = r.string("language", "en_US");
        perPlayerLocale = r.bool("per-player-language", false);
        usePlaceholderApi = r.bool("use-placeholderapi", true);
        Text.setMode(r.enumValue("text-format", Text.Mode.class, Text.Mode.AUTO));
        ConfigReader units = r.section("formats");
        settings = new Settings(
                units.string("day", "d"), units.string("hour", "h"), units.string("minute", "m"), units.string("second", "s"),
                units.string("yes", "&atrue"), units.string("no", "&cfalse"), units.string("none", "None"),
                units.string("unknown", "-"), units.bool("timer-decimals", true));

        // Bundled languages are always written out so administrators can translate them.
        for (String bundled : List.of("en_US", "de_DE", "custom")) {
            configs.saveResourceIfMissing("languages/" + bundled + ".yml");
        }
        languages.clear();
        File dir = new File(configs.plugin().getDataFolder(), "languages");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String code = f.getName().substring(0, f.getName().length() - 4);
                ConfigFile cf = configs.find("languages/" + f.getName());
                if (cf == null) {
                    cf = configs.register("languages/" + f.getName());
                }
                cf.load();
                languages.put(code.toLowerCase(Locale.ROOT), cf);
            }
        }
        fallback = configs.file("languages/en_US.yml");
        custom = languages.get("custom");
        ConfigFile sel = languages.get(language.toLowerCase(Locale.ROOT));
        if (sel == null) {
            configs.file("messages.yml").reader().info("language", "Language file languages/" + language
                    + ".yml not found; using en_US");
            sel = fallback;
        }
        selected = sel;
    }

    public Settings settings() {
        return settings;
    }

    public String prefix() {
        return prefix;
    }

    public void setExternalPlaceholders(BiFunction<Player, String, String> resolver) {
        this.externalPlaceholders = resolver;
    }

    // ----------------------------------------------------------------------------- lookup

    public Message message(String key) {
        return message(key, null);
    }

    private Message message(String key, String locale) {
        String cacheKey = locale == null ? key : locale + "|" + key;
        Message cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Message parsed = parse(key, locale);
        cache.put(cacheKey, parsed);
        return parsed;
    }

    private Object lookup(String key, String locale) {
        ConfigFile c = custom;
        if (c != null && c.isSetByUser(key)) {
            return c.rawValue(key);
        }
        if (locale != null) {
            ConfigFile byLocale = languages.get(locale);
            if (byLocale != null && byLocale != c) {
                Object v = byLocale.rawValue(key);
                if (v != null) {
                    return v;
                }
            }
        }
        ConfigFile sel = selected;
        if (sel != null) {
            Object v = sel.rawValue(key);
            if (v != null) {
                return v;
            }
        }
        ConfigFile fb = fallback;
        return fb == null ? null : fb.rawValue(key);
    }

    private Message parse(String key, String locale) {
        Object raw = lookup(key, locale);
        if (raw == null) {
            return new Message(List.of("&cMissing message: " + key), "", "", "", 10, 40, 10, "", true);
        }
        if (raw instanceof ConfigurationSection section) {
            List<String> chat = new ArrayList<>();
            Object c = section.get("chat");
            if (c instanceof List<?> list) {
                for (Object o : list) chat.add(String.valueOf(o));
            } else if (c != null && !c.toString().isEmpty()) {
                chat.add(c.toString());
            }
            return new Message(chat,
                    section.getString("actionbar", ""),
                    section.getString("title", ""),
                    section.getString("subtitle", ""),
                    section.getInt("fade-in", 10), section.getInt("stay", 40), section.getInt("fade-out", 10),
                    section.getString("sound", ""),
                    section.getBoolean("prefix", true));
        }
        if (raw instanceof List<?> list) {
            List<String> chat = new ArrayList<>();
            for (Object o : list) chat.add(String.valueOf(o));
            if (chat.size() == 1 && chat.get(0).isEmpty()) {
                return Message.EMPTY;
            }
            return new Message(chat, "", "", "", 10, 40, 10, "", true);
        }
        String s = raw.toString();
        if (s.isEmpty()) {
            return Message.EMPTY;
        }
        return new Message(List.of(s), "", "", "", 10, 40, 10, "", true);
    }

    private String localeOf(CommandSender sender) {
        if (!perPlayerLocale || !(sender instanceof Player p)) {
            return null;
        }
        try {
            return p.getLocale().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------------------- formatting

    /**
     * Replaces {@code {name}} placeholders given as alternating name/value pairs, then external
     * (PlaceholderAPI) placeholders for the viewer, then colours.
     */
    public String apply(Player viewer, String text, Object... placeholders) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = replace(text, placeholders);
        if (s.contains("{prefix}")) {
            s = s.replace("{prefix}", prefix);
        }
        BiFunction<Player, String, String> ext = externalPlaceholders;
        if (usePlaceholderApi && ext != null && viewer != null && s.indexOf('%') >= 0) {
            try {
                s = ext.apply(viewer, s);
            } catch (RuntimeException ignored) {
                // never let a third-party placeholder break a message
            }
        }
        return Text.color(s);
    }

    /**
     * Replaces plugin and PlaceholderAPI placeholders without applying colours (for commands).
     */
    public String placeholders(Player viewer, String text, Object... placeholders) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = replace(text, placeholders);
        BiFunction<Player, String, String> ext = externalPlaceholders;
        if (usePlaceholderApi && ext != null && viewer != null && s.indexOf('%') >= 0) {
            try {
                s = ext.apply(viewer, s);
            } catch (RuntimeException ignored) {
                // never let a third-party placeholder break a command
            }
        }
        return s;
    }

    public static String replace(String text, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0 || text.indexOf('{') < 0) {
            return text;
        }
        String s = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object value = placeholders[i + 1];
            s = s.replace("{" + name + "}", value == null ? "" : value.toString());
        }
        return s;
    }

    /**
     * @return the first chat line of a message, formatted (for item names, GUI titles, signs)
     */
    public String text(String key, Object... placeholders) {
        Message m = message(key);
        if (m.chat().isEmpty()) {
            return "";
        }
        return apply(null, m.chat().get(0), placeholders);
    }

    public String text(Player viewer, String key, Object... placeholders) {
        Message m = message(key, localeOf(viewer));
        if (m.chat().isEmpty()) {
            return "";
        }
        return apply(viewer, m.chat().get(0), placeholders);
    }

    /**
     * @return all chat lines of a message, formatted (for lore)
     */
    public List<String> lines(Player viewer, String key, Object... placeholders) {
        Message m = message(key, localeOf(viewer));
        if (m.chat().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(m.chat().size());
        for (String line : m.chat()) {
            out.add(apply(viewer, line, placeholders));
        }
        return out;
    }

    public boolean isEnabled(String key) {
        return !message(key).isEmpty();
    }

    /**
     * @return whether any language file (custom, player locale, selected or en_US) defines the key
     */
    public boolean exists(String key) {
        return lookup(key, null) != null;
    }

    /**
     * @return the unformatted chat lines of a message in the viewer's language (for random pools)
     */
    public List<String> rawLines(Player viewer, String key) {
        return message(key, localeOf(viewer)).chat();
    }

    // ----------------------------------------------------------------------------- sending

    public void send(CommandSender to, String key, Object... placeholders) {
        if (to == null) {
            return;
        }
        Message m = message(key, localeOf(to));
        deliver(to, m, placeholders);
    }

    public void deliver(CommandSender to, Message m, Object... placeholders) {
        if (m.isEmpty()) {
            return;
        }
        Player player = to instanceof Player p ? p : null;
        for (String line : m.chat()) {
            if (line.isEmpty()) {
                continue;
            }
            String formatted = apply(player, (m.prefix() ? prefix : "") + line, placeholders);
            to.sendMessage(formatted);
        }
        if (player != null) {
            if (!m.actionbar().isEmpty()) {
                actionBar(player, apply(player, m.actionbar(), placeholders));
            }
            if (m.hasTitle()) {
                title(player, apply(player, m.title(), placeholders), apply(player, m.subtitle(), placeholders),
                        m.fadeIn(), m.stay(), m.fadeOut());
            }
            if (!m.sound().isEmpty() && effects != null) {
                effects.playSound(player, m.sound());
            }
        }
    }

    /**
     * Sends a pre-formatted line with the prefix.
     */
    public void sendRaw(CommandSender to, String text, Object... placeholders) {
        Player player = to instanceof Player p ? p : null;
        to.sendMessage(apply(player, prefix + text, placeholders));
    }

    public void broadcast(String key, Object... placeholders) {
        Message m = message(key);
        if (m.isEmpty()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            deliver(p, localeMessage(p, key, m), placeholders);
        }
        if (!m.chat().isEmpty()) {
            deliver(Bukkit.getConsoleSender(), new Message(m.chat(), "", "", "", 0, 0, 0, "", m.prefix()), placeholders);
        }
    }

    /**
     * Broadcasts to players that pass the filter (for example only players in one world).
     */
    public void broadcast(java.util.function.Predicate<Player> filter, String key, Object... placeholders) {
        Message m = message(key);
        if (m.isEmpty()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (filter.test(p)) {
                deliver(p, localeMessage(p, key, m), placeholders);
            }
        }
        if (!m.chat().isEmpty()) {
            deliver(Bukkit.getConsoleSender(), new Message(m.chat(), "", "", "", 0, 0, 0, "", m.prefix()), placeholders);
        }
    }

    private Message localeMessage(Player p, String key, Message def) {
        String locale = localeOf(p);
        return locale == null ? def : message(key, locale);
    }

    @SuppressWarnings("deprecation")
    public void actionBar(Player player, String formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(formatted));
    }

    @SuppressWarnings("deprecation")
    public void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(Objects.requireNonNullElse(title, ""), Objects.requireNonNullElse(subtitle, ""), fadeIn, stay, fadeOut);
    }

    public String bool(boolean value) {
        return Text.color(value ? settings.yes() : settings.no());
    }

    public String duration(long millis) {
        Settings s = settings;
        return Text.formatDuration(millis, s.day(), s.hour(), s.minute(), s.second());
    }
}
