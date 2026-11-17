// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The ruler's tools. The list depends only on config ({@code allow-war}) and is sorted by name, so it is
 * byte-identical between requests (it is part of the cached prompt prefix).
 */
public final class ToolSpecs {

    public static final String CHECK_COUNTRY = "sprawdz_kraj_rozmowcy";
    public static final String PROPOSE_ALLIANCE = "zaproponuj_sojusz";
    public static final String ACCEPT_ALLIANCE = "przyjmij_sojusz";
    public static final String ACCEPT_PEACE = "przyjmij_pokoj";
    public static final String REFUSE = "odrzuc";
    public static final String PROPOSE_PEACE = "zaproponuj_pokoj";
    public static final String DEMAND_TRIBUTE = "zazadaj_trybutu";
    public static final String GIFT = "podaruj";
    public static final String END = "zakoncz_rozmowe";
    public static final String REMEMBER = "zapamietaj";
    public static final String DECLARE_WAR = "wypowiedz_wojne";
    public static final String OFFER_QUEST = "zaproponuj_zlecenie";
    public static final String CHECK_REPUTATION = "sprawdz_reputacje";
    public static final String TURN_IN_QUEST = "odbierz_zlecenie";

    /** Upper bound of any amount argument (the real limits come from config and are checked by the game). */
    public static final long MAX_AMOUNT = 100_000_000L;

    private ToolSpecs() {
    }

    public static List<ToolSpec> all(final boolean allowWar) {
        return all(allowWar, true);
    }

