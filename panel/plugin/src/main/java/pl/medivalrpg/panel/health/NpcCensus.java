// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Counts the NPCs of the MEDIVALRPG plugins among loaded entities by their PersistentDataContainer tags (namespace =
 * plugin name in lower case). Keys as used by the plugins (2026-10-27):
 * <ul>
 *   <li>{@code armia:part} = {@code brain} → one soldier (the brain carries {@code armia:country}); {@code ruler} → NPC
 *       ruler; other parts ({@code body}, {@code ruler_hitbox}, {@code arrow}, {@code siege_*}) are helpers;</li>
 *   <li>{@code skarbiec:npc} → banker / merchant, except {@code skarbiec:npc_role = hitbox} (helper);</li>
 *   <li>{@code ludnosc:country} → citizen villager (country UUID); {@code ludnosc:builder} → builder;
 *       {@code ludnosc:site_hologram}, {@code ludnosc:transport_visual} → helpers;</li>
 *   <li>{@code wladcy:*} → any tagged entity (Władcy spawns none today; rulers belong to Armia);</li>
 *   <li>{@code zycie:levy}, {@code zycie:fire_bucket} → villager on duty (only when not already a citizen);
 *       {@code zycie:bubble}, {@code zycie:firework} → helpers.</li>
 * </ul>
 * Kolej: works_builder Mannequin = builder; train-entity STRING without colon on a Mannequin = driver;
 * tagged seats are helpers. Szlaki: shipment STRING on Mannequin = driver, DONKEY = transport animal;
 * boats and cargo displays are helpers. One entity is counted once, in the order Armia, Skarbiec, Ludnosc,
 * Władcy, Życie, Kolej, Szlaki. Players are never passed in.
 * Pure (no Bukkit): the caller adapts the entity's PDC to {@link Tags}. Not thread-safe; one instance per scan.
 */
public final class NpcCensus {

    /** Tracked plugins (= PDC namespaces), in display order. */
    public static final List<String> PLUGINS = List.of("ludnosc", "armia", "skarbiec", "wladcy", "zycie", "kolej", "szlaki");
    /** Polish names for the panel. */
    public static final Map<String, String> NAMES = Map.of("ludnosc", "Ludność", "armia", "Armia", "skarbiec", "Skarbiec",
        "wladcy", "Władcy", "zycie", "Życie", "kolej", "Kolej", "szlaki", "Szlaki");

    /** Kinds of tagged entities. */
    public enum Kind {
        CITIZEN("ludnosc", "citizens"),
        BUILDER("ludnosc", "builders"),
        SOLDIER("armia", "soldiers"),
        RULER("armia", "rulers"),
        MERCHANT("skarbiec", "merchants"),
        WLADCY("wladcy", "wladcy"),
        ON_DUTY("zycie", "onDuty"),
        RAIL_BUILDER("kolej", "railBuilders"),
        TRAIN_DRIVER("kolej", "trainDrivers"),
        FREIGHT_DRIVER("szlaki", "freightDrivers"),
        FREIGHT_ANIMAL("szlaki", "freightAnimals"),
        HELPER(null, "helpers");

        public final String plugin;
        public final String json;

        Kind(final String plugin, final String json) {
            this.plugin = plugin;
            this.json = json;
        }
    }

    /** Read-only view of one entity's PDC. */
    public interface Tags {
        /** Bukkit entity type name; classification remains independent of Bukkit. */
        default String entityType() { return ""; }
        /** Namespaces of all keys. */
        Set<String> namespaces();

        boolean has(String namespace, String key);

        /** STRING value, or null when absent or of another type. */
        String string(String namespace, String key);
    }

    /** Result of one entity: kind and owning country (null = none / unknown). */
    public record Hit(Kind kind, UUID country) {
    }

