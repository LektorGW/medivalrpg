// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A building under construction (persisted in data.json, survives restarts). The building is placed with its
 * footprint centred on ({@link #originX}, {@link #originZ}) and its ground layer at {@link #originY}. Progress is the
 * index of the next step in the deterministic build order (foundation columns, then template cells); builders earn
 * {@link #credit} (blocks) over time and spend it while the site's chunks are loaded. Main thread only.
 */
public final class ConstructionSite {

    public UUID id;
    public UUID countryId;
    public String type;
    public String world;
    public int originX;
    public int originY;
    public int originZ;
    /** Clockwise quarter turns. */
    public int rotation;
    /** Next build step. */
    public int cursor;
    /** Blocks the builders may place now (accumulates while unloaded if offline progress is enabled). */
    public double credit;
    public long createdAt;
    /** Copper paid (for refunds). */
    public long cost;
    /** {@code BuildingTemplate#contentHash} at order time; a different hash after a template edit restarts the cursor. */
    public long templateHash;
    /** Terrain was checked for obstructions (orders placed in unloaded chunks are checked when they load). */
    public boolean validated;
    public String orderedBy;
    /** Version of the build order the cursor refers to; older sites restart the cursor (placed blocks are skipped). */
    public int orderVersion;
    /** Dye for {@code {kolor}} blocks (country colour at order time). */
    public String dye;
    /** Road cells placed after the building (surface positions). */
    public List<BlockPos> road;
    /** Temporary scaffolding (removed when the building is finished; survives restarts). */
    public List<BlockPos> scaffolding;
    /** Night campfire of the builders (temporary). */
    public BlockPos campfire;
    /** "Construction store" chest when materials are required (temporary). */
    public BlockPos storage;
    /** {@code build} (default), {@code repair}, {@code upgrade}, {@code road} or {@code restore} (clean-up after a cancelled build). */
    public String kind;
    /** Current phase (see {@code ConstructionService.Phase}); {@link #cursor} counts inside the phase. */
    public int phase;
    /** Terraforming plan: {x, z, oldTop, newTop, wallBottom, wallTop, plot(0/1)} per column. */
    public List<int[]> terrain;
    /** Version of the pending earthworks plan; upgraded without rewinding later building phases. */
    public int terrainVersion;
    /** Building being repaired (kind {@code repair}) or modernised (kind {@code upgrade}). */
    public UUID repairOf;
    /** Settlement level of the materials (material slots). */
    public int level;

    public static final String BUILD = "build";
    public static final String REPAIR = "repair";
    public static final String ROAD = "road";
    public static final String RESTORE = "restore";
    public static final String UPGRADE = "upgrade";
    /** Demolition of a building (rebuilding of towns in a new epoch); {@link #then} is built in its place afterwards. */
    public static final String DEMOLISH = "demolish";
    /** Levelling of a town quarter (redesign, plan v2): only the terrain columns. */
    public static final String LEVEL = "level";

    /** Building type ordered at the same place after a demolition (may be null). */
    public String then;
    /**
     * Raw material already taken out of the site chest and cut for use (group → units, see
     * {@code logic.MaterialNeeds}): one log gives four units of wood, the rest stays here for the next blocks.
     */
    public java.util.Map<String, Integer> materials;
    /** Trees the citizens felled for this (hut) site; persisted so a restart never resets the limit. */
    public int felledTrees;

    public java.util.Map<String, Integer> materials() {
        if (this.materials == null) {
            this.materials = new java.util.HashMap<>();
        }
        return this.materials;
    }

    public String kind() {
        return this.kind == null ? BUILD : this.kind;
    }

    public boolean is(final String k) {
        return this.kind().equals(k);
    }

    public ConstructionSite() {
    }

    public List<BlockPos> road() {
        if (this.road == null) {
            this.road = new ArrayList<>();
        }
        return this.road;
    }

    public List<BlockPos> scaffolding() {
        if (this.scaffolding == null) {
            this.scaffolding = new ArrayList<>();
        }
        return this.scaffolding;
    }

    public ChunkRef anchorChunk() {
        return ChunkRef.ofBlock(this.world, this.originX, this.originZ);
    }
}
