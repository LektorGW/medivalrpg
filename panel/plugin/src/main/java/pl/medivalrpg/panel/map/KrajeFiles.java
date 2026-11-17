// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Read-only view of two things KrajeApi does not expose: the protected areas ({@code plugins/Kraje/protected-areas.json},
 * e.g. the spawn) and the claim cost ({@code economy.claim-cost-base} / {@code claim-cost-per-chunk} in Kraje's
 * config.yml). Re-read when the file changes. Kraje stays authoritative; this only lets the panel keep
 * {@code ClaimCause.ADMIN} claims out of protected areas and show the cost.
 */
public final class KrajeFiles {

    /** A protected area in chunk coordinates (buffer included), like Kraje's {@code ProtectedArea}. */
    public record Area(String id, String label, String world, int minCX, int minCZ, int maxCX, int maxCZ) {

        public boolean contains(final String w, final int cx, final int cz) {
            return this.world.equals(w) && cx >= this.minCX && cx <= this.maxCX && cz >= this.minCZ && cz <= this.maxCZ;
        }
    }

    /** Claim cost: {@code base + perChunk × owned chunks}. */
    public record ClaimCost(long base, long perChunk) {
    }

    private final Path folder;
    private final Logger log;
    private long areasStamp = Long.MIN_VALUE;
    private List<Area> areas = List.of();
    private long configStamp = Long.MIN_VALUE;
    private ClaimCost cost;

    public KrajeFiles(final Path krajeFolder, final Logger log) {
        this.folder = krajeFolder;
        this.log = log;
    }

    /** Protected areas (empty when the file is missing or unreadable). */
    public synchronized List<Area> areas() {
        if (this.folder == null) {
            return List.of();
        }
        final Path f = this.folder.resolve("protected-areas.json");
        final long stamp = stamp(f);
        if (stamp != this.areasStamp) {
            this.areasStamp = stamp;
            List<Area> next = List.of();
            if (stamp != -1) {
                try {
                    next = parseAreas(Files.readString(f, StandardCharsets.UTF_8));
                } catch (final IOException | RuntimeException e) {
                    this.log.warning("Panel: nie udało się odczytać " + f + ": " + e.getMessage());
                }
            }
            this.areas = next;
        }
        return this.areas;
    }

    /** Kraje's claim cost, or {@code fallback} when its config cannot be read. */
    public synchronized ClaimCost claimCost(final ClaimCost fallback) {
        if (this.folder == null) {
            return fallback;
        }
        final Path f = this.folder.resolve("config.yml");
        final long stamp = stamp(f);
        if (stamp != this.configStamp) {
            this.configStamp = stamp;
            this.cost = null;
            if (stamp != -1) {
                try {
                    final YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                    if (y.contains("economy.claim-cost-base") || y.contains("economy.claim-cost-per-chunk")) {
                        final boolean enabled = y.getBoolean("economy.enabled", true);
                        this.cost = enabled ? new ClaimCost(Math.max(0, y.getLong("economy.claim-cost-base", fallback.base())),
                            Math.max(0, y.getLong("economy.claim-cost-per-chunk", fallback.perChunk()))) : new ClaimCost(0, 0);
                    }
                } catch (final RuntimeException e) {
                    this.log.warning("Panel: nie udało się odczytać kosztu zajęcia chunka z " + f + ": " + e.getMessage());
                }
            }
        }
        return this.cost == null ? fallback : this.cost;
    }

    private static long stamp(final Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() ^ Files.size(f) : -1;
        } catch (final IOException e) {
            return -1;
        }
    }

    /** Parses Kraje's {@code protected-areas.json} ({@code {version, areas:[{id,world,minX,minZ,maxX,maxZ,bufferChunks}]}}). */
    public static List<Area> parseAreas(final String json) {
        final List<Area> out = new ArrayList<>();
        final JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("areas") || !root.getAsJsonObject().get("areas").isJsonArray()) {
            return out;
        }
        for (final JsonElement e : root.getAsJsonObject().getAsJsonArray("areas")) {
            if (!e.isJsonObject()) {
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            try {
                final String id = o.get("id").getAsString();
                final String world = o.get("world").getAsString();
                int minX = o.get("minX").getAsInt();
                int minZ = o.get("minZ").getAsInt();
                int maxX = o.get("maxX").getAsInt();
                int maxZ = o.get("maxZ").getAsInt();
                final int buffer = o.has("bufferChunks") ? Math.max(0, Math.min(16, o.get("bufferChunks").getAsInt())) : 0;
                if (minX > maxX) {
                    final int t = minX;
                    minX = maxX;
                    maxX = t;
                }
                if (minZ > maxZ) {
                    final int t = minZ;
                    minZ = maxZ;
                    maxZ = t;
                }
                out.add(new Area(id, "spawn".equals(id) ? "Spawn" : id, world, (minX >> 4) - buffer, (minZ >> 4) - buffer,
                    (maxX >> 4) + buffer, (maxZ >> 4) + buffer));
            } catch (final RuntimeException ignored) {
                // skip a broken entry like Kraje does
            }
        }
        return out;
    }
}
