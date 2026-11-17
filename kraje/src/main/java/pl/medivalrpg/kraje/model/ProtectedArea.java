// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.util.regex.Pattern;

/**
 * A rectangle (block coordinates, inclusive) where nobody may found a country or claim land, extended by
 * {@code bufferChunks} chunks on every side (e.g. the server spawn). Immutable; build with {@link #of}.
 */
public record ProtectedArea(String id, String world, int minX, int minZ, int maxX, int maxZ, int bufferChunks) {

    public static final Pattern ID = Pattern.compile("^[a-z0-9_-]{1,32}$");
    public static final int MAX_BUFFER = 16;

    /** Why {@link #of} rejected its arguments. */
    public enum Problem { ID, WORLD, BUFFER }

    /** Thrown by {@link #of} for invalid arguments. */
    public static final class InvalidAreaException extends IllegalArgumentException {
        private final Problem problem;

        public InvalidAreaException(final Problem problem) {
            super("invalid protected area: " + problem);
            this.problem = problem;
        }

        public Problem problem() {
            return this.problem;
        }
    }

    public ProtectedArea {
        if (id == null || !ID.matcher(id).matches()) {
            throw new InvalidAreaException(Problem.ID);
        }
        if (world == null || world.isBlank()) {
            throw new InvalidAreaException(Problem.WORLD);
        }
        if (bufferChunks < 0 || bufferChunks > MAX_BUFFER) {
            throw new InvalidAreaException(Problem.BUFFER);
        }
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
    }

    /** Validates and normalises (min/max swapped when needed). */
    public static ProtectedArea of(final String id, final String world, final int minX, final int minZ,
                                   final int maxX, final int maxZ, final int bufferChunks) {
        return new ProtectedArea(id, world, minX, minZ, maxX, maxZ, bufferChunks);
    }

    /** Lowest protected chunk X (buffer included). */
    public int minChunkX() {
        return (this.minX >> 4) - this.bufferChunks;
    }

    public int minChunkZ() {
        return (this.minZ >> 4) - this.bufferChunks;
    }

    /** Highest protected chunk X (buffer included). */
    public int maxChunkX() {
        return (this.maxX >> 4) + this.bufferChunks;
    }

    public int maxChunkZ() {
        return (this.maxZ >> 4) + this.bufferChunks;
    }

    /** True when chunk ({@code cx}, {@code cz}) of {@code world} touches the area or its buffer. */
    public boolean containsChunk(final String world, final int cx, final int cz) {
        return this.world.equals(world)
            && cx >= this.minChunkX() && cx <= this.maxChunkX()
            && cz >= this.minChunkZ() && cz <= this.maxChunkZ();
    }

    /** Name shown on the map: "Spawn" for the id {@code spawn}, otherwise the id. */
    public String label() {
        return "spawn".equals(this.id) ? "Spawn" : this.id;
    }
}
