// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON files and the decision journal in the plugin folder. Snapshots are taken on the main thread and written by
 * one background thread: {@code <file>.tmp} → current becomes {@code <file>.bak} → atomic move. Loading falls back
 * to the {@code .bak} copy when the main file is broken.
 */
public final class JsonStore {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Wladcy-zapis");
        t.setDaemon(true);
        return t;
    });

    public JsonStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public <T> T load(final String file, final Type type) {
        final Path p = this.dir.resolve(file);
        for (final Path candidate : new Path[] {p, this.dir.resolve(file + ".bak")}) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                final T value = this.gson.fromJson(Files.readString(candidate, StandardCharsets.UTF_8), type);
                if (value != null) {
                    return value;
                }
            } catch (final IOException | JsonParseException e) {
                this.logger.log(Level.WARNING, "Nie można odczytać " + candidate.getFileName() + " — próbuję kopii", e);
            }
        }
        return null;
    }

    public void saveAsync(final String file, final Object snapshot) {
        final String json = this.gson.toJson(snapshot);
        this.writer.execute(() -> this.write(file, json));
    }

    public void saveNow(final String file, final Object snapshot) {
        try {
            this.checkpointAsync(file, snapshot).join();
        } catch (final RuntimeException e) {
            this.logger.log(Level.WARNING, "Nie można zapisać " + file, e);
        }
    }

    /** Ordered durable checkpoint: callers must observe completion before moving items or paying money. */
    public CompletableFuture<Void> checkpointAsync(final String file, final Object snapshot) {
        final String json = this.gson.toJson(snapshot);
        return CompletableFuture.runAsync(() -> {
            try {
                this.writeChecked(file, json);
            } catch (final IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, this.writer);
    }

    /** Appends one line to {@code dziennik.log} (decisions of rulers: wars, gifts, tributes, treaties). */
    public void journal(final String line) {
        final String text = "[" + LocalDateTime.now().format(TIME) + "] " + line.replaceAll("[\\r\\n]", " ") + System.lineSeparator();
        this.writer.execute(() -> {
            try {
                Files.createDirectories(this.dir);
                Files.writeString(this.dir.resolve("dziennik.log"), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException e) {
                this.logger.log(Level.WARNING, "Nie można zapisać dziennika", e);
            }
        });
    }

    private synchronized void write(final String file, final String json) {
        try {
            this.writeChecked(file, json);
        } catch (final IOException e) {
            this.logger.log(Level.WARNING, "Nie można zapisać " + file, e);
        }
    }

    private synchronized void writeChecked(final String file, final String json) throws IOException {
        Files.createDirectories(this.dir);
        final Path target = this.dir.resolve(file);
        final Path tmp = this.dir.resolve(file + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            final ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        if (Files.exists(target)) {
            Files.copy(target, this.dir.resolve(file + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis danych nie zakończył się w 10 s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