    /**
     * @param quests {@code zlecenia.enabled}: adds the quest and reputation tools
     */
    public static List<ToolSpec> all(final boolean allowWar, final boolean quests) {
        final List<ToolSpec> l = new ArrayList<>();
        l.add(new ToolSpec(CHECK_COUNTRY,
            "Sprawdza aktualny stan kraju rozmówcy: ziemie, wojsko, przybliżony skarbiec, sojusze, wojny i relację z twoim "
                + "krajem. Wywołaj, gdy rozmowa dotyczy siły lub bogactwa jego kraju, zanim zażądasz trybutu albo gdy nie "
                + "jesteś pewien obecnej relacji.",
            List.of()));
        l.add(new ToolSpec(PROPOSE_ALLIANCE,
            "Składa krajowi rozmówcy propozycję sojuszu; rozmówca dostaje ją do kliknięcia. Działa tylko, gdy rozmówca może "
                + "negocjować w imieniu kraju i wasze kraje są w relacji neutralnej. Użyj, gdy chcesz sojuszu, a rozmówca "
                + "go nie zaproponował.",
            List.of()));
        l.add(new ToolSpec(ACCEPT_ALLIANCE,
            "Przyjmuje propozycję sojuszu, którą kraj rozmówcy złożył wcześniej komendą w grze. Zwraca błąd, gdy takiej "
                + "propozycji nie ma — wtedy możesz sam zaproponować sojusz.",
            List.of()));
        l.add(new ToolSpec(ACCEPT_PEACE,
            "Przyjmuje propozycję pokoju, którą kraj rozmówcy złożył wcześniej komendą w grze (tylko w czasie wojny). Zwraca "
                + "błąd, gdy takiej propozycji nie ma.",
            List.of()));
        l.add(new ToolSpec(REFUSE,
            "Odrzuca oczekującą propozycję kraju rozmówcy (sojuszu albo pokoju). Użyj, gdy w rozmowie stanowczo odmawiasz.",
            List.of(ToolSpec.Param.choice("rodzaj", "Którą propozycję odrzucasz.", List.of("sojusz", "pokoj")))));
        l.add(new ToolSpec(PROPOSE_PEACE,
            "Proponuje krajowi rozmówcy pokój (tylko gdy wasze kraje są w stanie wojny); rozmówca dostaje propozycję do "
                + "kliknięcia. Gra może odmówić, jeśli wojna trwa za krótko.",
            List.of()));
        l.add(new ToolSpec(DEMAND_TRIBUTE,
            "Żąda od kraju rozmówcy trybutu ze skarbca jego kraju. Rozmówca (władca kraju) dostaje przyciski [Zapłać] i "
                + "[Odmów] — pieniądze przejdą tylko, jeśli sam kliknie. Użyj jako warunku sojuszu, pokoju, ochrony albo "
                + "zadośćuczynienia za obrazę.",
            List.of(ToolSpec.Param.integer("kwota", "Kwota w miedziakach (1 zł = 100 md). Nie więcej niż limit z kontekstu.", 1, MAX_AMOUNT))));
        l.add(new ToolSpec(GIFT,
            "Przekazuje pieniądze z twojego skarbca rozmówcy (na jego konto) albo jego krajowi (do skarbca kraju) jako DAR "
                + "lub NAGRODĘ z twojej woli. Nigdy jako zapłata za towar, usługę, zaliczkę ani „cenę” interesu z rozmowy — "
                + "towar sprzedaje się kupcowi albo przez /handel. Dzienny limit darów podaje kontekst; gra go pilnuje. "
                + "Używaj rzadko i zgodnie z charakterem.",
            List.of(ToolSpec.Param.integer("kwota", "Kwota w miedziakach (1 zł = 100 md).", 1, MAX_AMOUNT),
                ToolSpec.Param.choice("odbiorca", "„gracz” — konto rozmówcy, „kraj” — skarbiec jego kraju.", List.of("gracz", "kraj")))));
        l.add(new ToolSpec(END,
            "Kończy audiencję po twojej odpowiedzi. Użyj, gdy rozmówca się żegna, sprawa jest załatwiona albo nie chcesz go "
                + "dłużej słuchać.",
            List.of()));
        l.add(new ToolSpec(REMEMBER,
            "Zapisuje krótką notatkę o rozmówcy na przyszłe rozmowy i zmienia twoje nastawienie do niego. Tylko przy czymś "
                + "wyraźnym: obraza, groźba, kłamstwo, pochlebstwo, obietnica, ważna prośba.",
            List.of(ToolSpec.Param.string("notatka", "Jedno krótkie zdanie, np. „Obraził moją matkę”.", 3, 120),
                ToolSpec.Param.integer("zmiana_nastawienia", "Od -20 (wielka uraza) do 20 (wielka wdzięczność); 0 = bez zmiany.", -20, 20))));
        if (allowWar) {
            l.add(new ToolSpec(DECLARE_WAR,
                "Wypowiada wojnę krajowi rozmówcy (koszt płaci twój skarbiec). OSTATECZNOŚĆ: tylko zgodnie z charakterem "
                    + "(wysoka agresja, poważna obraza, odmowa trybutu po wcześniejszych groźbach) i po wcześniejszym "
                    + "ostrzeżeniu. Gra może odmówić (łagodny charakter, niedawna wojna, rozmówca bez kraju).",
                List.of(ToolSpec.Param.string("powod", "Krótki powód z przebiegu rozmowy; zostanie ogłoszony całemu królestwu, "
                    + "np. „urażony słowami posła”.", 3, 160))));
        }
        if (quests) {
            l.add(new ToolSpec(OFFER_QUEST,
                "Proponuje rozmówcy prawdziwe zlecenie z tablicy twojego kraju (gra wybiera najlepsze dostępne danego rodzaju "
                    + "i pokazuje mu warunki z przyciskami [Przyjmij] i [Odrzuć]). Użyj, gdy rozmówca szuka pracy, chce się "
                    + "wykazać albo gdy twój kraj czegoś potrzebuje. Nie wymyślaj zleceń ani nagród — obowiązuje tylko wynik.",
                List.of(ToolSpec.Param.choice("rodzaj", "„dostawa” — towar dla dworu, „surowce” — to, czego brakuje krajowi, "
                    + "„transport” — przewóz ładunku do stolicy innego kraju, „dowolne” — najlepsze dostępne.",
                    List.of("dostawa", "surowce", "transport", "dowolne")))));
            l.add(new ToolSpec(CHECK_REPUTATION,
                "Sprawdza reputację rozmówcy u twojego kraju (od Wróg do Bohater), jego wykonane, nieudane i porzucone zlecenia "
                    + "oraz zlecenia od ciebie w toku.",
                List.of()));
            l.add(new ToolSpec(TURN_IN_QUEST,
                "Odbiera od rozmówcy wykonane zlecenie (dostawę, surowce albo ładunek przywieziony do twojej stolicy). Gra sama "
                    + "sprawdza jego ekwipunek, zabiera towar i wypłaca nagrodę z depozytu. Użyj, gdy rozmówca mówi, że przyniósł "
                    + "towar albo przywiózł ładunek.",
                List.of()));
        }
        l.sort(Comparator.comparing(ToolSpec::name));
        return List.copyOf(l);
    }

    public static Optional<ToolSpec> find(final List<ToolSpec> specs, final String name) {
        return specs.stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
