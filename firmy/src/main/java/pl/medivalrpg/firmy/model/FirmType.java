// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** Kind of company. Decides limits and what it unlocks (contracts, workshops). */
public enum FirmType {
    CECH("cech", "Cech", "rzemieślnicy jednego fachu"),
    KOMPANIA("kompania", "Kompania kupiecka", "handel dalekosiężny"),
    MANUFAKTURA("manufaktura", "Manufaktura", "warsztat z najemnymi robotnikami (Renesans)"),
    DOM_HANDLOWY("dom_handlowy", "Dom handlowy", "duży kupiec hurtowy (tylko przez awans)");

    private final String key;
    private final String displayName;
    private final String description;

    FirmType(final String key, final String displayName, final String description) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
    }

    public String key() {
        return this.key;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    /** Can be chosen in {@code /firma zaloz} (the trading house is only reached by promotion). */
    public boolean foundable() {
        return this != DOM_HANDLOWY;
    }

    public static Optional<FirmType> parse(final String s) {
        if (s == null) {
            return Optional.empty();
        }
        final String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replace(' ', '_').replace('-', '_');
        for (final FirmType t : values()) {
            if (t.key.equals(n) || t.name().equalsIgnoreCase(n)) {
                return Optional.of(t);
            }
        }
        if (n.startsWith("kompania")) {
            return Optional.of(KOMPANIA);
        }
        if (n.startsWith("dom")) {
            return Optional.of(DOM_HANDLOWY);
        }
        return Optional.empty();
    }
}
