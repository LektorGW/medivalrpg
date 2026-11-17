// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small JSON files (shops, NPCs, merchant limits): serialised on the caller's thread, written in order on one
 * background thread to {@code <name>.tmp}, the previous file kept as {@code <name>.bak}, then moved into
 * place atomically. Reads fall back to the backup when the main file is broken.
 */
public final class JsonFiles {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final ExecutorService io;

    public JsonFiles(final Logger logger) {
        this.logger = logger;
        this.io = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Skarbiec-Storage");
            t.setDaemon(true);
            return t;
        });
    }

    /** Reads {@code file} (or its {@code .bak}); {@code null} when neither exists. */
    public JsonElement read(final Path file) throws IOException {
        final Path bak = file.resolveSibling(file.getFileName() + ".bak");
        if (!Files.exists(file)) {
            return Files.exists(bak) ? parse(bak) : null;
        }
        try {
            return parse(file);
        } catch (final IOException | RuntimeException e) {
            if (!Files.exists(bak)) {
                throw e instanceof IOException io ? io : new IOException(e);
            }
            this.logger.log(Level.SEVERE, "Plik " + file.getFileName() + " jest uszkodzony — wczytuję kopię .bak", e);
            Files.copy(file, file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis()));
            return parse(bak);
        }
    }

    private static JsonElement parse(final Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    public void writeAsync(final Path file, final JsonElement json) {
        final String text = GSON.toJson(json);
        try {
            this.io.execute(() -> this.write(file, text));
        } catch (final RuntimeException e) { // executor already shut down
            this.write(file, text);
        }
    }

    public void write(final Path file, final String text) {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        final Path bak = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                final ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            if (Files.exists(file)) {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać " + file.getFileName(), e);
        }
    }

    /** Runs a task on the storage thread (e.g. reading logs). */
    public void execute(final Runnable task) {
        this.io.execute(task);
    }

    public void shutdown() {
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(15, TimeUnit.SECONDS)) {
                this.logger.severe("Zapis danych Skarbca trwa zbyt długo — przerywam oczekiwanie.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
