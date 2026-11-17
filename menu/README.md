# Menu – plugin Paper (główne menu MEDIVALRPG)

Jedno miejsce ze wszystkimi funkcjami serwera: `/menu` (aliasy `/m`, `/pomoc`, `/przewodnik`), kompas
**Przewodnik**, książka **Poradnik** dla nowych graczy, teleport na **spawn** i **domy** graczy.

Wymagania: Paper 26.3 i Java 25. Wszystkie integracje są luźne. Przyciski wykonują komendy jako gracz, więc
Menu nie zależy od klas innych pluginów. Przycisk z `requires-plugin` jest ukryty, gdy pluginu nie ma albo jest
wyłączony (Skarbiec, Armia, Nowosci itd.).

## Instalacja
1. `gradlew build`. Wynik to `build/libs/Menu-1.0.0.jar`.
2. Wgraj jar do `plugins/` i uruchom serwer. Powstaną `plugins/Menu/config.yml` i `plugins/Menu/menus.yml`.
3. Po zmianach wpisz `/menu reload` (ostrzeżenia z `menus.yml` trafiają do konsoli).

> **Aktualizacja z poprzedniej wersji:** `menus.yml` nie nadpisuje się sam. Przy późniejszej, uzgodnionej
> aktywacji zrób kopię własnego `plugins/Menu/menus.yml`, przenieś nowe definicje z
> `src/main/resources/menus.yml`, zachowując własne zmiany, i użyj `/menu reload`.
> Samo przeładowanie nie odtwarza brakującego pliku; domyślny plik jest tworzony podczas uruchamiania pluginu.
> Zmiany kodu wymagają osobnej aktywacji nowego jara. Ta integracja nie obejmuje wdrożenia ani restartu.
> Nowe klucze `config.yml` (np. `motion-*`) działają bez tego, bo brakujące wartości biorą się z jara.

## Układ menu
`/menu` to jedno miejsce na wszystkie funkcje serwera. Menu główne ma kategorie, a każda otwiera podmenu
(przycisk `« Wróć` prowadzi z powrotem). Przycisk z `requires-plugin` jest ukryty, gdy pluginu nie ma.

| Kategoria | Menu | Zawartość |
|---|---|---|
| Postać i interakcje | `postac` → `interakcje`, `ruchy` | interakcje z configu Interakcji, **tańce i emotki** z configu (`type: motions`), usiądź, połóż się, puść dłoń |
| Kraj | `kraj` → `kraj-dyplomacja` | załóż / dołącz, info, zajmij, porzuć, mapa, zaproś, czat, stolica, skarbiec, wpłać, wypłać, podatek, panel www; sojusz, pokój, wojna, zerwij, oblężenie(a), wasale, kapitulacja, wykup |
| Osada | `osada` (Ludnosc) | info, budynki, typy, buduj, buduj auto, kolejka, plan, spichlerz, kopalnia, modernizuj, napraw |
| Armia | `armia` | rekrutuj, lista, info, za mną, stój, wróć, straż, patrol, atakuj, buława, rozpuść, najemnicy (najmij, lista, przedłuż) |
| Postęp i epoki | `postep` (Postep) | stan kraju (`/postep`), drzewko odkryć, dostępne odkrycia, inwestuj (wpisuje `/postep inwestuj ` do czatu), ranking, epoki i progi, Poradnik, pomoc; w Poradniku rozdział „Epoki i postęp” |
| Pieniądze | `skarbiec` | saldo, monety, przelew, handel (wybór gracza obok), sklepy, skarbiec kraju |
| Czat głosowy | `glos` | połącz, szept / normalnie / krzyk, rozłącz, pasek, wskaźnik |
| Teleporty | `teleporty` | spawn, powrót, dom, domy, ustaw dom, usuń dom, stolica |
| Świat i mapa | `swiat` | mapa świata, panel państwa w przeglądarce (`/panel`, panel.example.workers.dev), lista krajów, nowości, rozmowa z władcą NPC |
| Kolej | `kolej` → `kolej-admin` | panel, pomoc, składy, firma, wpłaty, wsiadanie, wysiadanie i prowadzenie; sieć, rozkłady i obsada tylko dla zarządcy |
| Turnieje | `turnieje` | informacja, zapisy, wycofanie, ranking, widownia i pomoc |
| Karawany i trasy morskie | `szlaki` → `szlaki-projekt`, `szlaki-admin` | rzeczywiste przewozy, stan tras i konwojów, wojenny napad/przejęcie, naprawa, projektowanie i odbiór tras |
| Pomoc | `pomoc` | Poradnik, kompas Przewodnik, pomoc każdego pluginu |
| Ustawienia | `ustawienia` | interakcje wł./wył., zablokowani, czat kraju, pasek i wskaźnik czatu głosowego, nowości |
| Admin (`menu.admin`) | `admin` → `panstwa` | Państwa (lista z teleportem), `/nk lista`, `/nk scenariusz lista`, `/wladca koszt`, `/skarbiec npc lista`, `/ustawspawn`, `/menu reload` |

Wszystkie przyciski wykonują komendy jako gracz. Uprawnienia i aktualny stan nadal sprawdza plugin będący
właścicielem komendy. Testy pilnują zachowania dotychczasowych funkcji, w tym floty i najemników.

## Samouczek nowego gracza — 2026-11-11 (pakiet `deploy-menu-samouczek-20261111`, stan wdrożenia: `MEMORY.md`)

- **Wejścia:** `/samouczek`, `/menu samouczek`, pierwszy przycisk głównego menu („★ Samouczek”), rozdział
  `/menu poradnik samouczek` (pierwszy w Poradniku), wpis `/samouczek` w `/pomoc start`.
- **Kroki** to przyciski menu `samouczek` w `menus.yml` z kluczem **`progress: <id>`** (teksty i akcje edytuje się
  w pliku; `progress: summary` dopisuje do przycisku linię „Twój postęp: x/y”). Zrobiony krok dostaje „✔” w nazwie,
  połysk i linię „✔ Zrobione”, niezrobiony „○ Jeszcze przed tobą” (`messages.tutorial-*`).
