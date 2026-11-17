// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.hook.Hooks;
import pl.medivalrpg.ludnosc.hook.WorldEditHook;
import pl.medivalrpg.ludnosc.logic.BlueprintRules;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Blueprint;
import pl.medivalrpg.ludnosc.storage.BlueprintCodec;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * Player blueprints ({@code /ludnosc kopia}): selections, copying a building from the country's land, persistent
 * per-country storage ({@code plugins/Ludnosc/kopie/<country>/<key>.yml}, async tmp + atomic replace + {@code .bak})
 * and registration in {@link pl.medivalrpg.ludnosc.template.TemplateLibrary} so the normal {@code /ludnosc buduj}
 * preview / order / builders path builds them.
 *
 * <p>Settings are read live from {@code blueprints.*} in config.yml with built-in defaults. Main thread, except
 * {@link #ownerOf} / {@link #mayOrder} which are safe from any thread.</p>
 */
public final class BlueprintService implements Listener {

    /** Selection corners of one player (each may be null). */
    public static final class Selection {
        public String world;
        public int[] pos1;
        public int[] pos2;
    }

    /** Outcome of a copy: the blueprint, or a Polish refusal. */
    public record SaveResult(Blueprint blueprint, String error) {

        static SaveResult fail(final String error) {
            return new SaveResult(null, error);
        }
    }

    private final LudnoscPlugin plugin;
    private final Path root;
    private final Map<String, Blueprint> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Ludnosc-kopie");
        t.setDaemon(true);
        return t;
    });

    public BlueprintService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("kopie");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ settings

    public BlueprintRules.Limits limits() {
        final var c = this.plugin.getConfig();
        return new BlueprintRules.Limits(c.getInt("blueprints.max-size", BlueprintRules.Limits.DEFAULTS.maxWidth()),
            c.getInt("blueprints.max-height", BlueprintRules.Limits.DEFAULTS.maxHeight()),
            c.getInt("blueprints.max-blocks", BlueprintRules.Limits.DEFAULTS.maxBlocks()),
            c.getInt("blueprints.max-per-country", BlueprintRules.Limits.DEFAULTS.perCountry()));
    }

    public boolean enabled() {
        return this.plugin.getConfig().getBoolean("blueprints.enabled", true);
    }

    /** Current order price of a template (copper). */
    public long price(final BuildingTemplate t) {
        final var c = this.plugin.getConfig();
        return BlueprintRules.cost(BlueprintRules.weightedBlocks(t), c.getLong("blueprints.cost-per-block", 4L),
            c.getLong("blueprints.min-cost", 500L), c.getLong("blueprints.max-cost", 0L));
    }

    // ------------------------------------------------------------------ API for other services

    /** Owning country of a blueprint template id, or null when the id is not a (known) blueprint. Any thread. */
    public UUID ownerOf(final String templateId) {
        if (!BlueprintRules.isBlueprintId(templateId)) {
            return null;
        }
        final Blueprint b = this.byId.get(templateId.toLowerCase(Locale.ROOT));
        return b == null ? null : b.country();
    }

    /** True when the id is a blueprint id (known or not). */
    public boolean isBlueprint(final String templateId) {
        return BlueprintRules.isBlueprintId(templateId);
    }

    /**
     * May {@code country} place a NEW order of {@code templateId}? True for every non-blueprint id; for a blueprint only
     * its own country, and not after the players deleted it. Repairs of existing buildings do not ask this. Any thread.
     */
    public boolean mayOrder(final UUID country, final String templateId) {
        if (!BlueprintRules.isBlueprintId(templateId)) {
            return true;
        }
        final Blueprint b = this.byId.get(templateId.toLowerCase(Locale.ROOT));
        return b != null && !b.removed() && b.country().equals(country);
    }

    // ------------------------------------------------------------------ registry

    /** Active (not deleted) blueprints of a country, by name. */
    public List<Blueprint> list(final UUID country) {
        final List<Blueprint> out = new ArrayList<>();
        for (final Blueprint b : this.byId.values()) {
            if (b.country().equals(country) && !b.removed()) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparing(b -> BlueprintRules.nameKey(b.name())));
        return out;
    }

    public Optional<Blueprint> find(final UUID country, final String name) {
        final String k = BlueprintRules.nameKey(name);
        return this.list(country).stream().filter(b -> BlueprintRules.nameKey(b.name()).equals(k)).findFirst();
    }

    private List<String> names(final UUID country) {
        return this.list(country).stream().map(Blueprint::name).toList();
    }

    private void register(final Blueprint b) {
        final BuildingTemplate priced = b.template().withMeta(b.template().meta().withBalance(this.price(b.template()), 0, 0, 0, 0));
        final Blueprint withPrice = b.withTemplate(priced);
        this.byId.put(withPrice.templateId(), withPrice);
        this.plugin.templates().registerPlayerTemplate(priced);
    }

    /** Is the template still used by a building, a construction site or a queued order? */
    private boolean inUse(final String templateId) {
        for (final var b : this.plugin.data().allBuildings()) {
            if (templateId.equals(b.type())) {
                return true;
            }
        }
        for (final var s : this.plugin.data().allSites()) {
            if (templateId.equals(s.type)) {
                return true;
            }
        }
        for (final var o : this.plugin.data().allQueued()) {
            if (templateId.equals(o.type)) {
                return true;
            }
        }
        return false;
    }

    /** Loads every stored blueprint (startup, after the template library). Deleted and unused ones are purged. */
    public int loadAll() {
        int n = 0;
        if (!Files.isDirectory(this.root)) {
            return 0;
        }
        try (DirectoryStream<Path> countries = Files.newDirectoryStream(this.root)) {
            for (final Path dir : countries) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                final UUID country;
                try {
                    country = UUID.fromString(dir.getFileName().toString());
                } catch (final IllegalArgumentException e) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.yml")) {
                    for (final Path f : files) {
                        final String fileName = f.getFileName().toString();
                        final String key = fileName.substring(0, fileName.length() - 4);
                        try {
                            final Blueprint b = BlueprintCodec.decode(Files.readString(f, StandardCharsets.UTF_8), country, key, 0L);
                            if (!this.validStates(b.template())) {
                                this.plugin.getLogger().warning("Kopia " + f + " ma nieznane bloki — pomijam.");
                                continue;
                            }
                            if (b.removed() && !this.inUse(b.templateId())) {
                                this.deleteFiles(f);
                                continue;
                            }
                            this.register(b);
                            n++;
                        } catch (final IOException | InvalidConfigurationException | RuntimeException e) {
                            this.plugin.getLogger().warning("Pominięto kopię " + f + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się wczytać kopii budowli", e);
        }
        if (n > 0) {
            this.plugin.getLogger().info("Wczytano kopie budowli graczy: " + n);
        }
        return n;
    }

    /** Re-prices every registered blueprint (after {@code /ludnosc admin reload}). */
    public void reprice() {
        for (final Blueprint b : List.copyOf(this.byId.values())) {
            this.register(b);
        }
    }

    private boolean validStates(final BuildingTemplate t) {
        for (final String s : t.distinctStates()) {
            try {
                Bukkit.createBlockData(s);
            } catch (final IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ selection

    /** Sets corner {@code index} (1 or 2) to the targeted block, or the block at the player's feet. */
    public int[] setCorner(final Player p, final int index) {
        final Block target = p.getTargetBlockExact(8);
        final Location l = target != null ? target.getLocation() : p.getLocation();
        final Selection s = this.selections.computeIfAbsent(p.getUniqueId(), k -> new Selection());
        final String world = l.getWorld().getName();
        if (s.world != null && !s.world.equals(world)) {
            s.pos1 = null;
            s.pos2 = null;
        }
        s.world = world;
        final int[] pos = {l.getBlockX(), l.getBlockY(), l.getBlockZ()};
        if (index == 1) {
            s.pos1 = pos;
        } else {
            s.pos2 = pos;
        }
        return pos;
    }

    public void clearSelection(final Player p) {
        this.selections.remove(p.getUniqueId());
    }

    /** Box from both corners; otherwise the WorldEdit selection (when present); null when nothing is selected. */
    public BlockBox selection(final Player p) {
        final Selection s = this.selections.get(p.getUniqueId());
        if (s != null && s.pos1 != null && s.pos2 != null) {
            return BlockBox.of(s.world, s.pos1[0], s.pos1[1], s.pos1[2], s.pos2[0], s.pos2[1], s.pos2[2]);
        }
        if (Hooks.worldEdit()) {
            try {
                return WorldEditHook.selection(p);
            } catch (final RuntimeException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    /** True when the selection comes from the two corners (false = WorldEdit). */
    public boolean ownCorners(final Player p) {
        final Selection s = this.selections.get(p.getUniqueId());
        return s != null && s.pos1 != null && s.pos2 != null;
    }

    public Selection corners(final Player p) {
        return this.selections.get(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        this.selections.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ copy / rename / delete

    /**
     * Copies the player's selection as blueprint {@code name} of {@code country}. {@code admin} skips the land check.
     * {@code front}: door side (north/east/south/west, Polish names too), null = detected.
     */
    public SaveResult save(final Player p, final UUID country, final String name, final String front, final boolean admin) {
        final BlockBox box = this.selection(p);
        if (box == null) {
            return SaveResult.fail("najpierw zaznacz budowlę: /ludnosc kopia pos1 i pos2 (albo zaznaczenie WorldEdit)");
        }
        return this.saveBox(box, p.getName(), p.getUniqueId(), country, name, front, admin);
    }

    /** Saves the box as a copy of the country (same checks as {@link #save}); also used by the isolated Paper trial. */
    public SaveResult saveBox(final BlockBox box, final String author, final UUID authorId, final UUID country, final String name,
                              final String front, final boolean admin) {
        if (!this.enabled()) {
            return SaveResult.fail("kopiowanie budowli jest wyłączone");
        }
        final String nameProblem = BlueprintRules.nameProblem(name);
        if (nameProblem != null) {
            return SaveResult.fail(nameProblem);
        }
        if (BlueprintRules.nameTaken(name, this.names(country), null)) {
            return SaveResult.fail("kopia o nazwie „" + name + "” już istnieje (zmień nazwę albo usuń starą)");
        }
        final BlueprintRules.Limits limits = this.limits();
        if (this.list(country).size() >= limits.perCountry()) {
            return SaveResult.fail("kraj ma już " + limits.perCountry() + " kopii — usuń którąś");
        }
        final World w = Bukkit.getWorld(box.world());
        if (w == null) {
            return SaveResult.fail("świat zaznaczenia nie istnieje");
        }
        final int sx = box.maxX() - box.minX() + 1;
        final int sy = box.maxY() - box.minY() + 1;
        final int sz = box.maxZ() - box.minZ() + 1;
        final String sizeProblem = BlueprintRules.sizeProblem(sx, sy, sz, limits);
        if (sizeProblem != null) {
            return SaveResult.fail(sizeProblem);
        }
        if (box.minY() < w.getMinHeight() || box.maxY() >= w.getMaxHeight()) {
            return SaveResult.fail("zaznaczenie wychodzi poza wysokość świata");
        }
        for (final int[] c : BlueprintRules.chunks(box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            if (!w.isChunkLoaded(c[0], c[1])) {
                return SaveResult.fail("część zaznaczenia jest w niewczytanym terenie — podejdź bliżej");
            }
        }
        if (!admin && !BlueprintRules.onCountryLand(country, box.minX(), box.minZ(), box.maxX(), box.maxZ(),
            (cx, cz) -> this.plugin.kraje().getCountryAt(box.world(), cx, cz).map(k -> k.id()).orElse(null))) {
            return SaveResult.fail("całe zaznaczenie musi leżeć na ziemi twojego kraju");
        }
        final String[] raw = new String[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    // block data only: never tile entities / inventories / NBT
                    raw[(y * sz + z) * sx + x] = w.getBlockAt(box.minX() + x, box.minY() + y, box.minZ() + z).getBlockData().getAsString();
                }
            }
        }
        final BlueprintRules.Grid grid;
        try {
            grid = BlueprintRules.grid(raw, sx, sy, sz, front);
        } catch (final RuntimeException e) {
            return SaveResult.fail("nie udało się odczytać budowli: " + e.getMessage());
        }
        final String blocksProblem = BlueprintRules.blocksProblem(grid.blocks(), limits);
        if (blocksProblem != null) {
            return SaveResult.fail(blocksProblem);
        }
        final Set<String> usedKeys = new HashSet<>();
        for (final Blueprint b : this.byId.values()) {
            if (b.country().equals(country)) {
                usedKeys.add(b.key());
            }
        }
        final String key = BlueprintRules.newKey(this.random, usedKeys);
        final BuildingTemplate t;
        try {
            t = BlueprintCodec.template(country, key, name, author, grid, 0L);
        } catch (final RuntimeException e) {
            return SaveResult.fail("nie udało się utworzyć szablonu: " + e.getMessage());
        }
        final Blueprint b = new Blueprint(key, country, name, author, authorId, System.currentTimeMillis(),
            grid.forbidden(), grid.liquids(), false, t);
        final String text;
        try {
            text = BlueprintCodec.encode(b);
        } catch (final RuntimeException e) {
            return SaveResult.fail(e.getMessage());
        }
        this.register(b);
        this.write(country, key, text);
        return new SaveResult(this.byId.get(b.templateId()), null);
    }

    /** Renames a blueprint; Polish refusal or null. The template id (and so existing buildings) stays the same. */
    public String rename(final UUID country, final String oldName, final String newName) {
        final Optional<Blueprint> found = this.find(country, oldName);
        if (found.isEmpty()) {
            return "nie ma kopii „" + oldName + "”";
        }
        final Blueprint b = found.get();
        final String problem = BlueprintRules.renameProblem(b.name(), newName, this.names(country));
        if (problem != null) {
            return problem;
        }
        final BuildingTemplate.Meta m = b.template().meta();
        final BuildingTemplate t = b.template().withMeta(BlueprintCodec.meta(m.id(), newName, b.author(), m.declaredBeds(),
            m.cost(), m.foundation(), m.foundationBlock(), m.groundLayer(), m.source()));
        final Blueprint renamed = b.withName(newName, t);
        this.register(renamed);
        this.write(country, b.key(), BlueprintCodec.encode(renamed));
        return null;
    }

    /**
     * Deletes a blueprint. While buildings / sites / queued orders still use it, it is only hidden (kept for repairs);
     * the file is purged on a later start once nothing uses it. Returns the deleted blueprint or empty.
     */
    public Optional<Blueprint> delete(final UUID country, final String name) {
        final Optional<Blueprint> found = this.find(country, name);
        if (found.isEmpty()) {
            return found;
        }
        final Blueprint b = found.get();
        if (this.inUse(b.templateId())) {
            final Blueprint hidden = b.withRemoved(true);
            this.byId.put(hidden.templateId(), hidden);
            this.write(country, b.key(), BlueprintCodec.encode(hidden));
        } else {
            this.byId.remove(b.templateId());
            this.plugin.templates().unregisterPlayerTemplate(b.templateId());
            final Path f = this.file(country, b.key());
            this.io.execute(() -> this.deleteFiles(f));
        }
        return found;
    }

    // ------------------------------------------------------------------ files

    private Path file(final UUID country, final String key) {
        return this.root.resolve(country.toString()).resolve(key + ".yml");
    }

    private void write(final UUID country, final String key, final String text) {
        final Path f = this.file(country, key);
        this.io.execute(() -> {
            try {
                Files.createDirectories(f.getParent());
                final Path tmp = f.resolveSibling(key + ".yml.tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                if (Files.exists(f)) {
                    Files.copy(f, f.resolveSibling(key + ".yml.bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException e) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Nie udało się zapisać kopii " + f, e);
            }
        });
    }

    private void deleteFiles(final Path f) {
        try {
            Files.deleteIfExists(f);
            Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".bak"));
            Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".tmp"));
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się usunąć kopii " + f, e);
        }
    }

    /** Waits for pending writes (plugin disable). */
    public void shutdown() {
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(10, TimeUnit.SECONDS)) {
                this.plugin.getLogger().warning("Nie wszystkie kopie budowli zdążyły się zapisać.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
