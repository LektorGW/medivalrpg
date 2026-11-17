// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.postep.api.PostepApi;
import pl.medivalrpg.postep.command.PostepCommand;
import pl.medivalrpg.postep.command.Views;
import pl.medivalrpg.postep.config.Messages;
import pl.medivalrpg.postep.config.PostepConfig;
import pl.medivalrpg.postep.config.TreeLoader;
import pl.medivalrpg.postep.display.SquaremapLayer;
import pl.medivalrpg.postep.display.TablistDecorator;
import pl.medivalrpg.postep.display.WebExport;
import pl.medivalrpg.postep.gui.TreeMenu;
import pl.medivalrpg.postep.listener.PostepListener;
import pl.medivalrpg.postep.logic.TechTree;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.Integrations;
import pl.medivalrpg.postep.service.PostepApiImpl;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.storage.DataStore;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Postep — eras and progress of every country of MEDIVALRPG (Średniowiecze → Renesans → Epoka przemysłowa →
 * Nowoczesność), Civilization-like: research points, a discovery tree, investments and era advances.
 */
public final class PostepPlugin extends JavaPlugin {

    private Integrations integrations;
    private DataStore store;
    private ProgressService service;
    private PostepCommand command;
    private TreeMenu menus;
    private TablistDecorator tablist;
    private SquaremapLayer map;
    private WebExport export;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private BukkitTask pendingDisplay;

    @Override
    public void onEnable() {
        this.getLogger().info("Postep by Maciej Kaczmarczyk — lektoro.pl");
        final KrajeApi kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        final SkarbiecApi skarbiec = Bukkit.getServicesManager().load(SkarbiecApi.class);
        if (kraje == null || skarbiec == null) {
            this.getLogger().severe("Brak API Kraje lub Skarbiec — wyłączam Postep");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.saveDefaultConfig();
        if (!new File(this.getDataFolder(), "drzewko.yml").exists()) {
            this.saveResource("drzewko.yml", false);
        }
        this.integrations = new Integrations(this.getLogger());
        this.store = new DataStore(this.getDataFolder().toPath(), this.getLogger());
        this.service = new ProgressService(this, kraje, skarbiec, this.integrations, this.store);
        this.loadConfiguration().forEach(p -> this.getLogger().warning("Konfiguracja: " + p));
        this.service.load();
        this.getLogger().info("Aktywne epoki: " + this.service.cfg().eras.stream()
            .map(EraDef::name).collect(java.util.stream.Collectors.joining(", "))
            + " (limit " + this.service.cfg().maxEra() + ").");

        Bukkit.getServicesManager().register(PostepApi.class, new PostepApiImpl(this.service), this, ServicePriority.Normal);

        final Views views = new Views(this.service);
        this.command = new PostepCommand(this, views);
        this.menus = new TreeMenu(this, views);
        this.tablist = new TablistDecorator(this);
        this.export = new WebExport(this, this.store);
        Bukkit.getPluginManager().registerEvents(new PostepListener(this), this);
        Bukkit.getPluginManager().registerEvents(this.menus, this);
        Bukkit.getPluginManager().registerEvents(this.tablist, this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(this.command.build("postep"), "Epoki i postęp państw MEDIVALRPG", List.of("epoka"));
        });

        this.service.onChange(this::scheduleDisplays);
        // First evaluation after every plugin finished enabling (Ludnosc/Armia services, squaremap worlds).
        Bukkit.getScheduler().runTask(this, () -> {
            this.startMap();
            this.startTasks();
            this.service.refreshAll();
        });
    }

    @Override
    public void onDisable() {
        this.tasks.forEach(BukkitTask::cancel);
        this.tasks.clear();
        if (this.tablist != null) {
            this.tablist.stop();
        }
        if (this.map != null) {
            this.map.shutdown();
            this.map = null;
        }
        if (this.service != null) {
            try {
                this.service.saveNow();
            } catch (final RuntimeException e) {
                this.getLogger().log(Level.SEVERE, "Nie udało się zapisać danych postępu", e);
            }
        }
        Bukkit.getServicesManager().unregisterAll(this);
    }

