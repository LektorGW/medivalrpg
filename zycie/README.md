# Zycie — codzienne życie i nastrój obywateli NPC

Plugin Paper 26.3 (Java 25) dla serwera **MEDIVALRPG**. Ożywia wieśniaków-obywateli państw (z pluginu **Ludnosc**):
mają plan dnia, mówią (dymki nad głową), rozmawiają ze sobą, reagują na graczy, chowają się przy oblężeniu, protestują,
gdy jest źle, i świętują po zwycięstwach. Liczy **nastrój** każdego kraju i udostępnia go przez `ZycieApi`
(`moodMultiplier` dla podatków i wzrostu w Ludnosc).

Zycie **nie zapisuje obywateli i nie pobiera podatków** — to robi Ludnosc. Zycie tylko steruje zachowaniem
załadowanych wieśniaków i liczy nastrój.

## Zależności

- `depend: [Kraje, Ludnosc]`, `softdepend: [Armia, Postep, Skarbiec]`.
- Implementuje `medival-api/zycie-api` (`ZycieApi`, rejestrowane w ServicesManager).
- Armia i Postep są pobierane leniwie przez `ServicesManager#getKnownServices()` i refleksję (jak w Kraje), więc nie
  ma cykli zależności: przyszłe `softdepend: [Zycie]` w Armii/Postępie jest bezpieczne. `EraAdvanceEvent` jest
  nasłuchiwany refleksyjnie (rejestracja po włączeniu Postępu, także gdy włączy się później).

## Funkcje

### Plan dnia (czas MC)
| Pora | Ticki (domyślnie) | Co robią |
|---|---|---|
| rano | 0–2000 | vanilla |
| praca | 2000–6000, 8000–9000 | idą do budynku swojego zawodu (rolnik → pole, kowal → kuźnia, kopacz → kopalnia, piekarz → piekarnia, bibliotekarz → biblioteka/szkoła…) |
| południe | 6000–8000 | ~30% na targ / rynek, reszta pracuje |
| spotkanie | 9000–10500 | vanilla (zebranie przy dzwonie) |
| wieczór | 10500–12500 | karczma (jeśli jest) albo plac przy dzwonie |
| noc | 12500–24000 | dom i łóżko (vanilla) |
| msza | niedziela (co 7. dzień MC) 0–3000 | kościół / katedra (brak → dzwon) |

Lokalizacje pochodzą z `LudnoscApi.info` (typy, funkcje i pozycje budynków). Brak budynku danego typu → dzwon
(pamięć `MEETING_POINT` wieśniaka) → centrum chunka stolicy. Wieśniak z własnym stanowiskiem pracy vanilla i bez
budynku zawodu pracuje jak w vanilla.

**Vanilla nie jest łamana.** Mózg wieśniaka (Brain) zostaje włączony — `setAware(false)` nie jest używane, bo
nieświadomy wieśniak w ogóle by nie chodził. Pathfinder walczy z Brain: zachowanie `MoveToTargetSink` nadpisuje naszą
ścieżkę, gdy vanilla ustawi własny cel (spacer, stanowisko, dzwon, łóżko). Dlatego Zycie steruje **krótkimi oknami**:
`getPathfinder().moveTo(...)` najwyżej co `ruch.co-tickow` (60) ticków na wieśniaka, tylko w porach, w których vanilla
nie robi nic ważnego (nigdy w nocy, rano i podczas zebrania przy dzwonie), dalekie cele krokami po 32 bloki. Gdy
wieśniak dotrze (`promien-dotarcia`), sterowanie się kończy i vanilla działa swobodnie w promieniu `smycz`; dopiero
gdy odejdzie dalej, dostaje nowy ruch. Śpiący, handlujący, na smyczy i w pojeździe nie są ruszani.

### Dymki i rozmowy
- `TextDisplay` jadący na wieśniaku jako pasażer (płynnie podąża, bez teleportów), widoczny ~12 bloków (view range),
  delikatnie unosi się (interpolacja transformacji), znika po kilku sekundach. Nietrwały (`setPersistent(false)`),
  oznaczony PDC `zycie:bubble`, sprzątany przy starcie, przy ładowaniu chunków i przy wyłączeniu.
