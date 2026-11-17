// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.tutorial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tutorial state of every player ({@code data/tutorial.json}): invited, reminders switched off, steps done and
 * steps clicked. Loaded on the main thread at start; saved asynchronously on a single writer thread (tmp file +
 * atomic replace, previous file kept as .bak). Main thread only, except the writer.
 */
public final class TutorialStore {

    /** One player. {@code done} is sticky: a step proven once stays done (e.g. money spent on founding a country). */
    public static final class Entry {
        public boolean invited;
        public boolean dismissed;
        public boolean finished;
        public final Set<String> done = new LinkedHashSet<>();
        public final Set<String> seen = new LinkedHashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Menu-TutorialStore");
        t.setDaemon(true);
        return t;
    });

    public TutorialStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        this.entries.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            this.entries.putAll(parse(Files.readString(this.file, StandardCharsets.UTF_8)));
        } catch (final IOException | JsonParseException | IllegalStateException e) {
            this.logger.warning("Nie można wczytać " + this.file + ": " + e);
        }
    }

    /** The player's entry, created empty on first use (not saved until {@link #save()}). */
    public Entry entry(final UUID id) {
        return this.entries.computeIfAbsent(id, k -> new Entry());
    }

    public void save() {
        final String json = serialize(this.entries);
        this.writer.execute(() -> this.write(json));
    }

    static String serialize(final Map<UUID, Entry> entries) {
        final JsonObject players = new JsonObject();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            final JsonObject o = new JsonObject();
            o.addProperty("invited", e.getValue().invited);
            o.addProperty("dismissed", e.getValue().dismissed);
            o.addProperty("finished", e.getValue().finished);
            o.add("done", GSON.toJsonTree(e.getValue().done));
            o.add("seen", GSON.toJsonTree(e.getValue().seen));
            players.add(e.getKey().toString(), o);
        });
        final JsonObject root = new JsonObject();
        root.addProperty("_copyright", "Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl");
        root.add("players", players);
        return GSON.toJson(root);
    }

    static Map<UUID, Entry> parse(final String json) {
        final Map<UUID, Entry> out = new HashMap<>();
        final JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("players")) {
            return out;
        }
        for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject().getAsJsonObject("players").entrySet()) {
            final UUID id;
            try {
                id = UUID.fromString(e.getKey());
            } catch (final IllegalArgumentException ignored) {
                continue; // skip garbage
            }
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getValue().getAsJsonObject();
            final Entry entry = new Entry();
            entry.invited = flag(o, "invited");
            entry.dismissed = flag(o, "dismissed");
            entry.finished = flag(o, "finished");
            strings(o, "done", entry.done);
            strings(o, "seen", entry.seen);
            out.put(id, entry);
        }
        return out;
    }

    private static boolean flag(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static void strings(final JsonObject o, final String key, final Set<String> into) {
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (final JsonElement s : o.getAsJsonArray(key)) {
                if (s.isJsonPrimitive()) {
                    into.add(s.getAsString());
                }
            }
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
                this.logger.warning("Zapis stanu samouczka nie zakończył się w 5 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