- **Postęp ze stanu gry** (`integration/ProgressHook` → `tutorial/TutorialRules`, refleksyjnie przez publiczne API,
  tylko odczyt): `pieniadze` (konto + monety ≥ `tutorial.money-goal` albo gracz ma kraj), `kraj`, `ziemia` (≥ 2 chunki),
  `mieszkania` (`LudnoscApi#housing` > 0), `jedzenie` (budynek `spichlerz`), `budowa` (kraj ma budynek albo plac),
  `produkcja` (`tartak`/`kopalnia`/`kamieniolom`), `armia` (`koszary` albo żołnierz). Pozostałe id (`wlasne`, `pomoc`)
  są informacyjne — zrobione po kliknięciu; tak samo krok, którego stanu nie da się odczytać (brak API). Krok raz
  zrobiony zostaje zrobiony (`plugins/Menu/data/tutorial.json`: `invited`, `dismissed`, `finished`, `done`, `seen`).
- **Po zalogowaniu** (Straznik/nLogin przez `LoginWatcher`, potem `tutorial.invite-delay-seconds`): pierwszy raz
  zaproszenie na czacie z przyciskami `[▶ Otwórz samouczek]` i `[nie pokazuj]`, później jedna linia przypomnienia
  z następnym krokiem — dopóki został krok sprawdzany ze stanu gry i gracz nie wpisał `/samouczek ukryj`
  (`/samouczek przypominaj` włącza z powrotem). Gracz, który ma wszystkie kroki ze stanu gry, nie dostaje nic.
  Menu niczego nie otwiera ani nie teleportuje przy logowaniu.
- Config: sekcja `tutorial` (`enabled`, `remind`, `invite-delay-seconds`, `money-goal`); produkcyjny `config.yml` nie
  wymaga zmiany — brakujące klucze i komunikaty biorą wartości z jara.

## Build menu-flags-20261029 — 2026-10-29 ~23:00 (przygotowane, nieaktywne)

`build/menu-flags-20261029/libs/Menu-1.0.0.jar` (profil `../tools/menu-flags-20261029.init.gradle`), pakiet
`../deploy-menu-flags-20261029/`. Zastępuje aktywny `b1db94be` (batch2). **Klasy bajt w bajt jak w b1db94be** — różnią się
tylko zasoby `menus.yml` i `pomoc.yml`:

- `menus.yml`: menu `kraj-flaga` ma 4 rzędy (`auto-slots: [10-16, 19-25]`, „« Wróć” w slocie 31) i 3 nowe przyciski
  sztandarów (`sztandar`, `sztandar-podglad`, `przedmiot`) z `requires-command` `kraj flaga sztandar|przedmiot` —
  ukryte przy Kraje 9240f7b2, widoczne z Kraje 463b65a5 (deploy-flags-banners-20261029).
- `pomoc.yml`: stan opublikowanego katalogu Discord 5d52b5c4 (aktywne wpisy bez `discord: false`, `discord-page: 2`)
  + 2 wpisy `/kraj flaga sztandar [podglad]`, `/kraj flaga przedmiot` (`requires-command` + `discord: false` do aktywacji).
- Test `preparedFeaturesAreGatedAndKeptOffDiscordUntilActivation` poprawiony do stanu po partii 2: wpisy z
  `requires-command` aktywnych funkcji (Firmy 2–3, `/kraj flaga`, taran, Interakcje) są opublikowane; nadal
  przygotowane (`/kraj flaga sztandar|przedmiot`) muszą mieć `discord: false`. Nowy test
  `flagStandardButtonsAppearOnlyWithTheNewKraje` (rejestry batch2 i `verify-flags-banners-20261029`). 146 testów, 0 błędów.

## Build menu-20261029 — 2026-10-29 po partii night1 (przygotowane, nieaktywne)

`build/menu-20261029/libs/Menu-1.0.0.jar`, pakiet `../deploy-menu-20261029/`. Zastępuje `release-20261029` (aktywny).
Zawiera integrację Strażnika (`LoginWatcher`, softdepend, przyciski „Zmień hasło” i „Wyloguj” — widoczne tylko ze Strażnikiem).

- **Nowe pozycje menu:** Firmy (menu główne + `firmy`, `firmy-zarzad`, `firmy-kontrakty`, `firmy-kredyty`, `firmy-manufaktura`),
  Pieniądze → Kupcy i dostawy oraz Firmy (menu `skarbiec` ma teraz 2 rzędy po 7), Kraj → Rangi, wygląd i czaty → Chorągiew
  (`kraj-flaga`), Osada → Zakłady i łańcuchy (`osada-zaklady`: zakłady, przepływ, huta szkła, cegielnia, owczarnia, skrzynia
  zakładu), Postać → Tytuł rycerski, Kolej (zarządca) → Uruchomienie linii (`kolej-uruchomienie`: postęp sieci, sprawdzenie,
  uruchomienie, palacz NPC), Skarb Świata → Przelew do wszystkich państw NPC, Admin → Państwa NPC (`admin-npc`: dotychczasowe
  `/nk …` + wojny NPC + animacje pracy NPC), Admin → Gospodarka (`admin-gospodarka`: NPC Skarbca, typ kupca, kramy, Skarb
  Świata, nadzór firm), Admin → Straż (`straz-admin`). Admin miał 28/28 miejsc — `/nk …` i NPC Skarbca przeniesiono do podmenu.
- **`requires-command`** (przyciski, menu, książki, rozdziały, sekcje Poradnika i wpisy `pomoc.yml`): słowa komendy, np.
  `requires-command: "firma kredyt"`. Element jest ukryty, dopóki na serwerze nie ma takiej ścieżki komendy (żywe drzewo
  Brigadier serwera; komendy plugin.yml z jednym argumentem „greedy” — tylko korzeń). Dzięki temu jeden `menus.yml` działa
  przed i po aktywacji przygotowanych modułów: Firmy etap 2–3, chorągwie Kraje, wojny NPC (`/nk wojny`), tytuł rycerski i
  animacje NPC (Interakcje), nowe interakcje w `/pomoc`. Przy kliknięciu warunek sprawdzany jest ponownie.
