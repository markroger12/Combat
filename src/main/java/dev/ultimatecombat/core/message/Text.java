package dev.ultimatecombat.core.message;

import dev.ultimatecombat.core.scheduler.Platform;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colour formatting shared by every message, item name and lore line.
 *
 * <p>Supported formats:</p>
 * <ul>
 *     <li>Legacy codes: {@code &a}, {@code &l}, ...</li>
 *     <li>Hex colours: {@code &#FF5555}, {@code {#FF5555}}, {@code <#FF5555>}</li>
 *     <li>MiniMessage tags ({@code <gradient:#f00:#00f>}, {@code <bold>}) on Paper/Folia when enabled</li>
 * </ul>
 */
public final class Text {

    private static final Pattern HEX = Pattern.compile("(?:&#|\\{#|<#)([0-9a-fA-F]{6})[}>]?");
    private static final Pattern MINI_TAG = Pattern.compile("<(/?)(gradient|rainbow|color|bold|italic|underlined|strikethrough|obfuscated|reset|"
            + "red|green|blue|yellow|gold|aqua|white|black|gray|grey|dark_[a-z]+|light_purple|hover|click|b|i|u|st|transition)[:>]");

    public enum Mode { AUTO, LEGACY, MINIMESSAGE }

    private static volatile Mode mode = Mode.AUTO;
    private static final boolean MINI_AVAILABLE = Platform.classExists("net.kyori.adventure.text.minimessage.MiniMessage")
            && Platform.classExists("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");

    private Text() {
    }

    public static void setMode(Mode newMode) {
        mode = newMode;
    }

    public static boolean miniMessageAvailable() {
        return MINI_AVAILABLE;
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return input == null ? "" : input;
        }
        String s = input;
        if (MINI_AVAILABLE && (mode == Mode.MINIMESSAGE || mode == Mode.AUTO && MINI_TAG.matcher(s).find())) {
            try {
                s = MiniMessageBridge.toLegacy(s);
            } catch (RuntimeException | LinkageError ignored) {
                // fall back to legacy parsing below
            }
        }
        s = hex(s);
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static List<String> color(List<String> input) {
        List<String> out = new ArrayList<>(input.size());
        for (String s : input) {
            out.add(color(s));
        }
        return out;
    }

    private static String hex(String s) {
        Matcher m = HEX.matcher(s);
        if (!m.find()) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                rep.append('§').append(Character.toLowerCase(c));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String strip(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.stripColor(color(input));
    }

    /**
     * Builds a progress bar such as {@code ||||||||||} with two colours.
     *
     * @param progress fraction between 0 and 1 of the filled part
     */
    public static String progressBar(double progress, int length, String filledChar, String emptyChar,
                                     String filledColor, String emptyColor) {
        double p = Math.max(0, Math.min(1, progress));
        int filled = (int) Math.round(p * length);
        StringBuilder sb = new StringBuilder(length * 3 + 8);
        sb.append(filledColor);
        sb.append(filledChar.repeat(filled));
        sb.append(emptyColor);
        sb.append(emptyChar.repeat(Math.max(0, length - filled)));
        return color(sb.toString());
    }

    /**
     * Formats seconds as text according to a pattern-free style used by timers: 5, 5.4, 1:05.
     */
    public static String formatSeconds(long millis, boolean decimals) {
        if (millis < 0) {
            millis = 0;
        }
        if (decimals) {
            double secs = millis / 1000.0;
            return String.format(java.util.Locale.ROOT, "%.1f", secs);
        }
        return Long.toString((millis + 999) / 1000);
    }

    /**
     * Formats a long duration into "1d 2h 3m 4s" style text using configurable unit labels.
     */
    public static String formatDuration(long millis, String d, String h, String m, String s) {
        long total = Math.max(0, millis) / 1000;
        long days = total / 86400;
        long hours = (total % 86400) / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(d).append(' ');
        if (hours > 0) sb.append(hours).append(h).append(' ');
        if (minutes > 0) sb.append(minutes).append(m).append(' ');
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append(s);
        return sb.toString().trim();
    }
}
