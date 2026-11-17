// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SitVariantAndCatalogTest {

    @Test
    void sitVariantIsChosenFromTheSeat() {
        SitVariant.Mode auto = SitVariant.Mode.AUTO;
        assertEquals(SitVariant.FLOOR, SitVariant.choose(SitVariant.Seat.GROUND, false, auto));
        assertEquals(SitVariant.CHAIR, SitVariant.choose(SitVariant.Seat.GROUND, true, auto), "edge of a ledge");
        assertEquals(SitVariant.CHAIR, SitVariant.choose(SitVariant.Seat.STAIRS, false, auto));
        assertEquals(SitVariant.CHAIR, SitVariant.choose(SitVariant.Seat.SLAB, false, auto));
        assertEquals(SitVariant.FLOOR, SitVariant.choose(SitVariant.Seat.STAIRS, true, SitVariant.Mode.FLOOR));
        assertEquals(SitVariant.CHAIR, SitVariant.choose(SitVariant.Seat.GROUND, false, SitVariant.Mode.CHAIR));
        assertEquals("sit_floor", SitVariant.FLOOR.animation());
        assertEquals("sit_chair", SitVariant.CHAIR.animation());
        assertEquals(SitVariant.Mode.CHAIR, SitVariant.Mode.parse(" chair "));
        assertEquals(SitVariant.Mode.AUTO, SitVariant.Mode.parse("nonsense"));
        assertEquals(SitVariant.Mode.AUTO, SitVariant.Mode.parse(null));
    }

    @Test
    void cardinalSnapsToTheNearestSide() {
        assertEquals(0, SitVariant.cardinal(10f));
        assertEquals(1, SitVariant.cardinal(80f));
        assertEquals(2, SitVariant.cardinal(179f));
        assertEquals(2, SitVariant.cardinal(-179f));
        assertEquals(3, SitVariant.cardinal(-100f));
        assertEquals(3, SitVariant.cardinal(260f));
        assertArrayEquals(new int[]{0, 1}, SitVariant.cardinalOffset(0));
        assertArrayEquals(new int[]{-1, 0}, SitVariant.cardinalOffset(1));
        assertArrayEquals(new int[]{0, -1}, SitVariant.cardinalOffset(2));
        assertArrayEquals(new int[]{1, 0}, SitVariant.cardinalOffset(3));
    }

    private static final String JSON = """
            {"format": 1, "models": ["m"],
             "clips": {
               "a": {"model": "m", "length": 1.5, "loop": false, "events": {"impact": [0.5, 0.25]}},
               "b": {"model": "m", "length": 2, "loop": true, "events": {}, "blend": 0.6},
               "bad": {"model": "m", "length": -1}
             },
             "animations": {
               "pair": {"type": "pair", "arrangement": "face", "distance": 0.7,
                        "roles": {"sender": {"clip": "a", "lock": true}, "target": {"clip": "a", "lock": false}}},
               "solo": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "b"}}},
               "Broken": {"type": "solo", "roles": {"sender": {"clip": "missing"}}},
               "nodist": {"type": "pair", "arrangement": "side",
                          "roles": {"sender": {"clip": "a"}, "target": {"clip": "a"}}}
             }}
            """;

    @Test
    void catalogParsesAndSkipsBrokenEntries() {
        List<String> warnings = new ArrayList<>();
        AnimationCatalog c = AnimationCatalog.parse(new StringReader(JSON), warnings::add);
        assertEquals(3, warnings.size(), warnings.toString()); // bad clip, unknown clip, missing distance
        AnimationCatalog.Clip a = c.clip("a");
        assertEquals(30, a.lengthTicks());
        assertEquals(List.of(5, 10), a.impactTicks(), "sorted, in ticks");
        assertFalse(a.loop());
        assertTrue(c.clip("b").loop());
        assertEquals(12, c.clip("b").blendTicks());
        assertEquals(-1, a.blendTicks());
        assertNull(c.clip("bad"));
        AnimationCatalog.Animation pair = c.animation("PAIR");
        assertNotNull(pair);
        assertTrue(pair.pair());
        assertEquals(AnimationCatalog.Arrangement.FACE, pair.arrangement());
        assertTrue(pair.role(AnimationCatalog.Role.SENDER).lock());
        assertFalse(pair.role(AnimationCatalog.Role.TARGET).lock());
        assertTrue(c.animation("solo").role(AnimationCatalog.Role.SENDER).lock(), "lock defaults to true");
        assertNull(c.animation("broken"));
        assertNull(c.animation("nodist"));
    }

    @Test
    void customCatalogOverridesTheBundledOne() {
        AnimationCatalog base = AnimationCatalog.parse(new StringReader(JSON), w -> { });
        AnimationCatalog extra = AnimationCatalog.parse(new StringReader("""
                {"models": ["mine"], "clips": {"b": {"model": "mine", "length": 4, "loop": true},
                                               "c": {"model": "mine", "length": 1}},
                 "animations": {"solo": {"type": "solo", "roles": {"sender": {"clip": "c"}}}}}
                """), w -> { });
        AnimationCatalog merged = AnimationCatalog.merge(base, extra);
        assertEquals(List.of("m", "mine"), merged.models());
        assertEquals(80, merged.clip("b").lengthTicks());
        assertEquals("c", merged.animation("solo").role(AnimationCatalog.Role.SENDER).clip());
        assertNotNull(merged.animation("pair"));
        assertTrue(AnimationCatalog.EMPTY.isEmpty());
    }
}
