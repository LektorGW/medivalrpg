// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.armia.ai.KrajeListener;
import pl.medivalrpg.armia.ai.NpcAiService;
import pl.medivalrpg.armia.ai.UpkeepService;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.armia.command.ArmiaCommand;
import pl.medivalrpg.armia.command.NpcKrajCommand;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.RulerRecord;
import pl.medivalrpg.armia.model.SoldierRecord;
import pl.medivalrpg.armia.ruler.GuiListener;
import pl.medivalrpg.armia.ruler.RulerService;
import pl.medivalrpg.armia.service.ArmiaApiImpl;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Keys;
import pl.medivalrpg.armia.soldier.MaceItem;
import pl.medivalrpg.armia.soldier.MaceListener;
import pl.medivalrpg.armia.soldier.SoldierController;
import pl.medivalrpg.armia.soldier.SoldierListener;
import pl.medivalrpg.armia.soldier.SoldierRegistry;
import pl.medivalrpg.armia.soldier.SoldierSpawner;
import pl.medivalrpg.armia.storage.DataStore;
import pl.medivalrpg.kraje.api.SiegeContributor;

/** Armia: NPC soldiers of countries and the AI of NPC countries ("Władcy NPC"). */
public final class ArmiaPlugin extends JavaPlugin implements Listener {

    private volatile ArmiaConfig config;
    private volatile Messages messages;
    private DataStore store;
    private DataStore.Meta meta;
    private SoldierRegistry registry;
    private SoldierController controller;
    private ArmyService army;
    private NpcAiService ai;
    private UpkeepService upkeep;
    private RulerService rulers;
    private SoldierListener soldierListener;
    private Services services;
    private pl.medivalrpg.armia.village.VillageService villages;
    private pl.medivalrpg.armia.naval.FleetService fleet;
    private pl.medivalrpg.armia.ai.NavalAiService navalAi;
    private pl.medivalrpg.armia.soldier.RoyalGarrisonService royalGarrison;
    private pl.medivalrpg.armia.ai.ConquestService conquest;

    /**
     * Narrow bridge for the web panel (no shared-API change): background NPC wars as JSON — offensives with progress,
     * exhaustion, hegemon, recent events. Main thread.
     */
    public String npcConquestJson() {
        if (!this.isEnabled() || this.conquest == null || !Bukkit.isPrimaryThread()) throw new IllegalStateException("Armia nie jest gotowa.");
        return this.conquest.json();
    }

    /** Isolated tests / admin diagnostics: the next background-war cycle runs at once. */
    public void npcConquestCycleNow() {
        if (this.conquest != null) this.conquest.runCycleNow();
    }

    /** Trusted royal rebuild helper only; its own strict country, ownership and limit validation runs on every call. */
    public int deployRoyalGarrison(final String json) {
        if(!this.isEnabled() || this.royalGarrison==null) throw new IllegalStateException("Armia nie jest gotowa do rozstawienia gwardii.");
        return this.royalGarrison.deploy(json);
    }

    /** Before any terrain replacement: shelter the royal army as durable records without live entities. */
    public int beginRoyalRebuild() {
        if(!this.isEnabled() || this.royalGarrison==null) throw new IllegalStateException("Armia nie jest gotowa.");
        return this.royalGarrison.begin();
    }

    /** Abort/rollback recovery: only old positions that are still demonstrably safe are resumed. */
    public int resumeRoyalRebuild() {
        if(!this.isEnabled() || this.royalGarrison==null) throw new IllegalStateException("Armia nie jest gotowa.");
        return this.royalGarrison.resume();
    }

    /**
     * Narrow bridge for Kolej (no shared-API change): answer of the NPC host {@code hostCountryId} to a railway
     * infrastructure/passage request of {@code applicantCountryId} (may be null/blank for the royal carrier). Main thread.
     * Returns {@code "ACCEPT"}, {@code "ACCEPT_FEE:<md>"} or {@code "REFUSE:<powód>"}; the fee is only asked — paying it
     * (transfer to the host's treasury) is up to the caller. Never leaves a request pending.
     */
    public String npcRailConsent(final String hostCountryId, final String applicantCountryId, final long baseFee) {
        if (!this.isEnabled() || this.ai == null || !Bukkit.isPrimaryThread()) throw new IllegalStateException("Armia nie jest gotowa.");
        final UUID host = UUID.fromString(hostCountryId);
        final UUID applicant = applicantCountryId == null || applicantCountryId.isBlank() ? null : UUID.fromString(applicantCountryId);
        final var d = this.ai.railConsent(host, applicant, baseFee);
        return switch (d.answer()) {
            case ACCEPT -> "ACCEPT";
            case ACCEPT_FEE -> "ACCEPT_FEE:" + d.fee();
            case REFUSE -> "REFUSE:" + d.reason();
        };
    }

