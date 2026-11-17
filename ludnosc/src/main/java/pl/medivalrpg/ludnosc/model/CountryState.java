// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-country Ludnosc state: granary food (food points), hunger/homeless counters and the containers marked as
 * granary deposit points. Mutated on the main thread only; numeric fields are volatile for API reads.
 */
public final class CountryState {

    /** Finished building id → who ordered it (player name, "AI", "przebudowa", "obywatele", "plan"...). */
    public java.util.Map<String, String> origins;

    public java.util.Map<String, String> origins() {
        if (this.origins == null) {
            this.origins = new java.util.HashMap<>();
        }
        return this.origins;
    }

    /**
     * Buildings registered by the players (building id → details). Absent in data written before 2026-11-03; older
     * versions ignore it.
     */
    public java.util.Map<String, OwnBuilding> ownBuildings;

    public java.util.Map<String, OwnBuilding> ownBuildings() {
        if (this.ownBuildings == null) {
            this.ownBuildings = new java.util.LinkedHashMap<>();
        }
        return this.ownBuildings;
    }

    /** Plots the planner must not propose again for a while (refused / obstructed orders); absent in older files. */
    public java.util.List<AvoidSpot> plannerAvoid;

    public volatile long food;
    public volatile int hungryDays;
    public volatile int homelessDays;
    /** Explicit royal reconstruction owns this country's building/planning turns until the journal finishes. */
    public boolean royalRebuildActive;
    /** Result of the last daily tick, for {@code /ludnosc info}. */
    public volatile long lastTax;
    public volatile long lastProduced;
    public volatile long lastEaten;
    public volatile int lastDepartures;
    public Set<BlockPos> granaryChests = new LinkedHashSet<>();
    /** Town centre chosen by the settlement planner (null = capital chunk centre). */
    public BlockPos center;
    /** Road columns built by the planner in the centre's world ({@code RoadPlanner#key}). */
    public Set<Long> roads = new LinkedHashSet<>();
    /** Template buildings awaiting inspection/reconstruction after a settled fire. Persists until accepted/repaired. */
    public Set<String> fireRepairs = new LinkedHashSet<>();
    /** Mines: building id → {headX, headY, headZ, direction (0 N, 1 E, 2 S, 3 W), gallery blocks}. */
    public java.util.Map<String, int[]> mines = new java.util.LinkedHashMap<>();
    /** Quarries: building id → {next cell, epoch day, stone mined today}; the world itself records completed cuts. */
    public java.util.Map<String, int[]> quarries = new java.util.LinkedHashMap<>();
    /**
     * Workshops (glassworks, brickworks, sheepfold, smithy): building id → {epoch day, dug today, fuel smelts left,
     * founders given (0/1), day the flock was last founded}. Unknown to older versions (ignored on load).
     */
    public java.util.Map<String, int[]> workshops = new java.util.LinkedHashMap<>();
    /** Underground parts: building id → {kind, hatchX, hatchY, hatchZ, dir, param, progress, state}. */
    public java.util.Map<String, int[]> underground = new java.util.LinkedHashMap<>();
    /** Design plan from the web panel (JSON) and its progress. */
    public String designJson;
    /** Completed royal layout remains visible in the editor without issuing construction again. */
    public boolean designExecutionPaused;
    public java.util.List<String> designDone;
    public java.util.List<String> designFailed;
    /** Per explicit plan order: current reason and bounded retry deadline (null in old saves). */
    public java.util.Map<String,pl.medivalrpg.ludnosc.logic.DesignOrderRunner.Failure> designFailures;
    public java.util.List<Long> designRoadsLeft;
    public java.util.List<Long> designWallsLeft;
    public java.util.List<Long> designGates;
    /** Wall cells of the design plan already ordered, and when the follow-border / follow-town lines were last computed. */
    public java.util.Set<Long> designWallsDone;
    public long designWallsAt;
    /** City walls following the town: the current ring (in order), its modules {from, to} still to build, old cells to take down. */
    public java.util.List<Long> wallCells;
    public java.util.List<int[]> wallModules;
    public java.util.List<Long> wallRemove;
    /**
     * Version of the ring planning {@link #wallCells} were made with (0 = before 2026-10-31: one-block holes on
     * diagonal stretches); an older ring gets its holes closed once, without re-ordering the stretches already built.
     */
    public int wallRingVersion;
    /** Border fortifications already built (chunk keys). */
    public java.util.Set<Long> fortified;
    /** Kingdom: centres of the other settlements, high road cells still to build, planner round robin. */
    public java.util.List<BlockPos> settlements;
    public java.util.List<Long> traktPending;
    public int settlementTurn;
    /** Town redesign (plan v2): new street cells left, buildings to demolish, quarters to level, state. */
    public java.util.List<Long> planRoads;
    public java.util.List<String> planDemolish;
    public java.util.List<int[]> planQuarters;
    public long planCreated;
    public boolean planActive;
    public boolean planDue;
    /**
     * Redesign started by an admin / the panel ("teraz"): worked off every few seconds without the daily levelling
     * limit; {@code planFree} = the treasury does not pay.
     */
    public boolean planForced;
    public boolean planFree;
    /** Quarters to fill with buildings {minX, minZ, maxX, maxZ} (after their levelling), and the totals (progress). */
    public java.util.List<int[]> planFill;
    public int planQuartersTotal;
    public int planFillTotal;
    /** The square {minX, minZ, maxX, maxZ}; levels of the quarters to level and of the square once computed. */
    public int[] planSquare;
    public int planSquareLevel = Integer.MIN_VALUE;
    public java.util.List<int[]> planLevels;
    public boolean planLevelsReady;
    /** Terrain planning version; older active plans recompute their remaining grading without cancelling builds. */
    public int planTerrainVersion;
    /** Levelling done today (epoch day) — the daily limit. */
    public long levelDay;
    public int levelBlocks;
    /** Raw materials of the country: drewno, kamien, rudy, ryby → amount. */
    public java.util.Map<String, Long> resources = new java.util.LinkedHashMap<>();
    /** Legacy resources remain untouched; new physical production never credits that map. */
    public java.util.Map<String, ProductionBinding> productionStores = new java.util.LinkedHashMap<>();
    /** Durable intent: any marker found at startup pauses that workplace pending explicit administrative audit. */
    public java.util.Set<String> productionIntents = new java.util.LinkedHashSet<>();

