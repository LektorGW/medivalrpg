// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Crash-safe persistence of the {@link Ledger} (pure Java + Gson, no Bukkit).
 *
 * <ul>
 *   <li>{@code journal.log} — write-ahead log: every committed operation is appended (and, with {@code fsync},
 *       forced to disk) by a dedicated writer thread, in commit order. Truncated after each snapshot.</li>
 *   <li>{@code accounts.json} — periodic snapshot (balances + last sequence number), written to a temp file,
 *       previous version kept as {@code accounts.json.bak}, then moved into place atomically.</li>
 *   <li>{@code logs/transakcje-YYYY-MM-DD.log} — the same lines as the journal, kept for {@code keepDays}
 *       days (human readable transaction log, {@code /skarbiec log}); also a second recovery source.</li>
 *   <li>{@code logs/zdarzenia-YYYY-MM-DD.log} — audit lines for coin events that are not account operations
 *       (merchant payouts in coins, shop sales, trades, admin coin grants).</li>
 * </ul>
 *
 * <p>Loading = snapshot (or its backup) + replay of every logged operation with a higher sequence number.
 * Replay is idempotent (it sets the logged "balance after" values), so a crash at any point — mid-line,
 * between snapshot and truncation, before the first snapshot — loses at most operations that were never
 * written. Main-thread callers never wait for disk: {@link #record} only enqueues.</p>
 */
public final class LedgerStore implements Ledger.Sink {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String TX_PREFIX = "transakcje-";
    private static final String AUDIT_PREFIX = "zdarzenia-";
    private static final String SUFFIX = ".log";
    private static final Object POISON = new Object();

    private record SnapshotTask(Map<String, Long> balances, long seq) {
    }

    private record AuditLine(long time, String text) {
    }

    /** Result of {@link #load}. */
    public record LoadResult(long snapshotSeq, int replayed, long lastSeq, int skippedLines, boolean usedBackup,
                             List<Long> gaps) {
    }

    private final Path dir;
    private final Path snapshot;
    private final Path backup;
    private final Path temp;
    private final Path journal;
    private final Path logs;
    private final Logger logger;
    private final ZoneId zone;
    private final boolean fsync;
    private final int keepDays;

    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Object lifecycle = new Object();
    private final Object fileLock = new Object();
    private Thread writer;
    private volatile boolean closed;
    private volatile Throwable writerFailure;
    private record DurabilityBarrier(java.util.concurrent.CompletableFuture<Void> result) {}

    // writer-thread state
    private FileChannel journalChannel;
    private BufferedWriter txLog;
    private LocalDate txLogDay;
    private BufferedWriter auditLog;
    private LocalDate auditLogDay;

    public LedgerStore(final Path dir, final Logger logger, final ZoneId zone, final boolean fsync, final int keepDays) {
        this.dir = dir;
        this.snapshot = dir.resolve("accounts.json");
        this.backup = dir.resolve("accounts.json.bak");
        this.temp = dir.resolve("accounts.json.tmp");
        this.journal = dir.resolve("journal.log");
        this.logs = dir.resolve("logs");
        this.logger = logger;
        this.zone = zone;
        this.fsync = fsync;
        this.keepDays = Math.max(1, keepDays);
    }

    // =================================================================== loading

    /** Restores the ledger from disk (call once, before {@link #start()}). Throws when the data is unreadable. */
    public LoadResult load(final Ledger ledger) throws IOException {
        Files.createDirectories(this.dir);
        Files.createDirectories(this.logs);
        Map<String, Long> balances = new HashMap<>();
        long snapSeq = 0;
        boolean usedBackup = false;
        if (Files.exists(this.snapshot)) {
            try {
                final var s = readSnapshot(this.snapshot);
                balances = s.balances();
                snapSeq = s.seq();
            } catch (final IOException | RuntimeException e) {
                if (!Files.exists(this.backup)) {
                    throw new IOException("accounts.json jest uszkodzony i brak kopii accounts.json.bak", e);
                }
                this.logger.log(Level.SEVERE, "accounts.json jest uszkodzony — używam accounts.json.bak i dziennika", e);
                Files.copy(this.snapshot, this.dir.resolve("accounts.json.broken-" + System.currentTimeMillis()));
                final var s = readSnapshot(this.backup);
                balances = s.balances();
                snapSeq = s.seq();
                usedBackup = true;
            }
        } else if (Files.exists(this.backup)) {
            this.logger.warning("Brak accounts.json — wczytuję accounts.json.bak i dziennik");
            final var s = readSnapshot(this.backup);
            balances = s.balances();
            snapSeq = s.seq();
            usedBackup = true;
        }

        final TreeMap<Long, TxRecord> records = new TreeMap<>();
        final int[] skipped = {0};
        this.collect(this.journal, records, skipped);
        for (final Path file : this.logFiles(TX_PREFIX)) { // newest first
            final long minSeq = this.collect(file, records, skipped);
            if (minSeq <= snapSeq) {
                break; // older files only hold operations already in the snapshot
            }
        }

        long last = snapSeq;
        int replayed = 0;
        final List<Long> gaps = new ArrayList<>();
        for (final TxRecord r : records.tailMap(snapSeq, false).values()) {
            if (r.seq() != last + 1) {
                gaps.add(last + 1);
            }
            if (r.from() != null && r.fromAfter() != null) {
                balances.put(r.from(), r.fromAfter());
            }
            if (r.to() != null && r.toAfter() != null) {
                balances.put(r.to(), r.toAfter());
            }
            last = r.seq();
            replayed++;
        }
        ledger.restore(balances, last);
        return new LoadResult(snapSeq, replayed, last, skipped[0], usedBackup, gaps);
    }

    private record Snapshot(Map<String, Long> balances, long seq) {
    }

    private static Snapshot readSnapshot(final Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            final long seq = root.get("seq").getAsLong();
            final Map<String, Long> balances = new HashMap<>();
            for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("accounts").entrySet()) {
                final long value = e.getValue().getAsLong();
                if (value < 0 || !Ledger.validAccount(e.getKey())) {
                    throw new IOException("Nieprawidłowe konto w " + path.getFileName() + ": " + e.getKey() + "=" + value);
                }
                balances.put(e.getKey(), value);
            }
            return new Snapshot(balances, seq);
        }
    }

    /** Adds the records of one file; returns the smallest sequence number in it (MAX_VALUE if none). */
    private long collect(final Path file, final Map<Long, TxRecord> into, final int[] skipped) throws IOException {
        if (!Files.exists(file)) {
            return Long.MAX_VALUE;
        }
        long min = Long.MAX_VALUE;
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            final TxRecord r = TxRecord.decode(line);
            if (r == null) {
                skipped[0]++;
                if (i < lines.size() - 1) { // a torn LAST line after a crash is expected, anything else is not
                    this.logger.warning("Pominięto uszkodzoną linię " + (i + 1) + " w " + file.getFileName());
                }
                continue;
            }
            into.putIfAbsent(r.seq(), r);
            min = Math.min(min, r.seq());
        }
        return min;
    }

    /** Log files with the prefix, newest first. */
    private List<Path> logFiles(final String prefix) throws IOException {
        if (!Files.isDirectory(this.logs)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(this.logs)) {
            final List<Path> files = new ArrayList<>(s
                .filter(p -> {
                    final String n = p.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(SUFFIX);
                })
                .sorted()
                .toList());
            Collections.reverse(files);
            return files;
        }
    }

    // =================================================================== writing

    public void start() {
        synchronized (this.lifecycle) {
            if (this.writer != null) {
                return;
            }
            this.writer = new Thread(this::run, "Skarbiec-Ledger-IO");
            this.writer.setDaemon(false); // never abandoned mid-write; close() joins it
            this.writer.start();
        }
    }

    @Override
    public void record(final TxRecord record) {
        synchronized (this.lifecycle) {
            if (!this.closed) {
                this.queue.add(record);
                return;
            }
        }
        // After shutdown (e.g. another plugin paying during its own onDisable): write synchronously.
        this.appendDirect(record);
    }

    @Override
    public void snapshot(final Map<String, Long> balances, final long seq) {
        synchronized (this.lifecycle) {
            if (!this.closed) {
                this.queue.add(new SnapshotTask(balances, seq));
                return;
            }
        }
        synchronized (this.fileLock) {
            try {
                this.writeSnapshot(new SnapshotTask(balances, seq));
            } catch (final IOException e) {
                this.logger.log(Level.SEVERE, "Nie udało się zapisać accounts.json", e);
            }
        }
    }

    /** Appends a line to today's audit log (coin events that are not account operations). */
    public void audit(final String text) {
        final AuditLine line = new AuditLine(System.currentTimeMillis(), TxRecord.sanitize(text).replace("\t", " "));
        synchronized (this.lifecycle) {
            if (!this.closed) {
                this.queue.add(line);
                return;
            }
        }
        synchronized (this.fileLock) {
            try {
                Files.createDirectories(this.logs);
                Files.writeString(this.logFile(AUDIT_PREFIX, line.time()), formatAudit(line) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException e) {
                this.logger.log(Level.SEVERE, "Nie udało się zapisać dziennika zdarzeń", e);
            }
        }
    }

    /** Acknowledges all prior queued bank writes only after forcing the journal; never blocks Bukkit. */
    public java.util.concurrent.CompletableFuture<Void> durable() {
        synchronized (this.lifecycle) {
            if(this.closed || this.writer==null)return java.util.concurrent.CompletableFuture.failedFuture(new IOException("Bank writer unavailable"));
            var result=new java.util.concurrent.CompletableFuture<Void>();
            this.queue.add(new DurabilityBarrier(result));return result;
        }
    }

    /** Stops the writer after everything queued so far has been written. Blocks up to 30 s. */
    public void close() {
        final Thread t;
        synchronized (this.lifecycle) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.queue.add(POISON);
            t = this.writer;
        }
        if (t == null) {
            return;
        }
        try {
            t.join(TimeUnit.SECONDS.toMillis(30));
            if (t.isAlive()) {
                this.logger.severe("Zapis banku trwa zbyt długo — przerywam oczekiwanie (dziennik zostanie odtworzony przy starcie).");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        final List<Object> batch = new ArrayList<>();
        boolean stop = false;
        while (!stop) {
            batch.clear();
            try {
                batch.add(this.queue.take());
            } catch (final InterruptedException e) {
                continue; // only close() ends the writer
            }
            this.queue.drainTo(batch, 4096);
            synchronized (this.fileLock) {
                for (final Object o : batch) {
                    try {
                        if(o instanceof DurabilityBarrier barrier) {
                            if(this.writerFailure!=null)barrier.result.completeExceptionally(this.writerFailure);
                            else try {
                                this.flush();
                                if(this.journalChannel!=null)this.journalChannel.force(true);
                                barrier.result.complete(null);
                            } catch(IOException | RuntimeException error) {this.writerFailure=error;barrier.result.completeExceptionally(error);}
                        } else if (o == POISON) {
                            stop = true;
                        } else if (o instanceof TxRecord r) {
                            this.writeTx(r);
                        } else if (o instanceof AuditLine a) {
                            this.writeAudit(a);
                        } else if (o instanceof SnapshotTask s) {
                            this.flush();
                            this.writeSnapshot(s);
                            this.truncateJournal();
                        }
                    } catch (final IOException | RuntimeException e) {
                        this.writerFailure=e;
                        this.logger.log(Level.SEVERE, "Błąd zapisu danych banku", e);
                    }
                }
                try {
                    this.flush();
                } catch (final IOException e) {
                    this.writerFailure=e;
                    this.logger.log(Level.SEVERE, "Błąd zapisu dziennika banku", e);
                }
            }
        }
        synchronized (this.fileLock) {
            this.closeFiles();
        }
    }

    private void writeTx(final TxRecord r) throws IOException {
        final String line = r.encode() + "\n";
        if (this.journalChannel == null) {
            Files.createDirectories(this.dir);
            this.journalChannel = FileChannel.open(this.journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        }
        final ByteBuffer buf = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
            this.journalChannel.write(buf);
        }
        final LocalDate day = this.day(r.time());
        if (this.txLog == null || !day.equals(this.txLogDay)) {
            if (this.txLog != null) {
                this.txLog.close();
            }
            Files.createDirectories(this.logs);
            this.txLog = Files.newBufferedWriter(this.logFile(TX_PREFIX, r.time()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.txLogDay = day;
            this.deleteOldLogs();
        }
        this.txLog.write(line);
    }

    private void writeAudit(final AuditLine a) throws IOException {
        final LocalDate day = this.day(a.time());
        if (this.auditLog == null || !day.equals(this.auditLogDay)) {
            if (this.auditLog != null) {
                this.auditLog.close();
            }
            Files.createDirectories(this.logs);
            this.auditLog = Files.newBufferedWriter(this.logFile(AUDIT_PREFIX, a.time()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.auditLogDay = day;
        }
        this.auditLog.write(formatAudit(a) + "\n");
    }

    private static String formatAudit(final AuditLine a) {
        return Instant.ofEpochMilli(a.time()) + "\t" + a.text();
    }

    private void flush() throws IOException {
        if (this.journalChannel != null && this.fsync) {
            this.journalChannel.force(false);
        }
        if (this.txLog != null) {
            this.txLog.flush();
        }
        if (this.auditLog != null) {
            this.auditLog.flush();
        }
    }

    private void writeSnapshot(final SnapshotTask s) throws IOException {
        Files.createDirectories(this.dir);
        final JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("seq", s.seq());
        root.addProperty("saved", Instant.now().toString());
        final JsonObject accounts = new JsonObject();
        new TreeMap<>(s.balances()).forEach(accounts::addProperty);
        root.add("accounts", accounts);
        final byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(this.temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            final ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        if (Files.exists(this.snapshot)) {
            Files.copy(this.snapshot, this.backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(this.temp, this.snapshot, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(this.temp, this.snapshot, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void truncateJournal() throws IOException {
        if (this.journalChannel == null) {
            this.journalChannel = FileChannel.open(this.journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        }
        this.journalChannel.truncate(0);
        this.journalChannel.force(true);
    }

    private void appendDirect(final TxRecord record) {
        synchronized (this.fileLock) {
            final String line = record.encode() + "\n";
            try {
                Files.createDirectories(this.logs);
                Files.writeString(this.journal, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.writeString(this.logFile(TX_PREFIX, record.time()), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException e) {
                this.logger.log(Level.SEVERE, "Nie udało się zapisać operacji po wyłączeniu banku: " + line.strip(), e);
            }
        }
    }

    private void closeFiles() {
        try {
            if (this.journalChannel != null) {
                this.journalChannel.force(true);
                this.journalChannel.close();
            }
            if (this.txLog != null) {
                this.txLog.close();
            }
            if (this.auditLog != null) {
                this.auditLog.close();
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Błąd zamykania plików banku", e);
        }
        this.journalChannel = null;
        this.txLog = null;
        this.auditLog = null;
    }

    private void deleteOldLogs() {
        final LocalDate limit = LocalDate.now(this.zone).minusDays(this.keepDays);
        for (final String prefix : List.of(TX_PREFIX, AUDIT_PREFIX)) {
            try {
                for (final Path p : this.logFiles(prefix)) {
                    final String n = p.getFileName().toString();
                    try {
                        final LocalDate d = LocalDate.parse(n.substring(prefix.length(), n.length() - SUFFIX.length()));
                        if (d.isBefore(limit)) {
                            Files.deleteIfExists(p);
                        }
                    } catch (final DateTimeParseException | IndexOutOfBoundsException ignored) {
                        // foreign file name, keep it
                    }
                }
            } catch (final IOException e) {
                this.logger.log(Level.WARNING, "Nie udało się usunąć starych dzienników", e);
            }
        }
    }

    private LocalDate day(final long time) {
        return Instant.ofEpochMilli(time).atZone(this.zone).toLocalDate();
    }

    private Path logFile(final String prefix, final long time) {
        return this.logs.resolve(prefix + this.day(time) + SUFFIX);
    }

    // =================================================================== reading history

    /** Newest-first operations involving the account, at most {@code limit} (reads the daily logs; any thread). */
    public List<TxRecord> history(final String account, final int limit) {
        final List<TxRecord> out = new ArrayList<>();
        try {
            for (final Path file : this.logFiles(TX_PREFIX)) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                    final TxRecord r = TxRecord.decode(lines.get(i));
                    if (r != null && r.involves(account)) {
                        out.add(r);
                    }
                }
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (final IOException e) {
            this.logger.log(Level.WARNING, "Nie udało się odczytać dziennika transakcji", e);
        }
        return out;
    }

    /** Newest-first audit lines containing {@code needle}, at most {@code limit}. */
    public List<String> auditHistory(final String needle, final int limit) {
        final List<String> out = new ArrayList<>();
        try {
            for (final Path file : this.logFiles(AUDIT_PREFIX)) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                    if (lines.get(i).contains(needle)) {
                        out.add(lines.get(i));
                    }
                }
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (final IOException e) {
            this.logger.log(Level.WARNING, "Nie udało się odczytać dziennika zdarzeń", e);
        }
        return out;
    }
}