    /** (Re)reads config.yml and drzewko.yml; returns Polish notes about problems. */
    private List<String> loadConfiguration() {
        this.reloadConfig();
        final List<String> problems = new ArrayList<>();
        final PostepConfig cfg = new PostepConfig(this.getConfig(), problems);
        final Messages messages = new Messages(this.getConfig(), this.getLogger());
        final YamlConfiguration treeFile = YamlConfiguration.loadConfiguration(new File(this.getDataFolder(), "drzewko.yml"));
        try (var in = this.getResource("drzewko.yml")) {
            if (in != null) {
                treeFile.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (final java.io.IOException ignored) {
            // defaults are optional
        }
        final List<String> treeProblems = new ArrayList<>();
        TechTree tree = TechTree.of(TreeLoader.load(treeFile, treeProblems).stream()
            .filter(discovery -> discovery.era() <= cfg.maxEra()).toList());
        treeProblems.addAll(tree.validate(cfg.maxEra()));
        if (!treeProblems.isEmpty()) {
            tree = tree.sanitized(cfg.maxEra());
            treeProblems.add("pominięto błędne odkrycia; zostało " + tree.size());
        }
        for (final EraDef era : cfg.eras) {
            final int n = tree.inEra(era.id()).size();
            if (n > 8) {
                treeProblems.add("epoka " + era.id() + " ma " + n + " odkryć — drzewko GUI pokaże tylko 8 pierwszych");
            }
            if (era.id() < cfg.maxEra() && tree.requiredToLeave(era.id()).isEmpty()) {
                treeProblems.add("epoka " + era.id() + " nie ma odkryć wymaganych do awansu (wymagane-do-epoki)");
            }
        }
        treeProblems.forEach(p -> problems.add("drzewko.yml: " + p));
        this.service.configure(cfg, tree, messages);
        return problems;
    }

    /** {@code /postep admin przeladuj}. */
    public List<String> reloadAll() {
        final List<String> problems = this.loadConfiguration();
        problems.forEach(p -> this.getLogger().warning("Konfiguracja: " + p));
        this.tasks.forEach(BukkitTask::cancel);
        this.tasks.clear();
        if (this.map != null) {
            this.map.shutdown();
            this.map = null;
        }
        this.startMap();
        this.startTasks();
        this.service.refreshAll();
        return problems;
    }

    private void startTasks() {
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.service::checkDaily, 100L, 20L * 60));
        final long refresh = 20L * this.service.cfg().refreshSeconds;
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.service::refreshAll, refresh, refresh));
        this.tasks.add(Bukkit.getScheduler().runTaskTimer(this, this.service::saveIfDirty, 20L * 30, 20L * 30));
        this.tablist.start();
    }

    private void startMap() {
        if (!this.service.cfg().mapLayer || Bukkit.getPluginManager().getPlugin("squaremap") == null
            || !Bukkit.getPluginManager().isPluginEnabled("squaremap")) {
            return;
        }
        try {
            this.map = new SquaremapLayer(this);
        } catch (final RuntimeException | LinkageError e) {
            this.getLogger().log(Level.WARNING, "Nie udało się podłączyć do squaremap", e);
            this.map = null;
        }
    }

    /** Debounced map + JSON refresh after changes (at most every 2 s). */
    private void scheduleDisplays() {
        if (this.pendingDisplay != null || !this.isEnabled()) {
            return;
        }
        this.pendingDisplay = Bukkit.getScheduler().runTaskLater(this, () -> {
            this.pendingDisplay = null;
            if (this.map != null) {
                this.map.refresh();
            }
            try {
                this.export.export();
            } catch (final RuntimeException e) {
                this.getLogger().log(Level.WARNING, "Błąd eksportu postep.json", e);
            }
        }, 40L);
    }

    public ProgressService service() {
        return this.service;
    }

    public Integrations integrations() {
        return this.integrations;
    }

    public PostepCommand command() {
        return this.command;
    }

    public TreeMenu menus() {
        return this.menus;
    }
}
