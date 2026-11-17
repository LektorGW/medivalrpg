# Ludnosc — obywatele NPC, budowanie i rozwój osad (MEDIVALRPG)

## Własne budynki graczy i poprawki budowania — 2026-11-03 (przygotowane, nieaktywne)

Pakiet `../deploy-ludnosc-budynki-20261103/` (baza: aktywny 019cec1e, odtworzony bajt w bajt). Nic nie wgrano.

**Zgłaszanie budynków postawionych ręcznie** — `/ludnosc budynek zglos <typ> [nazwa]` (ranga `building.rank`,
domyślnie Doradca/Władca; admin dowolny kraj), `typy`, `info`, `usun`. Zaznaczenie: `/ludnosc kopia pos1`/`pos2`
albo WorldEdit. Sprawdzane: cały obrys na ziemi własnego kraju (pierwszy obcy chunk z blokami i nazwą właściciela),
brak nachodzenia na budynki, place budowy i miejsca zamówione w kolejce, rozmiar (`player-buildings.min-side`,
`min-height`, `max-volume`, `min-volume` typu), kluczowe bloki typu, limity i wymagania szablonu tego rodzaju.

| typ | szablon (limity, wymagania) | kluczowe bloki | działanie bez szablonu |
|---|---|---|---|
| mieszkanie | chata | ≥1 całe łóżko (obie połowy w obrysie) | pojemność mieszkań = całe łóżka |
| spichlerz | spichlerz (zgłoszonych maks. 3) | skrzynia/beczka | +`food.capacity-per-granary`, skrzynie i beczki przyjmują jedzenie |
| magazyn | magazyn (2 na kraj) | 2 skrzynie/beczki | premia `granary: 300`; skrzynię można powiązać `/ludnosc magazyn powiaz` |
| koszary | koszary (3) | 2 całe łóżka | `LudnoscApi.hasBuilding(kraj, "koszary")` → rekrutacja Armii |
| kuznia | kuznia (2) | kowadło + piec / piec hutniczy / stół kowalski | narzędzia górników (`toolFactor`), zawód kowala (Workplaces, Życie) |
| studnia | studnia (3) | źródło wody | nastrój i miejsce spotkań (Życie: funkcja „studnia”) |
| karczma | karczma (2) | beczka + ognisko/wędzarnia/piec | premia karczmy (+5% podatku, wzrost), nastrój, karczmarz |
| kosciol | kosciol (1) | pulpit + świeca lub dzwon | premia kościoła (wzrost), nastrój, kapłan |
| ratusz | ratusz (1) | stół kartograficzny + pulpit | premia ratusza (+8% podatku), kartograf |

Limit szablonu liczy wszystkie budynki tego rodzaju (zbudowane, budowane, w kolejce i zgłoszone), także w planiście NPC
i w `buildingRequirements`. Epoka i odkrycie obowiązują zawsze; poziom osady, ludność i minimalny skarbiec — gdy
`player-buildings.requirements: true` (domyślnie). Wykluczone: kopalnia, pole, tartak, kamieniołom, przystań, huta
szkła, cegielnia, owczarnia (każda ma własny mechanizm: chodniki, pola, wyrąb, cięcie, wody, wykopy, stado — bez
szablonu nie działa), mur, brama, most (pierścień murów i trakty planisty) oraz epoki przemysłowe (`EXCLUDED`).

Zapis: zwykły `Building` z typem `wlasny_<typ>`, `registered = true`, funkcją typu, autor w `origins` (gracz → nigdy nie
burzony), szczegóły (nazwa, autor, powód nieczynności) w opcjonalnym `CountryState.ownBuildings`. Szablonu brak, więc
planista, przebudowa, modernizacja, naprawy, pożary i „porządki” go nie dotykają (jak dom zgłoszony przez admina).
Funkcja: każda zmiana bloku w obrysie (rozbicie, postawienie, ogień, wybuch, wiadro) i co 30 s jeden wczytany budynek
(`sweep-seconds`) przelicza kluczowe bloki; bez nich funkcja = `OTHER` (pojemność 0, Armia nie widzi koszar, brak
premii) i kraj dostaje wiadomość; po odstawieniu bloków funkcja wraca. Starszy jar czyta dane (typ i funkcja zostają);
traci tylko nazwy/powód i nie zna premii/aliasu koszar dla `wlasny_*`.

**Poprawki budowania (audyt 03.11)**: bilety chunków tylko dla placów zdolnych do pracy, kraje graczy pierwsze;
drogi i mury wczytują tylko chunki swoich komórek; wpisany wariant jest budowany; podgląd podaje powód z koordynatami
(i log INFO odmów); naturalny teren, liście, śnieg i rusztowania nie blokują placu gracza; planista pamięta odrzucone
działki (`CountryState.plannerAvoid`, 3 dni); `buduj anuluj` bez argumentu anuluje tylko podgląd, plac — `anuluj <id>`
lub `anuluj potwierdz`, zwrot 100% przed rozpoczęciem prac (dalej `building.refund-on-cancel`); `napraw` podaje
prawdziwy powód i nie przerywa na pierwszym; górnicy przekopują własny bruk/stemple pod ziemią; trawa jest glebą
(w MC 26 tag `#dirt` nie zawiera `grass_block` — `BuildFixes.SOIL`); niwelacja robi naturalne skarpy ≤1:1 z ziemi
i trawy (pas `RAMP` 8) bez murków i płotków, niski uskok tylko w ostateczności. Szczegóły i testy:
README pakietu.

## Przygotowane lokalnie — kraje graczy, 2026-10-29

Pakiet `../deploy-ludnosc-players-20261029/`, **niewgrany i nieaktywny**. Bazą jest minefix 73a1c0b2
(odtworzony build bez zmian); aktywny serwer według ostatniego potwierdzenia nadal 519ba1f0,
w `plugins/update/` czeka 73a1c0b2. Ta sekcja opisuje nowe źródła i ma pierwszeństwo przed historycznymi opisami poniżej.

Kraje graczy (`npc=false`) wybierają tylko: **chata, kopalnia, kamieniolom, tartak, pole, przystan,
huta_szkla, cegielnia, owczarnia, kuznia, spichlerz, magazyn, studnia, koszary** oraz ich warianty.
Lista jest konfigurowalna przez `player-countries.allowed-buildings`; brak klucza używa wartości w kodzie.
Kopie własnych budowli pozostają. Filtr obejmuje komendy, warianty, nowe zlecenia i katalog wymagań panelu.
Planista miasta, przebudowa, samodzielne chatki, `buduj auto` i automatyczna odbudowa/naprawy są tu wyłączone.
Odbudowę po pożarze zleca władca. Istniejące budynki, trwające budowy i opłacone kolejki pozostają.
NPC zachowują pełny katalog i automaty; chatki mają łagodniejsze reguły `courtesy.*` opisane w README pakietu.

`/ludnosc buduj anuluj` bez argumentu anuluje tylko podgląd (od 2026-11-03 nigdy plac — pokazuje listę placów);
plac: `/ludnosc buduj anuluj <id>` (minimum 8 znaków, `/ludnosc budowy`) albo `anuluj potwierdz` (plac pod stopami,
jedyny plac lub jedyne zlecenie). Władca/doradca może anulować plac i kolejkę (`/ludnosc kolejka anuluj <nr>`).
Zwrot: kolejka 100%, plac bez rozpoczętych prac 100%, rozpoczęty według `building.refund-on-cancel`;
fizyczne niewykorzystane materiały wracają do magazynu lub wypadają na placu, resztki bufora trafiają
do istniejącego rejestru surowców. Brak drugiego zwrotu. Format danych i medival-api bez zmian.

**Produkcja nie wymaga zmiany configu.** W przyszłości tylko jar do `plugins/update/`; bez restartu ani czynności
przy zatrzymanym serwerze. Pakiet zawiera rollback do minefix 73a1c0b2 i kopię aktywnego 519ba1f0.
Wyniki, SHA i szczegóły: [README pakietu](../deploy-ludnosc-players-20261029/README.md).


Plugin Paper 26.3 (Java 25) dla serwera MEDIVALRPG. Obywatelami państw z pluginu **Kraje** są prawdziwe wieśniaki,
które płacą podatki do skarbca kraju (**Skarbiec**). Budynki stawiają chodzący budowniczowie NPC, stopniowo i w
realistycznej kolejności, a planista sam rozwija osady od osady do stolicy.

Zależności: `Kraje`, `Skarbiec` (wymagane), `WorldEdit` (opcjonalnie: `/ludnosc dom zglos`, pliki `.schem`),
`Armia` (opcjonalnie: ochrona przed żołnierzami obcych krajów, pobór przez `LudnoscApi`).

## Jak to działa

- **Obywatele** to `Villager` z PDC `ludnosc:country`, z polskim imieniem i zawodem vanilla. Rejestr obywateli
  (`data.json`) liczy ludność także w niezaładowanych chunkach; wieśniaki są uzgadniane z rejestrem tylko przy
  wczytaniu/zamknięciu chunka i przy zdarzeniach — świat nie jest skanowany. Mózgi/konie Armii (PDC `armia:*`) nigdy
  nie są obywatelami.
- **Osadnicy w niezaładowanych miejscach** (wzrost, kraj NPC, `returnCitizen`) są zapisywani od razu jako rekord
  „oczekujący” (liczą się do ludności, podatków i jedzenia), a wieśniak pojawia się po wczytaniu chunka — zawsze na
  bezpiecznym bloku (twarde podłoże, bez wody i dziury; inaczej najbliższe bezpieczne miejsce na terenie kraju).
- **Mieszkania**: budynki z funkcją `housing` (chata, dom, kamienica, karczma, domy zgłoszone WorldEditem). Pojemność =
  łóżka w środku; zniszczone łóżka zmniejszają pojemność do czasu naprawy.
- **Wzrost**, **spichlerz**, **dzienny rozrachunek** (produkcja → posiłki → podatek NPC do
  `SkarbiecApi.countryAccount` → odejścia przy głodzie lub braku łóżek) — wszystko liczone z rejestru, także dla
  niezaładowanych terenów w zakresie posiłków/podatków. Nowe plony pochodzą wyłącznie z rzeczywistej pracy w załadowanych miejscach, przez fizyczny magazyn.
- **Premie budynków** (`buildings.<id>.bonus` w config.yml): jedzenie dziennie, procent podatku, szansa wzrostu,
  pojemność spichlerza. Widoczne po najechaniu w `/ludnosc typy`. Dawna premia wirtualnej dziennej produkcji jedzenia nie mintuje drugiego plonu w trybie fizycznym.
- **Ochrona**: obcy nie skrzywdzą obywatela na terenie jego kraju, chyba że kraje są w wojnie (`KrajeApi.isAtWar`).
- **Zmiana właściciela chunka**: zdobyty teren → obywatele i budynki przechodzą do zdobywcy; porzucony → wieśniaki
  zostają zwykłymi wieśniakami. Budowy w tym chunku są anulowane (zwrot, teren przywracany).
