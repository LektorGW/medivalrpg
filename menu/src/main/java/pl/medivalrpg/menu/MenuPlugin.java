// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.menu.book.BookService;
import pl.medivalrpg.menu.build.SpawnBuilder;
import pl.medivalrpg.menu.command.HelpCommand;
import pl.medivalrpg.menu.command.MenuCommand;
import pl.medivalrpg.menu.command.TeleportCommands;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.config.MenusLoader;
import pl.medivalrpg.menu.gui.ItemFactory;
import pl.medivalrpg.menu.gui.MenuHolder;
import pl.medivalrpg.menu.gui.MenuListener;
import pl.medivalrpg.menu.gui.MenuService;
import pl.medivalrpg.menu.guide.GuideListener;
import pl.medivalrpg.menu.guide.GuideService;
import pl.medivalrpg.menu.guide.ReceivedStore;
import pl.medivalrpg.menu.help.HelpCatalog;
import pl.medivalrpg.menu.help.HelpService;
import pl.medivalrpg.menu.home.HomeService;
import pl.medivalrpg.menu.home.HomeStore;
import pl.medivalrpg.menu.integration.KrajeHook;
import pl.medivalrpg.menu.integration.LoginWatcher;
import pl.medivalrpg.menu.model.MenuConfig;
import pl.medivalrpg.menu.teleport.PositionStore;
import pl.medivalrpg.menu.teleport.SpawnService;
import pl.medivalrpg.menu.teleport.StoredLocation;
import pl.medivalrpg.menu.teleport.TeleportService;
import pl.medivalrpg.menu.text.Placeholders;
import pl.medivalrpg.menu.tutorial.TutorialService;
import pl.medivalrpg.menu.tutorial.TutorialStore;

/**
 * Menu – the main menu of MEDIVALRPG: one place with every feature of the server, the "Przewodnik" compass
 * and the guide book. Integrations are loose: buttons run commands as the player.
 * Also {@code /spawn}, {@code /ustawspawn} and player homes ({@code /dom}, {@code /ustawdom}, {@code /usundom},
 * {@code /domy}) with a shared countdown and combat / siege checks, and the new-player tutorial ({@code /samouczek}).
 */
public final class MenuPlugin extends JavaPlugin {

    private volatile MenuSettings settings;
    private volatile Messages messages;
    private volatile MenuConfig menuConfig;
    private volatile Placeholders placeholders;
    private volatile HelpCatalog helpCatalog;

    private KrajeHook kraje;
    private MenuService menuService;
    private BookService bookService;
    private HelpService helpService;
    private GuideService guideService;
    private ReceivedStore received;
    private LoginWatcher loginWatcher;
    private TeleportService teleports;
    private SpawnService spawn;
    private HomeStore homeStore;
    private HomeService homes;
    private PositionStore positions;
    private SpawnBuilder builder;
    private TutorialStore tutorialStore;
    private TutorialService tutorialService;

