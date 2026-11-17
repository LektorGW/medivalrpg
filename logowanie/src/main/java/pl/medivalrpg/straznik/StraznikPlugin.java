// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.straznik.account.AccountStore;
import pl.medivalrpg.straznik.api.StraznikApi;
import pl.medivalrpg.straznik.auth.AuthService;
import pl.medivalrpg.straznik.command.AdminCommand;
import pl.medivalrpg.straznik.command.AuthCommands;
import pl.medivalrpg.straznik.config.Messages;
import pl.medivalrpg.straznik.config.Settings;
import pl.medivalrpg.straznik.limbo.LimboStore;
import pl.medivalrpg.straznik.limbo.Restrictions;
import pl.medivalrpg.straznik.log.CommandLogFilter;
import pl.medivalrpg.straznik.log.EventLog;
import pl.medivalrpg.straznik.nlogin.ImportReport;
import pl.medivalrpg.straznik.nlogin.NLoginExporter;
import pl.medivalrpg.straznik.nlogin.NLoginImporter;

/**
 * Straznik — the MEDIVALRPG login plugin (replacement for nLogin; the server runs in offline mode).
 *
 * <p>Fail-closed: when the account database cannot be opened, every connection is refused instead of letting players
 * in without a password. When nLogin is enabled at the same time, Straznik stays passive (no commands, no gate,
 * no API) so the two never fight over the same players.
 */
public final class StraznikPlugin extends JavaPlugin implements Listener {

    public static final String IMPORT_MARKER = "import.nlogin";
    // command aliases: one list for the registration, the pre-login allow list and the console filter
    static final List<String> LOGIN_ALIASES = List.of("l", "log", "zaloguj");
    static final List<String> REGISTER_ALIASES = List.of("reg", "zarejestruj");
    static final List<String> CHANGE_ALIASES = List.of("changepassword", "changepass");
    static final List<String> LOGOUT_ALIASES = List.of("logout");
    static final List<String> ADMIN_ALIASES = List.of("auth");
    /** Allowed before login (and the only suggestions then). */
    static final Set<String> LOGIN_COMMANDS = union(List.of("login", "register"), LOGIN_ALIASES, REGISTER_ALIASES);
    /** Commands with a password: their lines never reach the console / latest.log. */
    static final Set<String> HIDDEN_IN_CONSOLE = union(List.of("login", "register", "zmienhaslo", "straznik"), LOGIN_ALIASES,
        REGISTER_ALIASES, CHANGE_ALIASES, ADMIN_ALIASES);

    @SafeVarargs
    private static Set<String> union(final List<String>... lists) {
        final Set<String> out = new HashSet<>();
        for (final List<String> l : lists) {
            out.addAll(l);
        }
        return Set.copyOf(out);
    }

    private volatile Settings settings;
    private volatile Messages messages;
    private AccountStore store;
    private LimboStore limbo;
    private EventLog events;
    private AuthService auth;
    private ExecutorService hashPool;
    private Set<String> weakPasswords = Set.of();
    private boolean active;

