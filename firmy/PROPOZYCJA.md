<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Firmy graczy — propozycja (projekt, bez implementacji)

Stan: **tylko projekt** (2026-10-29). Nic nie zostało zbudowane, wgrane ani zmienione w innych modułach.
Dokument opisuje, jak „firmy graczy”, które dziś są tylko zapowiedzią, mogą działać w świecie epok 1–2
i w zamkniętej puli 100 000 000 md. Na końcu jest lista decyzji, każda z moją rekomendacją.

---

## 1. Czym jest firma

Firma to **wspólne przedsięwzięcie kilku graczy z własnym kontem w Skarbcu**. Typ firmy służy klimatowi
i decyduje o kilku uprawnieniach:

| Typ | Realia | Co odblokowuje |
|---|---|---|
| **Cech** | rzemieślnicy jednego fachu (kowale, piekarze, cieśle) | sklepy, magazyn, pracownicy-gracze |
| **Kompania kupiecka** | handel dalekosiężny | jak cech + (później) karawany Szlaków i przewozy koleją |
| **Manufaktura** | renesansowy warsztat z najemnymi robotnikami | jak cech + (później) pracownicy NPC; **wymaga epoki 2 (Renesans) w kraju siedziby** |
| **Dom handlowy** | duży kupiec hurtowy (Fuggerowie, Hanza) | jak kompania + wyższe limity kontraktów; tylko przez awans firmy (np. obrót 30 dni ≥ 5 000 zł) |

Zakres celowo nie obejmuje banków ani kredytu. Pożyczki w zamkniętej puli łatwo zamieniają się w piramidy.
Przy braku kredytu znika też większość problemów z upadłością (p. 2).

**Założenie firmy**
- `/firma zaloz <nazwa> <typ>`, stojąc na ziemi kraju, który ma być **siedzibą**. W dziczy firm nie ma.
- **Koszt:** opłata rejestrowa trafia do **skarbca kraju siedziby**, więc władca ma powód, żeby firmy przyjmować.
  Osobno wpłaca się kapitał początkowy, który zostaje na koncie firmy.
- **Zgoda:**
  - kraj gracza: wniosek zatwierdza Władca albo Doradca, klikając [Zatwierdź] / [Odrzuć]. Wniosek jest ważny 72 h.
    Gdy zakłada sam władca kraju, zgoda jest automatyczna;
  - kraj NPC: zgoda automatyczna, jeśli zakładający ma u tego kraju reputację co najmniej „Obcy” (Wladcy) i jego kraj nie jest z nim w wojnie.
- **Nazwa** jest unikalna na serwerze, ma 3–24 znaki i przechodzi przez filtr nazw. Po likwidacji jest zastrzeżona przez 30 dni.
- **Herb i barwa:** kolor (`#RRGGBB` albo nazwa, jak w `/kraj kolor`) oraz **sztandar** z ręki, czyli zwykły sztandar vanilla
  z wzorami. Herb widać w GUI, na tabliczkach sklepów, w panelu WWW i w rankingu.
- Właściciel firmy nie musi być obywatelem kraju siedziby. Zagraniczny kantor kupiecki jest w realiach epoki czymś naturalnym.

**Ludzie w firmie**

| Rola | Kto | Uprawnienia |
|---|---|---|
| **Mistrz** (właściciel) | jedna osoba | wszystko: role, pensje, dywidendy, sklepy, siedziba, likwidacja |
| **Wspólnik** | gracz z udziałami | widzi księgę, zarządza sklepami i magazynem, dostaje dywidendę według udziałów, głosuje przy likwidacji i sprzedaży udziałów |
| **Czeladnik** (pracownik) | gracz | otwiera skrzynie firmowe i uzupełnia sklepy, dostaje pensję, nie wypłaca pieniędzy |
| **Robotnik NPC** (etap 3) | obywatel z Ludnosci | pracuje w warsztacie firmy za pensję |

Udziały: zawsze 100 %. Przy założeniu cały pakiet ma mistrz albo jest dzielony według wkładu założycieli.
Zmiana udziałów wymaga zgody obu stron. Pieniądze za udziały gracze rozliczają między sobą przelewem.

---

## 2. Pieniądze w zamkniętej puli

