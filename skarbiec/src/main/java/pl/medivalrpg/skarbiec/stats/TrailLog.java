// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Append-only daily log of physical coin stack movements ({@code stats/ruchy/ruchy-YYYY-MM-DD.log}, pure Java).
 * {@link #append} only enqueues; one daemon writer thread writes and flushes about once a second. Lines are
 * tab-separated, one event each; old days are deleted after {@code keepDays}.
 */
public final class TrailLog {

    public static final String PREFIX = "ruchy-";
    public static final Map<String, String> KIND_LABELS;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("PICKUP", "Podniesienie z ziemi");
        m.put("DROP", "Upuszczenie");
        m.put("DEATH", "Wypadnięcie po śmierci");
        m.put("CONTAINER_IN", "Włożenie do pojemnika");
        m.put("CONTAINER_OUT", "Wyjęcie z pojemnika");
        m.put("HOPPER", "Lejek / przenoszenie automatyczne");
        m.put("HOPPER_PICKUP", "Lejek zebrał z ziemi");
        m.put("DEPOSIT", "Wpłata monet do banku");
        m.put("WITHDRAW", "Wypłata monet z banku");
        m.put("EVENT", "Zdarzenie Skarbca");
        KIND_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /** One movement. {@code player}/{@code playerId} may be null (hoppers); coordinates may be null. */
    public record Event(long time, String kind, String playerId, String player, long amount, int stacks, String world, Integer x,
                        Integer y, Integer z, String target, String note) {

        String encode() {
            return Instant.ofEpochMilli(this.time) + "\t" + this.kind + "\t" + n(this.playerId) + "\t" + n(this.player) + "\t" + this.amount
                + "\t" + this.stacks + "\t" + n(this.world) + "\t" + n(this.x) + "\t" + n(this.y) + "\t" + n(this.z) + "\t" + clean(this.target)
                + "\t" + clean(this.note);
        }

        static Event decode(final String line) {
            final String[] p = line.split("\t", -1);
            if (p.length != 12) {
                return null;
            }
            try {
                return new Event(Instant.parse(p[0]).toEpochMilli(), p[1], s(p[2]), s(p[3]), Long.parseLong(p[4]), Integer.parseInt(p[5]),
                    s(p[6]), i(p[7]), i(p[8]), i(p[9]), p[10], p[11]);
            } catch (final RuntimeException e) {
                return null;
            }
        }

        public Map<String, Object> toMap() {
            return FlowQuery.map("time", this.time, "kind", this.kind, "kindLabel", KIND_LABELS.getOrDefault(this.kind, this.kind),
                "player", this.player, "playerId", this.playerId, "amount", this.amount, "stacks", this.stacks, "world", this.world,
                "x", this.x, "y", this.y, "z", this.z, "target", this.target, "note", this.note);
        }

        private static String n(final Object o) {
            return o == null ? "-" : clean(o.toString());
        }

        private static String s(final String v) {
            return "-".equals(v) || v.isEmpty() ? null : v;
        }

        private static Integer i(final String v) {
            return "-".equals(v) ? null : Integer.valueOf(v);
        }

        private static String clean(final String v) {
            if (v == null) {
                return "";
            }
            final String c = v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            return c.length() > 160 ? c.substring(0, 160) : c;
        }
    }

    private static final Object POISON = new Object();

    private final Path dir;
    private final ZoneId zone;
    private final int keepDays;
    private final Logger logger;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(100_000);
    private final Thread writer;
    private final java.util.concurrent.atomic.LongAdder dropped = new java.util.concurrent.atomic.LongAdder();
    private final long since;

    public TrailLog(final Path dir, final ZoneId zone, final int keepDays, final Logger logger) {
        this.dir = dir;
        this.zone = zone;
        this.keepDays = Math.max(1, keepDays);
        this.logger = logger;
        this.since = System.currentTimeMillis();
        this.writer = new Thread(this::run, "Skarbiec-Coin-Trail");
        this.writer.setDaemon(true);
        this.writer.setPriority(Thread.MIN_PRIORITY);
    }

    public void start() {
        this.writer.start();
    }

    public long dropped() {
        return this.dropped.sum();
    }

    public long since() {
        return this.since;
    }

    /** Counts an event that was not written (rate limit). */
    public void countDropped() {
        this.dropped.increment();
    }

    /** Any thread; never blocks. */
    public void append(final Event e) {
        if (!this.queue.offer(e)) {
            this.dropped.increment();
        }
    }

    public void close() {
        this.queue.offer(POISON);
        try {
            this.writer.join(TimeUnit.SECONDS.toMillis(5));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        BufferedWriter out = null;
        LocalDate day = null;
        final List<Object> batch = new ArrayList<>();
        try {
            while (true) {
                batch.clear();
                final Object first = this.queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    this.queue.drainTo(batch, 4096);
                }
                boolean stop = false;
                for (final Object o : batch) {
                    if (o == POISON) {
                        stop = true;
                        continue;
                    }
                    final Event e = (Event) o;
                    final LocalDate d = Instant.ofEpochMilli(e.time()).atZone(this.zone).toLocalDate();
                    try {
                        if (out == null || !d.equals(day)) {
                            if (out != null) {
                                out.close();
                            }
                            Files.createDirectories(this.dir);
                            out = Files.newBufferedWriter(this.dir.resolve(PREFIX + d + ".log"), StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            day = d;
                            this.deleteOld();
                        }
                        out.write(e.encode());
                        out.write('\n');
                    } catch (final IOException ex) {
                        this.dropped.increment();
                        this.logger.log(Level.WARNING, "Nie udało się zapisać ruchu monet", ex);
                        out = null;
                    }
                }
                if (out != null) {
                    try {
                        out.flush();
                    } catch (final IOException ex) {
                        this.logger.log(Level.WARNING, "Nie udało się zapisać ruchów monet", ex);
                        out = null;
                    }
                }
                if (stop) {
                    break;
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException ignored) {
                    // closing on shutdown
                }
            }
        }
    }

    private void deleteOld() {
        final LocalDate limit = LocalDate.now(this.zone).minusDays(this.keepDays);
        for (final LocalDate d : this.days()) {
            if (d.isBefore(limit)) {
                try {
                    Files.deleteIfExists(this.dir.resolve(PREFIX + d + ".log"));
                } catch (final IOException ignored) {
                    // next day retries
                }
            }
        }
    }

    private List<LocalDate> days() {
        final List<LocalDate> out = new ArrayList<>();
        if (!Files.isDirectory(this.dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(this.dir)) {
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
        } catch (final IOException ignored) {
            // empty
        }
        out.sort(null);
        return out;
    }

    /** Streams logged events with {@code from <= time <= to} in time order (reader thread; complete lines only). */
    public void forEach(final long from, final long to, final Consumer<Event> sink) throws IOException {
        final LocalDate first = Instant.ofEpochMilli(from).atZone(this.zone).toLocalDate().minusDays(1);
        final LocalDate last = Instant.ofEpochMilli(to).atZone(this.zone).toLocalDate().plusDays(1);
        for (final LocalDate d : this.days()) {
            if (d.isBefore(first) || d.isAfter(last)) {
                continue;
            }
            final byte[] bytes = Files.readAllBytes(this.dir.resolve(PREFIX + d + ".log"));
            int start = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    final Event e = Event.decode(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                    if (e != null && e.time() >= from && e.time() <= to) {
                        sink.accept(e);
                    }
                    start = i + 1;
                }
            }
        }
    }
}