    @Override
    public void onEnable() {
        this.getLogger().info("Straznik by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.saveResourceIfMissing("slabe-hasla.txt");
        this.loadConfigOnly(new ArrayList<>());

        if (Bukkit.getPluginManager().isPluginEnabled("nLogin")) {
            this.getLogger().severe("nLogin jest włączony — Strażnik pozostaje NIEAKTYWNY (bez komend, bez blokady). "
                + "Przenieś nLogin do plugins/disabled/ i uruchom serwer ponownie, aby przełączyć logowanie.");
            return;
        }

        this.events = new EventLog(this.getDataFolder().toPath().resolve("zdarzenia.log"), this.getLogger());
        this.store = new AccountStore(this.getDataFolder().toPath().resolve("konta.db"), this.getLogger());
        try {
            this.store.open();
        } catch (final SQLException e) {
            this.getLogger().severe("Nie można otworzyć bazy kont (" + e.getMessage() + "). Serwer NIE wpuszcza graczy, "
                + "dopóki baza nie zadziała (ochrona kont w trybie offline).");
            this.lockServer();
            return;
        }
        this.autoImport();

        this.limbo = new LimboStore(this.getDataFolder().toPath().resolve("limbo"), this.getLogger());
        final AtomicInteger n = new AtomicInteger();
        this.hashPool = Executors.newFixedThreadPool(this.settings.hashThreads, r -> {
            final Thread t = new Thread(r, "Straznik-Hash-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.auth = new AuthService(this, () -> this.settings, () -> this.messages, this.store, this.limbo, this.events,
            this.hashPool, this.weakPasswords);
        this.active = true;

        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(new Restrictions(this.auth::isAuthenticated, this.auth::isOwnTeleport, () -> this.settings,
            () -> this.messages, LOGIN_COMMANDS), this);
        Bukkit.getServicesManager().register(StraznikApi.class, this.auth, this, ServicePriority.Highest);
        if (this.settings.hideCommandsInConsole) {
            CommandLogFilter.install(HIDDEN_IN_CONSOLE, this.getLogger());
        }

        final AuthCommands cmds = new AuthCommands(this.auth, () -> this.messages);
        final AdminCommand admin = new AdminCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var r = event.registrar();
            r.register(cmds.login(), "Logowanie na serwer: /login <hasło>", LOGIN_ALIASES);
            r.register(cmds.register(), "Rejestracja konta: /register <hasło> <hasło>", REGISTER_ALIASES);
            r.register(cmds.changePassword(), "Zmiana hasła: /zmienhaslo <stare> <nowe>", CHANGE_ALIASES);
            r.register(cmds.logout(), "Wylogowanie z serwera (kończy sesję)", LOGOUT_ALIASES);
            r.register(admin.build(), "Administracja kontami graczy (Strażnik)", ADMIN_ALIASES);
        });

        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.auth.adoptOnline(p);
        }
        Bukkit.getScheduler().runTaskTimer(this, this.auth::tick, 20L, 20L);
        this.getLogger().info("Strażnik aktywny: " + this.store.size() + " kont, hasła " + this.auth.hasher().describe()
            + ", limit czasu " + this.settings.authTimeoutSeconds + " s, sesja "
            + (this.settings.sessionEnabled ? this.settings.sessionMinutes + " min" : "wyłączona") + ".");
        final int left = this.limbo.count();
        if (left > 0) {
            this.getLogger().info("Kopie limbo do oddania przy następnym logowaniu: " + left + ".");
        }
    }