**Zasada nadrzędna:** firma niczego nie tworzy i niczego nie niszczy. Każdy ruch to `transfer` w Skarbcu
między istniejącymi kontami. Nigdy nie używa `deposit`/`withdraw`, które w zamkniętej puli dotykają `world:payments`.
Suma 100 000 000 md się nie zmienia.

**Konta**
- `firma:<id>` to konto firmy. Skarbiec ma już w statystykach Skarbu Świata kategorię „Firmy”,
  dziś opisaną jako „Firmy (kolej)”. Wystarczy dopisać przestrzeń nazw `firma:` i zmienić etykietę.
- `firma-kontrakt:<id>` to depozyt kontraktu (etap 2), liczony jak inne depozyty.

**Skąd firma ma pieniądze**
- kapitał i dopłaty wspólników (`/firma wplac`): przelew z konta gracza;
- utarg sklepów firmowych i zapłata za kontrakty.

**Dokąd pieniądze wypływają**

| Wypływ | Kierunek | Zasada |
|---|---|---|
| **Podatek** | → skarbiec kraju siedziby | procent od każdego **przychodu**, pobierany w chwili wpływu. Nie da się go uniknąć ani zalegać. Stawkę 0–20 % ustala władca, w krajach NPC jest stała z configu |
| **Pensje graczy** | → konto gracza | stała kwota dzienna o ustalonej godzinie, z limitem na osobę. Brak środków oznacza zaległość i powiadomienie |
| **Pensje NPC** (etap 3) | → `ludnosc:obywatele_<kraj>` | ta sama droga, którą dziś płaci kraj, więc pieniądze wracają do obiegu obywateli |
| **Dywidenda** | → wspólnicy | **tylko proporcjonalnie do udziałów**, bez wypłaty dla jednej wybranej osoby |
| **Zakupy** | → sklepy innych graczy/firm, kontrakty | zwykłe przelewy |

**Upadłość.** Ponieważ nie ma kredytu, a kontrakty są z góry zabezpieczone depozytem, firma nie może mieć długów
wobec obcych. Saldo w Skarbcu nigdy nie spada poniżej zera. Upadłość jest więc prosta:
1. **Niewypłacalność:** przez 3 dni z rzędu nie wystarcza pieniędzy na pensje. Pensje NPC są wtedy zawieszone, a robotnicy wracają do kraju.
2. Po **7 dniach** bez spłaty zaległości następuje **likwidacja przymusowa**. Likwidacja dobrowolna jest dostępna zawsze, za zgodą ponad 50 % udziałów.
3. **Kolejność podziału majątku:**
   - otwarte kontrakty: depozyty wracają do zleceniodawców według ich zasad;
   - zaległe pensje: dzielone proporcjonalnie;
   - reszta trafia do wspólników według udziałów.

   Niespłacona część zaległości przepada, bez tworzenia pieniędzy.
4. **Przedmioty:** sklepy firmowe są wyłączone. Skrzynie firmowe zostają otwarte tylko dla byłego mistrza przez 7 dni,
   potem stają się zwykłymi skrzyniami. Nazwa jest zastrzeżona na 30 dni.

**Zamrażanie pieniędzy.** Uśpiona firma z dużym saldem wyłącza pieniądze z obiegu. Proponuję mechanizm w dwóch krokach:
- przez 30 dni nikt z firmy nie wchodzi do gry ani firma nie ma obrotu: firma jest **uśpiona**, a sklepy stoją;
- po 60 dniach następuje **likwidacja automatyczna** według kolejności z punktu 3.

Pieniądze wracają do graczy-wspólników.

**Zmiana siedziby.** Kraj siedziby może upaść, oddać chunk albo zostać podbity.
- Firma ma 7 dni na przeniesienie siedziby. W tym czasie stoją sklepy, które znalazły się poza krajem siedziby.
- Nowy właściciel ziemi może firmę przyjąć bez opłaty.
- W czasie wojny władca może **zawiesić** sklepy firm, których mistrz jest obywatelem wroga. Nie ma konfiskaty pieniędzy.

---

## 3. Co firma może robić (połączenia z istniejącymi systemami)

