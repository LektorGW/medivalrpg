// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON files with atomic replacement: write {@code .tmp} (fsync) → keep the previous file as {@code .bak} → atomic move.
 * Reads fall back to {@code .bak} when the main file is broken (and keep the broken one aside). Asynchronous saves run
 * on one thread in order; the snapshot (JSON text) is taken on the caller's thread, so the data is never read while it
 * changes.
 */
public final class JsonStore {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private final Logger log;
    private volatile Thread ioThread;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Firmy-zapis");
        t.setDaemon(true);
        this.ioThread = t;
        return t;
    });

    public JsonStore(final Logger log) {
        this.log = log;
    }

    /** Reads a file (or its .bak); {@code fallback} when neither exists. Throws when both are unreadable. */
    public <T> T read(final Path file, final Type type, final Supplier<T> fallback) throws IOException {
        final Path bak = bak(file);
        if (Files.exists(file)) {
            try {
                final T v = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
                return v == null ? fallback.get() : v;
            } catch (final JsonParseException | IOException e) {
                final Path broken = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
                Files.copy(file, broken, StandardCopyOption.REPLACE_EXISTING);
                this.log.log(Level.SEVERE, "Plik " + file.getFileName() + " jest uszkodzony (kopia: " + broken.getFileName()
                    + ") — próbuję " + bak.getFileName(), e);
                if (!Files.exists(bak)) {
                    throw new IOException("Uszkodzony " + file + " i brak kopii .bak", e);
                }
            }
        }
        if (Files.exists(bak)) {
            final T v = GSON.fromJson(Files.readString(bak, StandardCharsets.UTF_8), type);
            this.log.warning("Wczytano " + bak.getFileName() + " (kopia poprzedniego zapisu).");
            return v == null ? fallback.get() : v;
        }
        return fallback.get();
    }

    private static Path bak(final Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /** Serializes now (caller thread) and writes in the background; the future completes when the file is on disk. */
    public CompletableFuture<Void> writeAsync(final Path file, final Object value) {
        final String json = GSON.toJson(value);
        final CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            this.io.execute(() -> {
                try {
                    write(file, json);
                    done.complete(null);
                } catch (final IOException | RuntimeException e) {
                    this.log.log(Level.SEVERE, "Nie udało się zapisać " + file.getFileName(), e);
                    done.completeExceptionally(e);
                }
            });
        } catch (final RuntimeException rejected) {
            try {
                write(file, json);
                done.complete(null);
            } catch (final IOException e) {
                done.completeExceptionally(e);
            }
        }
        return done;
    }

    /** Synchronous write with fsync (before money operations that depend on the record). */
    public void writeNow(final Path file, final Object value) {
        final String json = GSON.toJson(value);
        // wait for queued async writes of the same file so an older snapshot never overwrites this one
        if (Thread.currentThread() != this.ioThread) {
            final CompletableFuture<Void> barrier = new CompletableFuture<>();
            try {
                this.io.execute(() -> barrier.complete(null));
                barrier.get(10, TimeUnit.SECONDS);
            } catch (final Exception ignored) {
                // executor stopped or slow disk: write anyway
            }
        }
        try {
            write(file, json);
        } catch (final IOException e) {
            throw new IllegalStateException("Nie udało się zapisać " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static synchronized void write(final Path file, final String json) throws IOException {
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final ByteBuffer buf = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        if (Files.exists(file)) {
            Files.copy(file, bak(file), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void shutdown() {
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(10, TimeUnit.SECONDS)) {
                this.log.warning("Zapis danych firm nie zakończył się w 10 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