- **Poradnik w rozdziałach:** `/menu poradnik` otwiera spis 14 rozdziałów (Start, Kraj, Ekonomia i kupcy, Firmy, Ludność i budowy,
  Zakłady i łańcuchy, Armia i flota, Dyplomacja i podbój, Postęp, Kolej, Handel i szlaki, Turnieje, Postać, Administracja — ten
  tylko z `menu.admin`); klik w rozdział = `/menu poradnik <rozdział>` (podpowiedzi Tab). Każdy rozdział to osobna książka
  `poradnik-<rozdział>` z własnym spisem tematów i powrotem „« Wszystkie rozdziały”. Format: `books.<id>.chapters`, w książce
  rozdziału `name`, `description`, `requires-plugin`, `permission`, `requires-command`. `/menu <książka>` otwiera też książkę.
  Treść: `build/poradnik-rozdzialy.yml`, scalanie: `build/integrate_20261029b.py` (zawsze od `build/menus.base-32887554.yml`).
- **`pomoc.yml`:** Firmy etap 1 i `/skarbiec kupcy` publiczne; nowe `/ludnosc zaklady`, `/kraj flaga …`, Firmy etap 2–3
  (`requires-command` + `discord: false`). Na Discordzie Ludność i Firmy mają po dwie wiadomości (`discord-page: 2`,
  `discord-page-2: {name, description, guide}`; nowe klucze `pomoc/05-ludnosc-2`, `pomoc/13-firmy`, `pomoc/13-firmy-2`).
- Testy: 144 (JDK 25), w tym rejestr komend izolowanego Papera `../verify-menu-20261029/` (jary night1 = produkcja oraz
  z przygotowanymi): każdy przycisk wskazuje istniejącą komendę, a przyciski/wpisy pomocy komend nieistniejących na produkcji
  są ukryte i odsłaniają się z przygotowanymi jarami.

## Build release-20261029 — 2026-10-29 (przygotowane, nieaktywne)

`build/release-20261029/libs/Menu-1.0.0.jar` zastępuje `help-release3`. **138 testów, 0 błędów** (JDK 25), testy
generatora pomocy 6/6, katalog Discord 14 wiadomości, rejestr produkcji bez braków. Nic nie wgrano ani nie aktywowano.

- Wszystkie `*/MENU-ADDITIONS.yml` (8 modułów) są w `menus.yml`; nowe: **Admin → Skarb Świata** (`skarbswiata-admin`:
  info, spis monet, przelew/zwrot NPC, potwierdzenie, zasilenia jednorazowe; komendę widzi tylko UUID WorldSteward),
  **Osada → Życie obywateli** (`osada-obywatele`: karta obywatela, obywatele kraju, samodzielne chaty wł./wył./limit),
  **Osada → Plan budowy i kolejka** (`osada-budowa`), `/ludnosc buduj wariant`.
- Komendy końcowych buildów bez przycisku dostały wpisy: **Kraj → Rangi, wygląd i czaty** (`kraj-zarzad`), kapitulacja
  przyjmij/odrzuć i zwolnienie wasala, Postęp (kraj, odkryj, cel, awans), Armia (patrol start, rozpuszczenie z
  potwierdzeniem), Władcy (przyjmij/odrzuć/porzuć zlecenie, reputacja u kraju, koniec rozmowy), Kolej (odjazdy, stan
  pociągu, jedź/ster/hamulec), Interakcje (zgoda/odmowa, lista, odblokuj, paczka), Turnieje (pojedynki, turniej kraju),
  Świat (Nowości, wylogowanie panelu), pomoc Interakcji i Życia.
- `pomoc.yml`: nowe komendy Ludnosci (kopie, budowy, gospodarka, wariant, obywatele, chatki) z `discord: false` do
  aktywacji; aktywne na produkcji komendy unii, ambasad, kapitulacji, floty, interakcji i reputacji idą na Discord
  (2 wpisy dyplomacji z `discord: false` z braku miejsca). `/pomoc` nadal `discord: false`.
- Poradnik: spawn zgodny z decyzją (powrót w miejscu wylogowania), akapit o obywatelach w „Praca, kopalnie i sezony”.
  Książka ma ~100 stron — limit; nowe treści trzeba skracać.
- Nowy test: każda komenda z `/pomoc` ma przycisk w `/menu`; przygotowane funkcje są ukryte na Discordzie.
- `build/release-20261029/`: `menu-commands.json` (wszystkie komendy przycisków i pomocy do testu na Paperze),
  `production-menus-diff.txt` (produkcyjny `menus.yml` = jar produkcji, brak ręcznych zmian), `discord-preview.md`.

## Centrum pomocy `/pomoc` — 2026-10-29 (przygotowane, nieaktywne)

Jedno źródło danych: `src/main/resources/pomoc.yml`. Ten sam plik czyta Menu w grze i generator kanału Discord
(`tools/build_player_help_catalog.py`), więc pomoc i Server-command się nie rozjeżdżają. Menu czyta plik z jara;
`plugins/Menu/pomoc.yml` (jeśli administrator go utworzy) ma pierwszeństwo, przeładowanie `/menu reload`.

| Komenda | Działanie |
|---|---|
| `/pomoc` | okno „Centrum pomocy”: 12 kategorii (Start, Kraj, Dyplomacja i wojna, Ekonomia, Ludność i budowa, Armia i flota, Postęp i epoki, Kolej, Handel/szlaki/zlecenia, Turnieje, Postać i towarzystwo, Nowości) oraz skróty: Poradnik, Przewodnik, Szukaj, lista na czacie, Pomoc modułów |
| `/pomoc <kategoria>` | na czacie: opis, „Jak zacząć” z klikalnymi komendami, wszystkie komendy (najedź = opis i ranga, klik = wpisz albo wykonaj), zakładki kategorii |
| `/pomoc lista` | klikalna lista kategorii |
| `/pomoc szukaj <fraza>` | wyszukiwarka (bez wielkości liter i polskich znaków; najpierw trafienia w samej komendzie) |

