<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Firmy — firmy graczy MEDIVALRPG

Plugin Paper 26.3 (Java 25), `depend: Kraje, Skarbiec`. Cechy, kompanie kupieckie, manufaktury i domy handlowe ze wspólnym
kontem w Skarbcu. Projekt i decyzje: `PROPOZYCJA.md` (zatwierdzone 2026-10-29, z korektą: kredyty ograniczone).

**Zasada nadrzędna:** firma niczego nie tworzy ani nie niszczy. Każdy ruch pieniędzy to przelew między istniejącymi kontami
(`SkarbiecApi.transfer` albo atomowy przelew zbiorczy Skarbca). Suma 100 000 000 md się nie zmienia.

## Etapy (osobne jary: `./gradlew build -Pstage=1|2|3 -PbuildProfile=<katalog>`)
| Etap | Zawartość | Dane |
|---|---|---|
| 1 | założenie (zgoda kraju, opłata, kapitał), konto `firma:<id>`, role, udziały, pensje, dywidendy, podatek od przychodu, sklepy firmowe (Skarbiec), magazyn z dziennikiem, uśpienie i likwidacja, GUI, `/firma kraj`, ranking, panel `#/firmy` | `firmy.json`, `zamiary.json` |
| 2 | kontrakty z depozytem i wadium (gracz/firma/kraj), spory w panelu, zlecenia hurtowe władców NPC, kredyty ograniczone | `kontrakty.json` |
| 3 | manufaktury: warsztaty i robotnicy NPC z Ludnosci, przetwarzanie prawdziwych surowców, pensje do obywateli | `manufaktury.json` |

Dane etapów 2 i 3 są w osobnych plikach, więc cofnięcie jara do wcześniejszego etapu ich nie nadpisze.

## Konta w Skarbcu
- `firma:<id>` — konto firmy; `firma-wniosek:<id>` — depozyt wniosku (opłata + kapitał do decyzji kraju);
- `firma-kontrakt:<id>` — depozyt kontraktu (zapłata + wadium); `firma-kredyt:<id>` — depozyt kredytu (pieniądze pożyczkodawcy do wypłaty,
  przy kredycie Skarbu Świata także spłaty do zwrotu do rezerwy).
- Powody w księdze zaczynają się od `Firmy:` → kategoria „Firmy” w Skarbie Świata.

## Etap 1 — zasady (config.yml)
- **Założenie:** `/firma zaloz <cech|kompania|manufaktura> <nazwa>` na ziemi kraju siedziby. 25 zł opłaty (do skarbca kraju) + 25 zł
  kapitału trafia najpierw do depozytu wniosku. Kraj NPC: zgoda od razu (reputacja ≥ „Obcy” w Wladcy, kraj zakładającego nie jest
  w wojnie z nim). Kraj gracza: Władca/Doradca zatwierdza (`/firma kraj`, klik), 72 h, odrzucenie/wygaśnięcie = pełny zwrot.
  Władca zakładający w swoim kraju — od razu. Manufaktura wymaga epoki 2 (Postep). Dom handlowy tylko przez `/firma awans`
  (cech/kompania z obrotem 30 dni ≥ 5000 zł).
- **Nazwa** 3–24 znaki, unikalna (bez wielkości liter i polskich znaków), filtr słów, po likwidacji zastrzeżona 30 dni.
- **Ludzie:** mistrz (1), wspólnicy (udziały), czeladnicy. Mistrz najwyżej 1 firmy, członkostwo w najwyżej 3. Każdy członek:
  ≥ 5 h gry i konto ≥ 3 dni (alty). Udziały zawsze 100 %, zmiana tylko za zgodą obu stron (`/firma udzialy <gracz> <%>` +
  `/firma udzialy przyjmij`); udziałowca nie można wyrzucić; odchodzący oddaje udziały mistrzowi.
- **Pieniądze:** `/firma wplac` (kapitał, bez podatku); podatek od każdego przychodu (sklepy, kontrakty) pobierany w chwili wpływu:
  domyślnie 5 %, Władca ustala 0–20 % (`/firma kraj podatek`), kraje NPC stałe 5 %. Pensje do 50 zł/dzień codziennie o 12:00
  (najpierw zaległości). Dywidenda tylko według udziałów, co do miedziaka (metoda największych reszt); ponad 25 % salda albo
  200 zł dziennie czeka 24 h z powiadomieniem wspólników. Brak „wypłaty dla jednej osoby”.
