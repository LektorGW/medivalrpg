// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Last position of every player at quit ({@code plugins/Menu/data/last-positions.json}:
 * {@code {"<uuid>": {"world": "...", "x": 0, ...}}}). Saved on a single writer thread (tmp + atomic replace, .bak).
 */
public final class PositionStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, StoredLocation> positions = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Menu-PositionStore");
        t.setDaemon(true);
        return t;
    });

    public PositionStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        this.positions.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            this.positions.putAll(fromJson(Files.readString(this.file, StandardCharsets.UTF_8)));
        } catch (final IOException | RuntimeException e) {
            this.logger.warning("Nie można wczytać " + this.file + ": " + e);
        }
    }

    public Optional<StoredLocation> get(final UUID player) {
        return Optional.ofNullable(this.positions.get(player));
    }

    public void set(final UUID player, final StoredLocation location) {
        this.positions.put(player, location);
        this.saveAsync();
    }

    static Map<UUID, StoredLocation> fromJson(final String json) {
        final Map<UUID, StoredLocation> out = new LinkedHashMap<>();
        final JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return out;
        }
        for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            final UUID id;
            try {
                id = UUID.fromString(e.getKey());
            } catch (final IllegalArgumentException ex) {
                continue;
            }
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            final Map<String, Object> values = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> v : e.getValue().getAsJsonObject().entrySet()) {
                if (v.getValue().isJsonPrimitive()) {
                    values.put(v.getKey(), v.getValue().getAsJsonPrimitive().isNumber()
                        ? v.getValue().getAsDouble() : v.getValue().getAsString());
                }
            }
            StoredLocation.parse(values).ifPresent(loc -> out.put(id, loc));
        }
        return out;
    }

    static String toJson(final Map<UUID, StoredLocation> data) {
        final JsonObject root = new JsonObject();
        for (final Map.Entry<UUID, StoredLocation> e : new TreeMap<>(data).entrySet()) {
            final JsonObject o = new JsonObject();
            e.getValue().toMap().forEach((k, v) -> {
                if (v instanceof Number n) {
                    o.addProperty(k, n);
                } else {
                    o.addProperty(k, String.valueOf(v));
                }
            });
            root.add(e.getKey().toString(), o);
        }
        return GSON.toJson(root);
    }

    private void saveAsync() {
        final String json = toJson(Map.copyOf(this.positions));
        if (this.writer.isShutdown()) {
            this.write(json);
        } else {
            this.writer.execute(() -> this.write(json));
        }
    }

    private void write(final String json) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException atomicFailed) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.warning("Nie można zapisać " + this.file + ": " + e);
        }
    }

    /** Waits for pending writes (plugin disable). */
    public void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(5, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis ostatnich pozycji nie zakończył się w 5 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
