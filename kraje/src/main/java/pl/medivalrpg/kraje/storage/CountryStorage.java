// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.StoredInvite;

/**
 * JSON file storage. Serialisation happens on the caller's (main) thread, file IO on a single
 * background thread, so writes are always applied in order. Every write goes to a temporary file
 * first, the previous file is kept as {@code countries.json.bak}, and the temp file is then moved
 * into place atomically.
 */
public final class CountryStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final Path file;
    private final Path backup;
    private final Path temp;
    private final ExecutorService io;

    /** File content: countries plus the epoch millis of the last processed daily economy tick (0 = never). */
    public record Data(List<Country> countries, long lastDailyTick, List<StoredInvite> invites) {
        public Data(final List<Country> countries, final long lastDailyTick) {
            this(countries, lastDailyTick, List.of());
        }
    }

    public CountryStorage(final Path dataFolder, final Logger logger) {
        this.logger = logger;
        this.file = dataFolder.resolve("countries.json");
        this.backup = dataFolder.resolve("countries.json.bak");
        this.temp = dataFolder.resolve("countries.json.tmp");
        this.io = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Kraje-Storage");
            t.setDaemon(true);
            return t;
        });
    }

    /** Loads countries synchronously (used once on enable). Falls back to the backup if the main file is broken. */
    public Data load() throws IOException {
        Files.createDirectories(this.file.getParent());
        if (!Files.exists(this.file)) {
            if (Files.exists(this.backup)) {
                this.logger.warning("Brak countries.json, wczytuję kopię countries.json.bak");
                return this.read(this.backup);
            }
            return new Data(List.of(), 0L);
        }
        try {
            return this.read(this.file);
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Nie udało się wczytać countries.json, próbuję kopii .bak", e);
            if (Files.exists(this.backup)) {
                // Keep the broken file for manual inspection.
                Files.copy(this.file, this.file.resolveSibling("countries.json.broken-" + System.currentTimeMillis()));
                return this.read(this.backup);
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    private Data read(final Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return new Data(CountryCodec.decodeAll(root), CountryCodec.decodeLastDailyTick(root), CountryCodec.decodeInvites(root));
        }
    }

    /** Serialises now (caller thread) and writes in the background. */
    public void saveAsync(final List<Country> countries, final long lastDailyTick) {
        this.saveAsync(countries, lastDailyTick, List.of());
    }

    /** Serialises now (caller thread) and writes in the background, with the persistent invitations. */
    public void saveAsync(final List<Country> countries, final long lastDailyTick, final List<StoredInvite> invites) {
        final String json = GSON.toJson(CountryCodec.encodeAll(countries, lastDailyTick, invites));
        this.io.execute(() -> this.write(json));
    }

    /** A live checkpoint after earlier queued saves; failure is reported and the normal writer stays usable. */
    public CompletableFuture<Void> checkpoint(final List<Country> countries, final long lastDailyTick,
                                               final List<StoredInvite> invites) {
        final String json=GSON.toJson(CountryCodec.encodeAll(countries,lastDailyTick,invites));
        final CompletableFuture<Void> saved=new CompletableFuture<>();
        try {
            this.io.execute(()-> {
                try {this.writeStrict(json,true);saved.complete(null);}
                catch(final Exception ex){saved.completeExceptionally(ex);}
            });
        } catch(final RuntimeException ex){saved.completeExceptionally(ex);}
        return saved;
    }

    public void saveAndShutdown(final List<Country> countries, final long lastDailyTick) {
        this.saveAndShutdown(countries, lastDailyTick, List.of());
    }

    /**
     * Writes the given snapshot after all pending writes and waits for completion (used on disable).
     * {@code null} only waits for pending writes.
     */
    public void saveAndShutdown(final List<Country> countries, final long lastDailyTick, final List<StoredInvite> invites) {
        if (countries != null) {
            final String json = GSON.toJson(CountryCodec.encodeAll(countries, lastDailyTick, invites));
            this.io.execute(() -> this.write(json));
        }
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(15, TimeUnit.SECONDS)) {
                this.logger.severe("Zapis danych krajów trwa zbyt długo — przerywam oczekiwanie.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(final String json) {
        try {
            this.writeStrict(json,false);
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać countries.json", e);
        }
    }

    private void writeStrict(final String json, final boolean durable) throws IOException {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.temp, json, StandardCharsets.UTF_8);
            if(durable)try(final var channel=java.nio.channels.FileChannel.open(this.temp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            if(durable)try(final var channel=java.nio.channels.FileChannel.open(this.file,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
            if(durable&&!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))
                try(final var channel=java.nio.channels.FileChannel.open(this.file.getParent(),java.nio.file.StandardOpenOption.READ)){channel.force(true);}
    }
}