- `/pomoc` nie jest już aliasem `/menu` (aliasy `/m`, `/przewodnik` zostają). `/menu pomoc` otwiera to samo okno.
- Akcja `help:<kategoria>` w `menus.yml` otwiera generowane okno kategorii (28 komend na stronę, strony, « Wróć do
  pomocy, Jak zacząć, Pokaż na czacie, Szukaj, Zamknij). Kategorie i komendy są ukryte bez pluginu
  (`requires-plugin`) albo uprawnienia (`permission`); przy kliknięciu sprawdzane ponownie.
- Teksty z `pomoc.yml` są wstawiane jako zwykły tekst (nigdy MiniMessage). `click: run` tylko dla komend bez argumentów;
  pozostałe wpisują składnię do czatu. Wygląd w `config.yml → messages.help-*` (domyślne z jara).
- Dotychczasowe przyciski pomocy pluginów są w podmenu **Pomoc modułów** (`pomoc-moduly`).
- **Aktywacja:** `plugins/Menu/menus.yml` nie aktualizuje się sam — przenieść nowe `pomoc`, `pomoc-moduly`,
  `osada-kopie` i przyciski z `src/main/resources/menus.yml` (najprościej: kopia starego pliku i podmiana całości,
  jeśli nie ma własnych zmian), potem `/menu reload`. Bez tego `/pomoc` otworzy stare menu pomocy.
- Integracja `*/MENU-ADDITIONS.yml` (test sprawdza wszystkie pliki wszystkich modułów): dodano Kolej — tor
  realistyczny/klasyczny, stan i ponowienie wyceny sieci (zarządca), Ludnosc — **Kopie budynków** (`osada-kopie`),
  place budowy i przepływ materiałów. Te funkcje innych modułów nie są jeszcze aktywne na produkcji.
- Build 2026-10-29: `build/help-release3/libs/Menu-1.0.0.jar`, **136 testów, 0 błędów**, JDK 25.

## Integracja nowych funkcji — 2026-10-27

Włączono katalogi `wladcy/MENU-ADDITIONS.yml`, `kraje/MENU-ADDITIONS.yml`,
`ludnosc/MENU-ADDITIONS.yml`, `turnieje/MENU-ADDITIONS.yml`, `panel/MENU-ADDITIONS.yml`
oraz gotowy `szlaki/MENU-ADDITIONS.yml`.
Zmiany obejmują wyłącznie moduł Menu. Źródła są w `src/`, wynik kompilacji w
`build/libs/Menu-1.0.0.jar`. Nic nie zostało wgrane do serwera.

- **Świat → Władcy:** zlecenia, oddawanie towaru, reputacja, tablica innego kraju, rozmowa i pomoc.
  Administrator z `wladcy.admin` ma odświeżanie tablic zleceń.
- **Kraj → Dyplomacja:** osobne podmenu unii i ambasad zawierają wszystkie czynności z katalogu Kraje.
  Rangi, warunki oraz potwierdzenie zerwania nadal egzekwuje Kraje.
- **Osada → Praca, kopalnie i pory roku:** obsada, plony, magazyn, spichlerz, stan kopalń i płatna rozbudowa.
  W administracji jest diagnostyka pracy, zegar sezonów, wymuszenie/zakończenie nieurodzaju i reload Ludnosc.
- **Turnieje:** wszystkie sześć przycisków z katalogu Turniejów. Osobne podmenu administracyjne
  udostępnia listę i konfigurację aren, start, stop oraz reload, z `turnieje.admin`.
- **Admin → Wydajność serwera:** `/panel wydajnosc`, plugin Panel, uprawnienie `panel.health.alerts`.
  Wynik TPS, MSPT, graczy, encji i NPC trafia na czat; menu się zamyka. Pełne wykresy są w panelu WWW,
  Administracja → Wydajność serwera (`#/admin/serwer`). Niestandardowe uprawnienie alertów w konfiguracji
  Panelu należy również ustawić w definicji tego przycisku Menu.

Kolej ma kategorię w menu głównym: `/kolej panel`, `pomoc`, `pociag lista`, `firma info`,
podpowiedzi `firma wplac`, `prowadz`, `wejdz` oraz `/kolej zejdz`.
`/kolej linia lista` jest widoczne tylko z `kolej.admin`, zgodnie z aktualnym `KolejPlugin`.
Zarządca ma cztery podmenu: **stacje/baza/tory**, **linie/rozkłady**, **firma/składy/maszyniści/NPC**
oraz **baza/wagony/ładunki**.
Komendy wymagające ID, kwoty, gracza lub rozkładu są podpowiedziami do uzupełnienia na czacie.

Podmenu **Baza, wagony i ładunki** (`kolej-transport`) udostępnia:

| Komenda | Działanie |
|---|---|
| `/kolej baza wprowadz <pociag> <baza> <stanowisko>` | Wprowadzenie składu na stanowisko 1–6 |
| `/kolej baza wyprowadz <pociag>` | Wyprowadzenie składu z bazy |
| `/kolej wagon odczep <pociag>` | Odstawienie wagonów do wspólnej rezerwy przewoźnika |
| `/kolej wagon dolacz <pociag> <typ>` | Dołączenie wagonu z rezerwy |
| `/kolej ladunek magazyn <stacja>` | Przypisanie wskazanej skrzyni/beczki do stacji lub bazy |
| `/kolej ladunek zlec <pociag> <material> <ilosc> <od> <do>` | Zlecenie przewozu towaru |
| `/kolej ladunek regularnosc <pociag> <minuty\|0>` | Cykl przewozów; 0 oznacza przewóz jednorazowy |
| `/kolej ladunek status` | Bieżący stan przewozów towarowych |

