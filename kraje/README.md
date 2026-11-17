# Kraje

System państw dla serwera **MEDIVALRPG** (Paper 26.3). Gracze zakładają kraje, zajmują chunki,
prowadzą dyplomację i wojny z oblężeniami, a granice, kolory i powierzchnia krajów są widoczne na mapie
w przeglądarce (squaremap). Z pluginem **Skarbiec** kraje mają skarbce, koszty, utrzymanie i podatki.
Kraje mogą też być prowadzone przez SI (kraje NPC, plugin Armia). Plugin dodaje tag kraju w tabliście
i na czacie oraz kanały czatu krajowego i sojuszu. Dwa kraje mogą zawrzeć **unię personalną** (wspólny władca),
a kraj może otworzyć **ambasadę** na ziemi innego (patrz „Unia personalna” i „Ambasady”).

## Desanty i przyczółki (wave 13)

`/kraj desant` rozpoczyna oblężenie nadbrzeżnego chunka wroga bez wspólnej granicy. Wymaga wojny,
rangi jak zwykłe oblężenie oraz potwierdzenia własnego transportu z ludźmi lub wojskiem przez Armia
(`LandingSupport` w ServicesManager). Brzeg musi mieć suchą powierzchnię przy wodzie, z różnicą wysokości
najwyżej 3 bloków. Kraje sprawdza najwyżej 320 kolumn już załadowanego terenu i nie wczytuje chunków.
Bez dostawcy transportu desant jest niedostępny. Zwykłe `/kraj oblezenie` zachowuje wymóg wspólnej granicy.
`KrajeApi.startSiege` (AI państw NPC, panel) dla chunka bez wspólnej granicy z atakującym uruchamia desant z tymi samymi
warunkami (wojna, brzeg, limit przyczółków, dowód transportu `LandingSupport`, dopłata); przy wyłączonych desantach
lub `require-adjacent: false` zachowuje się jak dotąd (`siege/SiegeMode`).

Od 2026-10-29 domyślnie `union.npc-auto-refuse: false` i `embassy.npc-auto-decide: false`: na unie i ambasady krajów NPC
odpowiada SI Armii (zasady w `armia/README.md`). Dla istniejącego configu produkcji: `npc-diplomacy-config-merge.json`.

Wygrany desant przekazuje chunk bezpośrednio atakującemu, zapisując przyczółek w `countries.json` razem
ze zmianą właściciela. Z przyczółka można zwyczajnie zajmować sąsiednie wolne chunki i prowadzić dalsze
oblężenia. Domyślnie kraj ma 3 miejsca na odłączone przyczółki; dopłata za desant wynosi 1000 miedziaków
ponad zwykły koszt oblężenia. Klucze: `siege.landing.enabled`, `extra-cost`, `max-bridgeheads`.
Połączenie przyczółków scala je, połączenie z lądem stolicy zwalnia miejsce. Utrata pierwotnego brzegu
przenosi kotwicę na pozostały fragment tego przyczółka, a utrata całego fragmentu zwalnia miejsce.
Porzucanie, podbój i bankructwo nie mogą rozciąć istniejącego fragmentu; administrator nadal może zmieniać teren.

Nadal obowiązuje ochrona stolicy do ostatniego chunka, jedno oblężenie na kraj, normalny postęp walki,
ochrona bloków i łupy. Desant nie może naruszyć chronionych obszarów. Po rozpoczęciu nie wymaga dalszej
obecności statku, gdy siły zeszły na brzeg. Trwające oblężenia kończą się przy wyłączeniu pluginu, a zdobyte
przyczółki pozostają. Stare pliki bez pola `bridgeheads` wczytują się z pustą listą.

## Wymagania

### TNT na ziemi kraju

`protection.player-tnt: true` dopuszcza TNT z potwierdzonym krajem pochodzenia na własnej ziemi oraz
na ziemi wroga w aktywnie obleganym chunku. Każdy blok wroga ponownie sprawdza trwającą wojnę i
`canSiegeBreak`, więc koniec oblężenia/pokój zamyka możliwość zniszczeń. Chronione obszary (w tym spawn)
oraz kontenery pozostają bezpieczne także na własnej ziemi. Globalne `protection.explosions` pozostaje `false`:
creepery, nieznane wybuchy i TNT bez zweryfikowanego pochodzenia nadal nie niszczą krajów.

Pochodzenie jest nadawane tylko po zaakceptowanym zapłonie prawdziwego bloku TNT lub kontrolowanym
wydaniu TNT z własnego dozownika. Gracz musi mieć prawo uruchomić TNT w miejscu zapłonu. Redstone
może uruchomić TNT własnego kraju; zwykłe utworzenie encji TNT przez komendę lub obcy plugin nie daje
jej uprawnień. Znacznik kraju zapisuje się w PDC encji i przechodzi na kolejne ładunki w reakcji łańcuchowej;
przekroczenie granicy nie zmienia kraju pochodzenia. Ten wyjątek nie pozwala podpalać innych bloków wroga.
Wózki z TNT i nieprzypisane ładunki zachowują dotychczasową ochronę krajów.

- Paper **26.3** (testowane na API `26.3.build.218-stable`), Java **25**.
- Opcjonalnie: **squaremap 1.4.3** (`squaremap-paper-mc26.3-1.4.3.jar`). Bez squaremap plugin działa normalnie, tylko bez mapy.
- Opcjonalnie: **Skarbiec** (ekonomia MEDIVALRPG, `softdepend`). Bez niego funkcje ekonomiczne są wyłączone:
  wszystkie koszty wynoszą 0, nie ma podatków ani skarbca, a reszta działa jak dotąd.
- Opcjonalnie: **Ludnosc** i **Armia**. To one zależą od Kraje, więc nie ma ich w `softdepend` Kraje (byłby cykl).
  Kraje czyta ich dane leniwie przez ServicesManager (refleksją, bez linkowania klas): ludność, mieszkania
  i żołnierzy w `/kraj info`, w popupie mapy, w `kraje.json` i w limicie terenu.
