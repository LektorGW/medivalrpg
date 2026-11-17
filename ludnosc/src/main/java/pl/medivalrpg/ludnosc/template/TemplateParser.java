// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Parses a YAML building template (layers of characters + palette), see {@code budynki/chata.yml}. Pure: uses only
 * Bukkit's YAML configuration classes, no server.
 *
 * <pre>
 * id: chata
 * name: '&lt;gold&gt;Chata&lt;/gold&gt;'
 * function: housing          # housing | granary | barracks | smithy | well | tower | other
 * beds: 2                    # declared number of beds (checked against the layers)
 * cost: 1500                 # copper, paid from the country treasury
 * foundation: fill           # fill | pillars | none
 * foundation-block: cobblestone
 * ground-layer: 0            # layer lying at the origin's y (floor); lower layers go underground
 * palette:
 *   'C': cobblestone
 *   'n': dark_oak_stairs[facing=north,half=bottom]
 * layers:                    # bottom → top; rows north → south, columns west → east
 *   - |
 *     CCCC
 *     C..C
 * </pre>
 *
 * <p>{@code .} = air (cleared), {@code _} = keep terrain; both are reserved and cannot be used in the palette.</p>
 */
public final class TemplateParser {

    public static final char AIR_CHAR = '.';
    public static final char KEEP_CHAR = '_';

    private TemplateParser() {
    }

    public static BuildingTemplate parse(final ConfigurationSection yaml, final String fallbackId, final String source) {
        final String id = yaml.getString("id", fallbackId).trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]+")) {
            throw new IllegalArgumentException("Złe id szablonu (dozwolone a-z, 0-9, _ -): " + id);
        }
        final ConfigurationSection paletteSection = yaml.getConfigurationSection("palette");
        if (paletteSection == null) {
            throw new IllegalArgumentException("Brak sekcji palette w szablonie " + id);
        }
        final Map<Character, String> palette = new HashMap<>();
        for (final String key : paletteSection.getKeys(false)) {
            if (key.length() != 1) {
                throw new IllegalArgumentException("Klucz palety musi być jednym znakiem: '" + key + "' (" + id + ")");
            }
            final char c = key.charAt(0);
            if (c == AIR_CHAR || c == KEEP_CHAR || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("Znak '" + c + "' jest zarezerwowany (" + id + ")");
            }
            final String value = paletteSection.getString(key);
            palette.put(c, BlockStates.normalize(value));
        }

        final List<String> rawLayers = yaml.getStringList("layers");
        if (rawLayers.isEmpty()) {
            throw new IllegalArgumentException("Brak warstw (layers) w szablonie " + id);
        }
        final List<List<String>> layers = new ArrayList<>();
        for (final String raw : rawLayers) {
            final List<String> rows = new ArrayList<>();
            for (final String line : raw.split("\\R")) {
                final String row = line.strip();
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            layers.add(rows);
        }
        final int height = layers.size();
        final int depth = layers.get(0).size();
        final int width = depth == 0 ? 0 : layers.get(0).get(0).length();
        final String[] cells = new String[width * height * depth];
        for (int y = 0; y < height; y++) {
            final List<String> rows = layers.get(y);
            if (rows.size() != depth) {
                throw new IllegalArgumentException("Warstwa " + y + " ma " + rows.size() + " wierszy zamiast " + depth + " (" + id + ")");
            }
            for (int z = 0; z < depth; z++) {
                final String row = rows.get(z);
                if (row.length() != width) {
                    throw new IllegalArgumentException("Warstwa " + y + ", wiersz " + z + " ma " + row.length()
                        + " znaków zamiast " + width + " (" + id + ")");
                }
                for (int x = 0; x < width; x++) {
                    final char c = row.charAt(x);
                    final String state;
                    if (c == AIR_CHAR) {
                        state = BlockStates.AIR;
                    } else if (c == KEEP_CHAR) {
                        state = null;
                    } else {
                        state = palette.get(c);
                        if (state == null) {
                            throw new IllegalArgumentException("Znak '" + c + "' nie ma wpisu w palecie (" + id + ", warstwa " + y + ")");
                        }
                    }
                    cells[(y * depth + z) * width + x] = state;
                }
            }
        }

        final BuildingTemplate.Meta meta = new BuildingTemplate.Meta(
            id,
            yaml.getString("name", id),
            yaml.getString("description", ""),
            BuildingFunction.parse(yaml.getString("function", "other")),
            yaml.getInt("beds", 0),
            Math.max(0L, yaml.getLong("cost", 0L)),
            FoundationMode.parse(yaml.getString("foundation", "fill")),
            yaml.getString("foundation-block", "cobblestone"),
            yaml.getInt("ground-layer", 0),
            source,
            yaml.getInt("tier", 0),
            yaml.getString("category", null),
            yaml.getString("zone", null),
            yaml.getInt("max-per-country", 0),
            yaml.getInt("requires.population", 0),
            yaml.getLong("requires.treasury", 0L),
            yaml.getInt("requires.era", 1),
            yaml.getString("requires.discovery", ""),
            variantOf(yaml, id));
        final java.util.Map<Integer, java.util.Map<String, String>> materials = new HashMap<>();
        final ConfigurationSection mats = yaml.getConfigurationSection("materials");
        if (mats != null) {
            for (final String lvl : mats.getKeys(false)) {
                final ConfigurationSection l = mats.getConfigurationSection(lvl);
                if (l == null) {
                    continue;
                }
                final java.util.Map<String, String> m = new HashMap<>();
                for (final String slot : l.getKeys(false)) {
                    m.put(slot, l.getString(slot));
                }
                try {
                    materials.put(Integer.parseInt(lvl.trim()), m);
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException("materials: poziom musi być liczbą 0-4, jest '" + lvl + "' (" + id + ")");
                }
            }
        }
        // with material slots the connections are computed once the slots are resolved (BuildingTemplate#atLevel)
        final boolean slots = java.util.Arrays.stream(cells).anyMatch(MaterialPalette::isSlot);
        return new BuildingTemplate(meta, width, height, depth, slots ? cells : Connections.apply(cells, width, height, depth))
            .withMaterials(materials);
    }

    /** {@code variant-of: <base id>} — a look variant of another building type (same function, limits, config). */
    private static String variantOf(final ConfigurationSection yaml, final String id) {
        final String base = yaml.getString("variant-of", "");
        if (base == null || base.isBlank()) {
            return "";
        }
        final String b = base.trim().toLowerCase(Locale.ROOT);
        if (!b.matches("[a-z0-9_\\-]+")) {
            throw new IllegalArgumentException("Złe variant-of (dozwolone a-z, 0-9, _ -): " + b + " (" + id + ")");
        }
        return b;
    }

    /** Problems that make a template unusable or misleading (declared beds ≠ beds in layers, broken beds/doors). */
    public static List<String> check(final BuildingTemplate template) {
        final List<String> problems = new ArrayList<>(template.validate());
        final int beds = template.bedCount();
        if (beds != template.meta().declaredBeds()) {
            problems.add("zadeklarowano " + template.meta().declaredBeds() + " łóżek, a w warstwach jest " + beds);
        }
        return problems;
    }
}
