// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.text.Normalizer;
import java.util.Locale;

/** Small text helpers shared by config parsing and matching (pure Java). */
public final class Texts {

    private Texts() {
    }

    /** Lower case, Polish letters folded to ASCII ("Kościół" → "kosciol"), spaces/dashes → '_'. */
    public static String normalize(final String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().toLowerCase(Locale.ROOT).replace('ł', 'l');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replace(' ', '_').replace('-', '_');
    }

    /** {@code minecraft:farmer} → {@code farmer}. */
    public static String stripNamespace(final String key) {
        if (key == null) {
            return "";
        }
        final int colon = key.indexOf(':');
        return normalize(colon >= 0 ? key.substring(colon + 1) : key);
    }
}
