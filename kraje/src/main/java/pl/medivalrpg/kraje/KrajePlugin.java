// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.command.ChannelCommand;
import pl.medivalrpg.kraje.command.KrajCommand;
import pl.medivalrpg.kraje.command.MapLinkCommand;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.economy.Economy;
import pl.medivalrpg.kraje.economy.TreasuryService;
import pl.medivalrpg.kraje.integration.Integrations;
import pl.medivalrpg.kraje.listener.BorderListener;
import pl.medivalrpg.kraje.listener.ChatService;
import pl.medivalrpg.kraje.listener.PlayerListener;
import pl.medivalrpg.kraje.listener.TablistService;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.KrajeActions;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.manager.KrajeApiImpl;
import pl.medivalrpg.kraje.manager.RequestManager;
import pl.medivalrpg.kraje.map.MapHook;
import pl.medivalrpg.kraje.protection.EnvironmentListener;
import pl.medivalrpg.kraje.protection.ProtectedAreas;
import pl.medivalrpg.kraje.protection.ProtectionListener;
import pl.medivalrpg.kraje.protection.ProtectionService;
import pl.medivalrpg.kraje.protection.PvpListener;
import pl.medivalrpg.kraje.siege.SiegeManager;
import pl.medivalrpg.kraje.storage.CountryStorage;
import pl.medivalrpg.kraje.storage.ProtectedAreaStorage;
import pl.medivalrpg.kraje.embassy.EmbassyListener;
import pl.medivalrpg.kraje.embassy.EmbassyService;
import pl.medivalrpg.kraje.union.UnionService;
import pl.medivalrpg.kraje.vassal.VassalService;

/**
 * Kraje — country system for MEDIVALRPG.
 */
public final class KrajePlugin extends JavaPlugin {

    private volatile KrajeConfig config;
    private volatile Messages messages;
    private CountryStorage storage;
    private CountryManager manager;
    private final ProtectedAreas protectedAreas = new ProtectedAreas();
    private ProtectedAreaStorage areaStorage;
    private RequestManager requests;
    private ProtectionService protection;
    private pl.medivalrpg.kraje.protection.TntService tnt;
    private ChatService chat;
    private TablistService tablist;
    private KrajCommand command;
    private MapHook mapHook = MapHook.NOOP;
    private Economy economy = Economy.NONE;
    private TreasuryService treasury;
    private KrajeActions actions;
    private SiegeManager sieges;
    private VassalService vassals;
    private UnionService unions;
    private EmbassyService embassies;
    private pl.medivalrpg.kraje.flag.FlagService flags;
    private Integrations integrations;
    private BukkitTask saveTask;
    private BukkitTask economyTask;
    private boolean dataLoaded;
    private int minutes;

    @Override
    public void onLoad() {
        // Country banners: the shared default model must be in BetterModel's folder before it builds the startup pack.
        pl.medivalrpg.kraje.flag.FlagService.installDefaultModel(this);
    }

