// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel;

import com.google.gson.JsonObject;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.panel.access.Roles;
import pl.medivalrpg.panel.audit.AuditLog;
import pl.medivalrpg.panel.auth.AuthService;
import pl.medivalrpg.panel.auth.NLoginVerifier;
import pl.medivalrpg.panel.auth.StraznikVerifier;
import pl.medivalrpg.panel.command.PanelCommand;
import pl.medivalrpg.panel.data.PanelData;
import pl.medivalrpg.panel.net.Protocol;
import pl.medivalrpg.panel.net.WorkerConnection;
import pl.medivalrpg.panel.rpc.RpcService;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Panel: bridge between the web panel (Cloudflare Worker {@code panel-mc} + Durable Object) and the game.
 * The plugin is authoritative: it checks passwords (Straznik, fallback nLogin), issues login links, and executes every RPC with its
 * own permission check on the main thread. See PANEL.md.
 */
public final class PanelPlugin extends JavaPlugin implements Listener, WorkerConnection.Handler {

    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private volatile PanelConfig config;
    private volatile Messages messages;
    private KrajeApi kraje;
    private SkarbiecApi skarbiec;
    private PanelData data;
    private AuditLog audit;
    private pl.medivalrpg.panel.history.HistoryService history;
    private pl.medivalrpg.panel.health.HealthService health;
    private Roles roles;
    private AuthService auth;
    private RpcService rpc;
    private PanelCommand command;
    private WorkerConnection connection;
    private ExecutorService handlerThread;
    private ExecutorService authThreads;
    private ScheduledExecutorService timer;
    private pl.medivalrpg.panel.map.MapRenderService mapRender;
    private pl.medivalrpg.panel.map.KrajeFiles krajeFiles;

    @Override
    public void onEnable() {
        this.getLogger().info("Panel by Maciej Kaczmarczyk — lektoro.pl");
        this.saveDefaultConfig();
        this.ensureSecret();
        this.config = new PanelConfig(this.getConfig());
        this.messages = new Messages(this.getConfig());

        this.kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        this.skarbiec = Bukkit.getServicesManager().load(SkarbiecApi.class);
        if (this.kraje == null || this.skarbiec == null) {
            this.getLogger().severe("Brak KrajeApi lub SkarbiecApi w ServicesManager — Panel się wyłącza.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.data = new PanelData(this.getDataFolder().toPath(), this.getLogger());
        this.data.load();
        this.audit = new AuditLog(this.getDataFolder().toPath(), this.getLogger(), this.config.auditMemory);
        this.audit.loadRecent();
        this.roles = new Roles(() -> this.config, () -> this.kraje, this.data);

        this.handlerThread = Executors.newSingleThreadExecutor(daemon("Panel-Handler"));
        this.authThreads = Executors.newFixedThreadPool(2, daemon("Panel-Auth"));
        this.timer = Executors.newSingleThreadScheduledExecutor(daemon("Panel-Timer"));
        this.auth = new AuthService(this, this.authThreads);
        this.auth.setVerifier(StraznikVerifier.createOr(this, this::revoke, () -> NLoginVerifier.create(this, this::revoke)));
        this.history = new pl.medivalrpg.panel.history.HistoryService(this, this.getDataFolder().toPath());
        this.health = new pl.medivalrpg.panel.health.HealthService(this, this.getDataFolder().toPath());
        this.mapRender = new pl.medivalrpg.panel.map.MapRenderService(this);
        final org.bukkit.plugin.Plugin krajePlugin = Bukkit.getPluginManager().getPlugin("Kraje");
        this.krajeFiles = new pl.medivalrpg.panel.map.KrajeFiles(krajePlugin == null ? null : krajePlugin.getDataFolder().toPath(), this.getLogger());
        this.rpc = new RpcService(this, this.timer);

        this.connection = new WorkerConnection(this.getLogger(), this.handlerThread, this);
        this.connection.setDebug(this.config.debug);

        this.command = new PanelCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(this.command.build(), "Panel państwa w przeglądarce", List.of()));
        Bukkit.getPluginManager().registerEvents(this, this);

        // Housekeeping: expired rate-limit entries, assignments of countries that are gone.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            this.auth.guard().cleanup();
            this.rpc.cleanup();
            // Never prune while Kraje reports no countries at all (failed load) — assignments would be lost.
            if (!this.kraje.getCountries().isEmpty()) {
                this.data.retain(this.roles.npcCountries());
            }
        }, 20L * 60, 20L * 60 * 10);

