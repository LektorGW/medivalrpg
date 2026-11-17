# Postep — epoki i postęp państw (MEDIVALRPG)

Aktualny tryb serwera (2026-10-26): **średniowiecze i renesans**, `awans.maksymalna-epoka: 2`.
Przemysł i nowoczesność oraz ich odkrycia są wyłączone. Kraje z późniejszej epoki wracają do renesansu;
punkty i historia odkryć pozostają zapisane, efekty wyłączonych odkryć nie działają. Limit obowiązuje także
komendy administracyjne, API, GUI i automat NPC. Pełny katalog poniżej jest dostępny po zmianie limitu na 4.

Plugin Paper 26.3 (Java 25), który prowadzi każde państwo serwera — graczy i NPC — przez cztery epoki, jak w
Civilization:

| # | Epoka | Lata | Ikona |
|---|---|---|---|
| 1 | Średniowiecze | V–XV w. | ⚔ |
| 2 | Renesans | XV–XVII w. | ⚜ |
| 3 | Epoka przemysłowa | XIX w. | ⚙ |
| 4 | Nowoczesność | 2026 | ⚡ |

Każdy kraj rozwija się osobno. Dobrze prowadzony kraj dochodzi do Nowoczesności w około 9–12 tygodni: minimum to
3 tygodnie w każdej z trzech pierwszych epok, a resztę wyznaczają punkty badań.

Zależności: `depend: [Kraje, Skarbiec]`, `softdepend: [Ludnosc, Armia, squaremap]`. Bez Ludnosc nie ma punktów za
ludność i budynki, a progi ludności i osady są pomijane (`progi.pomijaj-nieznane`). Bez Armii kraje NPC mają neutralną
osobowość, a ich dochód liczy się z podatku NPC.

## Jak to działa

### Punkty badań (raz dziennie, `tick.godzina`, nadrabiane po restarcie)

```
(baza + 0,15 × mieszkańcy NPC + 2 × gracze + budynki) × (1 + modyfikatory %) × mnożnik epoki
```

- **Budynki** z Ludnosc (`LudnoscApi.info`, zapasowo `buildingRequirements`): `punkty.budynki` mapuje typ na liczbę
  punktów dziennie, np. biblioteka 4, szkoła 3, akademia 6, uniwersytet 10, kościół 1, katedra 3, ratusz 2, port 3,
  targ 2, laboratorium 12. Liczy się najdłuższy klucz zawarty w id typu, bez polskich znaków. Dzięki temu typy, które
  dopiero powstaną (np. `uniwersytet_renesansowy`, `katedra_gotycka`), działają bez zmian w configu. Liczą się
  najwyżej 3 budynki jednego typu.
- **Modyfikatory**: pokój (brak wojen) +10%, każdy sojusz +5% (najwyżej +15%), każda wojna −15% (najwyżej −45%),
  bycie wasalem −10%. Do tego odkrycia z efektem `badania` (zegar, uniwersytety, druk…).
- **Inwestycja** `/postep inwestuj <kwota>` (Doradca+): pieniądze ze skarbca kraju od razu zamieniają się w punkty.
  Przychody maleją: w ciągu jednego dnia `F(T) = sufit × (1 − e^(−T × stawka / sufit))`. Pierwsza złota korona daje
  `inwestycja-pkt-za-zlota` punktów, każda następna mniej, a dzienny sufit (`inwestycja-max-dziennie`) rośnie z epoką.
  Licznik zeruje się przy dziennym naliczeniu.
- **Kraje NPC** same inwestują część dochodu: z Armią to przychody z ostatnich 24 h, bez Armii podatek NPC. Udział
  wynosi `udzial-bazowy` × (0,5 + bogactwo/100) × (0,7 + 0,6 × dyplomacja/100), w czasie wojny × `mnoznik-wojna`.
  NPC zawsze zostawia `rezerwa` w skarbcu, wydaje najwyżej 20% skarbca i nie inwestuje ponad 85% dziennego sufitu.
  Armia może ustawić stały udział albo 0 przez `PostepApi.setNpcInvestShare` i inwestować sama przez `invest`.

### Drzewko odkryć (`drzewko.yml`)

Plugin ma 30 odkryć po polsku, 7–8 na epokę:

