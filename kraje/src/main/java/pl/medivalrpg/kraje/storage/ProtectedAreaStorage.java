// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.kraje.model.ProtectedArea;

/**
 * {@code protected-areas.json}: areas where nobody may found a country or claim land. Same scheme as
 * {@link CountryStorage}: serialisation on the caller's (main) thread, IO on one background thread, temp file +
 * {@code .bak} + atomic move. If the file cannot be read the storage turns read-only so it is never overwritten.
 */
public final class ProtectedAreaStorage {

    public static final String FILE_NAME = "protected-areas.json";
    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final Path file;
    private final Path backup;
    private final Path temp;
    private final ExecutorService io;
    private volatile boolean readOnly;

    public ProtectedAreaStorage(final Path dataFolder, final Logger logger) {
        this.logger = logger;
        this.file = dataFolder.resolve(FILE_NAME);
        this.backup = dataFolder.resolve(FILE_NAME + ".bak");
        this.temp = dataFolder.resolve(FILE_NAME + ".tmp");
        this.io = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Kraje-AreaStorage");
            t.setDaemon(true);
            return t;
        });
    }

    /** Loads synchronously (once on enable). Never throws: a broken file (and backup) gives an empty, read-only list. */
    public List<ProtectedArea> load() {
        try {
            Files.createDirectories(this.file.getParent());
            if (Files.exists(this.file)) {
                try {
                    return this.read(this.file);
                } catch (final IOException | RuntimeException e) {
                    this.logger.log(Level.SEVERE, "Nie udało się wczytać " + FILE_NAME + ", próbuję kopii .bak", e);
                    if (!Files.exists(this.backup)) {
                        throw e;
                    }
                    Files.copy(this.file, this.file.resolveSibling(FILE_NAME + ".broken-" + System.currentTimeMillis()));
                    return this.read(this.backup);
                }
            }
            if (Files.exists(this.backup)) {
                this.logger.warning("Brak " + FILE_NAME + ", wczytuję kopię .bak");
                return this.read(this.backup);
            }
            return List.of();
        } catch (final IOException | RuntimeException e) {
            this.readOnly = true;
            this.logger.log(Level.SEVERE, "Nie udało się wczytać obszarów chronionych — zmiany NIE będą zapisywane, "
                + "aby nie nadpisać pliku. Sprawdź plugins/Kraje/" + FILE_NAME + ".", e);
            return List.of();
        }
    }

    private List<ProtectedArea> read(final Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return decode(JsonParser.parseReader(reader).getAsJsonObject(), this.logger);
        }
    }

    /** Serialises now (caller thread) and writes in the background. */
    public void saveAsync(final List<ProtectedArea> areas) {
        if (this.readOnly) {
            this.logger.warning("Obszary chronione nie zostały zapisane (plik był uszkodzony przy starcie).");
            return;
        }
        final String json = GSON.toJson(encode(areas));
        this.io.execute(() -> this.write(json));
    }

    /** Waits for pending writes (on disable). */
    public void shutdown() {
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(15, TimeUnit.SECONDS)) {
                this.logger.severe("Zapis obszarów chronionych trwa zbyt długo — przerywam oczekiwanie.");
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
            try {
                Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać " + FILE_NAME, e);
        }
    }

    // ------------------------------------------------------------------ codec

    public static JsonObject encode(final List<ProtectedArea> areas) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        final JsonArray array = new JsonArray();
        for (final ProtectedArea a : areas) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("world", a.world());
            o.addProperty("minX", a.minX());
            o.addProperty("minZ", a.minZ());
            o.addProperty("maxX", a.maxX());
            o.addProperty("maxZ", a.maxZ());
            o.addProperty("bufferChunks", a.bufferChunks());
            array.add(o);
        }
        root.add("areas", array);
        return root;
    }

    /** Invalid entries are skipped with a warning ({@code logger} may be null). */
    public static List<ProtectedArea> decode(final JsonObject root, final Logger logger) {
        final List<ProtectedArea> out = new ArrayList<>();
        final JsonElement areas = root.get("areas");
        if (areas == null || !areas.isJsonArray()) {
            return out;
        }
        for (final JsonElement e : areas.getAsJsonArray()) {
            try {
                final JsonObject o = e.getAsJsonObject();
                out.add(ProtectedArea.of(o.get("id").getAsString(), o.get("world").getAsString(),
                    o.get("minX").getAsInt(), o.get("minZ").getAsInt(), o.get("maxX").getAsInt(), o.get("maxZ").getAsInt(),
                    o.has("bufferChunks") ? o.get("bufferChunks").getAsInt() : 0));
            } catch (final RuntimeException ex) {
                if (logger != null) {
                    logger.warning("Pominięto nieprawidłowy obszar chroniony w " + FILE_NAME + ": " + e);
                }
            }
        }
        return out;
    }
}
