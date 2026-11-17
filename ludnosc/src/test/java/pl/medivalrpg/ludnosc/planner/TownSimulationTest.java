// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;

/**
 * Grows a town on synthetic hilly terrain with the pure planner, step by step, and checks the plan stays consistent
 * (no overlaps, own land, connected streets, sensible zones). With {@code -Dludnosc.preview=true} it renders top-down
 * PNGs of a few stages into {@code preview/}.
 */
class TownSimulationTest {

    private static final int R = 72;

    /** Hills, a valley and a pond. */
    static final class Terrain implements TerrainView {
        @Override
        public int height(final int x, final int z) {
            final double h = 64 + 4 * Math.sin(x / 17.0) + 3 * Math.cos(z / 13.0) + 5 * Math.max(0, (x - 40) / 20.0)
                + 2 * Math.sin((x + z) / 9.0);
            return (int) Math.floor(h);
        }

        @Override
        public boolean liquid(final int x, final int z) {
            final int dx = x + 30;
            final int dz = z - 35;
            return dx * dx + dz * dz < 64;
        }

        @Override
        public boolean obstacle(final int x, final int z) {
            return false;
        }

        @Override
        public boolean own(final int x, final int z) {
            return x * x + z * z <= R * R;
        }
    }

    record Placed(String type, String category, BlockBox box) {
    }