- **Rozwiązanie kraju**: obywatele zostają zwykłymi wieśniakami, budowy i kolejka znikają, budynki stają się bezpańskie.
- **Kraj NPC** (`CountryCreateEvent` z `npc=true`): osadnicy w stolicy, zapas jedzenia, budynek startowy; dalej rozwija
  się sam (`LudnoscApi.developSettlement`).

## Poziomy osady

Osada → Wieś → Miasteczko → Miasto → Stolica (`levels:` — ludność i skarbiec). Poziom odblokowuje budynki (`tier`),
widać go w `/ludnosc info` i w `LudnoscApi.settlementTier`. Każdy typ ma limit na kraj i wymagania (ludność, skarbiec).

**Materiały rosną z bogactwem**: szablony używają slotów materiałów (`@wall`, `@base`, `@frame`, `@roof_stairs`,
`@roof_slab`, `@roof_block`, `@floor`, `@window`, `@door`, `@fence`), a paleta poziomu zamienia je na bloki — ten sam
dom w Osadzie ma strzechę i dąb, w Mieście cegłę/kamień i ciemny dąb, w Stolicy kalcyt, miedziany dach i witraże
(`preview/dom-poziom-1..5.png`). Nowy budynek dostaje materiały obecnego poziomu. Szablony z poprzednich wersji
pluginu wyglądają na poziomie 1 identycznie jak wcześniej (test), więc stare budynki nie wyglądają na „uszkodzone”.
Paletę można nadpisać w `materials.<poziom>.<slot>` (poziomy od 0).

**Modernizacja**: po awansie osady starsze budynki są oznaczone „do modernizacji” w `/ludnosc budynki`.
`/ludnosc modernizuj <id|wszystko>` przebudowuje tylko bloki-materiały (ściany, dach, okna, podłogi). Koszt = koszt
budynku × udział bloków-materiałów × `construction.upgrade-cost-multiplier` (0.4). Ma pierwszeństwo w kolejce jak
naprawa. Planista krajów NPC zleca modernizację sam, gdy nie ma nic ważniejszego do zbudowania.

## Budowanie

`/ludnosc buduj <typ>` pokazuje obrys cząsteczkami (zielony = można, czerwony = nie, żółta linia = strona z drzwiami).
`/ludnosc buduj auto` — tylko kraj NPC; planista sam wybiera budynek i miejsce. Koszt płaci skarbiec kraju (ranga od Doradcy).

- **Kolejność jak na prawdziwej budowie**: wykop i niwelacja terenu → fundament → ściany od dołu (najpierw słupy i
  belki) → stropy → dach od okapu do kalenicy → wnętrze → detale. Na końcu przebieg „połączeń”: szyby, kraty, płoty,
  furtki, murki i schody łączą się poprawnie.
- **Budowniczowie** (Mannequin z narzędziami i skórzanym ubraniem) chodzą po placu (płynne teleporty co 2 ticki),
  machają ręką, słychać stawianie bloków i widać cząsteczki. Liczba zależy od wielkości budynku
  (`blocks-per-extra-builder`, `builders-max`). Przy ścianach wyższych niż 3 bloki stawiają rusztowanie (rozbierane na
  końcu). Pracują w dzień (`construction.work-hours`), w nocy siedzą przy ognisku.
- **Teren**: działka + 2–3 bloki marginesu jest niwelowana (skarpy najwyżej 1:1, naturalne materiały, mury oporowe
  do 3 bloków, a na krawędzi muru ≥ 3 bloki płotek — żeby nikt nie spadł; drzewa nad działką usuwane). Drzwi nad
  terenem dostają schody do gruntu, droga dochodzi do najniższego stopnia.
- **24/7**: aktywne place trzymają swoje chunki załadowane biletami pluginu (`construction.keep-loaded`,
  `max-loaded-chunks-total: 32`, pierwszeństwo najstarszych zamówień); place ponad limit budują „wirtualnie” i
  nadrabiają po wczytaniu chunka.
- **Woda**: planista nie stawia budynku, którego działka z marginesem 3 bloków dotyka wody na poziomie wykopu lub
  wyżej (poza przystanią, latarnią i mostem), a bliskość wody niżej kosztuje punkty. Plac budowy z marginesem jest
  pilnowany: wokół wody stoi grobla z ziemi/kamienia, woda w obrysie jest usuwana (także zalana wcześniej — sprawdzane
  przy starcie i co 10 s), płynięcie wody i lawy do placu, chodnika kopalni i kopanych podziemi jest blokowane
  (`BlockFromToEvent`). Skarpy przy wodzie są z ziemi i kamienia, nie z piasku ani żwiru. Woda z szablonu (studnia,
  fontanna) zostaje.
- **Materiały** (opcjonalnie, `construction.require-materials`): budowniczowie biorą bloki ze skrzyni „magazyn budowy”.
- **Kolejka budów** (`construction.queue-size: 5`): płatność od razu, teren zarezerwowany, start automatyczny,
  anulowanie z kolejki = zwrot 100%. Anulowana budowa przywraca teren.
- **Naprawy**: budynki są okresowo porównywane z szablonem (budżet czasu, tylko załadowane chunki, różnice kosmetyczne
  pomijane). Przy uszkodzeniu > 2% albo zniszczonym łóżku/drzwiach/dachu nad łóżkiem: kraje NPC naprawiają same,
  gracze dostają powiadomienie i `/ludnosc napraw`. Koszt = koszt × brakujące/wszystkie × 0.5. Naprawy idą na początek
  kolejki, ale czekają na koniec oblężenia. Ruina (> 70%) jest odbudowywana albo usuwana (`repair.ruin-mode`).

## Plan miasta

Planista (`LudnoscApi.developSettlement`, `/ludnosc buduj auto`) zaczyna od studni na placu, potem główne ulice,
domy wzdłuż dróg, rzemiosło dalej, pola na obrzeżach, obrona na granicy; kopalnia przy skałach, tartak przy lesie,
przystań przy wodzie. Wybiera płaskie miejsca na własnym terenie (spadek > 3 odrzucany, gdy jest coś lepszego),
ustawia budynki wzdłuż warstwic wejściem pod górę. Drogi: ścieżki, od Miasteczka bruk, płyty na podjazdach, latarnie
co kilka bloków. Plan jest pamiętany w danych kraju; `/ludnosc plan` pokazuje go cząsteczkami
(`preview/miasto-etap-*.png` — symulacja rozwoju).

## Podziemia

Nowe domy (chata, dom, kamienica, karczma) dostają **piwnicę** z beczkami (+50 pojemności spichlerza), ratusz **lochy**
z celami za kratami i wartownią, zamek lochy z **tunelem ucieczkowym** (28 bloków dalej, wyjście klapą na powierzchni
własnego terenu), katedra **katakumby** z niszami, świecami i ossuarium. Wejście: klapa w podłodze i drabina (klapa
nie jest traktowana jako uszkodzenie). Kopane stopniowo w godzinach pracy: najpierw obmurowanie całej bryły (jaskinie,
woda i lawa odcięte, żwir nad stropem zamieniany), potem kopanie, na końcu wyposażenie. Starsze budynki:
`/ludnosc podziemia kop [id]` (koszt `underground.cost`), kraje NPC same. `/ludnosc podziemia` pokazuje stan.
Podglądy planów: `preview/podziemia-*.png`.

## Przejmowanie wiosek (dla Armii)

`LudnoscApi.findVillage(miejsce, promień)` (asynchronicznie): dzwon (POI meeting) jako centrum, łóżka (POI home) i
stanowiska pracy z serwerowego rejestru POI (`World#locateAllPoiInRange`), pola z migawek chunków, wieśniacy w promieniu;
zwraca spójny zestaw chunków zabudowy. `adoptVillage(kraj, wioska)` rejestruje domy z łóżkami jako „Dom wiejski”
(pokój wokół łóżek wykryty wypełnianiem; bez szablonu, więc bez naprawy/modernizacji z szablonu), ziemię uprawną jako
pola (`villages.farmland-per-field` bloków = jedno pole), adoptuje wszystkich wieśniaków z zawodami, ustawia centrum
planu na dzwonie i ścieżki jako drogi. Wieśniacy w niezaładowanych chunkach zostają obywatelami przy wczytaniu.

## Kopalnie

Po zbudowaniu kopalni górnicy drążą chodnik z dna szybu: schody w dół o `mines.depth` bloków, potem poziomy chodnik
z torami, stemplami i pochodniami. Jeden krok co 2 sekundy w godzinach pracy (bilet chunka głowicy chodnika).
Bezpieczeństwo: krok, przy którym jest woda lub lawa, nie jest kopany (chodnik skręca albo staje), jaskinie obok są
zamurowywane, dziury w podłodze zasypywane, żwir/piasek nad stropem zamieniany na bruk, nigdy pod cudzym terenem.
Pierwszy odcinek (`mines.extension-blocks`) jest gratis, kolejne `/ludnosc kopalnia rozbuduj` (`mines.extension-cost`);
kraje NPC rozbudowują same. Urobek trafia fizycznie do powiązanej skrzyni; dawny `mines.income-per-block` nie dopisuje dodatkowych pieniędzy.
Magazyn ma piwnicę (pojemność spichlerza +300).

Poprawka kopalń z 29.10.2026 (`deploy-ludnosc-minefix-20261029`):
- Bilety chunków są brane przed sprawdzeniem magazynu i obejmują chunk przodka oraz chunk powiązanej skrzyni.
  Wcześniej kopalnia, przy której nie było gracza, nie mogła sprawdzić skrzyni w niewczytanym chunku, więc nie brała biletu
  i stała. Gdy wczytana skrzynia odmawia pracy (jest pełna, niepowiązana albo czeka na audyt), kopalnia przez 5 minut
  nie bierze biletów.
- Zapis chodnika przechodzi na nowego właściciela razem z budynkiem: przy przejęciu ziemi, rozwiązaniu kraju i synchronizacji przy starcie.
  Kopalnia kraju bez zapisu, czyli sierota z czasów przed poprawką, odczytuje swój chodnik ze świata od wyjścia szybu.
  `MineTrace` idzie tylko po torach i schodach z bruku, nie wchodzi do naturalnych jaskiń.
  Na czas odczytu potrzebne chunki dostają bilety. Kopalnia kopie dalej od prawdziwego przodka, a w logu pojawia się wpis
  „przywrócono zapis chodnika”.
- Blok, którego górnicy nie mogą ruszyć (inny niż kamień, ziemia, żwir lub ruda, np. piasek, glina albo blok gracza),
  nie zatrzymuje już pracy po cichu. Status w `/ludnosc kopalnia` pokazuje „Przeszkoda w chodniku kopalni: …”,
  w logu jest jeden wpis, a samodzielne kopanie czeka 10 minut i próbuje ponownie.

## Budynki

68 wbudowanych szablonów (`plugins/Ludnosc/budynki/*.yml`, można edytować i dodawać własne; podglądy w `preview/`):