    @Override
    public void onEnable() {
        this.getLogger().info("Kraje by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.reloadConfig();
        this.config = new KrajeConfig(this.getConfig());
        this.messages = new Messages(this.getConfig(), this.getLogger());

        this.storage = new CountryStorage(this.getDataFolder().toPath(), this.getLogger());
        this.manager = new CountryManager(this.getLogger());
        try {
            final CountryStorage.Data data = this.storage.load();
            this.manager.loadAll(data.countries(), data.lastDailyTick(), data.invites());
            this.dataLoaded = true;
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Nie udało się wczytać danych krajów — plugin zostaje wyłączony, "
                + "aby nie nadpisać pliku. Sprawdź plugins/Kraje/countries.json.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.getLogger().info("Wczytano krajów: " + this.manager.countries().size());
        this.areaStorage = new ProtectedAreaStorage(this.getDataFolder().toPath(), this.getLogger());
        this.protectedAreas.setAll(this.areaStorage.load());
        if (!this.protectedAreas.all().isEmpty()) {
            this.getLogger().info("Wczytano obszarów chronionych: " + this.protectedAreas.all().size());
        }

        this.requests = new RequestManager();
        this.protection = new ProtectionService(this);
        this.chat = new ChatService(this);
        this.tablist = new TablistService(this);
        this.integrations = new Integrations(this.getLogger());
        this.economy = Economy.create(this);
        this.treasury = new TreasuryService(this);
        this.actions = new KrajeActions(this);
        this.sieges = new SiegeManager(this);
        this.vassals = new VassalService(this);
        this.unions = new UnionService(this);
        this.embassies = new EmbassyService(this);

        final var pm = Bukkit.getPluginManager();
        this.tnt=new pl.medivalrpg.kraje.protection.TntService(this);
        pm.registerEvents(this.tnt,this);
        pm.registerEvents(new ProtectionListener(this.protection), this);
        pm.registerEvents(new EnvironmentListener(this, this.protection), this);
        pm.registerEvents(new PvpListener(this), this);
        pm.registerEvents(new BorderListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(this.chat, this);
        pm.registerEvents(this.tablist, this);
        pm.registerEvents(new EmbassyListener(this.embassies), this);
        // Postep depends on Kraje (no softdepend here: cycle). Its EraAdvanceEvent is registered once Postep is enabled.
        final pl.medivalrpg.kraje.integration.PostepEvents postepEvents = new pl.medivalrpg.kraje.integration.PostepEvents(this);
        pm.registerEvents(postepEvents, this);
        postepEvents.tryRegister();
        pl.medivalrpg.kraje.manager.Notify.setDisplayNames(this::displayName);

        this.command = new KrajCommand(this);
        final ChannelCommand countryChat = new ChannelCommand(this, ChatService.Channel.COUNTRY);
        final ChannelCommand allianceChat = new ChannelCommand(this, ChatService.Channel.ALLIANCE);
        final MapLinkCommand mapLink = new MapLinkCommand(this);
        // Brigadier trees: the client shows argument names (<gracz>, <kraj>...) and suggestion tooltips.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(this.command.tree(), "System krajów MEDIVALRPG", List.of("k", "panstwo"));
            commands.register(countryChat.build("kc"), "Czat krajowy", List.of());
            commands.register(allianceChat.build("ks"), "Czat sojuszu", List.of());
            commands.register(mapLink.build(), "Link do mapy świata", List.of("mapaswiata"));
        });

        this.manager.setTributeEstimator(this.vassals::estimateTribute);
        this.manager.setUnionMinDuration(this.config.unionRules.minDurationMillis());
        this.manager.setRulerListener(this.unions::onRulerChanged);
        this.manager.refreshSnapshots();
        this.manager.setListener(this::onCountriesChanged);
        this.manager.setEventSink(event -> Bukkit.getPluginManager().callEvent(event));
        this.mapHook = MapHook.create(this);
        this.mapHook.scheduleRefresh();
        this.tablist.start();
        this.sieges.start();
        // Daily economy tick: checked every minute; the first check (with catch-up after a restart) runs once
        // all plugins are enabled, so Skarbiec is surely registered.
        this.economyTask = Bukkit.getScheduler().runTaskTimer(this, this::minuteTick, 100L, 1200L);

        this.flags = new pl.medivalrpg.kraje.flag.FlagService(this);
        this.flags.enable();

        Bukkit.getServicesManager().register(KrajeApi.class, new KrajeApiImpl(this), this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        if (this.flags != null) {
            this.flags.disable();
            this.flags = null;
        }
        if (this.economyTask != null) {
            this.economyTask.cancel();
            this.economyTask = null;
        }
        if (this.sieges != null) {
            this.sieges.shutdown();
        }
        Bukkit.getServicesManager().unregisterAll(this);
        if (this.mapHook != null) {
            this.mapHook.shutdown();
            this.mapHook = MapHook.NOOP;
        }
        if (this.tablist != null) {
            this.tablist.shutdown();
        }
        pl.medivalrpg.kraje.manager.Notify.setDisplayNames(null);
        if (this.command != null) {
            this.command.cancelTeleports();
        }
        if (this.chat != null) {
            this.chat.clear();
        }
        if (this.saveTask != null) {
            this.saveTask.cancel();
            this.saveTask = null;
        }
        if (this.storage != null) {
            if (this.dataLoaded) {
                this.storage.saveAndShutdown(this.manager.countriesSnapshot(), this.manager.lastDailyTick(), this.manager.storedInvites());
            } else {
                this.storage.saveAndShutdown(null, 0L);
            }
        }
        if (this.areaStorage != null) {
            this.areaStorage.shutdown();
        }
        if (this.requests != null) {
            this.requests.clear();
        }
        if (this.unions != null) {
            this.unions.clear();
        }
        if (this.embassies != null) {
            this.embassies.clear();
        }
    }

    /** A protected area was added / removed: persist and redraw the map. Main thread. */
    public void onProtectedAreasChanged() {
        if (this.areaStorage != null) {
            this.areaStorage.saveAsync(this.protectedAreas.all());
        }
        this.mapHook.scheduleRefresh();
    }

    /** {@code /kraj admin reload}: config + messages + map layer + tab list + economy link. Country data stays in memory. */
    public void reload() {
        this.reloadConfig();
        this.config = new KrajeConfig(this.getConfig());
        this.messages = new Messages(this.getConfig(), this.getLogger());
        this.economy = Economy.create(this);
        this.manager.setUnionMinDuration(this.config.unionRules.minDurationMillis());
        this.manager.refreshSnapshots();
        this.mapHook.shutdown();
        this.mapHook = MapHook.create(this);
        this.mapHook.scheduleRefresh();
        this.mapHook.refreshSieges();
        this.tablist.start();
    }

    /** Every minute: daily economy settlement when due, expired vassalages, expired persistent invitations. */
    private void minuteTick() {
        try {
            this.treasury.checkSchedule();
        } finally {
            this.vassals.checkExpiry();
            this.embassies.checkExpiry();
            this.manager.pruneStoredInvites();
            // era progress (Postep) changes all the time: keep the map popup / kraje.json roughly current
            if (++this.minutes % 5 == 0 && this.integrations.postepPresent()) {
                this.mapHook.scheduleRefresh();
            }
        }
    }

    /**
     * Name shown to players: the era title from {@code era-titles} applied to the real name (needs Postep). Thread-safe
     * (used by the async chat too); the real name is returned when titles are off or the era is unknown.
     */
    public String displayName(final java.util.UUID id, final String name, final boolean npc) {
        final pl.medivalrpg.kraje.util.EraTitles titles = this.config.eraTitles;
        if (!titles.appliesTo(npc) || this.integrations == null) {
            return name;
        }
        final java.util.OptionalInt era = this.integrations.eraId(id);
        return era.isEmpty() ? name : titles.apply(name, npc, era.getAsInt());
    }

    public String displayName(final Country c) {
        return this.displayName(c.id(), c.name(), c.npc());
    }

    /** A country advanced to another era (Postep's EraAdvanceEvent): titles, tab list and map change. */
    public void onEraAdvance() {
        this.tablist.scheduleRefresh();
        this.mapHook.scheduleRefresh();
    }

    private void onCountriesChanged() {
        this.scheduleSave();
        this.mapHook.scheduleRefresh();
        this.tablist.scheduleRefresh();
    }

    /** Coalesces changes: at most one save per second. */
    private void scheduleSave() {
        if (this.saveTask != null || !this.isEnabled()) {
            return;
        }
        this.saveTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            this.saveTask = null;
            this.storage.saveAsync(this.manager.countriesSnapshot(), this.manager.lastDailyTick(), this.manager.storedInvites());
        }, 20L);
    }

