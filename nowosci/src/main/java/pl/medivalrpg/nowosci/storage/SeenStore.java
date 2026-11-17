// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import pl.medivalrpg.nowosci.model.SeenState;

/**
 * Last seen entry per player UUID ({@code seen.json}):
 * {@code {"<uuid>": {"last": 5, "shows": 1, "book": 5, "bookOpens": 1}}} ({@code book*} = automatic book counter).
 * Kept in memory and changed on the main thread; every change writes a snapshot on the I/O thread
 * (tmp + .bak + atomic move).
 */
public final class SeenStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final IoExecutor io;
    private final Logger logger;
    private final Map<UUID, SeenState> states = new ConcurrentHashMap<>();

    public SeenStore(final Path file, final IoExecutor io, final Logger logger) {
        this.file = file;
        this.io = io;
        this.logger = logger;
    }

    public void load() {
        this.states.clear();
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            this.states.putAll(decode(Files.readString(this.file, StandardCharsets.UTF_8)));
        } catch (final IOException | RuntimeException e) {
            this.logger.severe("Nie udało się wczytać " + this.file.getFileName() + " (" + e.getMessage()
                + ") — gracze zobaczą nowości jako nieprzeczytane. Poprzednia wersja: "
                + this.file.getFileName() + ".bak");
        }
    }

    /** {@code null} when the player has no record yet. */
    public SeenState get(final UUID player) {
        return this.states.get(player);
    }

    public void put(final UUID player, final SeenState state) {
        if (!state.equals(this.states.put(player, state))) {
            this.save();
        }
    }

    public void save() {
        final String json = encode(new HashMap<>(this.states));
        this.io.submit(this.file.getFileName().toString(), () -> AtomicFiles.write(this.file, json), null);
    }

    public static String encode(final Map<UUID, SeenState> states) {
        final JsonObject root = new JsonObject();
        states.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                final JsonObject o = new JsonObject();
                o.addProperty("last", e.getValue().lastSeenId());
                o.addProperty("shows", e.getValue().shows());
                if (e.getValue().bookEntryId() > 0) {
                    o.addProperty("book", e.getValue().bookEntryId());
                    o.addProperty("bookOpens", e.getValue().bookOpens());
                }
                root.add(e.getKey().toString(), o);
            });
        return GSON.toJson(root);
    }

    public static Map<UUID, SeenState> decode(final String json) {
        final Map<UUID, SeenState> map = new HashMap<>();
        final JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            return map;
        }
        for (final Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
            final UUID uuid;
            try {
                uuid = UUID.fromString(e.getKey());
            } catch (final IllegalArgumentException ex) {
                continue;
            }
            final JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                final JsonObject o = v.getAsJsonObject();
                final int last = o.has("last") ? o.get("last").getAsInt() : 0;
                final int shows = o.has("shows") ? o.get("shows").getAsInt() : 0;
                final int book = o.has("book") ? o.get("book").getAsInt() : 0;
                final int bookOpens = o.has("bookOpens") ? o.get("bookOpens").getAsInt() : 0;
                map.put(uuid, new SeenState(last, shows, book, bookOpens));
            } else if (v.isJsonPrimitive()) {
                map.put(uuid, new SeenState(v.getAsInt(), 0));
            }
        }
        return map;
    }
}