- Pule tekstów po polsku w `config.yml` (`teksty.*`, `rozmowy`) z warunkami: pora, epoka (słownictwo średniowiecza,
  renesansu, przemysłu i nowoczesności), nastrój, wojna (z kim — `<wrog>`), oblężenie, głód, podatki, bezdomność,
  festyn, protest, wydarzenia (nowy budynek, awans epoki, zwycięstwo, porażka, pokój), zawód. Bardziej szczegółowe
  reguły mają pierwszeństwo, ostatnie kwestie się nie powtarzają.
- Rozmowy dwóch stojących obok wieśniaków: 2–3 kwestie na przemian, patrzą na siebie.

### Reakcje na graczy (< 5 bl., z cooldownami)
- władca: „Witaj, panie!”, rodak: „Dzień dobry, sąsiedzie”, zwierzchnik (kraj-suweren), sojusznik — powitania;
- obcy z neutralnego kraju lub bez kraju: „Czego tu szukasz?” (+ kręcenie głową);
- **wróg w wojnie**: „Straż! Wróg!”, wieśniak ucieka do domu (pamięć vanilla `HEARD_BELL_TIME` → aktywność HIDE
  vanilla + ruch pathfindera z prędkością ucieczki), sąsiedzi w promieniu 12 bl. też się chowają. Gdy jest Armia,
  pobliscy żołnierze kraju (rozpoznani przez `ArmiaApi.soldierCountry`, będący `Mob` bez celu) dostają wroga jako cel
  (`setTarget`). To tylko „szturchnięcie” — Armia nie ma API wezwania straży, a jej AI i tak atakuje wrogów w wojnie
  i może cel zmienić.

### Nastrój 0..100
Liczony co `nastroj.przelicz-sekund` (180 s) dla każdego kraju, zmienia się stopniowo (`max-zmiana`):
jedzenie (głód, zapasy spichlerza), mieszkania (bezdomność), podatki (podatek graczy z Kraje, podatek NPC na
obywatela z Ludnosc × mnożnik podatku kraju NPC z Armii), wojny (−), oblężenia (mocno −), pokój (+), zależność
wasalna (−), budynki komfortu wg epoki (karczma, kościół, rynek, łaźnia, szpital/zielarnia, park, szkoła…: +),
rosnące oczekiwania epoki (−), tymczasowe zdarzenia (zwycięstwo, obrona, porażka, utrata ziemi, zawarty pokój, nowa
wojna, nowa epoka, nowy budynek, festyn, zmiana admina). `moodFactors` i `concerns` (np. „Brakuje łóżek (4)”,
„Wojna z Zakonem Krzyżackim”, „Za wysokie podatki”).

**Skutki**: `moodMultiplier` = 0,6 (nastrój 0) … 1,0 (50) … 1,25 (100), liniowo; podczas protestu × 0,85 (nie mniej
niż 0,6). Nastrój < 20 dłużej niż 1 dzień MC → **protest na rynku** (`unrest = true`, wieśniacy idą na rynek i wołają
„Chleba!”, „Precz z podatkami!”), koniec od nastroju 25. Nastrój < 10 → `unrest = true` (sygnał odejść dla Ludnosc).

### Oblężenie
Podczas oblężenia chunka (`KrajeApi.getSieges()`) obywatele obrońcy w promieniu 48 bl. chowają się (vanilla HIDE),
nocą dzwon wioski (z pamięci `MEETING_POINT`) bije alarm (`Bell#ring`, inaczej dźwięk dzwonu). **Pospolite ruszenie**:
przy oblężeniu stolicy ~30% dorosłych (max 6) dostaje widły (motyka) lub siekierę i stoi przy bramie/murze/wieży
(kosmetyka, szansa dropu 0, PDC `zycie:levy`, zabierane po oblężeniu — także wieśniakom załadowanym później).

