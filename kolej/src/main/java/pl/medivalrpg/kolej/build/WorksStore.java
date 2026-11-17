// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Single background writer of {@code works/jobs.json}. Callers pass immutable snapshots, never Bukkit objects.
 *
 * <p>The file format is unchanged: the bytes are exactly {@code new Gson().toJson(snapshot)} (version 1), so an older
 * plugin build reads the file as before. What changed is how the bytes are produced:
 * <ul>
 * <li>{@link #save} never blocks. A newer snapshot supersedes a queued one (coalescing); every returned future completes
 *     only after that snapshot or a newer one is durable (fsync of the temporary file, then atomic rename).</li>
 * <li>The previous file is never parsed again: its validity is known from {@link #read()} and from our own writes.</li>
 * <li>{@code jobs.json.bak} (previous good version) is a hard link, with a copy fallback.</li>
 * <li>Entry arrays are immutable per job. The byte range of each array in the last written file is remembered and copied
 *     at kernel level into the next file, so a save serializes only the small job headers. The copy is used only while
 *     the file on disk is provably the one we wrote (size, modification time, file key); otherwise everything is
 *     serialized again.</li>
 * </ul>
 * A failed write is sticky: every later save fails, and the works stop until restart (as before).
 */
final class WorksStore {
    /** Test hook: called at fixed points of a write; throwing simulates a crash at that point. */
    interface Faults { void at(String stage) throws IOException; }
    private record Waiter(long seq, CompletableFuture<Void> future) { }
    private record Region(long offset, long length) { }
    private record Stamp(long size, FileTime modified, Object key) { }

    private static final Type ENTRIES = TypeToken.getParameterized(List.class, RailwayWorks.Entry.class).getType();
    private static final Type REUSED = TypeToken.getParameterized(List.class, RailReuse.Check.class).getType();
    private static final Type CELLS = TypeToken.getParameterized(List.class, pl.medivalrpg.kolej.network.TrackCell.class).getType();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");

    private final Path file, tmp, bak, bakTmp;
    private final Gson gson = new Gson();
    private final Faults faults;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kolej-works-save"); t.setDaemon(true); return t;
    });

    // Queue state, guarded by "this".
    private RailwayWorks.Snapshot queued;
    private long queuedSeq, submittedSeq;
    private boolean draining;
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private RuntimeException failure;
    private volatile long durableSeq;
    private volatile long lastWriteNanos, writes, copiedBytes, serializedBytes;

    // Writer state, guarded by "io" (writer thread, plus read() at start-up).
    private final Object io = new Object();
    /** Whether the current file is a good version worth keeping as .bak; null = unknown (decoded once). */
    private Boolean previousValid;
    private Stamp written;
    private IdentityHashMap<List<RailwayWorks.Entry>, Region> regions = new IdentityHashMap<>();

    WorksStore(Path file) { this(file, stage -> { }); }
    WorksStore(Path file, Faults faults) {
        this.file = file; this.faults = Objects.requireNonNull(faults);
        tmp = sibling(".tmp"); bak = sibling(".bak"); bakTmp = sibling(".bak.tmp");
    }
    private Path sibling(String suffix) { return file.resolveSibling(file.getFileName() + suffix); }

    RailwayWorks.Snapshot read() throws IOException {
        synchronized (io) {
            regions = new IdentityHashMap<>(); written = null; previousValid = false;
            if (!Files.exists(file)) return new RailwayWorks.Snapshot(1, List.of());
            try { RailwayWorks.Snapshot s = decode(file); previousValid = true; return s; }
            catch (IOException | RuntimeException ex) {
                // The damaged current file is never promoted to .bak by the next save.
                try { return decode(bak); }
                catch (IOException | RuntimeException backup) { throw new IOException("Uszkodzony jobs.json i kopia", ex); }
            }
        }
    }
    private RailwayWorks.Snapshot decode(Path p) throws IOException {
        RailwayWorks.Snapshot s = gson.fromJson(Files.readString(p), RailwayWorks.Snapshot.class);
        if (s == null || s.version() != 1 || s.jobs() == null) throw new IOException("Niepoprawny format jobs");
        RailwayWorks.validateSnapshot(s);
        return s;
    }

    /** Non-blocking. The future completes once this snapshot (or a newer one) is durable, or fails with the write. */
    synchronized CompletableFuture<Void> save(RailwayWorks.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (failure != null) { future.completeExceptionally(failure); return future; }
        long seq = ++submittedSeq;
        queued = snapshot; queuedSeq = seq; waiters.add(new Waiter(seq, future));
        if (!draining) {
            draining = true;
            try { writer.execute(this::drain); }
            catch (RejectedExecutionException closed) { draining = false; fail(new IllegalStateException("Zapis zleceń zamknięty", closed)); }
        }
        return future;
    }
    /** Sequence number of the most recent {@link #save}; compare with {@link #durableSeq()}. */
    synchronized long submittedSeq() { return submittedSeq; }
    long durableSeq() { return durableSeq; }
    synchronized Optional<RuntimeException> failure() { return Optional.ofNullable(failure); }
    long lastWriteMillis() { return TimeUnit.NANOSECONDS.toMillis(lastWriteNanos); }
    long writes() { return writes; }
    long copiedBytes() { return copiedBytes; }
    long serializedBytes() { return serializedBytes; }

    private void drain() {
        while (true) {
            RailwayWorks.Snapshot snapshot; long seq;
            synchronized (this) {
                if (queued == null || failure != null) { draining = false; return; }
                snapshot = queued; seq = queuedSeq; queued = null;
            }
            try {
                long start = System.nanoTime();
                write(snapshot);
                lastWriteNanos = System.nanoTime() - start; writes++;
            } catch (IOException ex) { fail(new UncheckedIOException(ex)); return; }
            catch (RuntimeException | Error ex) { fail(ex instanceof RuntimeException r ? r : new IllegalStateException(ex)); return; }
            synchronized (this) {
                durableSeq = seq;
                while (!waiters.isEmpty() && waiters.peek().seq <= seq) waiters.poll().future.complete(null);
            }
        }
    }
    private synchronized void fail(RuntimeException error) {
        if (failure == null) failure = error;
        queued = null; draining = false;
        while (!waiters.isEmpty()) waiters.poll().future.completeExceptionally(failure);
    }

    private static final int ENTRIES_PREFIX = ",\"entries\":".length(); // the name is deferred until the value

    private void write(RailwayWorks.Snapshot snapshot) throws IOException {
        synchronized (io) {
            Files.createDirectories(file.getParent());
            IdentityHashMap<List<RailwayWorks.Entry>, Region> next = new IdentityHashMap<>();
            long copied = 0, total;
            try (FileChannel previous = openReusable();
                 FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                JsonWriter json = gson.newJsonWriter(new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(out), StandardCharsets.UTF_8), 1 << 16));
                json.beginObject();
                json.name("version"); gson.toJson(snapshot.version(), int.class, json);
                json.name("jobs"); json.beginArray();
                int index = 0;
                for (RailwayWorks.Job job : snapshot.jobs()) {
                    json.beginObject();
                    json.name("id"); gson.toJson(job.id(), String.class, json);
                    json.name("world"); gson.toJson(job.world(), UUID.class, json);
                    json.name("country"); gson.toJson(job.country(), UUID.class, json);
                    json.name("entries");
                    Region old = previous == null ? null : regions.get(job.entries());
                    long at;
                    if (old != null) {
                        json.jsonValue(""); json.flush(); // writes ,"entries": only; the array bytes follow verbatim
                        at = out.position();
                        transfer(previous, old, out);
                        copied += old.length;
                    } else {
                        json.flush(); at = out.position() + ENTRIES_PREFIX;
                        gson.toJson(job.entries(), ENTRIES, json); json.flush();
                    }
                    Region region = new Region(at, out.position() - at);
                    if (array(out, region)) next.put(job.entries(), region);
                    else if (old != null) throw new IOException("Niezgodny fragment jobs.json przy kopiowaniu");
                    if (index++ == 0) faults.at("tmp-partial");
                    json.name("cursor"); gson.toJson(job.cursor(), int.class, json);
                    json.name("cost"); gson.toJson(job.cost(), long.class, json);
                    json.name("status"); gson.toJson(job.status(), String.class, json);
                    json.name("reason"); gson.toJson(job.reason(), String.class, json);
                    json.name("reused"); gson.toJson(job.reused(), REUSED, json);
                    json.name("financeHeld"); gson.toJson(job.financeHeld(), boolean.class, json);
                    json.name("routeCells"); gson.toJson(job.routeCells(), CELLS, json);
                    json.endObject();
                }
                if (index == 0) faults.at("tmp-partial");
                json.endArray(); json.endObject(); json.flush();
                total = out.position();
                out.force(true);
            } catch (IOException | RuntimeException | Error ex) {
                regions = new IdentityHashMap<>(); written = null; // never trust a half-known state
                throw ex;
            }
            faults.at("tmp-synced");
            if (previousValid == null) previousValid = decodes(file);
            if (previousValid && Files.exists(file)) {
                Files.deleteIfExists(bakTmp);
                try { Files.createLink(bakTmp, file); }
                catch (IOException | UnsupportedOperationException | SecurityException noLink) {
                    Files.copy(file, bakTmp, StandardCopyOption.REPLACE_EXISTING);
                }
                faults.at("bak-linked");
                move(bakTmp, bak);
            }
            faults.at("bak-moved");
            regions = new IdentityHashMap<>(); written = null;
            move(tmp, file);
            previousValid = true;
            if (!WINDOWS) try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            faults.at("renamed");
            try (FileChannel now = FileChannel.open(file, StandardOpenOption.READ)) {
                Stamp stamp = stamp(now);
                if (stamp.size == total) { written = stamp; regions = next; }
            }
            copiedBytes += copied; serializedBytes += total - copied;
        }
    }
    /**
     * Atomic rename. On Windows a scanner/indexer may briefly hold the freshly written file open; the rename is then
     * retried for up to 5 s (it either happens completely or not at all). Elsewhere a failure is immediate.
     */
    private static void move(Path from, Path to) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return; }
            catch (AccessDeniedException busy) {
                if (!WINDOWS || attempt >= 50) throw busy;
                try { Thread.sleep(100); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw busy; }
            }
        }
    }
    /** Only when read() was not called first (tests, tools): the old rule, decode once. */
    private boolean decodes(Path p) {
        if (!Files.exists(p)) return false;
        try { decode(p); return true; } catch (IOException | RuntimeException ex) { return false; }
    }
    /** The last file this writer produced, opened for copying; null when it cannot be proven unchanged. */
    private FileChannel openReusable() throws IOException {
        if (written == null || regions.isEmpty() || !Files.exists(file)) return null;
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try { if (stamp(channel).equals(written)) return channel; }
        catch (IOException | RuntimeException ex) { channel.close(); throw ex; }
        channel.close(); regions = new IdentityHashMap<>(); written = null;
        return null;
    }
    /** Cheap structural check of a remembered range: it must start with '[' and end with ']'. */
    private static boolean array(FileChannel channel, Region region) throws IOException {
        if (region.length < 2) return false;
        var one = java.nio.ByteBuffer.allocate(1);
        if (channel.read(one, region.offset) != 1 || one.get(0) != '[') return false;
        one.clear();
        return channel.read(one, region.offset + region.length - 1) == 1 && one.get(0) == ']';
    }
    private Stamp stamp(FileChannel channel) throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        return new Stamp(channel.size(), a.lastModifiedTime(), a.fileKey());
    }
    private static void transfer(FileChannel from, Region region, FileChannel to) throws IOException {
        long done = 0;
        while (done < region.length) {
            long n = from.transferTo(region.offset + done, region.length - done, to);
            if (n <= 0) throw new IOException("Nie można skopiować zapisanych wpisów jobs.json");
            done += n;
        }
    }

    /** Stops accepting work after the queued write and waits for it; true when everything submitted is durable. */
    boolean close(long timeout, TimeUnit unit) {
        writer.shutdown();
        try { writer.awaitTermination(timeout, unit); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        synchronized (this) { return failure == null && durableSeq == submittedSeq; }
    }
    void shutdown() { writer.shutdown(); }
}
