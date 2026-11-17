// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Locale;

/**
 * Output of mines (pure, unit tested). A mine employs miners up to its slots (more with every level); every miner
 * yields ore, stone and a little precious metal per day, multiplied by the mine level, the tools (a smithy and the
 * epoch) and the mood. The gallery is driven forward by the same miners: steps per miner and real hour of work.
 *
 * @param orePerMiner      ore per miner and day at level 1 with basic tools
 * @param stonePerMiner    stone per miner and day
 * @param preciousPerMiner precious metal ("kruszce") per miner and day
 * @param levelBonus       +share per mine level above 1
 * @param slotsBase        miners a level 1 mine employs
 * @param slotsPerLevel    extra miners per level above 1
 * @param smithyBonus      tools from a smithy (+share)
 * @param eraBonus         better tools per epoch above the first (+share)
 * @param stepsPerMinerHour gallery steps per miner and real hour of work
 * @param sellShare        share of the daily output sold to the treasury at once (the rest stays in the store)
 */
public record MineYield(double orePerMiner, double stonePerMiner, double preciousPerMiner, double levelBonus, int slotsBase,
                        int slotsPerLevel, double smithyBonus, double eraBonus, double stepsPerMinerHour, double sellShare) {

    /** Daily output. */
    public record Output(long ore, long stone, long precious) {

        public Output plus(final Output o) {
            return new Output(this.ore + o.ore, this.stone + o.stone, this.precious + o.precious);
        }

        public boolean isEmpty() {
            return this.ore == 0 && this.stone == 0 && this.precious == 0;
        }
    }

    public static final Output NONE = new Output(0, 0, 0);

    public MineYield {
        orePerMiner = Math.max(0, orePerMiner);
        stonePerMiner = Math.max(0, stonePerMiner);
        preciousPerMiner = Math.max(0, preciousPerMiner);
        levelBonus = Math.max(0, levelBonus);
        slotsBase = Math.max(1, slotsBase);
        slotsPerLevel = Math.max(0, slotsPerLevel);
        smithyBonus = Math.max(0, smithyBonus);
        eraBonus = Math.max(0, eraBonus);
        stepsPerMinerHour = Math.max(0, stepsPerMinerHour);
        sellShare = Math.max(0, Math.min(1, sellShare));
    }

    public static MineYield defaults() {
        return new MineYield(6, 10, 0.4, 0.3, 2, 2, 0.25, 0.15, 4, 0.6);
    }

    /** Miners a mine of this level employs. */
    public int slots(final int level) {
        return this.slotsBase + this.slotsPerLevel * (Math.max(1, Math.min(5, level)) - 1);
    }

    public double levelFactor(final int level) {
        return 1 + this.levelBonus * (Math.max(1, Math.min(5, level)) - 1);
    }

    /** Tool quality: 1 + smithy bonus (if the country has a smithy) + era bonus per epoch above the first. */
    public double toolFactor(final boolean smithy, final int era) {
        return 1 + (smithy ? this.smithyBonus : 0) + this.eraBonus * Math.max(0, era - 1);
    }

    /** Daily output of one mine. {@code miners} is capped at the slots of the level. */
    public Output daily(final int miners, final int level, final double tools, final double mood) {
        final int n = Math.max(0, Math.min(miners, this.slots(level)));
        if (n == 0) {
            return NONE;
        }
        final double f = n * this.levelFactor(level) * Math.max(0, tools) * Math.max(0, mood);
        return new Output(Math.round(this.orePerMiner * f), Math.round(this.stonePerMiner * f),
            (long) Math.floor(this.preciousPerMiner * f));
    }

    /** Gallery steps worked off by {@code miners} in {@code millis} of work (fractional). */
    public double steps(final int miners, final long millis) {
        return Math.max(0, miners) * this.stepsPerMinerHour * Math.max(0, millis) / 3_600_000.0;
    }

    /** Part of {@code amount} sold at once. */
    public long sold(final long amount) {
        return (long) Math.floor(Math.max(0, amount) * this.sellShare);
    }

    /** The pickaxe miners carry: stone without a smithy, iron with one (or from the Renaissance). */
    public static String pickaxe(final boolean smithy, final int era) {
        return smithy || era >= 2 ? "minecraft:iron_pickaxe" : "minecraft:stone_pickaxe";
    }

    /**
     * Value of an ore block dug out of the gallery: {@code [ore, precious]}. Coal/copper 1 ore, iron/redstone/lapis
     * 2 ore, gold 2 precious, diamond/emerald 5 precious; deepslate variants alike.
     */
    public static int[] oreValue(final String blockId) {
        if (blockId == null || !blockId.toLowerCase(Locale.ROOT).endsWith("_ore")) {
            return new int[] {0, 0};
        }
        final String id = blockId.toLowerCase(Locale.ROOT);
        if (id.contains("diamond") || id.contains("emerald")) {
            return new int[] {0, 5};
        }
        if (id.contains("gold")) {
            return new int[] {0, 2};
        }
        if (id.contains("iron") || id.contains("redstone") || id.contains("lapis")) {
            return new int[] {2, 0};
        }
        return new int[] {1, 0};
    }
}
