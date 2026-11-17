// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.prompt;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import pl.medivalrpg.wladcy.text.TextSafety;

/**
 * What a ruler knows about the wider world (waves 13–17): era and speech style, personal union, embassies, quality of
 * the relation with the player's country, overseas bridgeheads, recent landings/naval alarms, running sieges and how
 * quest rewards are secured. Pure: built from plain values collected on the main thread, rendered as the {@code <swiat>}
 * block of the session context. Every external string is escaped with {@link TextSafety#forPrompt}.
 *
 * @param era                 era of the ruler's country, clamped to 1..2 (the server stops at the Renaissance)
 * @param eraName             Polish era name from Postep, null without Postep
 * @param playerEra           era of the player's country (0 = unknown / no country)
 * @param relationQuality     Polish word for KrajeApi#getRelationQuality with the player's country, null without one
 * @param unionPartner        name of our personal-union partner, null when none
 * @param unionMonarch        name of the shared monarch, null when none
 * @param unionWithPlayer     the union partner is the player's country
 * @param playerEmbassyHere   the player's country has an embassy in ours
 * @param ourEmbassyThere     we have an embassy in the player's country
 * @param embassiesHosted     embassies we host
 * @param embassiesAbroad     our embassies abroad
 * @param ourOutposts         our overseas pieces of land (bridgeheads) besides the capital land
 * @param theirOutposts       the player's country's overseas pieces of land, 0 without a country
 * @param navalEvents         recent landings, alarms and naval decisions of our council (plain text)
 * @param sieges              running sieges involving us (plain text)
 * @param questSecurity       how quest rewards of this player are secured (plain text), null without quests
 */
