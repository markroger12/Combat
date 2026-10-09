package dev.ultimatecombat.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Number parsing and formatting helpers.
 */
public final class Numbers {

    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() ->
            new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)));
    private static final ThreadLocal<DecimalFormat> TWO = ThreadLocal.withInitial(() ->
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)));

    private Numbers() {
    }

    public static Double parseDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(s.trim().replace(",", ""));
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return parseSuffixed(s);
        }
    }

    /**
     * Parses values like 10k, 2.5m, 1b.
     */
    private static Double parseSuffixed(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        char last = t.charAt(t.length() - 1);
        double factor = switch (last) {
            case 'k' -> 1_000d;
            case 'm' -> 1_000_000d;
            case 'b' -> 1_000_000_000d;
            default -> 0;
        };
        if (factor == 0) {
            return null;
        }
        try {
            return Double.parseDouble(t.substring(0, t.length() - 1)) * factor;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer parseInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String money(double value) {
        return MONEY.get().format(value);
    }

    public static String twoDecimals(double value) {
        return TWO.get().format(value);
    }

    public static double round(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return BigDecimal.valueOf(value).setScale(Math.max(0, decimals), RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * @param chance fraction between 0 and 1
     */
    public static boolean roll(double chance) {
        if (chance >= 1) {
            return true;
        }
        if (chance <= 0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < chance;
    }
}
