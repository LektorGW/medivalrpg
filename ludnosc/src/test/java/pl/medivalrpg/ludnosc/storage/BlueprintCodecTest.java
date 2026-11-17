// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.BlueprintRules;
import pl.medivalrpg.ludnosc.model.Blueprint;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.Placement;

class BlueprintCodecTest {

    private static final UUID COUNTRY = UUID.fromString("abcdef01-2345-4678-9abc-def012345678");

    /** 5×3×4 cottage: stone floor, plank walls, a door in the south wall, a bed, stairs on the roof edge. */
    private static BlueprintRules.Grid cottage() {
        final int w = 5;
        final int h = 3;
        final int d = 4;
        final String[] raw = new String[w * h * d];
        Arrays.fill(raw, "minecraft:air");
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                raw[i(x, 0, z, w, d)] = "minecraft:cobblestone";
                if (x == 0 || x == w - 1 || z == 0 || z == d - 1) {
                    raw[i(x, 1, z, w, d)] = "minecraft:oak_planks";
                    raw[i(x, 2, z, w, d)] = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
                }
            }
        }
        raw[i(2, 1, 3, w, d)] = "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]";
        raw[i(2, 2, 3, w, d)] = "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]";
        raw[i(1, 1, 1, w, d)] = "minecraft:red_bed[facing=north,occupied=false,part=head]";
        raw[i(1, 1, 2, w, d)] = "minecraft:red_bed[facing=north,occupied=false,part=foot]";
        raw[i(3, 1, 1, w, d)] = "minecraft:chest[facing=south,type=single,waterlogged=false]";
        return BlueprintRules.grid(raw, w, h, d, null);
    }

    private static int i(final int x, final int y, final int z, final int w, final int d) {
        return (y * d + z) * w + x;
    }

    private static Blueprint blueprint(final BlueprintRules.Grid g, final String key, final String name) {
        final BuildingTemplate t = BlueprintCodec.template(COUNTRY, key, name, "WorldSteward", g, 777L);
        return new Blueprint(key, COUNTRY, name, "WorldSteward", UUID.randomUUID(), 1_700_000_000_000L, 2, 1, false, t);
    }

    @Test
    void roundTripKeepsEveryCellAndRebuildsMeta() throws InvalidConfigurationException {
        final BlueprintRules.Grid g = cottage();
        assertEquals("south", g.front());
        final Blueprint b = blueprint(g, "abc123", "Chatka_Ździsława");
        assertEquals("kopia_abcdef01_abc123", b.templateId());
        assertEquals(BuildingFunction.HOUSING, b.template().meta().function());
        assertEquals(1, b.template().meta().declaredBeds());
        assertEquals(BlueprintRules.CATEGORY, b.template().meta().category());

        final String yaml = BlueprintCodec.encode(b);
        assertTrue(yaml.contains("palette"));
        assertTrue(yaml.contains("layers"));
        final Blueprint back = BlueprintCodec.decode(yaml, COUNTRY, "abc123", 1234L);
        assertEquals(b.name(), back.name());
        assertEquals(b.author(), back.author());
        assertEquals(b.authorId(), back.authorId());
        assertEquals(b.createdAt(), back.createdAt());
        assertEquals(2, back.forbidden());
        assertEquals(1, back.liquids());
        assertFalse(back.removed());
        final BuildingTemplate t = back.template();
        assertEquals(b.templateId(), t.id());
        assertEquals(1234L, t.meta().cost(), "cost is the current price, not the stored one");
        assertEquals(0, t.meta().groundLayer());
        assertEquals(1, t.meta().era());
        assertTrue(t.meta().discovery().isEmpty());
        assertEquals(BuildingFunction.HOUSING, t.meta().function());
        assertEquals(1, t.bedCount());
        assertEquals(b.template().width(), t.width());
        assertEquals(b.template().height(), t.height());
        assertEquals(b.template().depth(), t.depth());
        for (int n = 0; n < t.volume(); n++) {
            assertEquals(b.template().state(n), t.state(n), "cell " + n);
        }
        assertTrue(t.validate().isEmpty(), t.validate().toString());
        // placed like every template: centred on the origin, the lowest layer at the origin's y
        final Placement p = Placement.centered("world", t.rotated(1), 100, 64, 100);
        assertEquals(64, p.box().minY());
    }

    @Test
    void decodedTemplateRotatesStairsAndDoors() throws InvalidConfigurationException {
        final Blueprint back = BlueprintCodec.decode(BlueprintCodec.encode(blueprint(cottage(), "rot001", "Obrot")), COUNTRY,
            "rot001", 0L);
        final BuildingTemplate t = back.template();
        assertEquals("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]", t.state(2, 1, 3));
        final BuildingTemplate r = t.rotated(1);
        assertEquals("west", r.front());
        // (x, z) → (depth − 1 − z, x)
        final int[] door = BuildingTemplate.rotatePoint(2, 3, t.width(), t.depth(), 1);
        assertEquals("minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]", r.state(door[0], 1, door[1]));
        final int[] stairs = BuildingTemplate.rotatePoint(0, 0, t.width(), t.depth(), 1);
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", r.state(stairs[0], 2, stairs[1]));
        final BuildingTemplate half = t.rotated(2);
        final int[] stairs2 = BuildingTemplate.rotatePoint(0, 0, t.width(), t.depth(), 2);
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]", half.state(stairs2[0], 2, stairs2[1]));
        assertTrue(r.validate().isEmpty(), r.validate().toString());
        assertEquals(1, r.bedCount());
    }

    @Test
    void manyDistinctStatesSurviveYaml() throws InvalidConfigurationException {
        final int w = 20;
        final int d = 20;
        final String[] cells = new String[w * d];
        for (int n = 0; n < cells.length; n++) {
            cells[n] = "minecraft:test_block_" + n;
        }
        final BlueprintRules.Grid g = new BlueprintRules.Grid(cells, w, 1, d, 0, 0, 0, 0, "south");
        final Blueprint b = blueprint(g, "many01", "Mozaika");
        final Blueprint back = BlueprintCodec.decode(BlueprintCodec.encode(b), COUNTRY, "many01", 0L);
        for (int n = 0; n < cells.length; n++) {
            assertEquals(cells[n], back.template().state(n));
        }
        assertEquals(BuildingFunction.OTHER, back.template().meta().function(), "no beds → neutral building");
        final Set<Character> chars = new HashSet<>();
        for (int n = 0; n < 4000; n++) {
            final char c = BlueprintCodec.paletteChar(n);
            assertTrue(c != '.' && c != '_' && !Character.isWhitespace(c) && !Character.isSurrogate(c), "char " + n);
            assertTrue(chars.add(c), "unique " + n);
        }
    }

    @Test
    void movedOrEditedFilesAreRefused() {
        final String yaml = BlueprintCodec.encode(blueprint(cottage(), "abc123", "Dom"));
        assertThrows(InvalidConfigurationException.class, () -> BlueprintCodec.decode(yaml, UUID.randomUUID(), "abc123", 0L));
        assertThrows(InvalidConfigurationException.class, () -> BlueprintCodec.decode(yaml, COUNTRY, "zzz999", 0L));
        assertThrows(InvalidConfigurationException.class, () -> BlueprintCodec.decode(yaml.replace("name: Dom", "name: '<red>x'"),
            COUNTRY, "abc123", 0L));
        assertThrows(InvalidConfigurationException.class, () -> BlueprintCodec.decode("palette: {}", COUNTRY, "abc123", 0L));
    }

    @Test
    void renameKeepsTheTemplateId() throws InvalidConfigurationException {
        final Blueprint b = blueprint(cottage(), "abc123", "Dom");
        final BuildingTemplate.Meta m = b.template().meta();
        final Blueprint renamed = b.withName("Dworek", b.template().withMeta(BlueprintCodec.meta(m.id(), "Dworek", b.author(),
            m.declaredBeds(), m.cost(), m.foundation(), m.foundationBlock(), m.groundLayer(), m.source())));
        final Blueprint back = BlueprintCodec.decode(BlueprintCodec.encode(renamed), COUNTRY, "abc123", 0L);
        assertEquals("Dworek", back.name());
        assertEquals(b.templateId(), back.templateId());
        assertEquals("<gold>Dworek</gold>", back.template().meta().displayName());
        final Blueprint hidden = BlueprintCodec.decode(BlueprintCodec.encode(back.withRemoved(true)), COUNTRY, "abc123", 0L);
        assertTrue(hidden.removed());
    }
}
