// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable building template: a 3D grid of block-state strings (pure Java, no Bukkit).
 *
 * <p>Local frame: {@code x} west → east, {@code y} bottom → top, {@code z} north → south. Cell value {@code null}
 * means "keep whatever is there" ({@code _} in YAML), {@link BlockStates#AIR} forces air ({@code .}). Layer
 * {@link Meta#groundLayer()} lies at the origin's y (floor level); layers below are underground (well shafts).
 * The front (door side) of an unrotated template is south.</p>
 *
 * <p>{@link #rotated(int)} turns the grid clockwise around the vertical axis and rotates every directional block
 * state with {@link BlockStates#rotate}, so stairs, doors, beds, torches, logs and panes keep facing the right way.</p>
 */
public final class BuildingTemplate {

    /**
     * Template metadata.
     *
     * @param groundLayer   layer index placed at the origin's y (0 = the lowest layer is the floor)
     * @param source        where the template came from (for messages), e.g. {@code wbudowany:chata.yml}
     * @param tier          minimal settlement level index (0 Osada … 4 Stolica) for the planner and players
     * @param category      planner category: housing, food, economy, military, civic, religious, defense,
     *                      representative, infrastructure, other
     * @param zone          preferred place in the town: center, inner, outer, border
     * @param maxPerCountry 0 = unlimited
     * @param minPopulation required citizens
     * @param minTreasury   required treasury balance, copper
     */
    public record Meta(String id, String displayName, String description, BuildingFunction function, int declaredBeds,
                       long cost, FoundationMode foundation, String foundationBlock, int groundLayer, String source,
                       int tier, String category, String zone, int maxPerCountry, int minPopulation, long minTreasury,
                       int era, String discovery, String variantOf) {

        /** Metadata of a base type (not a variant of another template). */
        public Meta(final String id, final String displayName, final String description, final BuildingFunction function,
                    final int declaredBeds, final long cost, final FoundationMode foundation, final String foundationBlock,
                    final int groundLayer, final String source, final int tier, final String category, final String zone,
                    final int maxPerCountry, final int minPopulation, final long minTreasury, final int era, final String discovery) {
            this(id, displayName, description, function, declaredBeds, cost, foundation, foundationBlock, groundLayer, source, tier,
                category, zone, maxPerCountry, minPopulation, minTreasury, era, discovery, "");
        }

        /** Metadata without epoch requirements (epoch 1, no discovery). */
        public Meta(final String id, final String displayName, final String description, final BuildingFunction function,
                    final int declaredBeds, final long cost, final FoundationMode foundation, final String foundationBlock,
                    final int groundLayer, final String source, final int tier, final String category, final String zone,
                    final int maxPerCountry, final int minPopulation, final long minTreasury) {
            this(id, displayName, description, function, declaredBeds, cost, foundation, foundationBlock, groundLayer, source, tier,
                category, zone, maxPerCountry, minPopulation, minTreasury, 1, "");
        }

        public Meta {
            Objects.requireNonNull(id, "id");
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
            description = description == null ? "" : description;
            function = function == null ? BuildingFunction.OTHER : function;
            foundation = foundation == null ? FoundationMode.FILL : foundation;
            foundationBlock = BlockStates.normalize(foundationBlock == null || foundationBlock.isBlank() ? "cobblestone" : foundationBlock);
            source = source == null ? "" : source;
            tier = Math.max(0, tier);
            category = category == null || category.isBlank() ? defaultCategory(function) : category.trim().toLowerCase(java.util.Locale.ROOT);
            zone = zone == null || zone.isBlank() ? "inner" : zone.trim().toLowerCase(java.util.Locale.ROOT);
            maxPerCountry = Math.max(0, maxPerCountry);
            minPopulation = Math.max(0, minPopulation);
            minTreasury = Math.max(0, minTreasury);
            era = Math.max(1, Math.min(4, era));
            discovery = discovery == null ? "" : discovery.trim().toLowerCase(java.util.Locale.ROOT);
            variantOf = variantOf == null ? "" : variantOf.trim().toLowerCase(java.util.Locale.ROOT);
            if (variantOf.equals(id)) {
                variantOf = "";
            }
        }

        /**
         * Building type this template belongs to: {@code variant-of} for a look variant (e.g. {@code dom_szachulec} →
         * {@code dom}), otherwise its own id. Limits, config ({@code buildings.<id>}), bonuses and underground features
         * of a variant are those of its base type.
         */
        public String baseType() {
            return this.variantOf.isEmpty() ? this.id : this.variantOf;
        }

        /** True for a look variant of another template ({@code variant-of}). */
        public boolean isVariant() {
            return !this.variantOf.isEmpty();
        }

        /** Metadata without planner information (schematics, tests). */
        public Meta(final String id, final String displayName, final String description, final BuildingFunction function,
                    final int declaredBeds, final long cost, final FoundationMode foundation, final String foundationBlock,
                    final int groundLayer, final String source) {
            this(id, displayName, description, function, declaredBeds, cost, foundation, foundationBlock, groundLayer, source,
                0, null, null, 0, 0, 0);
        }

        private static String defaultCategory(final BuildingFunction f) {
            if (f == null) {
                return "other";
            }
            return switch (f) {
                case HOUSING -> "housing";
                case GRANARY -> "food";
                case BARRACKS -> "military";
                case TOWER, WALL, GATE -> "defense";
                case WELL -> "infrastructure";
                case FARM -> "farm";
                default -> "other";
            };
        }

        /** Same metadata with overridden balance values (config {@code buildings.<id>}). */
        public Meta withBalance(final long cost, final int tier, final int maxPerCountry, final int minPopulation,
                                final long minTreasury) {
            return new Meta(this.id, this.displayName, this.description, this.function, this.declaredBeds, cost,
                this.foundation, this.foundationBlock, this.groundLayer, this.source, tier, this.category, this.zone,
                maxPerCountry, minPopulation, minTreasury, this.era, this.discovery, this.variantOf);
        }
    }

    /** Copy with other metadata (same grid). */
    public BuildingTemplate withMeta(final Meta newMeta) {
        return new BuildingTemplate(newMeta, this.width, this.height, this.depth, this.rotation, this.cells, this.materials);
    }

    /** Copy where the country-colour placeholder {@code {kolor}} is replaced by a dye colour (e.g. {@code blue}). */
    public BuildingTemplate colored(final String dye) {
        boolean any = false;
        final String[] out = new String[this.cells.length];
        for (int i = 0; i < out.length; i++) {
            final String s = this.cells[i];
            if (s != null && s.contains(BlockStates.COLOR)) {
                out[i] = BlockStates.colorize(s, dye);
                any = true;
            } else {
                out[i] = s;
            }
        }
        return any ? new BuildingTemplate(this.meta, this.width, this.height, this.depth, this.rotation, out, this.materials) : this;
    }

    /** True when some state uses a material slot ({@code @wall}...). */
    public boolean usesMaterials() {
        for (final String s : this.cells) {
            if (MaterialPalette.isSlot(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copy with material slots resolved for a settlement level (connections of windows/fences recomputed for the
     * real blocks). Unchanged when the template has no slots.
     */
    public BuildingTemplate atLevel(final MaterialPalette palette, final int level) {
        if (!this.usesMaterials()) {
            return this;
        }
        final String[] out = new String[this.cells.length];
        final Map<String, String> cache = new HashMap<>();
        for (int i = 0; i < out.length; i++) {
            final String s = this.cells[i];
            out[i] = s == null ? null : cache.computeIfAbsent(s, st -> palette.resolve(st, level, this.materials.get(level)));
        }
        return new BuildingTemplate(this.meta, this.width, this.height, this.depth, this.rotation,
            Connections.apply(out, this.width, this.height, this.depth), this.materials);
    }

    /** True when some state uses the country colour placeholder. */
    public boolean usesColor() {
        for (final String s : this.cells) {
            if (s != null && s.contains(BlockStates.COLOR)) {
                return true;
            }
        }
        return false;
    }

    private final Meta meta;
    private final int width;
    private final int height;
    private final int depth;
    private final int rotation;
    private final String[] cells;
    private final Map<Integer, Map<String, String>> materials;

    public BuildingTemplate(final Meta meta, final int width, final int height, final int depth, final String[] cells) {
        this(meta, width, height, depth, 0, cells);
    }

    private BuildingTemplate(final Meta meta, final int width, final int height, final int depth, final int rotation,
                             final String[] cells) {
        this(meta, width, height, depth, rotation, cells, Map.of());
    }

    private BuildingTemplate(final Meta meta, final int width, final int height, final int depth, final int rotation,
                             final String[] cells, final Map<Integer, Map<String, String>> materials) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Pusty szablon: " + meta.id());
        }
        if (cells.length != width * height * depth) {
            throw new IllegalArgumentException("Zła liczba komórek szablonu " + meta.id());
        }
        if (meta.groundLayer() < 0 || meta.groundLayer() >= height) {
            throw new IllegalArgumentException("ground-layer poza zakresem warstw w szablonie " + meta.id());
        }
        this.meta = meta;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.rotation = Math.floorMod(rotation, 4);
        this.cells = cells.clone();
        this.materials = materials == null ? Map.of() : Map.copyOf(materials);
    }

    /** Template-specific material overrides: level index → slot → block (e.g. its classic look at level 1). */
    public Map<Integer, Map<String, String>> materials() {
        return this.materials;
    }

    public BuildingTemplate withMaterials(final Map<Integer, Map<String, String>> overrides) {
        return new BuildingTemplate(this.meta, this.width, this.height, this.depth, this.rotation, this.cells, overrides);
    }

    public Meta meta() {
        return this.meta;
    }

    public String id() {
        return this.meta.id();
    }

    /** Size along x. */
    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /** Size along z. */
    public int depth() {
        return this.depth;
    }

    /** Quarter turns (clockwise) applied relative to the definition. */
    public int rotation() {
        return this.rotation;
    }

    public int volume() {
        return this.cells.length;
    }

    public int index(final int x, final int y, final int z) {
        return (y * this.depth + z) * this.width + x;
    }

    public int xOf(final int index) {
        return index % this.width;
    }

    public int zOf(final int index) {
        return (index / this.width) % this.depth;
    }

    public int yOf(final int index) {
        return index / (this.width * this.depth);
    }

    public boolean inside(final int x, final int y, final int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.height && z < this.depth;
    }

    /** State at a local position; {@code null} = keep terrain (also outside the grid). */
    public String state(final int x, final int y, final int z) {
        return this.inside(x, y, z) ? this.cells[this.index(x, y, z)] : null;
    }

    public String state(final int index) {
        return this.cells[index];
    }

    /** Direction the front (door side) faces after rotation. */
    public String front() {
        return BlockStates.rotateDirection("south", this.rotation);
    }

    // ------------------------------------------------------------------ rotation

    /** Copy rotated clockwise by {@code quarterTurns} (relative to this template). */
    public BuildingTemplate rotated(final int quarterTurns) {
        final int q = Math.floorMod(quarterTurns, 4);
        if (q == 0) {
            return this;
        }
        final boolean swap = q % 2 == 1;
        final int nw = swap ? this.depth : this.width;
        final int nd = swap ? this.width : this.depth;
        final String[] out = new String[this.cells.length];
        final Map<String, String> rotatedStates = new HashMap<>();
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    final String s = this.cells[this.index(x, y, z)];
                    final int[] p = rotatePoint(x, z, this.width, this.depth, q);
                    out[(y * nd + p[1]) * nw + p[0]] = s == null ? null : rotatedStates.computeIfAbsent(s, k -> BlockStates.rotate(k, q));
                }
            }
        }
        return new BuildingTemplate(this.meta, nw, this.height, nd, this.rotation + q, out, this.materials);
    }

    /**
     * Rotates local column (x, z) of a {@code width × depth} grid clockwise by {@code q} quarter turns and returns the
     * new (x, z) in the rotated grid. One quarter turn: (x, z) → (depth − 1 − z, x).
     */
    public static int[] rotatePoint(final int x, final int z, final int width, final int depth, final int q) {
        int px = x;
        int pz = z;
        int w = width;
        int d = depth;
        for (int i = 0; i < Math.floorMod(q, 4); i++) {
            final int nx = d - 1 - pz;
            final int nz = px;
            px = nx;
            pz = nz;
            final int t = w;
            w = d;
            d = t;
        }
        return new int[] {px, pz};
    }

    // ------------------------------------------------------------------ analysis

    /** Local positions {x, y, z} of all bed heads (one per bed). */
    public List<int[]> bedHeads() {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < this.cells.length; i++) {
            final String s = this.cells[i];
            if (s != null && s.contains("_bed") && BlockKinds.isBedHead(BlockStates.parse(s))) {
                out.add(new int[] {this.xOf(i), this.yOf(i), this.zOf(i)});
            }
        }
        return out;
    }

    public int bedCount() {
        return this.bedHeads().size();
    }

    /** Local positions of chests and barrels (granary deposit points). */
    public List<int[]> containers() {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < this.cells.length; i++) {
            final String s = this.cells[i];
            if (s != null && BlockKinds.isContainer(BlockStates.parse(s))) {
                out.add(new int[] {this.xOf(i), this.yOf(i), this.zOf(i)});
            }
        }
        return out;
    }

    /** Number of cells that place a non-air block. */
    /** Cells holding a material slot ({@code @wall}...), i.e. the blocks a modernisation rebuilds. */
    public int slotCount() {
        int n = 0;
        for (int i = 0; i < this.volume(); i++) {
            if (MaterialPalette.isSlot(this.state(i))) {
                n++;
            }
        }
        return n;
    }

    public int solidCount() {
        int n = 0;
        for (final String s : this.cells) {
            if (s != null && !BlockStates.isAir(s)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Structural problems: beds without their other half (head must be one block in {@code facing} from the foot)
     * and doors without the matching upper/lower half. Empty list = template is consistent.
     */
    public List<String> validate() {
        final List<String> problems = new ArrayList<>();
        for (int i = 0; i < this.cells.length; i++) {
            final String s = this.cells[i];
            if (s == null) {
                continue;
            }
            final BlockStates.Parsed p = BlockStates.parse(s);
            final int x = this.xOf(i);
            final int y = this.yOf(i);
            final int z = this.zOf(i);
            if (BlockKinds.isBed(p)) {
                final String facing = p.property("facing");
                final int[] v = BlockStates.vector(facing == null ? "" : facing);
                if (v[0] == 0 && v[1] == 0) {
                    problems.add("łóżko bez kierunku w " + pos(x, y, z));
                    continue;
                }
                final boolean head = BlockKinds.isBedHead(p);
                final int sign = head ? -1 : 1;
                final String other = this.state(x + sign * v[0], y, z + sign * v[1]);
                final BlockStates.Parsed o = other == null ? null : BlockStates.parse(other);
                if (o == null || !o.id().equals(p.id()) || !Objects.equals(o.property("facing"), facing)
                    || (head ? !BlockKinds.isBedFoot(o) : !BlockKinds.isBedHead(o))) {
                    problems.add("łóżko bez drugiej połowy w " + pos(x, y, z));
                }
            } else if (BlockKinds.isDoor(p)) {
                final boolean lower = "lower".equals(p.property("half"));
                final String other = this.state(x, lower ? y + 1 : y - 1, z);
                final BlockStates.Parsed o = other == null ? null : BlockStates.parse(other);
                if (o == null || !o.id().equals(p.id()) || !Objects.equals(o.property("facing"), p.property("facing"))
                    || !Objects.equals(o.property("hinge"), p.property("hinge"))
                    || !(lower ? "upper" : "lower").equals(o.property("half"))) {
                    problems.add("drzwi bez drugiej połowy w " + pos(x, y, z));
                }
            }
        }
        return problems;
    }

    private static String pos(final int x, final int y, final int z) {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    private boolean isAirAt(final int x, final int y, final int z) {
        final String s = this.state(x, y, z);
        return s != null && BlockStates.isAir(s);
    }

    private boolean isSolidAt(final int x, final int y, final int z) {
        final String s = this.state(x, y, z);
        return s != null && !BlockStates.isAir(s) && BlockKinds.isFullBlock(BlockStates.parse(s));
    }

    /**
     * Local cell where new settlers appear: an air cell with two air blocks above solid floor, at the height of a bed,
     * as close as possible to a bed; without beds the free floor cell nearest to the centre above the ground layer.
     * Null when the template has no such cell.
     */
    public int[] spawnCell() {
        final List<int[]> beds = this.bedHeads();
        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        for (int y = 1; y < this.height - 1; y++) {
            for (int z = 0; z < this.depth; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (!this.isAirAt(x, y, z) || !this.isAirAt(x, y + 1, z) || !this.isSolidAt(x, y - 1, z)) {
                        continue;
                    }
                    long score;
                    if (beds.isEmpty()) {
                        if (y != this.meta.groundLayer() + 1) {
                            continue;
                        }
                        score = Math.abs(2L * x - this.width) + Math.abs(2L * z - this.depth);
                    } else {
                        score = Long.MAX_VALUE;
                        for (final int[] b : beds) {
                            if (b[1] != y) {
                                continue;
                            }
                            score = Math.min(score, Math.abs(b[0] - x) + Math.abs(b[2] - z));
                        }
                        if (score == Long.MAX_VALUE) {
                            continue;
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new int[] {x, y, z};
                    }
                }
            }
        }
        return best;
    }

    /**
     * Columns {x, z} that get a foundation under the lowest layer: every column whose lowest cell places a solid
     * block ({@link FoundationMode#FILL}), or only corners and every third edge column ({@link FoundationMode#PILLARS}).
     */
    public List<int[]> foundationColumns() {
        final List<int[]> out = new ArrayList<>();
        if (this.meta.foundation() == FoundationMode.NONE) {
            return out;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int z = 0; z < this.depth; z++) {
            for (int x = 0; x < this.width; x++) {
                final String s = this.state(x, 0, z);
                if (s != null && !BlockStates.isAir(s)) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        for (int z = 0; z < this.depth; z++) {
            for (int x = 0; x < this.width; x++) {
                final String s = this.state(x, 0, z);
                if (s == null || BlockStates.isAir(s)) {
                    continue;
                }
                if (this.meta.foundation() == FoundationMode.PILLARS) {
                    final boolean edgeX = x == minX || x == maxX;
                    final boolean edgeZ = z == minZ || z == maxZ;
                    final boolean corner = edgeX && edgeZ;
                    final boolean edgePost = (edgeX && (z - minZ) % 3 == 0) || (edgeZ && (x - minX) % 3 == 0);
                    if (!corner && !edgePost) {
                        continue;
                    }
                }
                out.add(new int[] {x, z});
            }
        }
        return out;
    }

    /**
     * Cell indices in building order: layer by layer from the bottom; inside a layer first clearing (air), then
     * solid blocks, then attached ones (torches, doors, beds, panes...), each group north-west to south-east.
     * "Keep" cells are skipped.
     */
    public int[] buildOrder() {
        return BuildPhases.order(this);
    }

    /**
     * Exterior doors: lower door halves whose outside (opposite to {@code facing}) leads out of the building on that
     * layer (no full block within four cells, or up to the grid edge). Returns {x, y, z, dx, dz} with (dx, dz) = outward.
     */
    public List<int[]> exteriorDoors() {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < this.cells.length; i++) {
            final String s = this.cells[i];
            if (s == null || !s.contains("_door") || !s.contains("half=lower")) {
                continue;
            }
            final BlockStates.Parsed p = BlockStates.parse(s);
            final String facing = p.property("facing");
            if (facing == null) {
                continue;
            }
            final int[] v = BlockStates.vector(BlockStates.rotateDirection(facing, 2));
            final int x = this.xOf(i);
            final int y = this.yOf(i);
            final int z = this.zOf(i);
            boolean outside = true;
            for (int k = 1; k <= 4; k++) {
                final int ox = x + k * v[0];
                final int oz = z + k * v[1];
                if (!this.inside(ox, y, oz)) {
                    break;
                }
                final String o = this.state(ox, y, oz);
                if (o != null && !BlockStates.isAir(o) && BlockKinds.isFullBlock(BlockStates.parse(o))) {
                    outside = false;
                    break;
                }
            }
            if (outside) {
                out.add(new int[] {x, y, z, v[0], v[1]});
            }
        }
        return out;
    }

    /** Stable content hash (dimensions, rotation, ground layer and every cell) — detects edited templates. */
    public long contentHash() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, this.width);
        h = mix(h, this.height);
        h = mix(h, this.depth);
        h = mix(h, this.rotation);
        h = mix(h, this.meta.groundLayer());
        for (final String s : this.cells) {
            h = mix(h, s == null ? 0 : s.hashCode());
        }
        return h;
    }

    private static long mix(final long h, final int v) {
        return (h ^ v) * 0x100000001b3L;
    }

    @Override
    public String toString() {
        return "BuildingTemplate[" + this.meta.id() + " " + this.width + "x" + this.height + "x" + this.depth
            + " rot=" + this.rotation + "]";
    }

    /** Distinct non-null states (palette after rotation). */
    public List<String> distinctStates() {
        return Arrays.stream(this.cells).filter(Objects::nonNull).distinct().toList();
    }
}