- **Średniowiecze**: koło wodne, pług ciężki, kusza, cechy, zegar mechaniczny, gotyk, uniwersytety.
- **Renesans**: druk, proch, kartografia, nawigacja oceaniczna, bankowość, astronomia, manufaktury.
- **Przemysł**: maszyna parowa, stal, kolej, system fabryczny, elektryczność, telegraf, szczepionki, karabin gwintowany.
- **Nowoczesność**: beton zbrojony, samochód, lotnictwo, radio, antybiotyki, komputery, internet, energia jądrowa.

Każde odkrycie ma: koszt, wymagania (odkrycia z tej samej albo wcześniejszej epoki), flagę `wymagane-do-epoki`,
kategorię, ikonę GUI, opis, tekst efektu i liczbowe `efekty` w procentach (`podatki`, `jedzenie`, `sila-armii`,
`tempo-budowy`, `ludnosc`, `badania`). Tylko `badania` liczy sam Postep. Pozostałe efekty czytają inne pluginy przez
`PostepApi.effect(kraj, "podatki")`, a konkretne odblokowania (budynki, jednostki) przez `hasDiscovery(kraj, "proch")`.

Przy starcie i przy `przeladuj` drzewko jest sprawdzane: nieznane wymagania, wymagania z późniejszej epoki, cykle,
koszt ≤ 0. Błędne odkrycia (i wszystkie od nich zależne) są pomijane, a w logu pojawia się ostrzeżenie.

**Wybór**: `/postep odkryj <id>` (Władca/Doradca) odkrywa od razu, jeśli kraj ma dość punktów. W przeciwnym razie
ustawia **cel badań**, także przez brakujące wymagania (ścieżka). Odkrycie nastąpi samo, gdy uzbiera się punkty
(przy naliczeniu albo po inwestycji). Kraje NPC wybierają cel według osobowości z Armii: agresja → wojsko,
bogactwo → gospodarka, ekspansja → budownictwo, dyplomacja → dyplomacja i nauka. Odkrycia wymagane do epoki dostają
premię, a większą, gdy minimalny czas w epoce już minął. Tańsze mają lekką przewagę, a deterministyczny „szum” z id
kraju sprawia, że kraje NPC rozwijają się różnie.

### Przejście do następnej epoki

Wszystkie trzy warunki muszą być spełnione naraz:

1. wszystkie odkrycia bieżącej epoki z `wymagane-do-epoki: true`,
2. minimalny czas w bieżącej epoce (`epoki.N.min-dni`, domyślnie 21 dni),
3. progi wejścia do nowej epoki: ludność (`min-ludnosc`; mieszkańcy NPC + 5 × gracz) i poziom osady
   (`min-poziom`, `LudnoscApi.settlementTier`).

Gdy warunki są spełnione, kraj awansuje sam. Jeśli `awans.potwierdzenie-wladcy: true`, kraj graczy czeka na
`/postep awansuj` Władcy; Władca dostaje klikalne przypomnienie, także przy wejściu na serwer. Awans to ogłoszenie
na czacie, title „✦ Królestwo Polskie wkracza w Renesans! ✦” (dla wszystkich albo tylko dla kraju), dźwięk i
`EraAdvanceEvent`. Pasek postępu to ważona średnia trzech części: odkryć (zdobyte wymagane plus punkty w puli),
czasu i progów (60/25/15%). Pasek nie dochodzi do 100%, dopóki nie są spełnione wszystkie warunki.

## Komendy (`/postep`, alias `/epoka`)

