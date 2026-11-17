// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Model layer of Armia: settings ({@code modele.yml}), the BetterModel backend (softdepend), installation of the
 * bundled {@code .bbmodel} files and the live-model budget. Started lazily by the first visual user, so the
 * logic classes of Armia need no change. Without BetterModel every call reports "not ready" and the fleet and
 * siege engines keep their vanilla block look.
 */
public final class ArmiaModels {
    private static ArmiaModels instance;

    private final JavaPlugin plugin;
    private VisualSettings settings = VisualSettings.DEFAULT;
    private ModelBackend backend = ModelBackend.NONE;
    private final Set<ModelHandle> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private BukkitTask check;
    private int reloads;
    private boolean announced;

    private ArmiaModels(final JavaPlugin plugin) { this.plugin = plugin; }

    /** The running instance (started on first use while Armia is enabled). */
    public static ArmiaModels get() {
        if (instance == null) {
            instance = new ArmiaModels(JavaPlugin.getProvidingPlugin(ArmiaModels.class));
            instance.start();
        }
        return instance;
    }

    /** Closes every model; called when Armia disables. A later {@link #get()} starts afresh. */
    public static void shutdown() {
        final ArmiaModels current = instance;
        instance = null;
        if (current == null) return;
        if (current.check != null) current.check.cancel();
        for (final ModelHandle handle : java.util.List.copyOf(current.live)) handle.close();
        current.live.clear();
        ShipVisual.resetCount();
        KrajeFlags.hideAll();
    }

