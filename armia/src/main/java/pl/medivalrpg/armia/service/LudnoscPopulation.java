// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import pl.medivalrpg.ludnosc.api.LudnoscApi;

/** {@link Population} backed by {@link LudnoscApi}. Only loaded when the Ludnosc plugin is enabled. */
final class LudnoscPopulation implements Population {

    private final LudnoscApi api;
    private final Logger logger;

    LudnoscPopulation(final LudnoscApi api, final Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public int population(final UUID country) {
        try {
            return this.api.population(country);
        } catch (final RuntimeException e) {
            this.warn("population", e);
            return 0;
        }
    }

    @Override
    public int housing(final UUID country) {
        try {
            return this.api.housing(country);
        } catch (final RuntimeException e) {
            this.warn("housing", e);
            return 0;
        }
    }

    @Override
    public long dailyTax(final UUID country) {
        try {
            return this.api.dailyTaxIncome(country);
        } catch (final RuntimeException e) {
            this.warn("dailyTaxIncome", e);
            return 0;
        }
    }

    @Override
    public int settlementLevel(final UUID country) {
        try {
            return this.api.settlementTier(country).map(t -> t.level()).orElse(0);
        } catch (final LinkageError | RuntimeException e) {
            return 0;
        }
    }

    @Override
    public List<Fort> fortifications(final UUID country) {
        try {
            final var info = this.api.info(country);
            if (info.isEmpty() || info.get().buildings() == null) {
                return List.of();
            }
            final List<Fort> out = new java.util.ArrayList<>();
            for (final var b : info.get().buildings()) {
                if (b == null || b.world() == null) {
                    continue;
                }
                final String kind = fortKind(b.type(), b.function());
                if (kind != null) {
                    out.add(new Fort(b.world(), b.x(), b.z(), kind));
                }
            }
            return out;
        } catch (final LinkageError e) {
            this.old("info", e);
            return List.of();
        } catch (final RuntimeException e) {
            this.warn("info", e);
            return List.of();
        }
    }

    @Override
    public List<BuildingView> buildings(final UUID country) {
        try {
            final var info = this.api.info(country);
            if (info.isEmpty() || info.get().buildings() == null) {
                return List.of();
            }
            final List<BuildingView> out = new java.util.ArrayList<>();
            for (final var b : info.get().buildings()) {
                if (b != null && b.world() != null) {
                    out.add(new BuildingView(b.type(), b.function(), b.beds(), b.world(), b.x(), b.y(), b.z()));
                }
            }
            return out;
        } catch (final LinkageError e) {
            this.old("info", e);
            return List.of();
        } catch (final RuntimeException e) {
            this.warn("info", e);
            return List.of();
        }
    }

    /** Ludnosc building → fortification kind by function name ("mur", "brama", "wieża", "zamek", "pałac") or type id. */
    static String fortKind(final String type, final String function) {
        final String f = function == null ? "" : function.toLowerCase(java.util.Locale.ROOT);
        final String t = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        if (f.startsWith("zamek") || f.startsWith("pałac") || t.startsWith("zamek") || t.startsWith("palac")) {
            return "CASTLE";
        }
        if (f.startsWith("wieża") || t.startsWith("wieza")) {
            return "TOWER";
        }
        if (f.startsWith("mur") || f.startsWith("brama") || t.startsWith("mur") || t.startsWith("brama")) {
            return "WALL";
        }
        return null;
    }

    @Override
    public Optional<Conscripted> conscript(final UUID country, final Location near) {
        try {
            return this.api.conscript(country, near).map(c -> new Conscripted(c.profession(), c.location()));
        } catch (final RuntimeException e) {
            this.warn("conscript", e);
            return Optional.empty();
        }
    }

    @Override
    public Collection<String> buildingTypes() {
        try {
            return List.copyOf(this.api.buildingTypes());
        } catch (final RuntimeException e) {
            this.warn("buildingTypes", e);
            return List.of();
        }
    }

    @Override
    public String orderBuilding(final UUID country, final String type, final Location origin, final int rotation) {
        try {
            return this.api.orderBuilding(country, type, origin, rotation).name();
        } catch (final RuntimeException e) {
            this.warn("orderBuilding", e);
            return "ERROR";
        }
    }

    @Override
    public boolean returnCitizen(final UUID country, final Location location, final String profession) {
        try {
            return this.api.returnCitizen(country, location, profession);
        } catch (final LinkageError e) {
            this.old("returnCitizen", e); // older Ludnosc jar without the method
            return false;
        } catch (final RuntimeException e) {
            this.warn("returnCitizen", e);
            return false;
        }
    }

    @Override
    public Optional<Boolean> hasBuilding(final UUID country, final String type) {
        try {
            return Optional.of(this.api.hasBuilding(country, type));
        } catch (final LinkageError e) {
            this.old("hasBuilding", e);
            return Optional.empty();
        } catch (final RuntimeException e) {
            this.warn("hasBuilding", e);
            return Optional.empty();
        }
    }

    @Override
    public String developSettlement(final UUID country, final long budget) {
        try {
            return this.api.developSettlement(country, budget).name();
        } catch (final LinkageError e) {
            this.old("developSettlement", e);
            return "UNKNOWN_BUILDING";
        } catch (final RuntimeException e) {
            this.warn("developSettlement", e);
            return "ERROR";
        }
    }

    @Override
    public Optional<Location> buildingSpot(final UUID country, final String type) {
        try {
            return this.api.info(country).flatMap(info -> info.buildings().stream()
                .filter(b -> b.type().equalsIgnoreCase(type))
                .findFirst()
                .flatMap(b -> {
                    final org.bukkit.World w = org.bukkit.Bukkit.getWorld(b.world());
                    return w == null ? Optional.empty() : Optional.of(new Location(w, b.x() + 0.5, b.y(), b.z() + 0.5));
                }));
        } catch (final LinkageError e) {
            this.old("info", e);
            return Optional.empty();
        } catch (final RuntimeException e) {
            this.warn("info", e);
            return Optional.empty();
        }
    }

    @Override
    public List<Requirement> requirements(final UUID country) {
        try {
            final List<LudnoscApi.BuildingRequirement> list = this.api.buildingRequirements(country);
            if (list == null) {
                return List.of();
            }
            return list.stream().filter(r -> r != null && r.type() != null)
                .map(r -> new Requirement(r.type(), r.available(), r.built(), r.cost())).toList();
        } catch (final LinkageError e) {
            this.old("buildingRequirements", e);
            return List.of();
        } catch (final RuntimeException e) {
            this.warn("buildingRequirements", e);
            return List.of();
        }
    }

    @Override
    public String orderBuildingAuto(final UUID country, final String type) {
        try {
            final LudnoscApi.BuildOrderResult r = this.api.orderBuildingAuto(country, type);
            return r == null ? "ERROR" : r.name();
        } catch (final LinkageError e) {
            this.old("orderBuildingAuto", e);
            return "UNKNOWN_BUILDING";
        } catch (final RuntimeException e) {
            this.warn("orderBuildingAuto", e);
            return "ERROR";
        }
    }

    @Override
    public int adjustSettlers(final UUID country, final int delta) {
        try {
            return this.api.adjustSettlers(country, delta);
        } catch (final LinkageError e) {
            this.old("adjustSettlers", e);
            return 0;
        } catch (final RuntimeException e) {
            this.warn("adjustSettlers", e);
            return 0;
        }
    }

    @Override
    public boolean adjustFood(final UUID country, final long delta) {
        try {
            return this.api.adjustFood(country, delta);
        } catch (final LinkageError e) {
            this.old("adjustFood", e);
            return false;
        } catch (final RuntimeException e) {
            this.warn("adjustFood", e);
            return false;
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<Optional<Village>> findVillage(final Location near, final int radius) {
        try {
            final java.util.concurrent.CompletableFuture<Optional<LudnoscApi.VillageInfo>> f = this.api.findVillage(near, radius);
            if (f == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
            }
            return f.thenApply(o -> o == null ? Optional.<Village>empty() : o.map(v -> new Village(v.world(), v.bellX(), v.bellY(), v.bellZ(),
                v.chunkKeys() == null ? java.util.Set.of() : java.util.Set.copyOf(v.chunkKeys()), v.beds(), v.villagers(), v.jobSites(), v)));
        } catch (final LinkageError e) {
            this.old("findVillage", e);
        } catch (final RuntimeException e) {
            this.warn("findVillage", e);
        }
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public int adoptVillage(final UUID country, final Village village) {
        if (!(village.handle() instanceof LudnoscApi.VillageInfo info)) {
            return -1;
        }
        try {
            return this.api.adoptVillage(country, info);
        } catch (final LinkageError e) {
            this.old("adoptVillage", e);
            return -1;
        } catch (final RuntimeException e) {
            this.warn("adoptVillage", e);
            return -1;
        }
    }

    private boolean warnedOld;

    private void old(final String method, final LinkageError e) {
        if (!this.warnedOld) {
            this.warnedOld = true;
            this.logger.warning("Ludnosc nie ma metody LudnoscApi." + method + " (" + e.getClass().getSimpleName() + ") — używam zastępczej logiki Armii");
        }
    }

    private void warn(final String method, final RuntimeException e) {
        this.logger.log(Level.WARNING, "LudnoscApi." + method + " rzuciło wyjątek", e);
    }
}