    // ------------------------------------------------------------------ accessors

    /** Main-thread snapshot for the explicitly authorised royal reconstruction; never shuts down the writer. */
    public java.util.concurrent.CompletableFuture<Void> saveRoyalCheckpoint() {
        if(!Bukkit.isPrimaryThread()||this.manager==null||this.storage==null)
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Checkpoint krajów wymaga głównego wątku i aktywnego modułu."));
        return this.storage.checkpoint(this.manager.countriesSnapshot(),this.manager.lastDailyTick(),this.manager.storedInvites());
    }

    public KrajeConfig cfg() {
        return this.config;
    }

    public Messages messages() {
        return this.messages;
    }

    public CountryManager manager() {
        return this.manager;
    }

    /** Areas where nobody may found a country or claim land (e.g. the server spawn). Thread-safe reads. */
    public ProtectedAreas protectedAreas() {
        return this.protectedAreas;
    }

    public RequestManager requests() {
        return this.requests;
    }

    public ProtectionService protection() {
        return this.protection;
    }

    public pl.medivalrpg.kraje.protection.TntService tnt() {return this.tnt;}

    public ChatService chat() {
        return this.chat;
    }

    public MapHook mapHook() {
        return this.mapHook;
    }

    public Economy economy() {
        return this.economy;
    }

    public TreasuryService treasury() {
        return this.treasury;
    }

    public KrajeActions actions() {
        return this.actions;
    }

    public SiegeManager sieges() {
        return this.sieges;
    }

    public VassalService vassals() {
        return this.vassals;
    }

    public UnionService unions() {
        return this.unions;
    }

    public EmbassyService embassies() {
        return this.embassies;
    }

    public Integrations integrations() {
        return this.integrations;
    }

    /** Country flags and banners (null before onEnable / after onDisable). */
    public pl.medivalrpg.kraje.flag.FlagService flags() {
        return this.flags;
    }

    // ------------------------------------------------------------------ bridge: country flags (panel, Armia ships)
    //
    // No shared-API change: Panel and Armia call these public methods reflectively. Main thread only.

    /**
     * One panel RPC of the flag feature ({@code country.flag.*}, {@code admin.flag.*}). Returns a Map, or a
     * CompletableFuture of a Map for {@code country.flag.upload}. Rule violations: IllegalArgumentException (Polish).
     */
    public Object flagPanel(final java.util.UUID actor, final String actorName, final boolean panelAdmin, final String method,
                            final java.util.Map<String, String> args) {
        if (this.flags == null || !Bukkit.isPrimaryThread()) {
            throw new IllegalArgumentException("Chorągwie są obecnie niedostępne.");
        }
        return this.flags.panel(actor, actorName, panelAdmin, method, args == null ? java.util.Map.of() : args);
    }