Wszystkie te akcje i całe podmenu wymagają `kolej.admin`; `menu.admin` i `kolej.maszynista`
nie wystarczają. Tylko odczyt statusu wykonuje się od razu; pozostałe przyciski podpowiadają
komendę do uzupełnienia argumentami.

Sieć, rozkłady, zakupy i obsadę zmienia wyłącznie `kolej.admin`. Przycisk „Prowadź” celowo
nie wymaga w Menu `kolej.maszynista`: Kolej dopuszcza też własną lokalną listę przydziałów.
Przycisk tylko podpowiada `/kolej prowadz `, a przy wykonaniu Kolej sprawdza uprawnienie,
przydział do konkretnego składu/linii oraz odległość od lokomotywy. Wpłata do firmy pobiera
własne miedziaki gracza i wymaga świadomego podania kwoty.

`menu.admin` nie omija już wymagań pluginu i uprawnień podmenu. Otwarcie bezpośrednim
`/menu kolej-admin` również wymaga `kolej.admin`. Dostęp do przycisku jest ponownie sprawdzany
przy kliknięciu, więc wyłączenie pluginu lub odebranie uprawnienia unieważnia otwarte wcześniej GUI.
Poradnik filtruje rozdziały tymi samymi regułami obecności pluginów i uprawnień.

Menu główne i Osada mają po 28 miejsc automatycznych, Pomoc 21, Admin 28;
zarządzanie Koleją rozdzielono na podmenu. Łącznie są 34 menu. Flota, jej sterowanie,
transport i desant pozostają dostępne. Treści o epokach wskazują **maksymalnie Renesans (epoka 2)**;
**kolej parowa jest dostępna od startu niezależnie od epoki**. Dodano rozdziały Poradnika o zleceniach,
uniach/ambasadach, pracy/sezonach, Kolei, Turniejach i wydajności.

**Szlaki:** gotowa integracja karawan drogowych i konwojów morskich. Publiczna kategoria nie wymaga
nieistniejącego `szlaki.use`: dostępna jest po włączeniu Szlaków. Zawiera `/szlaki lista`, pomoc `/szlaki`,
podpowiedzi `trasa`, `info`, `napad`, `przejmij` i `napraw`. Wojna, kraj, HP konwoju, odległość i miejsce
na cały ładunek są sprawdzane przez Szlaki przy wykonaniu. Nie powstają dodatkowe kopie towaru w Menu.

Projektowanie (`szlaki-projekt`) obejmuje trasę drogową lub morską, punkty, czyszczenie punktów,
przystanki, skrzynie początku/końca, towar, okres/opłatę oraz start/stop rozkładu. Wymaga
`szlaki.zarzadzanie` **lub dokładnego wpisu UUID w `Szlaki/config.yml: managers`**, tak jak plugin Szlaki.
Własność konkretnej trasy i pozostałe warunki pozostają kontrolowane przez Szlaki. Edycja odbiera
zatwierdzenie. `stop` nie usuwa aktywnego przewozu — zatrzymuje nowe odjazdy.

Zatwierdzanie i audyt (`szlaki-admin`, dostępne też z Admin) wymagają wyłącznie uprawnienia
`szlaki.admin`, zgodnie z komendami i `plugin.yml`. Lista `managers` nie nadaje zatwierdzania ani audytu,
a samo `szlaki.admin` nie nadaje projektowania. Obecność pluginu i alternatywa UUID są sprawdzane
ponownie przy otwarciu/kliknięciu oraz filtrowaniu rozdziałów Poradnika. Nie opisano zakładania firm
graczy jako dostępnej funkcji. Nowości pozostawiono użytkownikowi.

### Walidacja i późniejszy smoke test

Build: ustaw `JAVA_HOME` na JDK 25 i uruchom istniejące `gradlew.bat build`.
Walidacja 2026-10-27 na Temurin JDK 25.0.4.1: **BUILD SUCCESSFUL, 122 testy,
0 niepowodzeń, 0 błędów, 0 pominiętych**, w tym 17 testów dodanych podczas integracji Menu
(7 nowych w rozszerzeniu o Szlaki, stanowiska bazy, rezerwę wagonów i ładunki kolejowe).
Testy sprawdzają wszystkie odnośniki menu/książek, osiągalność z `main`, pojemność i kolizje slotów
przy różnych zestawach pluginów i stanach kraju, gating nowych funkcji, lokalnego maszynistę,
odebranie uprawnienia, wyłączenie pluginu, zgodność katalogów komend oraz zachowanie floty.
Poradnik jest składany z włączonymi wszystkimi rozdziałami: nie przekracza 100 stron,
a wszystkie odnośniki do stron i menu prowadzą do istniejących celów.

Do późniejszego smoke testu zachowano źródła oraz JAR; raport testów jest w
`build/reports/tests/test/index.html`, log budowania w `build/integration-build.log`.
Aktualny pakiet `smoke-ready-2026-10-27-transport/` zawiera `Menu-1.0.0.jar`, `Menu-source.zip`
(źródła, zasoby, wrapper, skrypty Gradle i README), `menus.yml` oraz `SHA256SUMS`.
Wcześniejszy `smoke-ready-2026-10-27/` pozostaje zachowaną wersją sprzed integracji Szlaków i ładunków Kolei.
Po osobnej aktywacji należy sprawdzić GUI na kontach: zwykłego gracza, maszynisty z lokalnego
przydziału bez `kolej.maszynista`, zarządcy z `kolej.admin` oraz administratora Menu bez praw Kolei.
Sprawdź też brakujące/wyłączone pluginy, diagnostykę Panelu, linki Poradnika i dotychczasową flotę.
Dodatkowo: zarządcę Szlaków z samym wpisem UUID, cofnięcie tego wpisu przy otwartym menu,
odmowę audytu bez `szlaki.admin`, rozdzielenie projektowania od zatwierdzania oraz operacje bazy,
rezerwy wagonów i freight na koncie z `kolej.admin`. Do testów przewozu użyj rzeczywistych skrzyń
i przygotowanych tras; samo otwarcie Menu nie potwierdza fizycznego transportu.
Testy automatyczne nie uruchamiają rzeczywistych przejazdów ani walk turniejowych.

