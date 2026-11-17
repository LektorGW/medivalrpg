// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.util.List;
import java.util.Locale;

/** Small formatting helpers of the tab list (TPS, ping, game clock, rotating tip). Pure logic, no Bukkit. */
public final class TabFormat {

    /** TPS at or above this is shown green. */
    public static final double TPS_GOOD = 18.0;
    /** TPS at or above this (and below {@link #TPS_GOOD}) is shown yellow, below it red. */
    public static final double TPS_WARN = 15.0;
    /** Ping below this (ms) is green. */
    public static final int PING_GOOD = 100;
    /** Ping below this (ms, and at least {@link #PING_GOOD}) is yellow, above it red. */
    public static final int PING_WARN = 250;

    private TabFormat() {
    }

    /** Colour level: 0 good (green), 1 warning (yellow), 2 bad (red). */
    public static int tpsLevel(final double tps) {
        return tps >= TPS_GOOD ? 0 : tps >= TPS_WARN ? 1 : 2;
    }

    public static int pingLevel(final int ping) {
        return ping < PING_GOOD ? 0 : ping < PING_WARN ? 1 : 2;
    }

    /** MiniMessage colour tag of a level. */
    public static String levelColor(final int level) {
        return switch (level) {
            case 0 -> "green";
            case 1 -> "yellow";
            default -> "red";
        };
    }

    /** {@code 19,8} — one decimal with a Polish comma, capped at 20 (Paper reports slightly above 20 after lag). */
    public static String tps(final double tps) {
        final double v = Double.isFinite(tps) ? Math.max(0.0, Math.min(20.0, tps)) : 0.0;
        return String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    /** {@code 42 ms}; negative values (unknown) as {@code ? ms}. */
    public static String ping(final int ping) {
        return (ping < 0 ? "?" : Integer.toString(ping)) + " ms";
    }

    /** World time (0..23999 ticks, 0 = 06:00) as {@code HH:mm}. */
    public static String gameTime(final long worldTime) {
        final long t = Math.floorMod(worldTime, 24000L);
        final long hours = (t / 1000L + 6L) % 24L;
        final long minutes = t % 1000L * 60L / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    /** Day number of the world, 1-based, from {@code World#getFullTime()}. */
    public static long gameDay(final long fullTime) {
        return Math.max(0L, fullTime) / 24000L + 1L;
    }

    /**
     * Index of the tip shown at {@code nowMillis}: every player sees the same tip, it changes every {@code periodSeconds}.
     * -1 when there are no tips.
     */
    public static int tipIndex(final long nowMillis, final long periodSeconds, final int count) {
        if (count <= 0) {
            return -1;
        }
        final long period = Math.max(1L, periodSeconds) * 1000L;
        return (int) Math.floorMod(Math.floorDiv(nowMillis, period), (long) count);
    }

    /** The tip for {@code nowMillis}, {@code null} without tips. */
    public static String tip(final List<String> tips, final long nowMillis, final long periodSeconds) {
        final int i = tipIndex(nowMillis, periodSeconds, tips.size());
        return i < 0 ? null : tips.get(i);
    }

    /** URL for display (the tab list is not clickable): without {@code https://} / {@code http://} and trailing slash. */
    public static String displayUrl(final String url) {
        if (url == null) {
            return "";
        }
        String u = url.strip();
        if (u.regionMatches(true, 0, "https://", 0, 8)) {
            u = u.substring(8);
        } else if (u.regionMatches(true, 0, "http://", 0, 7)) {
            u = u.substring(7);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Text cut to {@code max} characters with an ellipsis (code points are not split). */
    public static String shorten(final String text, final int max) {
        if (text == null) {
            return "";
        }
        final String t = text.strip();
        if (max <= 0 || t.codePointCount(0, t.length()) <= max) {
            return t;
        }
        final int end = t.offsetByCodePoints(0, Math.max(1, max - 1));
        return t.substring(0, end).stripTrailing() + "…";
    }
}