| Komenda | Kto | Opis |
|---|---|---|
| `/postep` | każdy | stan kraju: epoka, pasek postępu, punkty (dymek z rozbiciem), inwestycje, cel, czego brakuje, klikalne dostępne odkrycia |
| `/postep drzewko` | każdy | GUI: rząd na epokę, zdobyte świecą, dostępne z ikoną, zablokowane jako szyby; klik = odkryj / ustaw cel |
| `/postep odkrycia` | członek | dostępne odkrycia (klikalne) |
| `/postep odkryj <odkrycie>` | Doradca+ | odkryj albo ustaw cel badań (podpowiedzi z tooltipami: koszt, stan) |
| `/postep cel [wyczysc]` | członek / Doradca+ | cel badań, koszt ścieżki, szacowany czas |
| `/postep inwestuj <kwota>` | Doradca+ | skarbiec → punkty (podpowiedzi pokazują, ile punktów da kwota) |
| `/postep awansuj` | Władca | potwierdzenie awansu (tryb `potwierdzenie-wladcy`) |
| `/postep ranking [strona]` | każdy | ranking krajów według epoki i postępu |
| `/postep kraj <kraj>` | każdy | postęp dowolnego kraju |
| `/postep epoki` | każdy | epoki, ich progi i wymagane odkrycia (w dymkach) |
| `/postep pomoc [strona]` | każdy | interaktywna pomoc (najedź = opis, klik = wpisz) |
| `/postep admin ustaw <kraj> <epoka>` | admin | ustawia epokę (1–4 albo nazwa), dodaje wymagane odkrycia wcześniejszych epok, liczy czas od nowa, wywołuje `EraAdvanceEvent` (także przy cofnięciu) |
| `/postep admin punkty <kraj> <±n>` | admin | zmienia pulę punktów |
| `/postep admin odkryj <kraj> <id>` | admin | nadaje odkrycie (z brakującymi wymaganiami) za darmo |
| `/postep admin zabierz <kraj> <id>` | admin | zabiera odkrycie i wszystkie, które od niego zależą |
| `/postep admin tick` | admin | dzienne naliczenie teraz (harmonogram bez zmian) |
| `/postep admin info` | admin | stan pluginu, integracje, ranking |
| `/postep admin przeladuj` | admin | przeładowanie `config.yml` i `drzewko.yml` |

Nazwy krajów mogą zawierać spacje i polskie litery. W komendach admina wartość (epoka, liczba, id) jest zawsze
ostatnim słowem. Uprawnienia: `postep.use` (domyślnie wszyscy) i `postep.admin` (op; omija też wymóg rangi).

## Wyświetlanie

- **Tablista**: Kraje ustawia nazwę w tabliście (tag kraju, ranga). Nowe Kraje mają własne placeholdery epoki
  `<era>` (plakietka) i `<era_icon>` (ikona) w `tablist.format`, domyślnie `<rank_icon><era_icon><color>[<country>]…`.
  **Z takim Kraje ustaw `tablista.dopisz-ikone: false`.** Gdy tego nie zrobisz, Postep i tak sam przestaje dopisywać
  ikonę, jeśli format tablisty Kraje zawiera `<era>` albo `<era_icon>` (sprawdza to co `tablista.co-ile-sekund`).
  Ze starym Kraje albo starym `plugins/Kraje/config.yml` (format bez tych placeholderów) Postep dopisuje ikonę jak
  dotąd (`tablista.format`, domyślnie `<name> <badge_icon>`) i co 5 s sprawdza, czy Kraje nie nadpisał nazwy.
- **squaremap**: warstwa „Epoki” z ikoną epoki przy stolicy każdego kraju: kolorowy krążek z 1–4 kropkami, rysowany
  bez czcionek, więc działa na serwerze headless. Popup pokazuje epokę, postęp, punkty, odkrycia i cel badań.
- **`postep.json`**: zapisywany do `plugins/squaremap/web/postep.json` (albo `mapa.sciezka-json`; bez squaremap do
  `plugins/Postep/postep.json`) po każdej zmianie i co minutę, atomowo (plik tymczasowy + podmiana).

### Eksport `postep.json`

```json
{
  "updated": 1793000000000, "nextTick": 1793050000000,
  "eras": [{"id":1,"name":"Średniowiecze","years":"V–XV w.","color":"#B08D57","icon":"⚔","description":"…","minDays":21.0,"minPopulation":0,"minTier":0}],
  "discoveries": [{"id":"druk","name":"Druk","era":2,"cost":300,"requires":["uniwersytety"],"requiredForEra":true,"category":"nauka","description":"…","effect":"…"}],
  "countries": [{
    "id":"…","name":"Królestwo Polskie","color":"#DC143C","npc":false,"rank":1,
    "era":2,"eraName":"Renesans","eraIcon":"⚜","eraColor":"#C9A227","eraSince":1792000000000,"maxEra":false,
    "progress":0.523,"ready":false,"pendingAdvance":false,
    "points":340.0,"totalPoints":1420.0,"dailyPoints":48.5,"investedToday":500,
    "discoveries":["kolo_wodne","plug"],"discoveryCount":9,"target":"druk","targetName":"Druk",
    "missing":["odkrycie: Proch","czas w epoce: jeszcze 6 dni 4 h"],
    "population":42,"tier":3,"capital":{"world":"minecraft_overworld","x":1208,"z":-344}
  }]
}
```