## Komendy

Aktualizacja 2026-10-28: Osada → Praca, kopalnie i pory roku zawiera powiązanie fizycznego kontenera
(`/ludnosc magazyn powiaz ID [ID_MAGAZYNU]`), ustawienie magazynu domyślnego, status i informację
o materiale/odstępie pochodni. Powiązanie wymaga Doradcy+; sprawdza to Ludnosc podczas wykonania.
Pełny, brakujący, zniszczony lub niezaładowany magazyn wstrzymuje rzeczywistą produkcję.
Podmenu administratora Ludnosc ma oddzielne odblokowanie po ręcznym audycie nieukończonego zapisu,
z `ludnosc.admin`; nie wydaje ono przedmiotów. Starsze pakiety smoke i ich hashe pozostają zachowane,
ale nie zawierają tej aktualizacji. Szczegóły i ograniczenia trwałości opisuje `ludnosc/README.md`.
Końcowy build tego rozszerzenia: **125 testów, zero błędów/pominiętych**, JDK 25.
Wspólny pakiet do Paper/smoke jest w `ludnosc/smoke-ready-2026-10-28-physical-storage/`.

| komenda | uprawnienie | opis |
|---|---|---|
| `/menu` (`/m`, `/przewodnik`) | `menu.use` (wszyscy) | menu główne |
| `/pomoc [kategoria \| lista \| szukaj <fraza>]` | `menu.use` | centrum pomocy (patrz wyżej) |
| `/menu <id>` | `menu.use` | konkretne menu, np. `/menu kraj` |
| `/menu poradnik [rozdział]` | `menu.use` | Poradnik: spis rozdziałów albo wybrany rozdział |
| `/przewodnik daj` (`/menu daj`) | `menu.guide` (wszyscy) | ponownie daje kompas (najwyżej 1) |
| `/menu reload` | `menu.admin` (op) | przeładowuje `config.yml` i `menus.yml` |
| `/spawn` (`/sp`) | `menu.spawn` (wszyscy) | teleport na spawn po odliczaniu |
| `/ustawspawn` | `menu.admin` (op) | zapisuje spawn (pozycja z obrotem) i ustawia spawn świata |
| `/ustawspawn zbuduj [noair]` | `menu.admin` | podgląd budowy miasteczka-spawnu w twoim miejscu (obrys cząsteczkami) |
| `/ustawspawn zbuduj potwierdz` | `menu.admin` | buduje i konfiguruje spawn (patrz niżej) |
| `/ustawspawn cofnij` | `menu.admin` | cofa ostatnią budowę (do restartu) |
| `/powrot` (`/back`) | `menu.spawn` | raz po wejściu: powrót do ostatniej pozycji |
| `/ustawdom [nazwa]` | `menu.homes` (wszyscy) | zapisuje dom (bez nazwy: `dom`), istniejący nadpisuje |
| `/dom [nazwa]` | `menu.homes` | teleport do domu (bez nazwy: `dom` albo jedyny dom, inaczej lista) |
| `/usundom <nazwa>` | `menu.homes` | usuwa dom |
| `/domy` | `menu.homes` | lista domów, klik = teleport |

Inne uprawnienia: `menu.spawn.bypass` i `menu.homes.bypass` (op) pomijają odliczanie, cooldown, combat tag
i blokadę oblężenia (`menu.homes.bypass` także regułę terenu). Limit domów: `homes.max`, więcej przez
`menu.homes.<n>` (np. `menu.homes.3`), bez limitu `menu.homes.unlimited` (op).

## menus.yml
Każde menu: `title`, `rows`, `type` (`static` / `interactions` / `countries`), `permission`, `requires-plugin`, ramka
(`border`, `border-corners`, `filler`), `auto-slots` (przyciski bez `slot` układają się po kolei, a niepełny rząd
jest wyśrodkowany) i `items`. Przycisk: `slot`, `material`, `name`, `lore`, `glow`, `head: viewer`,
`requires-plugin`, `permission`, `condition` (`has-country` / `no-country` / `can-return`), `keep-open` oraz `action`:

- `command:<komenda>` uruchamia komendę jako gracz,
- `menu:<id>` otwiera inne menu,
- `book:<id>` otwiera książkę z sekcji `books`,
- `url:<adres>` wysyła klikalny link na czat (GUI nie otwiera stron),
- `suggest:<tekst>` wysyła klikalną linię, która wstawia tekst do czatu (np. `/kraj zaloz `),
- `player:<zasięg>:<komenda>` pokazuje głowy graczy w zasięgu, a klik wykonuje `/<komenda> <nick>`,
- `page:prev` / `page:next` przełącza stronę menu z generowaną zawartością (`type: countries`); przycisk jest
  ukryty, gdy poprzedniej/następnej strony nie ma (w innym typie menu – ostrzeżenie przy wczytaniu),
- `close` zamyka menu, `none` to sama dekoracja.

Placeholdery: `<player> <country> <rank> <country_members> <country_chunks> <online> <max> <map_url> <map_link>`
oraz `<cfg:Plugin:ścieżka:domyślnie>`, czyli wartość z configu innego pluginu, np. `<cfg:Kraje:claims.base:16>`.
Kraj jest czytany z `KrajeApi` (ServicesManager) przez refleksję, więc działa też ze starszą wersją Kraje.

Menu `ruchy` (`type: motions`) czyta w locie `dances` i `emotes` z `config.yml` Interakcji (ikona, nazwa, opis):
klik = `/taniec <nr>` albo `/emotka <nazwa>`, PPM przy emotce z `target: optional` = wybór gracza obok
(teksty ikon: `motion-*` w `config.yml` Menu).

