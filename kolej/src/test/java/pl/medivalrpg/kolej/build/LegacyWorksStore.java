// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.concurrent.*;

/** Single writer; callers pass immutable snapshots, never Bukkit objects. */
/** Verbatim copy of WorksStore from a31656bb (rollback build): baseline for format compatibility and timing. */
final class LegacyWorksStore {
    private final Path file;
    private final Gson gson = new Gson();
    private volatile RuntimeException failure;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kolej-works-save"); t.setDaemon(true); return t;
    });
    LegacyWorksStore(Path file) { this.file = file; }
    RailwayWorks.Snapshot read() throws IOException {
        if (!Files.exists(file)) return new RailwayWorks.Snapshot(1, java.util.List.of());
        try { return decode(file); }
        catch (IOException | RuntimeException ex) {
            try { return decode(file.resolveSibling(file.getFileName() + ".bak")); }
            catch (IOException | RuntimeException backup) { throw new IOException("Uszkodzony jobs.json i kopia", ex); }
        }
    }
    private RailwayWorks.Snapshot decode(Path p) throws IOException {
        RailwayWorks.Snapshot s = gson.fromJson(Files.readString(p), RailwayWorks.Snapshot.class);
        if (s == null || s.version() != 1 || s.jobs() == null) throw new IOException("Niepoprawny format jobs");
        RailwayWorks.validateSnapshot(s);
        return s;
    }
    Future<?> save(RailwayWorks.Snapshot snapshot) {
        return writer.submit(() -> {
            if (failure != null) throw failure;
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                byte[] bytes = gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
                try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    var buffer = java.nio.ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                boolean validPrevious = false;
                if (Files.exists(file)) {
                    try { decode(file); validPrevious = true; } catch (IOException | RuntimeException ignored) { /* Keep good backup. */ }
                }
                if (validPrevious) {
                    Path bakTmp = file.resolveSibling(file.getFileName() + ".bak.tmp");
                    Files.copy(file, bakTmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(bakTmp, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows"))
                    try (var directory = java.nio.channels.FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            } catch (IOException e) { failure = new java.io.UncheckedIOException(e); throw failure; }
        });
    }
    void shutdown() { writer.shutdown(); }
}