- Opcjonalnie: **Postep** (epoki państw). Też zależy od Kraje, więc nie ma go w `depend`/`softdepend` Kraje. Kraje czyta
  `PostepApi` leniwie refleksją, a `EraAdvanceEvent` rejestruje dynamicznie (`PluginManager#registerEvent` z klasą
  z classloadera Postep), gdy Postep się włączy. Zobacz [Epoki (Postep)](#epoki-postep).
- Opcjonalnie: **WorldEdit 7.4.6** (`softdepend`) — dla `/kraj admin zajmij|porzuc|przywroc … zaznaczenie` i dla
  przywracania terenu (`/kraj admin przywroc`, `/kraj admin usun <kraj> przywroc`). Kod WorldEdit jest w osobnych
  klasach (`integration/WorldEditSelection`, `integration/WorldEditRegen`), ładowanych dopiero gdy WorldEdit jest
  włączony; bez niego reszta działa, a te komendy odpowiadają, że wymagają WorldEdit.
- Vault nie jest potrzebny.

## Instalacja

1. Skopiuj `build/libs/Kraje-1.0.0.jar` do `plugins/`.
2. (Opcjonalnie) wgraj squaremap do `plugins/`.
3. Uruchom serwer. Powstanie `plugins/Kraje/config.yml`, a dane krajów trafią do `plugins/Kraje/countries.json`.
4. Ustaw `map-url` w configu na publiczny adres mapy (np. `https://mapa.example.org`) i wpisz `/kraj admin reload`.

Aktualizacja istniejącej instalacji: brakujące komunikaty i nowe klucze konfiguracji (`economy`, `war`, `pvp`,
`siege`, `npc`, `claims.max`, `border.sound`, nowe `map.*`, `messages.*`) biorą się z configu w jarze,
więc `config.yml` nie trzeba usuwać. Stary szablon `messages.info` (bez `<extra>`) dalej działa: nowe linie
(skarbiec, ludność, żołnierze, limit terenu, oblężenia) pojawią się pod nim. Tak samo stary `map.popup` bez
`{extra}` dostaje nowe linie przed ostatnim `</div>`, a szablony bez `{npc}` dostają dopisek „(NPC)” po nazwie.
Dane `countries.json` w formacie 1 i 2 wczytują się bez zmian (nowe pola dostają wartości domyślne), a zapis odbywa się
już w formacie 3 (format 3 dodaje opcjonalne `vassalage` w kraju i `invitations` w korzeniu pliku). Starsza wersja pluginu przeczyta format 3, ale pominie wasalstwa i trwałe zaproszenia (a wersja sprzed formatu 2 — kraje NPC). Stary klucz `messages.help` nie jest już używany. Stary `messages.map-you`
nadal działa (pokaże `█`), ale nowy z jarowego configu (albo usunięcie klucza) daje legendę w dokładnych kolorach mapy.

Dane zapisywane są asynchronicznie (najwyżej raz na sekundę po zmianie) do pliku tymczasowego, a poprzednia
wersja zostaje jako `countries.json.bak`. Jeśli `countries.json` jest uszkodzony, plugin wczyta kopię `.bak`
(uszkodzony plik zostaje jako `countries.json.broken-<czas>`). Jeśli nie da się wczytać ani pliku, ani kopii,
plugin się wyłącza i niczego nie nadpisuje.

## Komendy

`/kraj` (aliasy: `/k`, `/panstwo`). Komendy są rejestrowane jako drzewo Brigadier, więc klient podpowiada
podkomendy i pokazuje szarym tekstem nazwy argumentów (np. `/kraj zapros <gracz>`). Podpowiedzi argumentów mają
dymki z opisem (np. przy `<kraj>` władca i powierzchnia, przy `<kolor>` próbka koloru, przy `<gracz>` „bez kraju” albo
ranga i kraj). Same podkomendy (literały) dymków nie mają, bo podpowiada je klient; opisy komend są w `/kraj pomoc`.
Gałąź `admin` widzą tylko gracze z `kraje.admin`.

`/kraj` bez argumentów i `/kraj pomoc [strona]` pokazują interaktywną pomoc: strona = dział (Teren, Ludzie, Wygląd,
Dyplomacja, Skarbiec, Informacje, Czat, a z `kraje.admin` także Admin). Każda komenda w jednej linii z krótkim opisem;
najechanie pokazuje pełny opis, składnię i wymaganą rangę („Wymaga: Doradca+”), kliknięcie wpisuje komendę do czatu.
Pod listą są klikalne „« poprzednia | następna »” i lista działów. Zamiast numeru można podać nazwę działu
(`/kraj pomoc dyplomacja`). Teksty pomocy: `messages.help-*` w configu.

Przykład (`/kraj pomoc 4`):

```
Kraje — pomoc: Dyplomacja (strona 4/7)
/kraj sojusz <kraj> — zaproponuj lub przyjmij sojusz
/kraj wojna <kraj> — wypowiedz wojnę
/kraj pokoj <kraj> — zaproponuj lub przyjmij pokój
/kraj zerwij <kraj> — zerwij sojusz
/kraj kapitulacja <wróg> [dni] — skapituluj przed wrogiem
/kraj kapitulacja przyjmij <kraj> — przyjmij kapitulację (albo: odrzuc)
/kraj wasale [kraj] — suweren i wasale
/kraj wykup — wykup się z wasalstwa
/kraj zwolnij <wasal> — zwolnij wasala
/kraj unia zaproponuj <kraj> — zaproponuj unię personalną
/kraj unia przyjmij <kraj> — przyjmij unię (albo: odrzuc)
/kraj unia info [kraj] — unia personalna kraju
/kraj ambasada otworz <kraj> — poproś o ambasadę tutaj
/kraj ambasada lista [kraj] — ambasady kraju
/kraj ambasada tp <kraj> — teleport do ambasady
/kraj oblezenie — oblegaj chunk wroga
/kraj oblezenia — trwające oblężenia
« poprzednia | następna »
Teren · Ludzie · Wygląd · Dyplomacja · Skarbiec · Informacje · Czat
```

| Komenda | Kto | Opis |
|---|---|---|
| `/kraj zaloz <nazwa>` | gracz bez kraju | Zakłada kraj, bieżący chunk zostaje stolicą (i punktem spawnu). Nazwa: 3–24 znaki, litery/cyfry/`_`/`-`, unikalna. Wymagany odstęp od cudzych granic (`found-min-distance-chunks`, domyślnie 3). Ze Skarbcem kosztuje `economy.found-cost` (5 zł, z konta, a gdy brak, z monet). |
| `/kraj zajmij` | Doradca+ | Zajmuje bieżący chunk. Musi przylegać bokiem do terytorium; limit terenu opisuje sekcja „Limit terenu”. Ze Skarbcem opłata ze skarbca kraju: `50 md + 5 md × liczba chunków`. |
| `/kraj porzuc` | Doradca+ | Oddaje bieżący chunk. Nie można porzucić stolicy ani rozspójnić terytorium (sprawdzane BFS). |
| `/kraj zapros <gracz>` | Doradca+ | Zaprasza gracza (zaproszenie ważne 5 min, klikalne „[Dołącz]”). |
| `/kraj dolacz <kraj>` | gracz bez kraju | Dołącza, tylko z ważnym zaproszeniem — zwykłym albo trwałym zaproszeniem od zwycięzcy po upadku twojego kraju (patrz „Podbój”). Do krajów NPC tylko przy `npc.players-can-join: true` (wtedy bez zaproszenia). |
| `/kraj odrzuc <kraj>` | gracz bez kraju | Odrzuca zaproszenie (zwykłe albo trwałe); władze kraju dostają informację. |
| `/kraj opusc` | członek | Opuszcza kraj (Władca musi najpierw przekazać władzę). |
| `/kraj wyrzuc <gracz>` | Doradca+ | Wyrzuca członka o niższej randze. Dłużnik zalegający z podatkiem co najmniej `economy.tax.kick-after-days` dni jest wyrzucany „za podatki”, a jego dług przepada. |
| `/kraj awansuj <gracz>` / `degraduj <gracz>` | Władca | Obywatel ↔ Doradca. |
| `/kraj przekaz <gracz> [potwierdz]` | Władca | Przekazuje władzę (wymaga potwierdzenia w 30 s); dawny władca zostaje Doradcą. |
| `/kraj rozwiaz [potwierdz]` (alias `usun`) | Władca | Rozwiązuje kraj (wymaga `/kraj rozwiaz potwierdz`). Skarbiec nie zostaje „zawieszony”: według `disband.treasury` trafia na konto władcy (domyślnie), jest dzielony między członków albo przepada; kraj NPC — przepada. Tak samo przy `/kraj admin usun` i `KrajeApi.deleteCountry`. |
| `/kraj kolor <#RRGGBB\|nazwa>` | Władca | Kolor kraju (np. `#E53935`, `czerwony`, `niebieski`, `zloty`, także nazwy MC, np. `dark_green`). |
| `/kraj opis <tekst>` | Doradca+ | Opis kraju (`/kraj opis -` czyści). |
| `/kraj stolica` | Władca | Przenosi stolicę i spawn na bieżący (własny) chunk. Nie działa na chunku, który jest oblegany. |
| `/kraj spawn` | członek | Teleport do spawnu stolicy (odliczanie 5 s, ruch anuluje). |
| `/kraj info [kraj]` | każdy | Nazwa (z „(NPC)” dla krajów NPC), władca, doradcy, obywatele, powierzchnia („24 chunki • 6 144 bl²”), stolica, sojusze, wojny, data założenia; do tego teren `24/40` z rozbiciem limitu, a gdy są dostępne: podatek, utrzymanie i koszt chunka (stan skarbca widzą tylko członkowie i admini), ostrzeżenie o bankructwie, ludność z mieszkaniami (Ludnosc), żołnierze (Armia), epoka z postępem do następnej (Postep) i trwające oblężenia. |
| `/kraj lista [strona]` | każdy | Ranking krajów po powierzchni. |
| `/kraj mapa` | każdy | Mapa 11×11 chunków w kolorach krajów (hover na kratce) + link do mapy WWW. Każda kratka to ten sam znak `█`, więc wiersze mają równą szerokość; twoja kratka jest biała (na białym kraju jasnożółta). |
| `/kraj pomoc [strona]` | każdy | Interaktywna pomoc (patrz wyżej); `/kraj` bez argumentów pokazuje stronę 1. |
| `/mapa` (alias `/mapaswiata`) | każdy | Klikalny link do mapy świata (`map-url`). |
| `/kraj sojusz <kraj>` | Władca | Propozycja sojuszu; druga strona akceptuje tą samą komendą. Kraj NPC odpowiada sam (SI z Armii). |
| `/kraj wojna <kraj>` | Władca | Wypowiada wojnę (zrywa sojusz). Ze Skarbcem kosztuje `war.declare-cost` (10 zł). Po pokoju z tym samym krajem obowiązuje przerwa `war.redeclare-cooldown-minutes` (12 h). |
| `/kraj pokoj <kraj>` | Władca | Propozycja pokoju; obie strony muszą ją wysłać / przyjąć (kraj NPC odpowiada sam). Możliwy dopiero po `war.min-duration-minutes` wojny (2 h). |
| `/kraj zerwij <kraj>` | Władca | Zrywa sojusz (powrót do relacji neutralnej). |
| `/kraj kapitulacja <wróg> [dni]` | Władca | W wojnie: oferta kapitulacji (wasalstwo na `[dni]`, domyślnie 7, zakres 1–30). Wróg-gracz dostaje klikalne [Przyjmij] [Odrzuć], kraj NPC odpowiada sam (SI). Patrz „Kapitulacja i wasalstwo”. |
| `/kraj kapitulacja przyjmij\|odrzuc <kraj>` | Władca | Odpowiedź na ofertę kapitulacji. |
| `/kraj wasale [kraj]` | każdy | Suweren (do kiedy, trybut, dług, koszt wykupu z przyciskiem) i lista wasali (Władca suwerena ma przy każdym [Zwolnij]). |
| `/kraj wykup [potwierdz]` | Władca wasala | Wykup z wasalstwa ze skarbca (pokazuje cenę, wymaga potwierdzenia, Skarbiec). |
| `/kraj zwolnij <wasal>` | Władca suwerena | Zwalnia wasala przed czasem (dług przepada). |
| `/kraj unia zaproponuj <kraj>` | Władca | Propozycja unii personalnej: zostajesz wspólnym władcą obu krajów (patrz „Unia personalna”). Druga strona dostaje klikalne [Przyjmij] [Odrzuć]. |
| `/kraj unia przyjmij\|odrzuc <kraj>` | Władca | Odpowiedź na propozycję unii. Przy przyjęciu władca proponującego zostaje też waszym władcą, a ty — namiestnikiem (Doradcą). |
| `/kraj unia info [kraj]` (albo samo `/kraj unia`) | każdy | Kraje unii, wspólny władca, namiestnik, data zawarcia, od kiedy można ją zerwać. |
| `/kraj unia rzadz` | wspólny władca | Przełącza, którego kraju dotyczą twoje komendy `/kraj` (zajmij, podatek, wojna, wyplac, przekaz…). |
| `/kraj unia zerwij [potwierdz]` | wspólny władca / namiestnik | Zrywa unię po `union.min-duration-hours`; zrywający płaci `union.break-cost` ze swojego skarbca. |
| `/kraj ambasada otworz <kraj>` | `embassy.min-rank` (Władca) | Stań na ziemi kraju `<kraj>` (pokój albo sojusz) i poproś o ambasadę na tym chunku. Władca gospodarza dostaje [Przyjmij] [Odrzuć]; kraj NPC odpowiada sam. |
| `/kraj ambasada przyjmij\|odrzuc <kraj>` | Władca gospodarza | Odpowiedź na prośbę o ambasadę. |
| `/kraj ambasada zamknij <kraj>` | `embassy.min-rank` | Zamyka waszą ambasadę w `<kraj>` od razu. |
| `/kraj ambasada wydal <kraj>` | Władca gospodarza | Wydala ambasadę `<kraj>` — zamyka się po `embassy.expel-notice-hours` (24 h). |
| `/kraj ambasada lista [kraj]` (albo samo `/kraj ambasada`) | każdy | Ambasady za granicą i u nas, z przyciskami [Teleport] / [Wydal]. |
| `/kraj ambasada tp <kraj>` | `embassy.tp-min-rank` (każdy członek) | Teleport do punktu dyplomaty waszej ambasady (odliczanie jak `/kraj spawn`). |
| `/kraj oblezenie` | Doradca+ (`siege.min-rank`) | Rozpoczyna oblężenie chunka wroga, na którym stoisz (patrz „Oblężenia”). |
| `/kraj oblezenia` | każdy | Lista trwających oblężeń z postępem i pozostałym czasem. |
| `/kraj skarbiec` | członek | Stan skarbca, podatek × płatnicy, dzienne utrzymanie, koszt następnego chunka, ostatnie operacje. Admin może podać `[kraj]`. Wymaga Skarbca. |
| `/kraj wplac <kwota> [konto\|monety]` | członek | Wpłata do skarbca kraju: z konta bankowego, a gdy go brak, z monet. Kwota: `150` (md), `3zl`, `1zł 5sr`. |
| `/kraj wyplac <kwota>` | Władca | Przelew ze skarbca na konto bankowe władcy. |
| `/kraj podatek [kwota]` | członek / Władca | Bez kwoty: podatek i lista dłużników (z przyciskiem [Wyrzuć]). Z kwotą (Władca): dzienny podatek od obywatela-gracza, najwyżej `economy.tax.max`; `0` znosi podatek. |
| `/kraj czat` | członek | Przełącza tryb czatu krajowego (wiadomości widzą tylko członkowie). |
| `/kc <wiadomość>` | członek | Jednorazowa wiadomość na czacie krajowym. |
| `/ks <wiadomość>` | członek | Wiadomość do własnego kraju i wszystkich sojuszników. |

### Admin (`kraje.admin`, domyślnie OP)

| Komenda | Opis |
|---|---|
| `/kraj admin usun <kraj>` | Usuwa kraj. |
| `/kraj admin usun <kraj> przywroc` | Usuwa kraj **i przywraca jego oryginalny teren** (zob. *Przywracanie terenu*): lista chunków jest zapisywana przed usunięciem, regeneracja rusza po `restore.delete-delay-ticks` (domyślnie 3 s — Ludnosc i Armia sprzątają w tym czasie po `CountryDisbandEvent`). Bez `przywroc` — jak dotąd. |
| `/kraj admin wladca <kraj> <gracz>` | Ustawia władcę (gracz nie może należeć do innego kraju; jeśli nie jest członkiem, zostaje dodany). |
| `/kraj admin zajmij <kraj>` | Zajmuje bieżący chunk dla dowolnego kraju (także NPC) bez limitów, kosztów, przylegania i blokady światów. |
| `/kraj admin zajmij <kraj> promien <n>` | Zajmuje wszystkie **wolne** chunki w kwadracie o promieniu `n` (0–16, do 33×33) wokół ciebie. Chunki innych krajów i obszarów chronionych są pomijane. |
| `/kraj admin zajmij <kraj> zaznaczenie` | Jak wyżej, ale chunki dotknięte twoim zaznaczeniem WorldEdit w bieżącym świecie (maks. 4096 chunków). |
| `/kraj admin przylacz <kraj> [pierścienie]` | Poszerza kraj o 1 (albo `n`, maks. 16) pierścień wolnych chunków wokół obecnej granicy, z narożnikami. Każdy pierścień dodaje tylko chunki stykające się bokiem z terenem (kraj zostaje spójny); cudze i chronione są pomijane i nie rosną dalej. Działa w świecie admina (jeśli kraj ma tam teren, inaczej w świecie stolicy), także z konsoli. |
| `/kraj admin porzuc <kraj> [promien <n>\|zaznaczenie]` | Zwalnia chunki wskazanego kraju: bieżący, z kwadratu albo z zaznaczenia. **Stolica nigdy.** Gdy po porzuceniu teren rozpadłby się na części, podgląd ostrzega (liczba części) i wymaga `potwierdz`. |
| `/kraj admin przywroc <kraj> [promien <n>\|zaznaczenie]` | Generuje od nowa z ziarna świata chunki kraju — wszystkie albo tylko te z kwadratu wokół ciebie / z zaznaczenia WorldEdit. Kraj zostaje. |
| `/kraj admin przywroc teren promien <n>\|zaznaczenie` | To samo dla dziczy (chunki bez kraju) wokół ciebie / w zaznaczeniu; chunki krajów są pomijane. |
| `/kraj admin przywroc stop` | Bezpiecznie zatrzymuje trwające przywracanie po bieżącej porcji. |
| `/kraj admin tp <kraj>` | Natychmiastowy teleport (bez odliczania, `teleportAsync`) do punktu spawnu stolicy dowolnego kraju — gracza albo NPC. Chunki wokół punktu są wczytywane asynchronicznie; gdy punkt jest niebezpieczny (lawa, ogień, woda, brak podłoża, zasypany), wybierany jest najbliższy bezpieczny blok w promieniu 8 (awaryjnie: to samo od najwyższego bloku kolumny). Podpowiedzi: wszystkie kraje (najpierw NPC) z dymkiem: władca, „(NPC)”, powierzchnia. Używa go też przycisk w menu „Państwa” (plugin Menu). |
| `/kraj admin porzuc` | Zwalnia bieżący chunk (bez sprawdzania spójności; stolicy nie można). |
| `/kraj admin bypass` | Przełącza omijanie ochrony terenu. |
| `/kraj admin spy` | Przełącza podgląd czatów krajowych i sojuszniczych. |
| `/kraj admin reload` | Przeładowuje config, komunikaty, warstwę mapy, tablistę i połączenie ze Skarbcem (dane krajów zostają w pamięci). |
| `/kraj admin tick` | Natychmiastowe dzienne rozliczenie wszystkich krajów (podatki, utrzymanie, bankructwa). Nie przesuwa godziny zwykłego rozliczenia. |
| `/kraj admin oblezenie` | Kończy bez zdobycia oblężenie chunka, na którym stoisz. |

**Przywracanie terenu** (`przywroc`, `usun <kraj> przywroc`; wymaga **WorldEdit**, bez niego komunikat): kopii
nie ma, więc „przywrócenie” = ponowne wygenerowanie chunków z ziarna świata przez API WorldEdit
(`World.regenerate`, z biomami). **Wszystko** w tych chunkach przepada: budynki graczy, skrzynie z zawartością, a przed
regeneracją każdego chunka usuwane są wszystkie byty poza graczami (wieśniacy/obywatele, manekiny, żołnierze, byty
interakcji, wyświetlacze, stojaki, zwierzęta, przedmioty); wygenerowane od nowa wioski wracają bez wieśniaków.
Chunki **obszarów chronionych** (`protected-areas.json`, np. spawn) nigdy nie są regenerowane — podgląd podaje, ile
pominięto. Zawsze najpierw **podgląd**: liczba chunków, świat, szacowany czas, ostrzeżenie i fioletowy obrys
cząsteczkami w pobliżu; potem **[Przywróć teren]** albo ta sama komenda z `potwierdz`. Wykonanie to jedno globalne
zadanie (drugie naraz jest odrzucane): porcje po `restore.chunks-per-batch` (4) chunków co `restore.pause-ticks` (20),
chunki wczytywane asynchronicznie, każdy sprawdzany ponownie (zmienił właściciela albo stał się chroniony = pominięty),
gracze stojący w regenerowanym chunku przenoszeni na nową powierzchnię (najbliższy bezpieczny blok). Postęp co
`restore.progress-step-percent` (10 %) do admina i do logu, na końcu raport (chunki, czas, usunięte byty, błędy).
Ochrona Kraje i WorldGuard nie blokują regeneracji (WorldEdit pisze bloki bez zdarzeń Bukkit). **Wydajność:**
WorldEdit generuje każdą porcję synchronicznie w tymczasowym świecie — serwer stoi ok. 2 s + 0,5 s na chunk na porcję
(pomiar lokalny: ~1 s/chunk przy porcji 4), więc duże kraje przywracaj poza godzinami gry. Przy `przywroc <kraj>` bez
usuwania wpisy innych pluginów (budynki Ludnosc, rejestr Armii) zostają — do pełnego sprzątania służy `usun <kraj> przywroc`.

**Narzędzia obszarowe** (`zajmij … promien|zaznaczenie`, `porzuc <kraj> …`, `przylacz`): wszystko z `ClaimCause.ADMIN`
(bez limitów i kosztów), z `ChunkOwnerChangeEvent` dla każdego chunka i odświeżeniem mapy/tablisty jak przy zwykłym
zajęciu. Operacja na **więcej niż 16 chunkach** (i każde porzucenie, które rozspójni kraj) najpierw pokazuje
**podgląd**: ile chunków się zmieni, ile pominięto i dlaczego (już należą do kraju / cudze z nazwami krajów / ziemia
neutralna / stolica / poza krajem), a w pobliżu (48 bl., do 20 s) cząsteczki na granicach chunków — zielone (zajęcie)
albo pomarańczowe (porzucenie) wokół zmienianego obszaru, czerwone wokół pominiętych cudzych/chronionych chunków
i stolicy. Klik **[Wykonaj]** (albo ta sama komenda z `potwierdz`, ważne `country.confirm-expiry-seconds`) wykonuje
dokładnie pokazany plan. Duże operacje idą porcjami po **32 chunki na tick**; każdy chunk jest sprawdzany ponownie
(zajęty w międzyczasie = pominięty), a raport na końcu podaje liczbę zmian i nową powierzchnię. Jedna operacja naraz
na admina. Tab-complete podpowiada kraj, `promien`/`zaznaczenie`, promień z rozmiarem kwadratu, liczbę pierścieni
i `potwierdz`.

## Uprawnienia

| Uprawnienie | Domyślnie | Opis |
|---|---|---|
| `kraje.use` | wszyscy | `/kraj`, `/kc`, `/ks`, `/mapa` |
| `kraje.admin` | OP | `/kraj admin …`, podgląd czatów, `/kraj spawn` bez odliczania |

## Rangi

**Władca** (jeden na kraj) › **Doradca** › **Obywatel**. Doradcy zajmują i porzucają teren, zapraszają, wyrzucają
obywateli i zmieniają opis. Władca dodatkowo zarządza rangami, kolorem, stolicą, dyplomacją, przekazaniem władzy i rozwiązaniem kraju.

## Ochrona terenu

Obcy (nie-członkowie) na terenie kraju nie mogą: niszczyć i stawiać bloków, otwierać kontenerów (skrzynie, beczki,
piece, lejki, shulkery, półki, pulpity…), używać drzwi i klap, furtek, przycisków i dźwigni, płyt naciskowych,
wylewać ani nabierać wiader, rozpalać ognia i TNT, bić, strzyc i prowadzić na smyczy zwierząt hodowlanych, niszczyć
ram, obrazów, stojaków na zbroję i pojazdów, ani korzystać z innych interaktywnych bloków (przekaźniki, komparatory,
bloki nutowe, ciasta, doniczki, tabliczki, kowadła, łóżka, kotły, ogniska…). Każdą kategorię wyłącza się osobno
w sekcji `protection`. Sojusznicy mogą używać drzwi, klap i furtek (`allies-use-doors`). Członkowie mogą wszystko.
Wspólny władca unii personalnej jest „swój” na ziemi obu krajów. Chunk **ambasady** jest chroniony dla kraju-gościa:
budują tam tylko jego członkowie (także nie gospodarz), a tłoki, ciecze i wybuchy traktują go jak osobny kraj.

Zabezpieczenia przed obejściami: tłoki przesuwające bloki przez granicę, woda i lawa wpływające do cudzego kraju,
wybuchy (TNT, creepery, łóżka/kotwice) niszczące teren kraju (`explosions: false`), ogień wchodzący z zewnątrz,
lejki i wagoniki z lejkiem wyciągające przedmioty z kraju przez granicę, dozowniki działające w cudzy kraj.

## Granice

Przy przejściu granicy gracz widzi title z nazwą kraju (albo action bar, `border.display: ACTIONBAR`), a w podtytule
(`messages.border-enter-details`) władcę i relację do własnego kraju: „Twój kraj”, „⚔ Jesteście w wojnie!”,
„⚑ Sojusznik”, „♛ Wasza unia personalna” (`border-relation-union`) (neutralny bez dopisku), dla krajów NPC „(NPC)”, a w oblężonym chunku „⚔ Trwa oblężenie!”.
W trybie ACTIONBAR podtytuł jest doklejony w tej samej linii. Przy wyjściu: „Dzicz / Opuszczasz: Nazwa”.
Wejściu towarzyszy cichy dźwięk (`border.sound.enter`, domyślnie `block.note_block.chime`), a na teren wroga lub do
oblężonego chunka groźniejszy (`border.sound.enemy`, domyślnie cichy `event.raid.horn`). Ten sam dźwięk gra
najwyżej raz na `border.sound.cooldown-seconds` (6 s). Pusty klucz wyłącza dźwięk. Sprawdzane tylko przy
zmianie chunka (także jadąc konno, łodzią, po teleporcie).

## Limit terenu

`limit = claims.base + claims.per-member × gracze-członkowie + ⌊claims.per-npc-citizen × obywatele NPC⌋`,
ale nie więcej niż `claims.max` (domyślnie 16 + 8 × gracze + 0,25 × ludność, najwyżej 300; `max: 0` = bez limitu).
Obywatele NPC liczą się tylko z pluginem Ludnosc. Kraje NPC nie mają graczy: ich limit to base + ludność.
Limit obowiązuje gracza i SI (`ClaimCause.NPC`); admin (`/kraj admin zajmij`, `ClaimCause.ADMIN`) go omija.
Komunikat o limicie i `/kraj info` pokazują rozbicie, np. „16 bazowo + 8 × 3 graczy + 2 z ludności”.

## Skarbiec, koszty i podatki (wymaga Skarbca)

- Skarbiec kraju to konto Skarbca `country:<id kraju>`. Wpłaty: `/kraj wplac`, wypłaty: `/kraj wyplac` (Władca).
  Kraje zapisuje 10 ostatnich operacji (`economy.treasury-log-size`) do `/kraj skarbiec`.
- Koszty (miedziaki, 1 sr = 10 md, 1 zł = 100 md): założenie kraju `found-cost` 500 (płaci gracz),
  chunk `claim-cost-base + claim-cost-per-chunk × chunki` (50 + 5 × n, ze skarbca), wojna `war.declare-cost` 1000,
  oblężenie `siege.cost` 500.
- **Dzienne rozliczenie** o `economy.daily-tick-time` (18:00 czasu serwera). Gdy serwer był wyłączony, przegapione
  rozliczenia są nadrabiane po starcie (najwyżej `max-catch-up-days`, 7). Pierwsze uruchomienie tylko ustawia licznik
  i niczego nie pobiera. Kolejność: najpierw podatki, potem utrzymanie.
- **Podatek** (`/kraj podatek <kwota>`, najwyżej `economy.tax.max`, domyślnie 1 zł) pobierany z konta bankowego każdego
  obywatela-gracza (władca jest zwolniony: `exempt-ruler`). Kto nie ma pieniędzy, płaci tyle, ile ma, a resztę dostaje
  jako dług. Dług jest ściągany razem z kolejnymi podatkami. Dłużnik dostaje powiadomienie (także po wejściu na serwer).
  Po `kick-after-days` (3) dniach zaległości można go wyrzucić „za podatki”, a dług przepada. Podatki od obywateli NPC
  zbiera plugin Ludnosc.
- **Utrzymanie**: `upkeep-per-chunk × (chunki − upkeep-free-chunks)` (2 md za chunk, stolica gratis). Jeśli skarbiec go
  nie pokryje, jest to dzień bankructwa (`TreasuryTickEvent.bankrupt`) i ostrzeżenie dla członków. Po `grace-days` (3)
  takich dniach z rzędu kraj przy każdym rozliczeniu traci `bankruptcy-chunks-per-day` (2) chunków najdalszych od stolicy
  (licząc kroki przez terytorium, więc teren zostaje spójny; nigdy stolicy; `ClaimCause.BANKRUPTCY`).
- Bez Skarbca: koszty 0, brak podatków, utrzymania i skarbca, komendy skarbca odpowiadają „Ekonomia jest wyłączona”.

## Wojna i PvP

- Wojna kosztuje (`war.declare-cost`), trwa co najmniej `war.min-duration-minutes` (2 h), zanim można zawrzeć pokój,
  a po pokoju ten sam kraj może znów wypowiedzieć wojnę dopiero po `war.redeclare-cooldown-minutes` (12 h).
- **PvP** (`pvp.mode`): domyślnie `vanilla`, czyli zwykłe PvP wszędzie, a Kraje w ogóle nie blokuje walki między
  graczami (ani na terenie kraju, ani w tym samym kraju, ani między sojusznikami). Opcja `pvp.friendly-fire: false`
  blokuje tylko ranienie członków własnego kraju i sojuszników. Tryb `war-only` (opcjonalny): gracze różnych krajów
  walczą tylko, gdy ich kraje są w wojnie; gracze bez kraju według `pvp.without-country`, a `pvp.wilderness-free: true`
  zezwala na walkę w dziczy. Obsługiwane są ciosy, pociski, podpalenie pociskiem oraz szkodliwe mikstury (rzucane
  i zalegające). Wojna niezależnie od PvP włącza oblężenia; ochrona bloków działa jak wcześniej.

## Oblężenia

- `/kraj oblezenie` na chunku wroga: oba kraje muszą być w wojnie, chunk musi przylegać do terenu atakującego
  (`siege.require-adjacent`), a jego zdobycie nie może rozciąć terytorium obrońcy (oblega się chunki z brzegu).
  Stolicę można oblegać tylko wtedy, gdy jest ostatnim chunkiem kraju (`siege.capital-anytime: false`). Koszt
  `siege.cost`, czas najwyżej `siege.max-duration-minutes` (30 min), jedno oblężenie na kraj naraz.
- **Postęp** co sekundę: siła atakujących w chunku (gracze kraju i sojuszników, `siege.allies-help`, plus żołnierze
  NPC ze wszystkich `SiegeContributor` w ServicesManager) porównana z siłą obrońców. Przewaga atakujących podnosi
  postęp, przewaga obrońców go obniża, a przy równowadze albo braku walki postęp stoi. Jeden atakujący bez obrońców
  zdobywa chunk w `siege.capture-seconds` (300 s); większa przewaga przyspiesza, najwyżej `max-speed-multiplier` (×3).
  Nie liczą się gracze w trybie kreatywnym, widza ani martwi.
- **Pasek bossbar** (nazwy krajów, %, stan atak/obrona/brak walki, siły, czas) widzą uczestnicy w promieniu
  `siege.bossbar-radius-chunks` (6) i każdy w oblężonym chunku. Start, zdobycie, odparcie i upadek kraju są ogłaszane
  całemu serwerowi. Na mapie jest osobna warstwa „Oblężenia” z czerwoną ramką chunka i ikoną ⚔.
- W oblężonym chunku atakujący i ich sojusznicy mogą wykonywać akcje z `siege.build.allowed-actions` (niszczenie
  i stawianie bloków, drzwi, furtki, przyciski, płyty), z wyjątkiem bloków z `siege.build.excluded-blocks` (nazwy
  i `#tagi`) i kontenerów (`exclude-containers`). Wybuchy dalej nie niszczą terenu krajów (`protection.explosions`).
- **Zdobycie** (100 %): chunk przechodzi do atakującego (`ClaimCause.CONQUEST`). Jeśli nie przylega już do jego terenu,
  wraca do dziczy, żeby atakujący nie miał rozspójnionego terytorium. Zdobycie ostatniego chunka oznacza upadek kraju.
  Przy `capital-anytime: true` zdobyta stolica przenosi się na najbliższy pozostały chunk obrońcy.
- Oblężenie kończy się bez zdobycia, gdy minie czas, nastanie pokój, chunk zmieni właściciela, kraj zniknie,
  admin je przerwie albo serwer się wyłączy (oblężenia nie są zapisywane).

## Oblężenia w tle (państwa NPC, 2026-10-29)

Armia rozgrywa wojny krajów NPC daleko od graczy jako abstrakcyjne bitwy (`armia/README.md`, „Wojny państw NPC w tle”).
Kraje tylko sprawdza zasady i stosuje wynik (`siege/AbstractConquest`), przez wąski most bez zmiany wspólnego API —
publiczne metody `KrajePlugin#abstractSiegeBegin`, `#abstractSiegeConquer`, `#abstractRevolt` (główny wątek, wynik
`OK…`/`DENIED:<powód>`). Zasady jak przy zwykłym oblężeniu: wojna, jedno oblężenie kraju, chunk nie jest oblegany
fizycznie, bez rozcięcia obrońcy, stolica tylko jako ostatni chunk, obszary chronione wyłączone, koszt `siege.cost`
(+ `siege.abstract.expedition-extra-cost` za wyprawę bez wspólnej granicy), `ClaimCause.CONQUEST`, łupy
`conquest.loot-*`, upadek przy ostatnim chunku. Wyprawa na skraj ziem wroga zakłada przyczółek (limit
`siege.landing.max-bridgeheads`). Odmowa, gdy gracz jest w `siege.abstract.player-block-radius-chunks` (8) — wtedy walka
fizyczna. **Ziemi krajów graczy nie zdobywa się w tle** (`siege.abstract.allow-player-countries: false`). Nic nie wczytuje
chunków; ogłoszenia (zbiorcze) robi Armia. Bunt (`abstractRevolt`) oddaje świeżo zdobyty chunk dawnemu właścicielowi,
gdy z nim graniczy, bez rozcinania posiadacza i nigdy jego stolicy. Klucze: `siege.abstract.*`.

## Podbój: łupy i obywatele upadłego kraju

- **Łupy** (tylko ze Skarbcem; bez niego cała ta część jest wyłączona). Przy każdym zdobytym chunku zwycięzca dostaje
  `conquest.loot-per-chunk` (5 %) **aktualnego** skarbca obrońcy, zaokrąglone w dół do miedziaka (kolejne chunki biorą
  5 % z tego, co zostało). Przy **upadku** (zdobycie ostatniego chunka) najpierw łup za ten chunk, potem
  `conquest.loot-on-fall` (75 %) reszty, a pozostałe pieniądze według `conquest.fall-remainder`: `destroy` (domyślnie,
  przepadają) albo `members` (po równo na konta bankowe byłych członków-graczy; niepodzielna reszta po 1 md dla
  pierwszych). Każdy transfer jest w historii obu skarbców (`/kraj skarbiec`, operacja „łupy wojenne”) i dostają go
  w komunikacie członkowie obu krajów.
- **Obywatele upadłego kraju**: każdy były członek-gracz dostaje zaproszenie do kraju zwycięzcy, ważne
  `conquest.citizen-invite-hours` (24 h), zapisywane w `countries.json` (przetrwa restart, działa dla graczy offline).
  Gracz online dostaje je od razu, offline — po wejściu do gry: klikalne `[Dołącz do X]` / `[Odrzuć]`
  (`/kraj dolacz X` / `/kraj odrzuc X`). Władca zwycięzcy (online) dostaje informację, ilu graczy zaproszono.
  Do zwycięzcy NPC tylko przy `npc.players-can-join: true`. Wyłączenie: `conquest.citizen-invites: false`.
- **Rozwiązanie bez wojny** (`/kraj rozwiaz`, `/kraj admin usun`, `KrajeApi.deleteCountry`): `disband.treasury`
  `ruler` (domyślnie, na konto władcy), `members` (po równo) albo `destroy`; kraj NPC — zawsze przepada.

## Kapitulacja i wasalstwo

- **Oferta**: w czasie wojny Władca `/kraj kapitulacja <wróg> [dni]` (domyślnie `vassal.default-days` 7, zakres
  `min-days`–`max-days` 1–30, oferta ważna `offer-expiry-seconds` 300 s). Kraj, który już jest wasalem, nie może
  kapitulować przed kolejnym. Władca/doradcy wroga dostają warunki i przyciski [Przyjmij] [Odrzuć]
  (`/kraj kapitulacja przyjmij|odrzuc <kraj>`). Oferta do kraju NPC odpala `CapitulationOfferEvent`, a odpowiada SI Armii.
- **Po przyjęciu** wojna od razu się kończy (relacja NEUTRAL, bez minimalnego czasu wojny, oblężenia stają), pokonany
  zostaje **wasalem**, zwycięzca **suwerenem**, na podaną liczbę dni. Ogłoszenie dla całego serwera.
- **Trybut** przy dziennym rozliczeniu (razem z podatkami i utrzymaniem): `vassal.tribute-share` (25 %) dziennego
  przychodu z podatków (podatki graczy zebrane tego dnia + dzienny podatek NPC z Ludnosc), co najmniej
  `vassal.tribute-min` (20 md), ze skarbca wasala do skarbca suwerena. Warunki są zamrażane w chwili kapitulacji.
  Brak pieniędzy: wasal płaci, ile ma, reszta to dług doliczany do kolejnych trybutów. Po `vassal.debt-war-days` (3)
  dniach długu suweren może wypowiedzieć wasalowi wojnę **bez kosztu i bez przerwy** po pokoju (dostaje klikalną
  podpowiedź). Bez Skarbca wasalstwo działa, ale bez trybutu i wykupu.
- **Ograniczenia** (wszystkie w configu): wasal nie wypowie wojny suwerenowi (`forbid-war-on-overlord`) ani jego
  sojusznikom (`forbid-war-on-overlord-allies`), nie zawrze sojuszu z wrogiem suwerena (`forbid-alliance-with-overlord-enemies`,
  sprawdzane przy propozycji i przyjęciu); suweren może zaatakować własnego wasala tylko za zaległy trybut
  (`overlord-war-only-for-debt`). Gdy suweren wchodzi w wojnę, wasale dostają powiadomienie (`notify-overlord-wars`),
  a przy `auto-join-overlord-wars: true` (domyślnie wyłączone) automatycznie do niej dołączają (bez kosztu; nie gdy są
  z przeciwnikiem w sojuszu).
- **Koniec wasalstwa**: po czasie (ogłoszenie), wykup `/kraj wykup` (cena = `vassal.buyout-multiplier` (1,5) × dzienny
  trybut × pozostałe dni (rozpoczęty dzień się liczy) + dług; płaci skarbiec wasala suwerenowi), zwolnienie
  `/kraj zwolnij <wasal>`, upadek albo rozwiązanie którejkolwiek strony, wojna między nimi. Dług przepada.
- Widoczność: `/kraj wasale`, `/kraj info` („Wasal: X (do …)” / „Wasale: …”), podtytuł przy wejściu na teren
  („♔ Wasz suweren” / „Wasz wasal”), popup mapy (`map.popup-vassal`, `map.popup-vassals`, placeholdery
  `{overlord}` `{until}` `{vassals}`) i `kraje.json` (`overlord`, `vassalUntil`, `vassals`).
- Wasalstwa zapisują się w danych kraju-wasala (`vassalage` w `countries.json`); oferty kapitulacji żyją w pamięci
  (restart je kasuje, jak propozycje sojuszu).

## Unia personalna

Dwa suwerenne kraje z **jednym wspólnym władcą**. Każdy zachowuje swój teren, skarbiec, obywateli, podatki i prawa.

- **Zawarcie**: Władca A `/kraj unia zaproponuj B` → władca B `/kraj unia przyjmij A` (klikalne [Przyjmij] [Odrzuć],
  ważne `union.proposal-expiry-seconds`). Przy przyjęciu skarbiec A płaci `union.form-cost` (2000 md). Władca A zostaje
  **wspólnym władcą** (monarchą): w A jest zwykłym członkiem, a w B ma rangę Władcy jako członek tytularny (gracz należy
  nadal tylko do A — czat, tablista, `/kraj info` bez argumentu dotyczą A). Dotychczasowy władca B zostaje Doradcą —
  **namiestnikiem**. Kraje są od razu w sojuszu. Ogłoszenie dla całego serwera i `UnionFormedEvent`.
- **Warunki**: oba kraje istnieją, A jest krajem gracza; żaden nie jest już w unii; nie są w wojnie; **żaden nie jest
  wasalem** (najpierw wykup/koniec wasalstwa — to wyklucza też „unię” suwerena z własnym wasalem); po końcu unii tych
  samych krajów obowiązuje przerwa `union.reform-cooldown-hours` (48 h, pamięć serwera).
- **Rządzenie**: wspólny władca przełącza komendy `/kraj` między krajami przez `/kraj unia rzadz` (np. `zajmij`, `podatek`,
  `wojna`, `wyplac`, `przekaz` działają wtedy na B). Na ziemi obu krajów jest „swój” (ochrona). Limit terenu B nie liczy
  go jako dodatkowego członka. Ostatni zwykły członek B nie może odejść ani zostać wyrzucony (ktoś musi przejąć tron).
- **Wojna**: kraje unii nie mogą wypowiedzieć sobie wojny ani zerwać sojuszu (`/kraj unia zerwij`). Wojna wypowiedziana
  jednemu z nich wciąga partnera jako **obrońcę** (`union.defend-partner: true`, bez kosztu; nie gdy partner ma z agresorem
  sojusz/wojnę albo wasalstwo). Wojna wypowiedziana PRZEZ kraj unii wciąga partnera tylko przy `union.join-offensive-wars`.
  Pokój każdy kraj zawiera osobno.
- **Koniec unii**: `/kraj unia zerwij` — wspólny władca albo namiestnik B (secesja, `union.regent-can-secede`), dopiero po
  `union.min-duration-hours` (72 h), za `union.break-cost` (1000 md) ze skarbca zrywającego; potwierdzenie kliknięciem.
  Automatycznie: wspólny władca przestaje być władcą któregoś kraju (`/kraj przekaz`, `/kraj admin wladca`), wojna między
  krajami (admin/SI), któryś kraj zostaje wasalem (kapitulacja), upadek albo rozwiązanie kraju. Tron B wraca do namiestnika
  (gdy go nie ma — do członka o najwyższej randze, najdawniej przyjętego; gdy nie ma nikogo, B przechodzi pod zarząd NPC).
  Sojusz zostaje (`union.keep-alliance-after`). `UnionDissolvedEvent` z przyczyną.
- **Kraje NPC**: unia gracza z krajem NPC jest dozwolona (`union.allow-npc`), ale domyślnie Kraje od razu odrzuca ją w imieniu
  SI (`union.npc-auto-refuse: true`). Przy `false` Kraje wysyła `UnionProposalEvent` i czeka na `respondUnion` z Armii.
  Kraj NPC w unii zostaje pod zarządem SI (wspólny władca jest tam tylko tytularny, bez komend). Kraj NPC nie może być
  stroną proponującą (jego „władca” nie jest graczem).
- **Widoczność**: `/kraj unia info`, linia w `/kraj info`, podtytuł przy wejściu na teren, popup mapy (`map.popup-union`,
  `{union}` `{monarch}`) i `kraje.json` (`union`).
- **Dane**: unia zapisuje się w danych kraju-partnera (`union` w `countries.json`, a wspólny władca jest na jego liście
  członków z rangą RULER). Propozycje żyją w pamięci (restart je kasuje). Przy starcie Kraje sprawdza spójność unii
  (np. ręcznie zmieniony władca) i w razie błędu rozwiązuje ją, oddając tron namiestnikowi.

## Ambasady

Kraj-gość może mieć **ambasadę** na jednym chunku ziemi gospodarza. Chunk zostaje terenem gospodarza (mapa, limit terenu),
ale jest chroniony dla gościa: budują tam tylko członkowie gościa.

- **Otwarcie**: stań na ziemi gospodarza i wpisz `/kraj ambasada otworz <kraj>` (ranga `embassy.min-rank`, domyślnie Władca).
  Relacja musi być w `embassy.allowed-relations` (pokój albo sojusz). Władca gospodarza dostaje [Przyjmij] [Odrzuć]
  (`/kraj ambasada przyjmij|odrzuc <kraj>`, ważne `embassy.request-expiry-seconds`). Przy otwarciu skarbiec gościa płaci
  `embassy.open-cost` (300 md) **do skarbca gospodarza**. Miejsce, w którym stałeś, to punkt dyplomaty (`/kraj ambasada tp`).
- **Warunki**: jedna ambasada gościa u danego gospodarza, najwyżej `embassy.max-per-country` (3) za granicą i
  `embassy.max-hosted` (5) u jednego gospodarza; nie na stolicy (`embassy.allow-capital`), nie na chunku innej ambasady,
  oblężonym ani w obszarze chronionym. Gospodarz nie może porzucić chunka ambasady.
- **Kraj NPC jako gospodarz** odpowiada sam (`embassy.npc-auto-decide: true`): przyjmuje przy relacjach z
  `embassy.npc-accept-relations` (domyślnie tylko sojusz), inaczej odmawia. Przy `false` wysyłany jest `EmbassyRequestEvent`
  i Kraje czeka na `respondEmbassy` z Armii.
- **Zamknięcie**: gość — `/kraj ambasada zamknij <kraj>` (od razu); gospodarz — `/kraj ambasada wydal <kraj>` z okresem
  wypowiedzenia `embassy.expel-notice-hours` (24 h, 0 = od razu). Automatycznie: **wojna** między krajami, utrata chunka
  przez gospodarza (zdobycie, admin, bankructwo), rozwiązanie/upadek któregoś kraju. `EmbassyClosedEvent` z przyczyną.
- **Do czego służą**: punkt dyplomaty (teleport), hak handlowy (`KrajeApi#getTradeBonus`: `embassy.trade-bonus` 5 %,
  w unii `union.trade-bonus` 10 %; Kraje sam go nigdzie nie nalicza) i warunek **wspólnych projektów** innych pluginów
  (`KrajeApi#canStartJointProject`, np. przyszła droga lub kolej do innego królestwa: unia zawsze, poza tym relacja co
  najmniej `embassy.joint-projects.min-relation` + ambasada; `require-own-embassy: true` wymaga ambasady budującego u celu).
- **Widoczność**: `/kraj ambasada lista`, linie „Ambasady za granicą / u nas” w `/kraj info`, popup mapy
  (`map.popup-embassies`, `{embassies}`: → nasze za granicą, ← obce u nas) i `kraje.json` (`embassies`, `hostedEmbassies`).
- **Dane**: ambasady zapisują się w danych kraju-gościa (`embassies` w `countries.json`); prośby żyją w pamięci.

## Chorągwie krajów (przygotowane 2026-10-29, nieaktywne)

Każdy kraj ma animowaną, powiewającą chorągiew (model BetterModel) nad stolicą i przy swoich ambasadach. Bez własnego
obrazka kraj ma **domyślną flagę w swoim kolorze**: jeden wspólny model `kraje_flaga_domyslna` z szarą teksturą (pole,
obramowanie, krzyż) barwioną w grze kolorem kraju (`tint`), więc zmiana koloru i nowe kraje nie wymagają przebudowy paczki.

**Własny obrazek** wgrywa Władca albo Doradca (`flags.upload-rank`) w panelu WWW (`#/kraj/<id>/flaga`). Serwer sprawdza
plik po nagłówku (tylko PNG/JPG, do 160 KB, 16×10 … 4096×4096, nagłówek czytany przed dekodowaniem), przycina go do
proporcji 1,6, skaluje uśrednianiem do **64×40**, ustawia przezroczystość 0/1 (dziury, np. jaskółczy ogon, są dozwolone,
flaga musi być w ≥ 50% kryjąca), ogranicza kolory do 32 (median cut) i zapisuje **nowy PNG bez metadanych** (tylko
IHDR/IDAT/IEND: bez EXIF, GPS, tekstów, profili). Obrazki: `plugins/Kraje/flags/images/<id>.png`, stan i historia:
`plugins/Kraje/flags/flags.json` (zapis tmp + atomowa podmiana + `.bak`).

**Moderacja:** wgrany obrazek czeka w kolejce i **nie trafia do gry ani na mapę**, dopóki nie zatwierdzi go moderator:
admin panelu, UUID z `flags.moderators` (domyślnie WorldSteward) albo gracz z `kraje.flagi.moderuj`. Moderator widzi podgląd
w panelu (`#/flagi`) albo listę w grze (`/kraj flaga admin kolejka`), zatwierdza albo odrzuca z powodem (3–200 znaków,
widocznym dla kraju; odrzucony plik jest usuwany). Zatwierdzenie dotyczy konkretnego `imageId` — jeśli w międzyczasie
wgrano nowszy obrazek, decyzja jest odrzucana. Historia (20 wpisów) pozwala wrócić do wcześniej **zatwierdzonej** flagi
bez ponownej moderacji; „domyślna” działa natychmiast.

**Paczka zasobów:** zatwierdzona flaga staje się plikiem `plugins/BetterModel/models/kraje_flaga_<12 hex id kraju>.bbmodel`
(drzewce z dwóch kości, złota głowica, płótno z 8 pasów połączonych łańcuchem, animacja `powiewanie` 2 s; `model_identifier`
= id obrazka). Pliki powstają dopiero przy przebudowie, która jest **zbiorcza**: pierwsza zmiana czeka `debounce-seconds`
(60 s) na kolejne, a dwie przebudowy dzielą co najmniej `min-interval-minutes` (15 min). Przebudowa to
`BetterModel.platform().reload()` w zadaniu asynchronicznym (jak `/bm reload`); **Interakcje** same wykrywają nowy
`build.zip` (zdarzenie BetterModel + kontrola co 30 s), publikują go z nowym SHA-1 w URL i wysyłają graczom online.
Kraje nic w Interakcjach nie zmienia. Nowy model pokazuje się `switch-delay-seconds` (20 s) po przebudowie, żeby gracze
zdążyli pobrać paczkę; do tego czasu stoi chorągiew domyślna. Po każdej udanej przebudowie (także cudzej `/bm reload`)
chorągwie są tworzone od nowa. Modele krajów, które wróciły do domyślnej flagi, są usuwane przy następnej przebudowie
(bez osobnego pobierania). Bez BetterModel wszystko poza modelami działa (panel, moderacja, mapa).

Domyślny model jest zapisywany w `onLoad`, przed startem BetterModel, więc wchodzi do paczki startowej bez dodatkowej
przebudowy. Przy pierwszej aktywacji paczka zmienia się raz (nowy model) — to i tak dzieje się przy restarcie.

**Miejsca:** stolica — miejsce ustawione `/kraj flaga postaw` (na własnej ziemi), inaczej 2 bloki od dzwonu wioski
w stolicy lub sąsiednich chunkach (państwa NPC z wiosek), inaczej obok punktu spawnu kraju, inaczej środek chunka stolicy.
Ambasada — chorągiew gościa (skala 0,75) przy punkcie dyplomaty. Chorągwie to wyłącznie pakiety (display entities
BetterModel), nic nie jest zapisywane w świecie; istnieją tylko przy wczytanym chunku i gdy ktoś jest w promieniu
`banners.view-distance` (96 bl.).

**Mapa:** w popupie kraju na squaremap jest ikona flagi (`web/kraje-flagi/<id>.png`, tylko zatwierdzone albo domyślne).
Stary szablon `map.popup` bez `{flag}` dostaje ikonę na początku.

### Komendy

| Komenda | Kto | Opis |
|---|---|---|
| `/kraj flaga` | członek | stan flagi, oczekująca, powód odrzucenia, link do panelu |
| `/kraj flaga postaw` / `auto` | Doradca+ | chorągiew stolicy tutaj / miejsce automatyczne |
| `/kraj flaga domyslna potwierdz` | Doradca+ | powrót do flagi w kolorze kraju (od razu) |
| `/kraj flaga historia`, `przywroc <id>` | Doradca+ | historia; powrót do wcześniej zatwierdzonej flagi |
| `/kraj flaga wycofaj` | Doradca+ | wycofanie oczekującej |
| `/kraj flaga admin kolejka\|status\|przebuduj` | moderator | kolejka, stan paczki, przebudowa teraz (min. 60 s od poprzedniej) |
| `/kraj flaga admin zatwierdz <kraj>`, `odrzuc <kraj> <powód>`, `postaw <kraj>` | moderator | decyzje, chorągiew stolicy dowolnego kraju |

### Most dla innych pluginów (statki i dowódcy w Armii)

Bez zmiany wspólnego API — publiczne metody `KrajePlugin` wołane refleksją (główny wątek), jak most Armii do oblężeń:

```java
Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
Method show = kraje.getClass().getMethod("flagShow", String.class, String.class, Location.class, float.class, boolean.class, float.class);
// hoistTop = szczyt drzewca/masztu (górna krawędź płótna przy maszcie); pole=false ukrywa drzewce (maszt statku)
boolean ok = (boolean) show.invoke(kraje, "armia:statek:" + shipId, countryId.toString(), mastTop, yaw, false, 1.0f);
kraje.getClass().getMethod("flagHide", String.class).invoke(kraje, "armia:statek:" + shipId);
kraje.getClass().getMethod("flagHidePrefix", String.class).invoke(kraje, "armia:");   // np. w onDisable Armii
```

`flagShow` z tym samym uchwytem tylko przesuwa model (tanie — można wołać przy każdym ruchu statku), kraj i wygląd
(własna/domyślna flaga, zmiana po przebudowie paczki) rozwiązuje Kraje. Płótno ma 2 × 1,25 bloku przy skali 1 i powiewa
w stronę +X modelu (obrót przez `yaw`); `scale` 0,25–2. Dla dowódcy w marszu: `pole=true`, `scale≈0,5`, punkt nad głową.
Model statku nie musi znać tekstur flag. Uchwyty nie są trwałe: po restarcie Kraje Armia wywołuje `flagShow` ponownie.

### Konfiguracja `flags:`

`enabled`, `upload-rank` (ADVISOR|RULER), `moderators` (UUID), `upload-cooldown-seconds` (60), `history-size` (20),
`image.*` (max-kb 160, max-source-pixels, max-source-side, min-width/height, max-colors 32),
`pack.*` (min-interval-minutes 15, debounce-seconds 60, force-min-interval-seconds 60, switch-delay-seconds 20,
model-prefix, default-model), `banners.*` (capital, embassy, view-distance 96, always-spawn — tylko testy), `map-icons`.
Komunikaty: `messages.flag-notice|flag-ok|flag-error|flag-line`. Panel: metody `country.flag.*` i `admin.flag.*`
(PANEL.md, „Chorągwie krajów”).

### Sztandary w grze z flagi kraju (przygotowane 2026-10-29, nieaktywne)

Decyzja użytkownika (29.10 ~19:00): flaga kraju ma być też sztandarem w grze — dwie formy. Obie biorą wyłącznie
**zatwierdzony** obrazek (`current` w `flags.json`); wgrany, ale niezatwierdzony nigdy nie wpływa na grę. Kraj bez
własnego obrazka dostaje sztandar z flagi domyślnej w swoim kolorze.

**A. Sztandar vanilla z przybliżonym wzorem** (`/kraj flaga sztandar`, `flag/BannerFit`). Zwykły przedmiot `*_BANNER`:
kolor tła + do 6 warstw. Lico sztandaru ma **20×40 tekseli** — sprawdzone w jarze klienta 26.3
(`BannerFlagModel`: `texOffs(0,0)`, `addBox(-10, 0, -2, 20, 40, 1)` na teksturze 64×64; region x 1..20, y 1..40 w
`textures/entity/banner/*.png`). Kolory: 16 barwników z `DyeColor.textureDiffuseColor` (sprawdzone w jarze). Maski
wzorów `src/main/resources/flag/banner-masks.txt` to wyłącznie kanał alfa tego regionu (kształty, bez grafiki),
wygenerowane `tools/extract_banner_masks.py` z jara klienta; używane są tylko 32 wzory z tagu
`banner_pattern/no_item_required` (krosno bez przedmiotu wzoru — bez creepera, czaszki, Mojang itd.).
Algorytm: flaga 64×40 → ściśnięcie do 20×40 (średnia powierzchniowa; pasy poziome i pionowe zostają na miejscu,
przezroczyste dziury się nie liczą) → przeszukiwanie wiązkowe (szerokość 12) po (wzór, barwnik) na każdym z 6 poziomów,
minimalizujące sumę odległości CIELAB ΔE76; potem dokładne dopracowanie najlepszych kandydatów rendererem jak w grze
(mieszanie sRGB z cieniem tkaniny) — zmiana tła, podmiana albo usunięcie warstwy. Warstwa zostaje tylko, gdy obniża
błąd o więcej niż kara (0,4% błędu jednolitego koloru). Liczy się poza głównym wątkiem (~0,5 s na obraz), wynik
w pamięci na `imageId`; po starcie wzory wszystkich krajów liczą się po kolei. Miara jakości: średni ΔE (podany w
`/kraj flaga sztandar podglad`, w panelu i w testach razem z ΔE jednolitego koloru i „podłogą palety” — każdy teksel
w najbliższym barwniku). Przykłady (testy `BannerFitTest`): Polska ΔE 15,5 przy podłodze palety 14,5 (tło czerwone,
górna połowa biała); krzyż 17,7 (jednolity 37,3); Niemcy 22,3 (60,4); Francja 16,6 (58,6).

Sztandar A można postawić, przybić do tarczy (zwykła receptura tarczy, ≤6 warstw) i dać wojsku: most dla Armii
`KrajePlugin#flagBanner(String countryId)` → `ItemStack` sztandaru albo null (wzór jeszcze się liczy — zapytać później).
Armia nie jest zmieniana w tym pakiecie (łatka opisana w pakiecie `deploy-flags-banners-20261029/ARMIA-PATCH.md`).

**B. Przedmiot „Sztandar kraju” z dokładnym obrazkiem** (`/kraj flaga przedmiot`, `flag/StandardService`,
`flag/StandardAssets`). Pod spodem `WHITE_BANNER` z komponentem `item_model` = `kraje:sztandar/<12 hex kraju>`
(komponent 1.21.4+, w API 26.3 `DataComponentTypes.ITEM_MODEL`) i PDC `kraje:sztandar-przedmiot`. Pliki paczki:
`assets/kraje/items/sztandar/<id>.json`, `assets/kraje/models/item/sztandar/<id>.json` (drzewce, złota głowica,
płótno 20×12,5 z dokładną flagą; transformacje dla ręki, ekwipunku, ziemi i `fixed`),
`assets/kraje/textures/item/sztandar/<id>.png` (ta sama tekstura 64×64 co chorągiew). Trafiają do paczki **tym samym
zbiorczym mechanizmem**: Kraje subskrybuje `PluginStartReloadEvent` BetterModel i przy każdej przebudowie (także
startowej przy `ServerLoadEvent` i cudzej `/bm reload`) dokłada pliki — tylko dla krajów, których plik
`kraje_flaga_<hex>.bbmodel` istnieje (te pisze wyłącznie przebudowa zatwierdzonych flag), plus wspólny model
`kraje:sztandar/domyslna` barwiony kolorem kraju (`custom_model_data` kolor 0). Limit częstotliwości przebudowy bez zmian.
Przedmioty w ekwipunkach i postawione sztandary przełączają się na własną flagę po przebudowie i opóźnieniu
`switch-delay-seconds` (gracze zdążą pobrać paczkę), a na domyślną od razu po „domyślnej”.

Postawienie: PPM na bloku wywołuje zwykłe `BlockPlaceEvent` (ochrona Kraje, WorldGuard, spawn decydują jak przy bloku);
Kraje anuluje postawienie bloku i stawia `ItemDisplay` (model w kontekście `fixed`) + niewidzialny `Interaction`
(0,5×3 bloku) — żadna z tych encji nie blokuje chodzenia. Obie są trwałe (zapisane z chunkiem, PDC
`kraje:sztandar-postawiony`, para w `kraje:sztandar-para`); przy wczytaniu chunka połówka bez pary jest usuwana
(przedmiot wypada), a wygląd odświeżany. Shift + PPM zabiera (stawiający, obywatel kraju sztandaru, gospodarz ziemi z
prawem niszczenia, `kraje.admin`). Limit `placed.max-per-chunk` (4). Przedmiotu B nie da się użyć w krośnie, do tarczy
ani w craftingu/crafterze (to nie jest zwykły sztandar).

**Opłata (zamknięta pula):** materiały z ekwipunku zamawiającego + kwota ze skarbca kraju przez
`TreasuryService.charge` → `Skarbiec.withdraw`, który przy zamkniętej puli przenosi ją na `world:payments` (nic nie
powstaje ani nie znika). Domyślnie A: 6 wełny (dowolny kolor) + 1 patyk + 100 md, 8 dziennie na kraj;
B: 6 wełny + 2 patyki + 500 md, 4 dziennie na kraj. Dzień liczony w strefie `time-zone` (Europe/Warsaw); licznik w
`plugins/Kraje/flags/standards.json` (osobny plik — stary jar go ignoruje). Zamawia ranga `flags.upload-rank`
(Władca/Doradca); państwa NPC — tylko administracja. Moderatorzy: `/kraj flaga admin sztandar|przedmiot <kraj>` bez
opłaty i limitu (nic nie przechodzi przez pieniądze).

**Panel:** `country.flag.get` → `current.banner` (PNG podglądu sztandaru A, 80×160), `current.bannerText`,
`current.bannerDeltaE` (null, dopóki wzór się liczy). Widok `#/kraj/<id>/flaga` pokazuje go obok obrazka
(`panel/web/public/js/views/flag.js`, wymaga nowego buildu WWW — nieobowiązkowe; bez niego panel działa jak dotąd).

| Komenda | Kto | Opis |
|---|---|---|
| `/kraj flaga sztandar` | Doradca+ | sztandar vanilla z przybliżonym wzorem (opłata, limit) |
| `/kraj flaga sztandar podglad` | członek | opis wzoru i jego błąd ΔE, bez wydawania |
| `/kraj flaga przedmiot` | Doradca+ | przedmiot „Sztandar kraju” z dokładnym obrazkiem (opłata, limit) |
| `/kraj flaga admin sztandar|przedmiot <kraj>` | moderator | to samo dla dowolnego kraju, bez opłaty |

Konfiguracja `flags.standards`: `enabled`, `time-zone`, `banner|item.{enabled, treasury-cost, materials, daily-limit}`
(`materials` jako `["WOOL:6", "STICK:1"]`, `WOOL` = dowolna wełna), `placed.max-per-chunk`. Brak sekcji w configu
produkcji = domyślne z jara.

## Kraje NPC

Tworzy je plugin Armia (`KrajeApi.createNpcCountry`). Mają flagę `npc`, syntetycznego władcę (nie gracza) z imieniem
`rulerName` i zwykle żadnych graczy. Gracze nie mogą do nich dołączać (`npc.players-can-join`). Są chronione jak
każdy kraj, a w info, liście, na mapie, w podtytule granicy i w `kraje.json` mają oznaczenie „(NPC)”. Sojusz i pokój
z krajem NPC działają zwykłymi komendami: Kraje wysyła `RelationProposalEvent`, a odpowiada SI. Gdy admin ustawi
graczowi władzę w kraju NPC (`/kraj admin wladca`), kraj staje się zwykłym krajem gracza.

## Obszary chronione (np. spawn)

Obszar chroniony to prostokąt w blokach (włącznie) w danym świecie, poszerzony o bufor chunków z każdej strony
(0–16; plugin Menu po zbudowaniu spawnu zgłasza obszar `spawn` z buforem 3). Na każdym chunku, który dotyka
prostokąta albo bufora, **nie można założyć kraju** (`/kraj zaloz` — liczy się chunk stolicy) **ani zająć terenu**
(`/kraj zajmij`). Te same zasady obowiązują w API: `claimChunk` z każdą przyczyną poza `ADMIN` oraz
`createNpcCountry` bez `force` — państwa NPC i SI nie zajmą spawnu. Admin (`/kraj admin zajmij`, `ClaimCause.ADMIN`,
`force`) może pojedynczy chunk; narzędzia obszarowe (`promien`, `zaznaczenie`, `przylacz`) obszary chronione pomijają. Chunki zajęte przed dodaniem obszaru nie są odbierane.

Gracz widzi komunikat `messages.protected-area` (`<area>` = id obszaru): „To ziemia neutralna (spawn). Nie można jej
zająć.” Obszary zapisują się w `plugins/Kraje/protected-areas.json` (zapis w tle: plik tymczasowy, kopia `.bak`,
atomowa podmiana; uszkodzony plik nie zostanie nadpisany). Na squaremap, w warstwie „Kraje”, obszar (z buforem) to
szary prostokąt z podpisem „Spawn” (dla id `spawn`) albo id obszaru.

```java
api.addProtectedArea("spawn", "world", -40, -40, 40, 40, 3); // id [a-z0-9_-]{1,32}; to samo id nadpisuje obszar
api.removeProtectedArea("spawn");                            // NOT_FOUND, gdy nie ma takiego obszaru
```

## Mapa (squaremap)

Na każdym świecie z włączonym squaremap pojawia się warstwa **„Kraje”** (można ją wyłączyć w panelu, domyślnie widoczna).
Chunki każdego kraju są scalane w wielokąty — obrys zewnętrzny i dziury, poprawnie także dla kilku rozłącznych kawałków
(zamiast osobnego kwadratu na każdy chunk). Wypełnienie w kolorze kraju z przezroczystością 0.55, obrys pełnym kolorem.
Po najechaniu widać nazwę i władcę, po kliknięciu: władcę, doradców, liczbę członków, powierzchnię w chunkach i bl²,
stolicę, sojusze i wojny, a jeśli są dostępne: oznaczenie NPC, ludność z mieszkaniami, żołnierzy i trwające oblężenia
(`map.popup-npc/-population/-army/-vassal/-vassals/-union/-embassies/-siege`, wstawiane w `{extra}`). Stolica ma ikonę korony. Mapa odświeża się po
każdej zmianie (debounce 2 s), bez timera. Teksty dymków (HTML) ustawisz w `map.hover-tooltip` i `map.popup`.
Warstwa **„Oblężenia”** (`map.siege-layer-name`) pokazuje oblężone chunki (ramka + ikona ⚔, dymek `map.siege-tooltip`
z postępem); odświeża się przy starcie i końcu oblężenia oraz co `siege.map-refresh-seconds` (15 s).

### `kraje.json` dla panelu strony mapy

Przy każdym odświeżeniu warstwy (ten sam debounce) i przy starcie Kraje zapisuje plik `kraje.json` w katalogu web
squaremapa: `plugins/squaremap/` + `settings.web-directory.path` z `plugins/squaremap/config.yml` (domyślnie `web`;
ścieżka może być absolutna). Dane zbierane są na głównym wątku, zapis idzie w tle do pliku tymczasowego
z atomową podmianą. Wyłączenie: `map.export-json: false`. Bez squaremapa nic nie jest zapisywane.

```json
{
  "updated": 1792960083162,
  "countries": [
    {
      "id": "uuid", "name": "Aldoria", "color": "#E53935", "npc": false, "description": "…",
      "ruler": "Zbyszko_77", "members": 3, "population": 12, "soldiers": 5, "chunks": 24, "areaBlocks": 6144,
      "founded": 1792960000000, "capital": { "world": "minecraft_overworld", "x": 120, "z": -340 },
      "allies": ["Norvik"], "wars": ["Borsk"], "sieges": 1,
      "overlord": null, "vassalUntil": null, "vassals": ["Celin"],
      "union": { "partner": "Borsk", "monarch": "Zbyszko_77", "since": 1792960000000 },
      "embassies": ["Norvik"], "hostedEmbassies": []
    }
  ]
}
```

`union` to unia personalna (`null`, gdy brak): nazwa drugiego kraju, wspólny władca i data zawarcia; `embassies` — kraje,
w których ten kraj ma ambasady, `hostedEmbassies` — kraje z ambasadą u niego.
`overlord` to nazwa suwerena, gdy kraj jest wasalem (inaczej `null`), `vassalUntil` — koniec wasalstwa w ms epoch
(albo `null`), `vassals` — nazwy wasali tego kraju (pusta lista, gdy brak). Pola `null` są zawsze zapisywane.

Kraje są posortowane malejąco po `chunks`. `population` i `soldiers` wynoszą 0, gdy nie ma Ludnosc/Armii.
Z pluginem Postep dochodzą `era`, `eraName`, `progress` (bez niego `null`) i zawsze `displayName` (zob. Epoki).
`capital.world` to identyfikator świata squaremapa, czyli klucz świata z `:` zamienionym na `_` (np. `minecraft_overworld`,
jak `name` w `tiles/settings.json`). `capital.x`/`z` to współrzędne blokowe punktu spawnu stolicy (albo środka chunka
stolicy, gdy spawn leży poza nim). Teksty są zwykłe, bez formatowania; `sieges` to liczba oblężeń, w których kraj
atakuje albo się broni. Ludność i żołnierze trafiają do pliku przy najbliższym odświeżeniu mapy (zmiana w krajach).

## Tablista

Jedynym właścicielem tablisty jest Kraje (`TablistService`): nazwa na liście (`player.playerListName`), kolejność
(`Player#setPlayerListOrder`), nagłówek i stopka. Bez scoreboard teams, nametagi nad głową nie są ruszane. Całość
wyłącza `tablist.enabled: false`.

### Styl rozbudowany (`tablist.style: extended`, domyślny)

- **Nazwa na liście** (`tablist.extended.name-format`): `[AFK]`, ikona rangi, ikona epoki kraju (Postep), tag kraju w
  jego kolorze i nick, np. `♛ ⚜ [Aldoria] Nick`. Gracz bez kraju: `tablist.extended.no-country-name-format`.
- **Sortowanie** jak dotąd (`tablist.sort`): kraje alfabetycznie, w kraju po randze, gracze bez kraju na końcu.
- **AFK**: Kraje sam śledzi aktywność (ruch do innego bloku, obrót głowy, czat, komenda, interakcja). Po
  `afk-seconds` (domyślnie 300 s, `0` = wyłączone) przed nazwą pojawia się `afk-tag`; znika od razu po ruchu,
  komendzie lub interakcji (po czacie — przy najbliższym odświeżeniu).
- **Nagłówek** (`tablist.extended.header`, lista linii): MEDIVALRPG, gracze online/limit (+ liczba AFK), ping gracza i
  TPS (kolor: zielony/żółty/czerwony), data (`time-zone`, `date-format`) i pora roku z dniem pory (Ludnosc,
  `LudnoscApi#season`), linki do mapy (`map-url`) i panelu (`panel-url`, domyślnie
  https://panel.example.workers.dev/). Tablista nie jest klikalna, więc linki są pokazane jako tekst bez `https://`.
- **Stopka** (`tablist.extended.footer`): kraj gracza w kolorze, ranga, epoka kraju (Postep), saldo konta gracza w
  Skarbcu (format Skarbca: zł/sr/md), tytuł ostatniej nowości z pluginu Nowosci (`news`, `news-max-length`) i rotująca
  wskazówka (`tips`, zmiana co `tip-seconds`; wszyscy widzą tę samą).
- **Brak danych = brak linii**: linia używająca niedostępnego placeholdera znika (brak Ludnosc → brak pory roku, brak
  Skarbca → brak salda, brak kraju → brak rangi/epoki, pusty `panel-url` → brak panelu). Fragment w `[[ ... ]]` jest
  opcjonalny — znika sam, reszta linii zostaje. Pusta linia `""` zostaje jako odstęp.
- Placeholdery linii: `<player> <online> <max> <afk_count> <ping> <tps> <date> <game_time> <game_day> <season>
  <season_icon> <season_day> <season_days> <country> <rank> <rank_icon> <era> <era_icon> <balance> <news> <tip>
  <map_link> <map_url> <panel_link> <panel_url>`.
- **Wydajność**: przeliczenie co `refresh-ticks` (domyślnie 40 = 2 s, min. 20). Nagłówek/stopka i nazwa są wysyłane
  tylko, gdy treść danego gracza się zmieniła; kolejność tylko przy zmianie. Saldo, epoka, nazwa kraju i pora roku są
  trzymane `cache-seconds` (5 s), nowość 30 s. Zmiany krajów (dołączenie, ranga, kolor, epoka) odświeżają tablistę w
  następnym ticku. Stan gracza (cache, AFK) jest usuwany przy wyjściu.

| Klucz (`tablist.extended.`) | Domyślnie |
|---|---|
| `refresh-ticks` | `40` |
| `cache-seconds` | `5` |
| `name-format` | `<afk><rank_icon><era_icon><color>[<country>]</color> <white><player>` |
| `no-country-name-format` | `<afk><gray>[Dzicz]</gray> <white><player>` |
| `afk-tag` / `afk-seconds` | `<gray>[AFK]</gray> ` / `300` |
| `header` / `footer` | listy linii z `config.yml` w jarze |
| `panel-url` | `https://panel.example.workers.dev/` |
| `time-zone` / `date-format` | `Europe/Warsaw` / `d MMMM yyyy` |
| `season-icons.wiosna/lato/jesien/zima` | `✿` / `☀` / `❦` / `❄` |
| `news` / `news-max-length` | `true` / `48` |
| `tip-seconds` / `tips` | `60` / 7 wskazówek |

### Styl klasyczny (`tablist.style: classic`)

Dawny wygląd: `tablist.format`, `tablist.no-country-format`, `tablist.header`, `tablist.footer`,
`tablist.footer-map-line`, `tablist.refresh-ticks` (domyślnie 100). Placeholdery jak wcześniej (`<online>`, `<max>`,
`<country>`, `<map_link>`…), w nazwie dodatkowo `<afk>`.

### Koordynacja z Postep

Postep (`TablistDecorator`) dopisuje ikonę epoki tylko wtedy, gdy Kraje sam jej nie pokazuje. Nowy Postep sprawdza
format nazwy **aktywnego** stylu Kraje: przy `extended` — `tablist.extended.name-format`, przy `classic` (albo starym
Kraje bez `tablist.style`) — `tablist.format`. Zawiera `<era>`/`<era_icon>` → Postep przywraca nazwy i nie dopisuje
ikony. Stary Postep sprawdza tylko `tablist.format`: przy rozbudowanym stylu i starym formacie bez `<era_icon>` ikona
byłaby podwójna — wdrażaj razem nowy Postep albo ustaw w Postep `tablista.dopisz-ikone: false`.

### Aktywacja na produkcji (stary `plugins/Kraje/config.yml`)

Produkcyjny plik ma tylko klasyczne klucze (`tablist.header`/`footer` jako tekst, `format` bez `<era_icon>`,
`refresh-ticks: 100`). Nowe klucze (`tablist.style`, cała sekcja `tablist.extended`) są czytane z `config.yml` w jarze,
więc po podmianie jara **bez zmian w pliku** włącza się styl rozbudowany z domyślnymi liniami, a stare
`tablist.header/footer/footer-map-line/refresh-ticks/format/no-country-format` są wtedy ignorowane (zostają tylko dla
`style: classic`). Przy aktywacji:

1. Wgraj razem nowy Kraje i nowy Postep (albo w Postep `tablista.dopisz-ikone: false`), żeby nie było podwójnej ikony.
2. Opcjonalnie dopisz w `plugins/Kraje/config.yml` `tablist.style: extended` i skopiuj sekcję `tablist.extended` z
   jara, jeśli chcesz zmieniać linie/wskazówki — bez tego obowiązują wartości z jara.
3. Zostawienie dawnego wyglądu: `tablist.style: classic`.
4. Zmiany pliku wczytuje `/kraj admin reload` (bez restartu).

## Epoki (Postep)

Z pluginem Postep (bez niego wszystko poniżej jest puste albo wyłączone, nic się nie psuje):

- **Tablista**: `<era_icon>` (kolorowa ikona epoki kraju ze spacją, np. `⚜ `) i `<era>` (plakietka `eraBadge`, np.
  „⚜ Renesans”) w `tablist.format`. Domyślny format pokazuje ikonę przed tagiem kraju:
  `<rank_icon><era_icon><color>[<country>]</color> <white><player>`. Gdy format zawiera `<era>`/`<era_icon>`, Postep
  sam przestaje dopisywać swoją ikonę (i tak warto ustawić w Postep `tablista.dopisz-ikone: false`). Przy stylu
  rozbudowanym liczy się `tablist.extended.name-format` (domyślnie z `<era_icon>`); przy `style: classic` stary
  `tablist.format` bez `<era_icon>` oznacza, że ikonę nadal dopisuje Postep (zob. Tablista → Koordynacja z Postep).
- **`/kraj info`**: linia `info-era` z plakietką i postępem do następnej epoki w % (`info-era-max` w ostatniej epoce).
- **Mapa**: `{era}` (ikona + nazwa w kolorze epoki), `{era_icon}`, `{progress}` (np. `52%`, w ostatniej epoce `maks.`)
  w `map.hover-tooltip`/`map.popup`; linia `map.popup-era` w `{extra}`. Przy Postep mapa odświeża się też co 5 min
  (postęp rośnie stale) i od razu po `EraAdvanceEvent` (razem z tablistą).
- **`kraje.json`**: `era` (1–4), `eraName`, `progress` (0–1, trzy miejsca po przecinku) — `null` bez Postep — oraz
  `displayName` (nazwa z tytułem epoki).
- **Tytuły zależne od epoki** (`era-titles`, domyślnie tylko kraje NPC): w wyświetlaniu (tablista, komunikaty, dymki
  podpowiedzi, `/kraj info`, granice, czat, mapa) początek nazwy jest zamieniany według epoki, np. w Nowoczesności
  `Królestwo_Mazowsza` → `Rzeczpospolita_Mazowsza`, `Księstwo` → `Republika`, `Cesarstwo` → `Imperium` (epoka 3)
  → `Federacja` (epoka 4). Prawdziwa nazwa się nie zmienia: komendy, podpowiedzi, API i pliki używają jej dalej, a
  `/kraj info` pokazuje ją w linii „Nazwa w komendach”. Zamiany są kumulowane (epoka 4 próbuje list 4, 3, 2, 1),
  dłuższy początek wygrywa, `_` i spacja są równoważne. `npc-only: false` obejmie też kraje graczy, `enabled: false`
  wyłącza tytuły.
- Ogłoszeń o nowej epoce Kraje nie wysyła — robi to Postep.

## Czat

- Czat ogólny: `♛ [Aldoria] Nick: wiadomość` — tylko podmiana `ChatRenderer` w `AsyncChatEvent` (event nie jest anulowany,
  podpisy czatu działają). Treść gracza jest wstawiana jako komponent i nigdy nie jest parsowana jako MiniMessage.
- Hover na tagu: nazwa, władca, liczba obywateli, powierzchnia; kliknięcie wykonuje `/kraj info <nazwa>`.
- Czat krajowy: `/kraj czat` (tryb) albo `/kc <wiadomość>`; czat sojuszu: `/ks <wiadomość>`. Kopia trafia do konsoli
  (`chat.log-to-console`) i do adminów z `/kraj admin spy`.
- Formaty i ikony rang (`rank-icons`) w configu. Całość: `chat.enabled`.

## Konfiguracja

Najważniejsze sekcje `config.yml` (pełny plik z komentarzami powstaje przy pierwszym starcie):

| Klucz | Domyślnie | Opis |
|---|---|---|
| `country.found-min-distance-chunks` | `3` | Odstęp nowego kraju od cudzych granic |
| `country.blocked-worlds` | `[]` | Światy bez krajów |
| `country.invite-expiry-seconds` | `300` | Ważność zaproszeń |
| `claims.base` / `claims.per-member` | `16` / `8` | Limit chunków |
| `claims.per-npc-citizen` / `claims.max` | `0.25` / `300` | Chunki z ludności NPC, twardy limit (0 = brak) |
| `economy.*` | | Ekonomia (tylko ze Skarbcem): koszty, utrzymanie, bankructwo, godzina rozliczenia, podatki |
| `war.*` | `1000` / `120` / `720` | Koszt wojny, minimalny czas wojny (min), przerwa przed ponowną wojną (min) |
| `pvp.mode` / `pvp.friendly-fire` | `vanilla` / `true` | Tryb PvP (`vanilla` albo `war-only`), ranienie swoich i sojuszników |
| `pvp.without-country` / `pvp.wilderness-free` | `false` / `false` | Tylko `war-only` |
| `siege.*` | | Oblężenia: koszt, ranga, czas, szybkość, przyleganie, stolica, sojusznicy, bossbar, budowanie |
| `npc.players-can-join` | `false` | Dołączanie graczy do krajów NPC |
| `conquest.loot-per-chunk` / `conquest.loot-on-fall` | `0.05` / `0.75` | Łupy za chunk i przy upadku (Skarbiec) |
| `conquest.fall-remainder` | `destroy` | Reszta skarbca upadłego kraju: `destroy` albo `members` |
| `conquest.citizen-invites` / `conquest.citizen-invite-hours` | `true` / `24` | Zaproszenia obywateli upadłego kraju do zwycięzcy |
| `disband.treasury` | `ruler` | Skarbiec rozwiązanego kraju: `ruler`, `members`, `destroy` |
| `vassal.enabled` | `true` | Kapitulacje i wasalstwo |
| `vassal.tribute-share` / `vassal.tribute-min` | `0.25` / `20` | Trybut: udział w dziennych podatkach, minimum (md) |
| `vassal.default-days` / `min-days` / `max-days` | `7` / `1` / `30` | Długość wasalstwa |
| `vassal.offer-expiry-seconds` | `300` | Ważność oferty kapitulacji |
| `vassal.buyout-multiplier` | `1.5` | Mnożnik ceny wykupu |
| `vassal.debt-war-days` | `3` | Dni długu, po których suweren może wypowiedzieć wojnę za darmo |
| `vassal.forbid-war-on-overlord` / `-overlord-allies` / `forbid-alliance-with-overlord-enemies` | `true` | Ograniczenia wasala |
| `vassal.overlord-war-only-for-debt` | `true` | Suweren atakuje wasala tylko za zaległy trybut |
| `vassal.notify-overlord-wars` / `vassal.auto-join-overlord-wars` | `true` / `false` | Wasal a wojny suwerena |
| `map.popup-vassal` / `map.popup-vassals` | | Linie popupu mapy o wasalstwie |
| `union.enabled` / `union.allow-npc` / `union.npc-auto-refuse` | `true` / `true` / `true` | Unie personalne; unia z NPC; Kraje odrzuca w imieniu NPC |
| `union.form-cost` / `union.break-cost` | `2000` / `1000` | Koszt zawarcia (skarbiec wspólnego władcy) i zerwania (skarbiec zrywającego), md |
| `union.min-duration-hours` / `union.reform-cooldown-hours` | `72` / `48` | Minimalny czas unii i przerwa przed ponowną unią tych samych krajów |
| `union.defend-partner` / `union.join-offensive-wars` | `true` / `false` | Partner broni napadniętego / dołącza do ataku |
| `union.regent-can-secede` / `union.keep-alliance-after` | `true` / `true` | Secesja namiestnika; sojusz po końcu unii |
| `union.proposal-expiry-seconds` / `union.trade-bonus` | `300` / `0.10` | Ważność propozycji; hak handlowy dla unii |
| `embassy.enabled` / `embassy.allowed-relations` | `true` / `[NEUTRAL, ALLIANCE]` | Ambasady; relacje, w których wolno je otwierać |
| `embassy.max-per-country` / `embassy.max-hosted` / `embassy.allow-capital` | `3` / `5` / `false` | Limity i stolica |
| `embassy.open-cost` / `embassy.request-expiry-seconds` / `embassy.expel-notice-hours` | `300` / `300` / `24` | Opłata dla gospodarza, ważność prośby, wypowiedzenie |
| `embassy.min-rank` / `embassy.tp-min-rank` | `RULER` / `CITIZEN` | Kto otwiera/zamyka, kto się teleportuje |
| `embassy.npc-auto-decide` / `embassy.npc-accept-relations` | `true` / `[ALLIANCE]` | Odpowiedzi krajów NPC |
| `embassy.trade-bonus` / `embassy.joint-projects.*` | `0.05` / `NEUTRAL`, `false` | Haki dla innych pluginów (handel, wspólne projekty) |
| `map.popup-union` / `map.popup-embassies` | | Linie popupu mapy o unii i ambasadach |
| `border.sound.*` | chime / raid horn | Dźwięki wejścia na teren kraju / wroga i ich cooldown |
| `map.export-json` | `true` | Plik `kraje.json` dla panelu strony mapy |
| `map.siege-layer-name`, `map.siege-tooltip`, `map.popup-*`, `map.npc-suffix` | | Warstwa oblężeń i dodatkowe linie popupu |
| `spawn.enabled` / `spawn.warmup-seconds` | `true` / `5` | `/kraj spawn` |
| `protection.*` | `true` | Kategorie ochrony (patrz wyżej) |
| `protection.explosions` | `false` | Czy wybuchy niszczą teren krajów |
| `border.display` | `TITLE` | `TITLE` albo `ACTIONBAR` |
| `map.fill-opacity` | `0.55` | Przezroczystość wypełnienia |
| `map.refresh-debounce-ticks` | `40` | Opóźnienie odświeżenia mapy |
| `map-url` | `""` | Link do mapy WWW (czat `/kraj mapa`, tablista) |
| `tablist.*`, `chat.*` | włączone | Tablista (`tablist.style`, `tablist.extended.*` — zob. Tablista) i czat |
| `messages.*` | | Wszystkie komunikaty (MiniMessage, `<prefix>` = `<gold>[Kraje]</gold>`) |
| `messages.help-*` | | Pomoc: nagłówek, linia, hover, przyciski, nazwy działów (`help-categories`) i opisy komend (`help-entries.<komenda>.short/description/rank`) |
| `messages.suggest-*` | | Dymki przy podpowiedziach argumentów |

## API dla innych pluginów

Interfejsy są we wspólnym module `medival-api/kraje-api` (pakiety `pl.medivalrpg.kraje.api`, `.api.event`,
`pl.medivalrpg.kraje.model.Rank/Relation`) i są wkompilowane do jara Kraje (`java.srcDir`). Pozostałe API
(Skarbiec, Ludnosc, Armia) są tylko `compileOnly` z `medival-api.jar`, z odfiltrowanymi pakietami Kraje.

```java
KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
api.getCountryAt(location);                 // Optional<KrajInfo>
api.getCountryAt("world", cx, cz);
api.getCountryOf(player.getUniqueId());
api.getClaims(countryId, "world");          // klucze ((long) cx << 32) | (cz & 0xFFFFFFFFL)
api.getRelation(countryA, countryB);        // NEUTRAL / ALLIANCE / WAR
api.getSieges();                            // Collection<SiegeInfo>
// główny wątek:
api.createNpcCountry("Norvik", 0x43A047, location, "Król Bolesław", false);
api.claimChunk(id, "world", cx, cz, ClaimCause.NPC); // te same zasady i koszty co gracz
api.propose(npcId, playerCountryId, Relation.NEUTRAL);
api.respondToProposal(npcId, playerCountryId, Relation.ALLIANCE, true);
api.startSiege(npcId, "world", cx, cz);
```

`KrajInfo` to niezmienny snapshot (id, nazwa, kolor, władca, członkowie z rangami, liczba chunków, sojusznicy, wrogowie,
`npc`, stolica, podatek, konto skarbca `country:<id>`). Metody odczytu są bezpieczne z dowolnego wątku. Metody zmieniające
dane wołaj na głównym wątku (inaczej zwracają `INVALID` i niczego nie zmieniają). Stosują te same reguły, koszty,
ogłoszenia i eventy co komendy graczy; `KrajeResult.message()` to krótki polski opis błędu.

Eventy (`pl.medivalrpg.kraje.api.event`, zawsze po wprowadzeniu zmiany, główny wątek): `CountryCreateEvent`,
`CountryDisbandEvent` (rozwiązanie, usunięcie, upadek), `ChunkOwnerChangeEvent` (zajęcie, porzucenie, admin, zdobycie,
bankructwo, stolica przy zakładaniu, wszystkie chunki przy rozwiązaniu; przyczyna w `ClaimCause`), `RelationChangeEvent`,
`RelationProposalEvent` (każda propozycja sojuszu/pokoju, też między graczami), `SiegeStartEvent`, `SiegeEndEvent`,
`TreasuryTickEvent` (każdy kraj przy każdym dziennym rozliczeniu, tylko ze Skarbcem), `CapitulationOfferEvent`,
`VassalageStartEvent`, `VassalageEndEvent` (szczegóły niżej).

Rozszerzenia dla panelu WWW (metody `default` w `KrajeApi`, główny wątek, te same reguły/koszty/komunikaty co komendy;
uprawnień wywołującego nie sprawdzają — robi to plugin Panel): `getDescription`, `members` (nicki, rangi, długi
podatkowe), `setColor`, `setDescription`, `setTax`, `setRank` (ADVISOR/CITIZEN), `kickMember(kraj, gracz, kto)`,
`transferRuler`, `invite(kraj, zapraszający, nick)` (tylko gracz online — zaproszenia żyją w pamięci kilka minut jak
w grze), `declareWar`, `breakAlliance`, `treasuryDeposit` (z konta bankowego członka), `treasuryWithdraw` (władca, na
jego konto), `treasuryLog`, `taxDebt`, `setRuler(kraj, nick)` (jak `/kraj admin wladca`).

Kapitulacja i wasalstwo (API 1.2, metody `default` w `KrajeApi`; odczyty bezpieczne z każdego wątku, zmiany na głównym):

```java
Optional<UUID> getOverlord(UUID country);            // suweren, gdy kraj jest wasalem
Set<UUID> getVassals(UUID country);                  // wasale kraju
Optional<VassalInfo> getVassalage(UUID vassal);      // VassalInfo(vassal, overlord, since, until, tributeShare,
                                                     //   tributeMin, dailyTribute, debt, debtDays, tributePaid)
KrajeResult offerCapitulation(UUID loser, UUID winner, int days);    // days <= 0 = domyślnie z configu
KrajeResult respondCapitulation(UUID winner, UUID loser, boolean accept);
KrajeResult releaseVassal(UUID overlord, UUID vassal);
KrajeResult buyout(UUID vassal);
long buyoutCost(UUID vassal);                        // -1 = nie jest wasalem
```

Eventy: `CapitulationOfferEvent` (`from` = kapitulujący, `to`, `days`, `tributeShare`, `tributeMin`, `estimatedTribute`,
`toNpc`, `expiresAt`; przy każdej ofercie — SI odpowiada dla `toNpc()` przez `respondCapitulation(to, from, accept)`),
`VassalageStartEvent` (`vassal`, `overlord`, `until`, `tributeShare`, `tributeMin`), `VassalageEndEvent` (`vassal`,
`overlord`, `reason`: `EXPIRED`, `BOUGHT_OUT`, `RELEASED`, `FALL`, `DISBANDED`, `WAR`). Uwaga: `setRelation` (admin/SI)
nie sprawdza ograniczeń wasala; wojna ustawiona nim między suwerenem a wasalem kończy wasalstwo (`WAR`).
`declareWar` stosuje ograniczenia jak komenda.

Unia personalna, ambasady i jakość relacji (API 1.5, metody `default` w `KrajeApi`; nowe klasy w `medival-api/kraje-api`:
`UnionInfo`, `EmbassyInfo`, `RelationQuality`, eventy `UnionProposalEvent`, `UnionFormedEvent`, `UnionDissolvedEvent`,
`EmbassyRequestEvent`, `EmbassyOpenedEvent`, `EmbassyClosedEvent`; odczyty bezpieczne z każdego wątku, zmiany na głównym):

```java
Optional<UnionInfo> getUnion(UUID kraj);              // home, partner, monarch, monarchName, since, regent, regentName, breakableAt
Optional<UUID> getUnionPartner(UUID kraj);
KrajeResult proposeUnion(UUID home, UUID partner);    // jak /kraj unia zaproponuj (władca home = wspólny władca)
KrajeResult respondUnion(UUID partner, UUID home, boolean accept);
KrajeResult dissolveUnion(UUID kraj);                 // admin/SI: od razu, bez kosztu i minimalnego czasu
boolean hasEmbassy(UUID gosc, UUID gospodarz);
boolean hasEmbassyBetween(UUID a, UUID b);
List<EmbassyInfo> getEmbassies(UUID kraj);            // za granicą i u niego
Optional<EmbassyInfo> getEmbassyAt(String swiat, int cx, int cz);
RelationQuality getRelationQuality(UUID a, UUID b);   // WAR < NEUTRAL < VASSALAGE < ALLIANCE < UNION < SAME
boolean canStartJointProject(UUID od, UUID do);       // np. droga do innego kraju: unia albo dobra relacja + ambasada
double getTradeBonus(UUID a, UUID b);                 // hak: 0.05 z ambasadą, 0.10 w unii (config), 0 w wojnie
KrajeResult requestEmbassy(UUID gosc, Location miejsce);
KrajeResult respondEmbassy(UUID gospodarz, UUID gosc, boolean accept);
KrajeResult closeEmbassy(UUID gosc, UUID gospodarz);
KrajeResult expelEmbassy(UUID gospodarz, UUID gosc);
```

SI Armii: dla kraju NPC Kraje domyślnie sam odrzuca unie i sam decyduje o ambasadach (sojusz = zgoda). Po ustawieniu
`union.npc-auto-refuse: false` / `embassy.npc-auto-decide: false` SI dostaje `UnionProposalEvent` / `EmbassyRequestEvent`
(`toNpc() == true`) i odpowiada `respondUnion` / `respondEmbassy`.

Obszary chronione (API 1.3, metody `default`, główny wątek): `addProtectedArea(id, świat, minX, minZ, maxX, maxZ,
buforChunki)` i `removeProtectedArea(id)` — opis w sekcji „Obszary chronione”. Błędne argumenty zwracają `INVALID`.

`SiegeContributor`: zarejestruj dowolną liczbę implementacji w ServicesManager; Kraje woła `presence(kraj, świat, cx, cz)`
raz na sekundę dla każdego kraju strony atakującej i broniącej trwającego oblężenia.

## Budowanie

Weryfikacja unii i ambasad: przy wczytaniu unia sprzeczna z wojną lub wasalstwem jest rozwiązywana,
a tron wraca do namiestnika lub następcy. Ambasady w wojnie, po terminie wydalenia, poza terenem gospodarza
lub z punktem teleportu poza ich chunkiem są usuwane. Teleport ponownie sprawdza członkostwo, rangę,
aktywny kraj monarchy, właściciela terenu, wojnę i termin wydalenia po odliczaniu.
Tytularny monarcha nie jest ponownie opodatkowany w kraju partnera ani uwzględniany w podziale
skarbu między obywateli przy upadku; zachowuje uprawnienia władcy do zarządzania krajem.

`MENU-ADDITIONS.yml` zawiera katalog prawdziwych komend do ręcznej integracji z Menu; nie instaluje przycisków.
Akceptacja unii i ambasady wymaga Władcy; namiestnik ma rangę Doradcy i może dokonać secesji zgodnie z configiem.
Uprawnienia API pozostają odpowiedzialnością wywołującego pluginu. Przełączenie `/kraj unia rzadz` dotyczy
komend Kraje; nie nadaje podwójnego obywatelstwa ani automatycznych praw w innych pluginach.
Propozycje, prośby, przełączenie kraju i cooldown ponownej unii pozostają w pamięci i nie przetrwają restartu.
Unie, ambasady i terminy wydalenia są trwałe w `countries.json` (zapis async, atomowa podmiana i `.bak`).
Testy jednostkowe nie zastępują próby na izolowanym Paper z graczami: ochrona bloków, odliczanie teleportu
i współpraca z innymi pluginami wymagają takiej próby przed wdrożeniem.

```
gradlew build
```

Gradle 9.8.2 (wrapper), toolchain Java 25 pobierany automatycznie (foojay). Wynik: `build/libs/Kraje-1.0.0.jar`.
Testy JUnit (geometria wielokątów, spójność terytorium, formatowanie, kolory, zapis JSON w formacie 1 i 2, config z
domyślnymi z jara, limit terenu, koszty, harmonogram dziennego rozliczenia, wybór chunków przy bankructwie, postęp
oblężenia, spójność po zdobyciu, reguły PvP, eventy managera, strony pomocy, drzewo komend Brigadier, łupy i ich
zaokrąglenia, trybut i dług, macierz ograniczeń wasala, koniec wasalstwa, trwałe zaproszenia, zapis w formacie 3, reguły unii
personalnej i ambasad, tron tytularny i następca, zapis/odczyt i naprawa unii oraz ambasad, gałęzie `unia`/`ambasada`) uruchamiają się
w `build`. Do kompilacji potrzebny jest zbudowany `../medival-api/build/libs/medival-api.jar` (`gradlew jar` w `medival-api`).

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