Menu `interakcje` (`type: interactions`) czyta interakcje w locie z `config.yml` pluginu Interakcje.
Interakcja z celem otwiera wybór gracza w jej zasięgu. Interakcja bez celu wykonuje się od razu po LPM,
a PPM pozwala wybrać gracza.

### Menu „Państwa” (`type: countries`, tylko admin)
Menu `panstwa` (`/menu panstwa` albo przycisk **⚑ Państwa** z mapą w dolnej ramce menu głównego, slot 51) widzą
tylko gracze z `menu.admin`. Lista jest **dynamiczna**: przy każdym otwarciu Menu czyta wszystkie kraje z
`KrajeApi.getCountries()` (refleksja w `KrajeHook`, jak reszta integracji – starsza wersja Kraje albo jej brak
daje komunikat `countries-unavailable` zamiast błędu).

- Każdy kraj to **sztandar w kolorze kraju** – najbliższy z 16 kolorów barwnika (`DyeColor`, odległość „redmean”).
  Kraje **NPC** mają na sztandarze obwódkę i koło (biało na ciemnym, czarno na jasnym) i dopisek „(NPC)”.
- Lore: władca, obywatele (gracze), teren w chunkach; teksty w `config.yml` (`country-name`,
  `country-lore-player`, `country-lore-npc`, `country-npc-suffix`, `countries-empty`, `countries-unavailable`).
- Kolejność: najpierw kraje NPC, potem kraje graczy, w obu grupach alfabetycznie (polskie sortowanie).
- Jedna strona = liczba `content-slots` (domyślnie 4 × 7 = 28); przy większej liczbie krajów pojawiają się
  przyciski `page:prev` / `page:next`. W nazwach i lore tego menu działają też `<page>`, `<pages>`, `<count>`.
- Klik na sztandar zamyka menu i wykonuje jako gracz `/kraj admin tp <kraj>` (Kraje sprawdza `kraje.admin`):
  natychmiastowy teleport do spawnu stolicy, na bezpieczny blok.

Każde menu typu `countries` działa tak samo – wystarczy `type: countries`, `content-slots` i ewentualnie
przyciski `page:prev` / `page:next`. Na serwerze z już istniejącym `plugins/Menu/menus.yml` dopisz menu `panstwa`
i przycisk `panstwa` w `main` ręcznie (plik nie jest nadpisywany), a potem `/menu reload`.

Książki: `title`, `author`, `intro`, `toc-title`, `sections.<id>` (`title`, `requires-plugin`, `permission`,
`text`). Strony są dzielone automatycznie według szerokości znaków (`book.line-width`, `book.lines-per-page`),
a spis treści przenosi na strony rozdziałów. `"---"` wymusza nową stronę.

## Spawn i domy
- **Odliczanie** (`spawn.warmup-seconds`, `homes.warmup-seconds`, domyślnie 5 s) w action barze („Teleport za 3…”)
  z cząsteczkami portalu i dźwiękiem na końcu. Ruch o więcej niż `teleport.max-move` (0,5 bl.), otrzymanie
  obrażeń albo uderzenie gracza anuluje teleport.
- **Cooldown** po udanym teleporcie: `spawn.cooldown-seconds` / `homes.cooldown-seconds` (30 s, osobno).
- **Blokady**: `teleport.combat-tag-seconds` (10 s) po walce PvP oraz trwające oblężenie na chunku gracza
  (`KrajeApi#getSiege` przez refleksję; bez Kraje blokada nie działa). Sprawdzane na starcie i na końcu odliczania.
- Teleport: chunk celu jest ładowany asynchronicznie (`World#getChunkAtAsync`), potem `Player#teleportAsync`.
- **Punkt spawnu**: `spawn.location` (ustawia `/ustawspawn`; puste = spawn głównego świata).
  `/ustawspawn` ustawia też `World#setSpawnLocation`, więc nowi gracze i odrodzenie bez łóżka trafiają tam.
- `spawn.teleport-new-players: true` – nowy gracz (pierwsze wejście na serwer) po zalogowaniu w nLogin trafia
  na spawn (ten sam mechanizm co Przewodnik).
- `spawn.respawn-at-spawn: false` – odrodzenie po śmierci na spawnie, jeśli gracz nie ma łóżka ani kotwicy.
- **Domy**: `plugins/Menu/data/homes.json` (zapis asynchroniczny, `.bak`). Nazwy: 1–16 znaków `a–z 0–9 _ -`.
  `homes.only-own-or-wild: true` – dom tylko na terenie własnego kraju albo w dziczy (`getCountryAt` /
  `getCountryOf`); gdy teren domu przejdzie do obcego kraju, teleport jest zablokowany z komunikatem.
- Menu → Teleporty: spawn, powrót (warunek `can-return`; także w menu głównym tuż po wejściu), dom, domy,
  ustaw / usuń dom i stolica kraju (`/kraj spawn`, tylko z krajem).

### Miejsce wejścia i opcjonalny /powrot
- Domyślnie `spawn.always-on-join: false`: powracający gracz pojawia się w miejscu wyjścia zapisanym przez
  serwer / nLogin, także blisko spawnu i w innych światach. Menu nie uruchamia dla niego teleportu ani ochrony
  spawnu przed teleportami nLogin. Pierwsze wejście nadal prowadzi na spawn (`spawn.teleport-new-players: true`).
- Na istniejącym serwerze ustaw `spawn.always-on-join: false` w `plugins/Menu/config.yml` i wykonaj
  `/menu reload`. Aktualizacja samego JAR-a nie nadpisuje zapisanej wartości `true`.
- Opcjonalne `spawn.always-on-join: true`: po każdym zalogowaniu (nLogin, ok. 1 s po `AuthenticateEvent`) gracz ląduje
  na spawnie. Gdy w ciągu 4 s inny plugin (np. nLogin przywracający pozycję) przeniesie go teleportem
  `PLUGIN`/`UNKNOWN` dalej niż 3 bl. od spawnu, Menu teleportuje go na spawn jeszcze raz.
