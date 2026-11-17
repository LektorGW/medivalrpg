// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import pl.medivalrpg.ludnosc.template.BlockKinds;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * Damage detection and repair pricing for buildings built from templates (pure, unit tested).
 *
 * <p>Cosmetic differences are ignored: open/closed doors, gates and trapdoors, occupied beds, powered/lit states,
 * waterlogging, neighbour connections (recomputed anyway), grown plants, cauldron levels, chest pairing, leaf decay
 * flags. Air cells of the template are never "damage" (things players put inside are not repaired away).</p>
 */
public final class RepairRules {

    /**
     * Modernisation cost: building cost × (material-slot blocks / all blocks) × multiplier, at least 1 when there is
     * anything to rebuild.
     */
    public static long upgradeCost(final long buildingCost, final int slotBlocks, final int allBlocks, final double multiplier) {
        if (slotBlocks <= 0 || allBlocks <= 0 || buildingCost <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(buildingCost * Math.min(1.0, (double) slotBlocks / allBlocks) * multiplier));
    }

    /** A building with stored material level {@code stored} (see MaterialPalette#decode) is behind the settlement level. */
    public static boolean outdated(final boolean usesMaterials, final int storedLevel, final int settlementLevel) {
        return usesMaterials && settlementLevel > pl.medivalrpg.ludnosc.template.MaterialPalette.decode(storedLevel);
    }

    /** Properties that do not count as damage. */
    public static final Set<String> IGNORED = Set.of("open", "powered", "occupied", "in_wall", "waterlogged", "north",
        "east", "south", "west", "up", "shape", "lit", "distance", "persistent", "age", "stage", "has_book",
        "has_bottle_0", "has_bottle_1", "has_bottle_2", "triggered", "enabled", "level", "candles", "signal_fire",
        "snowy", "moisture", "hanging", "attached", "disarmed", "honey_level", "bites", "rotation");

    /** Blocks that are interchangeable for repair purposes. */
    private static final List<Set<String>> SAME = List.of(
        Set.of("cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron"),
        Set.of("grass_block", "dirt", "dirt_path", "coarse_dirt", "farmland", "podzol", "rooted_dirt"),
        Set.of("campfire", "soul_campfire"));

    public enum Verdict { OK, REPAIR, RUIN }

    private RepairRules() {
    }

    /** True for template cells that are never checked (air, plants, flowers, liquids, crops). */
    public static boolean ignoredCell(final String expected) {
        if (expected == null || BlockStates.isAir(expected)) {
            return true;
        }
        final BlockStates.Parsed p = BlockStates.parse(expected);
        final String path = p.path();
        return BlockKinds.isLiquid(p) || path.contains("sapling") || path.endsWith("_tulip") || path.equals("poppy")
            || path.equals("dandelion") || path.equals("cornflower") || path.equals("allium") || path.equals("azure_bluet")
            || path.equals("oxeye_daisy") || path.equals("lily_of_the_valley") || path.equals("blue_orchid")
            || path.equals("short_grass") || path.equals("tall_grass") || path.equals("fern") || path.equals("wheat")
            || path.equals("carrots") || path.equals("potatoes") || path.equals("beetroots") || path.endsWith("_leaves")
            || path.equals("sweet_berry_bush") || path.equals("pumpkin_stem") || path.equals("melon_stem");
    }

    private static boolean sameBlock(final String a, final String b) {
        if (a.equals(b)) {
            return true;
        }
        for (final Set<String> group : SAME) {
            if (group.contains(a) && group.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code actual} counts as the expected block (or the cell is ignored). */
    public static boolean equivalent(final String expected, final String actual) {
        if (ignoredCell(expected)) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        final BlockStates.Parsed e = BlockStates.parse(expected);
        final BlockStates.Parsed a = BlockStates.parse(actual);
        if (a.path().endsWith("_trapdoor") && "top".equals(a.property("half")) && !BlockStates.isAir(expected)
            && !e.path().endsWith("_trapdoor")) {
            return true; // the hatch of a cellar/dungeon in the floor counts as the floor
        }
        if (!sameBlock(e.path(), a.path())) {
            return false;
        }
        for (final Map.Entry<String, String> prop : e.properties().entrySet()) {
            if (IGNORED.contains(prop.getKey()) || prop.getKey().equals("type") && e.path().contains("chest")) {
                continue;
            }
            final String av = a.property(prop.getKey());
            if (av != null && !Objects.equals(av, prop.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Key cells: beds, doors and the first block above every bed (the roof/ceiling over a sleeping place). A missing
     * key cell triggers a repair whatever the overall damage.
     */
    public static Set<Integer> keyCells(final BuildingTemplate t) {
        final Set<Integer> keys = new HashSet<>();
        for (int i = 0; i < t.volume(); i++) {
            final String s = t.state(i);
            if (s == null || BlockStates.isAir(s)) {
                continue;
            }
            final BlockStates.Parsed p = BlockStates.parse(s);
            if (BlockKinds.isBed(p) || BlockKinds.isDoor(p)) {
                keys.add(i);
            }
            if (BlockKinds.isBedHead(p)) {
                final int x = t.xOf(i);
                final int z = t.zOf(i);
                for (int y = t.yOf(i) + 1; y < t.height(); y++) {
                    final String above = t.state(x, y, z);
                    if (above != null && !BlockStates.isAir(above) && !ignoredCell(above)) {
                        keys.add(t.index(x, y, z));
                        break;
                    }
                }
            }
        }
        return keys;
    }

    /** Cells that are checked at all. */
    public static int checkedCells(final BuildingTemplate t) {
        int n = 0;
        for (int i = 0; i < t.volume(); i++) {
            if (!ignoredCell(t.state(i))) {
                n++;
            }
        }
        return n;
    }

    public static Verdict verdict(final int missing, final int checked, final boolean keyMissing, final double threshold,
                                  final double ruinThreshold) {
        if (missing <= 0 || checked <= 0) {
            return Verdict.OK;
        }
        final double ratio = missing / (double) checked;
        if (ratio > ruinThreshold) {
            return Verdict.RUIN;
        }
        return ratio > threshold || keyMissing ? Verdict.REPAIR : Verdict.OK;
    }

    /** Copper: building cost × missing / all × multiplier (at least 1 when anything is missing). */
    public static long cost(final long buildingCost, final int missing, final int checked, final double multiplier) {
        if (missing <= 0 || checked <= 0 || buildingCost <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(buildingCost * (missing / (double) checked) * Math.max(0, multiplier)));
    }

    /** Damage in percent (0–100). */
    public static int percent(final int missing, final int checked) {
        return checked <= 0 ? 0 : (int) Math.min(100, Math.round(missing * 100.0 / checked));
    }
}