    /** Whether the panel user moderates flags (panel admin, {@code flags.moderators}, or {@code kraje.flagi.moderuj}). */
    public boolean flagModerator(final java.util.UUID actor, final boolean panelAdmin) {
        return this.flags != null && this.flags.enabled() && this.flags.isModerator(actor, panelAdmin);
    }

    /**
     * Shows (or moves) an animated banner of {@code country} for another plugin, e.g. on a ship mast or with a marching
     * commander. {@code hoistTop} is the top of the hoist (where the cloth meets the pole); {@code pole=false} hides the
     * pole. Call again to move it (cheap); {@link #flagHide} removes it. Returns false when flags/the country are missing.
     */
    public boolean flagShow(final String handle, final String country, final org.bukkit.Location hoistTop, final float yaw,
                            final boolean pole, final float scale) {
        if (this.flags == null || !Bukkit.isPrimaryThread()) {
            return false;
        }
        try {
            return this.flags.show(handle, java.util.UUID.fromString(country), hoistTop, yaw, pole, scale);
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    public void flagHide(final String handle) {
        if (this.flags != null && Bukkit.isPrimaryThread()) {
            this.flags.hide(handle);
        }
    }

    /** Removes every bridge banner whose handle starts with {@code prefix} (e.g. {@code "armia:"} on Armia disable). */
    public void flagHidePrefix(final String prefix) {
        if (this.flags != null && Bukkit.isPrimaryThread()) {
            this.flags.hidePrefix(prefix);
        }
    }

    /**
     * A vanilla banner (base colour + up to 6 patterns) approximating the country's approved flag, or its default flag in
     * the country colour — for soldiers' shields and standard bearers in Armia (reflection, main thread). Returns null
     * while the pattern is still being computed (it is started by this call; ask again later) or when flags are off.
     * Copy {@code BannerMeta#getPatterns()} and the base colour of the material onto a {@code ShieldMeta}.
     */
    public org.bukkit.inventory.ItemStack flagBanner(final String country) {
        if (this.flags == null || !this.flags.enabled() || this.flags.standards() == null || !Bukkit.isPrimaryThread()) {
            return null;
        }
        try {
            final pl.medivalrpg.kraje.model.Country c = this.manager.get(java.util.UUID.fromString(country));
            return c == null ? null : this.flags.standards().bannerItemNow(c);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ narrow bridge for Armia (abstract NPC wars)
    //
    // No shared-API change: Armia calls these public methods reflectively (like ArmiaPlugin#npcRailConsent for Kolej).
    // Main thread only; ids are UUID strings; results are "OK…" or "DENIED:<reason>" (never an exception for a rule).

    private pl.medivalrpg.kraje.siege.AbstractConquest abstractConquest;

    private pl.medivalrpg.kraje.siege.AbstractConquest abstractConquest() {
        if (!this.isEnabled() || this.manager == null || !Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Kraje nie jest gotowe (albo wywołanie poza głównym wątkiem).");
        }
        if (this.abstractConquest == null) {
            this.abstractConquest = new pl.medivalrpg.kraje.siege.AbstractConquest(this);
        }
        return this.abstractConquest;
    }

    /** Checks (and with {@code charge} pays for) a background siege of an enemy chunk. {@code OK:<cost>} / {@code DENIED:…}. */
    public String abstractSiegeBegin(final String attacker, final String world, final int cx, final int cz,
                                     final boolean expedition, final boolean charge) {
        return this.abstractConquest().begin(java.util.UUID.fromString(attacker), world, cx, cz, expedition, charge);
    }

    /** Applies a background siege won by {@code attacker}: {@code OK:CONQUERED|BRIDGEHEAD|RAZED|FELL:<loot>} / {@code DENIED:…}. */
    public String abstractSiegeConquer(final String attacker, final String world, final int cx, final int cz, final boolean expedition) {
        return this.abstractConquest().conquer(java.util.UUID.fromString(attacker), world, cx, cz, expedition);
    }

    /** Revolt on conquered land: back to {@code former} (blank = wilderness). {@code OK:RETURNED|WILD} / {@code DENIED:…}. */
    public String abstractRevolt(final String holder, final String world, final int cx, final int cz, final String former) {
        return this.abstractConquest().revolt(java.util.UUID.fromString(holder), world, cx, cz,
            former == null || former.isBlank() ? null : java.util.UUID.fromString(former));
    }
}