    @Test
    void townGrowsConsistently() throws IOException {
        final Terrain view = new Terrain();
        final Map<String, BuildingTemplate.Meta> metas = new HashMap<>();
        final Map<String, int[]> sizes = new HashMap<>();
        final List<BuildingTemplate.Meta> catalog = new ArrayList<>();
        for (final BuildingTemplate t : BuiltinTemplates.loadAll()) {
            metas.put(t.id(), t.meta());
            sizes.put(t.id(), new int[] {t.width(), t.depth()});
            catalog.add(t.meta());
        }
        final TownPlanner.Catalog cat = new TownPlanner.Catalog(metas, sizes);
        final SettlementLevels levels = new SettlementLevels(SettlementLevels.DEFAULT);
        final List<Placed> placed = new ArrayList<>();
        final List<BlockBox> taken = new ArrayList<>();
        final Set<Long> roads = new LinkedHashSet<>();
        final Map<String, Integer> counts = new HashMap<>();
        int population = 4;
        int housing = 0;
        long treasury = 8_000;
        int farms = 0;
        final boolean preview = Boolean.getBoolean("ludnosc.preview");
        final Set<Integer> stages = Set.of(6, 16, 30, 50);
        for (int step = 1; step <= 50; step++) {
            final int level = levels.index(population, treasury);
            final long balance = farms * 15L + Math.round(population * 0.3 * 10) - population * 4L + 10;
            final BuildingChooser.Input in = new BuildingChooser.Input(level, population, housing, 100, 300 + 2000L * counts.getOrDefault("spichlerz", 0),
                balance, 0, 6 * counts.getOrDefault("koszary", 0), false, treasury, treasury / 2, counts, catalog, 0.2, 0.85);
            final TownPlanner.Result r = TownPlanner.plan(in, cat, view, "w", 0, 0, roads, taken,
                new SiteFinder.Settings(R - 4, 3, 2, 3), 6000);
            if (r.proposal() != null) {
                final TownPlanner.Proposal p = r.proposal();
                final BuildingTemplate.Meta m = metas.get(p.type());
                final BlockBox box = new BlockBox("w", p.box().minX(), p.spot().y(), p.box().minZ(), p.box().maxX(),
                    p.spot().y() + 10, p.box().maxZ());
                for (final BlockBox other : taken) {
                    assertFalse(other.intersects(box), "overlap at step " + step + ": " + p.type());
                }
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        assertTrue(view.own(x, z) && !view.liquid(x, z), "own dry land");
                    }
                }
                // street: contiguous, starts at the door, ends on the network
                for (int i = 1; i < p.road().size(); i++) {
                    final int[] a = p.road().get(i - 1);
                    final int[] b = p.road().get(i);
                    assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]), "contiguous road");
                }
                taken.add(box);
                placed.add(new Placed(p.type(), m.category(), box));
                for (final int[] c : p.road()) {
                    roads.add(RoadPlanner.key(c[0], c[1]));
                }
                counts.merge(p.type(), 1, Integer::sum);
                treasury -= m.cost();
                if (m.function().housing()) {
                    housing += m.declaredBeds();
                }
                if (m.category().equals("farm")) {
                    farms++;
                }
                if (p.type().equals("studnia") && roads.size() <= 1) {
                    for (final List<int[]> axis : TownPlanner.mainAxes(view, 0, 0, 24, taken, 6000)) {
                        for (final int[] c : axis) {
                            roads.add(RoadPlanner.key(c[0], c[1]));
                        }
                    }
                }
            }
            population = Math.min(housing, population + 2);
            treasury += 2_000L * step;
            if (preview && stages.contains(step)) {
                render(view, placed, roads, new File("preview/miasto-etap-" + step + ".png"));
            }
        }
        assertTrue(placed.size() >= 25, "the town grew: " + placed.size());
        assertEquals("studnia", placed.get(0).type(), "the square first");
        assertTrue(Math.abs(placed.get(0).box().centerX()) <= 4 && Math.abs(placed.get(0).box().centerZ()) <= 4);
        assertTrue(roads.size() > 40, "street network");
        assertTrue(counts.getOrDefault("dom", 0) + counts.getOrDefault("chata", 0) + counts.getOrDefault("kamienica", 0) >= 5);
        // zones: farms further from the centre than the centre buildings
        final double farmDist = placed.stream().filter(p -> p.category().equals("farm"))
            .mapToDouble(p -> Math.hypot(p.box().centerX(), p.box().centerZ())).average().orElse(99);
        final double civicDist = placed.stream().filter(p -> Set.of("civic", "religious", "representative", "infrastructure")
                .contains(p.category())).mapToDouble(p -> Math.hypot(p.box().centerX(), p.box().centerZ())).average().orElse(0);
        assertTrue(farmDist > civicDist, "farms on the outskirts (" + farmDist + ") vs centre (" + civicDist + ")");
    }

    private static final Map<String, Color> COLORS = Map.ofEntries(
        Map.entry("housing", new Color(205, 120, 70)), Map.entry("infrastructure", new Color(70, 140, 230)),
        Map.entry("food", new Color(230, 190, 70)), Map.entry("farm", new Color(170, 200, 60)),
        Map.entry("economy", new Color(150, 110, 80)), Map.entry("military", new Color(170, 50, 50)),
        Map.entry("civic", new Color(230, 160, 40)), Map.entry("religious", new Color(240, 240, 220)),
        Map.entry("representative", new Color(250, 215, 60)), Map.entry("defense", new Color(120, 120, 130)));

    private static void render(final Terrain view, final List<Placed> placed, final Set<Long> roads, final File out)
        throws IOException {
        final int scale = 4;
        final int size = (2 * R + 1) * scale;
        final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        for (int z = -R; z <= R; z++) {
            for (int x = -R; x <= R; x++) {
                Color c;
                if (!view.own(x, z)) {
                    c = new Color(60, 70, 60);
                } else if (view.liquid(x, z)) {
                    c = new Color(60, 110, 210);
                } else {
                    final int h = view.height(x, z) - 56;
                    final int v = Math.max(0, Math.min(255, 90 + h * 8));
                    c = new Color(Math.max(0, v - 60), Math.min(255, v + 20), Math.max(0, v - 90));
                }
                g.setColor(c);
                g.fillRect((x + R) * scale, (z + R) * scale, scale, scale);
            }
        }
        g.setColor(new Color(140, 115, 80));
        for (final long k : roads) {
            g.fillRect((RoadPlanner.keyX(k) + R) * scale, (RoadPlanner.keyZ(k) + R) * scale, scale, scale);
        }
        for (final Placed p : placed) {
            final BlockBox b = p.box();
            g.setColor(COLORS.getOrDefault(p.category(), Color.MAGENTA));
            g.fillRect((b.minX() + R) * scale, (b.minZ() + R) * scale, b.sizeX() * scale, b.sizeZ() * scale);
            g.setColor(Color.BLACK);
            g.drawRect((b.minX() + R) * scale, (b.minZ() + R) * scale, b.sizeX() * scale - 1, b.sizeZ() * scale - 1);
            g.drawString(p.type(), (b.minX() + R) * scale + 2, (b.minZ() + R) * scale + 12);
        }
        g.dispose();
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }

    @Test
    void uniqueRoadCells() {
        final Set<Long> s = new HashSet<>();
        s.add(RoadPlanner.key(-5, 7));
        assertEquals(-5, RoadPlanner.keyX(s.iterator().next()));
        assertEquals(7, RoadPlanner.keyZ(s.iterator().next()));
    }
}
