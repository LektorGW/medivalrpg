// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** NPC classification by the PDC keys of Ludnosc, Armia, Skarbiec, Władcy and Życie. */
class NpcCensusTest {

    static final UUID POLANIE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID MAZOWSZE = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    /** Fake PDC: "ns:key" → STRING value ("" for non-string keys). */
    static NpcCensus.Tags tags(final String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new NpcCensus.Tags() {
            @Override
            public Set<String> namespaces() {
                final Set<String> out = new HashSet<>();
                m.keySet().forEach(k -> out.add(k.substring(0, k.indexOf(':'))));
                return out;
            }

            @Override
            public boolean has(final String ns, final String key) {
                return m.containsKey(ns + ":" + key);
            }

            @Override
            public String string(final String ns, final String key) {
                final String v = m.get(ns + ":" + key);
                return v == null || v.isEmpty() ? null : v;
            }
        };
    }

    static NpcCensus.Kind kind(final String... kv) {
        final NpcCensus.Hit h = NpcCensus.classify(tags(kv));
        return h == null ? null : h.kind();
    }

    static NpcCensus.Tags typed(final String type, final String... kv) {
        final var pdc = tags(kv);
        return new NpcCensus.Tags() {
            public String entityType() { return type; }
            public Set<String> namespaces() { return pdc.namespaces(); }
            public boolean has(String ns, String key) { return pdc.has(ns, key); }
            public String string(String ns, String key) { return pdc.string(ns, key); }
        };
    }

    @Test
    void railwayWorkersDriversAndSeatsAreNotDoubleCounted() {
        final var c = new NpcCensus();
        c.add(NpcCensus.classify(typed("MANNEQUIN", "kolej:works_builder", "job")), "world");
        c.add(NpcCensus.classify(typed("MANNEQUIN", "kolej:train-entity", "train-a")), "world");
        for (final String seat : List.of("train-a:0", "train-a:4", "train-a:-1")) {
            c.add(NpcCensus.classify(typed("ARMOR_STAND", "kolej:train-entity", seat)), "world");
        }
        assertEquals(2, c.byPlugin().get("kolej"));
        assertEquals(2, c.total());
        assertEquals(3, c.helpers());
        assertEquals(1, c.byKind().get("railBuilders"));
        assertEquals(1, c.byKind().get("trainDrivers"));
        assertEquals(Map.of("world", 2), c.byWorld());
        assertEquals(0, c.byCountry().size());
        assertNull(NpcCensus.classify(typed("MANNEQUIN", "kolej:train-entity", "")));
        assertNull(NpcCensus.classify(typed("ARMOR_STAND", "kolej:works_builder", "job")));
        assertEquals(NpcCensus.Kind.HELPER, NpcCensus.classify(typed("ARMOR_STAND", "kolej:train-entity", "train-a")).kind());
        assertEquals(NpcCensus.Kind.CITIZEN, NpcCensus.classify(typed("MANNEQUIN", "ludnosc:country", POLANIE.toString(), "kolej:works_builder", "job")).kind());
    }

    @Test
    void freightCountsDriverAndDonkeyButNotBoatAndCargoDisplay() {
        final var c = new NpcCensus();
        for (final String type : List.of("MANNEQUIN", "DONKEY", "MANNEQUIN", "OAK_BOAT", "ITEM_DISPLAY")) {
            c.add(NpcCensus.classify(typed(type, "szlaki:shipment", "shipment-id")), "world");
        }
        assertEquals(3, c.total());
        assertEquals(3, c.byPlugin().get("szlaki"));
        assertEquals(2, c.byKind().get("freightDrivers"));
        assertEquals(1, c.byKind().get("freightAnimals"));
        assertEquals(2, c.helpers());
        assertEquals(Map.of("world", 3), c.byWorld());
        assertNull(NpcCensus.classify(typed("MANNEQUIN", "szlaki:shipment", "")));
        assertNull(NpcCensus.classify(typed("DONKEY", "other:shipment", "job")));
    }

