// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;

/**
 * Country flags ("chorągwie"): uploads with mandatory moderation, approved images as BetterModel banners over capitals
 * and at embassies, the batched resource-pack rebuild, map icons, the panel bridge and the bridge for other plugins.
 * Everything here runs on the main thread except image decoding and the BetterModel reload (async tasks).
 */
public final class FlagService {

    public static final String PERMISSION_MODERATE = "kraje.flagi.moderuj";
    static final String DEFAULT_IDENTIFIER = "kraje-flaga-domyslna-v1";
    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpe?g);base64,", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=\\r\\n]*");

    /** Model to show for a country: own image (tint -1) or the shared default tinted with the country colour. */
    public record Look(String model, int tint) {
    }

    private final KrajePlugin plugin;
    private final FlagStore store;
    private FlagSettings settings = FlagSettings.DEFAULT;
    private FlagBook book = new FlagBook();
    private BannerRenderer renderer = BannerRenderer.NONE;
    private BannerManager banners;
    private FlagMapIcons icons;
    private BukkitTask task;
    private volatile boolean rebuilding;
    private long rebuildStartedAt;
    private boolean enabled;
    private int ticks;
    /** The own model of a country is shown from this time (players first download the rebuilt pack). */
    private final Map<UUID, Long> liveAt = new HashMap<>();
    private final Map<Integer, byte[]> defaultPngs = new ConcurrentHashMap<>();
    /** Vanilla banner designs (option A) by {@link #designKey}: {@code img:<imageId>} or {@code def:<rgb>}. */
    private final Map<String, BannerFit.Design> designs = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<BannerFit.Design>> designing = new HashMap<>();
    private StandardService standards;
    /** Standards (option B) follow a new look once the switch delay after a rebuild has passed. */
    private long standardsRefreshAt;

    public FlagService(final KrajePlugin plugin) {
        this.plugin = plugin;
        this.store = new FlagStore(plugin.getDataFolder().toPath().resolve("flags"), plugin.getLogger());
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * {@code onLoad}: writes the shared default banner into BetterModel's model folder before BetterModel builds its
     * startup pack, so the first start needs no extra rebuild. Path-only, no BetterModel classes.
     */
    public static void installDefaultModel(final KrajePlugin plugin) {
        try {
            final FlagSettings s = FlagSettings.from(plugin.getConfig().getConfigurationSection("flags"));
            if (!s.enabled()) {
                return;
            }
            writeDefaultModel(plugin.getDataFolder().toPath().resolveSibling("BetterModel").resolve("models"), s);
        } catch (final IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się zainstalować domyślnej chorągwi", e);
        }
    }

    /** @return true when the file was (re)written */
    static boolean writeDefaultModel(final Path modelsDir, final FlagSettings s) throws IOException {
        final String json = FlagModel.bbmodel(s.defaultModel(), FlagImage.defaultTexture(), DEFAULT_IDENTIFIER);
        final Path target = modelsDir.resolve(s.defaultModel() + ".bbmodel");
        if (Files.isRegularFile(target) && Files.readString(target, StandardCharsets.UTF_8).equals(json)) {
            return false;
        }
        Files.createDirectories(modelsDir);
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        FlagStore.move(tmp, target);
        return true;
    }

    public void enable() {
        this.settings = FlagSettings.from(this.plugin.getConfig().getConfigurationSection("flags"));
        this.enabled = this.settings.enabled();
        this.book = this.store.load();
        if (!this.enabled) {
            this.plugin.getLogger().info("Chorągwie krajów są wyłączone (flags.enabled: false).");
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            try {
                this.renderer = new BetterModelBanners(this.plugin);
            } catch (final LinkageError e) {
                this.plugin.getLogger().log(Level.WARNING, "BetterModel jest, ale jego API nie pasuje — chorągwie bez modeli.", e);
                this.renderer = BannerRenderer.NONE;
            }
        }
        this.banners = new BannerManager(this);
        this.icons = this.settings.mapIcons() ? FlagMapIcons.create(this) : null;
        this.standards = new StandardService(this.plugin, this);
        this.standards.enable();
        if (this.renderer != BannerRenderer.NONE) {
            // The startup pack was built from the files that are on disk now.
            final Map<UUID, String> onDisk = this.scanModels();
            onDisk.keySet().retainAll(this.book.all().keySet());
            this.book.packBuilt(onDisk, this.book.lastRebuildAt());
            try {
                if (writeDefaultModel(this.renderer.modelsDir(), this.settings)) {
                    this.book.nextRebuildAt = PackSchedule.afterChange(System.currentTimeMillis(), 0, this.book.nextRebuildAt, this.settings);
                }
            } catch (final IOException e) {
                this.plugin.getLogger().log(Level.WARNING, "Nie udało się zapisać domyślnej chorągwi", e);
            }
            if (!this.book.waitingForPack().isEmpty() && this.book.nextRebuildAt == 0) {
                this.book.nextRebuildAt = PackSchedule.afterChange(System.currentTimeMillis(), this.book.lastRebuildAt(), 0, this.settings);
            }
            this.renderer.onReloaded(this::afterAnyReload);
            this.renderer.onPackBuild(this::standardPackFiles);
        }
        // Banner patterns (option A) of every country, one after another off the main thread, shortly after the start.
        Bukkit.getScheduler().runTaskLater(this.plugin, this::warmUpDesigns, 600L);
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 40L, 20L);
        this.plugin.getLogger().info("Chorągwie: " + (this.renderer == BannerRenderer.NONE ? "bez BetterModel (tylko panel i mapa)"
            : "modele BetterModel") + ", flag zatwierdzonych: " + this.book.desiredPack().size()
            + ", w kolejce moderacji: " + this.queue().size() + ".");
    }

    public void disable() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        if (this.banners != null) {
            this.banners.shutdown();
        }
        if (this.standards != null) {
            this.standards.disable();
        }
        this.renderer.shutdown();
        this.store.shutdown(this.book);
    }

    private void tick() {
        try {
            this.banners.tick();
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Błąd chorągwi", e);
        }
        final long now = System.currentTimeMillis();
        if (!this.rebuilding && this.renderer != BannerRenderer.NONE && PackSchedule.due(now, this.book.nextRebuildAt)) {
            this.startRebuild();
        }
        if (this.standardsRefreshAt > 0 && now >= this.standardsRefreshAt) {
            this.standardsRefreshAt = 0;
            this.refreshStandards();
        }
        if (++this.ticks % 60 == 1) {
            this.housekeeping();
        }
    }

    /** Every minute: forget disbanded countries, refresh map icons (colour changes). */
    private void housekeeping() {
        boolean changed = false;
        for (final UUID id : new ArrayList<>(this.book.all().keySet())) {
            if (this.plugin.manager().get(id) == null) {
                this.book.forget(id);
                changed = true;
            }
        }
        if (changed || this.ticks % 600 == 1) {
            this.save(); // also collects unreferenced (e.g. rejected) images every 10 minutes
        }
        if (this.icons != null && this.icons.sync(this.plugin.manager().countries())) {
            this.plugin.mapHook().scheduleRefresh();
        }
    }

    private void save() {
        this.store.saveAsync(this.book);
        this.store.collectGarbage(this.book.referencedImages());
    }

    // ------------------------------------------------------------------ pack rebuild

    private void startRebuild() {
        final Path modelsDir = this.renderer.modelsDir();
        if (modelsDir == null) {
            return;
        }
        this.rebuilding = true;
        this.rebuildStartedAt = System.currentTimeMillis();
        this.book.nextRebuildAt = 0;
        final Map<UUID, String> desired = new HashMap<>();
        final Map<UUID, byte[]> pixels = new HashMap<>();
        final Map<UUID, String> onDisk = this.scanModels();
        for (final Map.Entry<UUID, String> e : this.book.desiredPack().entrySet()) {
            if (this.plugin.manager().get(e.getKey()) == null) {
                continue;
            }
            final byte[] png = this.store.readImage(e.getValue());
            if (png == null) {
                this.plugin.getLogger().warning("Chorągwie: brak pliku zatwierdzonej flagi " + e.getValue() + " — kraj wraca do flagi domyślnej.");
                this.book.dropLost(e.getKey(), System.currentTimeMillis(), this.settings.historySize());
                continue;
            }
            desired.put(e.getKey(), e.getValue());
            if (!e.getValue().equals(onDisk.get(e.getKey()))) {
                pixels.put(e.getKey(), png);
            }
        }
        final FlagSettings s = this.settings;
        this.plugin.getLogger().info("Chorągwie: przebudowa paczki zasobów (nowe/zmienione flagi: " + pixels.size() + ").");
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            BannerRenderer.Reload result;
            try {
                Files.createDirectories(modelsDir);
                for (final Map.Entry<UUID, byte[]> e : pixels.entrySet()) {
                    final String model = s.modelOf(e.getKey());
                    final String json = FlagModel.bbmodel(model, FlagImage.decodeStored(e.getValue()), desired.get(e.getKey()));
                    final Path target = modelsDir.resolve(model + ".bbmodel");
                    final Path tmp = target.resolveSibling(model + ".bbmodel.tmp");
                    Files.writeString(tmp, json, StandardCharsets.UTF_8);
                    FlagStore.move(tmp, target);
                }
                // models of countries that went back to the default flag (or disappeared)
                for (final Map.Entry<UUID, String> e : onDisk.entrySet()) {
                    if (!desired.containsKey(e.getKey())) {
                        Files.deleteIfExists(modelsDir.resolve(s.modelOf(e.getKey()) + ".bbmodel"));
                    }
                }
                writeDefaultModel(modelsDir, s);
                result = this.renderer.reload();
            } catch (final IOException | RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Chorągwie: przygotowanie modeli nie powiodło się", e);
                result = BannerRenderer.Reload.FAILED;
            }
            final BannerRenderer.Reload outcome = result;
            if (this.plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(this.plugin, () -> this.rebuildFinished(outcome));
            }
        });
    }

    private void rebuildFinished(final BannerRenderer.Reload outcome) {
        this.rebuilding = false;
        final long now = System.currentTimeMillis();
        switch (outcome) {
            // normally PluginEndReloadEvent already ran afterAnyReload; this is only the fallback
            case OK -> {
                if (this.book.lastRebuildAt() < this.rebuildStartedAt) {
                    this.afterAnyReload();
                }
            }
            case BUSY -> this.book.nextRebuildAt = PackSchedule.busy(now);
            case FAILED -> this.book.nextRebuildAt = PackSchedule.failed(now, this.settings);
            case UNAVAILABLE -> {
            }
        }
        this.save();
    }

    /** Any successful BetterModel reload (ours or e.g. {@code /bm reload}): the pack holds the model files on disk. */
    private void afterAnyReload() {
        final long now = System.currentTimeMillis();
        final Map<UUID, String> onDiskAll = this.scanModels();
        final Map<UUID, String> onDisk = new HashMap<>(onDiskAll);
        onDisk.keySet().retainAll(this.book.all().keySet());
        onDisk.entrySet().removeIf(e -> {
            final boolean loaded = this.renderer.modelLoaded(this.settings.modelOf(e.getKey()));
            if (!loaded) {
                this.plugin.getLogger().warning("Chorągwie: BetterModel nie wczytał modelu " + this.settings.modelOf(e.getKey()) + " — zostaje flaga domyślna.");
            }
            return !loaded;
        });
        final List<UUID> fresh = new ArrayList<>();
        for (final Map.Entry<UUID, String> e : onDisk.entrySet()) {
            if (!e.getValue().equals(this.book.packed(e.getKey()))) {
                this.liveAt.put(e.getKey(), now + this.settings.switchDelaySeconds() * 1000L);
                if (e.getValue().equals(this.book.current(e.getKey()))) {
                    fresh.add(e.getKey());
                }
            }
        }
        this.book.packBuilt(onDisk, now);
        if (this.banners != null) {
            this.banners.respawnAll();
        }
        // items and placed standards: removed own models at once, new ones after the download delay
        this.refreshStandards();
        this.standardsRefreshAt = now + this.settings.switchDelaySeconds() * 1000L + 1000L;
        // Follow-up only for approvals whose model file is not written yet (approved during the rebuild); a written but
        // unloadable model is not retried in a loop.
        boolean unwritten = false;
        for (final UUID id : this.book.waitingForPack()) {
            unwritten |= !String.valueOf(this.book.current(id)).equals(onDiskAll.get(id));
        }
        if (unwritten && this.book.nextRebuildAt == 0) {
            this.book.nextRebuildAt = PackSchedule.afterChange(now, now, 0, this.settings);
        }
        this.save();
        for (final UUID id : fresh) {
            this.tellMembers(id, "Nowa chorągiew waszego kraju jest już w paczce zasobów — po jej pobraniu zobaczycie ją nad stolicą.");
        }
        this.plugin.getLogger().info("Chorągwie: paczka przebudowana, własnych flag w grze: " + onDisk.size() + ".");
    }

    /** country → imageId of the generated {@code <prefix><id>.bbmodel} files (from their {@code model_identifier}). */
    Map<UUID, String> scanModels() {
        final Map<UUID, String> out = new HashMap<>();
        final Path dir = this.renderer.modelsDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        final Map<String, UUID> byModel = new HashMap<>();
        for (final Country c : this.plugin.manager().countries()) {
            byModel.put(this.settings.modelOf(c.id()) + ".bbmodel", c.id());
        }
        try (var files = Files.list(dir)) {
            for (final Path p : files.toList()) {
                final UUID id = byModel.get(p.getFileName().toString());
                if (id == null) {
                    continue;
                }
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    final JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                    final String ident = o.has("model_identifier") ? o.get("model_identifier").getAsString() : "";
                    if (FlagStore.IMAGE_ID.matcher(ident).matches()) {
                        out.put(id, ident);
                    }
                } catch (final IOException | RuntimeException e) {
                    this.plugin.getLogger().warning("Chorągwie: pomijam uszkodzony model " + p.getFileName());
                }
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Chorągwie: nie można odczytać katalogu modeli", e);
        }
        return out;
    }

    // ------------------------------------------------------------------ looks

    public Look look(final Country c) {
        if (this.renderer == BannerRenderer.NONE) {
            return null;
        }
        final UUID id = c.id();
        final String own = this.settings.modelOf(id);
        if (this.book.live(id) && System.currentTimeMillis() >= this.liveAt.getOrDefault(id, 0L) && this.renderer.modelLoaded(own)) {
            return new Look(own, -1);
        }
        if (this.renderer.modelLoaded(this.settings.defaultModel())) {
            return new Look(this.settings.defaultModel(), c.color() & 0xFFFFFF);
        }
        return null;
    }

    byte[] defaultPng(final int color) {
        return this.defaultPngs.computeIfAbsent(color & 0xFFFFFF,
            rgb -> FlagImage.encodePng(FlagImage.defaultFlag(rgb), FlagImage.WIDTH, FlagImage.HEIGHT));
    }

    /** PNG players see now for this country (approved image or default), for the panel and commands. */
    byte[] currentPng(final Country c) {
        final String cur = this.book.current(c.id());
        final byte[] png = cur == null ? null : this.store.readImage(cur);
        return png != null ? png : this.defaultPng(c.color());
    }

    // ------------------------------------------------------------------ access

    public boolean isModerator(final UUID actor, final boolean panelAdmin) {
        final Player p = actor == null ? null : Bukkit.getPlayer(actor);
        return FlagAccess.moderator(actor, panelAdmin, p != null && p.hasPermission(PERMISSION_MODERATE), this.settings.moderators());
    }

    private static Rank rankIn(final Country c, final UUID actor) {
        final Member m = actor == null ? null : c.member(actor);
        return m == null ? null : m.rank();
    }

    boolean canManage(final Country c, final UUID actor, final boolean moderator) {
        return FlagAccess.manage(rankIn(c, actor), c.npc(), moderator, this.settings.uploadRank());
    }

    private void requireEnabled() {
        if (!this.enabled) {
            throw new IllegalArgumentException("Chorągwie krajów są wyłączone.");
        }
    }

    private void requireManage(final Country c, final UUID actor, final boolean moderator) {
        this.requireEnabled();
        if (!this.canManage(c, actor, moderator)) {
            throw new IllegalArgumentException(c.npc() ? "Flagą państwa NPC zarządza administracja."
                : "Flagą kraju zarządza " + (this.settings.uploadRank() == Rank.RULER ? "Władca." : "Władca albo Doradca."));
        }
    }

    private void requireModerator(final UUID actor, final boolean panelAdmin) {
        this.requireEnabled();
        if (!this.isModerator(actor, panelAdmin)) {
            throw new IllegalArgumentException("Flagi zatwierdza tylko administracja serwera.");
        }
    }

    // ------------------------------------------------------------------ operations (main thread)

    /** Upload: decoding/scaling off the main thread, then the state change back on it. */
    public CompletableFuture<Map<String, Object>> upload(final Country c, final UUID actor, final String actorName,
                                                         final boolean moderator, final String data) {
        this.requireManage(c, actor, moderator);
        final byte[] bytes = decodeBase64(data, this.settings.limits().maxBytes());
        final FlagImage.Limits limits = this.settings.limits();
        final UUID countryId = c.id();
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            final FlagImage.Result r;
            try {
                r = FlagImage.normalize(bytes, limits);
            } catch (final RuntimeException e) {
                out.completeExceptionally(e);
                return;
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    out.complete(this.submit(countryId, actor, actorName, moderator, r));
                } catch (final RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
        });
        return out;
    }

    Map<String, Object> submit(final UUID countryId, final UUID actor, final String actorName, final boolean moderator,
                               final FlagImage.Result r) {
        final Country c = this.plugin.manager().get(countryId);
        if (c == null) {
            throw new IllegalArgumentException("Nie ma takiego kraju.");
        }
        this.requireManage(c, actor, moderator);
        try {
            this.store.writeImage(r.imageId(), r.png());
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Nie udało się zapisać obrazka flagi", e);
            throw new IllegalArgumentException("Serwer nie mógł zapisać obrazka — spróbuj później.");
        }
        final long now = System.currentTimeMillis();
        final FlagBook.Pending pending = new FlagBook.Pending(r.imageId(), now, actor, actorName, r.format(), r.sourceWidth(),
            r.sourceHeight(), r.sourceBytes(), r.colors());
        this.book.submit(c.id(), pending, this.settings.uploadCooldownSeconds() * 1000L, this.settings.historySize());
        this.save();
        this.plugin.getLogger().info("Chorągwie: " + actorName + " wgrał flagę " + r.imageId() + " dla " + c.name() + " (czeka na moderację).");
        this.tellModerators("Nowa flaga do zatwierdzenia: " + c.name() + " (wgrał " + actorName
            + "). Panel → Moderacja chorągwi albo /kraj flaga admin kolejka.");
        final Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("message", "Flaga czeka na zatwierdzenie przez administrację. Do gry trafi dopiero po akceptacji.");
        res.put("pending", this.pendingJson(this.book.get(c.id()).pending()));
        return res;
    }

    public String withdraw(final Country c, final UUID actor, final String actorName, final boolean moderator) {
        this.requireManage(c, actor, moderator);
        this.book.withdraw(c.id(), actorName, System.currentTimeMillis(), this.settings.historySize());
        this.save();
        return "Wycofano flagę oczekującą na zatwierdzenie.";
    }

    public String reset(final Country c, final UUID actor, final String actorName, final boolean moderator) {
        this.requireManage(c, actor, moderator);
        this.book.reset(c.id(), actorName, System.currentTimeMillis(), this.settings.historySize());
        this.save();
        this.syncIconsSoon();
        this.refreshStandards();
        this.tellMembers(c.id(), actorName + " przywrócił domyślną chorągiew kraju (w kolorze kraju).");
        return "Przywrócono domyślną flagę w kolorze kraju — od razu w grze.";
    }

    public String restore(final Country c, final UUID actor, final String actorName, final boolean moderator, final String imageId) {
        this.requireManage(c, actor, moderator);
        this.book.restore(c.id(), imageId, actorName, System.currentTimeMillis(), this.settings.historySize(), this.store::hasImage);
        final boolean live = this.book.live(c.id());
        if (!live) {
            this.schedule();
        } else {
            this.refreshStandards();
        }
        this.save();
        this.syncIconsSoon();
        return live ? "Przywrócono wcześniejszą flagę — jest już w grze."
            : "Przywrócono wcześniejszą flagę. W grze pojawi się po przebudowie paczki zasobów" + this.etaText() + ".";
    }

    public String approve(final Country c, final String imageId, final UUID actor, final String actorName, final boolean panelAdmin) {
        this.requireModerator(actor, panelAdmin);
        this.book.approve(c.id(), imageId, actorName, System.currentTimeMillis(), this.settings.historySize());
        this.schedule();
        this.save();
        this.syncIconsSoon();
        this.plugin.getLogger().info("Chorągwie: " + actorName + " zatwierdził flagę " + this.book.current(c.id()) + " kraju " + c.name() + ".");
        this.tellMembers(c.id(), "Administracja zatwierdziła nową flagę kraju. W grze pojawi się po przebudowie paczki zasobów"
            + this.etaText() + ".");
        return "Zatwierdzono flagę kraju " + c.name() + ". Przebudowa paczki" + this.etaText() + ".";
    }

    public String reject(final Country c, final String imageId, final String reason, final UUID actor, final String actorName,
                         final boolean panelAdmin) {
        this.requireModerator(actor, panelAdmin);
        this.book.reject(c.id(), imageId, actorName, reason, System.currentTimeMillis(), this.settings.historySize());
        this.save();
        this.plugin.getLogger().info("Chorągwie: " + actorName + " odrzucił flagę kraju " + c.name() + ": " + reason);
        this.tellMembers(c.id(), "Administracja odrzuciła wgraną flagę. Powód: " + reason.strip());
        return "Odrzucono flagę kraju " + c.name() + ".";
    }

    public String forceRebuild(final UUID actor, final boolean panelAdmin) {
        this.requireModerator(actor, panelAdmin);
        if (this.renderer == BannerRenderer.NONE) {
            throw new IllegalArgumentException("Na serwerze nie ma BetterModel — paczki nie da się przebudować.");
        }
        if (this.rebuilding) {
            return "Przebudowa paczki już trwa.";
        }
        this.book.nextRebuildAt = PackSchedule.forced(System.currentTimeMillis(), this.book.lastRebuildAt(), this.settings);
        this.save();
        return "Przebudowa paczki zasobów" + this.etaText() + ".";
    }

    public String placeCapital(final Country c, final Location at, final UUID actor, final boolean moderator) {
        this.requireManage(c, actor, moderator);
        if (!moderator && !c.id().equals(this.plugin.manager().ownerAt(at))) {
            throw new IllegalArgumentException("Chorągiew stolicy można postawić tylko na ziemi własnego kraju.");
        }
        this.book.capitalSpot(c.id(), new FlagBook.Spot(at.getWorld().getName(), at.getBlockX() + 0.5, at.getBlockY(),
            at.getBlockZ() + 0.5, Math.round(at.getYaw() / 15f) * 15f));
        this.save();
        return "Chorągiew stolicy stoi teraz tutaj.";
    }

    public String autoCapital(final Country c, final UUID actor, final boolean moderator) {
        this.requireManage(c, actor, moderator);
        this.book.capitalSpot(c.id(), null);
        this.banners.forgetBell(c.id());
        this.save();
        return "Chorągiew stolicy wraca na miejsce automatyczne (przy dzwonie, przy punkcie spawnu albo w środku stolicy).";
    }

    private void schedule() {
        this.book.nextRebuildAt = PackSchedule.afterChange(System.currentTimeMillis(), this.book.lastRebuildAt(), this.book.nextRebuildAt,
            this.settings);
    }

    private void syncIconsSoon() {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (this.icons != null && this.icons.sync(this.plugin.manager().countries())) {
                this.plugin.mapHook().scheduleRefresh();
            }
        });
    }

    String etaText() {
        if (this.renderer == BannerRenderer.NONE) {
            return " (na serwerze brak BetterModel)";
        }
        final long next = this.book.nextRebuildAt();
        if (this.rebuilding) {
            return " (trwa teraz)";
        }
        if (next <= 0) {
            return "";
        }
        final long minutes = Math.max(0, (next - System.currentTimeMillis() + 59_999) / 60_000);
        return minutes <= 0 ? " (za chwilę)" : " (ok. " + minutes + " min)";
    }

    static byte[] decodeBase64(final String data, final int maxBytes) {
        if (data == null || data.isBlank()) {
            throw new IllegalArgumentException("Nie wybrano pliku.");
        }
        String s = data.strip();
        final var m = DATA_URL.matcher(s);
        if (m.find()) {
            s = s.substring(m.end());
        } else if (s.startsWith("data:")) {
            throw new IllegalArgumentException("Dozwolone są tylko obrazki PNG i JPG.");
        }
        if (s.length() > (long) maxBytes * 4 / 3 + 16) {
            throw new IllegalArgumentException("Plik jest za duży, limit to " + (maxBytes + 1023) / 1024 + " KB.");
        }
        if (!BASE64.matcher(s).matches()) {
            throw new IllegalArgumentException("Uszkodzone dane pliku.");
        }
        try {
            return Base64.getMimeDecoder().decode(s);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Uszkodzone dane pliku.");
        }
    }

    // ------------------------------------------------------------------ notifications

    private void tellMembers(final UUID country, final String text) {
        final Country c = this.plugin.manager().get(country);
        if (c == null) {
            return;
        }
        for (final Member m : c.members()) {
            final Player p = Bukkit.getPlayer(m.uuid());
            if (p != null) {
                this.plugin.messages().send(p, "flag-notice", Placeholder.unparsed("text", text));
            }
        }
    }

    private void tellModerators(final String text) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (this.isModerator(p.getUniqueId(), false)) {
                this.plugin.messages().send(p, "flag-notice", Placeholder.unparsed("text", text));
            }
        }
    }

    // ------------------------------------------------------------------ panel bridge

    /**
     * {@code KrajePlugin#flagPanel}: one panel RPC. Returns a Map, or for {@code country.flag.upload} a
     * CompletableFuture of a Map. Rule violations are IllegalArgumentExceptions with a Polish message.
     */
    public Object panel(final UUID actor, final String actorName, final boolean panelAdmin, final String method,
                        final Map<String, String> args) {
        this.requireEnabled();
        final boolean moderator = this.isModerator(actor, panelAdmin);
        final String name = actorName == null || actorName.isBlank() ? "?" : actorName;
        return switch (method) {
            case "country.flag.get" -> this.panelGet(this.country(args), actor, moderator);
            case "country.flag.upload" -> this.upload(this.country(args), actor, name, moderator, args.get("data"));
            case "country.flag.withdraw" -> ok(this.withdraw(this.country(args), actor, name, moderator));
            case "country.flag.reset" -> ok(this.reset(this.country(args), actor, name, moderator));
            case "country.flag.restore" -> {
                final Map<String, Object> res = ok(this.restore(this.country(args), actor, name, moderator, imageId(args)));
                res.put("nextRebuildAt", nullIfZero(this.book.nextRebuildAt()));
                yield res;
            }
            case "admin.flag.queue" -> {
                this.requireModerator(actor, panelAdmin);
                yield this.panelQueue();
            }
            case "admin.flag.approve" -> {
                final Map<String, Object> res = ok(this.approve(this.country(args), imageId(args), actor, name, panelAdmin));
                res.put("nextRebuildAt", nullIfZero(this.book.nextRebuildAt()));
                yield res;
            }
            case "admin.flag.reject" -> ok(this.reject(this.country(args), imageId(args), args.getOrDefault("reason", ""), actor, name, panelAdmin));
            case "admin.flag.rebuild" -> {
                final Map<String, Object> res = ok(this.forceRebuild(actor, panelAdmin));
                res.put("nextRebuildAt", nullIfZero(this.book.nextRebuildAt()));
                yield res;
            }
            default -> throw new IllegalArgumentException("Nieznana metoda chorągwi: " + method);
        };
    }

    private Country country(final Map<String, String> args) {
        final String raw = args.get("id");
        try {
            final Country c = raw == null ? null : this.plugin.manager().get(UUID.fromString(raw));
            if (c == null) {
                throw new IllegalArgumentException("Nie ma takiego kraju.");
            }
            return c;
        } catch (final IllegalArgumentException e) {
            if ("Nie ma takiego kraju.".equals(e.getMessage())) {
                throw e;
            }
            throw new IllegalArgumentException("Nie ma takiego kraju.");
        }
    }

    private static String imageId(final Map<String, String> args) {
        final String id = args.get("imageId");
        if (id == null || !FlagStore.IMAGE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Nieprawidłowy identyfikator flagi.");
        }
        return id;
    }

    private static Map<String, Object> ok(final String message) {
        final Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("message", message);
        return res;
    }

    private static Object nullIfZero(final long v) {
        return v > 0 ? v : null;
    }

    private String png(final String imageId) {
        final byte[] b = imageId == null ? null : this.store.readImage(imageId);
        return b == null ? null : FlagImage.dataUrl(b);
    }

    Map<String, Object> pendingJson(final FlagBook.Pending p) {
        if (p == null) {
            return null;
        }
        final Map<String, Object> o = new LinkedHashMap<>();
        o.put("imageId", p.imageId());
        o.put("png", this.png(p.imageId()));
        o.put("uploadedAt", p.uploadedAt());
        o.put("uploadedBy", p.uploaderName());
        final Map<String, Object> orig = new LinkedHashMap<>();
        orig.put("width", p.width());
        orig.put("height", p.height());
        orig.put("format", p.format());
        orig.put("bytes", p.bytes());
        o.put("original", orig);
        o.put("colors", p.colors());
        return o;
    }

    private Map<String, Object> panelGet(final Country c, final UUID actor, final boolean moderator) {
        final boolean manage = this.canManage(c, actor, moderator);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id().toString());
        out.put("name", c.name());
        out.put("color", c.hexColor());
        out.put("npc", c.npc());
        final Map<String, Object> access = new LinkedHashMap<>();
        access.put("view", true);
        access.put("upload", manage);
        access.put("manage", manage);
        access.put("moderate", moderator);
        out.put("access", access);
        final FlagBook.Country fc = this.book.get(c.id());
        final String cur = fc == null ? null : fc.current();
        final Map<String, Object> current = new LinkedHashMap<>();
        current.put("source", cur == null ? "DEFAULT" : "UPLOAD");
        current.put("imageId", cur);
        current.put("png", FlagImage.dataUrl(this.currentPng(c)));
        current.put("approvedAt", fc == null || fc.approvedAt() == 0 ? null : fc.approvedAt());
        current.put("approvedBy", fc == null ? null : fc.approvedBy());
        final boolean live = cur == null || this.book.live(c.id());
        current.put("status", live ? "LIVE" : "WAITING_PACK");
        current.put("liveSince", cur != null && live ? this.book.lastRebuildAt() : null);
        // option A: the vanilla banner made from the approved flag (null while it is being computed)
        final BannerFit.Design design = this.designNow(c);
        current.put("banner", design == null ? null : FlagImage.dataUrl(BannerFit.previewPng(design, 4)));
        current.put("bannerText", design == null ? null : design.describe());
        current.put("bannerDeltaE", design == null ? null : Math.round(design.meanDeltaE() * 10) / 10.0);
        out.put("current", current);
        if (manage || moderator) {
            out.put("pending", fc == null ? null : this.pendingJson(fc.pending()));
            final FlagBook.Rejection rej = fc == null ? null : fc.lastRejection();
            if (rej == null) {
                out.put("lastRejection", null);
            } else {
                final Map<String, Object> r = new LinkedHashMap<>();
                r.put("imageId", rej.imageId());
                r.put("at", rej.at());
                r.put("by", rej.by());
                r.put("reason", rej.reason());
                out.put("lastRejection", r);
            }
            final List<Map<String, Object>> history = new ArrayList<>();
            final Set<String> withPng = new HashSet<>();
            if (fc != null) {
                for (final FlagBook.Entry e : fc.history()) {
                    final Map<String, Object> h = new LinkedHashMap<>();
                    h.put("imageId", e.imageId());
                    h.put("status", e.status().name());
                    h.put("at", e.at());
                    h.put("by", e.by());
                    h.put("note", e.note());
                    final boolean keepPng = e.imageId() != null && e.status() != FlagBook.Status.REJECTED
                        && e.status() != FlagBook.Status.WITHDRAWN && withPng.add(e.imageId());
                    h.put("png", keepPng ? this.png(e.imageId()) : null);
                    h.put("restorable", this.book.restorable(c.id(), e.imageId(), this.store::hasImage));
                    history.add(h);
                }
            }
            out.put("history", history);
        }
        final Map<String, Object> limits = new LinkedHashMap<>();
        final FlagImage.Limits l = this.settings.limits();
        limits.put("maxBytes", l.maxBytes());
        limits.put("width", FlagImage.WIDTH);
        limits.put("height", FlagImage.HEIGHT);
        limits.put("maxColors", l.maxColors());
        limits.put("formats", List.of("PNG", "JPEG"));
        limits.put("minSourceWidth", l.minWidth());
        limits.put("minSourceHeight", l.minHeight());
        limits.put("maxSourcePixels", l.maxSourcePixels());
        out.put("limits", limits);
        out.put("pack", this.packJson());
        return out;
    }

    Map<String, Object> packJson() {
        final Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("available", this.renderer != BannerRenderer.NONE);
        pack.put("rebuilding", this.rebuilding);
        pack.put("nextRebuildAt", nullIfZero(this.book.nextRebuildAt()));
        pack.put("lastRebuildAt", nullIfZero(this.book.lastRebuildAt()));
        pack.put("minIntervalMinutes", this.settings.minIntervalMinutes());
        pack.put("queued", this.book.waitingForPack().size());
        return pack;
    }

    /** Countries with an upload waiting for moderation, oldest first. */
    List<Country> queue() {
        final List<Country> out = new ArrayList<>();
        for (final Map.Entry<UUID, FlagBook.Country> e : this.book.all().entrySet()) {
            final Country c = this.plugin.manager().get(e.getKey());
            if (c != null && e.getValue().pending() != null) {
                out.add(c);
            }
        }
        out.sort(java.util.Comparator.comparingLong(c -> this.book.get(c.id()).pending().uploadedAt()));
        return out;
    }

    private Map<String, Object> panelQueue() {
        final List<Map<String, Object>> items = new ArrayList<>();
        for (final Country c : this.queue()) {
            final FlagBook.Pending p = this.book.get(c.id()).pending();
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", c.id().toString());
            o.put("name", c.name());
            o.put("color", c.hexColor());
            o.put("npc", c.npc());
            final Map<String, Object> pj = this.pendingJson(p);
            o.putAll(pj);
            o.put("currentPng", FlagImage.dataUrl(this.currentPng(c)));
            items.add(o);
        }
        final List<Map<String, Object>> recent = new ArrayList<>();
        for (final FlagBook.Decision d : this.book.decisions()) {
            if (recent.size() >= 20) {
                break;
            }
            final Country c = this.plugin.manager().get(d.country());
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", d.country().toString());
            o.put("name", c == null ? "(kraj nie istnieje)" : c.name());
            o.put("imageId", d.imageId());
            o.put("status", d.status().name());
            o.put("at", d.at());
            o.put("by", d.by());
            o.put("note", d.note());
            recent.add(o);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("recent", recent);
        out.put("pack", this.packJson());
        return out;
    }

    // ------------------------------------------------------------------ bridge for other plugins

    public boolean show(final String handle, final UUID country, final Location at, final float yaw, final boolean pole, final float scale) {
        if (!this.enabled || this.banners == null || handle == null || handle.isBlank() || handle.length() > 96 || at == null
            || at.getWorld() == null || this.plugin.manager().get(country) == null) {
            return false;
        }
        this.banners.showExternal(handle, country, at, yaw, pole, Math.clamp(scale, 0.25f, 2.0f));
        return true;
    }

    public void hide(final String handle) {
        if (this.banners != null && handle != null) {
            this.banners.hideExternal(handle);
        }
    }

    public void hidePrefix(final String prefix) {
        if (this.banners != null && prefix != null && !prefix.isEmpty()) {
            this.banners.hideExternalPrefix(prefix);
        }
    }

    // ------------------------------------------------------------------ standards (A: vanilla banner, B: item)

    private static String designKey(final String imageId, final int color) {
        return imageId != null ? "img:" + imageId : String.format(java.util.Locale.ROOT, "def:%06x", color & 0xFFFFFF);
    }

    /** The vanilla banner design of the country's approved flag (or of its default flag), computed once per image. */
    public CompletableFuture<BannerFit.Design> design(final Country c) {
        String imageId = this.book.current(c.id());
        final byte[] png = imageId == null ? null : this.store.readImage(imageId);
        if (png == null) {
            imageId = null;
        }
        final String key = designKey(imageId, c.color());
        final BannerFit.Design cached = this.designs.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        final CompletableFuture<BannerFit.Design> running = this.designing.get(key);
        if (running != null) {
            return running;
        }
        final CompletableFuture<BannerFit.Design> out = new CompletableFuture<>();
        this.designing.put(key, out);
        final int color = c.color();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            BannerFit.Design d = null;
            Throwable error = null;
            try {
                d = BannerFit.fit(png != null ? FlagImage.decodeStored(png) : FlagImage.defaultFlag(color & 0xFFFFFF));
                this.designs.put(key, d);
            } catch (final RuntimeException e) {
                error = e;
                this.plugin.getLogger().log(Level.WARNING, "Sztandary: nie udało się obliczyć wzoru dla " + key, e);
            }
            final BannerFit.Design result = d;
            final Throwable failure = error;
            if (this.plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.designing.remove(key);
                    if (failure != null) {
                        out.completeExceptionally(failure);
                    } else {
                        out.complete(result);
                    }
                });
            }
        });
        return out;
    }

    /** The design if it is ready; otherwise starts computing it and returns null. */
    public BannerFit.Design designNow(final Country c) {
        final String cur = this.book.current(c.id());
        final BannerFit.Design d = this.designs.get(designKey(cur != null && this.store.hasImage(cur) ? cur : null, c.color()));
        if (d == null && this.enabled) {
            this.design(c);
        }
        return d;
    }

    private void warmUpDesigns() {
        if (!this.enabled || !this.plugin.isEnabled()) {
            return;
        }
        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        for (final Country c : new ArrayList<>(this.plugin.manager().countries())) {
            final UUID id = c.id();
            chain = chain.handle((x, e) -> null).thenCompose(x -> {
                final Country now = this.plugin.manager().get(id);
                return now == null || !this.plugin.isEnabled() ? CompletableFuture.completedFuture(null) : this.design(now);
            });
        }
    }

    private void refreshStandards() {
        if (this.standards != null) {
            this.standards.refreshAll();
        }
    }

    /**
     * Pack files of the item "Sztandar kraju" (BetterModel reload thread — files only): the default standard and one per
     * banner model file {@code <prefix><12 hex>.bbmodel} on disk, from the image named by its {@code model_identifier}.
     * Those files exist only for approved flags (written by {@link #startRebuild}), so nothing unapproved gets in.
     */
    Map<String, byte[]> standardPackFiles() {
        return standardPackFiles(this.renderer.modelsDir(), this.settings.modelPrefix(), this.store::readImage, this.plugin.getLogger());
    }

    /** Pure part of {@link #standardPackFiles()} (unit-tested): {@code images} reads a stored PNG by image id (or null). */
    static Map<String, byte[]> standardPackFiles(final Path dir, final String prefix, final java.util.function.Function<String, byte[]> images,
                                                 final java.util.logging.Logger logger) {
        final Map<String, byte[]> out = new LinkedHashMap<>(StandardAssets.defaultFiles());
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (var files = Files.list(dir)) {
            for (final Path p : files.sorted().toList()) {
                final String name = p.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".bbmodel")) {
                    continue;
                }
                final String hex = name.substring(prefix.length(), name.length() - ".bbmodel".length());
                if (!hex.matches("[0-9a-f]{12}")) {
                    continue;
                }
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    final JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                    final String ident = o.has("model_identifier") ? o.get("model_identifier").getAsString() : "";
                    final byte[] png = FlagStore.IMAGE_ID.matcher(ident).matches() ? images.apply(ident) : null;
                    if (png != null) {
                        out.putAll(StandardAssets.files(hex, FlagImage.decodeStored(png), false));
                    }
                } catch (final IOException | RuntimeException e) {
                    logger.warning("Sztandary: pomijam " + name + " (" + e.getMessage() + ")");
                }
            }
        } catch (final IOException e) {
            logger.log(Level.WARNING, "Sztandary: nie można odczytać katalogu modeli", e);
        }
        return out;
    }

    void line(final org.bukkit.command.CommandSender to, final String text) {
        this.plugin.messages().send(to, "flag-line", Placeholder.unparsed("text", text));
    }

    void ok(final org.bukkit.command.CommandSender to, final String text) {
        this.plugin.messages().send(to, "flag-ok", Placeholder.unparsed("text", text));
    }

    void error(final org.bukkit.command.CommandSender to, final String text) {
        this.plugin.messages().send(to, "flag-error", Placeholder.unparsed("text", text));
    }

    // ------------------------------------------------------------------ accessors

    public StandardService standards() {
        return this.standards;
    }

    public KrajePlugin plugin() {
        return this.plugin;
    }

    public FlagSettings settings() {
        return this.settings;
    }

    public FlagBook book() {
        return this.book;
    }

    FlagStore store() {
        return this.store;
    }

    BannerRenderer renderer() {
        return this.renderer;
    }

    BannerManager banners() {
        return this.banners;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public boolean rebuilding() {
        return this.rebuilding;
    }

    /** Banners standing now: key ({@code c:<country>}, {@code e:<guest>:<host>}, {@code x:<handle>}) → model and tint. */
    public Map<String, String> shownModels() {
        final Map<String, String> out = new java.util.TreeMap<>();
        if (this.banners != null) {
            this.banners.shownSnapshot().forEach((k, w) -> out.put(k, w.model() + (w.tint() >= 0 ? String.format("#%06X", w.tint()) : "")
                + (w.pole() ? "" : " bez-drzewca") + " @" + w.origin().getBlockX() + "," + w.origin().getBlockY() + "," + w.origin().getBlockZ()));
        }
        return out;
    }

    /** Thread-safe squaremap popup snippet (empty when map icons are off). */
    public String mapIconHtml(final UUID country) {
        return this.icons == null ? "" : this.icons.html(country);
    }
}
