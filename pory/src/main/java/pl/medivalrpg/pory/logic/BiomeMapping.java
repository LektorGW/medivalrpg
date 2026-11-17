// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Network biome id → seasonal variant id, built once from the server's biome registry (pure, unit tested).
 *
 * <p>The datapack registers {@code pory:<season>/<path>} for every vanilla {@code minecraft:<path>} biome that changes
 * in that season ({@code pory:<season>/<namespace>/<path>} for other namespaces). Biomes without a variant keep their
 * id. Variants themselves map to the variant of the requested season (or their base), so rewriting is idempotent and a
 * world that somehow contains variant biomes still shows the right season.</p>
 */
public final class BiomeMapping {

    public static final String NAMESPACE = "pory";

    /** One registry entry. */
    public record Entry(int id, String key, boolean precipitation, float temperature) {
    }

    private final Map<VisualSeason, int[]> tables = new EnumMap<>(VisualSeason.class);
    private final Map<VisualSeason, Integer> variantCounts = new EnumMap<>(VisualSeason.class);
    private final boolean[] snowEligible;
    private final int size;
    private final int baseCount;

    private BiomeMapping(final int size, final int baseCount, final boolean[] snowEligible) {
        this.size = size;
        this.baseCount = baseCount;
        this.snowEligible = snowEligible;
    }

    /** {@code minecraft:plains} → {@code pory:jesien/plains}; other namespaces keep theirs as a folder. */
    public static String variantKey(final VisualSeason season, final String baseKey) {
        final int colon = baseKey.indexOf(':');
        final String ns = colon < 0 ? "minecraft" : baseKey.substring(0, colon);
        final String path = colon < 0 ? baseKey : baseKey.substring(colon + 1);
        return NAMESPACE + ":" + season.id() + "/" + ("minecraft".equals(ns) ? path : ns + "/" + path);
    }

    /** Base key of a variant ({@code pory:zima/plains} → {@code minecraft:plains}); null when not a variant. */
    public static String baseKeyOf(final String key) {
        if (key == null || !key.startsWith(NAMESPACE + ":")) {
            return null;
        }
        final String rest = key.substring(NAMESPACE.length() + 1);
        final int slash = rest.indexOf('/');
        if (slash <= 0 || VisualSeason.parse(rest.substring(0, slash)) == null) {
            return null;
        }
        final String path = rest.substring(slash + 1);
        final int second = path.indexOf('/');
        if (second > 0 && !path.isEmpty()) {
            // pory:zima/ns/path for non-vanilla namespaces; vanilla paths never contain '/' in biome ids
            return path.substring(0, second) + ":" + path.substring(second + 1);
        }
        return "minecraft:" + path;
    }

    /** Season of a variant key, null when the key is not a variant. */
    public static VisualSeason seasonOf(final String key) {
        if (baseKeyOf(key) == null) {
            return null;
        }
        final String rest = key.substring(NAMESPACE.length() + 1);
        return VisualSeason.parse(rest.substring(0, rest.indexOf('/')));
    }

    /**
     * @param entries        all biomes of the registry with their network ids
     * @param snowBelow      a biome counts as snowy when it has precipitation and a base temperature below this
     *                       (vanilla: 0.15)
     */
    public static BiomeMapping build(final Collection<Entry> entries, final float snowBelow) {
        int size = 0;
        final Map<String, Entry> byKey = new HashMap<>();
        for (final Entry e : entries) {
            size = Math.max(size, e.id() + 1);
            byKey.put(e.key(), e);
        }
        int bases = 0;
        for (final Entry e : entries) {
            if (baseKeyOf(e.key()) == null) {
                bases++;
            }
        }
        final boolean[] eligible = new boolean[size];
        final BiomeMapping mapping = new BiomeMapping(size, bases, eligible);
        for (final VisualSeason season : VisualSeason.values()) {
            final int[] table = new int[size];
            boolean identity = true;
            int variants = 0;
            for (int i = 0; i < size; i++) {
                table[i] = i;
            }
            for (final Entry e : entries) {
                final String base = baseKeyOf(e.key());
                final String baseKey = base == null ? e.key() : base;
                final Entry variant = byKey.get(variantKey(season, baseKey));
                final Entry baseEntry = byKey.get(baseKey);
                final int target = variant != null ? variant.id() : baseEntry != null ? baseEntry.id() : e.id();
                if (base == null && variant != null) {
                    variants++;
                }
                table[e.id()] = target;
                // only base biomes count: the server world never contains variants, so a season that changes no
                // base biome (summer) needs no rewriting at all
                identity &= base != null || target == e.id();
            }
            mapping.variantCounts.put(season, variants);
            if (!identity) {
                mapping.tables.put(season, table);
            }
        }
        // snow cover: the winter look of the column decides (variant if present, else the biome itself)
        final int[] winter = mapping.tables.get(VisualSeason.ZIMA);
        final Map<Integer, Entry> byId = new HashMap<>();
        for (final Entry e : entries) {
            byId.put(e.id(), e);
        }
        for (final Entry e : entries) {
            final Entry look = byId.get(winter == null ? e.id() : winter[e.id()]);
            final Entry w = look == null ? e : look;
            eligible[e.id()] = w.precipitation() && w.temperature() < snowBelow;
        }
        return mapping;
    }

    /** Id table for the season, or null when the season changes nothing (e.g. summer = vanilla). */
    public int[] table(final VisualSeason season) {
        return season == null ? null : this.tables.get(season);
    }

    /** Whether snow may lie on a column of this (server-side) biome in winter. */
    public boolean snowEligible(final int id) {
        return id >= 0 && id < this.snowEligible.length && this.snowEligible[id];
    }

    public int variantCount(final VisualSeason season) {
        return this.variantCounts.getOrDefault(season, 0);
    }

    public int size() {
        return this.size;
    }

    public int baseCount() {
        return this.baseCount;
    }
}