    @Override
    public void onEnable() {
        this.getLogger().info("Armia by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.reloadSettings();

        final Optional<Services> svc = Services.load(this.getLogger());
        if (svc.isEmpty()) {
            this.getLogger().severe("Brak KrajeApi lub SkarbiecApi w ServicesManager — Armia się wyłącza");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.services = svc.get();

        this.store = new DataStore(this.getDataFolder().toPath(), this.getLogger());
        this.meta = this.store.loadMeta();
        final Keys keys = new Keys(this);
        this.registry = new SoldierRegistry();
        for (final SoldierRecord r : this.store.loadSoldiers()) {
            if (r.id != null && r.country != null && r.type != null) {
                this.registry.add(r);
            }
        }
        this.registry.consumeDirty();

        final SoldierSpawner spawner = new SoldierSpawner(this, keys, () -> this.config, () -> this.messages);
        this.controller = new SoldierController(this, this.registry, spawner, keys, this.services, () -> this.config);
        final MaceItem mace = new MaceItem(keys, () -> this.config, () -> this.messages);
        this.army = new ArmyService(this.registry, this.controller, this.services, () -> this.config, () -> this.messages, mace);
        final Map<UUID, NpcState> npcs = new ConcurrentHashMap<>(this.store.loadNpcs());
        npcs.values().removeIf(s -> s == null || s.country == null);
        this.ai = new NpcAiService(this, this.services, this.army, () -> this.config, () -> this.messages, this.store, npcs, this.meta);
        this.army.onDeath(this.ai::onSoldierDied);
        final Map<UUID, RulerRecord> rulerMap = new ConcurrentHashMap<>(this.store.loadRulers());
        rulerMap.values().removeIf(r -> r == null || r.country == null || r.pos == null);
        this.rulers = new RulerService(this, keys, this.services, () -> this.messages, rulerMap);
        this.ai.setRulers(this.rulers);
        // Eras (Postep, found lazily by reflection; Postep has Armia in its softdepend, not the other way round)
        this.services.setProgress(new pl.medivalrpg.armia.service.Progress(this, this.ai::onEraAdvance));
        spawner.setEraHooks(this.army::hpMultiplier, t -> this.config.unitEra(t).camo());
        this.controller.setDamageMultiplier(this.army::damageMultiplier);
        this.army.startMedics(this);
        this.fleet = new pl.medivalrpg.armia.naval.FleetService(this,this.army,this.services,()->this.config,()->this.messages,this::autosave);
        this.army.fleet(this.fleet);
        this.navalAi = new pl.medivalrpg.armia.ai.NavalAiService(this, this.services, this.army, this.ai);
        this.ai.setNaval(this.navalAi);
        this.conquest = new pl.medivalrpg.armia.ai.ConquestService(this, this.services, this.army, this.ai, () -> this.config, () -> this.messages);
        this.ai.setConquest(this.conquest);
        this.royalGarrison=new pl.medivalrpg.armia.soldier.RoyalGarrisonService(this.army,this.services,()->this.config,
            ()->this.store.checkpointSoldiers(this.registry.records()));
        this.upkeep = new UpkeepService(this, this.army, this.ai, () -> this.config, this.meta);

        this.soldierListener = new SoldierListener(this, keys, this.registry, this.controller, this.army, this.services, () -> this.config, () -> this.messages);
        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(this.soldierListener, this);
        pm.registerEvents(this.fleet,this);
        pm.registerEvents(new MaceListener(this.army, mace, () -> this.config), this);
        pm.registerEvents(new KrajeListener(this, this.ai, this.army, this.rulers, () -> this.config, this.upkeep), this);
        pm.registerEvents(new GuiListener(this.rulers, this.ai, this.army, this.services, () -> this.config, () -> this.messages), this);
        pm.registerEvents(new pl.medivalrpg.armia.ruler.RulerProtectionListener(this, this.rulers, () -> this.config), this);
        try {
            pm.registerEvents(new pl.medivalrpg.armia.ai.VassalListener(this, this.ai), this);
        } catch (final LinkageError e) {
            this.getLogger().warning("Kraje bez kapitulacji/wasalstwa (API 1.2) — AI nie odpowiada na kapitulacje");
        }
        try {
            pm.registerEvents(new pl.medivalrpg.armia.ai.BondsListener(this, this.ai), this);
        } catch (final LinkageError e) {
            this.getLogger().warning("Kraje bez unii/ambasad (API 1.5) — AI nie odpowiada na unie i ambasady");
        }
        pm.registerEvents(this, this);

        final ArmiaApiImpl apiImpl = new ArmiaApiImpl(keys, this.army, this.ai, this.rulers, this.services, () -> this.config, () -> this.messages);
        Bukkit.getServicesManager().register(ArmiaApi.class, apiImpl, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(SiegeContributor.class, this.controller::presence, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(pl.medivalrpg.kraje.api.LandingSupport.class,this.fleet,this,ServicePriority.Normal);

        final ArmiaCommand armia = new ArmiaCommand(this, this.army, this.ai, this.services, () -> this.config, () -> this.messages, this::reloadSettings);
        final NpcKrajCommand nk = new NpcKrajCommand(this, this.services, this.army, this.ai, this.rulers, () -> this.config, () -> this.messages);
        final pl.medivalrpg.armia.scenario.ScenarioService scenarios = new pl.medivalrpg.armia.scenario.ScenarioService(this, this.services, apiImpl, this.ai,
            () -> this.config, () -> this.messages);
        scenarios.extractDefaults();
        nk.setExtras(scenarios, apiImpl);
        this.villages = new pl.medivalrpg.armia.village.VillageService(this, this.services, apiImpl, this.ai, scenarios, () -> this.config, () -> this.messages);
        apiImpl.setVillages(this.villages);
        nk.setVillages(this.villages);
        pm.registerEvents(this.villages, this);
        this.villages.start();
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(armia.build(), "Żołnierze twojego kraju", List.of("wojsko"));
            commands.register(nk.build(), "Kraje NPC (admin)", List.of("nk"));
        });

        // Teams of the previous run are stale; entities re-join when attached.
        spawner.resetTeam();
        this.controller.start();
        this.fleet.start();
        this.ai.start();
        this.navalAi.start();
        this.conquest.start();
        this.upkeep.start();
        this.rulers.start();
        // Attach soldiers/rulers already loaded (reload, or chunks loaded before we enabled).
        Bukkit.getScheduler().runTask(this, this::scanLoaded);
        // Postep enables after Armia: look it up a moment later (registers EraAdvanceEvent).
        Bukkit.getScheduler().runTaskLater(this, () -> this.services.progress().present(), 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::autosave, 20L * 60 * 5, 20L * 60 * 5);
        this.getLogger().info("Wczytano " + this.registry.size() + " żołnierzy, " + npcs.size() + " krajów NPC, " + rulerMap.size()
            + " władców; tryb wyglądu: " + this.config.visual.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void scanLoaded() {
        for (final World w : Bukkit.getWorlds()) {
            for (final Entity e : w.getEntities()) {
                this.soldierListener.handleLoaded(e);
                this.rulers.onLoaded(e);
            }
            for (final Chunk c : w.getLoadedChunks()) {
                this.controller.onChunkLoaded(c);
            }
        }
        this.rulers.ensureAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity e : event.getEntities()) {
            this.rulers.onLoaded(e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        this.rulers.onChunkLoaded(event.getChunk());
    }

    @EventHandler
    public void onPluginChange(final PluginEnableEvent event) {
        if ("Ludnosc".equals(event.getPlugin().getName()) && this.services != null) {
            this.services.resetPopulation();
        }
        if ("Postep".equals(event.getPlugin().getName()) && this.services != null && this.services.progress() != null) {
            this.services.progress().reset();
        }
    }

    @EventHandler
    public void onPluginDisable(final PluginDisableEvent event) {
        if ("Ludnosc".equals(event.getPlugin().getName()) && this.services != null) {
            this.services.resetPopulation();
        }
        if ("Postep".equals(event.getPlugin().getName()) && this.services != null && this.services.progress() != null) {
            this.services.progress().reset();
        }
    }

    /** Reloads config.yml (units apply to newly spawned/loaded soldiers). */
    public void reloadSettings() {
        this.reloadConfig();
        this.config = new ArmiaConfig(this.getConfig(), this.getLogger());
        this.messages = new Messages(this.getConfig(), this.getLogger());
    }

    private void autosave() {
        this.registry.consumeDirty();
        this.ai.prepareSave();
        this.store.saveAsync(this.registry.records(), Map.copyOf(this.ai.states()), Map.copyOf(this.rulers.records()), this.meta);
        if(this.fleet!=null) this.fleet.save();
    }

    @Override
    public void onDisable() {
        if (this.controller == null) {
            return;
        }
        // Each step is isolated: an error in one service must never skip the final save of soldiers/NPC states.
        this.safely("AI", this.ai::stop);
        if (this.navalAi != null) {
            this.safely("desanty AI", this.navalAi::stop);
        }
        if (this.conquest != null) {
            this.safely("wojny w tle", this.conquest::stop);
        }
        if (this.villages != null) {
            this.safely("wioski", this.villages::stop);
        }
        if (this.upkeep != null) {
            this.safely("żołd", this.upkeep::stop);
        }
        if(this.fleet!=null) this.safely("flota", this.fleet::stop);
        this.safely("żołnierze", this.controller::stop); // detaches: saves positions/health, removes bodies
        this.safely("stan AI", this.ai::prepareSave);
        this.store.saveNow(this.registry.records(), Map.copyOf(this.ai.states()), Map.copyOf(this.rulers.records()), this.meta);
        Bukkit.getServicesManager().unregisterAll(this);
    }

    private void safely(final String what, final Runnable step) {
        try {
            step.run();
        } catch (final RuntimeException | LinkageError e) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "Błąd przy wyłączaniu (" + what + ") — zapis danych mimo to", e);
        }
    }
}