    /** Database unusable: refuse every connection (and kick anyone online). */
    private void lockServer() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, StraznikPlugin.this.messages.get("blad-bazy-kick"));
            }
        }, this);
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.kick(this.messages.get("blad-bazy-kick"));
        }
    }

    @Override
    public void onDisable() {
        CommandLogFilter.disable();
        if (this.auth != null) {
            this.auth.shutdown();
        }
        if (this.hashPool != null) {
            this.hashPool.shutdownNow();
            try {
                this.hashPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.store != null) {
            this.store.close();
        }
        if (this.events != null) {
            this.events.close();
        }
        this.active = false;
    }

    // ------------------------------------------------------------------ connection events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        final Component deny = this.auth.checkPreLogin(event.getName(), event.getUniqueId(), event.getAddress());
        if (deny != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, deny);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        final java.util.UUID id = event.getConnection().getProfile().getId();
        if (id == null) {
            return;
        }
        final Location to = this.auth.chooseSpawn(id, event.getSpawnLocation());
        if (to != null) {
            event.setSpawnLocation(to);
        }
    }

    /**
     * The player entity enters the world: data (inventory) is loaded, but in Paper 26.3 the first inventory packet
     * is sent only after this — hiding here means the client never receives the items before /login. Later world
     * changes of logged-in players are ignored by {@code prepareBeforeJoin}.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAddToWorld(final com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Player player) {
            this.auth.prepareBeforeJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        this.auth.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        this.auth.onQuit(event.getPlayer());
    }

    // ------------------------------------------------------------------ config, import, export

    private void loadConfigOnly(final List<String> warnings) {
        this.reloadConfig();
        this.settings = new Settings(this.getConfig());
        this.messages = new Messages(this.getConfig(), w -> {
            warnings.add(w);
            this.getLogger().warning(w);
        });
        this.weakPasswords = this.loadWeakPasswords();
    }

    /** {@code /straznik przeladuj}; returns warnings. */
    public List<String> reloadAll() {
        final List<String> warnings = new ArrayList<>();
        this.loadConfigOnly(warnings);
        if (this.auth != null) {
            this.auth.reload(this.weakPasswords);
            if (this.settings.hideCommandsInConsole) {
                CommandLogFilter.install(HIDDEN_IN_CONSOLE, this.getLogger());
            } else {
                CommandLogFilter.disable();
            }
        }
        return warnings;
    }

    private void saveResourceIfMissing(final String name) {
        if (!Files.exists(this.getDataFolder().toPath().resolve(name))) {
            this.saveResource(name, false);
        }
    }

    private Set<String> loadWeakPasswords() {
        final Set<String> set = new HashSet<>();
        try (InputStream in = this.getResource("slabe-hasla.txt")) {
            if (in != null) {
                readList(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), set);
            }
        } catch (final IOException e) {
            this.getLogger().warning("slabe-hasla.txt (jar): " + e.getMessage());
        }
        final Path own = this.getDataFolder().toPath().resolve("slabe-hasla.txt");
        if (Files.isRegularFile(own)) {
            try (BufferedReader r = Files.newBufferedReader(own, StandardCharsets.UTF_8)) {
                readList(r, set);
            } catch (final IOException e) {
                this.getLogger().warning("slabe-hasla.txt: " + e.getMessage());
            }
        }
        return Set.copyOf(set);
    }

    static void readList(final BufferedReader reader, final Set<String> into) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            final String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                into.add(t.toLowerCase(Locale.ROOT));
            }
        }
    }

    private Path importSource() {
        final Path p = Path.of(this.settings.importFile);
        return p.isAbsolute() ? p : Bukkit.getWorldContainer().toPath().resolve(p).normalize();
    }

    /** First start after the switch: imports nLogin once (marker in the database); later starts skip it. */
    private void autoImport() {
        if (!this.settings.importOnStart) {
            return;
        }
        if (this.store.meta(IMPORT_MARKER).isPresent()) {
            return;
        }
        final Path source = this.importSource();
        if (!Files.isRegularFile(source)) {
            this.getLogger().info("Brak " + source + " — import z nLogin pominięty.");
            return;
        }
        try {
            final ImportReport r = this.runImport(true);
            this.getLogger().info(r.summary());
        } catch (final Exception e) {
            this.getLogger().severe("Import z nLogin nie powiódł się: " + e.getMessage()
                + " — konta można zaimportować później: /straznik import nlogin potwierdz (konsola).");
        }
    }

    /** Import (or dry run) from the configured nLogin file; writes a numbers-only JSON report. */
    public ImportReport runImport(final boolean apply) throws IOException, SQLException {
        final ImportReport report = new NLoginImporter(this.settings.nickPattern).run(this.importSource(), this.store, apply);
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        final Path out = this.getDataFolder().toPath().resolve("import").resolve("nlogin-" + stamp + (apply ? "" : "-proba") + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toPrettyJson(), StandardCharsets.UTF_8);
        if (apply) {
            this.store.setMeta(IMPORT_MARKER, stamp + " zaimportowano=" + report.imported + " juzBylo="
                + report.alreadyImported + " pominieto=" + report.skippedTotal());
            this.events.log("IMPORT_NLOGIN", null, null, "zaimportowano=" + report.imported + " juzBylo="
                + report.alreadyImported + " pominieto=" + report.skippedTotal());
        }
        return report;
    }

    /** Copy of the nLogin database with the current accounts (rollback), in {@code plugins/Straznik/eksport/}. */
    public NLoginExporter.Result runExport() throws IOException, SQLException {
        this.store.flush();
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        final Path out = this.getDataFolder().toPath().resolve("eksport").resolve("nlogin-" + stamp + ".db");
        final NLoginExporter.Result r = NLoginExporter.export(this.importSource(), out, this.store.snapshot());
        this.events.log("EKSPORT_NLOGIN", null, null, "zaktualizowano=" + r.updated() + " dodano=" + r.inserted());
        return r;
    }

    public List<String> statusLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("Strażnik: " + (this.active ? "aktywny" : "nieaktywny") + ", kont: " + this.store.size()
            + ", czeka na logowanie: " + this.auth.waitingCount() + ", zalogowanych: " + this.auth.authenticatedCount());
        lines.add("Hasła: " + this.auth.hasher().describe() + "; zasady: " + this.settings.passwordMinLength + "–"
            + this.settings.passwordMaxLength + " znaków, słabych haseł na liście: " + this.weakPasswords.size());
        lines.add("Import nLogin: " + this.store.meta(IMPORT_MARKER).orElse("nie wykonano"));
        lines.add("Kopie limbo do oddania: " + this.limbo.count() + (this.store.failed() ? "; UWAGA: błąd zapisu bazy!" : ""));
        return lines;
    }

    public Settings settings() {
        return this.settings;
    }

    public Messages messages() {
        return this.messages;
    }

    public AccountStore store() {
        return this.store;
    }

    public AuthService auth() {
        return this.auth;
    }
}