- **Niewypłacalność:** 3 dni bez pensji = niewypłacalna, 7 dni = likwidacja przymusowa. **Uśpienie** po 30 dniach bez aktywności
  (sklepy stoją), likwidacja po 60.
- **Likwidacja** (dobrowolna: ponad 50 % udziałów głosuje `/firma likwiduj`): 1) otwarte kontrakty (etap 2), 2) pożyczkodawca
  (etap 2), 3) zaległe pensje proporcjonalnie, 4) reszta udziałowcom według udziałów — jednym atomowym przelewem zbiorczym;
  niespłacone zaległości przepadają. Były mistrz ma dostęp do skrzyń 7 dni, potem sklepy firmy są usuwane, a magazyn zwolniony.
- **Sklepy firmowe:** zwykły `[Sklep]` na ziemi kraju siedziby + `/firma sklep` (patrząc na niego; „Kupię” tylko mistrz). Utarg na
  konto firmy minus podatek, „Kupię” płaci z konta firmy. Skrzynię otwierają ludzie firmy, rozbiera mistrz/wspólnik. Ludzie firmy
  nie handlują z własną firmą przez jej sklep. Sklep stoi, gdy firma jest uśpiona, bez siedziby, poza krajem siedziby albo
  zawieszona przez Władcę w czasie wojny (mistrz z kraju wroga: `/firma kraj zawies`).
- **Magazyn:** `/firma magazyn` (patrząc na skrzynię na ziemi siedziby) — otwierają tylko ludzie firmy, chroniony przed lejkami,
  wybuchami, tłokami i ogniem; dziennik „kto co wyjął/włożył” (zamknięcie skrzyni porównuje zawartość).
- **Kraje gracza:** członek firmy, który nie jest obywatelem kraju siedziby, może otwierać skrzynie swojej firmy na jego ziemi
  (zagraniczne kantory). Inne ograniczenia Kraje (stawianie bloków) bez zmian.
- **Awaria w połowie:** zamiar (`zamiary.json`, fsync) → przelew → potwierdzenie banku (`bankDurable`) i zapis firm → usunięcie
  zamiaru. Zamiar po restarcie = firma zamrożona do audytu (`/firma admin info`, `/firma admin odmroz`). Wnioski rozlicza saldo
  własnego depozytu (bez zamrażania).
- **Alty:** salt + SHA-256 adresów przy wejściu (także z API Straznika, jeśli udostępni adresy); ostrzeżenie o wspólnym adresie
  członków tylko dla WorldSteward (`/firma admin alty`, panel). Surowych adresów nie zapisujemy.

## Komendy (`/firma`, alias `/kompania`)
`/firma` (GUI; bez firmy: okno startowe), `start`, `zlecenia`, `zrzutka`, `pomoc`, `zaloz`, `wniosek [wycofaj]`, `info [firma]`, `lista`, `ranking [obrot|majatek|ludzie|kontrakty]`, `wybierz <firma>`,
`wplac`, `dywidenda <kwota>|anuluj`, `pensja`, `zapros`, `przyjmij`, `odrzuc`, `wyrzuc`, `opusc`, `rola`, `udzialy`, `mistrz`,
`sklep [usun]`, `sklepy`, `magazyn [usun|dziennik]`, `ksiega [strona]`, `herb`, `kolor`, `siedziba`, `awans`, `likwiduj`,
`kraj` (`zatwierdz|odrzuc <wniosek>`, `podatek <%>`, `zawies|odwies <firma>`, `przyjmij <firma>`),
`admin` (`info|zamroz|odmroz|likwiduj … potwierdz|anuluj|zatwierdz|odrzuc|alty|sklep usun|przeladuj`), uprawnienie `firmy.admin`.
`/kraj firmy` z propozycji jest dostępne jako `/firma kraj` (bez zmian w pluginie Kraje).

## Etap 2 — kontrakty, hurt władców NPC, kredyty (`kontrakty.json`)
- **Kontrakt na towar:** zleceniodawca (gracz, firma przez mistrza, kraj przez Władcę) patrzy na skrzynię odbiorczą:
  `/firma kontrakt zamow[-firma|-kraj] <firma|wszyscy> <przedmiot> <ilość> <cena> <godziny> [wadium]` — pełna zapłata od razu w depozycie
  `firma-kontrakt:<id>`. Firma przyjmuje (`/firma kontrakt przyjmij <id>`, mistrz/wspólnik) i wpłaca wadium (≤ 50 % ceny). Towar oddaje
  się przy skrzyni: `/firma kontrakt oddaj <id>` przenosi PRAWDZIWE przedmioty (zwykłe, bez nazwy i zaklęć) z ekwipunku do skrzyni, także
  częściami. Po komplecie: zapłata z depozytu jako przychód firmy (podatek kraju siedziby) i zwrot wadium — jednym przelewem zbiorczym.