    @Test
    void armiaPartsSoldiersRulersAndHelpers() {
        assertEquals(NpcCensus.Kind.SOLDIER, kind("armia:soldier", "x", "armia:country", POLANIE.toString(), "armia:part", "brain"));
        assertEquals(POLANIE, NpcCensus.classify(tags("armia:part", "brain", "armia:country", POLANIE.toString())).country());
        assertEquals(NpcCensus.Kind.RULER, kind("armia:country", MAZOWSZE.toString(), "armia:part", "ruler"));
        for (final String helper : List.of("body", "ruler_hitbox", "arrow", "siege_model", "siege_shot")) {
            assertEquals(NpcCensus.Kind.HELPER, kind("armia:part", helper, "armia:soldier", "x"), helper);
        }
        assertEquals(NpcCensus.Kind.SOLDIER, kind("armia:soldier", "x"), "legacy brain without part");
        assertNull(kind("armia:fleet_ship", "x"), "cannonball display is not an NPC");
    }

    @Test
    void skarbiecMerchantsWithoutHitbox() {
        assertEquals(NpcCensus.Kind.MERCHANT, kind("skarbiec:npc", "id", "skarbiec:npc_type", "bankier"));
        assertEquals(NpcCensus.Kind.HELPER, kind("skarbiec:npc", "id", "skarbiec:npc_role", "hitbox"));
        assertNull(kind("skarbiec:coin", "1"));
    }

    @Test
    void ludnoscCitizensBuildersHelpers() {
        final NpcCensus.Hit h = NpcCensus.classify(tags("ludnosc:country", POLANIE.toString(), "ludnosc:name", "Wit", "zycie:levy", "s"));
        assertEquals(NpcCensus.Kind.CITIZEN, h.kind(), "a citizen on levy duty is still a citizen");
        assertEquals(POLANIE, h.country());
        assertEquals(NpcCensus.Kind.BUILDER, kind("ludnosc:builder", "site"));
        assertEquals(NpcCensus.Kind.HELPER, kind("ludnosc:site_hologram", "site"));
        assertEquals(NpcCensus.Kind.HELPER, kind("ludnosc:transport_visual", ""));
        assertNull(kind("ludnosc:royal_saved_ai", ""), "temporary state on a non-citizen");
        assertNull(NpcCensus.classify(tags("ludnosc:country", "not-a-uuid")).country());
    }

    @Test
    void wladcyZycieAndForeignNamespaces() {
        assertEquals(NpcCensus.Kind.WLADCY, kind("wladcy:anything", "x"));
        assertEquals(NpcCensus.Kind.ON_DUTY, kind("zycie:levy", "s"));
        assertEquals(NpcCensus.Kind.ON_DUTY, kind("zycie:fire_bucket", ""));
        assertEquals(NpcCensus.Kind.HELPER, kind("zycie:bubble", ""));
        assertNull(kind("kraje:tnt-country", "x"));
        assertNull(kind());
    }

    @Test
    void accumulationByPluginKindCountryAndWorld() {
        final NpcCensus c = new NpcCensus();
        for (int i = 0; i < 3; i++) {
            c.add(NpcCensus.classify(tags("ludnosc:country", POLANIE.toString())), "world");
        }
        c.add(NpcCensus.classify(tags("armia:part", "brain", "armia:country", POLANIE.toString())), "world");
        c.add(NpcCensus.classify(tags("armia:part", "body", "armia:country", POLANIE.toString())), "world");
        c.add(NpcCensus.classify(tags("armia:part", "ruler", "armia:country", MAZOWSZE.toString())), "world_nether");
        c.add(NpcCensus.classify(tags("skarbiec:npc", "x")), "world");
        c.add(NpcCensus.classify(tags("kraje:tnt", "x")), "world");
        assertEquals(Map.of("ludnosc", 3, "armia", 2, "skarbiec", 1, "wladcy", 0, "zycie", 0, "kolej", 0, "szlaki", 0), c.byPlugin());
        assertEquals(NpcCensus.PLUGINS, List.copyOf(c.byPlugin().keySet()), "fixed order");
        assertEquals(6, c.total());
        assertEquals(1, c.helpers());
        assertEquals(3, c.byKind().get("citizens"));
        assertEquals(1, c.byKind().get("rulers"));
        assertEquals(4, c.byCountry().get(POLANIE).total());
        assertEquals(1, c.byCountry().get(POLANIE).soldiers);
        assertEquals(1, c.byCountry().get(MAZOWSZE).rulers);
        assertEquals(Map.of("world", 5, "world_nether", 1), c.byWorld());
    }
}