| Funkcja | System | Etap | Uwagi |
|---|---|---|---|
| **Sklepy firmowe** | Skarbiec (sklepy na tabliczce) | **MVP** | Zwykły sklep `[Sklep]` przypisuje się do firmy przez `/firma sklep`, patrząc na skrzynię. Utarg trafia na konto firmy (minus podatek), a sklep „Kupię” płaci z konta firmy. Skrzynię otwierają mistrz, wspólnicy i czeladnicy. Ochrona przed lejkami, wybuchami i tłokami pozostaje bez zmian |
| **Magazyn firmowy** | Skarbiec/Firmy | **MVP** | Kilka chronionych skrzyń dostępnych dla ludzi firmy, z dziennikiem „kto co wyjął”, który ogranicza kradzieże wewnątrz firmy |
| **Kontrakty z depozytem** | Firmy (wzór: depozyty Wladcy) | 2 | Zleceniodawca (gracz, firma albo kraj ze skarbca) wpłaca pełną zapłatę do depozytu. Wykonawca dostarcza **prawdziwe przedmioty** do wskazanej skrzyni odbiorczej, co gra sprawdza fizycznie, i dostaje zapłatę z depozytu. Opcjonalnie wykonawca wpłaca **wadium**, które przepada na rzecz zleceniodawcy, jeśli nie dotrzyma terminu. Kontrakt na usługę (np. budowę) potwierdza zleceniodawca, a spory rozstrzyga administrator w panelu |
| **Zamówienia władców NPC** | Wladcy (zlecenia, depozyty) | 2 | Nowy rodzaj „zlecenie hurtowe dla firm”: większa ilość, nagroda trafia na konto firmy. Tak samo jak dziś płaci skarbiec NPC, przez depozyt i w tych samych limitach procentowych skarbca. Reputację zdobywa gracz, który oddał zlecenie |
| **Robotnicy NPC i warsztaty** | Ludnosc | 3 | Manufaktura rejestruje warsztat (stół pracy, piec, wędzarnię itp. ze skrzynią wejścia i wyjścia) na ziemi kraju siedziby. Zatrudnia **bezrobotnego dorosłego obywatela** tego kraju za pensję. Robotnik **przetwarza prawdziwe surowce** (zboże → chleb, kłody → deski, ruda + węgiel → sztabki) według receptur vanilla. Nic nie powstaje z niczego. Pełna skrzynia wyjścia zatrzymuje pracę, tak jak w kopalniach |
| **Karawany i konwoje** | Szlaki | 4 | Firma może być **zarządcą trasy** (zapłata na konto firmy zamiast osobistego konta zarządcy) i **zleceniodawcą** (płaci z konta firmy). Zatwierdzanie tras przez administratora zostaje bez zmian |
| **Przewozy koleją** | Kolej (moduł ładunków) | 4 | Firma jest **klientem** przewoźnika. Przewoźnik pozostaje osobistą firmą WorldSteward (`kolej:przewoznik`) i nie należy do systemu Firm. Taryfę ustala WorldSteward, płatność idzie przez depozyt i jest zwalniana dopiero po potwierdzonym rozładunku, który moduł ładunków już weryfikuje. **Zależy od uruchomienia linii**, która dziś stoi przed płatnością |
| **Kontrakty gracz ↔ firma ↔ firma** | Firmy | 2 | Ten sam mechanizm depozytu co wyżej |

Czego **nie** proponuję: produkcji z niczego, wirtualnych towarów, giełdy udziałów i kredytu.
Firma nie ma też własnego limitu u kupca NPC. Sprzedaje zawsze konkretny gracz i liczy się **jego** dzienny limit (500 zł).

---

## 4. Ochrona i nadużycia

