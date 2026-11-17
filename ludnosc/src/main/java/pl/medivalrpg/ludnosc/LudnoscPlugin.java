// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.command.LudnoscCommand;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.config.Messages;
import pl.medivalrpg.ludnosc.listener.BuildingListener;
import pl.medivalrpg.ludnosc.listener.CitizenListener;
import pl.medivalrpg.ludnosc.listener.KrajeListener;
import pl.medivalrpg.ludnosc.listener.ProtectionListener;
import pl.medivalrpg.ludnosc.service.BuildingService;
import pl.medivalrpg.ludnosc.service.CitizenService;
import pl.medivalrpg.ludnosc.service.ConstructionService;
import pl.medivalrpg.ludnosc.service.EconomyService;
import pl.medivalrpg.ludnosc.service.GranaryService;
import pl.medivalrpg.ludnosc.service.LudnoscApiImpl;
import pl.medivalrpg.ludnosc.service.LudnoscData;
import pl.medivalrpg.ludnosc.service.PlanService;
import pl.medivalrpg.ludnosc.service.RepairService;
import pl.medivalrpg.ludnosc.service.SettlementService;
import pl.medivalrpg.ludnosc.service.TerrainCache;
import pl.medivalrpg.ludnosc.storage.DataStore;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;
import pl.medivalrpg.ludnosc.util.Keys;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** Ludnosc — NPC citizens of countries and building construction for MEDIVALRPG. */
public final class LudnoscPlugin extends JavaPlugin {

    private volatile LudnoscConfig settings;
    private volatile Messages messages;
    private KrajeApi kraje;
    private SkarbiecApi skarbiec;
    private Keys keys;
    private final LudnoscData data = new LudnoscData();
    private DataStore store;
    private boolean dataLoaded;
    private TemplateLibrary templates;
    private CitizenService citizens;
    private BuildingService buildings;
    private GranaryService granary;
    private ConstructionService construction;
    private PlanService plans;
    private EconomyService economy;
    private SettlementService settlement;
    private RepairService repairs;
    private pl.medivalrpg.ludnosc.service.RoyalRebuildService royalRebuild;
    private pl.medivalrpg.ludnosc.service.MineService mines;
    private pl.medivalrpg.ludnosc.hook.PostepHook postep;
    private pl.medivalrpg.ludnosc.hook.ZycieHook zycie;
    private pl.medivalrpg.ludnosc.service.VillageService villages;
    private pl.medivalrpg.ludnosc.service.ResourceService resources;
    private pl.medivalrpg.ludnosc.service.ProductionStorageService production;
    private pl.medivalrpg.ludnosc.service.LogisticsService logistics;
    private pl.medivalrpg.ludnosc.service.WorkshopService workshops;
    private pl.medivalrpg.ludnosc.service.WorkAlertService alerts;
    private pl.medivalrpg.ludnosc.service.SupplyDeliveryService supply;
    private pl.medivalrpg.ludnosc.service.RedesignService redesign;
    private pl.medivalrpg.ludnosc.service.KingdomService kingdom;
    private pl.medivalrpg.ludnosc.service.DesignService design;
    private pl.medivalrpg.ludnosc.service.UndergroundService underground;
    private pl.medivalrpg.ludnosc.service.SettlementTransportService transport;
    private pl.medivalrpg.ludnosc.service.SeasonService seasons;
    private pl.medivalrpg.ludnosc.service.WorkService work;
    private pl.medivalrpg.ludnosc.service.LifeService life;
    private TerrainCache terrain;
    private pl.medivalrpg.ludnosc.service.BlueprintService blueprints;
    private BukkitTask growthTask;
    private pl.medivalrpg.ludnosc.service.PlayerBuildingService playerBuildings;

    @Override
    public void onEnable() {
        this.getLogger().info("Ludnosc by Maciej Kaczmarczyk — lektoro.pl");
        this.postep = new pl.medivalrpg.ludnosc.hook.PostepHook(this.getLogger());
        this.zycie = new pl.medivalrpg.ludnosc.hook.ZycieHook(this.getLogger());
        this.saveDefaultConfig();
        this.reloadConfig();
        this.settings = new LudnoscConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());

