// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.log;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Paper's {@code logs/latest.log} lines ({@code [HH:mm:ss] [Thread/LEVEL]: [Plugin] message}) into error
 * events; stack-trace lines after a header are attached to it (pure, unit tested). JUL {@code SEVERE} is written
 * by Log4j as {@code ERROR}, so ERROR/FATAL are the "SEVERE" events of the report.
 */
public final class LogParser {

    /** One ERROR/FATAL entry with its first exception line (if any). */
    public record Event(long time, String level, String plugin, String message, String detail) {
    }

    /** File format {@code [HH:mm:ss] [Thread/LEVEL]: msg}; console format {@code [HH:mm:ss LEVEL]: msg} also accepted. */
    private static final Pattern HEADER = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})(?:] \\[([^\\]]*)/| ())([A-Z]+)]: ?(.*)$");
    private static final Pattern PLUGIN_TAG = Pattern.compile("^\\[([A-Za-z0-9_.\\- ]{1,40})] ?(.*)$", Pattern.DOTALL);
    private static final Pattern PLUGIN_IN_TEXT = Pattern.compile(
        "(?:to|for|enabling|disabling|loading|initializing) ([A-Za-z0-9_\\-]{2,40}) v\\d[^\\s]*");
    private static final Pattern FRAME = Pattern.compile("(?:^|[\\s/])pl\\.medivalrpg\\.([a-z0-9]+)\\.");
    private static final Pattern NOT_RESPONDED = Pattern.compile("has not responded for (\\d+) seconds");
    private static final Pattern EXCEPTION_LINE = Pattern.compile("^(?:Caused by: )?([a-zA-Z_$][\\w$]*\\.)+[\\w$]*(Exception|Error|Throwable)\\b.*");

    public static final String SERVER = "Serwer";
    /** Paper Watchdog writes every line of its thread dump as a separate ERROR: one event per dump. */
    public static final String WATCHDOG = "Serwer (watchdog)";

    private final ZoneId zone;
    private Pending pending;

    private static final class Pending {
        long time;
        String level;
        String plugin;
        String message;
        String detail;
        String framePlugin;
        boolean watchdog;
    }

    public LogParser(final ZoneId zone) {
        this.zone = zone;
    }

    /**
     * Feeds complete lines (in order). Returns the ERROR/FATAL events finished by these lines; the last one may
     * still be waiting for stack-trace lines — call {@link #flush()} at the end of a read.
     */
    public List<Event> feed(final List<String> lines, final long now) {
        final List<Event> out = new ArrayList<>();
        for (final String raw : lines) {
            final String line = stripAnsi(raw);
            final Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                final String thread = m.group(4) == null ? "" : m.group(4);
                final String level = m.group(6);
                if (thread.contains("Watchdog") && ("ERROR".equals(level) || "FATAL".equals(level))) {
                    this.watchdog(m, now, out);
                    continue;
                }
                this.finish(out);
                if (!"ERROR".equals(level) && !"FATAL".equals(level)) {
                    continue;
                }
                final Pending p = new Pending();
                p.time = this.timeOf(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), now);
                p.level = level;
                String msg = m.group(7);
                final Matcher tag = PLUGIN_TAG.matcher(msg);
                if (tag.matches()) {
                    p.plugin = tag.group(1).trim();
                    msg = tag.group(2);
                } else {
                    final Matcher in = PLUGIN_IN_TEXT.matcher(msg);
                    p.plugin = in.find() ? in.group(1) : null;
                }
                p.message = msg.trim();
                this.pending = p;
            } else if (this.pending != null) {
                final String t = line.strip();
                if (t.isEmpty()) {
                    continue;
                }
                if (this.pending.detail == null && EXCEPTION_LINE.matcher(t).matches()) {
                    this.pending.detail = t;
                }
                if (this.pending.framePlugin == null) {
                    final Matcher f = FRAME.matcher(line);
                    if (f.find()) {
                        this.pending.framePlugin = capitalize(f.group(1));
                    }
                }
                if (this.pending.message.isEmpty()) {
                    this.pending.message = t;
                }
            }
        }
        return out;
    }

    private void watchdog(final Matcher m, final long now, final List<Event> out) {
        if (this.pending == null || !this.pending.watchdog) {
            this.finish(out);
            final Pending p = new Pending();
            p.time = this.timeOf(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), now);
            p.level = m.group(6);
            p.plugin = WATCHDOG;
            p.message = "Serwer nie odpowiadał — zrzut wątków Paper Watchdog";
            p.watchdog = true;
            this.pending = p;
        }
        final String text = m.group(7);
        final Matcher nr = NOT_RESPONDED.matcher(text);
        if (nr.find()) {
            this.pending.message = "Serwer nie odpowiadał przez " + nr.group(1) + " s — zrzut wątków Paper Watchdog";
        }
        if (text.contains("has stopped responding")) {
            this.pending.message = "Serwer przestał odpowiadać (Paper Watchdog)";
        }
        if (this.pending.framePlugin == null) {
            final Matcher f = FRAME.matcher(text);
            if (f.find()) {
                this.pending.framePlugin = capitalize(f.group(1));
            }
        }
    }

    /** Finishes the event still waiting for stack-trace lines. */
    public List<Event> flush() {
        final List<Event> out = new ArrayList<>();
        this.finish(out);
        return out;
    }

    private void finish(final List<Event> out) {
        final Pending p = this.pending;
        if (p == null) {
            return;
        }
        this.pending = null;
        if (p.watchdog) {
            out.add(new Event(p.time, p.level, WATCHDOG, p.message,
                p.framePlugin == null ? null : "Wątek główny w kodzie pluginu: " + p.framePlugin));
            return;
        }
        String plugin = p.plugin;
        if (plugin == null || plugin.isBlank()) {
            plugin = p.framePlugin != null ? p.framePlugin : SERVER;
        }
        String message = p.message;
        if (message.isEmpty() && p.detail != null) {
            message = p.detail;
        }
        out.add(new Event(p.time, p.level, plugin, message, p.detail));
    }

    private long timeOf(final int h, final int m, final int s, final long now) {
        final ZonedDateTime current = java.time.Instant.ofEpochMilli(now).atZone(this.zone);
        final LocalDate day = current.toLocalDate();
        ZonedDateTime at = ZonedDateTime.of(day, LocalTime.of(h % 24, m % 60, s % 60), this.zone);
        if (at.isAfter(current.plusMinutes(1))) {
            at = at.minusDays(1);
        }
        return at.toInstant().toEpochMilli();
    }

    static String stripAnsi(final String s) {
        return s.indexOf('\u001b') < 0 ? s : s.replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "");
    }

    private static String capitalize(final String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * Deduplication key part: numbers, UUIDs, long hex and coordinates are replaced, so the same error at another
     * place or amount has the same signature.
     */
    public static String normalize(final String message) {
        String s = message == null ? "" : message;
        s = s.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "‹uuid›");
        s = s.replaceAll("\\b[0-9a-fA-F]{12,}\\b", "‹hex›");
        s = s.replaceAll("-?\\d+(?:[.,]\\d+)?", "#");
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
