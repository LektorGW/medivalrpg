// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Settings of the extended tab list ({@code tablist.style} and the {@code tablist.extended} section of config.yml).
 * Every key is read from the server file first and falls back to the jar's config.yml (Bukkit configuration
 * defaults), then to the constant below — so an old {@code plugins/Kraje/config.yml} without these keys gets the
 * bundled extended layout. Immutable, safe from any thread.
 */
public final class TablistSettings {

    public static final String STYLE_PATH = "tablist.style";
    public static final String NAME_FORMAT_PATH = "tablist.extended.name-format";
    public static final String DEFAULT_NAME_FORMAT = "<afk><rank_icon><era_icon><color>[<country>]</color> <white><player>";
    public static final String DEFAULT_NO_COUNTRY_FORMAT = "<afk><gray>[Dzicz]</gray> <white><player>";
    public static final String DEFAULT_PANEL_URL = "https://panel.example.workers.dev/";
    static final String DEFAULT_DATE_PATTERN = "d MMMM yyyy";
    static final Locale POLISH = Locale.forLanguageTag("pl-PL");

    /** {@code tablist.style}: extended (new layout) or classic (old {@code header}/{@code footer}/{@code format}). */
    public final boolean extended;
    public final long refreshTicks;
    public final long cacheMillis;
    public final String nameFormat;
    public final String noCountryNameFormat;
    public final String afkTag;
    /** 0 = AFK detection off. */
    public final long afkMillis;
    public final List<String> header;
    public final List<String> footer;
    public final String panelUrl;
    public final ZoneId zone;
    public final DateTimeFormatter dateFormat;
    /** Season id ({@code wiosna}, {@code lato}, {@code jesien}, {@code zima}) → MiniMessage icon. */
    public final Map<String, String> seasonIcons;
    public final boolean news;
    public final int newsMaxLength;
    public final long tipSeconds;
    public final List<String> tips;

    public TablistSettings(final Configuration c) {
        final Fallback r = new Fallback(c);
        this.extended = isExtendedStyle(r.str(STYLE_PATH, "extended"));
        this.refreshTicks = Math.max(20L, Math.min(1200L, r.lng("tablist.extended.refresh-ticks", 40L)));
        this.cacheMillis = Math.max(1L, Math.min(300L, r.lng("tablist.extended.cache-seconds", 5L))) * 1000L;
        this.nameFormat = r.str(NAME_FORMAT_PATH, DEFAULT_NAME_FORMAT);
        this.noCountryNameFormat = r.str("tablist.extended.no-country-name-format", DEFAULT_NO_COUNTRY_FORMAT);
        this.afkTag = r.str("tablist.extended.afk-tag", "<gray>[AFK]</gray> ");
        this.afkMillis = Math.max(0L, r.lng("tablist.extended.afk-seconds", 300L)) * 1000L;
        this.header = r.list("tablist.extended.header", List.of("<gold><bold>MEDIVALRPG</bold></gold>"));
        this.footer = r.list("tablist.extended.footer", List.of("<gray>Kraj: <country></gray>"));
        this.panelUrl = r.str("tablist.extended.panel-url", DEFAULT_PANEL_URL).trim();
        ZoneId zone;
        try {
            zone = ZoneId.of(r.str("tablist.extended.time-zone", "Europe/Warsaw").trim());
        } catch (final DateTimeException e) {
            zone = ZoneId.of("Europe/Warsaw");
        }
        this.zone = zone;
        DateTimeFormatter date;
        try {
            date = DateTimeFormatter.ofPattern(r.str("tablist.extended.date-format", DEFAULT_DATE_PATTERN), POLISH);
        } catch (final IllegalArgumentException e) {
            date = DateTimeFormatter.ofPattern(DEFAULT_DATE_PATTERN, POLISH);
        }
        this.dateFormat = date;
        this.seasonIcons = r.map("tablist.extended.season-icons");
        this.news = r.bool("tablist.extended.news", true);
        this.newsMaxLength = (int) Math.max(8L, Math.min(120L, r.lng("tablist.extended.news-max-length", 48L)));
        this.tipSeconds = Math.max(5L, r.lng("tablist.extended.tip-seconds", 60L));
        this.tips = r.list("tablist.extended.tips", List.of()).stream().filter(s -> !s.isBlank()).toList();
    }

    /** {@code extended} / {@code rozbudowana} (default for anything unknown) versus {@code classic} / {@code klasyczna}. */
    public static boolean isExtendedStyle(final String style) {
        if (style == null) {
            return true;
        }
        final String s = style.trim().toLowerCase(Locale.ROOT);
        return !(s.equals("classic") || s.equals("klasyczna") || s.equals("klasyczny"));
    }

    /** Season icon (MiniMessage) of a season id, empty when not configured. */
    public String seasonIcon(final String seasonId) {
        return seasonId == null ? "" : this.seasonIcons.getOrDefault(seasonId.toLowerCase(Locale.ROOT), "");
    }

    /** Reads a key from the server file, falling back to the jar defaults, then to the given constant. */
    private static final class Fallback {
        private final Configuration c;

        Fallback(final Configuration c) {
            this.c = c;
        }

        private Object raw(final String path) {
            Object v = this.c.get(path, null);
            if (v == null && this.c.getDefaults() != null) {
                v = this.c.getDefaults().get(path, null);
            }
            return v;
        }

        String str(final String path, final String def) {
            final Object v = this.raw(path);
            return v == null || v instanceof ConfigurationSection ? def : String.valueOf(v);
        }

        long lng(final String path, final long def) {
            final Object v = this.raw(path);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (final NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }

        boolean bool(final String path, final boolean def) {
            final Object v = this.raw(path);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                return Boolean.parseBoolean(s.trim());
            }
            return def;
        }

        List<String> list(final String path, final List<String> def) {
            final Object v = this.raw(path);
            if (v instanceof List<?> l) {
                return l.stream().map(o -> o == null ? "" : String.valueOf(o)).toList();
            }
            if (v instanceof String s) {
                return List.of(s.split("\n", -1));
            }
            return def;
        }

        Map<String, String> map(final String path) {
            final Object v = this.raw(path);
            final Map<String, String> out = new HashMap<>();
            if (v instanceof ConfigurationSection s) {
                for (final String key : s.getKeys(false)) {
                    final Object value = s.get(key);
                    if (value != null && !(value instanceof ConfigurationSection)) {
                        out.put(key.toLowerCase(Locale.ROOT), String.valueOf(value));
                    }
                }
            }
            return Map.copyOf(out);
        }
    }
}