        this.history.start();
        this.health.start();
        this.startConnection();
    }

    @Override
    public void onDisable() {
        if (this.connection != null) {
            this.connection.stop();
        }
        if (this.mapRender != null) {
            this.mapRender.shutdown();
        }
        for (final ExecutorService e : new ExecutorService[] {this.handlerThread, this.authThreads, this.timer}) {
            if (e != null) {
                e.shutdownNow();
            }
        }
        if (this.history != null) {
            this.history.stop();
        }
        if (this.health != null) {
            this.health.stop();
        }
        if (this.audit != null) {
            this.audit.close();
        }
        if (this.data != null) {
            this.data.close();
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return r -> {
            final Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ config

    /** {@code /panel reload}: config and messages; the connection restarts when the address or secret changed. */
    public void reloadAll() {
        final String oldUrl = this.config.workerUrl;
        final String oldSecret = this.config.pluginSecret;
        this.reloadConfig();
        this.ensureSecret();
        this.config = new PanelConfig(this.getConfig());
        this.messages = new Messages(this.getConfig());
        this.data.load();
        this.auth.reload();
        this.rpc.reload();
        this.health.reload();
        this.connection.setDebug(this.config.debug);
        if (!oldUrl.equals(this.config.workerUrl) || !oldSecret.equals(this.config.pluginSecret)) {
            this.startConnection();
        }
    }

    /** Adds/removes a nick in {@code admins} / {@code npc-managers} and saves config.yml. Returns whether it changed. */
    public boolean editRoleList(final String key, final String name, final boolean add) {
        final List<String> list = new ArrayList<>(this.getConfig().getStringList(key));
        final boolean present = list.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        if (add == present) {
            return false;
        }
        if (add) {
            list.add(name);
        } else {
            list.removeIf(n -> n.equalsIgnoreCase(name));
        }
        this.getConfig().set(key, list);
        this.saveConfig();
        this.config = new PanelConfig(this.getConfig());
        this.getLogger().info("Role panelu: " + key + (add ? " + " : " - ") + name);
        return true;
    }

    private void ensureSecret() {
        final String secret = this.getConfig().getString("plugin-secret", "");
        if (secret == null || secret.isBlank()) {
            final SecureRandom r = new SecureRandom();
            final StringBuilder sb = new StringBuilder(48);
            for (int i = 0; i < 48; i++) {
                sb.append(SECRET_ALPHABET.charAt(r.nextInt(SECRET_ALPHABET.length())));
            }
            this.getConfig().set("plugin-secret", sb.toString());
            this.saveConfig();
            this.getLogger().warning("Wygenerowano nowy plugin-secret i zapisano go w config.yml. "
                + "Ustaw tę samą wartość w Workerze: wrangler secret put PANEL_SECRET");
        }
    }

    private void startConnection() {
        if (this.config.workerUrl.isEmpty()) {
            this.getLogger().warning("Nie ustawiono worker-url w config.yml — panel jest wyłączony.");
            this.connection.disconnect();
            return;
        }
        try {
            this.connection.start(this.config.workerUrl, this.config.pluginSecret);
        } catch (final IllegalArgumentException e) {
            this.getLogger().severe("Niepoprawny worker-url: " + this.config.workerUrl + " (" + e.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------ Worker messages (handler thread)

    @Override
    public void onConnected() {
        // Nothing to resend: sessions live in the Durable Object.
    }

    @Override
    public void onDisconnected() {
        // WorkerConnection reconnects on its own.
    }

    @Override
    public void onMessage(final String type, final JsonObject msg) {
        try {
            switch (type.toLowerCase(Locale.ROOT)) {
                case "auth" -> this.auth.handle(msg);
                case "rpc" -> this.rpc.handle(msg);
                default -> {
                    if (this.config.debug) {
                        this.getLogger().info("Nieznany typ wiadomości od Workera: " + type);
                    }
                }
            }
        } catch (final RuntimeException e) {
            this.getLogger().log(Level.WARNING, "Błąd obsługi wiadomości " + type, e);
        }
    }

    /** Sends a protocol message; false when not connected. */
    public boolean send(final Map<String, Object> message) {
        return this.connection != null && this.connection.send(message);
    }

    public boolean connected() {
        return this.connection != null && this.connection.isOpen();
    }

    /** Invalidates all panel sessions of a player (logout, password change). */
    public void revoke(final UUID player) {
        if (this.send(Protocol.revoke(player))) {
            this.audit.log(new AuditLog.Entry(System.currentTimeMillis(), player.toString(), Roles.canonicalName(player, "?"), "-",
                "revoke", null, true, null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.command.forget(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ accessors

    public PanelConfig cfg() {
        return this.config;
    }

    public Messages messages() {
        return this.messages;
    }

    public KrajeApi kraje() {
        return this.kraje;
    }

    public SkarbiecApi skarbiec() {
        return this.skarbiec;
    }

    /** Armia (softdepend) or null; looked up every time (it may be reloaded). */
    public ArmiaApi armia() {
        if (Bukkit.getPluginManager().getPlugin("Armia") == null) {
            return null;
        }
        try {
            return Bukkit.getServicesManager().load(ArmiaApi.class);
        } catch (final LinkageError e) {
            return null;
        }
    }

    /** Top-down map renderer of the plan editor. */
    public pl.medivalrpg.panel.map.MapRenderService mapRender() {
        return this.mapRender;
    }

    /** Kraje's protected areas and claim cost (files KrajeApi does not expose). */
    public pl.medivalrpg.panel.map.KrajeFiles krajeFiles() {
        return this.krajeFiles;
    }

    /** Ludnosc (softdepend) or null. */
    public LudnoscApi ludnosc() {
        if (Bukkit.getPluginManager().getPlugin("Ludnosc") == null) {
            return null;
        }
        try {
            return Bukkit.getServicesManager().load(LudnoscApi.class);
        } catch (final LinkageError e) {
            return null;
        }
    }

    /**
     * Postep (softdepend) or null. Postep itself soft-depends only on Ludnosc, Armia and squaremap — not on Panel — so
     * Panel may soft-depend on Postep without a load-order cycle.
     */
    public pl.medivalrpg.postep.api.PostepApi postep() {
        if (Bukkit.getPluginManager().getPlugin("Postep") == null) {
            return null;
        }
        try {
            return Bukkit.getServicesManager().load(pl.medivalrpg.postep.api.PostepApi.class);
        } catch (final LinkageError e) {
            return null;
        }
    }

    /**
     * Zycie (softdepend) or null: mood of NPC citizens. Zycie depends on Kraje and Ludnosc, not on Panel, so there is no
     * load-order cycle; looked up lazily like Postep (an old jar without the API class gives null).
     */
    public pl.medivalrpg.zycie.api.ZycieApi zycie() {
        if (Bukkit.getPluginManager().getPlugin("Zycie") == null) {
            return null;
        }
        try {
            return Bukkit.getServicesManager().load(pl.medivalrpg.zycie.api.ZycieApi.class);
        } catch (final LinkageError e) {
            return null;
        }
    }

    public PanelData data() {
        return this.data;
    }

    public pl.medivalrpg.panel.history.HistoryService history() {
        return this.history;
    }

    /** Server health probe (TPS, MSPT, NPC load). */
    public pl.medivalrpg.panel.health.HealthService health() {
        return this.health;
    }

    public AuditLog audit() {
        return this.audit;
    }

    public Roles roles() {
        return this.roles;
    }

    public AuthService auth() {
        return this.auth;
    }
}