| Ryzyko | Ochrona |
|---|---|
| **Pranie pieniędzy / ukrywanie przelewów** | Każdy ruch firmy jest w księdze Skarbca z kategorią „Firmy: …” i widać go w Skarbie Świata (`#/skarbswiata`). Wspólnicy widzą pełną księgę. Duża wypłata (powyżej 25 % salda albo 200 zł dziennie) czeka **24 h**, a wspólnicy dostają o niej powiadomienie. Dywidenda jest tylko proporcjonalna do udziałów |
| **Obejście limitu kupca** | Firma nie sprzedaje kupcowi jako firma: limit liczy się na gracza, który sprzedaje. Kupiec odsprzedaje towar z marżą co najmniej 25 %, więc firmowy sklep „Kupię” nie da zysku na kupcu. Na etapie 3 towar z warsztatów NPC przy sprzedaży kupcowi też liczy się do limitu sprzedającego |
| **Alt-konta** (serwer w trybie offline) | Wspólnikiem lub czeladnikiem może zostać tylko gracz z co najmniej **5 h gry** (statystyka vanilla) i kontem starszym niż 3 dni. Mistrz może mieć tylko jedną firmę. Do sprawdzenia, czy nLogin udostępnia IP: jeśli tak, WorldSteward dostaje w panelu ostrzeżenie o wspólnym IP członków (bez automatycznych kar) |
| **Firmy-wydmuszki** | Opłata rejestrowa, limit członkostwa w kilku firmach i usypianie/likwidacja nieaktywnych |
| **Zamrażanie pieniędzy** | Uśpienie po 30 dniach i automatyczna likwidacja po 60 dniach. Pieniądze wracają do wspólników |
| **Oszustwa w kontraktach** | Zapłata jest z góry w depozycie. Dostawę sprawdza gra przy prawdziwej skrzyni, a wadium chroni zleceniodawcę. Spory blokują depozyt do decyzji administratora i nikt nie dostaje pieniędzy automatycznie |
| **Kradzież wewnątrz firmy** | Czeladnik nie wypłaca pieniędzy. Dziennik skrzyń pokazuje, kto co wyjął. Mistrz może natychmiast odebrać dostęp |
| **Awaria w połowie operacji** | Sprawdzony wzorzec Wladcy i Szlaków: trwały zamiar → przelew → potwierdzenie banku (`bankDurable`) → zapis końcowy. Po restarcie nie ma ponownych wypłat, a niejasny stan idzie do audytu |
| **Narzędzia administratora** | `/firma admin info|zamroz|odmroz|likwiduj|spor` |

---

## 5. Interfejs

**Komendy gracza** (`/firma`, alias `/kompania`), z polskimi podpowiedziami i klikalną pomocą jak w Kraje:

| Komenda | Kto |
|---|---|
| `/firma` | otwiera GUI własnej firmy |
| `/firma zaloz <nazwa> <typ>`, `info [firma]`, `lista`, `ranking` | każdy |
| `/firma wplac <kwota>` | członek |
| `/firma dywidenda <kwota>`, `pensja <gracz> <kwota>` | mistrz |
| `/firma zapros <gracz> <wspolnik/czeladnik>`, `wyrzuc`, `rola`, `udzialy <gracz> <%>` | mistrz (udziały za zgodą obu stron) |
| `/firma sklep` (patrząc na sklep), `magazyn` (patrząc na skrzynię), `ksiega [strona]` | mistrz/wspólnik (księga: każdy członek) |
| `/firma herb` (sztandar w ręce), `kolor`, `siedziba`, `likwiduj` | mistrz |
| `/firma kontrakt nowy/lista/przyjmij/oddaj/spor` | etap 2 |
| `/firma robotnik zatrudnij/zwolnij`, `warsztat` | etap 3 |
| `/kraj firmy` (lista, zatwierdzanie wniosków, `podatek <%>`) | Władca/Doradca kraju siedziby |
| `/firma admin …` | administrator |

**GUI** (`/firma`) ma zakładki:
- Konto: saldo, przychód i podatek z 7 dni;
- Ludzie: role, udziały, pensje;
- Sklepy: stan zapasów, pusty sklep oznaczony;
- Magazyn: ostatnie wyjęcia;
- Kontrakty;
- Księga.

**Panel WWW `#/firmy`:**
- publicznie: ranking i karta firmy (herb, kraj, typ, liczba ludzi, obrót bez kwot salda);
- dla członka: pełna księga;
- dla WorldSteward: wszystkie firmy, przepływy, spory, ostrzeżenia o altach.

**Ranking** według obrotu z 30 dni (domyślnie), z przełącznikami na majątek, liczbę ludzi i liczbę wykonanych kontraktów.
Przewoźnik kolejowy WorldSteward jest pokazywany osobno jako „Królewski przewoźnik”, poza rankingiem.

