// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.template.BlockStates;

/** {@link LudnoscApi} registered in the ServicesManager. Reads are registry snapshots (any thread). */
public final class LudnoscApiImpl implements LudnoscApi {

    private final LudnoscPlugin plugin;
    private final TerrainLevelService terrainLevels;
    private final java.util.Map<UUID,LudnoscInfo> infoSnapshots=new java.util.concurrent.ConcurrentHashMap<>();
    private int snapshotTurn;

    public LudnoscApiImpl(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.terrainLevels = new TerrainLevelService(plugin);
        for(var country:plugin.kraje().getCountries()) this.captureInfo(country.id()).ifPresent(info -> this.infoSnapshots.put(country.id(),info));
        Bukkit.getScheduler().runTaskTimer(plugin,() -> {
            final var countries=new ArrayList<>(plugin.kraje().getCountries());
            if(!countries.isEmpty()) {
                UUID id=countries.get(Math.floorMod(this.snapshotTurn++,countries.size())).id();
                this.captureInfo(id).ifPresent(info -> this.infoSnapshots.put(id,info));
            }
            this.infoSnapshots.keySet().removeIf(id -> plugin.kraje().getCountry(id).isEmpty());
        },1L,20L);
    }

    @Override
    public String previewTerrainLevel(final UUID country, final String world, final int minX, final int minZ,
                                      final int maxX, final int maxZ, final Integer targetY) {
        requireMain();
        return this.terrainLevels.preview(country,world,minX,minZ,maxX,maxZ,targetY);
    }

    @Override
    public BuildOrderResult orderTerrainLevel(final UUID country, final String world, final int minX, final int minZ,
                                              final int maxX, final int maxZ, final int targetY, final boolean free) {
        requireMain();
        return this.terrainLevels.order(country,world,minX,minZ,maxX,maxZ,targetY,free);
    }

    @Override
    public int requestFireRecovery(final UUID country, final String world, final int minX, final int minZ,
                                   final int maxX, final int maxZ) {
        requireMain();
        return this.plugin.repairs().requestFireRecovery(country,world,minX,minZ,maxX,maxZ);
    }

    @Override
    public int population(final UUID countryId) {
        return countryId == null ? 0 : this.plugin.data().population(countryId);
    }

    @Override
    public int housing(final UUID countryId) {
        return countryId == null ? 0 : this.plugin.data().housing(countryId);
    }

    @Override
    public Collection<UUID> citizens(final UUID countryId) {
        return countryId == null ? List.of() : this.plugin.data().citizenIds(countryId);
    }

    @Override
    public Optional<UUID> countryOf(final Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        final CitizenRecord r = this.plugin.data().citizen(entity.getUniqueId());
        return r == null ? Optional.empty() : Optional.of(r.countryId());
    }

    @Override
    public long dailyTaxIncome(final UUID countryId) {
        return countryId == null ? 0 : this.info(countryId).map(LudnoscInfo::dailyTax).orElse(0L);
    }