### Festyny
Po zwycięstwie (zdobycie chunka w oblężeniu, kapitulacja przeciwnika), po `EraAdvanceEvent` (albo zauważonej zmianie
epoki) i po ukończeniu wielkiego budynku (pałac, katedra, zamek — wzrost ich liczby wykrywany okresowo w
`LudnoscApi.info`): festyn na rynku stolicy przez 1 dzień MC — fajerwerki wieczorem (bez obrażeń), muzyka
(`block.note_block.*`, skala pentatoniczna), tańce (podskoki, obroty), dymki „Wiwat!”, `festival = true`, +12 nastroju.
Automatyczne festyny jednego kraju co najmniej co 2 dni MC.

### Zawody miejskie
Profesje vanilla → nazwy zależne od epoki i budynków (sekcja `zawody`): piekarz, karczmarz, strażnik miejski, kapłan,
kupiec, a w epokach wyższych robotnik, urzędnik, lekarz, nauczyciel… Nazwa wieśniaka: „Jan, piekarz” (imię z PDC
`ludnosc:name`, kolor kraju). `zawody-nazwy: false` wyłącza zmianę nazw.

## Komendy

| Komenda | Opis | Uprawnienie |
|---|---|---|
| `/zycie` (`/miasto`) | nastrój mojego kraju, czynniki, zmartwienia | `zycie.use` (wszyscy) |
| `/zycie kraj <kraj>` | nastrój dowolnego kraju | `zycie.use` |
| `/zycie pomoc [strona]` | interaktywna pomoc (najedź = opis, kliknij = wpisz) | `zycie.use` |
| `/zycie admin swieto <kraj>` | festyn teraz (bez cooldownu) | `zycie.admin` (op) |
| `/zycie admin nastroj <kraj> <±n>` | tymczasowa zmiana nastroju (`zdarzenia.admin.czas`) | `zycie.admin` |
| `/zycie admin info` | statystyki (rotacja, dymki, ruchy/min, integracje) | `zycie.admin` |
| `/zycie admin reload` | przeładowanie config.yml | `zycie.admin` |

## Wydajność
- Tylko załadowani obywatele z listy `LudnoscApi.citizens` (`Bukkit.getEntity(uuid)`), rotacja `obywatele-na-tick`
  (10) i budżet `ruchy-na-tick` (4) wywołań pathfindera; sterowanie tylko z graczem w promieniu `zasieg-graczy` (64).
- Brak skanowania świata: budynki z Ludnosc (cache 30 s), reakcje = `getNearbyEntities(5)` wokół graczy co 10 ticków,
  oblężenia = jedno zapytanie o wieśniaków na oblężenie co 40 ticków, rozmowy tylko z kandydatów z rotacji.
- Dymki: limit `max-naraz` (30), nietrwałe, sprzątane.

## Dane
`plugins/Zycie/dane.json` (nastrój, protest, festyn, modyfikatory) — zapis w tle: `.tmp` → `.bak` → atomowa podmiana.

## Pożary osad (wave 12)

- Zapłon, rozprzestrzenianie i wypalenie bloku trafiają do ograniczonej kolejki. Rotacyjny skan przy budynkach
  odnajduje także ogień zastany po starcie. System działa tylko w dozwolonych światach i załadowanych chunkach;
  nie wczytuje świata na potrzeby pożaru. `pozary.*` ustawia limity skanowania, ruchu, incydentów i ekip.
- Dzwon osady faktycznie dzwoni (punkt zebrań mieszkańca albo dzwon przy studni/ratuszu); brak dzwonu daje
  dźwięk alarmowy. Alarm ma przerwę na incydent, a mieszkaniec woła „Pożar!”.
- Mieszkańcy przerywają zwykły plan dnia i uciekają do bezpiecznego domu lub na własną, suchą ziemię poza
  płomieniami. Śpiący są budzeni tylko w bezpośrednim zagrożeniu (do 8 bloków lub gdy już płoną). Handel,
  smycz i jazda nie są przerywane. Dorośli z pustą ręką mogą zostać przydzieleni do ograniczonej ekipy gaśniczej.
