// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import pl.medivalrpg.ludnosc.template.BlockKinds;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.FoundationMode;

/**
 * Pure rules of player blueprints ({@code /ludnosc kopia}): which copied blocks are allowed, how they are cleaned
 * (no liquids, no container contents, no valuables that could be mined back for profit), size / territory limits,
 * names, cost and the material list. No Bukkit calls — unit tested.
 *
 * <p>Only block-state strings are ever copied, never tile entities / NBT: chests come out empty, signs blank,
 * lecterns without books. Disallowed blocks and liquids become air, so the builders clear those cells.</p>
 */
public final class BlueprintRules {

    /** Template id prefix of every player blueprint ({@code kopia_<8 hex of the country>_<key>}). */
    public static final String ID_PREFIX = "kopia_";
    /** Planner category of blueprints (never chosen by the NPC planner). */
    public static final String CATEGORY = "kopia";
    public static final int MIN_NAME = 2;
    public static final int MAX_NAME = 24;
    /** Polish letters, digits, {@code _} and {@code -}; no spaces and nothing MiniMessage could interpret. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9ĄĆĘŁŃÓŚŹŻąćęłńóśźż_\\-]+");
    private static final Pattern KEY = Pattern.compile("[a-z0-9]{4,12}");
    /** Words that are subcommands of {@code /ludnosc kopia} (a name like that would read confusingly in help). */
    private static final Set<String> RESERVED = Set.of("pos1", "pos2", "zapisz", "lista", "info", "usun", "nazwa", "buduj",
        "wyczysc", "zaznaczenie", "pomoc");

    /** Blocks that are never copied (creative/technical, unobtainable, mob spawning, dangerous or valuable). */
    private static final Set<String> FORBIDDEN = Set.of(
        // technical / creative only
        "spawner", "trial_spawner", "vault", "command_block", "chain_command_block", "repeating_command_block",
        "structure_block", "structure_void", "jigsaw", "barrier", "light", "test_block", "test_instance_block",
        "moving_piston", "piston_head",
        // unbreakable / unobtainable / portals
        "bedrock", "end_portal_frame", "end_portal", "end_gateway", "nether_portal", "reinforced_deepslate",
        "budding_amethyst", "petrified_oak_slab", "frosted_ice", "suspicious_sand", "suspicious_gravel",
        // valuables that could be mined back and sold (no money from thin air)
        "beacon", "conduit", "dragon_egg", "dragon_head", "dragon_wall_head", "wither_skeleton_skull",
        "wither_skeleton_wall_skull", "heavy_core", "lodestone", "ancient_debris", "gilded_blackstone",
        "coal_block", "iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
        "netherite_block", "copper_block", "waxed_copper_block", "raw_iron_block", "raw_gold_block", "raw_copper_block",
        "resin_block", "enchanting_table", "jukebox", "anvil", "chipped_anvil", "damaged_anvil", "respawn_anchor",
        "ender_chest", "sponge", "wet_sponge", "crying_obsidian",
        // fire, explosives, mob spawning
        "tnt", "fire", "soul_fire", "creaking_heart", "turtle_egg", "sniffer_egg", "frogspawn", "sculk_shrieker");
    /** Liquids and blocks that only exist in water: replaced by air (no floods on the new site). */
    private static final Set<String> LIQUIDS = Set.of("water", "lava", "bubble_column", "kelp", "kelp_plant", "seagrass",
        "tall_seagrass");
    /** Properties holding contents / stored value: reset so the copy is an empty shell. */
    private static final Map<String, String> RESET_PROPERTIES = Map.of(
        "waterlogged", "false",
        "has_book", "false",
        "has_record", "false",
        "honey_level", "0");