- **Usługa:** `/firma kontrakt usluga[-firma|-kraj] <firma|wszyscy> <cena> <godziny> <wadium> <opis>`; wykonawca zgłasza `oddaj`,
  zleceniodawca `potwierdz` albo `spor`; bez odpowiedzi zapłata zwalnia się po 72 h.
- **Termin minął:** dostarczona część płatna proporcjonalnie, reszta ceny i wadium wracają do zleceniodawcy. Oferta bez wykonawcy
  wygasa po 72 h (zwrot). **Spór** (`/firma kontrakt spor <id> <powód>`): depozyt zamrożony, decyduje WorldSteward w panelu
  (`firmy.dispute.resolve`: client / contractor / split) albo admin `/firma spor <id> <client|contractor|split>`.
- **Zlecenia hurtowe władców NPC** (`/firma hurt`): raz dziennie do 2 ofert na kraj NPC z puli towarów (`hurt.towary`). Pieniądze nie
  ruszają się, dopóki firma nie przyjmie oferty — wtedy skarbiec NPC wpłaca nagrodę do depozytu, w tych samych limitach co zlecenia
  Wladcy (3 % skarbca na zlecenie, 10 % dziennie łącznie ze zleceniami graczy; most `firmyOrderBudget`). Towar oddaje się w stolicy
  (do 2 chunków), zapłata trafia na konto firmy (minus podatek), reputację u kraju dostaje gracz, który oddał.
- **Kredyty** (`/firma kredyt`): limit = min(2 × saldo firmy, 50 % przychodu z 30 dni, 5000 zł z configu); odsetki ≤ 10 % za cały okres
  7–30 dni; jeden kredyt naraz; firma z kredytem nie pożycza dalej, a członek nie pożycza własnej firmie. Pożyczkodawca: gracz
  (`/firma kredyt oferuj <firma> <kwota> <%> <dni>`) albo firma (`oferuj-firma`) — pieniądze leżą w depozycie `firma-kredyt:<id>` do
  przyjęcia przez mistrza (`/firma kredyt przyjmij <id>`), odrzucenie/wycofanie/wygaśnięcie (72 h) zwraca je. Skarb Świata
  (`/firma kredyt skarb <kwota> <%> <dni> <cel>`) — tylko po RĘCZNEJ zgodzie WorldSteward w panelu `#/skarbswiata`
  (`world.allocate-firm-loan` z rezerwy do depozytu); spłaty czekają w depozycie, właściciel zwraca je do rezerwy przyciskiem
  (`world.return-firm-loan`). Spłata automatyczna: 50 % każdego przychodu netto, reszta z salda w terminie; dług jest zarezerwowany
  (dywidendy tylko ponad dług, pensje płacone normalnie). 7 dni po terminie — likwidacja przymusowa; w likwidacji pożyczkodawca
  jest pierwszy po depozytach kontraktów (przed zaległymi pensjami). Wszystko w księdze z powodem `Firmy: …`.

## Etap 3 — manufaktury (`manufaktury.json`)
- Tylko typ manufaktura, kraj siedziby w epoce 2. `/firma warsztat zaloz <przepis>` patrząc na stanowisko (stół, piec, wędzarnia,
  krosno) na ziemi siedziby; `/firma warsztat wejscie|wyjscie <id>` patrząc na skrzynie do 6 bloków od stanowiska (stają się
  magazynem firmy: ochrona + dziennik). Przepisy w proporcjach vanilla (chleb, deski, papier, wełna z nici, sztabki żelaza/złota, węgiel
  drzewny, cegły, szkło, wędzone mięso); wytapianie spala prawdziwy węgiel (1 sztuka = 8 partii).
