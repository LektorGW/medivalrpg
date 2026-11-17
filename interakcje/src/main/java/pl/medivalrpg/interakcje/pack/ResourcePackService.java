// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.interakcje.InterakcjePlugin;
import pl.medivalrpg.interakcje.anim.BetterModelBackend;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * Serves the resource pack BetterModel generates: copies the zip into the squaremap web folder
 * ({@code plugins/squaremap/web/pack/medivalrpg.zip} -> {@code http://host:port/pack/medivalrpg.zip}),
 * computes its SHA-1 and sends it to every player on join (required, Polish prompt). When the pack
 * changes (BetterModel reload, new models) the new version is sent to everyone online.
 *
 * <p>Changes are noticed through BetterModel's reload event ({@link #requestCheck()}) and, as a fallback,
 * by polling the file's modification time and size.
 */
public final class ResourcePackService implements Listener {
    /** Fixed id: a new version replaces the old one on the client instead of stacking. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("medivalrpg:interakcje-resource-pack".getBytes(StandardCharsets.UTF_8));

    private final InterakcjePlugin plugin;
    /** Read by BetterModel's threads ({@link #hasPack}) and the async checks. */
    private volatile Settings settings = Settings.from(null);
    private volatile Path source;
    private volatile boolean sourceIsFolder;
    private volatile Pack current;
    private volatile String lastStamp;
    private final AtomicBoolean checking = new AtomicBoolean();
    /** Bumped by every new download check and on stop: older checks see it and quit. */
    private final AtomicLong verifyGeneration = new AtomicLong();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> sent = new HashMap<>();
    private BukkitTask poller;
    /** Every async task of this service; {@link #stop()} ends them before the class loader closes. */
    private final AsyncWorkers workers = new AsyncWorkers();
    /** Longest wait in onDisable for running async tasks (a download check reacts to the interrupt at once). */
    static final long STOP_WAIT_MILLIS = 3000L;

    /** A published pack version. */
    public record Pack(String sha1, String url, long size) {
    }

    public record Settings(boolean enabled, String urlBase, String path, String webRoot, String source,
                           boolean required, String prompt, int pollSeconds, boolean resendOnChange,
                           boolean kickOnDecline, boolean verifyDownload, boolean cacheBust, int sendDelayTicks,
                           PackVerifier.Policy verifyPolicy, String verifyLocalBase) {

        public static Settings from(ConfigurationSection s) {
            if (s == null) {
                s = new MemoryConfiguration();
            }
            return new Settings(
                    s.getBoolean("enabled", true),
                    s.getString("url-base", "http://mc.example.org:8100"),
                    s.getString("path", "pack/medivalrpg.zip"),
                    s.getString("web-root", "plugins/squaremap/web"),
                    s.getString("source", "auto"),
                    s.getBoolean("required", true),
                    s.getString("prompt", "<gold>Serwer MEDIVALRPG</gold> <gray>potrzebuje paczki zasobów z animacjami postaci.</gray>"),
                    Math.max(0, s.getInt("poll-seconds", 30)),
                    s.getBoolean("resend-on-change", true),
                    s.getBoolean("kick-on-decline", true),
                    s.getBoolean("verify-download", true),
                    s.getBoolean("cache-bust", true),
                    Math.max(0, s.getInt("send-delay-ticks", 10)),
                    new PackVerifier.Policy(
                            s.getInt("verify-attempts", 6),
                            Math.round(s.getDouble("verify-initial-delay-seconds", 15) * 1000),
                            Math.round(s.getDouble("verify-retry-seconds", 5) * 1000),
                            Math.round(s.getDouble("verify-retry-max-seconds", 60) * 1000)),
                    s.getString("verify-local-url", "auto"));
        }
    }

    public ResourcePackService(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        stopTasks();
        settings = Settings.from(plugin.getConfig().getConfigurationSection("resource-pack"));
        if (!settings.enabled()) {
            plugin.getLogger().info("Wysyłanie paczki zasobów wyłączone (resource-pack.enabled: false).");
            return;
        }
        resolveSource();
        plugin.getLogger().info("Paczka zasobów: źródło " + serverRoot().relativize(source.toAbsolutePath().normalize())
                + " -> " + serverRoot().relativize(target()) + " (" + PackFiles.url(settings.urlBase(), settings.path(), null, false) + ")");
        Bukkit.getScheduler().runTaskLater(plugin, () -> requestCheck(false), 40L);
        if (settings.pollSeconds() > 0) {
            long period = settings.pollSeconds() * 20L;
            poller = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                    () -> workers.run(false, () -> check(false)), period, period);
        }
    }

    /** {@code /interakcje reload}. */
    public void reload() {
        boolean was = settings.enabled();
        start();
        if (!settings.enabled() && was) {
            loaded.clear();
        }
    }

    /**
     * onDisable: cancels the poller and the download check and waits (at most {@link #STOP_WAIT_MILLIS}) until
     * running async tasks end – otherwise they fail after Paper closes the class loader ("zip file error") and
     * Paper nags about async tasks at shutdown.
     */
    public void stop() {
        stopTasks();
        int left = workers.close(STOP_WAIT_MILLIS);
        if (left > 0) {
            plugin.getLogger().warning("Paczka zasobów: " + left + " zadań w tle nie zakończyło się w "
                    + STOP_WAIT_MILLIS / 1000 + " s wyłączania.");
        }
    }

    private void stopTasks() {
        verifyGeneration.incrementAndGet();
        if (poller != null) {
            poller.cancel();
            poller = null;
        }
    }

    private void resolveSource() {
        Path root = serverRoot();
        String configured = settings.source();
        if (configured == null || configured.isBlank() || configured.equalsIgnoreCase("auto")) {
            if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
                try {
                    BetterModelBackend.PackOutput out = BetterModelBackend.packOutput();
                    if (out != null) {
                        source = out.path();
                        sourceIsFolder = out.folder();
                        return;
                    }
                    plugin.getLogger().warning("BetterModel ma pack-type: none – nie generuje paczki.");
                } catch (LinkageError | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Nie udało się odczytać ścieżki paczki z BetterModel", e);
                }
            }
            source = root.resolve("plugins/BetterModel/build.zip");
            sourceIsFolder = false;
            return;
        }
        source = root.resolve(configured);
        sourceIsFolder = Files.isDirectory(source);
    }

    private Path serverRoot() {
        return plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile().toPath().toAbsolutePath().normalize();
    }

    private Path target() {
        Path web = serverRoot().resolve(settings.webRoot());
        Path t = PackFiles.inside(web, settings.path());
        if (t == null) {
            throw new IllegalArgumentException("resource-pack.path wychodzi poza web-root: " + settings.path());
        }
        return t;
    }

    // ------------------------------------------------------------------ change detection

    /** BetterModel rebuilt its pack: look at the file now and once more a moment later. */
    public void requestCheck() {
        requestCheck(true);
    }

    private void requestCheck(boolean twice) {
        if (!settings.enabled() || !plugin.isEnabled() || workers.closed()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> workers.run(false, () -> check(true)));
        if (twice) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> workers.run(false, () -> check(true)), 100L);
        }
    }

    /** Async: hash the source, publish it to the web folder when it changed. */
    private void check(boolean force) {
        Path src = source;
        if (src == null || !checking.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!Files.exists(src)) {
                return;
            }
            String stamp = PackFiles.stamp(src);
            if (!force && stamp.equals(lastStamp)) {
                return;
            }
            byte[] data = sourceIsFolder ? PackFiles.zipFolder(src) : Files.readAllBytes(src);
            lastStamp = stamp;
            if (data.length == 0) {
                return;
            }
            String sha1 = PackFiles.sha1(data);
            Pack now = current;
            Path target = target();
            if (now != null && now.sha1().equals(sha1) && Files.exists(target)) {
                return;
            }
            PackFiles.writeAtomically(target, data);
            Pack pack = new Pack(sha1, PackFiles.url(settings.urlBase(), settings.path(), sha1, settings.cacheBust()), data.length);
            if (!running()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> publish(pack));
            if (settings.verifyDownload()) {
                // Own async task: the check waits and retries, it must not hold the change-detection lock.
                Settings s = settings;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> workers.run(true, () -> verify(pack, s)));
            }
        } catch (IOException | RuntimeException e) {
            if (running()) {
                plugin.getLogger().log(Level.WARNING, "Paczka zasobów: nie udało się przygotować " + src, e);
            }
            // stopping: the scheduler refuses new tasks of a disabled plugin – nothing to report
        } finally {
            checking.set(false);
        }
    }

    private boolean running() {
        return plugin.isEnabled() && !workers.closed();
    }

    private void publish(Pack pack) {
        Pack old = current;
        current = pack;
        plugin.getLogger().info(String.format("Paczka zasobów %s: %.1f KB, SHA-1 %s, %s",
                old == null ? "gotowa" : "zmieniona", pack.size() / 1024.0, pack.sha1(), pack.url()));
        if (old != null && settings.resendOnChange()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!pack.sha1().equals(sent.get(p.getUniqueId()))) {
                    send(p);
                }
            }
        } else if (old == null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!sent.containsKey(p.getUniqueId())) {
                    send(p);
                }
            }
        }
    }

    /**
     * Async, diagnostics only: downloads the published URL and compares the hash. Waits first and retries with
     * backoff (at server start the pack is published before the web server / network path is surely ready) and
     * warns only after the last attempt. A newer pack or plugin stop cancels the check.
     */
    private void verify(Pack pack, Settings s) {
        long generation = verifyGeneration.incrementAndGet();
        BooleanSupplier cancelled = () -> generation != verifyGeneration.get() || !running();
        HttpClient http = PackVerifier.httpClient(Duration.ofSeconds(5));
        PackVerifier.Fetcher fetcher = PackVerifier.httpFetcher(http, Duration.ofSeconds(20));
        PackVerifier.Sleeper sleeper = millis -> {
            long end = System.nanoTime() + millis * 1_000_000L;
            while (!cancelled.getAsBoolean()) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return;
                }
                Thread.sleep(Math.min(left, 250L));
            }
        };
        PackVerifier.Outcome o;
        try {
            o = new PackVerifier(fetcher, sleeper, s.verifyPolicy())
                    .run(pack.url(), pack.sha1(), PackVerifier.localUrl(s.verifyLocalBase(), pack.url()), cancelled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // stop(): AsyncWorkers clears it again for the pooled thread
            return;
        } finally {
            http.shutdownNow(); // no idle selector thread / connection left behind
        }
        if (o.kind() == PackVerifier.Kind.CANCELLED || !running()) {
            return;
        }
        String tries = o.attempts() == 1 ? "1 próbie" : o.attempts() + " próbach";
        switch (o.kind()) {
            case OK -> plugin.getLogger().info("Paczka zasobów: adres działa, SHA-1 zgodny"
                    + (o.attempts() > 1 ? " (za " + o.attempts() + ". razem)." : "."));
            case LOCAL_ONLY -> plugin.getLogger().info("Paczka zasobów: lokalny serwer WWW (" + o.localUrl()
                    + ") podaje właściwy plik, ale adresu publicznego " + pack.url() + " nie da się sprawdzić z wnętrza serwera po "
                    + tries + ": " + o.reason() + ". Zwykle oznacza to brak NAT loopback u hostingu – gracze łączą się z zewnątrz."
                    + " Jeśli zgłaszają błąd pobierania, sprawdź adres z zewnątrz.");
            case HTTP_STATUS -> plugin.getLogger().warning("Paczka zasobów: " + pack.url() + " zwraca HTTP " + o.status()
                    + " po " + tries + " – sprawdź squaremap (web-root / port) i resource-pack.url-base.");
            case SHA_MISMATCH -> plugin.getLogger().warning("Paczka zasobów: po " + tries
                    + " pobrany plik nadal ma inny SHA-1 niż wysłany – serwer WWW podaje starą wersję?");
            default -> plugin.getLogger().warning("Paczka zasobów: nie udało się pobrać " + pack.url() + " po " + tries
                    + " (" + Math.round(s.verifyPolicy().totalDelay() / 1000.0) + " s): " + o.reason()
                    + (o.localUrl() == null ? "" : "; lokalnie " + o.localUrl() + ": " + o.local())
                    + ". Gracze prawdopodobnie też jej nie pobiorą – sprawdź adres i squaremap.");
        }
    }

    // ------------------------------------------------------------------ players

    /** Sends the current pack to the player (no-op when there is none yet). */
    public boolean send(Player p) {
        Pack pack = current;
        if (pack == null || !settings.enabled()) {
            return false;
        }
        Component prompt = plugin.messages().parse(settings.prompt());
        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(pack.url()), pack.sha1()))
                .required(settings.required())
                .prompt(prompt)
                .replace(false)
                .build();
        p.sendResourcePacks(request);
        sent.put(p.getUniqueId(), pack.sha1());
        return true;
    }

    /**
     * Can this viewer see model animations? Thread-safe (BetterModel asks from its own threads).
     * Without this module every viewer counts as having the pack (it is served some other way).
     */
    public boolean hasPack(UUID viewer) {
        return !settings.enabled() || loaded.contains(viewer);
    }

    public Pack current() {
        return current;
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        if (!settings.enabled()) {
            out.add("Paczka: wyłączona w configu");
            return out;
        }
        Pack p = current;
        out.add("Źródło: " + (source == null ? "?" : source) + (source != null && Files.exists(source) ? "" : " (brak pliku)"));
        out.add(p == null ? "Paczka: jeszcze nie opublikowana" : String.format("Paczka: %.1f KB, SHA-1 %s", p.size() / 1024.0, p.sha1()));
        if (p != null) {
            out.add("URL: " + p.url());
        }
        out.add("Graczy z wczytaną paczką: " + loaded.size() + "/" + Bukkit.getOnlinePlayers().size());
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!settings.enabled()) {
            return;
        }
        UUID id = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                send(p);
            }
        }, settings.sendDelayTicks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        loaded.remove(e.getPlayer().getUniqueId());
        sent.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getID())) {
            return;
        }
        Player p = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(p.getUniqueId());
            case DECLINED -> {
                loaded.remove(p.getUniqueId());
                if (settings.required() && settings.kickOnDecline()) {
                    p.kick(plugin.messages().get("pack-declined-kick"));
                }
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                loaded.remove(p.getUniqueId());
                plugin.getLogger().warning("Paczka zasobów: " + p.getName() + " – " + e.getStatus());
                plugin.messages().send(p, "pack-failed");
            }
            case DISCARDED -> loaded.remove(p.getUniqueId());
            default -> {
                // ACCEPTED, DOWNLOADED: still loading
            }
        }
    }
}
