// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy;

import com.google.gson.reflect.TypeToken;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.firmy.bridge.BukkitNotifier;
import pl.medivalrpg.firmy.bridge.BukkitWorld;
import pl.medivalrpg.firmy.bridge.ShopBridge;
import pl.medivalrpg.firmy.bridge.SkarbiecBank;
import pl.medivalrpg.firmy.command.FirmaCommand;
import pl.medivalrpg.firmy.core.AltWatch;
import pl.medivalrpg.firmy.core.Ctx;
import pl.medivalrpg.firmy.core.Extension;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Settings;
import pl.medivalrpg.firmy.gui.FirmGui;
import pl.medivalrpg.firmy.listener.FirmyListener;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.firmy.panel.FirmyPanel;
import pl.medivalrpg.firmy.store.JsonStore;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Firmy — player companies of MEDIVALRPG: registry with the seat country's consent, company accounts in Skarbiec,
 * shares, salaries, dividends, tax on income, company shops and warehouses, dormancy and liquidation. Money only moves by
 * transfers between existing accounts (closed pool of 100 000 000 md).
 */
public final class FirmyPlugin extends JavaPlugin {

    private JsonStore store;
    private FirmsData data;
    private Ctx ctx;
    private FirmService service;
    private ShopBridge shops;
    private FirmGui gui;
    private FirmaCommand command;
    private FirmyPanel panel;
    private AltWatch alts;
    private BukkitTask ticker;
    private Path dataFile;
    private Path intentFile;
    /** Co-founding fields of the applications (see FirmsData.GatheringBackup): the stage-1..3 jars of 29.10 never touch it. */
    private Path gatheringFile;
    private final List<Runnable> onDisable = new ArrayList<>();