    @Override
    public void onEnable() {
        this.getLogger().info("Menu by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        if (!new File(this.getDataFolder(), "menus.yml").exists()) {
            this.saveResource("menus.yml", false);
        }
        this.kraje = new KrajeHook(this.getLogger());
        this.loadConfigs();

        final ItemFactory items = new ItemFactory(this.getLogger());
        this.bookService = new BookService(() -> this.menuConfig, () -> this.settings, () -> this.placeholders,
            this.getLogger());
        this.menuService = new MenuService(this, () -> this.menuConfig, () -> this.settings, () -> this.messages,
            () -> this.placeholders, this.kraje, this.bookService, items);
        this.menuService.setReturnCheck(p -> this.spawn != null && this.spawn.canReturn(p));
        this.helpService = new HelpService(() -> this.helpCatalog, () -> this.messages, () -> this.settings, items,
            this.menuService);
        this.menuService.setHelpOpener((p, id) -> this.helpService.open(p, id, 0));
        this.guideService = new GuideService(this, () -> this.settings, items);
        this.received = new ReceivedStore(this.getDataFolder().toPath().resolve("data").resolve("guide-received.txt"),
            this.getLogger());
        this.received.load();

        this.tutorialStore = new TutorialStore(this.getDataFolder().toPath().resolve("data").resolve("tutorial.json"),
            this.getLogger());
        this.tutorialStore.load();
        this.tutorialService = new TutorialService(this, () -> this.menuConfig, () -> this.settings,
            () -> this.messages, new pl.medivalrpg.menu.integration.ProgressHook(this.getLogger()), this.tutorialStore);
        this.menuService.setProgress(this.tutorialService);

        this.teleports = new TeleportService(this, () -> this.settings, () -> this.messages, this.kraje);
        this.positions = new PositionStore(this.getDataFolder().toPath().resolve("data").resolve("last-positions.json"),
            this.getLogger());
        this.positions.load();
        this.spawn = new SpawnService(this, () -> this.settings, () -> this.messages, this.teleports, this.positions,
            this.getLogger());
        this.builder = new SpawnBuilder(this, this.kraje);
        this.builder.extractResources();
        this.homeStore = new HomeStore(this.getDataFolder().toPath().resolve("data").resolve("homes.json"),
            this.getLogger());
        this.homeStore.load();
        this.homes = new HomeService(() -> this.settings, () -> this.messages, this.homeStore, this.kraje,
            this.teleports);

        final var pm = this.getServer().getPluginManager();
        pm.registerEvents(this.teleports, this);
        pm.registerEvents(this.spawn, this);
        pm.registerEvents(new MenuListener(this.menuService, () -> this.settings.clickCooldownMs), this);
        pm.registerEvents(new GuideListener(this, this.guideService, () -> this.settings, () -> this.messages,
            p -> this.menuService.open(p, MenuConfig.MAIN)), this);
        this.loginWatcher = new LoginWatcher(this, () -> this.settings, this::onLoggedIn);
        this.loginWatcher.start();

        final MenuCommand command = new MenuCommand(this);
        final HelpCommand help = new HelpCommand(this);
        final TeleportCommands tp = new TeleportCommands(this);
        final pl.medivalrpg.menu.command.TutorialCommand tutorial = new pl.medivalrpg.menu.command.TutorialCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var r = event.registrar();
            pl.medivalrpg.menu.integration.Requirements.capture(r.getDispatcher(), this.getLogger());
            r.register(command.build(), "Główne menu serwera MEDIVALRPG", List.of("m", "przewodnik"));
            r.register(help.build(), "Pomoc dla graczy: kategorie, poradniki i wyszukiwarka komend");
            r.register(tutorial.build(), "Samouczek nowego gracza: kroki od pierwszych monet do własnego kraju");
            r.register(tp.spawn(), "Teleport na spawn serwera", List.of("sp"));
            r.register(tp.setSpawn(), "Ustawia albo buduje spawn serwera w twoim miejscu (admin)");
            r.register(tp.returnBack(), "Powrót do ostatniej pozycji po wejściu na serwer", List.of("back"));
            r.register(tp.home(), "Teleport do twojego domu");
            r.register(tp.setHome(), "Zapisuje dom w twoim miejscu");
            r.register(tp.deleteHome(), "Usuwa dom");
            r.register(tp.listHomes(), "Lista twoich domów");
        });
    }

    @Override
    public void onDisable() {
        if (this.teleports != null) {
            this.teleports.stop();
        }
        if (this.spawn != null) {
            this.spawn.stop();
        }
        if (this.positions != null) {
            this.positions.close();
        }
        if (this.homeStore != null) {
            this.homeStore.close();
        }
        if (this.loginWatcher != null) {
            this.loginWatcher.stop();
        }
        for (final Player p : this.getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) {
                p.closeInventory();
            }
        }
        if (this.received != null) {
            this.received.close();
        }
        if (this.tutorialStore != null) {
            this.tutorialStore.close();
        }
    }

    /** Reloads config.yml and menus.yml; returns the number of warnings. */
    public int reload() {
        this.reloadConfig();
        return this.loadConfigs();
    }

    private int loadConfigs() {
        this.settings = new MenuSettings(this.getConfig());
        this.messages = new Messages(this.getConfig().getConfigurationSection("messages"));
        this.placeholders = new Placeholders(this.settings, this.kraje);
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(this.getDataFolder(), "menus.yml"));
        this.menuConfig = MenusLoader.load(yaml);
        for (final String warning : this.menuConfig.warnings()) {
            this.getLogger().warning("menus.yml: " + warning);
        }
        this.helpCatalog = this.loadHelp();
        for (final String warning : this.helpCatalog.warnings()) {
            this.getLogger().warning("pomoc.yml: " + warning);
        }
        this.getLogger().info("Wczytano " + this.menuConfig.menus().size() + " menu, "
            + this.menuConfig.books().size() + " książek i " + this.helpCatalog.categories().size() + " kategorii pomocy.");
        return this.menuConfig.warnings().size() + this.helpCatalog.warnings().size();
    }

    /**
     * Help catalog: {@code plugins/Menu/pomoc.yml} when an admin created one, else the file shipped in the jar
     * (the same file is published to the Discord command channel, so the jar default keeps both in sync).
     */
    private HelpCatalog loadHelp() {
        final File custom = new File(this.getDataFolder(), "pomoc.yml");
        if (custom.isFile()) {
            return HelpCatalog.load(YamlConfiguration.loadConfiguration(custom));
        }
        try (var in = this.getResource("pomoc.yml")) {
            if (in == null) {
                return HelpCatalog.load(null);
            }
            return HelpCatalog.load(YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (final java.io.IOException e) {
            this.getLogger().warning("Nie udało się wczytać pomoc.yml z jara: " + e.getMessage());
            return HelpCatalog.load(null);
        }
    }

    /** Login (after Straznik/nLogin authentication): new players go to the spawn, the guide compass is handed out once. */
    private void onLoggedIn(final Player player) {
        this.spawn.onLoggedIn(player);
        this.tutorialService.onLoggedIn(player); // a chat line a few seconds later; never a GUI or a teleport
        final MenuSettings s = this.settings;
        if (!s.guideEnabled || !s.guideGiveOnFirstJoin || this.received.has(player.getUniqueId())) {
            return;
        }
        final GuideService.GiveResult result = this.guideService.give(player);
        if (result == GuideService.GiveResult.GIVEN || result == GuideService.GiveResult.ALREADY_HAS) {
            this.received.add(player.getUniqueId());
        }
        if (result == GuideService.GiveResult.GIVEN) {
            player.sendMessage(this.messages.get("guide-first-join"));
        } else if (result == GuideService.GiveResult.NO_SPACE) {
            player.sendMessage(this.messages.get("guide-no-space"));
        }
    }

    /**
     * {@code /ustawspawn}: saves the player's position with rotation as {@code spawn.location} in config.yml and
     * moves the world spawn there (new players, respawn without bed).
     */
    public void setSpawnHere(final Player player) {
        final Location here = player.getLocation();
        final boolean world = this.setSpawn(here);
        player.sendMessage(this.messages.get(world ? "spawn-set" : "spawn-set-config-only",
            Placeholder.unparsed("location", StoredLocation.of(here).describe())));
    }

    /** Saves {@code spawn.location} (config.yml) and moves the world spawn; {@code false} if the world refused. */
    public boolean setSpawn(final Location location) {
        this.getConfig().set("spawn.location", StoredLocation.of(location).toMap());
        this.saveConfig();
        this.settings = new MenuSettings(this.getConfig());
        return SpawnService.applyWorldSpawn(location);
    }

    /** Puts back a previous {@code spawn.location} ({@code null} = empty = main world spawn). */
    public void restoreSpawn(final StoredLocation previous) {
        this.getConfig().set("spawn.location", previous == null ? "" : previous.toMap());
        this.saveConfig();
        this.settings = new MenuSettings(this.getConfig());
    }

    public MenuSettings settings() {
        return this.settings;
    }

    public SpawnBuilder builder() {
        return this.builder;
    }

    public SpawnService spawn() {
        return this.spawn;
    }

    public HomeService homes() {
        return this.homes;
    }

    public MenuService menus() {
        return this.menuService;
    }

    public HelpService help() {
        return this.helpService;
    }

    public BookService books() {
        return this.bookService;
    }

    public TutorialService tutorial() {
        return this.tutorialService;
    }

    public GuideService guide() {
        return this.guideService;
    }

    public Messages messages() {
        return this.messages;
    }

    public MenuConfig menuConfig() {
        return this.menuConfig;
    }
}
