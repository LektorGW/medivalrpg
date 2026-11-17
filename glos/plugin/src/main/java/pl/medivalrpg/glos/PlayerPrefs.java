// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Preferencje graczy w {@code prefs.json}:
 * {@code {"<uuid>": {"actionbar": false, "indicators": false}}}.
 * Zapisywane są tylko wartości różne od domyślnych ({@code true}). Zapis na osobnym wątku
 * (kolejność zachowana, zapis atomowy przez plik tymczasowy), bez Bukkit API, więc da się to testować.
 */
public final class PlayerPrefs {
    public static final String ACTIONBAR = "actionbar";
    public static final String INDICATORS = "indicators";

    private final Path file;
    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** uuid -> (klucz -> wartość); trzymamy tylko wartości {@code false}. */
    private final Map<UUID, Map<String, Boolean>> data = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Glos-Prefs");
        t.setDaemon(true);
        return t;
    });

    public PlayerPrefs(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Wczytuje plik synchronicznie (przy starcie). */
    public void load() {
        data.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                UUID id;
                try {
                    id = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> p : e.getValue().getAsJsonObject().entrySet()) {
                    if (p.getValue().isJsonPrimitive() && p.getValue().getAsJsonPrimitive().isBoolean()) {
                        setInternal(id, p.getKey(), p.getValue().getAsBoolean());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Nie udało się wczytać " + file.getFileName()
                    + " – używam domyślnych, kopia uszkodzonego pliku: " + file.getFileName() + ".broken", e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // trudno
            }
        }
    }

    public boolean get(UUID id, String key) {
        Map<String, Boolean> m = data.get(id);
        Boolean v = m == null ? null : m.get(key);
        return v == null || v;
    }

    /** Ustawia wartość i planuje zapis asynchroniczny. */
    public void set(UUID id, String key, boolean value) {
        setInternal(id, key, value);
        saveAsync();
    }

    private void setInternal(UUID id, String key, boolean value) {
        if (value) {
            data.computeIfPresent(id, (k, m) -> {
                m.remove(key);
                return m.isEmpty() ? null : m;
            });
        } else {
            data.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(key, false);
        }
    }

    /** Migawka JSON robiona od razu (na wątku wołającym), zapis pliku na wątku "Glos-Prefs". */
    public void saveAsync() {
        String json = snapshot();
        try {
            writer.execute(() -> write(json));
        } catch (RejectedExecutionException e) {
            write(json); // wyłączanie – zapisz od razu
        }
    }

    /** Czeka na zaległe zapisy (onDisable), najwyżej 3 s. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
                write(snapshot());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String snapshot() {
        JsonObject root = new JsonObject();
        Map<String, Map<String, Boolean>> sorted = new TreeMap<>();
        data.forEach((id, m) -> {
            if (!m.isEmpty()) {
                sorted.put(id.toString(), new TreeMap<>(m));
            }
        });
        sorted.forEach((id, m) -> {
            JsonObject o = new JsonObject();
            m.forEach(o::addProperty);
            root.add(id, o);
        });
        return gson.toJson(root);
    }

    private synchronized void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Nie udało się zapisać " + file.getFileName(), e);
        }
    }
}
