// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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
import pl.medivalrpg.armia.model.ConquestState;

/**
 * {@code conquest.json}: background wars. Written in the background (tmp → atomic replace, previous file as {@code .bak});
 * a broken file falls back to {@code .bak}, and when both are broken the state starts empty while the broken file is kept
 * as {@code conquest.json.broken-<time>}.
 */
public final class ConquestStore {

    private static final String NAME = "conquest.json";

    private final Path dir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Armia-conquest-writer");
        t.setDaemon(true);
        return t;
    });

    public ConquestStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    public ConquestState load() {
        final Path file = this.dir.resolve(NAME);
        final ConquestState main = this.read(file);
        if (main != null) {
            main.normalize();
            return main;
        }
        if (Files.exists(file)) {
            final ConquestState bak = this.read(this.dir.resolve(NAME + ".bak"));
            try {
                Files.copy(file, this.dir.resolve(NAME + ".broken-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                this.logger.log(Level.WARNING, "Nie skopiowano uszkodzonego " + NAME, e);
            }
            if (bak != null) {
                this.logger.warning(NAME + " uszkodzony — wczytano kopię .bak");
                bak.normalize();
                return bak;
            }
            this.logger.severe(NAME + " i kopia .bak uszkodzone — wojny w tle zaczynają od zera (plik zachowany jako .broken-*)");
        }
        return new ConquestState();
    }

    private ConquestState read(final Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return this.gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), ConquestState.class);
        } catch (final IOException | JsonParseException e) {
            this.logger.log(Level.WARNING, "Błąd odczytu " + file.getFileName(), e);
            return null;
        }
    }

    /** Serialises on the calling (main) thread, writes in the background. */
    public void saveAsync(final ConquestState state) {
        final String json = this.gson.toJson(state);
        this.writer.execute(() -> this.write(json));
    }

    public void saveNow(final ConquestState state) {
        final String json = this.gson.toJson(state);
        this.writer.execute(() -> this.write(json));
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis " + NAME + " trwa dłużej niż 10 s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(final String json) {
        try {
            Files.createDirectories(this.dir);
            final Path file = this.dir.resolve(NAME);
            final Path tmp = this.dir.resolve(NAME + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, this.dir.resolve(NAME + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie zapisano " + NAME, e);
        }
    }
}
