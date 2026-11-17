// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Purpose of a ledger operation, derived from its reason text and accounts (pure Java). Reasons are written by the
 * paying plugin ({@code "Armia: żołd 12 żołnierzy"}, {@code "kupiec: skup 16x WHEAT"}), so the classification is a
 * best effort: anything unknown lands in {@code other} and the raw reason is always shown next to the category.
 */
public final class Categories {

    public static final Map<String, String> LABELS;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("tax", "Podatki");
        m.put("npcIncome", "Dochód państw NPC");
        m.put("labor", "Robocizna obywateli NPC (udział z budów)");
        m.put("citizens", "Dochody i wydatki obywateli NPC");
        m.put("wages", "Żołd");
        m.put("army", "Wojsko i rekrutacja");
        m.put("construction", "Budowy i naprawy");
        m.put("land", "Ziemia i granice");
        m.put("research", "Badania");
        m.put("trade", "Handel i sklepy");
        m.put("merchant", "Kupiec");
        m.put("transfer", "Przelewy graczy");
        m.put("treasury", "Skarbce krajów — wpłaty i wypłaty");
        m.put("cash", "Monety: wpłaty i wypłaty");
        m.put("firms", "Firmy");
        m.put("railway", "Kolej");
        m.put("caravans", "Szlaki i karawany");
        m.put("quests", "Zlecenia władców");
        m.put("tournaments", "Turnieje");
        m.put("diplomacy", "Trybuty i wojny");
        m.put("world", "Skarb Świata — przydziały");
        m.put("admin", "Korekty administracyjne");
        m.put("other", "Inne");
        LABELS = java.util.Collections.unmodifiableMap(m);
    }

    private Categories() {
    }

    public static String label(final String key) {
        return LABELS.getOrDefault(key, LABELS.get("other"));
    }

    public static String category(final String reason, final String from, final String to) {
        final String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.startsWith("robocizna")) {
            return "labor";
        }
        if (r.startsWith("cash ") || r.startsWith("bank:")) {
            return "cash";
        }
        if (r.startsWith("kupiec")) {
            return "merchant";
        }
        // Player companies: every reason written by Firmy starts with "Firmy:" (wpłaty, pensje, dywidendy, podatek, kredyty...)
        if (r.startsWith("firmy")) {
            return "firms";
        }
        if (r.startsWith("sklep") || r.startsWith("handel")) {
            return "trade";
        }
        if (r.startsWith("przelew")) {
            return "transfer";
        }
        if (r.startsWith("worldsteward") || r.contains("skarbu świata") || r.contains("skarb świata")) {
            return "world";
        }
        if (r.contains("korekta") || r.startsWith("panel:") || r.contains("admin") || r.startsWith("scenariusz")) {
            return "admin";
        }
        if (r.contains("dochód kraju npc") || r.contains("mnożnik podatku npc")) {
            return "npcIncome";
        }
        if (r.contains("podat")) {
            return "tax";
        }
        if (r.contains("obywatel") && (r.contains("dochód") || r.contains("wynagrodzenie") || r.contains("posiłk") || r.contains("oszczędności")
            || r.contains("targ") || r.contains("zakup"))) {
            return "citizens";
        }
        if (r.contains("żołd")) {
            return "wages";
        }
        if (r.contains("trybut") || r.contains("wojn") || r.contains("okup") || r.contains("kapitulac")) {
            return "diplomacy";
        }
        if (r.contains("kolej") || r.contains("pociąg") || r.contains("bilet") || isType(from, to, "railBudgets") || prefix(from, to, "kolej:")) {
            return "railway";
        }
        if (r.contains("szlak") || r.contains("karawan") || r.contains("konwó") || prefix(from, to, "szlaki:")) {
            return "caravans";
        }
        if (r.contains("turniej") || prefix(from, to, "turnieje:") || prefix(from, to, "serwer:turnieje")) {
            return "tournaments";
        }
        if (r.contains("zleceni") || r.startsWith("wladcy") || r.startsWith("władcy") || prefix(from, to, "wladcy:")) {
            return "quests";
        }
        if (r.contains("rekrut") || r.contains("przezbroj") || r.contains("amunic") || r.contains("najem") || r.startsWith("armia")) {
            return "army";
        }
        if (r.contains("budow") || r.contains("napraw") || r.contains("moderniz") || r.contains("drogi") || r.contains("wyrówn")
            || r.contains("rozbudow") || r.contains("niwel") || r.contains("odbudow")) {
            return "construction";
        }
        if (r.contains("claim") || r.contains("chunk") || r.contains("ziemi") || r.contains("utrzymanie") || r.contains("teren")) {
            return "land";
        }
        if (r.contains("badan") || r.contains("postęp") || r.contains("postep") || r.contains("odkry")) {
            return "research";
        }
        if (r.contains("skarbca") || r.contains("skarbiec")) {
            return "treasury";
        }
        return "other";
    }

    /** Plugin that wrote the operation (reason prefix, then account namespaces). */
    public static String plugin(final String reason, final String from, final String to) {
        final String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.startsWith("robocizna")) {
            return "Skarbiec";
        }
        if (r.startsWith("cash ") || r.startsWith("bank:") || r.startsWith("kupiec") || r.startsWith("sklep") || r.startsWith("przelew")
            || r.startsWith("worldsteward") || r.startsWith("api:") || r.startsWith("handel")) {
            return "Skarbiec";
        }
        if (r.startsWith("panel")) {
            return "Panel";
        }
        if (r.startsWith("ludnosc") || r.startsWith("ludność")) {
            return "Ludnosc";
        }
        if (r.startsWith("armia") || r.contains("dochód kraju npc") || r.contains("mnożnik podatku npc")) {
            return "Armia";
        }
        if (r.startsWith("kraje")) {
            return "Kraje";
        }
        if (r.startsWith("kolej") || r.contains("obsługa kolei") || r.contains("obsługi kolei") || prefix(from, to, "kolej:")) {
            return "Kolej";
        }
        if (r.startsWith("szlaki") || prefix(from, to, "szlaki:")) {
            return "Szlaki";
        }
        if (r.startsWith("turniej") || prefix(from, to, "turnieje:") || prefix(from, to, "serwer:turnieje")) {
            return "Turnieje";
        }
        if (r.startsWith("wladcy") || r.startsWith("władcy") || prefix(from, to, "wladcy:")) {
            return "Wladcy";
        }
        if (r.startsWith("postęp") || r.startsWith("postep")) {
            return "Postep";
        }
        if (r.startsWith("zycie") || r.startsWith("życie")) {
            return "Zycie";
        }
        if (r.startsWith("interakcje")) {
            return "Interakcje";
        }
        final int colon = r.indexOf(':');
        if (colon > 1 && colon < 24 && r.substring(0, colon).chars().allMatch(Character::isLetter)) {
            final String p = reason.substring(0, colon);
            return Character.toUpperCase(p.charAt(0)) + p.substring(1);
        }
        return "Inne";
    }

    private static boolean prefix(final String from, final String to, final String prefix) {
        return from != null && from.startsWith(prefix) || to != null && to.startsWith(prefix);
    }

    private static boolean isType(final String from, final String to, final String type) {
        return from != null && AccountTypes.type(from).equals(type) || to != null && AccountTypes.type(to).equals(type);
    }
}