| poziom | budynki |
|---|---|
| Osada | studnia, chata (2 łóżka), dom (4), spichlerz, pole uprawne, owczarnia |
| Wieś | kuźnia, koszary (6 łóżek wojska), wieża strażnicza, karczma (4), młyn, stajnia, mur z basztą, piekarnia, tartak, kopalnia, zielarnia, przystań, cmentarz, huta szkła, cegielnia |
| Miasteczko | targ, kamienica (6), ratusz, kościół, brama miejska, browar, biblioteka, magazyn (z piwnicą), łaźnia, latarnia morska, kamieniołom |
| Miasto | zamek (barwy kraju), pomnik władcy, rynek z fontanną, koszary rozbudowane (12 łóżek wojska), akademia rycerska, skarbiec królewski, arena |
| Stolica | pałac z kopułą, wieżyczkami i ogrodem (barwy kraju), ogrody pałacowe, katedra |

Most (Wieś) stawia się nad wodą (filary w wodzie, pokład na poziomie brzegów).

Szablony generuje `templates-src/generate.py` (Python; walidacja każdego stanu bloku na każdym poziomie materiałów
względem rejestru MC 26.3, `--preview` rysuje PNG). Format YAML: `palette` (znak → stan bloku), `layers` (od dołu;
wiersze z północy na południe), `.` = powietrze, `_` = nie ruszaj terenu, front na południu, `{kolor}` = barwa kraju,
`materials` = materiały poziomu 1 danego szablonu. Obrót obraca stany bloków; przy starcie porównanie z
`BlockData#rotate` serwera.

## Komendy (`/ludnosc`, alias `/lud`)

| Komenda | Kto |
|---|---|
| `pomoc [strona]`, `info [kraj]`, `budynki [kraj]`, `typy`, `plan`, `spichlerz`, `kopalnia`, `podziemia`, `magazyn` | każdy |
| `buduj <typ>` / `buduj auto` / `buduj obroc` / `buduj potwierdz` / `buduj anuluj` | Doradca+ |
| `kolejka`, `kolejka anuluj <nr>` / `wyzej <nr>` / `nizej <nr>` / `pokaz <nr>` | Doradca+ |
| `napraw <id\|wszystko>`, `modernizuj <id\|wszystko>`, `kopalnia rozbuduj [id]`, `podziemia kop [id]`, `przebuduj`, `spichlerz oznacz` / `usun` | Doradca+ |
| `budynek zglos <typ> [nazwa]`, `budynek usun` (budynki postawione przez graczy) | Doradca+ |
| `budynek typy`, `budynek info` | każdy |
| `dom zglos` (zaznaczenie WorldEdit), `dom usun`, `admin kolejka <kraj> [wyczysc]` | admin |
| `admin dodaj <kraj> <n>`, `admin usun <kraj> <n>`, `admin budynek anuluj [id]` | admin |
| `admin jedzenie <kraj> <ilość>`, `admin dzien`, `admin wzrost`, `admin reload` | admin |

Uprawnienia: `ludnosc.use` (wszyscy), `ludnosc.admin` (op), `ludnosc.bypass` (op — omija ochronę obywateli).

## API

`LudnoscApi` w ServicesManager: `population`, `housing`, `citizens`, `countryOf`, `dailyTaxIncome`, `conscript`
(także niezaładowani — rekord od razu, wieśniak znika po wczytaniu), `returnCitizen`, `hasBuilding`, `buildingTypes`,
`orderBuilding`, `orderBuildingAuto`, `developSettlement`, `buildingRequirements`, `settlementTier`, `queue`,
`moveQueue`, `cancelQueued`, `adjustFood`, `adjustSettlers`, `findVillage`, `adoptVillage`, `resources`, `lastPlanFailure`, `info` (dla panelu: ludność, spichlerz, podatek, budynki
ze stanem „uszkodzony X%” / „w naprawie”, place budowy z postępem).

## Epoki (plugin Postep)

Postep jest opcjonalny i nie jest w `softdepend` (Postep ma Ludnosc w swoim — cykl): `PostepApi` jest szukane leniwie
w ServicesManager i wołane refleksyjnie, `EraAdvanceEvent` rejestrowany klasą z classloadera Postep po jego włączeniu.
Bez Postep każdy kraj jest w Średniowieczu.

- **Palety epok**: styl materiałów = (epoka, poziom osady) — 4 × 6 palet. Renesans: tynk, jasny kamień, czerwona
  dachówka; Przemysł: cegła, ciemne drewno, łupek, stalowe kraty; Nowoczesność: beton, kwarc, ciemne szyby. Stare
  budynki w nowej epoce są „do modernizacji” (`/ludnosc modernizuj`, kraje NPC same).
- **Budynki epok** (wymagana epoka i odkrycie w szablonie, `requires.era`, `requires.discovery`): kamienica
  renesansowa (6 łóżek), kamienica czynszowa (8), fabryka (`fabryki`), dworzec z torami (`kolej`), blok mieszkalny
  (15 łóżek, `beton_zbrojony`), wieżowiec biurowy (~45 bl., poziom Miasto), wieżowiec mieszkalny (~65 bl., 28 łóżek,
  Metropolia). Wieżowce tylko na płaskiej działce, z większą ekipą; budowy sprawdzają limit wysokości świata.
- **Drogi epok**: ścieżki i bruk → kamień → kostka z latarniami gazowymi → asfalt z przerywaną linią, chodnikami,
  przejściami i latarniami ulicznymi (z `samochod` dwa pasy). Po awansie sieć dróg jest przebudowywana
  (`/ludnosc przebuduj`, kraje NPC same).
- **Przebudowa miast** po awansie (kraje NPC, przez kolejne tygodnie): drogi, modernizacja, potem co
  `rebuild.demolish-hours` rozbiórka małego domu poza starówką (`old-town-radius`) i większy dom w jego miejscu
  (chata → kamienica → blok). Rozbiórka przez budowniczych od dachu; połowa materiałów wraca do magazynu. Wyburzane są
  tylko budynki Ludnosc z szablonem, nigdy zabytki (`rebuild.heritage`) ani bloki graczy.
- **Metropolia** — szósty poziom osady (ludność 150, skarbiec 300 000).
- **Efekty odkryć**: `jedzenie` (produkcja), `ludnosc` (wzrost), `tempo-budowy` (szybkość budowniczych).
- **Place naraz**: `max-sites-per-country` (5) + 1 za poziom osady + 1 za epokę, najwyżej `max-sites-cap` (12);
  budżet CPU `construction.blocks-per-tick-country` / `-server`; `/ludnosc info` pokazuje „Place: 3/7”.

## Gdy brakuje miejsca

Planista luzuje zasady stopniowo (mniejszy margines, większy spadek z tarasami i murami oporowymi, mniejszy odstęp,
każdy obrót). Kraje NPC (i gracze z `planner.auto-claim-for-players`) same zajmują przyległe chunki z dobrym terenem
(`KrajeApi.claimChunk`, przyczyna NPC, koszt i limit Kraje). Każda odmowa ma polski powód (za mało ziemi — ile chunków,
za stromo, woda, kolizja, teren jeszcze nie zbadany, limit terenu) w `/ludnosc buduj`, `buduj auto` i w
`LudnoscApi.lastPlanFailure`. `orderBuilding` z jawnymi x/z buduje na ziemi kraju także na gorszym terenie
(odmowa tylko przy kolizji z budynkiem albo obcej ziemi).

## Surowce i mury

Drwale z tartaków ścinają całe drzewa (z koroną, bez latających liści) tylko na ziemi kraju, z dala od dróg,
budynków i centrum, zostawiają część lasu i sadzą sadzonki. Kamieniołomy, kopalnie i przystanie dają kamień, rudy
i ryby. `/ludnosc magazyn`, `LudnoscApi.resources` pokazują zapas fizyczny i zachowane zapasy historyczne; automatyczna sprzedaż nowego urobku jest wyłączona. Kraje NPC
od Miasteczka stawiają mury miejskie modułami: prostokąt wokół zabudowy, baszty, blanki, bramy na drogach; przy
wzroście miasta nowy pierścień.

## Kopalnie, nastrój, przebudowa, królestwo, plan z panelu

- **Kopalnie rosną**: poziomy 1–5 (długość, piętra co 10 bl. w dół schodami, epoka), górnicy idą za żyłami rud (rudy
  do magazynu), oświetlenie i chodniki według epoki (pochodnie → latarnie w niszach → lampy, drewno → stal → beton),
  w epoce przemysłowej stalowa wieża wyciągowa; dochód rośnie z poziomem.
- **Nastrój (plugin Zycie, opcjonalny, refleksja)**: `moodMultiplier` mnoży podatki NPC, wzrost i produkcję; przy
  `unrest` ludność nie rośnie.
- **Przeprojektowanie miasta (plan v2)**: regularna siatka ulic wokół rynku (gęstsza w późniejszych epokach), kwartały
  z zielenią; różnica planów → rozbiórki (tylko budynki Ludnosc, nigdy zabytki), nowe ulice odcinkami, niwelacja
  wolnych kwartałów z dziennym limitem. Kraje NPC same, gracze `/ludnosc przebudowa` (podgląd) i `potwierdz` (Władca),
  admin `/ludnosc admin przebudowa <kraj> [podglad|teraz] [darmowo]`, `admin drogi`, `admin modernizuj`.
- **Królestwo**: nowe osady na dalekiej ziemi (studnia, własne tury planisty), trakt do stolicy, `/ludnosc plan krolestwo`
  (mapa ASCII); w wojnie umocnienia granicy od strony wroga.
- **Mury idą za miastem**: wypukła otoczka zabudowy, po grzbietach, rzeka/jezioro/klif zastępują mur, brama na każdej
  drodze, najpierw odcinki od strony obcych i wrogów; rozrost miasta lub utrata ziemi → nowy pierścień, stary rozbierany
  (`walls.old-ring`). Palisada w młodej osadzie, kamień, bastiony, w późniejszych epokach niskie posterunki.
- **Odcinki murów (2026-10-31)**: plac budowy muru / rozbiórki to zawsze jeden ciągły odcinek (najwyżej kilka chunków
  biletu), jeden odcinek naraz na kraj — także gdy czeka w kolejce. Dziury do 4 bl. w pierścieniu (ukosy, pominięta
  kolumna) są domykane; szersze przerwy (rzeka, klif, obca ziemia) zostają. Podłoże kolumny liczone od góry przez
  stojący już mur, liście i pnie — ponowne planowanie po restarcie nie stawia drugiego muru na pierwszym i nie zależy
  od wysokości centrum miasta. Baszty co `walls.tower-every` i na prawdziwych zakrętach (ukos to zwykły mur).
  Stare rozproszone odcinki dzielone raz po starcie; stare pierścienie domykane raz (`wallRingVersion`).
  Kraje graczy: mury planu z panelu tylko przy `player-countries.plan-walls: true` (domyślnie szkic + komunikat).
- **Pracownicy**: rolnicy zbierają i sieją dojrzałe uprawy (jedzenie), kamieniarze i rybacy pracują przy kamieniołomie
  i przystani; przy `require-materials` brakujące drewno i kamień idą z magazynu kraju.
- **Plan wytyczny z panelu** (`LudnoscApi.setDesignPlan`, format w PANEL.md): budynki w kolejności priorytetu tam, gdzie
  narysowano; drogi i mury po liniach (także `follow-border` / `follow-town`); strefy ograniczają automat; ma
  pierwszeństwo przed planerem i jest celem przebudowy.
