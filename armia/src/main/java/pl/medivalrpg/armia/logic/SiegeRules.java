// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.UUID;

/** Pure limits shared by artillery commands, projectiles and impact protection. */
public final class SiegeRules {
    public enum Ammo {
        STONE("kamien", 5, 1, true, 40, 1.0),
        BALL("kula", 10, 1, true, 64, 1.0),
        GRAPESHOT("kartacz", 15, 5, false, 16, 0.65);
        private final String id;
        private final long cost;
        private final int pellets;
        private final boolean breaksBlocks;
        private final double range, damageShare;
        Ammo(final String id, final long cost, final int pellets, final boolean breaksBlocks,
             final double range, final double damageShare) {
            this.id=id; this.cost=cost; this.pellets=pellets; this.breaksBlocks=breaksBlocks;
            this.range=range; this.damageShare=damageShare;
        }
        public String id() { return this.id; }
        public long cost() { return this.cost; }
        public int pellets() { return this.pellets; }
        public boolean breaksBlocks() { return this.breaksBlocks; }
        public double range() { return this.range; }
        public double damageShare() { return this.damageShare; }
    }
    /** How an engine breaks walls: a swept projectile ({@code /armia ostrzal}) or a ram striking the blocks in front of it. */
    public enum Mode { PROJECTILE, RAM }

    public record Engine(Mode mode, boolean cannon, int reloadTicks, long shotCost, int blockLimit, double radius) {
        public boolean ram() { return this.mode == Mode.RAM; }
    }
    private SiegeRules() { }

    public static Engine engine(final String id) {
        if ("katapulta".equals(id)) return new Engine(Mode.PROJECTILE, false, 160, 5, 12, 1.8);
        if ("armata".equals(id) || "artyleria".equals(id)) return new Engine(Mode.PROJECTILE, true, 100, 10, 18, 2.2);
        // Ram: no projectile, no ammunition; reloadTicks = shortest strike interval, blockLimit = hard cap per strike.
        if ("taran".equals(id)) return new Engine(Mode.RAM, false, 60, 0, 4, 1.0);
        return null;
    }

    /** Projectile engines only (catapult, cannon): the ram never fires and never takes ammunition. */
    public static boolean shoots(final String id) {
        final Engine engine = engine(id);
        return engine != null && !engine.ram();
    }

    public static boolean ram(final String id) {
        final Engine engine = engine(id);
        return engine != null && engine.ram();
    }

    /** Every siege engine (the ram included) cancels part of the fortification bonus in abstract NPC battles. */
    public static boolean breaksFortifications(final String id) {
        return engine(id) != null;
    }

    // ------------------------------------------------------------------ ram

    /** Material class against which a ram strike is measured. */
    public enum RamClass { WOOD, STONE, FORTIFICATION }

    /**
     * Ram parameters ({@code units.taran.ram} in config.yml).
     *
     * @param arrowMultiplier     damage taken from arrows, bolts and thrown projectiles (below 1 = sturdy hide roof)
     * @param fireMultiplier      damage taken from fire and lava (above 1 = dry timber)
     * @param headReach           horizontal distance from the crew to the centre of the struck block (model head ~6.5)
     * @param blocksPerStrike     most blocks one strike may break
     * @param wood                share of a wooden/light block's durability removed per strike (1 = one strike)
     * @param stone               the same for stone and ordinary masonry
     * @param fortification       the same for fortifications (iron doors/bars, obsidian, hardness of at least 3)
     * @param fortificationBlocks extra material names always counted as fortifications
     * @param orderTicks          an unfinished order (unreachable gate) is dropped after this many ticks
     */
    public record RamSettings(double arrowMultiplier, double fireMultiplier, double headReach, int blocksPerStrike,
                              double wood, double stone, double fortification, java.util.Set<String> fortificationBlocks,
                              int orderTicks) {
        public static RamSettings defaults() {
            return new RamSettings(0.25, 2.5, 7.0, 4, 0.5, 0.25, 0.1,
                java.util.Set.of("IRON_DOOR", "IRON_BARS", "IRON_TRAPDOOR", "OBSIDIAN", "CRYING_OBSIDIAN",
                    "IRON_BLOCK", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL"), 3600);
        }

        public double strength(final RamClass type) {
            if (type == null) return 0;
            return switch (type) {
                case WOOD -> this.wood;
                case STONE -> this.stone;
                case FORTIFICATION -> this.fortification;
            };
        }

        /** Strikes needed to break one block of this class (help texts, tests). */
        public int strikes(final RamClass type) {
            final double s = this.strength(type);
            return s <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1.0 / s - 1e-9);
        }
    }

    /**
     * Class of a block for the ram; null = unbreakable (bedrock, barrier). Axe blocks and very soft blocks count as wood,
     * listed and hard blocks (hardness of at least 3: deepslate masonry, iron, obsidian) as fortifications, the rest as stone.
     */
    public static RamClass ramClass(final String material, final boolean axeTool, final float hardness,
                                    final java.util.Set<String> fortificationBlocks) {
        if (material == null || !Float.isFinite(hardness) || hardness < 0) return null;
        if (fortificationBlocks != null && fortificationBlocks.contains(material)) return RamClass.FORTIFICATION;
        if (axeTool || hardness < 1.0f) return RamClass.WOOD;
        if (hardness >= 3.0f) return RamClass.FORTIFICATION;
        return RamClass.STONE;
    }

    /** Accumulated wear of a block after one more strike; the block breaks once it reaches 1. */
    public static double ramDamage(final double accumulated, final RamClass type, final RamSettings settings) {
        if (type == null || settings == null) return accumulated;
        return Math.max(0, accumulated) + Math.max(0, settings.strength(type));
    }

    public static boolean ramBreaks(final double accumulated) {
        return accumulated >= 1.0 - 1e-9;
    }

    /** Damage dealt to a ram crew: fire first (a flaming arrow still burns), then arrows. */
    public static double ramIncoming(final double damage, final boolean projectile, final boolean fire,
                                     final RamSettings settings) {
        if (!Double.isFinite(damage) || damage <= 0 || settings == null) return damage;
        if (fire) return damage * Math.max(0, settings.fireMultiplier());
        if (projectile) return damage * Math.max(0, settings.arrowMultiplier());
        return damage;
    }

    /** Cardinal facing {dx, dz} from the crew towards the target (walls and gates are axis-aligned). */
    public static int[] ramFacing(final double dx, final double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return new int[] {dx >= 0 ? 1 : -1, 0};
        return new int[] {0, dz >= 0 ? 1 : -1};
    }

    public enum RamPlace { APPROACH, IN_POSITION, UNREACHABLE }

    /**
     * Where the crew stands relative to the struck block: {@code dx, dz} = block centre minus crew, {@code dy} = block y
     * minus crew feet. The head reaches blocks from the ground up to two blocks above it.
     */
    public static RamPlace ramPlace(final double dx, final double dz, final double dy, final int[] facing, final double reach) {
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || !Double.isFinite(dy) || dy < -0.01 || dy > 2.01) {
            return RamPlace.UNREACHABLE;
        }
        final double along = dx * facing[0] + dz * facing[1];
        final double lateral = Math.abs(dx * facing[1] - dz * facing[0]);
        return along >= reach - 0.9 && along <= reach + 0.9 && lateral <= 1.0 ? RamPlace.IN_POSITION : RamPlace.APPROACH;
    }

