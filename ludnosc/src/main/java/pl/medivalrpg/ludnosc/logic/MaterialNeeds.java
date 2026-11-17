// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Which raw material a placed block really costs (pure, unit tested). A block costs units of one or more material
 * groups ({@link #costs}); each group is made by the country's own workplaces:
 * <ul>
 *   <li>{@link #WOOD} — logs from the lumberjacks (1 log = 4 units = 4 planks),</li>
 *   <li>{@link #STONE} — cobblestone / stone from quarries and mines (1 item = 1 unit),</li>
 *   <li>{@link #GLASS} — glass from the glassworks (sand dug on the country's shores; 1 glass = 8 units, a pane 3),</li>
 *   <li>{@link #SAND} — sand for sandstone (dug by the glassworks; sandstone = 4 sand),</li>
 *   <li>{@link #BRICK} and {@link #TERRACOTTA} — fired in the brickworks from clay of the rivers (1 brick = 1 unit),</li>
 *   <li>{@link #WOOL} — shorn in the sheepfold (1 wool = 3 units, a carpet 2; beds also need wood),</li>
 *   <li>{@link #HAY} — hay bales pressed in the sheepfold's barn from field wheat (1 bale = 9 wheat units),</li>
 *   <li>{@link #IRON} — ingots smelted in the smithy from the mines' raw iron (1 ingot = 8 units, iron bars 3),</li>
 *   <li>{@link #TORCH} — torches made in the smithy from coal / charcoal and wood (lanterns need one too).</li>
 * </ul>
 * Everything else (flowers, bells, quartz, copper...) is covered by the coins of the order: never stalled for. A group
 * the country cannot make yet is bought with the order's coins as well ({@link #mustWait}).
 */
public final class MaterialNeeds {

    public static final String WOOD = "drewno";
    public static final String STONE = "kamien";
    public static final String GLASS = "szklo";
    public static final String SAND = "piasek";
    public static final String BRICK = "cegla";
    public static final String TERRACOTTA = "terakota";
    public static final String WOOL = "welna";
    public static final String HAY = "siano";
    public static final String IRON = "zelazo";
    public static final String TORCH = "pochodnie";

    /** All groups, in display order. */
    public static final List<String> GROUPS = List.of(WOOD, STONE, GLASS, SAND, BRICK, TERRACOTTA, WOOL, HAY, IRON, TORCH);

    private static final String[] WOODS = {"OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY",
        "PALE_OAK", "CRIMSON", "WARPED"};
    private static final String[] WOOD_SHAPES = {"_PLANKS", "_STAIRS", "_SLAB", "_FENCE", "_FENCE_GATE", "_DOOR", "_TRAPDOOR",
        "_PRESSURE_PLATE", "_BUTTON", "_SIGN", "_WALL_SIGN", "_HANGING_SIGN", "_WALL_HANGING_SIGN"};
    private static final Set<String> WOOD_ITEMS = Set.of("LADDER", "CHEST", "BARREL", "CRAFTING_TABLE", "BOOKSHELF",
        "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "SMITHING_TABLE", "LOOM", "COMPOSTER", "LECTERN");
    private static final String[] STONE_PREFIXES = {"COBBLESTONE", "MOSSY_COBBLESTONE", "STONE", "SMOOTH_STONE", "STONE_BRICK",
        "MOSSY_STONE_BRICK", "CRACKED_STONE_BRICKS", "CHISELED_STONE_BRICKS", "ANDESITE", "POLISHED_ANDESITE", "DIORITE",
        "POLISHED_DIORITE", "GRANITE", "POLISHED_GRANITE", "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICK",
        "DEEPSLATE_TILE", "CRACKED_DEEPSLATE", "CHISELED_DEEPSLATE", "DEEPSLATE", "TUFF", "POLISHED_TUFF", "TUFF_BRICK",
        "CHISELED_TUFF"};
    private static final Set<String> STONE_SHAPES = Set.of("", "S", "_STAIRS", "_SLAB", "_WALL", "_BRICKS", "_BRICK_STAIRS",
        "_BRICK_SLAB", "_BRICK_WALL", "_TILES", "_TILE_STAIRS", "_TILE_SLAB", "_TILE_WALL");
    private static final Set<String> STONE_EXCLUDED = Set.of("STONECUTTER", "STONE_BUTTON", "STONE_PRESSURE_PLATE",
        "STONE_SWORD", "STONE_AXE", "STONE_PICKAXE", "STONE_SHOVEL", "STONE_HOE", "REINFORCED_DEEPSLATE");
    private static final Set<String> NOT_CARPET = Set.of("MOSS_CARPET", "PALE_MOSS_CARPET");

    private MaterialNeeds() {
    }

    private static String norm(final String material) {
        String m = material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
        if (m.startsWith("MINECRAFT:")) {
            m = m.substring("MINECRAFT:".length());
        }
        final int bracket = m.indexOf('[');
        return bracket >= 0 ? m.substring(0, bracket) : m;
    }

    private static boolean log(final String m) {
        return m.endsWith("_LOG") || m.endsWith("_WOOD") || m.endsWith("_STEM") || m.endsWith("_HYPHAE");
    }

    /** Wood or stone (the historical groups), or null. */
    private static String basicGroup(final String m) {
        if (log(m) && !m.startsWith("DEEPSLATE")) {
            return WOOD;
        }
        if (WOOD_ITEMS.contains(m)) {
            return WOOD;
        }
        for (final String w : WOODS) {
            if (m.startsWith(w + "_")) {
                final String rest = m.substring(w.length());
                for (final String shape : WOOD_SHAPES) {
                    if (rest.equals(shape)) {
                        return WOOD;
                    }
                }
            }
        }
        if (STONE_EXCLUDED.contains(m)) {
            return null;
        }
        for (final String p : STONE_PREFIXES) {
            if (m.startsWith(p) && STONE_SHAPES.contains(m.substring(p.length()))) {
                return STONE;
            }
        }
        return null;
    }

    private static int basicUnits(final String group, final String m) {
        if (WOOD.equals(group) && log(m)) {
            return 4;
        }
        if (WOOD.equals(group) && (m.equals("CHEST") || m.equals("BARREL") || m.equals("BOOKSHELF") || m.endsWith("_DOOR"))) {
            return 2;
        }
        return 1;
    }

    private static boolean glassBlock(final String m) {
        return m.equals("GLASS") || m.endsWith("_STAINED_GLASS");
    }

    private static boolean glassPane(final String m) {
        return m.equals("GLASS_PANE") || m.endsWith("_STAINED_GLASS_PANE");
    }

    private static boolean wool(final String m) {
        return m.endsWith("_WOOL");
    }

    private static boolean carpet(final String m) {
        return m.endsWith("_CARPET") && !NOT_CARPET.contains(m);
    }

    private static boolean terracotta(final String m) {
        return m.equals("TERRACOTTA") || m.endsWith("_TERRACOTTA");
    }

    /**
     * Raw material a placed block / item costs, by group (insertion order = main group first); empty when the block is
     * free or paid by the order's coins. E.g. {@code OAK_PLANKS} → drewno 1, {@code GLASS_PANE} → szkło 3,
     * {@code RED_BED} → wełna 9 + drewno 3, {@code LANTERN} → żelazo 8 + pochodnie 1.
     */
    public static Map<String, Integer> costs(final String material) {
        final String m = norm(material);
        final Map<String, Integer> out = new LinkedHashMap<>();
        if (m.isEmpty()) {
            return out;
        }
        final String basic = basicGroup(m);
        if (basic != null) {
            out.put(basic, basicUnits(basic, m));
            return out;
        }
        if (glassBlock(m)) {
            out.put(GLASS, 8);
        } else if (glassPane(m)) {
            out.put(GLASS, 3);
        } else if (m.contains("SANDSTONE")) {
            out.put(SAND, m.endsWith("_STAIRS") ? 6 : m.endsWith("_SLAB") ? 2 : 4);
        } else if (m.equals("SAND") || m.equals("RED_SAND")) {
            out.put(SAND, 1);
        } else if (m.equals("BRICKS") || m.equals("BRICK_WALL")) {
            out.put(BRICK, 4);
        } else if (m.equals("BRICK_STAIRS")) {
            out.put(BRICK, 6);
        } else if (m.equals("BRICK_SLAB")) {
            out.put(BRICK, 2);
        } else if (m.equals("FLOWER_POT")) {
            out.put(BRICK, 3);
        } else if (m.equals("DECORATED_POT")) {
            out.put(BRICK, 4);
        } else if (terracotta(m)) {
            out.put(TERRACOTTA, 1);
        } else if (wool(m)) {
            out.put(WOOL, 3);
        } else if (carpet(m)) {
            out.put(WOOL, 2);
        } else if (m.endsWith("_BED")) {
            out.put(WOOL, 9);   // three wool
            out.put(WOOD, 3);   // three planks
        } else if (m.endsWith("_BANNER")) {
            out.put(WOOL, 18);  // six wool (the stick is a cut-off)
        } else if (m.equals("HAY_BLOCK")) {
            out.put(HAY, 9);
        } else if (m.equals("IRON_BARS")) {
            out.put(IRON, 3);   // 6 ingots → 16 bars
        } else if (m.equals("IRON_CHAIN") || m.equals("CHAIN")) {
            out.put(IRON, 10);
        } else if (m.equals("IRON_DOOR")) {
            out.put(IRON, 16);
        } else if (m.equals("IRON_TRAPDOOR")) {
            out.put(IRON, 32);
        } else if (m.equals("CAULDRON")) {
            out.put(IRON, 56);
        } else if (m.equals("LANTERN")) {
            out.put(IRON, 8);   // eight nuggets
            out.put(TORCH, 1);
        } else if (m.equals("SOUL_LANTERN")) {
            out.put(IRON, 8);
        } else if (m.equals("TORCH") || m.equals("WALL_TORCH")) {
            out.put(TORCH, 1);
        }
        return out;
    }

    /** Main raw material group of a block / item name ({@code OAK_STAIRS}, {@code minecraft:cobblestone[...]}), or null. */
    public static String group(final String material) {
        final Map<String, Integer> c = costs(material);
        return c.isEmpty() ? null : c.keySet().iterator().next();
    }

    /** Units of its main group one placed block costs (a whole log = 4 wood, a glass pane = 3 glass...); 0 when free. */
    public static int units(final String material) {
        final Map<String, Integer> c = costs(material);
        return c.isEmpty() ? 0 : c.values().iterator().next();
    }

    /**
     * Units one stored item supplies to a group (what the builders saw / cut / bale / forge it into): a log 4 planks,
     * a plank 1, a cobblestone 1, a glass 8, a pane 3, a brick 1, a bricks block 4, a wool 3, a carpet 2, a hay bale 9,
     * an iron ingot 8, a torch 1. Only plain goods qualify — crafted shapes in a store are not cut up again.
     */
    public static int supply(final String item, final String group) {
        final String m = norm(item);
        if (group == null) {
            return 0;
        }
        return switch (group) {
            case WOOD -> {
                if (log(m) && !m.startsWith("DEEPSLATE")) {
                    yield 4;
                }
                yield m.endsWith("_PLANKS") && WOOD.equals(basicGroup(m)) ? 1 : 0;
            }
            case STONE -> switch (m) {
                case "COBBLESTONE", "STONE", "COBBLED_DEEPSLATE", "DEEPSLATE", "ANDESITE", "DIORITE", "GRANITE", "TUFF",
                     "STONE_BRICKS", "MOSSY_COBBLESTONE" -> 1;
                default -> 0;
            };
            case GLASS -> glassBlock(m) ? 8 : glassPane(m) ? 3 : 0;
            case SAND -> switch (m) {
                case "SAND", "RED_SAND" -> 1;
                case "SANDSTONE", "RED_SANDSTONE" -> 4;
                default -> 0;
            };
            case BRICK -> m.equals("BRICK") ? 1 : m.equals("BRICKS") ? 4 : 0;
            case TERRACOTTA -> m.equals("TERRACOTTA") || m.endsWith("_TERRACOTTA") && !m.endsWith("_GLAZED_TERRACOTTA") ? 1 : 0;
            case WOOL -> wool(m) ? 3 : carpet(m) ? 2 : 0;
            case HAY -> m.equals("HAY_BLOCK") ? 9 : 0;
            case IRON -> m.equals("IRON_INGOT") ? 8 : m.equals("IRON_BLOCK") ? 72 : 0;
            case TORCH -> m.equals("TORCH") ? 1 : 0;
            default -> 0;
        };
    }

    /** Units still needed per group for the given block names (counted in template order). */
    public static Map<String, Long> total(final Iterable<String> blocks) {
        final Map<String, Long> out = new LinkedHashMap<>();
        for (final String b : blocks) {
            costs(b).forEach((g, u) -> out.merge(g, (long) u, Long::sum));
        }
        return out;
    }

    /**
     * Whether a site must wait for the physical material of a group, or the builders buy it with the order's coins.
     * A country that cannot produce the group yet (no sawmill for wood, no quarry or mine for stone, no glassworks for
     * glass...) or has no registered store at all never stalls; a country that can, waits — at most {@code limitMillis}
     * (0 = forever), after which the material is bought so that no village blocks itself for good (e.g. its stores stay
     * unloaded).
     */
    public static boolean mustWait(final boolean producer, final boolean store, final long waitedMillis, final long limitMillis) {
        if (!producer || !store) {
            return false;
        }
        return limitMillis <= 0 || waitedMillis < limitMillis;
    }

    /**
     * Whether a country with these finished workplaces makes the group at all: wood — sawmill; stone — quarry or mine;
     * glass and sand — glassworks; bricks and terracotta — brickworks; wool and hay — sheepfold; iron — smithy and a
     * mine (raw iron); torches — smithy.
     */
    public static boolean producedBy(final String group, final Predicate<BuildingFunction> has) {
        return switch (group == null ? "" : group) {
            case WOOD -> has.test(BuildingFunction.SAWMILL);
            case STONE -> has.test(BuildingFunction.QUARRY) || has.test(BuildingFunction.MINE);
            case GLASS, SAND -> has.test(BuildingFunction.GLASSWORKS);
            case BRICK, TERRACOTTA -> has.test(BuildingFunction.BRICKWORKS);
            case WOOL, HAY -> has.test(BuildingFunction.SHEEPFOLD);
            case IRON -> has.test(BuildingFunction.SMITHY) && has.test(BuildingFunction.MINE);
            case TORCH -> has.test(BuildingFunction.SMITHY);
            default -> false;
        };
    }

    /** Polish name of the workplace(s) that make a group ("tartak", "huta szkła"...). */
    public static String producerName(final String group) {
        return switch (group == null ? "" : group) {
            case WOOD -> "tartak";
            case STONE -> "kamieniołom lub kopalnia";
            case GLASS, SAND -> "huta szkła";
            case BRICK, TERRACOTTA -> "cegielnia";
            case WOOL, HAY -> "owczarnia";
            case IRON -> "kuźnia i kopalnia";
            case TORCH -> "kuźnia";
            default -> "-";
        };
    }

    /** Units of a typical stored item of a group (for readable counts: 12 units of glass = 2 glass blocks). */
    public static int unitsPerItem(final String group) {
        return switch (group == null ? "" : group) {
            case WOOD -> 4;
            case GLASS, IRON -> 8;
            case WOOL -> 3;
            case HAY -> 9;
            default -> 1;
        };
    }

    /** Readable amount of a group: items of its typical raw good, rounded up ("szkło 2 szt."). */
    public static String readable(final String group, final long units) {
        final int per = unitsPerItem(group);
        final long items = (units + per - 1) / per;
        return displayName(group) + " " + items + " " + itemWord(group);
    }

    private static String itemWord(final String group) {
        return switch (group == null ? "" : group) {
            case WOOD -> "kłód";
            case GLASS -> "bl. szkła";
            case SAND, BRICK, TERRACOTTA, STONE, TORCH -> "szt.";
            case WOOL -> "wełny";
            case HAY -> "bel";
            case IRON -> "sztab";
            default -> "szt.";
        };
    }

    /** Polish name of a group. */
    public static String displayName(final String group) {
        return switch (group == null ? "" : group) {
            case WOOD -> "drewno";
            case STONE -> "kamień";
            case GLASS -> "szkło";
            case SAND -> "piasek";
            case BRICK -> "cegła";
            case TERRACOTTA -> "terakota";
            case WOOL -> "wełna";
            case HAY -> "siano";
            case IRON -> "żelazo";
            case TORCH -> "pochodnie";
            default -> group == null ? "" : group;
        };
    }
}
