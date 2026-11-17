// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 3 data ({@code manufaktury.json}): workshops of manufactories and their NPC workers. */
public final class Stage3Data {

    /** A worker: a former citizen of the seat country, now working in the workshop (a Mannequin in the world). */
    public static final class Worker {
        public String id;
        /** Entity uuid of the Mannequin (null while it is not spawned). */
        public String entity;
        public String name;
        /** Villager profession of the citizen (returned with it on dismissal). */
        public String profession;
        public long hiredAt;
        public long wage;
    }

    public static final class Workshop {
        public String id;
        public String firm;
        public String recipe;
        public String world;
        public int x;
        public int y;
        public int z;
        /** Input and output chests ({@code world:x:y:z}); production needs both. */
        public String input;
        public String output;
        public List<Worker> workers = new ArrayList<>();
        /** Work time (ms × workers) accumulated while the chunk was not loaded, spent when it loads (capped). */
        public long pendingMs;
        /** Batches of fuel left from the last coal (1 coal = 8 smelts). */
        public int fuelLeft;
        public long produced;
        public long createdAt;
        public String status = "";

        public String key() {
            return this.world + ":" + this.x + ":" + this.y + ":" + this.z;
        }
    }

    public int version = 1;
    public Map<String, Workshop> workshops = new LinkedHashMap<>();
    /** Unpaid daily wages are never kept: a worker who is not paid goes back to the country. */
    public String lastWages = "";
}