    /**
     * Blocks one strike may hit, as {lateral, up-from-feet} offsets in the plane of the struck block: three wide, from
     * the ground to two blocks up, nearest to the head (lateral 0, one block up) first.
     */
    public static java.util.List<int[]> ramFace() {
        return java.util.List.of(new int[] {0, 1}, new int[] {0, 0}, new int[] {0, 2}, new int[] {-1, 1}, new int[] {1, 1},
            new int[] {-1, 0}, new int[] {1, 0}, new int[] {-1, 2}, new int[] {1, 2});
    }

    /** The impact frame of the {@code uderzenie} clip, in ticks after the clip starts. */
    public static int ramImpactDelay(final double releaseSeconds) {
        if (!Double.isFinite(releaseSeconds)) return 0;
        return (int) Math.max(0, Math.min(20, Math.round(releaseSeconds * 20)));
    }

    public static Ammo parseAmmo(final String id) {
        for (final Ammo ammo:Ammo.values()) if (ammo.id().equals(id)) return ammo;
        return null;
    }

    public static boolean accepts(final String type, final Ammo ammo) {
        final Engine engine=engine(type);
        return engine!=null && !engine.ram() && ammo!=null && (engine.cannon()?ammo!=Ammo.STONE:ammo==Ammo.STONE);
    }

    /** Missing, hand-edited and incompatible saved selections never turn a stone thrower into a cannon. */
    public static Ammo ammunition(final String type, final String saved) {
        final Ammo chosen=parseAmmo(saved);
        if (accepts(type,chosen)) return chosen;
        final Engine engine=engine(type);
        return engine==null || engine.ram()?null:engine.cannon()?Ammo.BALL:Ammo.STONE;
    }

    public static boolean fitsVolley(final int flights, final int capacity, final Ammo ammo) {
        return ammo!=null && flights>=0 && flights<=capacity-ammo.pellets();
    }

    public static boolean enemyCountry(final UUID own, final UUID target, final boolean war) {
        return own!=null && target!=null && !own.equals(target) && war;
    }

    public static boolean registerVictim(final java.util.Set<UUID> hit, final UUID victim) {
        return victim!=null && hit.size()<5 && hit.add(victim);
    }

    public static boolean enemySiege(final UUID attacker, final UUID owner, final UUID siegeAttacker,
                                    final UUID siegeDefender, final boolean war, final long endsAt, final long now) {
        return attacker != null && owner != null && !attacker.equals(owner) && war && now < endsAt
            && attacker.equals(siegeAttacker) && owner.equals(siegeDefender);
    }

    public static boolean ready(final long now, final long nextAttack, final int flights, final int capacity,
                                final boolean attached, final double distanceSq, final double range) {
        return attached && now >= nextAttack && flights < capacity && distanceSq >= 4
            && Double.isFinite(distanceSq) && Double.isFinite(range) && range > 0 && distanceSq <= range * range;
    }

    /** Stable parabolic flight: starts and ends on the selected points; never alters the endpoint. */
    public static double arc(final double progress, final double height) {
        return 4 * height * progress * (1 - progress);
    }

    public static boolean sameImpactChunk(final int impactX, final int impactZ, final int blockX, final int blockZ) {
        return (impactX >> 4) == (blockX >> 4) && (impactZ >> 4) == (blockZ >> 4);
    }
}
