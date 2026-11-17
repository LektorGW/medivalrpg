// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/** Modest training/logistics benefit: +2% per settlement level above a hamlet, capped at +10%. */
public final class SettlementBonus {
    private SettlementBonus() { }

    public static double multiplier(final int level) {
        return 1.0 + (Math.clamp(level, 1, 6) - 1) * 0.02;
    }
}
