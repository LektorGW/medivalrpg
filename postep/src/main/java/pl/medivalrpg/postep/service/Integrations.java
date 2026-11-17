// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.service;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.postep.logic.NpcStrategy;

/**
 * Optional plugins (soft dependencies). Ludnosc and Armia are looked up lazily on use; their API classes are touched
 * only inside the bridge classes, and every call is guarded against {@link LinkageError} (missing or older API jar),
 * so Postep works without them.
 */
public final class Integrations {

    /** Data from Ludnosc. */
    public interface Population {
        boolean present();

        int population(UUID country);

        /** Settlement tier level (1..), -1 when unknown. */
        int tier(UUID country);

        /** Finished building type → count (main thread). */
        Map<String, Integer> buildings(UUID country);

        long dailyTax(UUID country);

        Population NONE = new Population() {
            @Override
            public boolean present() {
                return false;
            }

            @Override
            public int population(final UUID country) {
                return 0;
            }

            @Override
            public int tier(final UUID country) {
                return -1;
            }

            @Override
            public Map<String, Integer> buildings(final UUID country) {
                return Map.of();
            }

            @Override
            public long dailyTax(final UUID country) {
                return 0;
            }
        };
    }

    /** Data from Armia's NPC AI. */
    public interface NpcAi {
        boolean present();

        Optional<NpcStrategy.Personality> personality(UUID country);

        /** Income of the last 24 h in copper, empty when unknown. */
        OptionalLong income24h(UUID country);

        /** Line in the NPC decision log; {@code category} = NpcLogCategory name or Polish name. */
        void log(UUID country, String category, String text);

        NpcAi NONE = new NpcAi() {
            @Override
            public boolean present() {
                return false;
            }

            @Override
            public Optional<NpcStrategy.Personality> personality(final UUID country) {
                return Optional.empty();
            }

            @Override
            public OptionalLong income24h(final UUID country) {
                return OptionalLong.empty();
            }

            @Override
            public void log(final UUID country, final String category, final String text) {
            }
        };
    }

    private final Logger logger;
    private Population population;
    private NpcAi npcAi;

    public Integrations(final Logger logger) {
        this.logger = logger;
    }

    public Population population() {
        if (this.population != null && this.population.present()) {
            return this.population;
        }
        if (!enabled("Ludnosc")) {
            return Population.NONE;
        }
        try {
            this.population = LudnoscBridge.create(this.logger);
        } catch (final LinkageError e) {
            this.logger.warning("Ludnosc jest, ale jej API jest niezgodne: " + e);
            this.population = Population.NONE;
        }
        return this.population;
    }

    public NpcAi npcAi() {
        if (this.npcAi != null && this.npcAi.present()) {
            return this.npcAi;
        }
        if (!enabled("Armia")) {
            return NpcAi.NONE;
        }
        try {
            this.npcAi = ArmiaBridge.create(this.logger);
        } catch (final LinkageError e) {
            this.logger.warning("Armia jest, ale jej API jest niezgodne: " + e);
            this.npcAi = NpcAi.NONE;
        }
        return this.npcAi;
    }

    /** A soft dependency was enabled/disabled: look it up again next time. */
    public void reset() {
        this.population = null;
        this.npcAi = null;
    }

    private static boolean enabled(final String name) {
        final Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    // ------------------------------------------------------------------ bridges (resolve foreign classes lazily)

    private static final class LudnoscBridge {
        static Population create(final Logger logger) {
            final pl.medivalrpg.ludnosc.api.LudnoscApi api = Bukkit.getServicesManager().load(pl.medivalrpg.ludnosc.api.LudnoscApi.class);
            return api == null ? Population.NONE : new LudnoscPopulation(api, logger);
        }
    }

    private static final class ArmiaBridge {
        static NpcAi create(final Logger logger) {
            final pl.medivalrpg.armia.api.ArmiaApi api = Bukkit.getServicesManager().load(pl.medivalrpg.armia.api.ArmiaApi.class);
            return api == null ? NpcAi.NONE : new ArmiaNpcAi(api, logger);
        }
    }
}