- **Budynki epok** (68 szablonów): uniwersytet, drukarnia, port, bank, obserwatorium, ratusz i pałac renesansowy,
  szkoła, szpital, most stalowy, elektrownia, park, galeria, szpital i szkoła nowoczesne, stadion, parlament, dworzec
  nowoczesny, parking, laboratorium, lotnisko.

## Transport, mapa i premie osad (2026-10-26)

- `SettlementTransportService`: wózki kopalniane poruszają się po istniejących, przejezdnych torach przy kopalni;
  na prostych odcinkach dostają napęd, przed końcem toru zawracają. Skan ma globalny limit 256 bloków co 10 ticków.
  Tor musi leżeć w kraju właściciela. Brak torów oznacza brak wózka; system nie nadpisuje budynków nowymi torami.
- Tramwaje pasażerskie używają torów przy dworcach od epoki 3. Dźwigi z obracanym ramieniem i opuszczaną platformą
  pojawiają się przy aktywnej budowie wieżowców/biurowców od epoki 4. Przy limicie epok do renesansu obie funkcje
  pozostają niedostępne; wózki kopalniane działają. Praca tylko w załadowanych chunkach i w pobliżu graczy.
- `transport.enabled`, `transport.max-carts` (24), `transport.max-cranes` (8). Encje mają własny PDC, nie są trwałe;
  po restarcie/chunk-load osierocone są usuwane, a potrzebne odtwarzane z rejestru budynków/placów budowy.
- Kraje dodaje osobną, domyślnie ukrytą warstwę squaremap **Królestwa — osady i trakty**, odświeżaną co 30 sekund.
  Zawiera stolicę i centra pozostałych osad, gotowe ulice/trakty, planowane trakty (kolor bursztynowy), budynki
  i place budowy, ich funkcję/procent, kraj, poziom osady, ludność i mieszkania. `LudnoscApi.kingdomMap` zwraca
  immutable snapshot; wcześniejsze implementacje API domyślnie zwracają pusty wynik. Tysiące komórek dróg są
  scalane do prostych odcinków; zakręty planowanych traktów pozostają zachowane, luki nigdy nie są łączone.
  Limity na kraj: 64 centra, 256 odcinków gotowych dróg, 32 linie planowanych traktów (do 128 punktów każda)
  i 800 markerów dróg/budynków łącznie. Najdłuższe trakty mają pierwszeństwo przed krótkimi uliczkami.
- `LudnoscInfo` publikuje `settlementLevel` (1–6; 0 = nieznany) i `settlementName`; poprzedni konstruktor nadal działa.
  Armia korzysta z `settlementTier` i daje +2% obrażeń oraz HP rekrutowanych/podpinanych jednostek za każdy poziom
  powyżej pierwszego, maksymalnie +10%. Brak Ludnosc lub starsze API oznacza brak premii.

## Kamieniołomy, mosty i teren (2026-10-26)

- Kamieniarze wydobywają rzeczywiste bloki w ograniczonej niecce z ławami. Kamień trafia do magazynu tylko za
  wydobyty blok, do dziennego limitu `resources.quarry-stone-per-day`; postęp i licznik przetrwają restart.
  Nie ma już darmowej dziennej produkcji kamienia. Złoże jest wyczerpywalne.
- Trakty między osadami wyznaczają drewniane (średniowiecze) lub kamienne (renesans) mosty przez rzeki
  do 24 bloków szerokości. Pomost i barierki powstają stopniowo w trwałej kolejce budowy, na własnym terenie.
- Planista sprawdza cały obrys budynku i dojście przed drzwiami. Wyrównywanie kwartałów wymaga zbadania
  całego obszaru i ogranicza rzeczywistą głębokość wykopu/nasypu do 6 bloków w każdej kolumnie.
- Zajęty kwartał również dostaje wyrównane podwórka i dojścia, łączące rzeczywiste poziomy parterów. Znane
  obrysy pozostają chronione; budynki zgłoszone ręcznie i rezerwacje mają dodatkowy margines bezpieczeństwa.
- Budowniczowie wczytują pełny obszar skarp przed planowaniem. Po niwelacji planista czeka na świeży skan
  terenu, aby kolejny dom nie powstał na dawnej wysokości pagórka. Starsze aktywne plany przeliczają pozostałe
  prace ziemne bez cofania budowy ścian i fundamentów.

## Plan gracza, ręczna niwelacja i odbudowa po pożarze (wave 12)

- Plan zapisany w panelu kraju gracza jest wykonywany stopniowo (jeden kraj na turę), według priorytetów,
  z opłatami ze skarbca oraz normalnymi kontrolami terenu. Nie omija ochrony istniejących budynków.
- `previewTerrainLevel` i `orderTerrainLevel`: prostokąt do 4096 bloków (bok najwyżej 128), automatyczny poziom
  mediany lub jawne Y, maksymalna zmiana wysokości 6 bloków, podgląd kosztu i kolumn pomijanych.
  Podgląd i potwierdzenie czytają aktualny załadowany teren, a wykonanie trafia do trwałego placu niwelacji.
- `requestFireRecovery`: zapisuje budynki w trwałym rejestrze przeglądu. Skan i naprawa korzystają z istniejącego
  budżetu prac; odbudowa czeka na ugaszenie ognia, materiały i pieniądze. Nie naprawia bez szablonu ręcznie
  zgłoszonych budowli. Zlecenia są odporne na restart i nie powielają napraw.

## Ponawianie budowy z planu (wave 14)

Plan jawnie narysowanych budynków (wave 14): tymczasowa przeszkoda nie odrzuca już pozycji na zawsze. Stan
`designFailures` zachowuje osobny powód, liczbę prób i termin `retryAt` dla każdej pozycji; jest zapisany w zwykłym
`data.json`. Po usunięciu przeszkody pozycja wraca do zwykłej kontroli i kolejki bez ponownego zapisywania planu.
Odstępy prób rosną od 30 s do najwyżej 10 min, maksymalnie osiem prób budynków na turę. Starsze `designFailed`
bez szczegółów migrują do ponownej kontroli. Brak szablonu i strefa `nie_ruszac` pozostają odrzuceniami planu.

Brak epoki/odkrycia i niezbadany teren pomijają tylko daną pozycję, pozwalając realizować kolejne dostępne budynki.
Epoka i odkrycie są sprawdzane przed środkami: niedostępny wieżowiec nie rezerwuje budżetu ani nie blokuje targu.
Pełna kolejka i brak środków nadal respektują priorytet planu. Nie zmienia to harmonogramu automatycznego `autoFill`.

`designPlanPreview.orders[]` oprócz dotychczasowego `status` zwraca `reason: string|null` i `retryAt: epochMillis|null`.
Oczekiwanie na ponowną próbę ma status `czeka`, stały błąd `odrzucony`, a przyjęte zamówienie `zlecony`.
Ochrona gracza pozostaje taka sama: plan nie wymusza rozbiórki, kontroli właściciela ani darmowego zlecenia.
Komunikat o przeszkodzie podaje teraz pierwszy materiał i współrzędne X/Y/Z; pochodnia lub drewno nie jest
automatycznie nazywane budowlą gracza. Materiał pozostaje chroniony do ręcznego usunięcia lub przesunięcia planu.

## Budowanie projektu

```
./gradlew build
```

Jar: `build/libs/Ludnosc-1.0.0.jar`. Szablony: `cd templates-src && python generate.py --preview`.




## Królewska przebudowa KRÓLESTWA (wave15)

Nowa administracyjna komenda `/ludnosc admin krolestwo preview|start|status|resume` obsługuje wyłącznie
uzgodniony kraj UUID `9d1fe36e-4852-4d18-9b2a-a89146f43e66`, świat `world` i nowy prostokąt
chunków295..311 ×53..69 (289chunków,4720..4991 ×848..1119), poziom gruntu90, fundament od54,
czyszczenie do158. Pisownia `krolewstwo` pozostaje aliasem. Komenda niczego nie restartuje i nie uruchamia
się sama po starcie pluginu. `preview` wyłącznie sprawdza przygotowany pakiet, a `start` jest poleceniem
rozpoczęcia pełnej, autoryzowanej przebudowy; przed jego użyciem trzeba mieć sprawdzone kopie świata i danych.

Pliki umieszcza się wyłącznie w `plugins/Ludnosc/royal-rebuild/`: `manifest.json`, `plan.json`,
`decorations.json`, `garrisons.json`, `access.json`. Pałac i jego metadata muszą wcześniej być wczytane przez
normalną bibliotekę szablonów (`budynki/palac_krola_maciusa.schem` + `.schem.yml`, `/ludnosc admin reload`).
Manifest używa **środka** budynku `x/z`, `groundY:90`, obrotów0..3 i kontrolnego pełnego obrysu; `level`
jest indeksem stylu materiałów, a rejestr zapisuje go przez `MaterialPalette.encode`. Wszystkie obrysy i drogi
muszą mieścić się na własnym docelowym terenie, wewnątrz aktualnej granicy świata i poza obszarami chronionymi.
Drogi mają jawne `alignment:positive` (poszerzenie0..width-1), rejestrowane są dokładne kolumny, bez wielkich
prostokątów obejmujących zakręty. Dojścia zawierają maks.10000 jawnych zmian wyłącznie w zewnętrznym
czteroblokowym pasie nowego kraju, y54..158. Nie mogą stawiać TNT, ognia, lawy ani bloków poleceń.

Dziennik `state.json` + `.bak` przechowuje pierwotne claims, identyfikatory i obrysy starych budynków,
obywateli, fazę i postęp oraz SHA manifestu i wszystkich assetów. Zapisy mają pojedynczego writera,
atomową podmianę i fsync; błąd zapisu zatrzymuje dalsze operacje. Po restarcie zadanie jest wstrzymane.
`resume` odmawia po zmianie SHA i **odtwarza podest, ewakuację oraz deterministycznie powtarza przebudowę**,
ponieważ zapis Minecrafta mógł cofnąć się względem kursora dziennika. Stałe identyfikatory budynków nie
pozwalają tworzyć duplikatów. Zakończenie wymaga potwierdzonych zapisów Ludnosc, Kraje i Armia oraz
jednorazowego `minecraft:save-all flush`, a dopiero potem trwałego `DONE`.

Mieszkańcy są rozstawiani na tymczasowym podłożu15×15 na y162 nad rynkiem, z zachowaniem poprzedniego
AI i nietykalności w PDC; otrzymują ochronę przed obrażeniami i przemianą na czas zadania. Rekordy `pending`
pozostają oczekujące i nie są uznawane za zaginione encje. Załadowany brakujący żywy NPC po5sekundach
powoduje wstrzymanie, nie przeniesienie samego rekordu. Na początku każdego niszczonego chunka jest
ponowna kontrola rzeczywistych encji odczytanych z NBT. Przed zmianą mapy Armia musi trwale zabezpieczyć42
istniejące jednostki przez `beginRoyalRebuild()`. Końcowy `deployRoyalGarrison(String)` potwierdza32
strażników i10artylerii; ich chunki ładowane są partiami, z czasowymi ticketami. Gracze nie są ewakuowani:
obecność poniżej y161 w aktualnie zmienianym chunku wstrzymuje zadanie.

