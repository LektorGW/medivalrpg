// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON files: serialised on the main thread, written on one background thread (order kept) as tmp file + atomic
 * replace, the previous version kept as {@code .bak}. Corrupt files stop loading: replaying an old backup could duplicate items or payments.
 */
public final class JsonFiles {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Turnieje-zapis");
        t.setDaemon(true);
        return t;
    });

    public JsonFiles(final Logger logger) {
        this.logger = logger;
    }

    /** Serialises now (caller's thread = main) and writes in the background. */
    public void writeAsync(final Path file, final Object value) {
        final String json = GSON.toJson(value);
        this.writer.execute(() -> this.write(file, json));
    }

    /** Writes on the I/O worker and waits for durable completion; failures reach the caller. */
    public void writeNow(final Path file, final Object value) {
        final String json = GSON.toJson(value);
        java.util.concurrent.CompletableFuture.runAsync(() -> this.write(file, json), this.writer).join();
    }

    private void write(final Path file, final String json) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (final var channel = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
                final var bytes = java.nio.ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException("Nie udało się zapisać " + file, e);
        }
    }

    /** Null only when missing; corrupt files require manual reconciliation. */
    public <T> T read(final Path file, final Type type) {
        for (final Path p : new Path[] {file}) {
            if (!Files.exists(p)) {
                continue;
            }
            try {
                final T value = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), type);
                if (value == null) throw new IllegalStateException("Pusty JSON: " + p);
                return value;
            } catch (final IOException | RuntimeException e) {
                throw new IllegalStateException("Uszkodzony plik " + p + "; wymagane ręczne odzyskanie danych", e);
            }
        }
        return null;
    }

    /** Waits for pending writes (plugin disable). */
    public void shutdown() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis plików nie zakończył się w 10 s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
