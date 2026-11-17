// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;
import pl.medivalrpg.ludnosc.template.MaterialPalette;

/** Epochs: palettes per (epoch, level), epoch-locked buildings, roads of each epoch, high-rises. */
class ErasTest {

    @Test
    void stylesCombineEpochAndLevel() {
        assertEquals(0, MaterialPalette.style(1, 0));
        assertEquals(4, MaterialPalette.style(1, 4), "medieval styles keep their old numbers (stored data)");
        assertEquals(6 * 3 + 5, MaterialPalette.style(4, 5));
        assertEquals(4, MaterialPalette.eraOf(MaterialPalette.style(4, 2)));
        assertEquals(2, MaterialPalette.levelOf(MaterialPalette.style(4, 2)));
        assertEquals(MaterialPalette.ERAS * MaterialPalette.LEVELS_PER_ERA, MaterialPalette.DEFAULTS.size());
        assertTrue(MaterialPalette.style(2, 0) > MaterialPalette.style(1, 5), "a new epoch is always newer than the old");
        // and the outdated check follows: an old Stolica building is outdated in the Renaissance
        assertTrue(RepairRules.outdated(true, MaterialPalette.encode(MaterialPalette.style(1, 4)), MaterialPalette.style(2, 1)));
    }

    @Test
    void theSameHouseInEveryEpoch() throws IOException {
        final BuildingTemplate dom = BuiltinTemplates.load("dom");
        final MaterialPalette p = MaterialPalette.defaults();
        final List<String> renesans = dom.atLevel(p, MaterialPalette.style(2, 2)).distinctStates();
        final List<String> przemysl = dom.atLevel(p, MaterialPalette.style(3, 2)).distinctStates();
        final List<String> nowoczesnosc = dom.atLevel(p, MaterialPalette.style(4, 3)).distinctStates();
        assertTrue(renesans.stream().anyMatch(s -> s.startsWith("minecraft:resin_brick_stairs")), "red tile roof");
        assertTrue(renesans.stream().anyMatch(s -> s.startsWith("minecraft:smooth_sandstone")), "light stone");
        assertTrue(przemysl.stream().anyMatch(s -> s.startsWith("minecraft:bricks")), "brick");
        assertTrue(przemysl.stream().anyMatch(s -> s.startsWith("minecraft:deepslate_tile_stairs")), "slate");
        assertTrue(nowoczesnosc.stream().anyMatch(s -> s.startsWith("minecraft:black_stained_glass_pane")), "dark windows");
        assertTrue(nowoczesnosc.stream().anyMatch(s -> s.startsWith("minecraft:smooth_quartz")), "quartz");
        assertTrue(nowoczesnosc.stream().noneMatch(s -> s.startsWith("minecraft:hay_block")));
    }

    @Test
    void epochBuildingsNeedTheirEpochAndDiscovery() throws IOException {
        final List<BuildingTemplate.Meta> catalog = new ArrayList<>();
        for (final BuildingTemplate t : BuiltinTemplates.loadAll()) {
            catalog.add(t.meta());
        }
        final BuildingTemplate.Meta blok = catalog.stream().filter(m -> m.id().equals("blok")).findFirst().orElseThrow();
        assertEquals(4, blok.era());
        assertEquals("beton_zbrojony", blok.discovery());
        final Map<String, Integer> counts = new HashMap<>();
        final BuildingChooser.Input medieval = new BuildingChooser.Input(5, 400, 400, 1000, 5000, 50, 0, 0, false, 10_000_000,
            10_000_000, counts, catalog, 0.2, 0.85);
        assertFalse(BuildingChooser.allowed(medieval, blok), "no blocks of flats in the Middle Ages");
        final BuildingChooser.Input modernNoConcrete = new BuildingChooser.Input(5, 400, 400, 1000, 5000, 50, 0, 0, false, 10_000_000,
            10_000_000, counts, catalog, 0.2, 0.85, 4, d -> false);
        assertFalse(BuildingChooser.allowed(modernNoConcrete, blok), "needs reinforced concrete");
        final BuildingChooser.Input modern = new BuildingChooser.Input(5, 400, 400, 1000, 5000, 50, 0, 0, false, 10_000_000,
            10_000_000, counts, catalog, 0.2, 0.85, 4, d -> true);
        assertTrue(BuildingChooser.allowed(modern, blok));
        // high-rises: tall, on flat plots only, beds on the floors, lit
        final BuildingTemplate tower = BuiltinTemplates.load("wiezowiec");
        assertTrue(tower.height() >= 40 && tower.height() <= 80, "height " + tower.height());
        assertTrue(tower.bedCount() >= 20, "beds " + tower.bedCount());
        assertEquals("highrise", tower.meta().category());
        final BuildingTemplate office = BuiltinTemplates.load("wiezowiec_biurowy");
        assertTrue(office.height() >= 30 && office.height() <= 60, "height " + office.height());
        final BuildingTemplate flats = BuiltinTemplates.load("blok");
        assertTrue(flats.bedCount() >= 15 && flats.bedCount() <= 25);
        assertTrue(BuiltinTemplates.load("kamienica_czynszowa").bedCount() >= 6);
    }

    @Test
    void modernRoadsHaveAsphaltLinesPavementsAndStreetLights() {
        final List<int[]> path = new ArrayList<>();
        for (int x = 0; x < 30; x++) {
            path.add(new int[] {x, 0});
        }
        final RoadDecor.Style modern = RoadDecor.Style.of(4, 3, 3, true);
        final List<RoadDecor.Block> blocks = RoadDecor.blocks(path, (x, z) -> 64, modern, 8, (x, z) -> true);
        assertTrue(blocks.stream().anyMatch(b -> b.state().equals("minecraft:black_concrete")), "asphalt");
        assertTrue(blocks.stream().anyMatch(b -> b.state().equals("minecraft:white_concrete") && b.z() == 0), "centre line");
        assertTrue(blocks.stream().anyMatch(b -> b.state().equals("minecraft:smooth_stone")), "pavement");
        assertTrue(blocks.stream().anyMatch(b -> b.state().equals("minecraft:sea_lantern") && b.y() == 64 + 4), "street light");
        assertTrue(blocks.stream().anyMatch(b -> b.z() != 0 && b.y() == 64 && b.state().equals("minecraft:black_concrete")),
            "a second lane with cars");
        // medieval: dirt path below the paved level, cobbles above
        assertEquals("minecraft:dirt_path", RoadDecor.Style.of(1, 0, 3, false).surface());
        assertEquals("minecraft:cobblestone", RoadDecor.Style.of(1, 3, 3, false).surface());
        assertEquals("minecraft:polished_andesite", RoadDecor.Style.of(3, 3, 3, false).surface());
    }

    @Test
    void roadNetworkIsResurfacedAsChains() {
        final Set<Long> cells = new HashSet<>();
        for (int x = 0; x < 10; x++) {
            cells.add(RoadPlanner.key(x, 0));
        }
        for (int z = 1; z < 6; z++) {
            cells.add(RoadPlanner.key(4, z));
        }
        final List<int[]> chain = RoadDecor.chains(cells);
        assertEquals(cells.size(), chain.size());
        int breaks = 0;
        for (int i = 1; i < chain.size(); i++) {
            if (Math.abs(chain.get(i)[0] - chain.get(i - 1)[0]) + Math.abs(chain.get(i)[1] - chain.get(i - 1)[1]) != 1) {
                breaks++;
            }
        }
        assertTrue(breaks <= 2, "a T junction is at most three pieces: " + breaks);
    }
}
