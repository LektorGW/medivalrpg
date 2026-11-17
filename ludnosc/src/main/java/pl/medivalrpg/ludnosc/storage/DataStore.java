// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import java.io.IOException;
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
import pl.medivalrpg.ludnosc.service.LudnoscData;

/**
 * {@code plugins/Ludnosc/data.json}. Serialisation on the main thread, file IO on one background thread (writes keep
 * their order): temp file → previous file copied to {@code data.json.bak} → atomic move.
 */
public final class DataStore {

    private final Logger logger;
    private final Path file;
    private final Path backup;
    private final Path temp;
    private final ExecutorService io;

    public DataStore(final Path dataFolder, final Logger logger) {
        this.logger = logger;
        this.file = dataFolder.resolve("data.json");
        this.backup = dataFolder.resolve("data.json.bak");
        this.temp = dataFolder.resolve("data.json.tmp");
        this.io = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Ludnosc-Storage");
            t.setDaemon(true);
            return t;
        });
    }

    /** Loads synchronously (once on enable); falls back to the backup when the main file is broken. */
    public void load(final LudnoscData data) throws IOException {
        Files.createDirectories(this.file.getParent());
        if (!Files.exists(this.file)) {
            if (Files.exists(this.backup)) {
                this.logger.warning("Brak data.json, wczytuję kopię data.json.bak");
                DataCodec.apply(DataCodec.decode(Files.readString(this.backup, StandardCharsets.UTF_8)), data);
            }
            return;
        }
        try {
            DataCodec.apply(DataCodec.decode(Files.readString(this.file, StandardCharsets.UTF_8)), data);
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Nie udało się wczytać data.json, próbuję kopii .bak", e);
            if (Files.exists(this.backup)) {
                Files.copy(this.file, this.file.resolveSibling("data.json.broken-" + System.currentTimeMillis()));
                DataCodec.apply(DataCodec.decode(Files.readString(this.backup, StandardCharsets.UTF_8)), data);
                return;
            }
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    public void saveAsync(final LudnoscData data) {
        final String json = DataCodec.encode(data);
        this.io.execute(() -> this.write(json));
    }

    /** Explicit royal-job commit, ordered after earlier normal saves; propagates disk failures and keeps the writer open. */
    public java.util.concurrent.CompletableFuture<Void> saveRoyalCheckpoint(final LudnoscData data) {
        final String json=DataCodec.encode(data);
        return java.util.concurrent.CompletableFuture.runAsync(()->{
            try {
                Files.createDirectories(this.file.getParent());Files.writeString(this.temp,json,StandardCharsets.UTF_8);
                try(final var channel=java.nio.channels.FileChannel.open(this.temp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
                if(Files.exists(this.file))Files.copy(this.file,this.backup,StandardCopyOption.REPLACE_EXISTING);
                this.replace();
                if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))try(final var channel=java.nio.channels.FileChannel.open(this.file.getParent(),java.nio.file.StandardOpenOption.READ)){channel.force(true);}
            } catch(IOException ex){
                this.logger.log(Level.SEVERE,"Nie udało się trwale zapisać checkpointu Ludnosc",ex);
                throw new java.io.UncheckedIOException(ex);
            }
        },this.io);
    }

    /** Final save on disable: waits for all writes. */
    public void saveAndShutdown(final LudnoscData data) {
        if (data != null) {
            final String json = DataCodec.encode(data);
            this.io.execute(() -> this.write(json));
        }
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(15, TimeUnit.SECONDS)) {
                this.logger.severe("Zapis danych Ludnosc trwa zbyt długo — przerywam oczekiwanie.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(final String json) {
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.temp, json, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.backup, StandardCopyOption.REPLACE_EXISTING);
            }
            this.replace();
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać data.json", e);
        }
    }

    /**
     * Atomic replace of data.json by the written temp file. A transient {@link java.nio.file.AccessDeniedException}
     * (Windows: a scanner / indexer briefly holding data.json open) is retried a few times on the writer thread
     * before it counts as a failed write (which fails closed for a pending production commit).
     */
    private void replace() throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (final AtomicMoveNotSupportedException e) {
                    Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (final java.nio.file.AccessDeniedException e) {
                if (attempt >= 8) {
                    throw e;
                }
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
