// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import pl.medivalrpg.ludnosc.model.CitizenLife;

/**
 * {@code plugins/Ludnosc/zycie-obywateli.json}: citizens' homes, work, savings shares and families plus per-country
 * settings of self-built huts. Separate from {@code data.json}, so old saves load unchanged; a missing file means
 * "nobody assigned yet". Serialisation on the main thread, IO on one background thread: temp → {@code .bak} → atomic
 * move. The share book is re-checked against the real Skarbiec balance every day, so a lost write never loses money.
 */
public final class LifeStore {

    public static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Per-country settings and counters. */
    public static final class CountryLife {
        /** Self-built huts: null = config default. */
        public Boolean huts;
        public Integer hutLimit;
        /** Huts ordered by the citizens (lifetime) and the last order (epoch millis). */
        public int hutsOrdered;
        public long lastHut;
        /** Last daily settlement: wages owed / paid, meals paid, workers, unpaid workers. */
        public long lastWagesDue;
        public long lastWagesPaid;
        public long lastMeals;
        /** Market purchases of the last day (back to the households pool). */
        public long lastMarket;
        /** Income from work (households pool), NPC tax owed and collected from the citizens. */
        public long lastIncome;
        public long lastTaxDue;
        public long lastTaxPaid;
        public int lastWorkers;
        public int lastUnpaid;
        public long lastDay;
    }

    /** File content. */
    public static final class Snapshot {
        public int version = VERSION;
        public Map<UUID, CitizenLife> citizens = new LinkedHashMap<>();
        public Map<UUID, CountryLife> countries = new LinkedHashMap<>();
    }

    private final Logger logger;
    private final Path file;
    private final Path backup;
    private final Path temp;
    private final ExecutorService io;

    public LifeStore(final Path dataFolder, final Logger logger) {
        this.logger = logger;
        this.file = dataFolder.resolve("zycie-obywateli.json");
        this.backup = dataFolder.resolve("zycie-obywateli.json.bak");
        this.temp = dataFolder.resolve("zycie-obywateli.json.tmp");
        this.io = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Ludnosc-Zycie-Storage");
            t.setDaemon(true);
            return t;
        });
    }

    public static String encode(final Snapshot s) {
        return GSON.toJson(s);
    }

    /** Decodes, dropping broken entries; never null. */
    public static Snapshot decode(final String json) {
        final Snapshot s = json == null || json.isBlank() ? null : GSON.fromJson(json, Snapshot.class);
        final Snapshot out = new Snapshot();
        if (s == null) {
            return out;
        }
        if (s.citizens != null) {
            s.citizens.forEach((id, life) -> {
                if (id != null && life != null) {
                    if (life.bed < -1) {
                        life.bed = -1;
                    }
                    life.savings = Math.max(0, life.savings);
                    out.citizens.put(id, life);
                }
            });
        }
        if (s.countries != null) {
            s.countries.forEach((id, c) -> {
                if (id != null && c != null) {
                    out.countries.put(id, c);
                }
            });
        }
        return out;
    }

    /** Loads synchronously; a broken file falls back to the backup, a missing one to an empty snapshot. */
    public Snapshot load() {
        for (final Path p : new Path[] {this.file, this.backup}) {
            if (!Files.exists(p)) {
                continue;
            }
            try {
                return decode(Files.readString(p, StandardCharsets.UTF_8));
            } catch (final IOException | RuntimeException e) {
                this.logger.log(Level.SEVERE, "Nie udało się wczytać " + p.getFileName() + " (życie obywateli)", e);
                try {
                    Files.copy(p, p.resolveSibling(p.getFileName() + ".broken-" + System.currentTimeMillis()));
                } catch (final IOException ignored) {
                    // keep going with the backup
                }
            }
        }
        return new Snapshot();
    }

    public void saveAsync(final Snapshot s) {
        final String json = encode(s);
        this.io.execute(() -> this.write(json));
    }

    public void saveAndShutdown(final Snapshot s) {
        if (s != null) {
            final String json = encode(s);
            this.io.execute(() -> this.write(json));
        }
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.severe("Zapis życia obywateli trwa zbyt długo — przerywam oczekiwanie.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(final String json) {
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.temp, json, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(this.temp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać zycie-obywateli.json", e);
        }
    }
}