Niwelacja dotyczy **tylko nowych289chunków**. W starych oddanych chunkach pozostaje naturalny teren;
usuwane są jedynie rozpoznane fragmenty pierwotnych budynków oraz ogień. Rejestruje się rzeczywiste
funkcje, łóżka i punkty spawnu nowych budynków, magazyny, kopalnie i podziemia; cztery mury i bramy mają
własne rejestry. Zapasy opcjonalne `{provisions:{foodStock:10000,farmerTarget:100}}` ustawiają co najmniej
zadaną ilość jedzenia i uzupełniają rolników **wyłącznie z dorosłych bezrobotnych**, z XP1. Nie zmieniają
profesji dzieci, rybaków ani innych specjalistów. Na końcu mieszkańcy trafiają na bezpieczne9×9 pól rynku
(po ok.3osoby na pole przy230NPC), poprzednie AI i nietykalność wracają, a podest znika.

Plan pozostaje widoczny w panelu jako już ukończony i wstrzymany (`designExecutionPaused`, `autoFill:false`,
`allowDemolish:false`), bez ponownego zamawiania184budynków. Świadomy późniejszy zapis w edytorze
zdejmuje to wstrzymanie. Podczas zadania wstrzymane są stare budowy, plany, naprawy, wzrost, rozliczenia,
rekonsyliacja brakujących NPC, pobór, kopalnie i podziemia tego kraju. Zwykłe zachowanie innych krajów
pozostaje bez zmian, w tym ponowienia kolejki planu z wave14.

Powracający gracz nadal zachowuje miejsce wylogowania. Wyjątek ratunkowy dotyczy tylko faktycznej
kolizji ciała ze stałym, nieprzezroczystym blokiem w obszarze zadania; poprawne, przechodnie miejsce
pozostaje nietknięte. Po ukończeniu działa jednorazowo dla dawnych pozycji poniżej91 na nowej ziemi
KRÓLESTWA, z krótkim oknem na powtórną korektę teleportu nLogin. Gracz trafia na najbliższe suche
podłoże nad kolizją, a nie na globalny spawn. Menu i ogólna obsługa logowania nie są zmieniane.

Główna pętla ma budżet2ms i najwyżej2000 operacji na tick. Ładowanie chunków i plikowe checkpointy są
asynchroniczne. Wstępna walidacja assetów, zamknięcie faz metadanych, strictcheckpointy Armia i końcowy
flush świata są celowo osobnymi, jednorazowymi operacjami administracyjnymi; nie mają twardej gwarancji2ms.

## Fizyczne magazyny i oświetlenie — aktualny kontrakt 2026-10-28

Nowy urobek kopalni, kamieniołomów, tartaków, pól i rybaków trafia wyłącznie do rzeczywistej,
powiązanej skrzyni lub beczki. Nie jest dopisywany równolegle do `CountryState.resources`,
nie ma wirtualnego overflow, wyrzucania itemów na ziemię ani automatycznej sprzedaży nowego urobku.
Dawna dzienna produkcja kopalń/ryb oraz dochód za długość galerii nie wytwarzają już pieniędzy ani materiałów.
Poprzednie opisy dziennego wirtualnego urobku w historycznych sekcjach README nie dotyczą tego trybu.

**Brak magazynu, pełny magazyn, niewczytany chunk, zniszczenie kontenera lub zmiana właściciela
zatrzymują pracę przed zmianą kolejnego bloku.** Po zwolnieniu miejsca/ponownym wczytaniu tej samej
skrzyni praca wznawia się automatycznie w godzinach pracy. Zniszczonej i zastąpionej skrzyni nie uznaje
się automatycznie za poprzednią: wymagane jest nowe powiązanie. Status jest widoczny w
`/ludnosc magazyn status`, `/ludnosc kopalnia`, diagnostyce pracownika i snapshotach `LudnoscApi.info`.
Komunikaty obejmują „Magazyn pełny — praca wstrzymana” i „Praca wstrzymana — przypisz magazyn”.

### Powiązanie i automatyczny wybór

| Komenda | Działanie |
|---|---|
| `/ludnosc magazyn powiaz ID` | Powiąż budynek z kontenerem wskazanym do 6 bloków |
| `/ludnosc magazyn powiaz ID ID_MAGAZYNU` | Użyj zarejestrowanego kontenera istniejącego magazynu/spichlerza kraju |
| `/ludnosc magazyn domyslny` | Wskazany kontener jako wspólny magazyn kraju |
| `/ludnosc magazyn status` | ID miejsc pracy, powody wstrzymania i dostępne zapasy |
| `/ludnosc magazyn` | Zapasy fizyczne oraz osobno zachowane składniki historycznego zapasu wirtualnego |

Wymagana jest ranga **Doradca+** albo `ludnosc.admin`. Administrator stojący na terenie kraju NPC
może wskazywać jego budynki. Kontener musi być w całości na ziemi właściwego kraju, w tym samym świecie
co miejsce pracy. Obsługiwane są `CHEST` i `BARREL`, również podwójna skrzynia: walidowane są obie
połówki, ich właściciel i tożsamość PDC. Wspólne inventory jest liczone oraz zdejmowane tylko raz,
niezależnie od liczby powiązanych budynków. Nie udostępnia to magazynów kolejowych/cystern.

Natywny budynek może sam powiązać pierwszą własną skrzynię/beczkę w swoim bbox. Kolejność jest
stała: od najniższego Y, następnie Z i X. Skan jest leniwy, z globalnym budżetem domyślnie 128 bloków
na tick, pamięcią kursora i krótkim cache wyniku negatywnego. Nie ładuje chunków, nie skanuje świata
ani nie szuka w promieniach. Gdy w budynku nie ma skrzyni, używany jest jawny magazyn domyślny lub
kontener znanego budynku `WAREHOUSE` kraju; rolnicy i rybacy mogą też użyć znanego `GRANARY`.
Kraje NPC korzystają z tego samego mechanizmu. Brak dostępnego kontenera oznacza jawne wstrzymanie.

### Przedmioty, pojemność, zużycie i API

Kopalnia i kamieniołom korzystają z rzeczywistych dropów bloków wyliczanych z roboczym żelaznym kilofem:
np. raw iron/raw copper/raw gold, diament, węgiel, lapis, redstone, cobblestone lub cobbled deepslate.
Drwal zachowuje dropy pni i liści; rolnik odkłada rzeczywisty zbiór po odjęciu jednej sztuki potrzebnej
na ponowne zasadzenie; rybak odkłada fizycznego dorsza/łososia. Vanilla AI obywatela-rolnika nie zbiera
równolegle upraw poza kontrolą magazynu. Nie powstaje drugi wirtualny plon. Sezony i nieurodzaj
wpływają na rzeczywisty wzrost upraw. Dzienne żywienie może pobrać potrzebną żywność z fizycznych
magazynów do bieżącego posiłku, rzeczywiście zmniejszając liczbę przedmiotów.

Przed pracą symulowany jest cały pakiet dropów i kosztów z uwzględnieniem limitów stacków i metadanych.
Przed zatwierdzeniem na main sprawdzane są ponownie: dokładne inventory, tożsamość i topologia
kontenera, własność oraz wszystkie źródłowe bloki. Zmiana któregokolwiek warunku anuluje przygotowanie
bez wydobycia. Bloki i cursor postępu zmieniają się dopiero przy udanym depozycie. Nie ma częściowego
wrzucenia i usunięcia całego źródła przy niedoborze pojemności.

Budowy zdejmują faktyczne materiały z fizycznych magazynów (najpierw zgodny materiał, potem dotychczasowa
kategoria surowca), a dopiero następnie korzystają ze starego zapasu wirtualnego. `resources()` i `info()`
w zamrożonym API zwracają wyłącznie niemutowalne snapshoty. Getter API nie czyta `Inventory` ani bloków,
również gdy wywołuje go panel spoza głównego wątku. Snapshoty powstają na main: po zatwierdzonym depozycie
lub zużyciu oraz cyklicznie po jednym kraju na sekundę. Zmiany ręczne/graczy/lejków są widoczne po kolejnym
pomiarze (przy N krajach pełny obieg trwa około N sekund). Odczyt API nie wymusza wczytania magazynu.
Zapas pokazuje dostępne zwykłe materiały; przedmioty z metadanymi pozostają zachowane, ale nie są
samoczynnie używane przez budowy/żywienie. Wirtualna mapa nie jest zwiększana przez nową produkcję.
Pozostaje dotychczasowy, osobny odzysk materiałów z rozbiórek.

### Światło w galerii

Domyślnie kopalnia stawia fizyczną pochodnię na początku i co około 7 bloków postępu, za przodkiem.
Używa istniejącej, pełnej stabilnej posadzki albo ściany (`WALL_TORCH` z poprawnym `facing`). Nie zastępuje
kontenerów, wody, rud, torów ani konstrukcji; cel musi być powietrzem. Podpora też jest ponownie sprawdzana
przed zatwierdzeniem. Istniejące światło jest odczytywane ze świata, a odstęp z zapisanej długości galerii,
więc restart nie zużywa kolejnej pochodni w tym samym miejscu. Jedna pochodnia jest zdejmowana z tego
samego inventory dopiero w udanym pakiecie wydobycia i oświetlenia. Brak materiału daje komunikat
„Brak oświetlenia — brak pochodni w magazynie, praca wstrzymana”; po uzupełnieniu automatyczny powrót.
Pełna skrzynia nadal blokuje wydobycie, również przy dostępnych pochodniach.

```yaml
mines:
  lighting:
    enabled: true
    spacing: 7
    item: TORCH                 # TORCH lub SOUL_TORCH; inne wartości -> TORCH
    consume-from-store: true
production:
  scan-blocks-per-tick: 128
  commit-interval-ticks: 20     # maks. jeden zatwierdzony pakiet na interwał
```

Zmiany konfiguracji wczytuje `/ludnosc admin reload`. Komendy powiązania/statusu oraz bieżący materiał
i odstęp pochodni są dostępne w Menu → Osada → Praca, kopalnie i pory roku. Katalog integratora:
[MENU-ADDITIONS.yml](MENU-ADDITIONS.yml).

### Restart, stare dane i audyt

Nie przeprowadzamy automatycznej migracji starych `CountryState.resources` do skrzyń ani kasowania starych
zapasów. Historia nie pozwala bezpiecznie ustalić, czy gracz już wykorzystał towary. Powiązania kontenerów
z tokenami PDC, ich obie połówki oraz niezakończone zamiary zapisu są przechowywane w danych kraju.
Przy normalnym wyłączeniu przygotowania, które jeszcze nie zmieniły świata, są bezpiecznie odrzucane.

Nowa produkcja zapisuje trwały zamiar przez uporządkowany async checkpoint, czeka na potwierdzenie,
ponownie weryfikuje świat/inventory na main, wykonuje jeden pakiet, zapisuje świat i dopiero potem usuwa
zamiar przez kolejny checkpoint. Po twardej awarii z niejednoznacznym zamiarem praca pozostaje wstrzymana.
Nie odtwarzamy automatycznie dropów ani nie cofamy samego inventory. Administrator porównuje źródło,
skrzynię i zapis, następnie świadomie używa `/ludnosc magazyn audyt ID potwierdz`.
Ta komenda jedynie usuwa blokadę — nie wydaje towaru ani nie odtwarza bloków.