`countries` są posortowane jak ranking. `population` i `tier` mają wartość −1, gdy brak Ludnosc.

## API (`medival-api/postep-api`)

`PostepApi` w ServicesManager. Odczyty są bezpiecznymi wątkowo migawkami, odświeżanymi co `tick.odswiezanie-sekundy`
i po każdej zmianie.

- `era`, `eraInfo`, `eras`, `progress`, `researchPoints` (pula do wydania), `discoveries`, `hasDiscovery`,
  `missingForNextEra`, `eraSince`,
- (default, od Postep 1.0) `eraIcon(era)`, `eraBadge(kraj)` (MiniMessage, np. `<color:#C9A227>⚜ Renesans</color>`),
  `dailyResearch`, `totalResearchPoints`, `researchTarget`, `discoveryTree`, `discovery`, `effect(kraj, klucz)`,
  `investPreview`, `invest` (główny wątek), `npcInvestShare` / `setNpcInvestShare` (−1 = auto), `investedToday`,
  `discover(kraj, id, byPlayer)` (jak `/postep odkryj`, wynik `DiscoverResult`), admin: `setEra`, `addResearchPoints`,
  `grantDiscovery`; rekordy `DiscoveryInfo`, `InvestResult`, `DiscoverResult`. Panel www korzysta z nich (PANEL.md, „Postęp”).
- `EraAdvanceEvent(countryId, oldEra, newEra)` jest wywoływany na głównym wątku po zapisaniu zmiany, także przy
  `admin ustaw` (wtedy możliwe `newEra < oldEra`).

**Ważne dla konsumentów**: Postep ma Ludnosc i Armię w `softdepend`, więc te pluginy **nie powinny** dodawać `Postep` do
swojego `softdepend`, bo powstałby cykl ładowania. Postep startuje po nich, dlatego trzeba go szukać leniwie:
`ServicesManager#getKnownServices()` po nazwie klasy `pl.medivalrpg.postep.api.PostepApi`, z wywołaniem refleksyjnym
(tak jak `kraje/.../integration/Integrations.java`), albo nasłuchiwać `ServiceRegisterEvent`. `EraAdvanceEvent` można
zarejestrować przez `PluginManager#registerEvent(klasa, …)`, gdzie klasę bierze się z classloadera providera.

## Dane

`plugins/Postep/dane.json` (Gson). Zapis jest asynchroniczny: `dane.json.tmp`, potem `.bak` i atomowa podmiana.
Uszkodzony plik zostaje zachowany jako `dane.json.broken-<czas>`, a dane wczytują się z `.bak`. Na każdy kraj
zapisywane są: epoka, `eraSince`, pula i suma punktów, odkrycia z czasem zdobycia, cel badań, dzisiejsze inwestycje,
oczekujący awans, udział inwestycji NPC i historia epok. Kraj usunięty w Kraje (`CountryDisbandEvent`) jest usuwany
także tutaj. Przy pierwszym starcie wszystkie istniejące kraje zaczynają w Średniowieczu od tej chwili.

## Budowanie

```
./gradlew build
```

Testy JUnit 6 sprawdzają:
- naliczanie punktów,
- drzewko: wymagania, ścieżki, cykle, sanityzację,
- warunki awansu: czas, odkrycia, progi, nieznane progi, pasek,
- malejące przychody z inwestycji,
- wybór odkryć i inwestycje NPC,
- harmonogram z nadrabianiem,
- poprawność domyślnych `config.yml` i `drzewko.yml` (w tym: NPC zawsze może dojść do ostatniej epoki, każdy
  komunikat użyty w kodzie istnieje).

Gotowy plik to `build/libs/Postep-1.0.0.jar`. Do budowania potrzebny jest
`../medival-api/build/libs/medival-api.jar` (`./gradlew jar` w `medival-api`). Jar zawiera tylko klasy Postep i
`postep-api`.

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
