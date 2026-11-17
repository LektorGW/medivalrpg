// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import pl.medivalrpg.skarbiec.ledger.TxRecord;

/**
 * Read-only view of the daily transaction logs ({@code bank/logs/transakcje-YYYY-MM-DD.log}) written by
 * {@code LedgerStore}. Never writes, never locks the files: complete lines are parsed, a torn last line is left for the
 * next read. Parsed days are cached (closed days by size+mtime, the current day incrementally from the last byte
 * offset), at most {@link #MAX_CACHED_DAYS} days; older days are streamed without caching. Call from one worker thread.
 */
public final class FlowIndex {

    public static final String PREFIX = "transakcje-";
    public static final int MAX_CACHED_DAYS = 31;

    private static final class Day {
        long size;
        long mtime;
        long offset;
        final List<TxRecord> records = new ArrayList<>();
    }

    private final Path logs;
    private final ZoneId zone;
    private final Map<LocalDate, Day> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<LocalDate, Day> eldest) {
            return this.size() > MAX_CACHED_DAYS;
        }
    };
    private final Map<String, String> intern = new HashMap<>();

    public FlowIndex(final Path logs, final ZoneId zone) {
        this.logs = logs;
        this.zone = zone;
    }

    public ZoneId zone() {
        return this.zone;
    }

    /** Oldest day with a log file, or null. */
    public LocalDate oldestDay() throws IOException {
        LocalDate oldest = null;
        for (final LocalDate d : this.days()) {
            if (oldest == null || d.isBefore(oldest)) {
                oldest = d;
            }
        }
        return oldest;
    }

    private List<LocalDate> days() throws IOException {
        if (!Files.isDirectory(this.logs)) {
            return List.of();
        }
        final List<LocalDate> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(this.logs)) {
            for (final Path p : (Iterable<Path>) s::iterator) {
                final String n = p.getFileName().toString();
                if (n.startsWith(PREFIX) && n.endsWith(".log")) {
                    try {
                        out.add(LocalDate.parse(n.substring(PREFIX.length(), n.length() - 4)));
                    } catch (final DateTimeParseException ignored) {
                        // foreign file
                    }
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** All records with {@code from <= time <= to}, ascending by sequence number. */
    public List<TxRecord> range(final long from, final long to) throws IOException {
        final List<TxRecord> out = new ArrayList<>();
        this.forEach(from, to, out::add);
        return out;
    }

    /**
     * Streams the records with {@code from <= time <= to} in ascending sequence order (days in order, lines in commit
     * order); a sequence number already seen is skipped. Days older than the cache window are not kept in memory.
     */
    public void forEach(final long from, final long to, final java.util.function.Consumer<TxRecord> sink) throws IOException {
        final LocalDate first = Instant.ofEpochMilli(from).atZone(this.zone).toLocalDate().minusDays(1);
        final LocalDate last = Instant.ofEpochMilli(to).atZone(this.zone).toLocalDate().plusDays(1);
        final LocalDate cacheFrom = LocalDate.now(this.zone).minusDays(MAX_CACHED_DAYS - 1L);
        long lastSeq = Long.MIN_VALUE;
        for (final LocalDate d : this.days()) {
            if (d.isBefore(first) || d.isAfter(last)) {
                continue;
            }
            final List<TxRecord> records;
            if (d.isBefore(cacheFrom) && !this.cache.containsKey(d)) {
                final Day once = new Day();
                final Path file = this.logs.resolve(PREFIX + d + ".log");
                this.readFrom(file, once, Files.size(file));
                records = once.records;
            } else {
                records = this.day(d);
            }
            final List<TxRecord> sorted = new ArrayList<>(records);
            sorted.sort((a, b) -> Long.compare(a.seq(), b.seq()));
            for (final TxRecord r : sorted) {
                if (r.time() >= from && r.time() <= to && r.seq() > lastSeq) {
                    sink.accept(r);
                    lastSeq = r.seq();
                }
            }
        }
    }

    private List<TxRecord> day(final LocalDate d) throws IOException {
        final Path file = this.logs.resolve(PREFIX + d + ".log");
        if (!Files.exists(file)) {
            this.cache.remove(d);
            return List.of();
        }
        final long size = Files.size(file);
        final long mtime = Files.getLastModifiedTime(file).toMillis();
        Day day = this.cache.get(d);
        if (day != null && day.size == size && day.mtime == mtime) {
            return day.records;
        }
        if (day == null || size < day.offset) {
            day = new Day();
        }
        this.readFrom(file, day, size);
        day.size = size;
        day.mtime = mtime;
        this.cache.put(d, day);
        return day.records;
    }

    private void readFrom(final Path file, final Day day, final long size) throws IOException {
        if (size <= day.offset) {
            return;
        }
        final long length = size - day.offset;
        if (length > Integer.MAX_VALUE - 16) {
            throw new IOException("Dziennik transakcji jest zbyt duży: " + file.getFileName());
        }
        final byte[] bytes = new byte[(int) length];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(day.offset);
            raf.readFully(bytes);
        }
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                final String line = new String(bytes, start, i - start, StandardCharsets.UTF_8);
                final TxRecord r = TxRecord.decode(line);
                if (r != null) {
                    day.records.add(this.compact(r));
                }
                start = i + 1;
            }
        }
        day.offset += start; // a torn last line (no newline yet) is read again next time
    }

    private TxRecord compact(final TxRecord r) {
        return new TxRecord(r.seq(), r.time(), r.type(), this.str(r.from()), this.str(r.to()), r.amount(), r.fromAfter(), r.toAfter(),
            this.str(r.reason()));
    }

    private String str(final String s) {
        if (s == null) {
            return null;
        }
        if (this.intern.size() > 200_000) {
            this.intern.clear();
        }
        return this.intern.computeIfAbsent(s, k -> k);
    }
}
