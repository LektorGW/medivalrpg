// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** Role of a player in a company. */
public enum Role {
    /** Owner: everything (roles, salaries, dividends, shops, seat, liquidation). Exactly one. */
    MISTRZ("mistrz", "Mistrz", 3),
    /** Partner: may hold shares, sees the ledger, manages shops and the warehouse. */
    WSPOLNIK("wspolnik", "Wspólnik", 2),
    /** Journeyman: opens company chests and restocks shops, gets a salary, never pays money out. */
    CZELADNIK("czeladnik", "Czeladnik", 1);

    private final String key;
    private final String displayName;
    private final int weight;

    Role(final String key, final String displayName, final int weight) {
        this.key = key;
        this.displayName = displayName;
        this.weight = weight;
    }

    public String key() {
        return this.key;
    }

    public String displayName() {
        return this.displayName;
    }

    public boolean atLeast(final Role other) {
        return this.weight >= other.weight;
    }

    public static Optional<Role> parse(final String s) {
        if (s == null) {
            return Optional.empty();
        }
        final String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        for (final Role r : values()) {
            if (r.key.equals(n) || r.name().equalsIgnoreCase(n)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
