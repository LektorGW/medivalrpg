// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.money;

import java.util.Locale;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** The three coin denominations, from the smallest. Pure Java (no Bukkit), safe in tests. */
public enum Denomination {
    COPPER(1, "md"),
    SILVER(SkarbiecApi.COPPER_PER_SILVER, "sr"),
    GOLD(SkarbiecApi.COPPER_PER_GOLD, "zł");

    private final long value;
    private final String symbol;

    Denomination(final long value, final String symbol) {
        this.value = value;
        this.symbol = symbol;
    }

    /** Value of one coin in copper. */
    public long value() {
        return this.value;
    }

    public String symbol() {
        return this.symbol;
    }

    /** Id stored in the coin's persistent data ({@code copper}, {@code silver}, {@code gold}). */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public static Denomination byId(final String id) {
        if (id == null) {
            return null;
        }
        for (final Denomination d : values()) {
            if (d.id().equals(id)) {
                return d;
            }
        }
        return null;
    }
}
