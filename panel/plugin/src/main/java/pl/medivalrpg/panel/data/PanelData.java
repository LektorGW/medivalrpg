// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.data;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import pl.medivalrpg.panel.util.Json;

/**
 * {@code plugins/Panel/data.json}: managers assigned to single NPC countries ({@code countryId → player}).
 * Concurrent map (read from any thread); saved asynchronously (tmp file, atomic move, {@code .bak}).
 */
public final class PanelData {

    /** Assigned manager of one NPC country. */
    public record Manager(UUID uuid, String name) {
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Manager> managers = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Panel-Data");
        t.setDaemon(true);
        return t;
    });

    public PanelData(final Path dataFolder, final Logger logger) {
        this.file = dataFolder.resolve("data.json");
        this.logger = logger;
    }

    public void load() {
        this.managers.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            final JsonElement root = JsonParser.parseString(Files.readString(this.file, StandardCharsets.UTF_8));
            final JsonObject m = root.isJsonObject() && root.getAsJsonObject().has("npcManagers")
                ? root.getAsJsonObject().getAsJsonObject("npcManagers") : new JsonObject();
            for (final Map.Entry<String, JsonElement> e : m.entrySet()) {
                try {
                    final JsonObject o = e.getValue().getAsJsonObject();
                    this.managers.put(UUID.fromString(e.getKey()),
                        new Manager(UUID.fromString(o.get("uuid").getAsString()), o.has("name") ? o.get("name").getAsString() : "?"));
                } catch (final RuntimeException ignored) {
                    this.logger.warning("data.json: pomijam nieprawidłowy wpis zarządcy " + e.getKey());
                }
            }
        } catch (final IOException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Nie udało się wczytać data.json", e);
        }
    }

    public Manager manager(final UUID country) {
        return country == null ? null : this.managers.get(country);
    }

    public Map<UUID, Manager> managers() {
        return Map.copyOf(this.managers);
    }

    /** NPC countries assigned to this player. */
    public Set<UUID> managedBy(final UUID player) {
        return this.managers.entrySet().stream().filter(e -> e.getValue().uuid().equals(player))
            .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    /** {@code manager == null} removes the assignment. */
    public void setManager(final UUID country, final Manager manager) {
        if (manager == null) {
            this.managers.remove(country);
        } else {
            this.managers.put(country, manager);
        }
        this.saveAsync();
    }

    /** Drops assignments of countries that no longer exist / are no NPC countries. */
    public void retain(final Set<UUID> npcCountries) {
        if (this.managers.keySet().removeIf(id -> !npcCountries.contains(id))) {
            this.saveAsync();
        }
    }

    private String serialize() {
        final Map<String, Object> m = new LinkedHashMap<>();
        this.managers.forEach((country, mgr) -> m.put(country.toString(), Json.obj("uuid", mgr.uuid().toString(), "name", mgr.name())));
        return Json.GSON.toJson(Json.obj("version", 1, "npcManagers", m));
    }

    public void saveAsync() {
        final String json = this.serialize();
        try {
            this.io.execute(() -> this.write(json));
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            this.write(json);
        }
    }

    private void write(final String json) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling("data.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.file.resolveSibling("data.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać data.json", e);
        }
    }

    /** Waits for pending writes (onDisable). */
    public void close() {
        this.io.shutdown();
        try {
            this.io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
