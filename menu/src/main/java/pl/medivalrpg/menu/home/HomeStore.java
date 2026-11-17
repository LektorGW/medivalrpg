// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.home;

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
import java.util.Collections;
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
import pl.medivalrpg.menu.teleport.StoredLocation;

/**
 * Homes of all players in one JSON file ({@code plugins/Menu/data/homes.json}):
 * {@code {"<uuid>": {"dom": {"world": "...", "x": 0, ...}}}}.
 *
 * <p>Loaded on the main thread at start. Each player's homes are an immutable sorted map replaced on change,
 * so tab completion may read them from any thread. Saved on a single writer thread (tmp file + atomic replace,
 * previous file kept as .bak).</p>
 */
public final class HomeStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Map<String, StoredLocation>> homes = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Menu-HomeStore");
        t.setDaemon(true);
        return t;
    });

    public HomeStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        this.homes.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            this.homes.putAll(fromJson(Files.readString(this.file, StandardCharsets.UTF_8)));
        } catch (final IOException | RuntimeException e) {
            this.logger.warning("Nie można wczytać " + this.file + ": " + e);
        }
    }

    /** Homes of the player by name (sorted, immutable). */
    public Map<String, StoredLocation> homes(final UUID player) {
        return this.homes.getOrDefault(player, Map.of());
    }

    public Optional<StoredLocation> get(final UUID player, final String name) {
        return Optional.ofNullable(this.homes(player).get(name));
    }

    /** Main thread. */
    public void set(final UUID player, final String name, final StoredLocation location) {
        final Map<String, StoredLocation> copy = new TreeMap<>(this.homes(player));
        copy.put(name, location);
        this.homes.put(player, Collections.unmodifiableMap(copy));
        this.saveAsync();
    }

    /** Main thread; {@code false} when there was no such home. */
    public boolean remove(final UUID player, final String name) {
        final Map<String, StoredLocation> current = this.homes(player);
        if (!current.containsKey(name)) {
            return false;
        }
        final Map<String, StoredLocation> copy = new TreeMap<>(current);
        copy.remove(name);
        if (copy.isEmpty()) {
            this.homes.remove(player);
        } else {
            this.homes.put(player, Collections.unmodifiableMap(copy));
        }
        this.saveAsync();
        return true;
    }

    // ------------------------------------------------------------------ JSON (package-private for tests)

    static Map<UUID, Map<String, StoredLocation>> fromJson(final String json) {
        final Map<UUID, Map<String, StoredLocation>> out = new LinkedHashMap<>();
        final JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return out;
        }
        for (final Map.Entry<String, JsonElement> player : root.getAsJsonObject().entrySet()) {
            final UUID id;
            try {
                id = UUID.fromString(player.getKey());
            } catch (final IllegalArgumentException e) {
                continue;
            }
            if (!player.getValue().isJsonObject()) {
                continue;
            }
            final Map<String, StoredLocation> list = new TreeMap<>();
            for (final Map.Entry<String, JsonElement> home : player.getValue().getAsJsonObject().entrySet()) {
                if (!home.getValue().isJsonObject() || HomeRules.normalizeName(home.getKey()).isEmpty()) {
                    continue;
                }
                final JsonObject o = home.getValue().getAsJsonObject();
                final Map<String, Object> values = new LinkedHashMap<>();
                for (final String key : new String[] {"world", "x", "y", "z", "yaw", "pitch"}) {
                    final JsonElement e = o.get(key);
                    if (e != null && e.isJsonPrimitive()) {
                        values.put(key, e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : e.getAsString());
                    }
                }
                StoredLocation.parse(values).ifPresent(loc -> list.put(home.getKey(), loc));
            }
            if (!list.isEmpty()) {
                out.put(id, Collections.unmodifiableMap(list));
            }
        }
        return out;
    }

    static String toJson(final Map<UUID, Map<String, StoredLocation>> data) {
        final JsonObject root = new JsonObject();
        for (final Map.Entry<UUID, Map<String, StoredLocation>> player : new TreeMap<>(data).entrySet()) {
            final JsonObject list = new JsonObject();
            for (final Map.Entry<String, StoredLocation> home : player.getValue().entrySet()) {
                final JsonObject o = new JsonObject();
                home.getValue().toMap().forEach((k, v) -> {
                    if (v instanceof Number n) {
                        o.addProperty(k, n);
                    } else {
                        o.addProperty(k, String.valueOf(v));
                    }
                });
                list.add(home.getKey(), o);
            }
            root.add(player.getKey().toString(), list);
        }
        return GSON.toJson(root);
    }

    private void saveAsync() {
        final String json = toJson(Map.copyOf(this.homes));
        this.writer.execute(() -> this.write(json));
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
                this.logger.warning("Zapis domów nie zakończył się w 5 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