        this.kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        if (this.kraje == null) {
            this.getLogger().severe("Brak KrajeApi (plugin Kraje) — Ludnosc zostaje wyłączona.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.skarbiec = Bukkit.getServicesManager().load(SkarbiecApi.class);
        if (this.skarbiec == null) {
            this.getLogger().warning("Brak SkarbiecApi — podatki NPC i płatne budowy są niedostępne.");
        }
        this.keys = new Keys(this);
        this.store = new DataStore(this.getDataFolder().toPath(), this.getLogger());
        try {
            this.store.load(this.data);
            this.dataLoaded = true;
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Nie udało się wczytać plugins/Ludnosc/data.json — plugin zostaje wyłączony, "
                + "aby nie nadpisać danych.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.templates = new TemplateLibrary(this);
        final int templateCount = this.templates.load(this.settings);
        this.blueprints = new pl.medivalrpg.ludnosc.service.BlueprintService(this); this.blueprints.loadAll();
        this.citizens = new CitizenService(this);
        this.buildings = new BuildingService(this);
        this.granary = new GranaryService(this);
        this.construction = new ConstructionService(this);
        this.plans = new PlanService(this);
        this.economy = new EconomyService(this);
        this.terrain = new TerrainCache(this);
        this.settlement = new SettlementService(this);
        this.repairs = new RepairService(this);
        this.mines = new pl.medivalrpg.ludnosc.service.MineService(this);
        this.villages = new pl.medivalrpg.ludnosc.service.VillageService(this);
        this.resources = new pl.medivalrpg.ludnosc.service.ResourceService(this);
        this.production = new pl.medivalrpg.ludnosc.service.ProductionStorageService(this);
        this.logistics = new pl.medivalrpg.ludnosc.service.LogisticsService(this);
        this.workshops = new pl.medivalrpg.ludnosc.service.WorkshopService(this);
        this.redesign = new pl.medivalrpg.ludnosc.service.RedesignService(this);
        this.kingdom = new pl.medivalrpg.ludnosc.service.KingdomService(this);
        this.design = new pl.medivalrpg.ludnosc.service.DesignService(this);
        this.underground = new pl.medivalrpg.ludnosc.service.UndergroundService(this);
        this.transport = new pl.medivalrpg.ludnosc.service.SettlementTransportService(this);
        this.royalRebuild=new pl.medivalrpg.ludnosc.service.RoyalRebuildService(this);
        this.seasons = new pl.medivalrpg.ludnosc.service.SeasonService(this);
        this.work = new pl.medivalrpg.ludnosc.service.WorkService(this);
        this.alerts = new pl.medivalrpg.ludnosc.service.WorkAlertService(this);
        this.supply = new pl.medivalrpg.ludnosc.service.SupplyDeliveryService(this);
        this.life = new pl.medivalrpg.ludnosc.service.LifeService(this);
        this.playerBuildings = new pl.medivalrpg.ludnosc.service.PlayerBuildingService(this);

        this.granary.rebuildIndex();
        this.citizens.dropDeadCountries();
        this.buildings.syncOwners();
        // countries disbanded while Ludnosc was not running
        final java.util.Set<UUID> known = new java.util.HashSet<>(this.data.knownCountries());
        this.data.allSites().forEach(s -> known.add(s.countryId));
        known.addAll(this.data.queuedCountries());
        for (final UUID id : known) {
            if (this.kraje.getCountry(id).isEmpty()) {
                this.construction.countryRemoved(id);
                this.buildings.countryRemoved(id);
                this.granary.countryRemoved(id);
            }
        }
        this.getLogger().info("Obywateli: " + this.data.allCitizens().size() + ", budynków: " + this.data.allBuildings().size()
            + ", placów budowy: " + this.data.allSites().size() + ", szablonów: " + templateCount);

        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(this.royalRebuild,this);
        pm.registerEvents(new CitizenListener(this), this);
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(new BuildingListener(this), this);
        pm.registerEvents(new KrajeListener(this), this);
        pm.registerEvents(this.seasons, this);
        pm.registerEvents(this.work, this);
        pm.registerEvents(this.life, this);
        pm.registerEvents(this.workshops, this);
        pm.registerEvents(new pl.medivalrpg.ludnosc.listener.SupplyProtectionListener(this.supply),this);
        pm.registerEvents(new pl.medivalrpg.ludnosc.listener.MineSafetyListener(this), this);
        // Postep (epochs) is optional and loads after us: hook its event when it gets enabled
        this.postep.listen(this, this::eraAdvanced);
        pm.registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onEnable(final org.bukkit.event.server.PluginEnableEvent e) {
                if (e.getPlugin().getName().equals("Postep")) {
                    LudnoscPlugin.this.postep.listen(LudnoscPlugin.this, LudnoscPlugin.this::eraAdvanced);
                }
            }
        }, this);

        final LudnoscCommand command = new LudnoscCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(command.tree(), "Ludność i budowanie MEDIVALRPG", List.of("lud"));
        });

        Bukkit.getServicesManager().register(LudnoscApi.class, new LudnoscApiImpl(this), this, ServicePriority.Normal);

        final var scheduler = Bukkit.getScheduler();
        scheduler.runTaskTimer(this,this.royalRebuild::tick,20L,1L);
        this.transport.start();
        this.getLogger().info("Budowanie: pełna kontrola obrysu i dojść, wykopy/nasypy do 6 bloków; "
            + "dostępne kamieniołomy warstwowe, mosty traktów i transport osad.");
        scheduler.runTaskTimer(this, () -> {
            this.construction.tick();
            this.plans.tick();
        }, ConstructionService.PERIOD, ConstructionService.PERIOD);
        scheduler.runTaskTimer(this, this.construction::moveBuilders, 2L, 2L);
        scheduler.runTaskTimer(this, this.mines::tick, 60L, pl.medivalrpg.ludnosc.service.MineService.PERIOD);
        scheduler.runTaskTimer(this, this.resources::tick, 80L, pl.medivalrpg.ludnosc.service.ResourceService.PERIOD);
        scheduler.runTaskTimer(this, this.production::tick, 1L, 1L);
        scheduler.runTaskTimer(this, this.logistics::tick, 140L, pl.medivalrpg.ludnosc.service.LogisticsService.PERIOD);
        scheduler.runTaskTimer(this, this.workshops::tick, 150L, pl.medivalrpg.ludnosc.service.WorkshopService.PERIOD);
        scheduler.runTaskTimer(this, this.alerts::tick, 120L, 20L);
        scheduler.runTaskTimer(this, this.supply::tick, 1L, 1L);
        scheduler.runTaskTimer(this,this.design::tickPlayerPlans,100L,60L);
        scheduler.runTaskTimer(this, this.redesign::tickPlayers, 2400L, 3600L);
        scheduler.runTaskTimer(this, this.redesign::tickForced, 600L, 600L);
        scheduler.runTaskTimer(this, this.underground::tick, 70L, pl.medivalrpg.ludnosc.service.UndergroundService.PERIOD);
        scheduler.runTaskTimer(this, () -> {
            this.terrain.tick();
            this.repairs.tick();
        }, 40L, 1L);
        scheduler.runTaskTimer(this, this.economy::checkDaily, 100L, 1200L);
        scheduler.runTaskTimer(this, this.work::tick, 90L, pl.medivalrpg.ludnosc.service.WorkService.PERIOD);
        scheduler.runTaskTimer(this, this.seasons::tick, 60L, pl.medivalrpg.ludnosc.service.SeasonService.PERIOD);
        scheduler.runTaskTimer(this, this.life::tick, 110L, pl.medivalrpg.ludnosc.service.LifeService.PERIOD);
        if (this.seasons.enabled()) {
            final var season = this.seasons.state();
            this.getLogger().info("Pora roku: " + season.season().displayName() + " (dzień " + season.day() + "/" + season.days() + ")");
        }
        scheduler.runTaskTimer(this, this.citizens::refreshLoaded, 6000L, 6000L);
        final long autosave = this.settings.autosaveMinutes * 1200L;
        scheduler.runTaskTimer(this, this::saveIfDirty, autosave, autosave);
        this.scheduleGrowth();
        // entities of chunks that were already loaded before the plugin enabled (startup / reload)
        scheduler.runTask(this, () -> {
            for (final World w : Bukkit.getWorlds()) {
                for (final Chunk c : w.getLoadedChunks()) {
                    if (c.isEntitiesLoaded()) {
                        this.citizens.onEntitiesLoad(c, List.of(c.getEntities()));
                    }
                }
            }
        });
    }

    private void scheduleGrowth() {
        if (this.growthTask != null) {
            this.growthTask.cancel();
        }
        final long period = this.settings.growthIntervalMinutes * 1200L;
        this.growthTask = Bukkit.getScheduler().runTaskTimer(this, this.economy::growthTick, period, period);
    }

    private void saveIfDirty() {
        if (this.data.takeDirty()) {
            this.store.saveAsync(this.data);
        }
    }

    /** Journal phase checkpoints also flush the normal citizen/building registry to its ordered writer. */
    public void saveRoyalRegistry(){this.store.saveAsync(this.data);}
    public java.util.concurrent.CompletableFuture<Void> saveRoyalCheckpoint(){return this.store.saveRoyalCheckpoint(this.data);}

    @Override
    public void onDisable() {
        if(this.supply!=null) this.supply.close();
        if(this.production!=null) this.production.shutdown();
        if(this.logistics!=null) this.logistics.shutdown();
        if(this.workshops!=null) this.workshops.shutdown();
        if(this.royalRebuild!=null)this.royalRebuild.close();
        Bukkit.getServicesManager().unregisterAll(this);
        if (this.transport != null) {
            this.transport.stop();
        }
        if (this.construction != null) {
            this.construction.shutdown();
        }
        if (this.mines != null) {
            this.mines.shutdown();
        }
        if (this.work != null) {
            this.work.shutdown();
        }
        if (this.resources != null) {
            this.resources.shutdown();
        }
        if (this.blueprints != null) this.blueprints.shutdown();
        if (this.life != null) this.life.shutdown();
        if (this.store != null) {
            this.store.saveAndShutdown(this.dataLoaded ? this.data : null);
        }
    }

    /** Reloads config.yml and templates. Returns the number of templates. */
    public int reload() {
        this.reloadConfig();
        this.settings = new LudnoscConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());
        this.construction.reload();
        final int n = this.templates.load(this.settings);
        if (this.blueprints != null) this.blueprints.reprice();
        this.citizens.reload();
        if (this.playerBuildings != null) this.playerBuildings.reload();
        this.scheduleGrowth();
        return n;
    }

    // ------------------------------------------------------------------ accessors

    public LudnoscConfig settings() {
        return this.settings;
    }

    public Messages messages() {
        return this.messages;
    }

    public KrajeApi kraje() {
        return this.kraje;
    }

    public SkarbiecApi skarbiec() {
        if (this.skarbiec == null) {
            this.skarbiec = Bukkit.getServicesManager().load(SkarbiecApi.class);
        }
        return this.skarbiec;
    }

    public Keys keys() {
        return this.keys;
    }

    public LudnoscData data() {
        return this.data;
    }

    public pl.medivalrpg.ludnosc.service.RoyalRebuildService royalRebuild(){return this.royalRebuild;}

    public TemplateLibrary templates() {
        return this.templates;
    }

    public CitizenService citizens() {
        return this.citizens;
    }

    public BuildingService buildings() {
        return this.buildings;
    }

    public GranaryService granary() {
        return this.granary;
    }

    public ConstructionService construction() {
        return this.construction;
    }

    public PlanService plans() {
        return this.plans;
    }

    public EconomyService economy() {
        return this.economy;
    }

    public SettlementService settlement() {
        return this.settlement;
    }

    public pl.medivalrpg.ludnosc.service.UndergroundService underground() {
        return this.underground;
    }

    public pl.medivalrpg.ludnosc.service.DesignService design() {
        return this.design;
    }

    public pl.medivalrpg.ludnosc.service.KingdomService kingdom() {
        return this.kingdom;
    }

    public pl.medivalrpg.ludnosc.service.RedesignService redesign() {
        return this.redesign;
    }

    public pl.medivalrpg.ludnosc.service.ResourceService resources() {
        return this.resources;
    }
    public pl.medivalrpg.ludnosc.service.ProductionStorageService production() { return this.production; }
    public pl.medivalrpg.ludnosc.service.LogisticsService logistics() { return this.logistics; }
    /** Production chains: glassworks, brickworks, sheepfold and the smithy (may be null before enable). */
    public pl.medivalrpg.ludnosc.service.WorkshopService workshops() { return this.workshops; }
    /** Panel/RPC: material flow snapshot of a country (main thread; see LogisticsService#flowData). */
    public java.util.Map<String, Object> materialFlow(final UUID country) { return this.logistics.flowData(country); }
    public pl.medivalrpg.ludnosc.service.WorkAlertService alerts() { return this.alerts; }
    public pl.medivalrpg.ludnosc.service.SupplyDeliveryService supply() { return this.supply; }

    /** Optional server-plugin reflection bridge; poll the same immutable payload. Must be called on main. */
    public java.util.Map<String,Object> supplyDelivery(UUID player, UUID country, String contractId, String material, int amount) {
        return this.supply==null ? pl.medivalrpg.ludnosc.logic.SupplyLedger.result("UNAVAILABLE","","Odbiór dostaw nie jest gotowy.")
            : this.supply.receive(player,country,contractId,material,amount);
    }

    /**
     * Reflection bridge for Firmy (stage 3 manufactories; the frozen {@code LudnoscApi} stays unchanged): the nearest
     * loaded <b>unemployed</b> adult citizen of {@code country} leaves the population to work for a player company.
     * Returns the profession key ({@code minecraft:mason}…) to pass back to {@code LudnoscApi#returnCitizen} when the
     * worker is dismissed; empty when the country has no such citizen now (or is locked by the royal rebuild). Never
     * takes a citizen with a workplace. Main thread only (else {@link IllegalStateException}).
     */
    public java.util.Optional<String> hireWorker(final UUID country, final org.bukkit.Location near) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Ludnosc#hireWorker: tylko główny wątek");
        }
        if (country == null || this.citizens == null || this.kraje() == null || this.kraje().getCountry(country).isEmpty()) {
            return java.util.Optional.empty();
        }
        if (this.royalRebuild != null && this.royalRebuild.blocks(country)) {
            return java.util.Optional.empty();
        }
        return this.citizens.hireWorker(country, near);
    }

    public pl.medivalrpg.ludnosc.service.VillageService villages() {
        return this.villages;
    }

    /** Mood of the inhabitants (optional plugin Zycie; multiplier 1 without it). */
    public pl.medivalrpg.ludnosc.hook.ZycieHook zycie() {
        return this.zycie;
    }

    /** Epochs (optional plugin Postep; epoch 1 without it). */
    public pl.medivalrpg.ludnosc.hook.PostepHook postep() {
        return this.postep;
    }

    /** {@code EraAdvanceEvent} from Postep: {country, oldEra, newEra}. */
    private void eraAdvanced(final Object[] args) {
        if (args[0] instanceof UUID country && args[2] instanceof Number to) {
            this.settlement().eraAdvanced(country, args[1] instanceof Number from ? from.intValue() : 1, to.intValue());
        }
    }

    /** Server-wide seasons and poor harvests. */
    public pl.medivalrpg.ludnosc.service.SeasonService seasons() {
        return this.seasons;
    }

    /** Citizens' jobs and visible work. */
    public pl.medivalrpg.ludnosc.service.WorkService work() {
        return this.work;
    }

    /** Citizens' homes, work, wages, families and self-built huts (also read reflectively by Zycie). */
    public pl.medivalrpg.ludnosc.service.LifeService life() {
        return this.life;
    }

    public pl.medivalrpg.ludnosc.service.MineService mines() {
        return this.mines;
    }

    public RepairService repairs() {
        return this.repairs;
    }

    public TerrainCache terrain() {
        return this.terrain;
    }

    public pl.medivalrpg.ludnosc.service.BlueprintService blueprints() { return this.blueprints; }

    /** Buildings registered by the players ({@code /ludnosc budynek}); null before enable. */
    public pl.medivalrpg.ludnosc.service.PlayerBuildingService playerBuildings() { return this.playerBuildings; }

    /** Dye of the country colour (for {@code {kolor}} blocks). */
    public String dyeOf(final UUID country) {
        return this.kraje.getCountry(country).map(k -> pl.medivalrpg.ludnosc.util.Dyes.nearest(k.color().value())).orElse("red");
    }

    public boolean automaticBuildingAllowed(final UUID country) {
        return country != null && this.kraje().getCountry(country).map(k -> pl.medivalrpg.ludnosc.logic.PlayerCountryRules.automatic(k.npc())).orElse(false);
    }

    public boolean buildingAllowed(final UUID country, final String type) {
        final var t = this.templates().get(type);
        if (t == null) return false;
        final var allowed = this.getConfig().contains("player-countries.allowed-buildings")
            ? this.getConfig().getStringList("player-countries.allowed-buildings")
            : pl.medivalrpg.ludnosc.logic.PlayerCountryRules.DEFAULT_BUILDINGS;
        return pl.medivalrpg.ludnosc.logic.PlayerCountryRules.allowed(this.automaticBuildingAllowed(country),
            pl.medivalrpg.ludnosc.logic.BlueprintRules.isBlueprintId(type), type, t.meta().baseType(), allowed);
    }

    private String plainMessage(final String key, final String value) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(this.messages().get(key,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("value", value)));
    }

    /**
     * Polish bonus summary of a building type from the live config ("podatki +5%, wzrost +10%"), empty when none.
     * A look variant has the bonus of its base type (as in {@code EconomyService#bonus}). The food bonus and the mine
     * income are not listed: neither does anything since the physical economy.
     */
    public String bonusText(final pl.medivalrpg.ludnosc.template.BuildingTemplate t) {
        final java.util.List<String> parts = new java.util.ArrayList<>();
        var b = this.settings().bonuses.get(t.id());
        if (b == null) {
            b = this.settings().bonuses.get(t.meta().baseType());
        }
        if (b != null) {
            if (b.tax() != 0) {
                parts.add(this.plainMessage("bonus-tax", Long.toString(Math.round(b.tax() * 100))));
            }
            if (b.growth() != 0) {
                parts.add(this.plainMessage("bonus-growth", Long.toString(Math.round(b.growth() * 100))));
            }
            if (b.granary() != 0) {
                parts.add(this.plainMessage("bonus-granary", Long.toString(b.granary())));
            }
        }
        return String.join(", ", parts);
    }

    public java.util.List<pl.medivalrpg.ludnosc.template.BuildingTemplate> catalog(final UUID country) {
        return this.templates().all().stream().filter(t -> this.buildingAllowed(country, t.id())).toList();
    }

    // ------------------------------------------------------------------ helpers

    public String countryName(final UUID country) {
        return country == null ? "-" : this.kraje.getCountry(country).map(KrajInfo::name).orElse("?");
    }

    public String formatMoney(final long copper) {
        final SkarbiecApi eco = this.skarbiec();
        return eco == null ? copper + " md" : eco.format(copper);
    }

    /** Sends a message to the online player members of a country. */
    public void notifyCountry(final UUID country, final String key, final TagResolver... resolvers) {
        if (country == null || this.messages.raw(key).isEmpty()) {
            return;
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (this.kraje.getCountryOf(p.getUniqueId()).map(k -> k.id().equals(country)).orElse(false)) {
                this.messages.send(p, key, resolvers);
            }
        }
    }
}
