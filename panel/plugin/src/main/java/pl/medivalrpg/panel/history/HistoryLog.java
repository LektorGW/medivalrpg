// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hourly samples of one country ({@code plugins/Panel/history/<id>.json}): treasury, population, soldiers, chunks,
 * food. A missing measure (plugin not installed) is null. Points are kept sorted by time, one per hour. Pure.
 */
public final class HistoryLog {

    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    /** One sample; nullable measures are absent in the file. */
    public record Point(long t, Long treasury, Integer population, Integer soldiers, Integer chunks, Long food) {
    }

    /** File format. */
    static final class FileData {
        String id;
        String name;
        long intervalMs = HOUR;
        List<Point> points = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final UUID id;
    private String name;
    private final List<Point> points = new ArrayList<>();

    public HistoryLog(final UUID id, final String name) {
        this.id = id;
        this.name = name;
    }

    public UUID id() {
        return this.id;
    }

    /** Hour start of {@code time}. */
    public static long hourOf(final long time) {
        return Math.floorDiv(time, HOUR) * HOUR;
    }

    /** Adds a sample at its hour (replacing a sample of the same hour) and drops samples older than {@code keepMs}. */
    public synchronized void add(final Point p, final long now, final long keepMs, final String name) {
        if (name != null) {
            this.name = name;
        }
        final Point aligned = new Point(hourOf(p.t()), p.treasury(), p.population(), p.soldiers(), p.chunks(), p.food());
        this.points.removeIf(x -> x.t() == aligned.t() || now - x.t() > keepMs);
        int i = this.points.size();
        while (i > 0 && this.points.get(i - 1).t() > aligned.t()) {
            i--;
        }
        this.points.add(i, aligned);
    }

    /** Samples with {@code t >= from}, oldest first, at most {@code max} (the newest ones). */
    public synchronized List<Point> since(final long from, final int max) {
        final List<Point> out = new ArrayList<>();
        for (final Point p : this.points) {
            if (p.t() >= from) {
                out.add(p);
            }
        }
        return List.copyOf(out.subList(Math.max(0, out.size() - Math.max(0, max)), out.size()));
    }

    public synchronized long lastTime() {
        return this.points.isEmpty() ? 0 : this.points.get(this.points.size() - 1).t();
    }

    public synchronized int size() {
        return this.points.size();
    }

    public synchronized String toJson() {
        final FileData d = new FileData();
        d.id = this.id.toString();
        d.name = this.name;
        d.points = new ArrayList<>(this.points);
        return GSON.toJson(d);
    }

    /** Parses a file; a broken file gives an empty log (the caller keeps a copy). */
    public static HistoryLog fromJson(final UUID id, final String json) {
        final HistoryLog log = new HistoryLog(id, null);
        try {
            final FileData d = GSON.fromJson(json, FileData.class);
            if (d != null && d.points != null) {
                log.name = d.name;
                for (final Point p : d.points) {
                    if (p != null) {
                        log.points.add(new Point(hourOf(p.t()), p.treasury(), p.population(), p.soldiers(), p.chunks(), p.food()));
                    }
                }
                log.points.sort((a, b) -> Long.compare(a.t(), b.t()));
                for (int i = log.points.size() - 1; i > 0; i--) {
                    if (log.points.get(i).t() == log.points.get(i - 1).t()) {
                        log.points.remove(i - 1);
                    }
                }
            }
        } catch (final JsonParseException | IllegalStateException e) {
            return new HistoryLog(id, null);
        }
        return log;
    }
}