Granica gwarancji: Bukkit `World.save()` i JSON nie stanowią wspólnej transakcji ACID z regionami oraz
zewnętrznymi rollbackami. Dziennik służy odmowie automatycznego ponawiania niejednoznacznych operacji.
Zewnętrzne cofnięcia plików lub utrata zasilania wymagają spójnego audytu. Zapis regionów odbywa się na
main i może opóźnić tick; limit jednego pakietu na interwał ogranicza jego częstotliwość. Częstotliwość i
wydajność przy docelowej liczbie NPC trzeba zweryfikować podczas osobnego smoke testu na Paperze.

### Walidacja

Budowanie: istniejący `gradlew.bat build` z JDK 25. Nowe testy obejmują brak miejsca/pełny pakiet,
automatyczne wznowienie po zwolnieniu miejsca, zachowanie rodzajów/metadanych i limitów stacków,
5000 losowych przypadków zachowania towarów, współdzieloną podwójną skrzynię, deterministyczny skan bbox,
zapis/odczyt starego zapasu oraz tokenów i blokad, odstępy/kierunki/idempotencję oświetlenia, zużycie jednej
pochodni wyłącznie przy udanym planie oraz niemutowalne odczyty cache z wielu wątków bez Bukkit.
Raport Gradle: `build/reports/tests/test/index.html`. Nie uruchamiano produkcyjnego serwera, niczego nie
wdrożono, `medival-api` pozostało bez zmian. Odbiór fizyki pochodni, unload/restart, lejków i opóźnień
zapisu pozostaje do wykonania na izolowanej kopii przed późniejszym wdrożeniem.

Końcowa walidacja 2026-10-28: **Ludnosc 305 testów (22 nowe), Menu 125 testów (1 nowy)**,
oba `gradlew.bat build` zakończone `BUILD SUCCESSFUL` na JDK 25; zero błędów, niepowodzeń i pominiętych.
Pakiet do finalnego Paper/smoke: `smoke-ready-2026-10-28-physical-storage/` — oba JAR-y,
źródła, konfiguracje, katalog komend, logi i `SHA256SUMS`. To przygotowanie, nie wdrożenie.

### Alerty pracy i zaopatrzenia — alerts-release1

Pełny magazyn kopalni, kamieniołomu, tartaku, pola lub przystani, brak wymaganych pochodni oraz brak
lub niedostępność przypisanej skrzyni uruchamiają polskie alerty do władcy i doradców kraju.
Alert pochodzi z rzeczywistej odmowy wykonania pracy; samo wyświetlenie statusu magazynu go nie uruchamia.
Po udanym zatwierdzeniu produkcji i końcowym checkpointcie przychodzi pojedyncze potwierdzenie wznowienia.
Osobny alert podaje niedobór jedzenia na następny dzienny posiłek, uwzględniając zapisany zapas i zwykłą
żywność w dostępnych, zweryfikowanych magazynach. Odczyt nie pobiera przedmiotów ani nie dopisuje zapasów.

Domyślnie przypomnienie jest co 600 s na miejsce pracy (żywność: na kraj), a odbiorca dostaje najwyżej
3 wiadomości na minutę, łącznie z potwierdzeniami. Zarządców krajów NPC wskazuje wyłącznie
`alerts.npc-manager-uuids`; domyślnie lista jest pusta. Istniejące konfiguracje korzystają z nowych tekstów
z zasobu JAR bez obowiązkowego scalenia sekcji `messages`. Pusty tekst konkretnego komunikatu jawnie go wyłącza.

Pełne limity, dokładny zakres plików, testy, klucze konfiguracji i ograniczenia mostu dostaw:
[ALERTS-RELEASE1.md](ALERTS-RELEASE1.md). Opcjonalny fragment do scalenia:
[alerts-config-merge.yml](alerts-config-merge.yml). Pakiet pozostaje lokalnie w `build/alerts-release1/`.

### Odbiór rzeczywistych dostaw — supply-release2

Opcjonalny publiczny most `LudnoscPlugin.supplyDelivery(UUID player, UUID country, String contractId,
String material, int amount)` przyjmuje zwykłe przedmioty z głównego inventory gracza do istniejącej,
zarejestrowanej skrzyni/beczki w magazynie kraju w zasięgu 16 bloków. Wladcy korzysta z tego mostu bez
własnego usuwania towaru. Odpowiedź zawiera `status`, `receipt`, `reason`; po ponowieniu ten sam identyfikator
jest trwale związany z graczem, krajem, materiałem i ilością. Samo odpytywanie nie wykonuje transferu.
Nowe, niegotowe ID otrzymuje po odczytowym preflight `BLOCKED` z pustym receipt, bez rejestracji i blokad.
Znane ID pomija preflight; błąd po rejestracji zachowuje jego receipt i wymaga audytu.

Oba inventory pozostają zablokowane od zapisu zamiaru do rozstrzygnięcia. `DELIVERED` wymaga odczytania
pokwitowania w rzeczywistym pliku GZIP gracza i regionie MCA magazynu, a następnie trwałego zapisu dziennika.
Brak pliku, nieudany zapis lub rozbieżność nie uruchamiają odtwarzania przedmiotów. Po ponownym wczytaniu
sprawdzany jest najnowszy łańcuch trwałych markerów, również dla wcześniejszego `DELIVERED`; późniejsze
przenoszenie przedmiotów i zużycie przez NPC nie muszą zachowywać starego układu slotów.

Opis statusów, limity, dokładne pliki i testy oraz zasady ręcznego audytu:
[SUPPLY-RELEASE2.md](SUPPLY-RELEASE2.md). [Opcjonalny fragment konfiguracji](supply-config-merge.yml).
Pakiet jest lokalny, bez aktywacji na serwerze. Testy: **394 JUnit + 47 asercji izolowanego Paper 26.3**;
probe używa prawdziwych inventory i transportu pamięciowego, bez zdalnego klienta i bez rozliczania nagrody zlecenia.

### Budowniczowie i przepływ materiałów — flow-release1

**Budowniczowie.** Cała ekipa pracuje naraz: blok w zasięgu stawia ten budowniczy, który najdłużej czekał, a wolni
idą przed kursor budowy (kolejne odcinki ściany), zamiast stać. Budowniczy z pustymi rękami idzie do skrzyni budowy
(albo do stosu przed placem) po porcję `construction.carry-load` bloków i niesie je w ręce. Przy przeszkodzie wspina
się po ścianie/rusztowaniu; przeskok następuje dopiero po ok. 2 s całkowitego zablokowania albo 12 s marszu do jednego
bloku. Fundament też jest kopany i stawiany przez budowniczego. Kredyt bloków na wczytanym placu nie rośnie podczas
marszu (bez „magicznych” serii), nadrabianie bez chodzenia dotyczy wyłącznie postępu z czasu niewczytania.
Zmiana szablonu (aktualizacja pluginu) nie cofa już rozpoczętej budowy do niwelacji: plac sprawdza od fundamentu,
postawione bloki są pomijane. Ognisko pozostawione przez restart w nocy znika rano.

**Materiał.** Przy `construction.require-materials: true` fizyczne są drewno i kamień (`logic/MaterialNeeds`):
kłoda = 4 jednostki (4 deski), deska/bruk/kamień = 1; kłody/drzwi/skrzynie kosztują więcej jednostek. Szkło, wełna,
kraty, łóżka itd. pokrywa koszt zamówienia — budowa nigdy nie staje przez nie. Budowniczy wyjmuje kłodę ze skrzyni
budowy i tnie ją na placu (`ConstructionSite.materials`), resztki ze skrzyni po zakończeniu tragarze odnoszą do
magazynu. Brak materiału daje polski powód w hologramie, `/ludnosc budowy` i powiadomieniu władcy (np. „czeka na
kamień — brak w dostępnych magazynach kraju; włóż kamień do skrzyni budowy albo magazynu”).

**Skąd materiał (decyzja 2026-10-29: `require-materials: true`).**

| Materiał w szablonach | Źródło w wiosce | Na budowie |
|---|---|---|
| kłody, deski, schody/półpłyty/płoty/drzwi/klapy z drewna, drabiny, skrzynie, beczki | drwal z tartaku (kłody) | fizycznie: 1 kłoda = 4 jedn. |
| bruk, kamień, cegły kamienne, schody/murki/półpłyty kamienne, andezyt/dioryt/granit, łupek głębinowy, tuf | kamieniołom, kopalnia | fizycznie: 1 szt. = 1 jedn. |
| fundament (bruk/cegła kamienna) | kamieniołom, kopalnia | fizycznie |
| ziemia, trawa (niwelacja) | z wykopu na miejscu | bez kosztu |
| szkło i szyby, wełna i dywany, terakota, cegła gliniana, piaskowiec, kraty, latarnie, łańcuchy, łóżka, dzwony, kwiaty | brak łańcucha w wiosce (piasek, owce, glina, wytop żelaza) | monety z ceny zamówienia |
| siano/strzecha | pszenica z pól idzie na jedzenie | monety |

> Od **chains-release1** (przygotowane, nieaktywne) szkło, piaskowiec, cegły, terakota, wełna, łóżka, siano, żelazo,
> pochodnie i latarnie mają własne łańcuchy produkcji — zob. sekcję „Łańcuchy produkcji wiosek” niżej.

Kraj bez tartaku (drewno) albo bez kamieniołomu/kopalni (kamień) lub bez żadnego zarejestrowanego magazynu kupuje dany
materiał za monety zamówienia, więc młoda wioska się nie blokuje. Kraj, który go wytwarza, czeka na tragarzy najwyżej
`construction.material-wait-minutes` (60), potem również kupuje, aż prawdziwy materiał znów trafi do skrzyni budowy.

**Tragarze (`logistics.*`).** Widoczny NPC niesie prawdziwe przedmioty między zarejestrowanymi skrzyniami:
drewno/kamień z magazynów → skrzynia placu (tyle, ile budowa jeszcze potrzebuje, najwyżej `site-stock` jednostek);
plony i ryby → spichlerz, gdzie stają się zapasem jedzenia (`CountryState.food`), z którego jedzą obywatele i który
zasila wzrost ludności; pełne skrzynie tartaku/kamieniołomu/kopalni (od `unload-fill`) → magazyn kraju, więc praca
nie staje. Przeniesienie to jeden krok na głównym wątku po dojściu: obie skrzynie są ponownie sprawdzane, nic nie jest
„w drodze” w zapisanych danych (restart/niewczytany chunk tylko przerywa kurs). Pomija skrzynie z blokadą dostawy
Wladcy i z przygotowanym zapisem produkcji. Oba końce muszą być wczytane i w zasięgu `max-distance`.

