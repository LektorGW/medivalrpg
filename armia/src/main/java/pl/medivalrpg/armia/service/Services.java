// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.service;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Access to the other MEDIVALRPG plugins. Kraje and Skarbiec are hard dependencies (loaded once in onEnable);
 * Ludnosc is looked up lazily because it is a soft dependency and may register its service late.
 */
public final class Services {

    private final KrajeApi kraje;
    private final SkarbiecApi skarbiec;
    private final Logger logger;
    private Population population;
    private Progress progress;

    private Services(final KrajeApi kraje, final SkarbiecApi skarbiec, final Logger logger) {
        this.kraje = kraje;
        this.skarbiec = skarbiec;
        this.logger = logger;
    }

    /** Empty when Kraje or Skarbiec did not register their API. */
    public static Optional<Services> load(final Logger logger) {
        final KrajeApi k = Bukkit.getServicesManager().load(KrajeApi.class);
        final SkarbiecApi s = Bukkit.getServicesManager().load(SkarbiecApi.class);
        if (k == null || s == null) {
            return Optional.empty();
        }
        return Optional.of(new Services(k, s, logger));
    }

    public KrajeApi kraje() {
        return this.kraje;
    }

    public SkarbiecApi skarbiec() {
        return this.skarbiec;
    }

    /** Ludnosc if installed and enabled, otherwise {@link Population#NONE}. */
    public Population population() {
        if (this.population != null && this.population.present()) {
            return this.population;
        }
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("Ludnosc");
        if (plugin == null || !plugin.isEnabled()) {
            return Population.NONE;
        }
        this.population = LudnoscBridge.create(this.logger);
        return this.population;
    }

    /** Barracks of a country, cached briefly (the healing pass and limit checks ask often). */
    private record BarracksCache(java.util.List<pl.medivalrpg.armia.logic.BarracksRules.Barracks> list, long at,
                                 pl.medivalrpg.armia.logic.BarracksRules.Settings settings, String building) {
    }

    private static final long BARRACKS_CACHE_MS = 5_000L;
    private final java.util.Map<UUID, BarracksCache> barracksCache = new java.util.HashMap<>();

    /**
     * Main thread. Barracks of a country known to Ludnosc (working and out of order), newest view at most 5 s old.
     * Empty without Ludnosc (or with an older one) — barracks then give nothing and nothing else changes.
     */
    public java.util.List<pl.medivalrpg.armia.logic.BarracksRules.Barracks> barracks(
        final UUID country, final String building, final pl.medivalrpg.armia.logic.BarracksRules.Settings settings) {
        if (country == null) {
            return java.util.List.of();
        }
        final long now = System.currentTimeMillis();
        final BarracksCache hit = this.barracksCache.get(country);
        if (hit != null && now - hit.at() < BARRACKS_CACHE_MS && hit.settings() == settings && java.util.Objects.equals(hit.building(), building)) {
            return hit.list();
        }
        final Population pop = this.population();
        final java.util.List<pl.medivalrpg.armia.logic.BarracksRules.Barracks> out = new java.util.ArrayList<>();
        if (pop.present()) {
            for (final Population.BuildingView b : pop.buildings(country)) {
                pl.medivalrpg.armia.logic.BarracksRules.classify(b.type(), b.function(), b.beds(), b.world(), b.x(), b.y(), b.z(),
                    building, settings).ifPresent(out::add);
            }
        }
        final java.util.List<pl.medivalrpg.armia.logic.BarracksRules.Barracks> list = java.util.List.copyOf(out);
        if (this.barracksCache.size() > 512) {
            this.barracksCache.clear();
        }
        this.barracksCache.put(country, new BarracksCache(list, now, settings, building));
        return list;
    }

    /** Eras of the Postep plugin (never null after Armia started; neutral without Postep). */
    public Progress progress() {
        return this.progress;
    }

    public void setProgress(final Progress progress) {
        this.progress = progress;
    }

    /** Ludnosc disabled/reloaded: look it up again next time. */
    public void resetPopulation() {
        this.population = null;
        this.barracksCache.clear();
    }

    /** Treasury account of a country (KrajInfo's account, or the SkarbiecApi convention). */
    public String treasury(final UUID country) {
        return this.kraje.getCountry(country)
            .map(KrajInfo::treasuryAccount)
            .filter(a -> a != null && !a.isBlank())
            .orElse(SkarbiecApi.countryAccount(country));
    }

    public String money(final long copper) {
        return this.skarbiec.format(copper);
    }

    /** Separate class so LudnoscApi is only resolved when Ludnosc is present. */
    private static final class LudnoscBridge {
        static Population create(final Logger logger) {
            final pl.medivalrpg.ludnosc.api.LudnoscApi api = Bukkit.getServicesManager().load(pl.medivalrpg.ludnosc.api.LudnoscApi.class);
            return api == null ? Population.NONE : new LudnoscPopulation(api, logger);
        }
    }
}