    public java.util.Map<String, ProductionBinding> productionStores() {
        if (productionStores == null) productionStores = new java.util.LinkedHashMap<>();
        return productionStores;
    }
    public java.util.Set<String> productionIntents() {
        if (productionIntents == null) productionIntents = new java.util.LinkedHashSet<>();
        return productionIntents;
    }
    /** City walls: the rectangle {minX, minZ, maxX, maxZ} already walled (null = none). */
    public int[] walls;
    /** Epoch rebuilding: since when (epoch millis, 0 = none), epoch of the road surface, last demolition. */
    public long rebuildSince;
    public int roadsEra;
    public long lastDemolition;
    public int rebuildTurn;
    /** Farmland blocks of adopted villages (count as fields: food). */
    public int villageFarmland;
    /** Poor harvest ("nieurodzaj"): active until (epoch millis, 0 = none) and no new one before {@code blightCooldown}. */
    public volatile long blightUntil;
    public long blightCooldown;
    /** Visible work of today (epoch day {@code workDay}): food delivered by farmers, fish caught by fishermen. */
    public long workDay;
    public long workFood;
    public long workFish;
    /** Mines: output of the last daily tick (ore, stone, precious) and its sold value, for {@code /ludnosc kopalnia}. */
    public long lastMineOre;
    public long lastMineStone;
    public long lastMinePrecious;
    public long lastMineSold;

    public CountryState() {
    }

    public java.util.Map<String, Long> resources() {
        if (this.resources == null) {
            this.resources = new java.util.LinkedHashMap<>();
        }
        return this.resources;
    }

    public java.util.Map<String, int[]> underground() {
        if (this.underground == null) {
            this.underground = new java.util.LinkedHashMap<>();
        }
        return this.underground;
    }

    public java.util.Map<String, int[]> mines() {
        if (this.mines == null) {
            this.mines = new java.util.LinkedHashMap<>();
        }
        return this.mines;
    }

    /** Committed output of the workplaces: building id → epoch hour → material key → items (last 24 h, see OutputLedger). */
    public java.util.Map<String, java.util.Map<String, java.util.Map<String, Integer>>> output24h = new java.util.LinkedHashMap<>();

    public java.util.Map<String, java.util.Map<String, java.util.Map<String, Integer>>> output24h() {
        if (this.output24h == null) {
            this.output24h = new java.util.LinkedHashMap<>();
        }
        return this.output24h;
    }

    public java.util.Map<String, int[]> workshops() {
        if (this.workshops == null) {
            this.workshops = new java.util.LinkedHashMap<>();
        }
        return this.workshops;
    }

    public java.util.Map<String, int[]> quarries() {
        if (this.quarries == null) {
            this.quarries = new java.util.LinkedHashMap<>();
        }
        return this.quarries;
    }

    public Set<Long> roads() {
        if (this.roads == null) {
            this.roads = new LinkedHashSet<>();
        }
        return this.roads;
    }

    public Set<BlockPos> chests() {
        if (this.granaryChests == null) {
            this.granaryChests = new LinkedHashSet<>();
        }
        return this.granaryChests;
    }
}
