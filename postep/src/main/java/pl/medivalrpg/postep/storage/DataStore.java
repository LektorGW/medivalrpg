// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code plugins/Postep/dane.json}. Serialised on the main thread, written by one background thread:
 * {@code dane.json.tmp} → the current file becomes {@code dane.json.bak} → atomic move. A broken file is kept as
 * {@code dane.json.broken-<millis>} and the {@code .bak} copy is loaded instead.
 */
public final class DataStore {

    /** File content. */
    public static final class Data {
        public int version = 1;
        /** Epoch millis of the last processed daily tick (0 = schedule not started). */
        public long lastTickAt;
        public Map<UUID, CountryState> countries = new HashMap<>();
    }

    private static final String FILE = "dane.json";

    private final Path dir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Postep-zapis");
        t.setDaemon(true);
        return t;
    });

    public DataStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public Data load() {
        Data data = this.read(this.dir.resolve(FILE), true);
        if (data == null) {
            data = this.read(this.dir.resolve(FILE + ".bak"), false);
        }
        if (data == null) {
            data = new Data();
        }
        if (data.countries == null) {
            data.countries = new HashMap<>();
        }
        data.countries.values().removeIf(java.util.Objects::isNull);
        data.countries.values().forEach(CountryState::normalize);
        return data;
    }

    private Data read(final Path file, final boolean keepBroken) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return this.gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (final IOException | JsonParseException e) {
            this.logger.log(Level.SEVERE, "Nie można wczytać " + file.getFileName(), e);
            if (keepBroken) {
                try {
                    Files.copy(file, this.dir.resolve(FILE + ".broken-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
                } catch (final IOException ignored) {
                    // best effort
                }
            }
            return null;
        }
    }

    /** Main thread: serialise now, write in the background. */
    public void saveAsync(final Data data) {
        final String json = this.gson.toJson(data);
        try {
            this.writer.execute(() -> this.write(json));
        } catch (final RejectedExecutionException e) {
            this.write(json);
        }
    }

    /** On disable: waits for pending writes, then writes synchronously. */
    public void saveNow(final Data data) {
        final String json = this.gson.toJson(data);
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis w tle nie skończył się w 10 s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.write(json);
    }

    private synchronized void write(final String json) {
        try {
            Files.createDirectories(this.dir);
            final Path file = this.dir.resolve(FILE);
            final Path tmp = this.dir.resolve(FILE + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, this.dir.resolve(FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie można zapisać " + FILE, e);
        }
    }

    /** Writes any text file atomically (tmp + move), e.g. {@code postep.json} for the map. Background thread. */
    public void writeExternalAsync(final Path target, final String content) {
        try {
            this.writer.execute(() -> writeAtomic(target, content, this.logger));
        } catch (final RejectedExecutionException ignored) {
            // shutting down
        }
    }

    static void writeAtomic(final Path target, final String content, final Logger logger) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            logger.log(Level.WARNING, "Nie można zapisać " + target, e);
        }
    }
}