- Pozycja przy wyjściu zapisuje się w `plugins/Menu/data/last-positions.json` (tylko gracze zalogowani w tej sesji;
  nie nadpisuje jej wyjście w promieniu `spawn.return-keep-radius` = 48 bl. od spawnu). Przy wyłączaniu serwera
  zapisywani są wszyscy online.
- Przy włączonym `always-on-join` gracz, który już grał, dostaje na czacie klikalne `[⟲ Wróć do ostatniej pozycji]` i action bar. `/powrot`
  (`/back`) działa przez `spawn.return-window-minutes` (10) od wejścia, raz, z odliczaniem i blokadami jak `/spawn`
  (combat tag, oblężenie; bez cooldownu). Nowi gracze nie dostają przycisku.
- Niebezpieczna ostatnia pozycja (lawa, ogień, przepaść, woda, w bloku) → najbliższe bezpieczne miejsce w promieniu
  `spawn.return-safe-radius` (8), inaczej odmowa z komunikatem. Chunki celu ładują się asynchronicznie przed
  sprawdzeniem.

## Budowa spawnu – `/ustawspawn zbuduj`
Schemat miasteczka (`spawn.schem` 69×32×69, `spawn_noair.schem` bez powietrza) i `markers.json` są w jarze;
przy pierwszym starcie trafiają do `plugins/Menu/spawn/` (można je tam podmienić). Wymaga WorldEdit
(softdepend; API WorldEdit tylko w `build/WorldEditPaster`, ładowanej refleksyjnie).

1. Stań na środku przyszłego placu, na gruncie: blok pod tobą stanie się lodestone róży wiatrów
   (punkt wklejenia = twoje stopy, offset schematu `[-34, -5, -34]`).
2. `/ustawspawn zbuduj` (albo `zbuduj noair` – jak `//paste -a`, nie czyści terenu powietrzem). Pokazuje się
   obrys cząsteczkami (czerwony: schemat, żółty: pas wygładzania) i ostrzeżenie, że teren 69×32×69 zostanie
   nadpisany.
3. `/ustawspawn zbuduj potwierdz` (albo klik w `[Potwierdź budowę]`, ważne `spawn.build.confirm-seconds`).
   Chunki ładują się asynchronicznie, potem w jednej sesji WorldEdit:
   - wklejenie schematu,
   - **wygładzenie krawędzi**: w pasie `spawn.build.smooth-width` (4–6) bloków wokół schematu teren jest
     przycinany albo dosypywany (ziemia + trawa) do stożka „poziom gruntu ± odległość od muru”, czyli najwyżej
     1 blok różnicy na blok; drzewa nad przyciętymi kolumnami są usuwane,
   - usunięcie dywanów-markerów NPC (blok pod spodem zostaje).
4. Konfiguracja automatyczna:
   - `spawn.location` i spawn świata na lodestone (kierunek `spawn.build.spawn-yaw`, 0 = południe),
     gamerule `respawn_radius` 0 (`GameRules.RESPAWN_RADIUS`),
   - **NPC Skarbca** (kupiec, bankier; nazwy `spawn.npc.*`): Skarbiec nie ma API do tworzenia NPC, a
     `/skarbiec npc stworz` stawia NPC w pozycji gracza, więc Menu teleportuje admina na marker z właściwym
     kierunkiem (kupiec 0 = południe, bankier −90 = wschód), wykonuje jako on
     `/skarbiec npc stworz <typ> "<nazwa>"` i wraca go na miejsce,
   - **WorldGuard** (softdepend): region `spawn` = schemat + 8 bl. marginesu, cała wysokość świata, priorytet 10,
     bez członków (opy omijają przez uprawnienia WG). Flagi: `build`, `pvp`, `tnt`, `creeper-explosion`,
     `other-explosion`, `fire-spread`, `lava-fire`, `lava-flow`, `chest-access`, `item-frame-rotation`, `sleep`
     = deny; `water-flow`, `use`, `interact`, `ride` = allow; `deny-spawn` na wrogie moby (lista w configu; pusta
     = `mob-spawning deny`); `greeting` / `farewell` (MiniMessage z configu zamieniany na kody `&`);
     `invincible` allow (opcja). Regiony są zapisywane,
   - **Kraje**: `KrajeApi#addProtectedArea("spawn", …, bufor 3 chunki)` – nikt nie założy ani nie zajmie tam kraju
     („To ziemia neutralna”).
5. Podsumowanie na czacie z klikalnym `[Cofnij]`. `/ustawspawn cofnij` cofa bloki (także jako `//undo`),
   region, obszar Kraje, `spawn.location`, spawn świata i gamerule. NPC trzeba usunąć ręcznie
   (`/skarbiec npc usun`). Cofnąć można tylko ostatnią budowę, do restartu serwera.

## Kompas Przewodnik
- Dostaje go każdy gracz przy pierwszym wejściu od instalacji Menu, dopiero po zalogowaniu w nLogin
  (`AuthenticateEvent` i `nLoginAPI` przez refleksję). Bez nLogina dostaje go 3 s po wejściu.
  Trafia do slotu 9, a gdy ten jest zajęty, do pierwszego wolnego.
  Lista graczy, którzy już go dostali: `plugins/Menu/data/guide-received.txt`.
- PPM otwiera menu. Igła wskazuje spawn (`point-to-spawn`). Kompasu nie da się przypiąć do lodestone'a.
- Można go przekładać we własnym ekwipunku. Nie da się go włożyć do skrzyni (także skrzyni sklepu),
  enderchesta, ramki, stojaka, worka, siatki craftingu, garnka ani półki. Lejki go nie przenoszą.
- Wyrzucony znika z komunikatem, jak go odzyskać (`on-drop: delete|block`). Nie wypada przy śmierci
  i wraca po odrodzeniu (`keep-on-death`).

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