**Warianty i przebudowa (2026-10-29).** Planista NPC, `buduj auto`, przebudowa miasta i chatki obywateli wybierają
wariant typu (np. `dom` → `dom_szachulec`/`dom_kamienny`) stałym kluczem z położenia działki (`logic/VariantPick`),
z kontrolą epoki, odkrycia i miejsca; restart nie zmienia wyboru. `/ludnosc buduj dom` pokazuje wariant działki,
a `/ludnosc buduj wariant` przełącza na następny w tym samym miejscu. Postawione budynki się nie zmieniają.
Przebudowa nigdy nie burzy kopii budowli graczy, domów zgłoszonych przez admina, zabytków ani niczego zamówionego
przez gracza (`CountryState.origins`); w kraju gracza burzy wyłącznie to, co postawił sam planista
(`logic/DemolitionRules`). Licznik ściętych drzew na chatę jest zapisany w placu budowy (przetrwa restart).

**Obieg budżetu gospodarstw (`world:households`).** Dochód obywateli z pracy pochodzi z tej puli; wracają do niej opłaty
za posiłki (`life.meals.to-households: true`) i zakupy na targu — połowa oszczędności ponad 30 md na osobę dziennie
(`life.market.enabled|buffer|share`). Pula startuje od 0; zasilenie startowe z rezerwy (decyzja: 312 600 md = 60 dni)
wykonuje pakiet wdrożeniowy.

| Komenda | Działanie |
|---|---|
| `/ludnosc budowy [kraj]` | Place budowy: etap (niwelacja, fundament, konstrukcja, ściany, dach, okna, wnętrze...), %, budowniczowie, materiał do końca, powód przestoju |
| `/ludnosc gospodarka [kraj]` | Produkcja i stan miejsc pracy, skrzynie z towarem, spichlerz i wzrost, budowy, tragarze w drodze, ostatnie przeniesienia, braki |

`[kraj]` tylko dla administratora. Panel może pobrać ten sam stan przez `LudnoscPlugin#materialFlow(UUID)`
(główny wątek, niemutowalna mapa). Rotacja torów bez `shape` jest liczona jak domyślne `north_south` (0 różnic z
`BlockData#rotate`). Kopie budowli graczy: `/ludnosc kopia` (zob. komendy w MENU-ADDITIONS.yml).

### Łańcuchy produkcji wiosek — chains-release1 (przygotowane, nieaktywne)

Wioska wytwarza sama szkło, cegły, terakotę, wełnę, siano, żelazo i pochodnie. Wszystko trafia do prawdziwych skrzyń
(własna skrzynia zakładu albo magazyn kraju), tragarze wożą dalej. Monety zamówienia pokrywają materiał **tylko wtedy,
gdy kraj nie ma odpowiedniego zakładu** albo jego zakłady nie mają surowca/paliwa dłużej niż
`workshops.stall-grace-minutes` (20). Kraj z działającym zakładem czeka na tragarzy najwyżej
`construction.material-wait-minutes` (60), jak przy drewnie i kamieniu. Pieniądze się nie pojawiają: zakład tylko
zamienia przedmioty z własnej skrzyni na inne.

| Materiał w szablonach | Grupa (jednostki) | Źródło w wiosce | Na budowie |
|---|---|---|---|
| szkło, szyby, witraże | szkło (blok 8, szyba 3) | **huta szkła**: piasek z brzegów → szkło (paliwo) | 1 szkło = 8 jedn. (6 szkła → 16 szyb) |
| piaskowiec (gładki, cięty, schody, płyty) | piasek (blok 4) | huta szkła (wykopany piasek) | 4 piasku = blok |
| cegły, schody/płyty/murki z cegły, doniczki | cegła (1 cegła = 1) | **cegielnia**: glina z rzek → grudki → cegły (paliwo) | 4 cegły = blok |
| terakota (też barwiona i szkliwiona) | terakota (1) | cegielnia: 4 grudki gliny → terakota | 1 : 1 |
| wełna, dywany, sztandary | wełna (wełna 3, dywan 2, sztandar 18) | **owczarnia**: prawdziwe owce, strzyża | dowolny kolor wełny (barwienie na placu) |
| łóżka | wełna 9 + drewno 3 | owczarnia + tartak | 3 wełny + 3 deski |
| bele siana (strzecha) | siano (bela = 9 pszenicy) | owczarnia: pszenica z pól → bele (stodoła) | bela z magazynu |
| kraty, łańcuchy, drzwi i klapy żelazne, kociołki | żelazo (sztabka 8; krata 3) | **kuźnia** + kopalnia: surowe żelazo → sztabki (paliwo) | kute na placu ze sztabek |
| pochodnie | pochodnie (1) | kuźnia: 1 kłoda + 4 węgle → 16 pochodni | z magazynu |
| latarnie | żelazo 8 + pochodnia 1 | kuźnia | z magazynu |
| kwiaty, dzwony, kwarc, miedź, beton, kowadła, ubita glina itp. | — | — | monety zamówienia (bez zmian) |

**Paliwo.** Piece liczą paliwo w wytopach: 1 węgiel albo węgiel drzewny = 8 wytopów (`kiln.smelts-per-fuel`), resztka
przechodzi na następny wsad (zapis w danych kraju). Gdy nie ma czym palić, piec wypala z kłód węgiel drzewny
(pierwszy: 2 kłody → 1 węgiel drzewny, potem 1 kłoda + 1 wytop → 1). Tragarze dowożą węgiel z magazynów, a gdy go
nie ma — kłody.

**Nowe budynki pracy** (epoka 1, poziom Osada/Wieś, kategoria `economy`, planista krajów NPC buduje je sam w ramach
„jednego z każdego”, gracze przez `/ludnosc buduj <typ>`; każdy ma jedną skrzynię = magazyn zakładu):

| Typ | Warianty | Funkcja | Strefa | Pracownik |
|---|---|---|---|---|
| `huta_szkla` | `huta_szkla_murowana` | glassworks | `shore` (blisko wody, na suchym) | hutnik: kopie piasek, wytapia szkło |
| `cegielnia` | `cegielnia_kryta` | brickworks | `shore` | ceglarz: kopie glinę, wypala cegły i terakotę |
| `owczarnia` | `owczarnia_kamienna` | sheepfold | `farm` (obrzeża przy polach) | owczarz: stado, strzyża, bele siana |
| `kuznia` (istniejąca) | `kuznia_kamienna` | smithy (teraz miejsce pracy) | — | kowal: sztabki żelaza, pochodnie |

Podglądy: `preview/huta_szkla*.png`, `preview/cegielnia*.png`, `preview/owczarnia*.png`.

**Wydobycie piasku i gliny** (`workshops.dig.*`): tylko wierzchnia warstwa naturalnego piasku / gliny w promieniu
`radius` (24) od zakładu, **wyłącznie na ziemi kraju**, poza strefami planu gracza, obszarami chronionymi Kraje,
promieniem spawnu (`spawn-radius` 160), obrysami budynków i placów (+`building-margin`) oraz 2 bloki od dróg. Nigdy dół
do jaskini (pod spodem musi być lity blok), nigdy sucha kratka przy wodzie (bez nowych strumieni), brzeg obniżany
warstwami (kratka niżej od wszystkich sąsiadów nie jest kopana), suchy piasek tylko od poziomu morza w górę; glinę dna
rzeki można kopać pod wodą (dziura wypełnia się wodą). Roślina na wierzchu blokuje kopanie. Limit dzienny na zakład:
`sand-per-day` 48, `clay-per-day` 24. Przegląd terenu jest leniwy (`scan-columns` kolumn na przebieg, co
`rescan-seconds`), tylko we wczytanych chunkach.

**Owce.** Owczarnia ma zagrodę z trawą pod gołym niebem. Po zbudowaniu owczarz przyprowadza parę owiec (w cenie
budynku), dalej jagnię kosztuje 2 pszenicy (`breed-minutes`), stado do `sheep.flock` (4). Owce są prawdziwe (PDC
`ludnosc:flock`, trwałe), nie zjadają trawy zagrody, uciekinierzy wracają do zagrody, wełna odrasta po
`regrow-minutes` (10). Owca, która sama wejdzie do zagrody, dołącza do stada. Gdy stado wyginie, nowa para po
`refound-days` (3). Owce rozebranej owczarni stają się zwykłymi owcami. Bez graczy w pobliżu nic się nie dzieje
(zgodnie z resztą modułu — praca fizyczna tylko w wczytanym terenie przy graczach).

**Priorytet paliwa.** Węgiel i węgiel drzewny dostają najpierw piece huty szkła i cegielni. Gdy którykolwiek z nich
ma piasek/glinę, a nie ma czym palić, kuźnia nie spala nowego węgla (kończy tylko opłacone wytopy żelaza), nie robi
pochodni i zamienia kłody na węgiel drzewny, który tragarze wiozą do pieców; tragarze obsługują zakłady w kolejności
piece → owczarnia → kuźnia, a kuźnia nie zamawia wtedy węgla. Pochodnie i bele siana mają limit zapasu (`torch-stock`,
`hay-stock`); zapotrzebowanie budów podnosi go tylko 2× / 4×, nigdy bez końca.

**Wydajność.** Jeden pracownik (Mannequin) na zakład, tylko w wczytanym chunku z graczem w promieniu
`workshops.player-range` (96), w godzinach pracy; budżet `budget-ms` (2 ms) i `per-tick` zakładów na przebieg co 10 ticków.
Każdy krok to jeden zapis `ProductionStorageService.produce` (wsad 8 przedmiotów), więc ogólny limit zapisów produkcji
pozostaje bez zmian. Zakład nie zleca kroku, gdy czeka już `max-pending` (2) zapisów produkcji (kopalnie i pola mają
swoją kolej), a zakład bez własnej skrzyni (praca z magazynu kraju) robi krok najwyżej co `shared-store-seconds` (30 s),
żeby magazyn był wolny dla tragarzy. Tragarz, który doszedł do skrzyni z oczekującym zapisem, czeka obok zamiast zawracać.

**Bilety chunków placów budowy (poprawka stabilności).** `ConstructionService.updateTickets` dodawał bilet pluginu
(`addPluginChunkTicket`) do niewczytanych chunków — Paper ładował/generował je wtedy **synchronicznie** na głównym wątku,
przy starcie pod obciążeniem nawet 64 chunki naraz (10–15 s zawieszenia w próbie nadzoru). Teraz (`logic/TicketPlan`):
wczytane chunki dostają bilet od razu, niewczytane są ładowane asynchronicznie (`getChunkAtAsync`), najwyżej 2 na
przebieg i 6 w toku; bilet jest dokładany po wczytaniu, gdy plac nadal go chce. Czas przebiegu jest mierzony
(ostrzeżenie w logu > 50 ms, `ConstructionService#ticketStats`). Tak samo bilety kopalń (`MineService`). Zapis
`data.json` ponawia przejściowy `AccessDeniedException` (Windows) zanim uzna zapis za nieudany.

**Tragarze.** Nowy kurs „zakład”: surowe żelazo i węgiel do kuźni, węgiel/kłody do pieca, który ma piasek lub glinę,
pszenica do owczarni (na bele i jagnięta). Jedzenie i dostawy do zakładów idą na zmianę. Gotowe szkło, cegły,
wełna, siano, sztabki i pochodnie trafiają na place budowy jak drewno i kamień (z każdej skrzyni kraju), a z pełnej
skrzyni zakładu do magazynu — wsady i paliwo zostają w zakładzie.

