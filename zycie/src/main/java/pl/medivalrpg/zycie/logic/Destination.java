// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/** Where the plan wants a citizen to be right now. {@link #NONE} = leave the villager to vanilla. */
public enum Destination {
    NONE,
    WORKPLACE,
    MARKET,
    TAVERN,
    BELL,
    CHURCH,
    FESTIVAL,
    PROTEST,
    GATE,
    /** Home of the citizen (its bed from Ludnosc, PDC {@code ludnosc:home}) in the evening. */
    HOME,
    /** Hide at home (siege / enemy nearby) — done by vanilla's bell reaction; the plan only stops other steering. */
    SHELTER
}