    /**
     * Cost weight of a block relative to an ordinary one (iron / gold content and rare decorations): the order pays
     * {@code cost-per-block × weight}, so nothing worth more than it cost can be mined out of a copy.
     */
    private static final Map<String, Integer> WEIGHTS = Map.ofEntries(
        Map.entry("lantern", 2), Map.entry("soul_lantern", 2), Map.entry("iron_door", 2), Map.entry("iron_trapdoor", 4),
        Map.entry("cauldron", 8), Map.entry("water_cauldron", 8), Map.entry("powder_snow_cauldron", 8),
        Map.entry("hopper", 6), Map.entry("blast_furnace", 6), Map.entry("smithing_table", 2),
        Map.entry("heavy_weighted_pressure_plate", 2), Map.entry("light_weighted_pressure_plate", 6),
        Map.entry("bell", 10), Map.entry("powered_rail", 6), Map.entry("detector_rail", 2), Map.entry("activator_rail", 2),
        Map.entry("rail", 1), Map.entry("chain", 1), Map.entry("iron_bars", 1), Map.entry("brewing_stand", 3),
        Map.entry("skeleton_skull", 5), Map.entry("skeleton_wall_skull", 5), Map.entry("zombie_head", 5),
        Map.entry("zombie_wall_head", 5), Map.entry("creeper_head", 5), Map.entry("creeper_wall_head", 5),
        Map.entry("piglin_head", 5), Map.entry("piglin_wall_head", 5), Map.entry("player_head", 2),
        Map.entry("player_wall_head", 2), Map.entry("obsidian", 3), Map.entry("glowstone", 2), Map.entry("sea_lantern", 3),
        Map.entry("amethyst_block", 2), Map.entry("lightning_rod", 3), Map.entry("copper_bulb", 3), Map.entry("crafter", 6),
        Map.entry("observer", 2), Map.entry("piston", 2), Map.entry("sticky_piston", 3), Map.entry("target", 2),
        Map.entry("daylight_detector", 3), Map.entry("comparator", 3), Map.entry("repeater", 2), Map.entry("note_block", 2),
        Map.entry("redstone_lamp", 3), Map.entry("chiseled_bookshelf", 2), Map.entry("bookshelf", 2),
        Map.entry("decorated_pot", 2), Map.entry("trapped_chest", 2), Map.entry("barrel", 1), Map.entry("chest", 1));

    private BlueprintRules() {
    }

    // ------------------------------------------------------------------ blocks

    /** Why a copied cell changed. */
    public enum Change { NONE, CLEANED, FORBIDDEN, LIQUID }

    /** A copied cell after cleaning: the state to store and what was done to it. */
    public record Cell(String state, Change change) {
    }

    /** Block id without namespace and properties, lower case ({@code oak_stairs}). */
    public static String path(final String state) {
        if (state == null || state.isBlank()) {
            return "";
        }
        return BlockStates.parse(state).path();
    }

    /** True for a block that is never copied (see the class comment). */
    public static boolean forbidden(final String state) {
        final String p = path(state);
        return FORBIDDEN.contains(p) || p.endsWith("shulker_box") || p.endsWith("_ore") || p.startsWith("infested_");
    }

    public static boolean liquid(final String state) {
        return LIQUIDS.contains(path(state));
    }