**Menu, pomoc i ogłoszenia:**
- `/menu`: nowa pozycja „Firmy” z GUI, rankingiem i pomocą (wpisy w `firmy/MENU-ADDITIONS.yml`);
- `/pomoc`: kategoria „Firmy” z poradnikiem „Jak założyć firmę” w `pomoc.yml`;
- katalog komend na Discordzie odświeżany zwykłą ścieżką;
- przy wdrożeniu wpis w Nowościach.

Opcjonalnie w etapie 2: siedziby firm jako znaczniki na mapie squaremap.

---

## 6. Integracja techniczna

**Rekomendacja: nowy plugin `firmy/` (Firmy) i mała, wąska poprawka w Skarbcu.**

Dlaczego nie wszystko w Skarbcu:
- Skarbiec jest sercem ekonomii (księga, monety, kupiec, Skarb Świata). Każda zmiana w nim to ryzyko dla całej puli.
- W `skarbiec/` trwa już osobna praca nad rozszerzeniem oferty kupca.
- Osobny plugin można wyłączyć bez dotykania pieniędzy: konta `firma:*` zostają w księdze.

Co zawiera Firmy:
- rejestr firm, ról, udziałów, pensji, podatku, uśpienia i likwidacji;
- później kontrakty i warsztaty;
- dane w JSON z atomowym zapisem i `.bak` (jak Szlaki i Wladcy) oraz dziennik zamiarów przed przelewami;
- `depend: Kraje, Skarbiec`; `softdepend: Ludnosc, Wladcy, Szlaki, Kolej, Postep, Menu, squaremap`.

**Poprawka Skarbca (MVP):**
1. Sklep dostaje opcjonalne pole „konto/firma”. Utarg trafia wtedy na `firma:<id>` (z podatkiem do kraju), a „Kupię” płaci z firmy.
   Dostęp do skrzyni sprawdza Firmy.
2. `AccountTypes`/`Categories`: przestrzeń `firma:` → „Firmy”, `firma-kontrakt:` → „Depozyty”.
3. Wąska, publiczna metoda dla Firm wołana **przez refleksję** (jak `bankDurable()` w Kolei i Wladcy).

**API:** `medival-api` pozostaje **zamrożone**. Firmy korzystają z istniejących interfejsów:
- `SkarbiecApi.transfer/balance/format/parse`;
- `KrajeApi.getCountryAt/getCountryOf/getRank/isAtWar/members`;
- `PostepApi` (epoka 2 dla manufaktury);
- `LudnoscApi.info` (bezrobotni).

Nowe połączenia idą przez refleksję na publicznych metodach pluginów, a brak metody wyłącza tylko daną funkcję, z czytelnym komunikatem:
- Ludnosc: zatrudnienie i warsztat;
- Wladcy: zlecenie na firmę;
- Szlaki/Kolej: płatnik i odbiorca;
- Panel: RPC `firmy.*`.

**Ryzyka**
- **Wdrożenie Skarbca:**
  - pakiet musi zachować obecny config z cenami kupca ×3,5 i uzgodnić się z trwającą pracą nad kupcem;
  - stary Skarbiec po cofnięciu wersji zignoruje nowe pole sklepu, więc sklepy firmowe zaczęłyby płacić założycielowi.
    Plan rollbacku: przed cofnięciem wyłączyć sklepy firmowe.
- **Alt-konta:** w trybie offline nie da się ich w pełni wykryć. Ograniczenia z p. 4 tylko utrudniają nadużycia.
- **Restart:** zmiany wymagają restartu. Obowiązuje trwała zgoda z 29.10, z jej warunkami (kompletny pakiet, test Paper, kopia, ostrzeżenie graczy).
- **Zależności:** etap 4 (kolej) zależy od faktycznego uruchomienia linii. Etap 3 dotyka złożonego cyklu życia obywateli w Ludnosci.

---

## 7. Etapy i szacunek pracy