    @Override
    public void onEnable() {
        this.getLogger().info("Firmy by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        final KrajeApi kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        final SkarbiecApi skarbiec = Bukkit.getServicesManager().load(SkarbiecApi.class);
        final Plugin skarbiecPlugin = Bukkit.getPluginManager().getPlugin("Skarbiec");
        if (kraje == null || skarbiec == null || skarbiecPlugin == null) {
            this.getLogger().severe("Firmy wymagają Kraje i Skarbca — plugin zostaje wyłączony.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.store = new JsonStore(this.getLogger());
        this.dataFile = this.getDataFolder().toPath().resolve("firmy.json");
        this.intentFile = this.getDataFolder().toPath().resolve("zamiary.json");
        this.gatheringFile = this.getDataFolder().toPath().resolve("zrzutki.json");
        final List<FirmsData.Intent> intents;
        try {
            this.data = this.store.read(this.dataFile, FirmsData.class, FirmsData::new);
            intents = this.store.read(this.intentFile, new TypeToken<List<FirmsData.Intent>>() { }.getType(), ArrayList::new);
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.SEVERE, "Nie udało się wczytać danych firm — plugin zostaje wyłączony, aby niczego nie nadpisać.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        normalize(this.data);
        try {
            final Map<String, FirmsData.GatheringBackup> backup = this.store.read(this.gatheringFile,
                new TypeToken<Map<String, FirmsData.GatheringBackup>>() { }.getType(), java.util.LinkedHashMap::new);
            final int restored = FirmsData.GatheringBackup.restore(this.data.applications, backup);
            if (restored > 0) {
                this.getLogger().warning("Firmy: odtworzono listy wkładów " + restored + " zrzutek z zrzutki.json (firmy.json zapisała starsza wersja).");
            }
        } catch (final IOException | RuntimeException e) {
            this.getLogger().log(Level.WARNING, "Nie udało się wczytać zrzutki.json — zrzutki bez listy wkładów czekają na administratora", e);
        }
        final SkarbiecBank bank = new SkarbiecBank(skarbiec, skarbiecPlugin, this.getLogger());
        final BukkitNotifier notifier = new BukkitNotifier(this.data, kraje, this.getLogger());
        final Ctx.Store persistence = new Ctx.Store() {
            @Override
            public CompletableFuture<Void> save() {
                FirmyPlugin.this.store.writeAsync(FirmyPlugin.this.gatheringFile, FirmsData.GatheringBackup.of(FirmyPlugin.this.data.applications));
                return FirmyPlugin.this.store.writeAsync(FirmyPlugin.this.dataFile, FirmyPlugin.this.data);
            }

            @Override
            public void saveNow() {
                FirmyPlugin.this.store.writeNow(FirmyPlugin.this.gatheringFile, FirmsData.GatheringBackup.of(FirmyPlugin.this.data.applications));
                FirmyPlugin.this.store.writeNow(FirmyPlugin.this.dataFile, FirmyPlugin.this.data);
            }

            @Override
            public void saveIntentsNow(final List<FirmsData.Intent> list) {
                FirmyPlugin.this.store.writeNow(FirmyPlugin.this.intentFile, new ArrayList<>(list));
            }

            @Override
            public void saveIntentsLater(final List<FirmsData.Intent> list) {
                FirmyPlugin.this.store.writeAsync(FirmyPlugin.this.intentFile, new ArrayList<>(list));
            }
        };
        this.ctx = new Ctx(this.data, intents, bank, new BukkitWorld(kraje), notifier, Clock.systemUTC(), persistence,
            Settings.from(this.getConfig()));
        this.ctx.main = r -> {
            if (this.isEnabled()) {
                Bukkit.getScheduler().runTask(this, r);
            }
        };
        this.service = new FirmService(this.ctx);
        for (final String note : this.service.startup()) {
            this.getLogger().warning("Firmy po restarcie: " + note);
        }
        this.alts = new AltWatch(this.data);
        this.shops = new ShopBridge(this, skarbiecPlugin, this.service, kraje, this.getLogger());
        this.service.addExtension(new Extension() {
            @Override
            public void afterLiquidation(final Firm firm) {
                // shops stand still at once (policy), the former master keeps chest access for N days
            }

            @Override
            public void daily(final long now) {
                FirmyPlugin.this.releaseLiquidated(now);
            }
        });
        this.gui = new FirmGui(this.service, this.shops).plugin(this);
        this.panel = new FirmyPanel(this.service, this.shops, this.alts);
        this.command = new FirmaCommand(this.service, this.shops, this.gui, kraje, skarbiec, this.alts, this::reload);

        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(this.gui, this);
        pm.registerEvents(new FirmyListener(this, this.service, this.shops, notifier, this.alts), this);

        // Later stages (contracts and loans, workshops) hook in here before the command tree is built.
        this.enableStages();

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
            event -> event.registrar().register(this.command.build(), "Firmy graczy", List.of("kompania")));
        this.ticker = Bukkit.getScheduler().runTaskTimer(this, this.service::tick, 200L, 1200L);
        this.getLogger().info("Firmy: " + this.data.firms.values().stream().filter(Firm::active).count() + " aktywnych, "
            + this.data.applications.size() + " wniosków; sklepy firmowe: " + (this.shops.available() ? "tak" : "nie (stary Skarbiec)")
            + ", przelewy zbiorcze atomowe: " + (bank.atomicBatch() ? "tak" : "nie"));
    }

    /** Stage modules present in this jar (stage-1 jars do not contain them). */
    private final List<String> stages = new ArrayList<>(List.of("1"));

    private void enableStages() {
        for (final String[] m : new String[][] {{"2", "pl.medivalrpg.firmy.stage2.Stage2Module"}, {"3", "pl.medivalrpg.firmy.stage3.Stage3Module"}}) {
            final Class<?> c;
            try {
                c = Class.forName(m[1], true, this.getClass().getClassLoader());
            } catch (final ClassNotFoundException e) {
                continue;
            }
            try {
                c.getConstructor(FirmyPlugin.class).newInstance(this);
                this.stages.add(m[0]);
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                this.getLogger().log(Level.SEVERE, "Etap " + m[0] + " Firm nie wystartował — działa bez niego", e);
            }
        }
        this.getLogger().info("Firmy: etapy " + String.join(", ", this.stages));
    }

    public List<String> stages() {
        return List.copyOf(this.stages);
    }

    public Ctx ctx() {
        return this.ctx;
    }

    /** Former master's grace period over: company shops are deleted in Skarbiec and warehouse chests released. */
    private void releaseLiquidated(final long now) {
        final long grace = this.ctx.settings.exMasterDays * Ctx.DAY;
        for (final Firm f : this.data.firms.values()) {
            if (!f.active() && f.liquidatedAt > 0 && now - f.liquidatedAt >= grace && (!f.warehouse.isEmpty() || f.exMaster != null)) {
                final int n = this.shops.removeAll(f);
                this.service.warehouse.release(f);
                f.exMaster = null;
                this.ctx.journal(f, "likwidacja", "Koniec dostępu byłego mistrza: usunięto sklepy (" + n + ") i magazyn", 0, "system");
            }
        }
    }

    private static void normalize(final FirmsData d) {
        if (d.firms == null) {
            d.firms = new java.util.LinkedHashMap<>();
        }
        if (d.applications == null) {
            d.applications = new java.util.LinkedHashMap<>();
        }
        if (d.countries == null) {
            d.countries = new java.util.LinkedHashMap<>();
        }
        if (d.reservedNames == null) {
            d.reservedNames = new java.util.LinkedHashMap<>();
        }
        if (d.addresses == null) {
            d.addresses = new java.util.LinkedHashMap<>();
        }
        if (d.mail == null) {
            d.mail = new java.util.LinkedHashMap<>();
        }
        for (final Firm f : d.firms.values()) {
            if (f.members == null) {
                f.members = new java.util.LinkedHashMap<>();
            }
            if (f.warehouse == null) {
                f.warehouse = new ArrayList<>();
            }
            if (f.chestLog == null) {
                f.chestLog = new ArrayList<>();
            }
            if (f.journal == null) {
                f.journal = new ArrayList<>();
            }
            if (f.payouts == null) {
                f.payouts = new ArrayList<>();
            }
            if (f.arrears == null) {
                f.arrears = new java.util.LinkedHashMap<>();
            }
            if (f.incomeByDay == null) {
                f.incomeByDay = new java.util.TreeMap<>();
            }
            if (f.taxByDay == null) {
                f.taxByDay = new java.util.TreeMap<>();
            }
            if (f.bannerPatterns == null) {
                f.bannerPatterns = new ArrayList<>();
            }
            if (f.frozenReason == null) {
                f.frozenReason = "";
            }
            if (f.payoutDay == null) {
                f.payoutDay = "";
            }
        }
    }

    public void reload() {
        this.reloadConfig();
        this.ctx.settings = Settings.from(this.getConfig());
    }

    @Override
    public void onDisable() {
        if (this.ticker != null) {
            this.ticker.cancel();
        }
        this.onDisable.forEach(Runnable::run);
        if (this.shops != null) {
            this.shops.unregister();
        }
        if (this.store != null && this.data != null && this.dataFile != null) {
            try {
                this.store.writeNow(this.gatheringFile, FirmsData.GatheringBackup.of(this.data.applications));
                this.store.writeNow(this.dataFile, this.data);
                // Intents still open belong to transfers that already happened and only wait for the bank journal; once the
                // company data is on disk and the bank is durable they are settled (else they would freeze the firms).
                if (this.ctx != null && !this.ctx.intents.isEmpty()) {
                    try {
                        this.ctx.bank.durable().get(10, java.util.concurrent.TimeUnit.SECONDS);
                        this.ctx.intents.clear();
                        this.store.writeNow(this.intentFile, new ArrayList<>());
                    } catch (final Exception e) {
                        this.getLogger().warning("Bank nie potwierdził zapisu przy wyłączaniu — otwarte zamiary zostają do audytu.");
                    }
                }
            } catch (final RuntimeException e) {
                this.getLogger().log(Level.SEVERE, "Nie udało się zapisać firm przy wyłączaniu", e);
            }
            this.store.shutdown();
        }
    }

    // ------------------------------------------------------------------ accessors for the later stages and probes

    public FirmService service() {
        return this.service;
    }

    public ShopBridge shops() {
        return this.shops;
    }

    public FirmaCommand command() {
        return this.command;
    }

    public FirmGui gui() {
        return this.gui;
    }

    public FirmyPanel panel() {
        return this.panel;
    }

    public JsonStore store() {
        return this.store;
    }

    public void onDisable(final Runnable r) {
        this.onDisable.add(r);
    }

    // ------------------------------------------------------------------ Panel bridge (reflection, JDK types only)

    /**
     * Web panel RPC {@code firmy.*} (Panel plugin → reflection). Access is decided here on every call (public / member /
     * owner WorldSteward). Main thread. Throws IllegalArgumentException with a Polish message on refusal.
     */
    public Object firmyPanel(final UUID actor, final String method, final Map<String, String> args) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Main thread required");
        }
        if (this.panel == null) {
            throw new IllegalArgumentException("Firmy są niedostępne.");
        }
        return this.panel.invoke(actor, method, args);
    }
}