- Gaszący chodzą po wodę do rzeczywistej powierzchni źródła przy studni/fontannie (także w głębokiej studni)
  albo do dostępnego brzegu wody w pobliżu. Wracają z widocznym pełnym wiadrem. Nabieraniu i gaszeniu
  towarzyszą ruch ręki wieśniaka, spojrzenie na cel, widoczna smuga wody, chlupnięcie i dźwięk. Jedno nabranie
  gasi najwyżej 5 sąsiednich, widocznych płomieni w zasięgu 4 bloków (`pozary.plomieni-na-wiadro`, 1–8);
  ściana blokuje gaszenie każdego płomienia osobno.
  Brak dostępnej wody lub drogi oznacza ewakuację/oczekiwanie, a nie magiczne gaszenie. Nie usuwają paliwa,
  lawy ani ognisk i nie gaszą na cudzej ziemi. Tymczasowe wiadra mają PDC i zerowy drop; są sprzątane.
- Po pełnym skanie bez ognia i co najmniej 20 sekundach ciszy `requestFireRecovery` przekazuje przegląd
  budynków do trwałej kolejki Ludnosc. Naprawy korzystają z normalnych kosztów i materiałów. Nieobsługiwane
  API jest ponawiane; przyjęta odbudowa nie jest wysyłana ponownie. Budowle ręcznie zgłoszone bez szablonu
  nie mogą być automatycznie odtworzone.
- `plugins/Zycie/pozary.json` przechowuje incydenty, płomienie, alarmy i oczekiwanie na odbudowę — zapis
  w tle z atomową podmianą i `.bak`. Po restarcie trwająca akcja jest kontynuowana; obywatele wracają do
  planu dnia po zakończeniu zagrożenia.
- Sąsiednie ogniska nie blokują mieszkańców przez wygasłe dyżury. Martwi/wyładowani strażacy zwalniają
  miejsca w ekipie; ekipy są obsługiwane rotacyjnie. Źródła są sprawdzane od powierzchni wody, nie od dna;
  nieosiągalne płomienie i trasy są czasowo pomijane. Bezpieczne miejsca uwzględniają wszystkie bliskie ogniska
  danego kraju. Co pół sekundy obowiązuje wspólny limit 256 prób źródeł, 256 pozycji podejścia i 4 wyszukiwań
  ścieżki; efekty nie tworzą osobnych zadań ani encji. `/zycie admin info` pokazuje źródła, ekipy, pełne wiadra,
  rzeczywiste pobrania i ugaszenia, nieudane ścieżki oraz próby bez źródła.

## Dom, praca i pensje z Ludnosc (życie obywateli)

- Ludnosc przydziela każdemu obywatelowi stały dom/łóżko i miejsce pracy i zapisuje je w PDC wieśniaka
  (`ludnosc:home`, `ludnosc:workplace` = `int[]{x,y,z}`). Plan dnia idzie najpierw tam: praca → przydzielone miejsce
  pracy (dopiero potem budynek zawodu, stanowisko vanilla, dzwon), wieczór → karczma (`udzial-karczma`) albo **dom**
  (nowy cel `HOME`, łóżko z Ludnosc; `plan-dnia.wieczor-do-domu: false` przywraca plac przy dzwonie). Noc pozostaje
  vanilla — Ludnosc ustawia pamięć `HOME` wieśniaka na jego łóżko.
- Czynnik nastroju **zarobki** (`nastroj.zarobki.*`): pracownicy bez pełnej pensji obniżają nastrój (do `kara-max`),
  średnie oszczędności od `zamoznosc-od` md dają `premia-zamoznosc`. Zmartwienie `brak_wyplat`. Dane pochodzą z
  `LudnoscPlugin#life()#moodInputs(UUID)` (refleksja, bez zmian w zamrożonym API); starsza Ludnosc = brak czynnika.

## Budowanie
```
./gradlew build
```
Jar: `build/libs/Zycie-1.0.0.jar`. Wymaga `../medival-api/build/libs/medival-api.jar` (`./gradlew jar` w `medival-api`).
Testy JUnit: nastrój (czynniki, zakres, mnożnik), wybór tekstów wg kontekstu i epoki, plan dnia (pora → cel), reakcje
(relacje), progi protestu i festynów, zawody miejskie, domyślny config.

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