    private void start() {
        this.settings = this.loadSettings();
        if (!this.settings.enabled()) {
            this.plugin.getLogger().info("Modele statków i machin wyłączone (modele.yml: wlaczone: false) – wygląd z bloków.");
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            this.plugin.getLogger().info("Brak BetterModel – statki i machiny oblężnicze zostają w wyglądzie z bloków.");
            return;
        }
        try {
            this.backend = new pl.medivalrpg.armia.visual.bm.BetterModelBackend();
        } catch (final LinkageError | RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "BetterModel jest, ale jego API nie działa – wygląd z bloków.", e);
            this.backend = ModelBackend.NONE;
            return;
        }
        boolean written = false;
        if (this.settings.install()) {
            try {
                written = this.install();
            } catch (final IOException | RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Nie udało się zainstalować modeli Armii w plugins/BetterModel/models", e);
            }
        }
        this.plugin.getLogger().info("Modele statków i machin: " + this.backend.name() + (written ? " (zainstalowano nowe pliki)" : ""));
        this.check = Bukkit.getScheduler().runTaskLater(this.plugin, this::ensureLoaded, 60L);
    }

    private VisualSettings loadSettings() {
        final Path file = this.plugin.getDataFolder().toPath().resolve("modele.yml");
        try {
            if (!Files.exists(file)) this.plugin.saveResource("modele.yml", false);
            return VisualSettings.from(YamlConfiguration.loadConfiguration(file.toFile()));
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Błędny modele.yml – używam ustawień domyślnych", e);
            return VisualSettings.DEFAULT;
        }
    }

    /**
     * Copies the bundled models. A file we wrote earlier (hash in {@code modele-zainstalowane.json}) is updated;
     * a file changed by an administrator is left alone.
     */
    private boolean install() throws IOException {
        final Path folder = this.backend.modelFolder();
        if (folder == null) return false;
        Files.createDirectories(folder);
        final Path ledger = this.plugin.getDataFolder().toPath().resolve("modele-zainstalowane.json");
        final Map<String, String> installed = readLedger(ledger);
        boolean written = false;
        for (final String model : ModelCatalog.ALL) {
            final byte[] bundled;
            try (InputStream in = this.plugin.getResource("models/" + model + ".bbmodel")) {
                if (in == null) {
                    this.plugin.getLogger().warning("Brak modelu w jarze: " + model);
                    continue;
                }
                bundled = in.readAllBytes();
            }
            final String hash = sha256(bundled);
            final Path target = folder.resolve(model + ".bbmodel");
            if (Files.exists(target)) {
                final String present = sha256(Files.readAllBytes(target));
                if (present.equals(hash)) { installed.put(model, hash); continue; }
                if (!present.equals(installed.get(model))) {
                    this.plugin.getLogger().warning("Model " + target.getFileName() + " zmieniony ręcznie – nie nadpisuję.");
                    continue;
                }
            }
            final Path tmp = folder.resolve(model + ".bbmodel.tmp");
            Files.write(tmp, bundled);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            installed.put(model, hash);
            written = true;
        }
        final StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (final var e : installed.entrySet())
            json.append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"').append(++i < installed.size() ? ",\n" : "\n");
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, json.append("}\n").toString(), StandardCharsets.UTF_8);
        return written;
    }

    private static Map<String, String> readLedger(final Path ledger) {
        final Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.exists(ledger)) return out;
            final var root = com.google.gson.JsonParser.parseString(Files.readString(ledger, StandardCharsets.UTF_8)).getAsJsonObject();
            for (final var e : root.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        } catch (final IOException | RuntimeException ignored) {
            // a damaged ledger only means we treat present files as possibly hand-edited
        }
        return out;
    }

    static String sha256(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** BetterModel loads models asynchronously; if ours are still missing, ask it once to reload. */
    private void ensureLoaded() {
        this.check = null;
        final long missing = ModelCatalog.ALL.stream().filter(m -> !this.backend.ready(m)).count();
        if (missing == 0) {
            if (!this.announced) this.plugin.getLogger().info("Modele Armii wczytane: " + ModelCatalog.ALL.size() + "/" + ModelCatalog.ALL.size());
            this.announced = true;
            return;
        }
        if (!this.settings.reloadBetterModel() || this.reloads >= 2) {
            this.plugin.getLogger().warning("BetterModel nie wczytał " + missing + " modeli Armii – wygląd z bloków do /bm reload.");
            return;
        }
        this.reloads++;
        final boolean started = this.backend.reload();
        this.plugin.getLogger().info(started ? "Przeładowuję BetterModel, aby wczytał modele Armii…" : "BetterModel się ładuje – sprawdzę ponownie.");
        this.check = Bukkit.getScheduler().runTaskLater(this.plugin, this::ensureLoaded, 200L);
    }

    public VisualSettings settings() { return this.settings; }

    public ModelBackend backend() { return this.backend; }

    public boolean ready(final String model) { return model != null && this.backend.ready(model); }

    /** Creates a live model within the per-kind budget, or null (caller keeps the block look). */
    public ModelHandle create(final String model, final org.bukkit.Location at, final float yaw, final int liveOfKind, final int limit) {
        if (!this.ready(model) || !VisualRules.admit(liveOfKind, limit)) return null;
        final ModelHandle handle;
        try {
            handle = this.backend.create(model, at, yaw);
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się utworzyć modelu " + model, e);
            return null;
        }
        if (handle != null) this.live.add(handle);
        return handle;
    }

    public void release(final ModelHandle handle) {
        if (handle == null) return;
        this.live.remove(handle);
        handle.close();
    }

    public int liveModels() { return this.live.size(); }

    /** Flag picture of a country: a registered {@link FlagArt} provider, then the item model from modele.yml. */
    public Optional<ItemStack> flag(final UUID country) {
        if (country == null) return Optional.empty();
        final FlagArt provider = Bukkit.getServicesManager().load(FlagArt.class);
        if (provider != null) {
            try {
                final Optional<ItemStack> item = provider.flag(country);
                if (item != null && item.isPresent()) return item;
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.FINE, "FlagArt provider failed", e);
            }
        }
        final String pattern = this.settings.flagItemModel();
        if (pattern == null || pattern.isBlank()) return Optional.empty();
        final NamespacedKey key = NamespacedKey.fromString(pattern.replace("{kraj}", country.toString()).toLowerCase(java.util.Locale.ROOT));
        if (key == null) return Optional.empty();
        final ItemStack item = new ItemStack(org.bukkit.Material.PAPER);
        item.editMeta(meta -> meta.setItemModel(key));
        return Optional.of(item);
    }

    public JavaPlugin plugin() { return this.plugin; }
}