    /** Classifies one entity; null = not an entity of the tracked plugins. */
    public static Hit classify(final Tags t) {
        final Set<String> ns = t.namespaces();
        if (ns == null || ns.isEmpty()) {
            return null;
        }
        if (ns.contains("armia")) {
            final String part = t.string("armia", "part");
            final UUID country = uuid(t.string("armia", "country"));
            if ("brain".equals(part) || (part == null && t.has("armia", "soldier"))) {
                return new Hit(Kind.SOLDIER, country);
            }
            if ("ruler".equals(part)) {
                return new Hit(Kind.RULER, country);
            }
            if (part != null) {
                return new Hit(Kind.HELPER, country);
            }
        }
        if (ns.contains("skarbiec") && t.has("skarbiec", "npc")) {
            return new Hit("hitbox".equals(t.string("skarbiec", "npc_role")) ? Kind.HELPER : Kind.MERCHANT, null);
        }
        if (ns.contains("ludnosc")) {
            if (t.has("ludnosc", "country")) {
                return new Hit(Kind.CITIZEN, uuid(t.string("ludnosc", "country")));
            }
            if (t.has("ludnosc", "builder")) {
                return new Hit(Kind.BUILDER, null);
            }
            if (t.has("ludnosc", "site_hologram") || t.has("ludnosc", "transport_visual")) {
                return new Hit(Kind.HELPER, null);
            }
        }
        if (ns.contains("wladcy")) {
            return new Hit(Kind.WLADCY, null);
        }
        if (ns.contains("zycie")) {
            if (t.has("zycie", "levy") || t.has("zycie", "fire_bucket")) {
                return new Hit(Kind.ON_DUTY, null);
            }
            if (t.has("zycie", "bubble") || t.has("zycie", "firework")) {
                return new Hit(Kind.HELPER, null);
            }
        }
        if (ns.contains("kolej")) {
            final String builder = t.string("kolej", "works_builder");
            if (builder != null && !builder.isBlank() && "MANNEQUIN".equals(t.entityType())) {
                return new Hit(Kind.RAIL_BUILDER, null);
            }
            final String train = t.string("kolej", "train-entity");
            if (train != null && !train.isBlank()) {
                return new Hit("MANNEQUIN".equals(t.entityType()) && !train.contains(":") ? Kind.TRAIN_DRIVER : Kind.HELPER, null);
            }
        }
        if (ns.contains("szlaki")) {
            final String shipment = t.string("szlaki", "shipment");
            if (shipment != null && !shipment.isBlank()) {
                return new Hit(switch (t.entityType()) {
                    case "MANNEQUIN" -> Kind.FREIGHT_DRIVER;
                    case "DONKEY" -> Kind.FREIGHT_ANIMAL;
                    default -> Kind.HELPER; // Boats and cargo displays are vehicles/visuals, not NPCs.
                }, null);
            }
        }
        return null;
    }

    static UUID uuid(final String s) {
        if (s == null || s.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ accumulation

    /** Per-country counts (only kinds that carry a country). */
    public static final class CountryCount {
        public int citizens;
        public int soldiers;
        public int rulers;

        public int total() {
            return this.citizens + this.soldiers + this.rulers;
        }
    }

    private final Map<Kind, Integer> kinds = new LinkedHashMap<>();
    private final Map<UUID, CountryCount> countries = new HashMap<>();
    private final Map<String, Integer> worlds = new TreeMap<>();

    public NpcCensus() {
        for (final Kind k : Kind.values()) {
            this.kinds.put(k, 0);
        }
    }

    /** Adds one entity of {@code world}; null hits are ignored. */
    public void add(final Hit hit, final String world) {
        if (hit == null) {
            return;
        }
        this.kinds.merge(hit.kind(), 1, Integer::sum);
        if (hit.kind() == Kind.HELPER) {
            return;
        }
        if (world != null) {
            this.worlds.merge(world, 1, Integer::sum);
        }
        if (hit.country() != null) {
            final CountryCount c = this.countries.computeIfAbsent(hit.country(), id -> new CountryCount());
            switch (hit.kind()) {
                case CITIZEN -> c.citizens++;
                case SOLDIER -> c.soldiers++;
                case RULER -> c.rulers++;
                default -> {
                }
            }
        }
    }

    /** NPCs per plugin in {@link #PLUGINS} order (zeros included). */
    public Map<String, Integer> byPlugin() {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final String p : PLUGINS) {
            out.put(p, 0);
        }
        for (final Map.Entry<Kind, Integer> e : this.kinds.entrySet()) {
            if (e.getKey().plugin != null) {
                out.merge(e.getKey().plugin, e.getValue(), Integer::sum);
            }
        }
        return out;
    }

    /** Count per kind (JSON names: citizens, builders, soldiers, rulers, merchants, wladcy, onDuty, helpers). */
    public Map<String, Integer> byKind() {
        final Map<String, Integer> out = new LinkedHashMap<>();
        this.kinds.forEach((k, v) -> out.put(k.json, v));
        return out;
    }

    public int total() {
        int n = 0;
        for (final Map.Entry<Kind, Integer> e : this.kinds.entrySet()) {
            if (e.getKey() != Kind.HELPER) {
                n += e.getValue();
            }
        }
        return n;
    }

    public int helpers() {
        return this.kinds.get(Kind.HELPER);
    }

    public Map<UUID, CountryCount> byCountry() {
        return java.util.Collections.unmodifiableMap(this.countries);
    }

    public Map<String, Integer> byWorld() {
        return java.util.Collections.unmodifiableMap(this.worlds);
    }
}
