// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import pl.medivalrpg.armia.api.NpcLogCategory;

/** Search and categories of AI log lines. Pure. */
public final class LogText {

    private static final List<String> WAR = List.of("wojn", "obleze", "oblega", "obron", "zdoby", "stracon", "odbi", "kapitul",
        "atak", "kryzys", "agresor", "wrog");
    private static final List<String> DIPLOMACY = List.of("sojusz", "pokoj", "propozycj", "trybut", "wasal", "suweren", "opini",
        "nastawien", "dyplom", "wykup", "zwolni");
    private static final List<String> BUILD = List.of("budow", "budyn", "osad", "mur", "wieza", "spichlerz", "kolejk");
    private static final List<String> ARMY = List.of("rekrut", "straz", "zold", "zolnierz", "armi", "dezerc", "postaw", "pobor", "wojsk");
    private static final List<String> ECONOMY = List.of("dochod", "skarb", "podat", "budzet", "rozrost", "chunk", "mnoznik", "zajeto");

    private LogText() {
    }

    /** Lower case without Polish diacritics ({@code "Żółć" → "zolc"}). */
    public static String normalize(final String s) {
        if (s == null) {
            return "";
        }
        final String lower = s.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /** Substring search, case- and diacritics-insensitive; blank query matches everything. */
    public static boolean matches(final String text, final String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return normalize(text).contains(normalize(query.trim()));
    }

    /** Best guess of a category for a line logged without one (older entries, other plugins). */
    public static NpcLogCategory classify(final String text) {
        final String n = normalize(text);
        if (DIPLOMACY.stream().anyMatch(n::contains) && !n.contains("wojna z") && !n.startsWith("wojna")) {
            return NpcLogCategory.DIPLOMACY;
        }
        if (WAR.stream().anyMatch(n::contains)) {
            return NpcLogCategory.WAR;
        }
        if (BUILD.stream().anyMatch(n::contains)) {
            return NpcLogCategory.BUILD;
        }
        if (ARMY.stream().anyMatch(n::contains)) {
            return NpcLogCategory.ARMY;
        }
        if (ECONOMY.stream().anyMatch(n::contains)) {
            return NpcLogCategory.ECONOMY;
        }
        return NpcLogCategory.OTHER;
    }
}