public record RealmFacts(int era, String eraName, int playerEra, String relationQuality, String unionPartner, String unionMonarch,
                         boolean unionWithPlayer, boolean playerEmbassyHere, boolean ourEmbassyThere, int embassiesHosted,
                         int embassiesAbroad, int ourOutposts, int theirOutposts, List<String> navalEvents, List<String> sieges,
                         String questSecurity) {

    public RealmFacts {
        era = Math.max(1, Math.min(2, era));
        playerEra = Math.max(0, Math.min(2, playerEra));
        navalEvents = navalEvents == null ? List.of() : List.copyOf(navalEvents);
        sieges = sieges == null ? List.of() : List.copyOf(sieges);
    }

    /** Speech of the ruler's era (the server ends at era 2; the steam railway is the only later novelty). */
    public static String eraStyle(final int era) {
        return era >= 2
            ? "Mówisz jak władca renesansowy (XV–XVII w.): dworna polszczyzna, łacińskie wtręty (np. „pro publico bono”), sejmiki, "
              + "kupcy, druk, proch i armaty, karawele. Nie używaj pojęć epoki przemysłowej ani nowoczesnej (fabryki, elektryczność, "
              + "samochody). Kolej parowa przewoźnika królewskiego to jedyna nowinka — mów o niej z podziwem lub nieufnością."
            : "Mówisz jak władca średniowieczny: „waszmość”, „jako żywo”, Bóg, rycerstwo, grody, drużyna, transportowce i katapulty. "
              + "Nie znasz prochu ani druku, nie używaj późniejszych pojęć. Kolej parowa przewoźnika królewskiego to dziwo, "
              + "które widzisz, ale słabo rozumiesz.";
    }

    /** Polish word for a relation quality name (KrajeApi RelationQuality). */
    public static String qualityWord(final String quality) {
        if (quality == null) {
            return null;
        }
        return switch (quality) {
            case "WAR" -> "wojna";
            case "NEUTRAL" -> "pokój bez traktatów";
            case "VASSALAGE" -> "wasalstwo (jeden kraj jest lennem drugiego)";
            case "ALLIANCE" -> "sojusz";
            case "UNION" -> "unia personalna — jeden wspólny władca";
            case "SAME" -> "ten sam kraj";
            default -> quality.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Separate pieces of land beyond the capital's one (4-neighbour components − 1), chunk keys as in KrajeApi. */
    public static int outposts(final Set<Long> claims) {
        if (claims == null || claims.isEmpty()) {
            return 0;
        }
        final Set<Long> seen = new HashSet<>();
        int pieces = 0;
        for (final long start : claims) {
            if (!seen.add(start)) {
                continue;
            }
            pieces++;
            final ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                final long k = queue.poll();
                final int x = (int) (k >> 32);
                final int z = (int) k;
                for (final long n : new long[] {key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1)}) {
                    if (claims.contains(n) && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return Math.max(0, pieces - 1);
    }

    private static long key(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** The {@code <swiat>} block. */
    public String render() {
        final StringBuilder b = new StringBuilder("<swiat>\n");
        b.append("Epoka twojego kraju: ").append(this.eraName == null ? (this.era >= 2 ? "Renesans" : "Średniowiecze") : TextSafety.forPrompt(this.eraName, 40))
            .append(this.playerEra > 0 && this.playerEra != this.era ? "; kraj rozmówcy jest w epoce " + (this.playerEra >= 2 ? "Renesansu" : "Średniowiecza") : "")
            .append(".\n");
        b.append(eraStyle(this.era)).append('\n');
        if (this.relationQuality != null) {
            b.append("Stosunki z krajem rozmówcy: ").append(qualityWord(this.relationQuality)).append(".\n");
        }
        if (this.unionPartner != null) {
            b.append("Unia personalna z krajem „").append(TextSafety.forPrompt(this.unionPartner, 40)).append("”, wspólny monarcha: ")
                .append(this.unionMonarch == null ? "?" : TextSafety.forPrompt(this.unionMonarch, 32))
                .append(this.unionWithPlayer ? " (to kraj rozmówcy)" : "")
                .append(". Twój kraj zachowuje ziemię, skarbiec, wojsko i prawa; to ty i twoja rada rządzicie na co dzień, monarcha jest tytularny. "
                    + "Z partnerem unii nie wolno toczyć wojny.\n");
        } else {
            b.append("Unia personalna: brak. Jeśli ktoś o nią prosi: zgadzasz się tylko z bliskim sojusznikiem, któremu ufasz, i nigdy nie oddajesz "
                + "skarbca, ziemi ani wojska — unia daje tylko wspólny tytuł.\n");
        }
        b.append("Ambasady: u ciebie ").append(this.embassiesHosted).append(", twoje za granicą ").append(this.embassiesAbroad);
        if (this.relationQuality != null) {
            b.append("; kraj rozmówcy ma u ciebie ambasadę: ").append(this.playerEmbassyHere ? "tak" : "nie")
                .append(", ty u niego: ").append(this.ourEmbassyThere ? "tak" : "nie");
        }
        b.append(". Ambasada to znak dobrej woli; bez niej wspólne przedsięwzięcia (drogi, tory) są trudniejsze.\n");
        b.append("Posiadłości zamorskie (przyczółki): twoje ").append(this.ourOutposts);
        if (this.relationQuality != null) {
            b.append(", kraju rozmówcy ").append(this.theirOutposts);
        }
        b.append(". Desant morski to wyprawa statkiem z wojskiem na brzeg wroga w czasie wojny; zdobyty brzeg zostaje przyczółkiem.\n");
        if (!this.sieges.isEmpty()) {
            b.append("Trwające oblężenia (dane):\n");
            for (final String s : this.sieges) {
                b.append("- ").append(TextSafety.forPrompt(s, 160)).append('\n');
            }
        }
        if (!this.navalEvents.isEmpty()) {
            b.append("Ostatnie sprawy morskie i desanty twojej rady (dane):\n");
            for (final String s : this.navalEvents) {
                b.append("- ").append(TextSafety.forPrompt(s, 200)).append('\n');
            }
        }
        if (this.questSecurity != null && !this.questSecurity.isBlank()) {
            b.append(TextSafety.forPrompt(this.questSecurity, 300)).append('\n');
        }
        b.append("</swiat>\n");
        return b.toString();
    }
}
