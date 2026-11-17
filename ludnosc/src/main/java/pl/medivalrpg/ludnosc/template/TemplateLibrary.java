// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.hook.Hooks;
import pl.medivalrpg.ludnosc.hook.WorldEditHook;

/**
 * Runtime library of building templates: the built-in YAML templates from the jar, YAML files from
 * {@code plugins/Ludnosc/budynki/} (same id overrides a built-in) and, with WorldEdit, {@code .schem} files from the
 * same folder. Rotated variants are turned into {@link BlockData} once and cached.
 *
 * <p>Rotation of block states is done by the pure {@link BlockStates#rotate}; when {@code templates.verify-rotation}
 * is on, every distinct state is also rotated by the server ({@link BlockData#rotate}) and any difference is logged
 * and resolved in favour of the server.</p>
 */
public final class TemplateLibrary {

    /** A template rotated and resolved to block data, ready to place. Main thread only (BlockData creation). */
    public record Runtime(BuildingTemplate template, BlockData[] data, int[] order, List<int[]> foundation,
                          BlockData foundationBlock) {

        public int totalSteps() {
            return this.foundation.size() + this.order.length;
        }
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    private volatile Map<String, BuildingTemplate> templates = Map.of();
    private Map<String, LudnoscConfig.BuildingBalance> balance = Map.of();
    private MaterialPalette palette = MaterialPalette.defaults();
    private TemplateTexts texts = TemplateTexts.empty();
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    /** Pure-rotated state → server-rotated state, where the two differ. */
    private final Map<String, String> rotationFixes = new ConcurrentHashMap<>();

    public TemplateLibrary(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public BuildingTemplate get(final String id) {
        if (id == null) {
            return null;
        }
        final BuildingTemplate t = this.templates.get(id.toLowerCase(Locale.ROOT));
        return t != null ? t : this.playerTemplates.get(id.toLowerCase(Locale.ROOT));
    }

    /**
     * Player blueprints ({@code /ludnosc kopia}, see {@code BlueprintService}): found by {@link #get} / {@link #runtime},
     * but NOT listed in {@link #ids()} / {@link #all()}, so the NPC planner and the catalogue never offer them. They
     * survive {@link #load}. Main thread; states must already be valid block data.
     */
    private final Map<String, BuildingTemplate> playerTemplates = new ConcurrentHashMap<>();

    public void registerPlayerTemplate(final BuildingTemplate t) {
        this.playerTemplates.put(t.id(), t);
        this.runtimes.keySet().removeIf(k -> k.startsWith(t.id() + "#"));
    }

    public void unregisterPlayerTemplate(final String id) {
        this.playerTemplates.remove(id);
        this.runtimes.keySet().removeIf(k -> k.startsWith(id + "#"));
    }

    public Collection<String> ids() {
        return this.templates.keySet();
    }

    /** Player-facing texts of the built-in templates (description, short effect); see {@link TemplateTexts}. */
    public TemplateTexts texts() {
        return this.texts;
    }

    /**
     * Building type of a template id: the base id of a look variant ({@code variant-of}), otherwise the id itself
     * (also for unknown ids). Use it for everything configured per type: bonuses, underground features, heritage.
     */
    public String baseType(final String id) {
        if (id == null) {
            return null;
        }
        final BuildingTemplate t = this.get(id);
        return t == null ? id.toLowerCase(Locale.ROOT) : t.meta().baseType();
    }

    /** Ids of every template of a building type (the base first, then its variants in catalogue order). */
    public java.util.List<String> family(final String type) {
        final String base = this.baseType(type);
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final BuildingTemplate t : this.templates.values()) {
            if (t.meta().baseType().equals(base)) {
                if (t.id().equals(base)) {
                    out.addFirst(t.id());
                } else {
                    out.add(t.id());
                }
            }
        }
        return out;
    }

    public Collection<BuildingTemplate> all() {
        return this.templates.values();
    }

    /** (Re)loads every template. Main thread. Returns the number of usable templates. */
    public int load(final LudnoscConfig config) {
        this.balance = config.buildings;
        this.palette = config.materials;
        try {
            this.texts = TemplateTexts.load();
        } catch (final java.io.IOException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Nie udało się wczytać opisów budynków (" + TemplateTexts.RESOURCE + ")", e);
            this.texts = TemplateTexts.empty();
        }
        final Map<String, BuildingTemplate> out = new LinkedHashMap<>();
        final File dir = new File(this.plugin.getDataFolder(), "budynki");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            this.logger.warning("Nie można utworzyć katalogu " + dir);
        }
        for (final String id : BuiltinTemplates.IDS) {
            if (config.exportBuiltins && !new File(dir, id + ".yml").exists()) {
                try {
                    this.plugin.saveResource(BuiltinTemplates.resourcePath(id), false);
                } catch (final RuntimeException e) {
                    this.logger.log(Level.WARNING, "Nie udało się skopiować szablonu " + id, e);
                }
            }
            try {
                this.accept(out, BuiltinTemplates.load(id));
            } catch (final Exception e) {
                this.logger.log(Level.SEVERE, "Wbudowany szablon " + id + " jest uszkodzony", e);
            }
        }
        final File[] files = dir.listFiles();
        if (files != null) {
            for (final File f : files) {
                final String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".schem.yml") || name.endsWith(".schematic.yml")) {
                    continue; // sidecar of a schematic
                }
                if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    try {
                        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                        final String fallback = name.substring(0, name.lastIndexOf('.'));
                        this.accept(out, TemplateParser.parse(yaml, fallback, "plik:" + f.getName()));
                    } catch (final RuntimeException e) {
                        this.logger.warning("Pominięto szablon " + f.getName() + ": " + e.getMessage());
                    }
                } else if (name.endsWith(".schem") || name.endsWith(".schematic")) {
                    if (!Hooks.worldEdit()) {
                        this.logger.warning("Pominięto " + f.getName() + " — pliki .schem wymagają WorldEdit.");
                        continue;
                    }
                    try {
                        this.accept(out, WorldEditHook.loadSchematic(f, config.schematicDefaultCost));
                    } catch (final Exception e) {
                        this.logger.warning("Pominięto schemat " + f.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        // insertion order (built-ins in IDS order, then files): the planner iterates the catalogue deterministically
        this.templates = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(out));
        for (final BuildingTemplate t : out.values()) {
            if (!t.meta().isVariant()) {
                continue;
            }
            final BuildingTemplate base = out.get(t.meta().baseType());
            if (base == null) {
                this.logger.warning("Szablon " + t.id() + ": variant-of " + t.meta().baseType() + " — brak takiego szablonu bazowego.");
            } else if (base.meta().function() != t.meta().function()) {
                this.logger.warning("Szablon " + t.id() + ": funkcja " + t.meta().function() + " różni się od bazowego "
                    + base.id() + " (" + base.meta().function() + ").");
            }
        }
        this.runtimes.clear();
        this.rotationFixes.clear();
        if (config.verifyRotation) {
            this.verifyRotation();
        }
        return out.size();
    }

    private void accept(final Map<String, BuildingTemplate> out, final BuildingTemplate raw) {
        BuildingTemplate t = raw;
        // a look variant without its own buildings.<id> entry inherits the balance of its base type
        LudnoscConfig.BuildingBalance bal = this.balance.get(raw.id());
        if (bal == null && raw.meta().isVariant()) {
            bal = this.balance.get(raw.meta().baseType());
        }
        if (bal != null) {
            final BuildingTemplate.Meta m = raw.meta();
            t = raw.withMeta(m.withBalance(bal.cost() != null ? bal.cost() : m.cost(), bal.tier() != null ? bal.tier() : m.tier(),
                bal.maxPerCountry() != null ? bal.maxPerCountry() : m.maxPerCountry(),
                bal.population() != null ? bal.population() : m.minPopulation(),
                bal.treasury() != null ? bal.treasury() : m.minTreasury()));
        }
        for (final String problem : TemplateParser.check(t)) {
            this.logger.warning("Szablon " + t.id() + " (" + t.meta().source() + "): " + problem);
        }
        for (int level = 0; level < this.palette.levels(); level++) {
            for (final String s : t.atLevel(this.palette, level).distinctStates()) {
                try {
                    Bukkit.createBlockData(BlockStates.colorize(s, BlockStates.DEFAULT_DYE));
                } catch (final IllegalArgumentException e) {
                    this.logger.severe("Szablon " + t.id() + " (" + t.meta().source() + ") ma nieprawidłowy blok '" + s
                        + "' (poziom " + level + ") — pomijam szablon.");
                    return;
                }
            }
        }
        try {
            Bukkit.createBlockData(t.meta().foundationBlock());
        } catch (final IllegalArgumentException e) {
            this.logger.severe("Szablon " + t.id() + ": zły foundation-block — pomijam szablon.");
            return;
        }
        out.put(t.id(), t);
    }

    private static StructureRotation structureRotation(final int q) {
        return switch (Math.floorMod(q, 4)) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /** Cross-checks the pure rotation with the server's {@code BlockData#rotate} for every distinct state. */
    private void verifyRotation() {
        int checked = 0;
        int differences = 0;
        final Map<String, Boolean> seen = new HashMap<>();
        for (final BuildingTemplate t : this.templates.values()) {
            final List<String> states = new java.util.ArrayList<>();
            for (int level = 0; level < this.palette.levels(); level++) {
                states.addAll(t.atLevel(this.palette, level).colored(BlockStates.DEFAULT_DYE).distinctStates());
            }
            for (final String s : states) {
                if (seen.put(s, Boolean.TRUE) != null) {
                    continue;
                }
                final BlockData base = Bukkit.createBlockData(s);
                for (int q = 1; q < 4; q++) {
                    final String mineString = BlockStates.rotate(s, q);
                    final BlockData mine = Bukkit.createBlockData(mineString);
                    final BlockData server = base.clone();
                    server.rotate(structureRotation(q));
                    checked++;
                    if (!mine.equals(server)) {
                        differences++;
                        this.rotationFixes.put(mineString, server.getAsString());
                        if (differences <= 10) {
                            this.logger.warning("Obrót " + (q * 90) + "° stanu " + s + ": " + mine.getAsString()
                                + " ≠ serwer " + server.getAsString() + " — używam wersji serwera.");
                        }
                    }
                }
            }
        }
        if (differences == 0) {
            this.logger.info("Obracanie bloków sprawdzone z BlockData#rotate: " + checked + " przypadków, zgodne.");
        } else {
            this.logger.warning("Obracanie bloków: " + differences + " różnic z serwerem na " + checked + " (poprawione).");
        }
    }

    /** Rotated template resolved to block data (country colour = default dye). Main thread. Null for an unknown id. */
    public Runtime runtime(final String id, final int quarterTurns) {
        return this.runtime(id, quarterTurns, null, DEFAULT_LEVEL);
    }

    public Runtime runtime(final String id, final int quarterTurns, final String dye) {
        return this.runtime(id, quarterTurns, dye, DEFAULT_LEVEL);
    }

    /** Level used when none is known (templates without material slots ignore it). */
    public static final int DEFAULT_LEVEL = 1;

    public MaterialPalette palette() {
        return this.palette;
    }

    /** Rotated template with {@code {kolor}} replaced by {@code dye}. Main thread. Null for an unknown id. */
    public Runtime runtime(final String id, final int quarterTurns, final String dye, final int level) {
        final BuildingTemplate raw = this.get(id);
        if (raw == null) {
            return null;
        }
        final String d = dye == null || dye.isBlank() ? BlockStates.DEFAULT_DYE : dye;
        final int lv = Math.max(0, Math.min(level, this.palette.levels() - 1));
        final BuildingTemplate base = raw.atLevel(this.palette, lv).colored(d);
        final int q = Math.floorMod(quarterTurns, 4);
        return this.runtimes.computeIfAbsent(raw.id() + "#" + q + "#" + (raw.usesColor() ? d : "") + "#"
            + (raw.usesMaterials() ? lv : ""), k -> {
            final BuildingTemplate rotated = base.rotated(q);
            final BlockData[] data = new BlockData[rotated.volume()];
            final Map<String, BlockData> cache = new HashMap<>();
            for (int i = 0; i < data.length; i++) {
                final String s = rotated.state(i);
                if (s != null) {
                    data[i] = cache.computeIfAbsent(s, st -> Bukkit.createBlockData(this.rotationFixes.getOrDefault(st, st)));
                }
            }
            return new Runtime(rotated, data, rotated.buildOrder(), rotated.foundationColumns(),
                Bukkit.createBlockData(base.meta().foundationBlock()));
        });
    }
}