    /**
     * Cleans one copied block state: air stays air, forbidden blocks and liquids become air, waterlogging / stored
     * books / records / honey are reset, a lava cauldron becomes an empty cauldron. Unparseable states become air.
     */
    public static Cell sanitize(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new Cell(BlockStates.AIR, Change.NONE);
        }
        final BlockStates.Parsed p;
        try {
            p = BlockStates.parse(raw);
        } catch (final IllegalArgumentException e) {
            return new Cell(BlockStates.AIR, Change.FORBIDDEN);
        }
        if (!p.id().startsWith("minecraft:")) {
            return new Cell(BlockStates.AIR, Change.FORBIDDEN); // modded / custom ids: never
        }
        final String formatted = p.format();
        if (BlockStates.isAir(formatted)) {
            return new Cell(BlockStates.AIR, Change.NONE);
        }
        if (LIQUIDS.contains(p.path())) {
            return new Cell(BlockStates.AIR, Change.LIQUID);
        }
        if (forbidden(formatted)) {
            return new Cell(BlockStates.AIR, Change.FORBIDDEN);
        }
        if (p.path().equals("lava_cauldron")) {
            return new Cell("minecraft:cauldron", Change.CLEANED);
        }
        boolean changed = false;
        final Map<String, String> props = new LinkedHashMap<>(p.properties());
        for (final Map.Entry<String, String> e : props.entrySet()) {
            final String reset = RESET_PROPERTIES.get(e.getKey());
            final boolean slot = e.getKey().startsWith("slot_") && e.getKey().endsWith("_occupied");
            final String target = slot ? "false" : reset;
            if (target != null && !target.equals(e.getValue())) {
                e.setValue(target);
                changed = true;
            }
        }
        if (p.path().equals("composter") && props.containsKey("level") && !"0".equals(props.get("level"))) {
            props.put("level", "0");
            changed = true;
        }
        if (!changed) {
            return new Cell(formatted, Change.NONE);
        }
        return new Cell(new BlockStates.Parsed(p.id(), props).format(), Change.CLEANED);
    }

    /** Cost weight of a placed block (air / keep = 0, ordinary = 1). */
    public static int weight(final String state) {
        if (state == null || BlockStates.isAir(state)) {
            return 0;
        }
        return WEIGHTS.getOrDefault(path(state), 1);
    }

    // ------------------------------------------------------------------ capture

    /**
     * Result of turning raw copied cells into a template grid.
     *
     * @param cells     cleaned cells, front turned to the south, empty top layers trimmed
     * @param forbidden blocks dropped because they may not be copied
     * @param liquids   liquid / underwater blocks dropped
     * @param cleaned   blocks whose stored contents were reset
     * @param orphans   bed / door halves without their partner (dropped)
     */
    public record Grid(String[] cells, int width, int height, int depth, int forbidden, int liquids, int cleaned,
                       int orphans, String front) {

        public int blocks() {
            int n = 0;
            for (final String s : this.cells) {
                if (s != null && !BlockStates.isAir(s)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * Cleans raw world states (index {@code (y * depth + z) * width + x}, x west→east, z north→south), drops bed and
     * door halves cut by the selection, trims empty layers on top and turns the grid so that {@code front}
     * ({@code north|east|south|west}, null = detected from the outside doors, default south) faces south like every
     * other template.
     */
    public static Grid grid(final String[] raw, final int width, final int height, final int depth, final String front) {
        if (width <= 0 || height <= 0 || depth <= 0 || raw.length != width * height * depth) {
            throw new IllegalArgumentException("Złe wymiary kopii");
        }
        final String[] cells = new String[raw.length];
        int forbidden = 0;
        int liquids = 0;
        int cleaned = 0;
        for (int i = 0; i < raw.length; i++) {
            final Cell c = sanitize(raw[i]);
            cells[i] = c.state();
            switch (c.change()) {
                case FORBIDDEN -> forbidden++;
                case LIQUID -> liquids++;
                case CLEANED -> cleaned++;
                default -> { }
            }
        }
        final int orphans = dropOrphans(cells, width, height, depth);
        // trim layers of pure air on top (nothing to build there)
        int top = height;
        while (top > 1 && layerEmpty(cells, width, depth, top - 1)) {
            top--;
        }
        final String[] trimmed = top == height ? cells : java.util.Arrays.copyOf(cells, width * depth * top);
        final BuildingTemplate plain = new BuildingTemplate(new BuildingTemplate.Meta("kopia_tmp", "kopia", "",
            BuildingFunction.OTHER, 0, 0, FoundationMode.NONE, "cobblestone", 0, ""), width, top, depth, trimmed);
        final String facing = front == null ? detectFront(plain) : normalizeDirection(front);
        final int q = Math.floorMod(2 - List.of("north", "east", "south", "west").indexOf(facing), 4);
        final BuildingTemplate turned = plain.rotated(q);
        final String[] out = new String[turned.volume()];
        for (int i = 0; i < out.length; i++) {
            out[i] = turned.state(i);
        }
        return new Grid(out, turned.width(), turned.height(), turned.depth(), forbidden, liquids, cleaned, orphans, facing);
    }

    private static boolean layerEmpty(final String[] cells, final int width, final int depth, final int y) {
        for (int i = y * width * depth; i < (y + 1) * width * depth; i++) {
            if (cells[i] != null && !BlockStates.isAir(cells[i])) {
                return false;
            }
        }
        return true;
    }

    /** Replaces bed halves and door halves whose partner is missing with air; returns how many. */
    static int dropOrphans(final String[] cells, final int width, final int height, final int depth) {
        final List<Integer> drop = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    final int i = (y * depth + z) * width + x;
                    final String s = cells[i];
                    if (s == null || BlockStates.isAir(s) || !(s.contains("_bed") || s.contains("_door"))) {
                        continue;
                    }
                    final BlockStates.Parsed p = BlockStates.parse(s);
                    int ox = x;
                    int oy = y;
                    int oz = z;
                    if (BlockKinds.isBed(p)) {
                        final int[] v = BlockStates.vector(Objects.requireNonNullElse(p.property("facing"), ""));
                        final int sign = BlockKinds.isBedHead(p) ? -1 : 1;
                        ox = x + sign * v[0];
                        oz = z + sign * v[1];
                    } else if (BlockKinds.isDoor(p)) {
                        oy = "lower".equals(p.property("half")) ? y + 1 : y - 1;
                    } else {
                        continue;
                    }
                    final boolean inside = ox >= 0 && oy >= 0 && oz >= 0 && ox < width && oy < height && oz < depth
                        && !(ox == x && oy == y && oz == z);
                    final String other = inside ? cells[(oy * depth + oz) * width + ox] : null;
                    final BlockStates.Parsed o = other == null || BlockStates.isAir(other) ? null : BlockStates.parse(other);
                    final boolean ok = o != null && o.id().equals(p.id())
                        && Objects.equals(o.property("facing"), p.property("facing"))
                        && (BlockKinds.isBed(p) ? !Objects.equals(o.property("part"), p.property("part"))
                            : !Objects.equals(o.property("half"), p.property("half")));
                    if (!ok) {
                        drop.add(i);
                    }
                }
            }
        }
        for (final int i : drop) {
            cells[i] = BlockStates.AIR;
        }
        return drop.size();
    }

    /** Most common outward direction of the outside doors; south when there is none. */
    public static String detectFront(final BuildingTemplate t) {
        final Map<String, Integer> votes = new HashMap<>();
        for (final int[] d : t.exteriorDoors()) {
            final String dir = d[3] > 0 ? "east" : d[3] < 0 ? "west" : d[4] < 0 ? "north" : "south";
            votes.merge(dir, 1, Integer::sum);
        }
        String best = "south";
        int max = 0;
        for (final String dir : List.of("south", "east", "west", "north")) {
            final int v = votes.getOrDefault(dir, 0);
            if (v > max) {
                max = v;
                best = dir;
            }
        }
        return best;
    }

    /** Polish or English direction name → {@code north|east|south|west}; null when unknown. */
    public static String direction(final String word) {
        if (word == null) {
            return null;
        }
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "polnoc", "północ", "n", "north" -> "north";
            case "wschod", "wschód", "e", "east" -> "east";
            case "poludnie", "południe", "s", "south" -> "south";
            case "zachod", "zachód", "w", "west" -> "west";
            default -> null;
        };
    }

    private static String normalizeDirection(final String front) {
        final String d = direction(front);
        return d == null ? "south" : d;
    }

    // ------------------------------------------------------------------ limits

    /**
     * Configurable limits.
     *
     * @param maxWidth      along x / z (both horizontal sides)
     * @param maxHeight     along y
     * @param maxBlocks     placed (non-air) blocks
     * @param perCountry    blueprints per country
     */
    public record Limits(int maxWidth, int maxHeight, int maxBlocks, int perCountry) {

        public static final Limits DEFAULTS = new Limits(32, 32, 12_000, 20);

        public Limits {
            maxWidth = Math.max(1, maxWidth);
            maxHeight = Math.max(1, maxHeight);
            maxBlocks = Math.max(1, maxBlocks);
            perCountry = Math.max(0, perCountry);
        }
    }

    /** Selection size problem (Polish) or null. Width/depth are the horizontal sides, in any order. */
    public static String sizeProblem(final int sx, final int sy, final int sz, final Limits limits) {
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            return "puste zaznaczenie";
        }
        if (sx > limits.maxWidth() || sz > limits.maxWidth() || sy > limits.maxHeight()) {
            return "zaznaczenie " + sx + "×" + sz + "×" + sy + " przekracza limit " + limits.maxWidth() + "×"
                + limits.maxWidth() + "×" + limits.maxHeight() + " (szer.×gł.×wys.)";
        }
        return null;
    }

    /** Block-count problem (Polish) or null. */
    public static String blocksProblem(final int blocks, final Limits limits) {
        if (blocks <= 0) {
            return "w zaznaczeniu nie ma żadnych bloków do skopiowania";
        }
        if (blocks > limits.maxBlocks()) {
            return "kopia ma " + blocks + " bloków, limit to " + limits.maxBlocks();
        }
        return null;
    }

    /** Chunks {cx, cz} covered by a block box. */
    public static List<int[]> chunks(final int minX, final int minZ, final int maxX, final int maxZ) {
        final List<int[]> out = new ArrayList<>();
        for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(maxX, 16); cx++) {
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) {
                out.add(new int[] {cx, cz});
            }
        }
        return out;
    }

    /** True when every chunk of the box belongs to {@code country} ({@code ownerAt(cx, cz)}, null = wilderness). */
    public static boolean onCountryLand(final UUID country, final int minX, final int minZ, final int maxX, final int maxZ,
                                        final BiFunction<Integer, Integer, UUID> ownerAt) {
        if (country == null) {
            return false;
        }
        for (final int[] c : chunks(minX, minZ, maxX, maxZ)) {
            if (!country.equals(ownerAt.apply(c[0], c[1]))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ names and ids

    /** Problem with a blueprint name (Polish) or null when it is valid. */
    public static String nameProblem(final String name) {
        if (name == null || name.isBlank()) {
            return "podaj nazwę";
        }
        if (name.length() < MIN_NAME || name.length() > MAX_NAME) {
            return "nazwa musi mieć od " + MIN_NAME + " do " + MAX_NAME + " znaków";
        }
        if (!NAME.matcher(name).matches()) {
            return "dozwolone są litery (także polskie), cyfry, _ i -";
        }
        if (RESERVED.contains(nameKey(name))) {
            return "„" + name + "” to nazwa komendy — wybierz inną";
        }
        return null;
    }

    /** Case-insensitive key used for collisions. */
    public static String nameKey(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** True when {@code name} collides with one of {@code existing} (ignoring {@code except}, e.g. on rename). */
    public static boolean nameTaken(final String name, final Collection<String> existing, final String except) {
        final String k = nameKey(name);
        for (final String e : existing) {
            if (nameKey(e).equals(k) && (except == null || !nameKey(except).equals(k))) {
                return true;
            }
        }
        return false;
    }

    /** Problem with a rename (Polish) or null. */
    public static String renameProblem(final String oldName, final String newName, final Collection<String> existing) {
        final String p = nameProblem(newName);
        if (p != null) {
            return p;
        }
        if (nameKey(oldName).equals(nameKey(newName))) {
            return oldName.equals(newName) ? "to ta sama nazwa" : null; // only the letter case changes
        }
        return nameTaken(newName, existing, oldName) ? "kopia o nazwie „" + newName + "” już istnieje" : null;
    }

    /** Template id of a blueprint: {@code kopia_<first 8 hex of the country>_<key>}. */
    public static String templateId(final UUID country, final String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Zły klucz kopii: " + key);
        }
        return ID_PREFIX + country.toString().replace("-", "").substring(0, 8) + "_" + key;
    }

    public static boolean isBlueprintId(final String templateId) {
        return templateId != null && templateId.toLowerCase(Locale.ROOT).startsWith(ID_PREFIX);
    }

    /** Valid file / id key ({@code [a-z0-9]{4,12}}). */
    public static boolean validKey(final String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** New random key not in {@code used}. */
    public static String newKey(final java.util.Random random, final Collection<String> used) {
        for (int i = 0; i < 1000; i++) {
            final StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 6; c++) {
                sb.append(Character.forDigit(random.nextInt(36), 36));
            }
            if (!used.contains(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Brak wolnego klucza kopii");
    }

    // ------------------------------------------------------------------ cost and materials

    /** Sum of block weights (see {@link #weight}). */
    public static long weightedBlocks(final BuildingTemplate t) {
        long n = 0;
        for (int i = 0; i < t.volume(); i++) {
            n += weight(t.state(i));
        }
        return n;
    }

    /** Order cost in copper: {@code max(minimum, weighted blocks × perBlock)}, capped by {@code maximum} (0 = none). */
    public static long cost(final long weightedBlocks, final long perBlock, final long minimum, final long maximum) {
        final long base = Math.max(0, Math.max(minimum, Math.multiplyExact(Math.max(0, weightedBlocks), Math.max(0, perBlock))));
        return maximum > 0 ? Math.min(maximum, Math.max(base, 0)) : base;
    }

    /** Placed blocks per block id (no properties), most numerous first. */
    public static List<Map.Entry<String, Integer>> materials(final BuildingTemplate t) {
        final Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < t.volume(); i++) {
            final String s = t.state(i);
            if (s != null && !BlockStates.isAir(s)) {
                counts.merge(path(s), 1, Integer::sum);
            }
        }
        final List<Map.Entry<String, Integer>> out = new ArrayList<>(counts.entrySet());
        out.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        return out;
    }

    /** Raw material units taken from the country store (wood / stone, see {@link MaterialNeeds}). */
    public static Map<String, Long> storeNeeds(final BuildingTemplate t) {
        final List<String> blocks = new ArrayList<>();
        for (int i = 0; i < t.volume(); i++) {
            final String s = t.state(i);
            if (s != null && !BlockStates.isAir(s)) {
                blocks.add(s);
            }
        }
        return MaterialNeeds.total(blocks);
    }

    /** Function of a blueprint: housing when it has whole beds, otherwise a neutral building. */
    public static BuildingFunction function(final int beds) {
        return beds > 0 ? BuildingFunction.HOUSING : BuildingFunction.OTHER;
    }
}