    @Override
    public Optional<Conscript> conscript(final UUID countryId, final Location near) {
        requireMain();
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(countryId))return Optional.empty();
        return countryId == null ? Optional.empty() : this.plugin.citizens().conscript(countryId, near);
    }

    /**
     * Main thread. Spawns an adult citizen at {@code location} or the nearest safe spot on the country's land (else the
     * capital), with the given profession (none when unknown). Above the housing limit too.
     */
    @Override
    public boolean returnCitizen(final UUID countryId, final Location location, final String profession) {
        requireMain();
        return this.plugin.citizens().returnCitizen(countryId, location, profession);
    }

    /** Thread-safe: the country owns a finished (or admin-registered, type {@code zgloszony}) building of this type. */
    @Override
    public boolean hasBuilding(final UUID countryId, final String buildingType) {
        return this.plugin.data().hasBuilding(countryId, buildingType);
    }

    @Override
    public Collection<String> buildingTypes() {
        return List.copyOf(this.plugin.templates().ids());
    }

    /**
     * Origin = centre of the footprint at floor level (the floor replaces the block at {@code origin}); the door faces
     * south at rotation 0 and turns clockwise. Orders in unloaded chunks are accepted and the terrain is checked when
     * the site loads (obstructed → cancelled with a full refund).
     */
    @Override
    public BuildOrderResult orderBuilding(final UUID countryId, final String buildingType, final Location origin,
                                          final int rotation) {
        requireMain();
        if (countryId == null || origin == null || origin.getWorld() == null) {
            return BuildOrderResult.NOT_ON_OWN_LAND;
        }
        if (buildingType == null || this.plugin.templates().get(buildingType) == null) {
            return BuildOrderResult.UNKNOWN_BUILDING;
        }
        // explicit coordinates (admin / panel): build there even on rough terrain, refuse only another building or foreign land
        final var out = this.plugin.construction().orderDetailed(countryId, buildingType, origin.getWorld(), origin.getBlockX(),
            origin.getBlockY(), origin.getBlockZ(), BlockStates.quarterTurns(rotation), false, "API", null, this.plugin.automaticBuildingAllowed(countryId));
        if (out.result() != BuildOrderResult.OK) {
            this.plugin.settlement().setFailure(countryId, this.plugin.construction().lastCheckReason(countryId));
        } else {
            final String warn = this.plugin.construction().lastCheckReason(countryId);
            this.plugin.settlement().setFailure(countryId, warn != null && warn.startsWith("uwaga") ? warn : null);
        }
        return out.result();
    }

    @Override
    public List<String> setDesignPlan(final UUID countryId, final String planJson) {
        requireMain();
        return countryId == null ? List.of("brak kraju") : this.plugin.design().set(countryId, planJson);
    }

    @Override
    public Optional<String> designPlan(final UUID countryId) {
        return this.plugin.design().json(countryId);
    }

    @Override
    public boolean clearDesignPlan(final UUID countryId) {
        requireMain();
        return countryId != null && this.plugin.design().clear(countryId);
    }

    @Override
    public Optional<String> designPlanPreview(final UUID countryId) {
        return countryId == null ? Optional.empty() : this.plugin.design().preview(countryId);
    }

    @Override
    public int forceRedesign(final UUID countryId, final boolean free) {
        requireMain();
        return countryId == null ? -1 : this.plugin.redesign().forceOrders(countryId, free); // 0 = already in progress (no stacking)
    }

    @Override
    public boolean forceRoads(final UUID countryId) {
        requireMain();
        return countryId != null && this.plugin.settlement().resurfaceRoads(countryId, Long.MAX_VALUE, "panel",
            !this.plugin.settings().redesignAdminPays) == BuildOrderResult.OK;
    }

    @Override
    public int forceModernize(final UUID countryId) {
        requireMain();
        return countryId == null ? 0 : this.plugin.repairs().forceModernize(countryId, !this.plugin.settings().redesignAdminPays);
    }

    @Override
    public java.util.Map<String, Long> resources(final UUID countryId) {
        return this.plugin.resources().stockSnapshot(countryId);
    }

    @Override
    public Optional<String> lastPlanFailure(final UUID countryId) {
        return Optional.ofNullable(this.plugin.settlement().lastFailure(countryId));
    }

    @Override
    public Optional<pl.medivalrpg.ludnosc.api.SeasonInfo> season() {
        return this.plugin.seasons().season();
    }

    @Override
    public boolean poorHarvest(final UUID countryId) {
        return countryId != null && this.plugin.seasons().blight(countryId);
    }

    @Override
    public double foodProductionMultiplier(final UUID countryId) {
        return countryId == null ? 1.0 : this.plugin.seasons().foodFactor(countryId);
    }

    /** Immutable main-thread publication, safe from panel/read-only threads; never touches the world on read. */
    @Override
    public Optional<LudnoscInfo> info(final UUID countryId) {
        return Optional.ofNullable(countryId==null ? null : this.infoSnapshots.get(countryId));
    }
    private Optional<LudnoscInfo> captureInfo(final UUID countryId) {
        requireMain();
        if (countryId == null) {
            return Optional.empty();
        }
        final LudnoscData data = this.plugin.data();
        final CountryState st = data.stateIfPresent(countryId);
        final List<LudnoscInfo.Building> buildings = new ArrayList<>();
        for (final Building b : data.buildingsOf(countryId)) {
            final BlockPos at = b.spawn() != null ? b.spawn() : new BlockPos(b.box().world(), b.box().centerX(), b.box().minY(), b.box().centerZ());
            // the record has no state field: the state is appended to the function text ("mieszkanie • uszkodzony 12%")
            String function = b.function() == null ? "" : b.function().displayName();
            if(ProductionStorageService.productive(b)) function+=" • "+this.plugin.production().status(b);
            if (this.plugin.redesign().toDemolish(b)) {
                function += " • do rozbiórki (plan v2)";
            }
            if (PlayerBuildingService.isPlayer(b)) {
                // registered by the players: never repaired by the plugin; without its key blocks the function is off
                final var own = this.plugin.playerBuildings();
                if (own != null && own.def(b) != null && !own.working(b)) {
                    function = own.def(b).function().displayName() + " • nieczynny — w naprawie u graczy";
                } else {
                    function += " • zgłoszony przez graczy";
                }
            } else if (this.plugin.construction().repairing(b.id())) {
                function += " • w naprawie";
            } else {
                final RepairService.Damage d = this.plugin.repairs().damageOf(b.id());
                if (d != null && d.percent() > 0) {
                    function += " • uszkodzony " + d.percent() + "%";
                }
            }
            final String name = PlayerBuildingService.isPlayer(b) && this.plugin.playerBuildings() != null
                ? this.plugin.playerBuildings().plainLabel(b) : this.typeName(b.type());
            buildings.add(new LudnoscInfo.Building(b.type(), name, function, b.beds(), at.world(), at.x(),
                at.y(), at.z()));
        }
        final List<LudnoscInfo.Site> sites = new ArrayList<>();
        for (final ConstructionSite s : data.sitesOf(countryId)) {
            sites.add(new LudnoscInfo.Site(s.type, this.typeName(s.type), this.plugin.construction().percent(s),
                s.world, s.originX, s.originY, s.originZ));
        }
        return Optional.of(new LudnoscInfo(data.population(countryId), data.housing(countryId),
            this.plugin.granary().food(countryId), this.plugin.granary().capacity(countryId),
            this.plugin.economy().expectedFoodBalance(countryId), this.plugin.economy().expectedTax(countryId),
            st == null ? 0 : st.hungryDays, st == null ? 0 : st.homelessDays, List.copyOf(buildings), List.copyOf(sites),
            this.plugin.settlement().levelIndex(countryId) + 1,
            this.plugin.settings().levels.level(this.plugin.settlement().levelIndex(countryId)).name()));
    }

    private String typeName(final String type) {
        final String raw = BuildingService.REGISTERED_TYPE.equals(type) ? "dom (zgłoszony)" : this.plugin.construction().displayName(type);
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(raw == null ? "" : raw));
    }

    @Override
    public Optional<pl.medivalrpg.ludnosc.api.KingdomMapInfo> kingdomMap(final UUID countryId) {
        requireMain();
        if (countryId == null || this.plugin.kraje().getCountry(countryId).isEmpty()) return Optional.empty();
        final List<pl.medivalrpg.ludnosc.api.KingdomMapInfo.Settlement> centres = new ArrayList<>();
        final var positions = this.plugin.kingdom().settlements(countryId);
        for (int i = 0; i < Math.min(positions.size(), 64); i++) {
            final var p = positions.get(i);
            centres.add(new pl.medivalrpg.ludnosc.api.KingdomMapInfo.Settlement(p.world(), p.x(), p.z(), i == 0));
        }
        final List<pl.medivalrpg.ludnosc.api.KingdomMapInfo.Road> roads = new ArrayList<>();
        final CountryState state = this.plugin.data().stateIfPresent(countryId);
        if (state != null && !positions.isEmpty()) {
            final String world = positions.getFirst().world();
            roads.addAll(pl.medivalrpg.ludnosc.logic.KingdomMapGeometry.built(world,
                state.roads == null ? java.util.Set.of() : state.roads, 256));
            roads.addAll(pl.medivalrpg.ludnosc.logic.KingdomMapGeometry.planned(world,
                state.traktPending == null ? List.of() : state.traktPending, 32));
        }
        return Optional.of(new pl.medivalrpg.ludnosc.api.KingdomMapInfo(centres, roads));
    }

    // ------------------------------------------------------------------ villages

    @Override
    public java.util.concurrent.CompletableFuture<Optional<VillageInfo>> findVillage(final Location near, final int radius) {
        return this.plugin.villages().find(near, radius);
    }

    @Override
    public int adoptVillage(final UUID countryId, final VillageInfo village) {
        requireMain();
        return this.plugin.villages().adopt(countryId, village);
    }

    // ------------------------------------------------------------------ planner & web panel v2

    @Override
    public BuildOrderResult developSettlement(final UUID countryId, final long budget) {
        requireMain();
        return countryId == null ? BuildOrderResult.NOT_ON_OWN_LAND : this.plugin.settlement().develop(countryId, Math.max(0, budget));
    }

    @Override
    public BuildOrderResult orderBuildingAuto(final UUID countryId, final String buildingType) {
        requireMain();
        if (countryId == null || buildingType == null) {
            return BuildOrderResult.UNKNOWN_BUILDING;
        }
        return this.plugin.settlement().orderAuto(countryId, buildingType, "panel");
    }

    @Override
    public boolean adjustFood(final UUID countryId, final long delta) {
        requireMain();
        return countryId != null && this.plugin.economy().adjustFood(countryId, delta);
    }

    @Override
    public int adjustSettlers(final UUID countryId, final int delta) {
        requireMain();
        return countryId == null ? 0 : this.plugin.economy().adjustSettlers(countryId, delta);
    }

    @Override
    public List<QueuedOrderInfo> queue(final UUID countryId) {
        if (countryId == null) {
            return List.of();
        }
        final List<QueuedOrderInfo> out = new ArrayList<>();
        final List<pl.medivalrpg.ludnosc.model.QueuedOrder> q = this.plugin.data().queue(countryId);
        for (int i = 0; i < q.size(); i++) {
            final pl.medivalrpg.ludnosc.model.QueuedOrder o = q.get(i);
            out.add(new QueuedOrderInfo(i, o.type, o.world, o.originX, o.originY, o.originZ, o.rotation * 90, o.cost,
                o.orderedBy == null || o.orderedBy.isEmpty() ? "AI" : o.orderedBy, o.createdAt));
        }
        return List.copyOf(out);
    }

    @Override
    public boolean moveQueue(final UUID countryId, final int index, final int delta) {
        requireMain();
        if (countryId == null || delta == 0) {
            return false;
        }
        final int step = Integer.signum(delta);
        int at = index;
        boolean moved = false;
        for (int k = 0; k < Math.abs(delta) && this.plugin.data().moveQueued(countryId, at, step); k++) {
            at += step;
            moved = true;
        }
        return moved;
    }

    @Override
    public boolean cancelQueued(final UUID countryId, final int index) {
        requireMain();
        if (countryId == null) {
            return false;
        }
        final List<pl.medivalrpg.ludnosc.model.QueuedOrder> q = this.plugin.data().queue(countryId);
        if (index < 0 || index >= q.size()) {
            return false;
        }
        this.plugin.construction().cancelQueued(q.get(index), null);
        return true;
    }

    @Override
    public List<BuildingRequirement> buildingRequirements(final UUID countryId) {
        if (countryId == null) {
            return List.of();
        }
        final pl.medivalrpg.ludnosc.planner.BuildingChooser.Input in = this.plugin.settlement().input(countryId, Long.MAX_VALUE);
        final java.util.Map<String, Integer> built = new java.util.HashMap<>();
        for (final Building b : this.plugin.data().buildingsOf(countryId)) {
            built.merge(this.plugin.playerBuildings() == null ? b.type() : this.plugin.playerBuildings().kind(b), 1, Integer::sum);
        }
        final List<BuildingRequirement> out = new ArrayList<>();
        for (final pl.medivalrpg.ludnosc.template.BuildingTemplate t : this.plugin.catalog(countryId)) {
            final List<String> missing = this.plugin.settlement().requirementsMissing(t.meta(), in);
            out.add(new BuildingRequirement(t.id(), this.typeName(t.id()), t.meta().cost(), missing.isEmpty(), List.copyOf(missing),
                built.getOrDefault(t.id(), 0), t.meta().maxPerCountry() == 0 ? -1 : t.meta().maxPerCountry()));
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<SettlementTier> settlementTier(final UUID countryId) {
        if (countryId == null || this.plugin.kraje().getCountry(countryId).isEmpty()) {
            return Optional.empty();
        }
        final int idx = this.plugin.settlement().levelIndex(countryId);
        return Optional.of(new SettlementTier(idx + 1, this.plugin.settings().levels.level(idx).name()));
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("LudnoscApi: ta metoda wymaga głównego wątku serwera");
        }
    }
}
