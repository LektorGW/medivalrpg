// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Choice and persistence of the NPC appearance UUID (default skins). */
class NpcSkinsTest {

    private static final UUID FIXED = UUID.fromString("12345678-9abc-def0-1234-56789abcdef0");

    @Test
    void npcWithoutNickGetsRandomAppearanceOnce() {
        final AtomicInteger calls = new AtomicInteger();
        final UUID first = NpcSkins.appearance("", null, () -> {
            calls.incrementAndGet();
            return FIXED;
        });
        assertEquals(FIXED, first);
        // Already has one: kept, the generator is not called again (stable look).
        assertSame(first, NpcSkins.appearance("", first, () -> {
            calls.incrementAndGet();
            return UUID.randomUUID();
        }));
        assertEquals(1, calls.get());
        assertEquals(FIXED, NpcSkins.appearance(null, null, () -> FIXED));
        assertEquals(FIXED, NpcSkins.appearance("x", null, () -> FIXED), "too short for a Mojang nick → default skin");
    }

    @Test
    void nickSkinNeedsNoAppearanceButKeepsOld() {
        assertNull(NpcSkins.appearance("Notch", null, UUID::randomUUID));
        assertEquals(FIXED, NpcSkins.appearance("Notch", FIXED, UUID::randomUUID));
    }

    @Test
    void randomAppearancesDiffer() {
        final Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(NpcSkins.appearance("", null, UUID::randomUUID));
        }
        assertEquals(100, seen.size());
    }

    @Test
    void placeholderNameIsShortAndNeverAMojangNick() {
        final String name = NpcSkins.placeholderName(FIXED);
        assertTrue(name.length() <= 16, name);
        assertTrue(name.startsWith("#"));
        assertFalse(NpcSkins.isNick(name));
        assertEquals(name, NpcSkins.placeholderName(FIXED), "deterministic");
        assertNotEquals(name, NpcSkins.placeholderName(UUID.randomUUID()));
        for (int i = 0; i < 200; i++) {
            final String n = NpcSkins.placeholderName(UUID.randomUUID());
            assertTrue(n.length() <= 16 && n.chars().allMatch(c -> c > 32 && c < 127), n);
        }
    }

    @Test
    void nickAndKeywords() {
        assertTrue(NpcSkins.isNick("Notch"));
        assertTrue(NpcSkins.isNick("jeb_"));
        assertFalse(NpcSkins.isNick("ab"));
        assertFalse(NpcSkins.isNick("zły nick"));
        assertFalse(NpcSkins.isNick("abcdefghijklmnopq"));
        assertTrue(NpcSkins.isRandomKeyword("losowy"));
        assertTrue(NpcSkins.isRandomKeyword("LOSOWY"));
        assertFalse(NpcSkins.isRandomKeyword("Notch"));
    }

    @Test
    void appearanceSurvivesSaveAndLoad() {
        final JsonObject o = new JsonObject();
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("type", "kupiec");
        o.addProperty("name", "<green>Kupiec");
        o.addProperty("skin", "");
        o.addProperty("world", "world");
        o.addProperty("x", 1.5);
        o.addProperty("y", 64);
        o.addProperty("z", -3.5);
        o.addProperty("yaw", 90f);
        o.addProperty("pitch", 0f);
        final NpcRecord legacy = NpcRecord.fromJson(o);
        assertNull(legacy.appearance(), "records from older versions have no appearance yet");

        legacy.appearance = NpcSkins.appearance(legacy.skin(), legacy.appearance(), () -> FIXED);
        final NpcRecord reloaded = NpcRecord.fromJson(legacy.toJson());
        assertEquals(FIXED, reloaded.appearance());
        assertEquals(legacy.id(), reloaded.id());
        assertEquals(NpcType.MERCHANT, reloaded.type());
        // A reloaded record keeps its look: no new UUID is chosen.
        assertEquals(FIXED, NpcSkins.appearance(reloaded.skin(), reloaded.appearance(), UUID::randomUUID));
        assertFalse(reloaded.toJson().has("hitbox"), "the hitbox is not persistent");
    }
}