| Etap | Zakres | Szacunek (praca agentów) |
|---|---|---|
| **1 — MVP** | założenie ze zgodą władcy i opłatą, konto `firma:`, kapitał, role (mistrz/wspólnik/czeladnik), udziały, pensje graczy, dywidendy, **podatek od przychodu**, **sklepy firmowe** (poprawka Skarbca), magazyn z dziennikiem, uśpienie i likwidacja, GUI, `/kraj firmy`, `/menu` i `/pomoc`, ranking w grze, kategoria w Skarbie Świata, prosta strona `#/firmy` | ok. **4–5 dni**: Firmy ok. 3 dni z testami, Skarbiec 1 dzień, Menu/Panel 0,5–1 dnia, wspólny test Paper i pakiet 0,5 dnia. Z równoległymi subagentami ok. 2–3 dni kalendarzowe. Jeden restart |
| **2 — Kontrakty** | kontrakty z depozytem i wadium (gracz/firma/kraj), spory w panelu, zlecenia hurtowe władców NPC dla firm (poprawka Wladcy), pełny panel, znaczniki na mapie | ok. **3–4 dni** |
| **3 — Manufaktury** | warsztaty i robotnicy NPC z Ludnosci, przetwarzanie prawdziwych surowców, pensje do obywateli, wymóg epoki 2 | ok. **4–6 dni**, bo Ludnosc jest najbardziej złożonym modułem |
| **4 — Logistyka** | firma jako zarządca/płatnik w Szlakach, przewozy koleją jako klient przewoźnika z taryfą WorldSteward | ok. **2–3 dni**, dopiero po uruchomieniu linii kolejowej |

---

## 8. Decyzje do podjęcia

| # | Decyzja | Warianty | **Rekomendacja** |
|---|---|---|---|
| 1 | Koszt założenia | 5 / 25 / 100 zł | **25 zł opłaty do skarbca kraju siedziby + min. 25 zł kapitału** na koncie firmy (kraj kosztuje 5 zł, dzienny limit kupca to 500 zł) |
| 2 | Zgoda władcy | brak / wymagana | **Wymagana**: Władca lub Doradca w kraju gracza, automatyczna w kraju NPC przy reputacji ≥ „Obcy” |
| 3 | Limit firm na gracza | 1 / 2 / 3 | **Mistrz najwyżej 1 firmy; członkostwo łącznie w najwyżej 3 firmach** |
| 4 | Podatek | stały dzienny / % przychodu / oba | **% od przychodu pobierany przy wpływie**: domyślnie 5 %, władca ustawia 0–20 %, kraje NPC stałe 5 %. Bez stałej opłaty dziennej |
| 5 | Pracownicy NPC | nie / tak od razu / tak w etapie 3 | **Tak, w etapie 3**, tylko bezrobotni obywatele kraju siedziby, tylko w manufakturze (epoka 2), limit np. 2/4/8 zależnie od wielkości firmy |
| 6 | Obywatelstwo mistrza | musi być z kraju siedziby / dowolne | **Dowolne** (zagraniczne kantory) z prawem władcy do zawieszenia sklepów firm wroga w czasie wojny |
| 7 | Limit pensji gracza | brak / stały | **Do 50 zł dziennie na osobę** (config) |
| 8 | Duże wypłaty | bez ograniczeń / zwłoka | **Zwłoka 24 h powyżej 25 % salda albo 200 zł dziennie**, z powiadomieniem wspólników |
| 9 | Wymóg dla członków (alty) | brak / staż | **≥ 5 h gry i konto ≥ 3 dni**, a ostrzeżenia o wspólnym IP tylko dla WorldSteward, jeśli nLogin na to pozwala |
| 10 | Nieaktywne firmy | nic / uśpienie / likwidacja | **Uśpienie po 30 dniach, automatyczna likwidacja po 60 dniach**, pieniądze do wspólników |
| 11 | Spory w kontraktach (etap 2) | automat / administrator | **Rozstrzyga administrator w panelu**, depozyt zamrożony do decyzji. Bez sporu po terminie płatność zwalnia się automatycznie na korzyść strony, która spełniła warunek |
| 12 | Banki i kredyt | tak / nie | **Nie** (ryzyko piramid w zamkniętej puli) |
| 13 | Miejsce w kodzie | wszystko w Skarbcu / nowy plugin | **Nowy plugin `firmy/` + wąska poprawka Skarbca** (sklepy, kategorie) |
| 14 | Przewoźnik w rankingu | w rankingu / osobno / ukryty | **Osobno jako „Królewski przewoźnik”**, poza systemem Firm (zgodnie z decyzją, że to osobista firma WorldSteward) |
| 15 | Kolejność etapów | — | **1 → 2 → 3 → 4**; etap 4 dopiero, gdy kolej faktycznie jeździ |

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