**Komendy i panel.**

| Komenda | Działanie |
|---|---|
| `/ludnosc zaklady [kraj]` | zakłady kraju: stan, ostatni wsad, wydobycie dziś / limit, paliwo, stado; materiały z własnych zakładów i kupowane za monety |
| `/ludnosc gospodarka [kraj]` | jak dotąd + sekcja „Łańcuchy produkcji”, stany i braki wszystkich grup |
| `/ludnosc budowy [kraj]` | materiał do końca we wszystkich grupach z oznaczeniem „(magazyn)” albo „(monety)” |

`LudnoscPlugin#materialFlow(UUID)` — sygnatura bez zmian; struktura w wersji **`version: 2`** (dotychczasowe klucze bez
zmian, `workplaces` obejmuje też zakłady, `sites[].needs` ma nowe grupy). Nowe pola:
- `version` = 2;
- `workplaces[].daily`: mapa `minecraft:<materiał>` → sztuki rzeczywiście złożone do skrzyni w ostatnich 24 h (liczone przy
  zatwierdzeniu produkcji, godzinowe kubełki w `CountryState.output24h` w `data.json` — przetrwają restart; ruchy tragarzy
  się nie liczą). Dotyczy pól, kopalń, kamieniołomów, tartaków, przystani i nowych zakładów;
- `sites[].waitingSince`: epoch ms początku czekania placu na materiał albo `null`; `sites[].waitingFor`: grupa (nazwa
  polska) albo `null`;
- `workshops`: lista map `id`, `type`, `function`, `status`, `last`, `dugToday`, `dugLimit`, `fuel`, `flock` (−1 = nieznane),
  `makes` (lista grup), `x`, `z`;
- `chains`: lista map dla każdej grupy `group`, `name`, `producer`, `produced` (bool: kraj wytwarza teraz), `stockUnits`,
  `neededUnits`, `unitsPerItem`.

**Konfiguracja** — nowa sekcja `workshops:` (wszystkie klucze opcjonalne; brak sekcji = wartości domyślne). Wyłączenie:
`workshops.enabled: false` (budowy kupują te materiały za monety jak w flow-release5). Kuźnię bez produkcji:
`workshops.smithy.enabled: false`. Do testów izolowanych `workshops.require-player: false`.

**Wycofanie.** Starszy jar (flow-release5) nie zna funkcji `glassworks`/`brickworks`/`sheepfold`: zbudowane zakłady
wczytają się z funkcją `null` (komendy statusu mogą wtedy zgłaszać błąd). Wycofywać razem z `data.json` sprzed
aktywacji albo przed postawieniem pierwszego zakładu. Bufory placów (`materials`) z nowymi grupami stary jar ignoruje.

Walidacja: `ProductionChainsTest` (15 testów, w tym priorytet paliwa i limity zapasu), `TicketPlanTest` (3), `OutputLedgerTest` (2): koszty bloków, podaż, zakłady, paliwo, 5000 losowych magazynów bez
tworzenia towaru, potrzeby tragarzy, zasady kopania, konfiguracja) oraz próba na izolowanym Paperze `verify-chains/`.

## Życie obywateli — dom, praca, pensje, rodzina, chaty (przygotowane, nieaktywne)

`LifeService` + `plugins/Ludnosc/zycie-obywateli.json` (osobny plik; stary `data.json` bez zmian — brak pliku = nikt
jeszcze nie przydzielony). Ustawienia `life.*` są opcjonalne (bez sekcji działają wartości domyślne z `LifeSettings`).

- **Stały dom i łóżko** (`logic/Housing`): łóżko = (budynek, numer) < liczba łóżek; ważne przydziały zostają, rodzina
  (para + niezamężne dzieci) mieszka razem, bezdomni widoczni w `/ludnosc obywatele`. Przydział co kilka sekund dla
  jednego kraju, z rejestru — także dla niewczytanych i oczekujących obywateli.
- **Stała praca** (`logic/Workplaces`): pole/kopalnia/kamieniołom/tartak/przystań z istniejącego `JobBoard`, potem
  warsztat zawodu (kuźnia, młyn, biblioteka, kościół…), pomocnik na placu budowy, w spichlerzu albo magazynie.
- **Rytm dnia** (`logic/DailyRoutine`, granice jak plan Zycie): noc — łóżko, rano — droga do pracy, praca, południe —
  rynek, wieczór — dom. Fizycznie tylko wczytani obywatele przy graczach (`life.physical.per-tick`, budżet ms):
  łóżko → pamięć vanilla `HOME`, dom/praca → PDC `ludnosc:home`/`ludnosc:workplace` (czyta Zycie); bez Zycie
  wieczorem pathfinder do domu. Niewczytani: logika abstrakcyjna (karta pokazuje, gdzie „są”).
- **Pieniądze (obieg zamknięty, tylko przelewy Skarbca)**: oszczędności obywateli kraju = konto
  `ludnosc:obywatele_<kraj>` z księgą udziałów (codziennie uzgadniana z rzeczywistym saldem). Pensja: przelew
  `country:<kraj>` → konto obywateli (nigdy ponad saldo skarbca minus `wages.min-treasury`; brak = brak wypłaty i
  czynnik nastroju „zarobki” w Zycie). Posiłki: przelew konto obywateli → skarbiec (nie więcej, niż płacący ma).
  Chata obywateli: `withdraw` z konta obywateli (jak zwykła opłata budowy), zamówienie `free=true`, `orderedBy=obywatele`.
  **Podatek NPC (decyzja 2026-10-29, wariant A)**: dochód z pracy `world:households` → konto obywateli (udział
  podatku × `income.employed-factor` 1,1, bezrobotni × 1,0, + posiłki własne i dzieci), potem posiłki i podatek
  przelewem konto obywateli → skarbiec (każdy najwyżej swoje oszczędności), na końcu pensje ze skarbca. Kopalnie jak
  dawniej. `income.tax-from-citizens: false` przywraca bezpośrednią wpłatę. Konto rozwiązanego kraju wraca do Skarbu Świata.
- **Rodzina i wzrost** (`logic/Family`, `logic/LifeGrowth`): pary, dzieci (mały wieśniak z blokadą wieku) dorastają po
  `family.childhood-hours` i przejmują zawód rodzica; wzrost zależy od wolnych łóżek, zapasu jedzenia i zamożności.
  Starość i śmierć (domyślnie włączone): życie 35–50 dni rzeczywistych losowane raz na obywatela; dotychczasowi
  obywatele dostają jednorazowo losowy wiek 0–40% życia (bez fali zgonów); po przekroczeniu — szansa 35%/dzień,
  najwyżej 2 zgony dziennie na kraj. Łóżko wraca do puli, pracę przejmuje dorosłe dziecko, oszczędności dziedziczy
  małżonek/dzieci (księga), a bez nich wracają przelewem do skarbca kraju. Zimą rolnicy pracują w warsztacie/spichlerzu.
- **Samodzielne chaty** (`logic/HutSiting`): gdy brakuje łóżek i są oszczędności — chata w wolnym miejscu planu
  (nie na drogach, planowanych ulicach, polach, cudzej ziemi, poza strefami planu gracza). Domyślnie włączone tylko dla
  krajów NPC; kraje graczy zamawiają chaty ręcznie. Na drewno obywatele ścinają całe naturalne drzewo w pobliżu placu chaty (tylko ziemia kraju, poza
  strefą planu gracza, obszarem chronionym i spawnem, bez bloków budowli obok; sadzonka z dropów wraca), rzeczywiste
  dropy trafiają do skrzyni placu albo magazynu kraju — pełna skrzynia = drzewo zostaje (`huts.felling.*`).

| Komenda | Kto |
|---|---|
| `/ludnosc obywatel [imię]` — dom, praca, rytm, pensja, oszczędności, rodzina (patrząc na wieśniaka) | każdy |
| `/ludnosc obywatele [kraj]` — bezdomni, bezrobotni, średnie oszczędności, pary, dzieci, chaty | każdy |
| `/ludnosc obywatele chatki wl\|wyl\|limit <n>` | Doradca+ |

## Robotnicy manufaktur (Firmy etap 3) — integration-release1 (przygotowane, nieaktywne)

Decyzja użytkownika 2026-10-29: firmy graczy zatrudniają **tylko bezrobotnych** dorosłych obywateli kraju siedziby.
Most refleksyjny (zamrożone `LudnoscApi` bez zmian): `LudnoscPlugin#hireWorker(UUID country, Location near)` →
`Optional<String>` z kluczem zawodu (pusty = nikogo do zatrudnienia; tylko główny wątek). Firmy wołają go przez
`getMethod("hireWorker", UUID.class, Location.class)`; bez niego spadają do `conscript` (bierze także pracujących).

- Bezrobotny = dorosły bez miejsca pracy po świeżym przeliczeniu domów/pracy kraju (`LifeService#unemployedAdults`,
  tablica prac liczona od razu, gdy jej nie ma), ten sam stan co „bezrobotni” w `/ludnosc obywatele`. Wybór
  (`logic/WorkerHire`): najbliższy **wczytany**, nigdy dziecko ani wieśniak w handlu/na smyczy/w pojeździe;
  handlowani z graczami na końcu; remisy po UUID. Kraj w królewskiej przebudowie: odmowa.
- Odejście (`LifeService#leftForWork`): rekord i wpis życia znikają od razu, domy kraju przydzielane ponownie (łóżko
  wolne natychmiast), partner wolny, udział w oszczędnościach zostaje w kasie obywateli (partner albo dzieci; bez nich
  rozkłada go codzienne uzgodnienie — bez przelewu do skarbca). Powrót: `returnCitizen` (nowy obywatel, ten sam zawód).
- `returnCitizen` bez bezpiecznego miejsca na ziemi kraju i z niewczytaną stolicą zapisuje oczekującego obywatela
  w stolicy (dotąd zwracał `false` i obywatel przepadał).
- Testy: `WorkerHireTest` (7); izolowany Paper `verify-ludnosc-integration-20261029/` (Firmy etap 3 `d6a980d3`).
  Pakiet: `deploy-ludnosc-integration-20261029/`.

## Animacje pracy NPC (Interakcje) — npc-anim1 (przygotowane, nieaktywne)

Wszyscy widoczni pracownicy (budowniczowie, tragarze, górnicy, drwale, rolnicy, kamieniarze, rybacy, kowal, hutnik,
ceglarz, owczarz) to ciała `BuilderCrew`; rola z `ensure`, a `move` ustawia pracę w Interakcjach (`hook/WorkAnim`,
refleksja na `WorkAnimations`, bez softdepend) tylko przy zmianie stanu: stoi → `budowa`/`kilof`/`siekiera`/`motyka`/
`kowadlo`/`odpoczynek`, idzie → `noszenie_plecy` (tragarz) albo `noszenie_rece`. Bez Interakcji nic się nie dzieje.
Test `WorkAnimRolesTest`; Paper `verify-npc-anim-20261029/`; pakiet partii 3 `deploy-npc-anim-20261029/`.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