- `/firma robotnik zatrudnij <warsztat>`: dorosły obywatel kraju siedziby odchodzi z ludności (`LudnoscApi.conscript`, najbliższy
  wczytany; nowszy Ludnosc może podać `hireWorker(UUID, Location)` tylko dla bezrobotnych) i staje przy warsztacie jako Mannequin
  (nie wieśniak — Ludnosc go nie adoptuje). Do 2 na warsztat, firma 2/4/8 zależnie od przychodu z 30 dni. Pensja 3 zł dziennie →
  `ludnosc:obywatele_<kraj>`; brak pieniędzy, niewypłacalność albo likwidacja = robotnik wraca do kraju (`returnCitizen`).
- Praca co 5 s: wejście → wyjście, pełna skrzynia wyjścia albo brak surowców zatrzymuje pracę przed pobraniem czegokolwiek. Gdy
  chunk nie jest wczytany, czas pracy się odkłada (najwyżej 60 min) i jest nadrabiany po wczytaniu (do 16 partii na takt).
- Przed cofnięciem etapu 3: `/firma robotnik zwolnij-wszystkich` (admin) — obywatele wracają do krajów, ciała robotników znikają.
- **Proponowana łatka Ludnosc** (nie wprowadzona — inny agent pracuje w `ludnosc/`): publiczna metoda
  `Optional<String> hireWorker(UUID country, Location near)` w `LudnoscPlugin`, która wybiera dorosłego obywatela BEZ pracy
  (`life()/work()`), usuwa go jak `conscript` i zwraca klucz zawodu. Firmy wołają ją refleksją, jeśli istnieje.

## Poprawki 2026-10-31 (pakiet `deploy-firmy-fix-20261031/`)
- `/firma` bez firmy = okno „Firmy — jak zacząć” (lista warunków ✔/✘ dla miejsca, w którym stoisz), `/firma start`;
  `/firma zaloz` bez argumentów = przewodnik zamiast błędu niepełnej komendy.
- **Zrzutka** (wspólne założenie, „udziały według wkładu założycieli” z PROPOZYCJA): `/firma zrzutka zaloz <typ> <wkład> <nazwa>`,
  `dolacz <zrzutka> <kwota>`, `wycofaj`; stan wniosku `ZBIORKA`, depozyt `firma-wniosek:<id>`, wkłady w `contributions`
  (kopia w `zrzutki.json` na wypadek zapisu `firmy.json` przez starszy jar), zapis w locie `pending` (`+uuid|kwota`/`-uuid|kwota`)
  rozliczany po restarcie z salda depozytu; zwroty dokładnie według wkładów; config `zalozenie.zrzutka.*`.
  Admin: `/firma admin zrzutki`.
- **Tablica zleceń** `/firma zlecenia` i zakładka „Zlecenia do wzięcia” (najbliższe pierwsze, miejsce dowozu, zapłata po podatku,
  klik = przyjmij); klikalna zakładka Kontrakty.
- Hurt: `hurt.mnoznik-stawek` (domyślnie 5 — decyzja użytkownika 2026-10-31), `hurt.uzupelnianie-minut` (60), nieprzyjęte oferty
  dostają nową stawkę od razu, przy mniejszym skarbcu dwór zamawia tyle, ile zapłaci (min. połowę).
- Zamrożona firma nie nalicza zaległości i nie jest likwidowana (także za kredyt po terminie).

## Integracje
- Skarbiec (refleksja, `SkarbiecPlugin`): `transferBatch`, `bankDurable`, `registerShopAccounts`, `shopInfoAt`, `shopAssign`,
  `shopsOfAccount`, `shopsRemoveAccount`, `worldTreasury` (etap 2). Starszy Skarbiec: brak sklepów firmowych, przelewy zbiorcze
  pojedynczo z kompensacją.
- Panel (refleksja z pluginu Panel): `FirmyPlugin.firmyPanel(UUID, metoda, parametry)` — `firmy.list`, `firmy.get`, `firmy.admin`,
  `firmy.admin.freeze` (+ etap 2: `firmy.contracts`, `firmy.disputes`, `firmy.dispute.resolve`, `firmy.loans`, `firmy.world.loans`,
  `firmy.world.loanDecide`, `firmy.world.loanSweep`). Publicznie bez salda, członek widzi księgę, WorldSteward wszystko.
- Wladcy (refleksja): reputacja (`firmyReputation` albo pole `quests`), etap 2: budżet i reputacja zleceń hurtowych.
- Postep: epoka manufaktury. Straznik: adresy (opcjonalnie).

## Testy
`./gradlew test` (JUnit, czysta Java: TestKit z bankiem zamkniętej puli). Paper: `../verify-firmy-20261029/run.py` (boty protokołu).

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
