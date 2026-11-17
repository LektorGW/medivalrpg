# Armia

Żołnierze NPC państw i AI państw prowadzonych przez NPC („Władcy NPC”) dla serwera **MEDIVALRPG** (Paper 26.3, Java 25).

## Wymagania

- **Kraje** i **Skarbiec** (twarde zależności), opcjonalnie **Ludnosc** (pobór obywateli, budynki, dezerterzy wracają do ludności).
- Instalacja: `build/libs/Armia-1.0.0.jar` do `plugins/`. Dane: `plugins/Armia/soldiers.json`, `npc.json`, `rulers.json`, `meta.json`
  (zapis w tle: `.tmp` → atomowa podmiana, poprzednia wersja jako `.bak`), log decyzji AI: `plugins/Armia/logs/ai-decisions.log`.

## Jak działa żołnierz

`visual: mannequin` (domyślnie): widoczne ciało to `Mannequin` ze skinem i ekwipunkiem w kolorach kraju; AI, ruch
i punkty życia ma niewidzialny mob-„mózg” (`brain-entity`, domyślnie wędrowny handlarz), **ukryty przed klientami**
(`setVisibleByDefault(false)`). Ciało jest co tick przestawiane na pozycję mózgu, ciosy w ciało przechodzą na mózg.
Rycerz: mózgiem jest koń, ciało siedzi na nim jako pasażer.

`visual: mob`: widoczny humanoidalny mob (`units.*.mob-type`) z ekwipunkiem i głową ze skinem.

Wyłączone zachowania vanilla: despawn, drop i XP, palenie w słońcu, konwersje (topielec, zombie itp.), naturalne cele,
podnoszenie przedmiotów, łodzie/wagoniki/portale, handel, smycz, nametag, reinforcements zombie, rajdy i patrole.

## Komendy

`/armia` (`/wojsko`): `rekrutuj <typ> [ilość]`, `lista [strona]`, `zamna`, `stoj`, `wroc`, `straz [promień]`,
`patrol dodaj|start|wyczysc`, `atakuj`, `ostrzal`, `taranuj`, `amunicja <kamien|kula|kartacz>`, `rozpusc potwierdz`, `bulawa`, `info [kraj]`, `trybut przyjmij|odrzuc <kraj>`,
`przezbroj [typ] [ilość]`, `najmij <typ> [ilość]`, `najemnicy`, `przedluz [id|wszystkich]`, `zwolnij-najemnikow`, `pomoc [strona]`,
`przeladuj` (admin).

`/npckraj` (`/nk`, `armia.admin`): `stworz <nazwa> <kolor> [władca]`, `usun <kraj> potwierdz`, `ustaw <kraj> <parametr> <wartość>`,
`wladca <kraj> <imię> [skin]`, `skarbiec <kraj> daj|zabierz <kwota>`, `armia <kraj> dodaj <typ> <n>`, `armia <kraj> rozpusc`,
`wojna|pokoj|sojusz <a> <b>`, `pauza|wznow <kraj|wszystkie>`, `info <kraj>`, `lista`, `tick <kraj>`, `log <kraj> [ile]`,
`tp <kraj>` (teleport do stolicy obok władcy, `teleportAsync`), `scenariusz lista|pokaz <id>|zaladuj <id> [potwierdz]`,
`wioska [nazwa] [kolor] [władca]`, `wioska potwierdz`.
Nazwy krajów i imiona z polskimi literami wpisuje się w cudzysłowie (podpowiedzi robią to same).

**Buława dowódcy**: PPM na blok — oddział idzie tam, PPM na cel (także z daleka) — atak, Shift+PPM — za mną / stój.

## Machiny oblężnicze (wave 12)

Katapulta jest dostępna w średniowieczu, armata w renesansie po odkryciu prochu. Obie rekrutuje się jak żołnierzy:
`/armia rekrutuj katapulta` lub `/armia rekrutuj armata`; obowiązują koszary, pobór obywatela, skarbiec i zwykłe limity
wojska (globalnie 1000). Obsługa jest widocznym żołnierzem, obok którego podąża model z trzynastu vanilla BlockDisplay:
drewniany wóz, dwa koła z metalowymi piastami i wsporniki. Katapulta ma ruchome ramię oraz łyżkę; armata ciemną lufę,
wylot, metalową obejmę i odrzut. Nie wymaga resource packa. Trafiaj w obsługę, aby zniszczyć machinę.
Modele są tworzone wyłącznie do 64 bloków od gracza, maksymalnie dla 96 machin naraz; dalsza obsługa nadal działa normalnie.

Zbierz oddział przez `/armia zamna`, ustaw go przez `stoj` lub buławę, rozpocznij wojnę i zwykłe `/kraj oblezenie`.
Spójrz na blok w obleganym chunku wroga i użyj `/armia ostrzal` — to jedna salwa załadowanych machin twojego oddziału.
Katapulta: zasięg 40 bloków, przeładowanie co najmniej 8 sekund, amunicja 5 md; armata: 24 bloki, co najmniej 5 sekund,
10 md. Koszt amunicji schodzi ze skarbca osobno za każdy wystrzelony pocisk; brak pieniędzy, przeładowanie lub zasięg
nie pobierają opłaty. O czasie przeładowania decyduje większa wartość z powyższego minimum i `units.*.cooldown-ticks`.
Ponów komendę po przeładowaniu, aby oddać kolejną salwę. Zwykłe `/armia atakuj` i walka AI też używają kul zamiast strzał;
te strzały trafiają wyłącznie wskazany, nadal dozwolony cel żywy (mogą chybić, gdy się przemieści).

`/armia amunicja kamien|kula|kartacz` zapisuje wybór dla zgodnych machin własnego oddziału; ranga i kontrola kraju są
takie jak przy dowodzeniu. Katapulta przyjmuje tylko kamień, armata/istniejąca artyleria kulę lub kartacz. Niepasujące
machiny są pomijane. Wybór przetrwa restart w polu `siegeAmmo`; stare zapisy domyślnie używają kamienia/kuli.
Kamień to duży widoczny brukowiec, łuk lotu i jasny ślad; kula to mały metalowy pocisk, płaski tor, dym i huk.
Kartacz kosztuje 15 md za salwę pięciu drobnych widocznych kul z iskrzącym śladem, w wachlarzu do 16 bloków.
Trafia maksymalnie pięciu różnych żołnierzy/graczy kraju w stanie wojny, każdego najwyżej raz (65% obrażeń jednostki).
Nie atakuje swoich, sojuszników, neutralnych, bezpaństwowców ani potworów i nigdy nie zmienia bloków; zatrzymuje się na
ścianach. Używaj `/armia atakuj` albo automatycznej walki z wrogiem. Przeładowanie armaty nadal wynosi co najmniej 5 s.

Pociski są widoczne, kamienie lecą łukiem, każdy krok lotu sprawdza kolizję z blokami. Maksymalnie 64 pociski naraz
(kartacz rezerwuje pięć miejsc przed pobraniem opłaty). Tory lotu ani uderzenia nie ładują dodatkowych chunków.
Nie ma niszczącego `createExplosion` ani TNT. Uderzenie usuwa maksymalnie 12/18 bloków w małym promieniu, tylko w jednym
chunku aktywnego oblężenia, którego właściciel jest wrogiem atakującego. Przy trafieniu i osobno dla każdego bloku
sprawdzane jest `KrajeApi.canSiegeBreak`: aktualna wojna, role oblężenia, spawn/obszary chronione, ustawienie działań
oblężniczych i lista wykluczeń. Kontenery, płyny i bloki niezniszczalne są dodatkowo pomijane. Własna ziemia, dzicz,
sojusznicy i neutralni pozostają chronieni. Zniszczone bloki nie dają dropu. Starszy provider Kraje bez tej metody
domyślnie zabrania zniszczeń. Historyczny typ `artyleria` zachowuje zapis i wymagania epoki 3; mechanika nie odblokowuje
epok 3/4. Pozostałe jednostki pozostają bez zmian, SiegeContributor nadal liczy wyłącznie obsługę stojącą w chunku.

Modele i kule mają PDC Armia, nie zapisują się jako osobne jednostki, znikają po odłączeniu jednostki/wyłączeniu pluginu,
a osierocone encje są czyszczone przy starcie i ładowaniu chunków. Istniejący `soldiers.json` nie wymaga migracji.
Przy aktualizacji konfiguracji zachowaj własne ustawienia i dołącz `units.katapulta`, `ai.unit-mix.katapulta`, komunikaty
`messages.siege-*` oraz pomoc `messages.help-armia.entries.ostrzal` / `amunicja` z nowego pliku. Dokładna lista brakujących
kluczy do skopiowania: `siege-config-merge.json`. Nie zmieniaj limitu epok.

## Taran (2026-10-29, przygotowany)

Taran (`units.taran`, epoka 1, bez odkrycia) to machina bez pocisków. Rekrutacja jak każda jednostka:
`/armia rekrutuj taran` — koszary, pobór obywatela, skarbiec (800 md, żołd 35 md/dzień) i limity wojska (globalnie 1000);
dostępny dla krajów graczy i NPC (`ai.unit-mix.taran: 1`). Obsługa nie poluje na ludzi (broni się tylko na rozkaz
`/armia atakuj`). HP 60: strzały, bełty i rzucane pociski ×0,25 (`ram.arrow-multiplier`), ogień i lawa ×2,5
(`ram.fire-multiplier`); płonąca strzała lub miecz z Zaklętym ogniem podpala taran. Pociski machin liczą się w pełni.

`/armia taranuj` — zbierz tarany przez `/armia zamna`, spójrz na bramę lub dół muru w aktywnie obleganym chunku wroga.
Taran podjeżdża (prędkość 0,08), aż głowica (≈`ram.head-reach` 7 bloków przed obsługą) dotknie celu, obraca się
prostopadle do muru i uderza co `cooldown-ticks` (min. 60 = 3 s). Animacja `uderzenie` startuje w ticku decyzji,
bloki dostają cios na klatce uderzenia (0,12 s = 2 ticki, `SiegeRules.ramImpactDelay`), a `ladowanie` kończy się, gdy
wolno uderzyć ponownie. Cios trafia płaszczyznę 3 szer. × 3 wys. od ziemi, w jednym chunku z celem. Każdy blok
sprawdzają te same reguły co machiny i TNT: aktywne oblężenie tego chunka przez ten kraj, `KrajeApi.canSiegeBreak`
(wojna, spawn/obszary chronione, lista wykluczeń), do tego kontenery, płyny i bloki niezniszczalne są pomijane.
Bloki się zużywają (`ram.strength`: drewno i miękkie 0,5 → 2 ciosy, kamień 0,25 → 4, umocnienia — `fortification-blocks`
i twardość ≥ 3 — 0,1 → 10); jeden cios niszczy najwyżej `blocks-per-strike` (4). Bez dropu i bez fizyki bloków
(bez łańcuchów zniszczeń); drzwi znikają w całości. Po przebiciu płaszczyzny taran wchodzi do 2 bloków głębiej, gdy mur
trwa, potem rozkaz się kończy. Rozkaz przerywają `stoj`/`wroc`/zmiana dowódcy, koniec oblężenia lub `ram.order-seconds`
(180 s, nieosiągalna brama). Rozkazy nie przetrwają restartu (AI wydaje je ponownie).

**AI.** Oblężenie fizyczne kraju NPC zabiera najbliższy taran (`NpcAiService.sendTo`, obrona — bez taranów);
`NavalAiService.ram` co 5 s wybiera bramę (drzwi, furty, klapy, kraty — pierwszeństwo) albo najbliższy blok muru
obleganego chunka od strony taranu i wydaje rozkaz (`ai.sieges.rams`). W bitwach w tle (podbój NPC↔NPC) taran jest
machiną jak katapulta: zmniejsza premię umocnień (`engine-vs-fort`, najwyżej 2 machiny w wyprawie).

**Kolizja.** Model `armia_taran` jak inne machiny: kolizja tylko dla rozstawionej machiny (stoi ≥ 2 s), znika przy ruchu,
uderzeniu albo gdy w jej obrysie znajdzie się gracz — da się obejść, nikogo nie więzi. Bez BetterModel taran ma własny
model z 13 BlockDisplay (rama, koła, dach ze skóry, belka z okuciem kołysana w rytm uderzeń).

Config produkcji: dopisać `units.taran` (z `ram:`), `ai.unit-mix.taran`, `ai.sieges.rams`, komunikaty `siege-ram-*`
i `help-armia.entries.taranuj` (scalony plik w `deploy-taran-20261029/config/`). Brak `units.taran.ram` = wartości
domyślne z kodu (identyczne z configiem w jarze; test). Testy: `logic/RamRulesTest`, `ConfigMessagesTest`; próba Paper:
`verify-taran-20261029/` (port 25614).

## Flota morska i desanty (wave 16)

`/armia flota zbuduj transportowiec|karawela` woduje większy statek tam, gdzie patrzysz (woda do 12 bloków).
Koszt schodzi ze skarbca; wodowanie wymaga własnego akwenu albo dziczy, wolnego pełnego kadłuba i miejsca na maszty.
Cały obszar musi być już załadowany. Transportowiec: średniowiecze, 3000 md, kadłub ok. 3×9 bloków, 100 HP,
4 graczy **łącznie z kapitanem** oraz 16 NPC. Karawela: renesans, 6000 md, ok. 4×11 bloków, 160 HP,
6 graczy łącznie z kapitanem oraz 32 NPC. Limity: 3 statki kraju / 24 na serwerze. Epoki 3/4 pozostają wyłączone.

Kadłub, pokład, burty, beczki, maszty, reje, żagle, proporce i działa są z vanilla BlockDisplay. Pokład ma fizyczne
podparcie z 12 niewidzialnych, skalowanych shulkerów na osobnych nośnikach Interaction. Gracze chodzą i skaczą po nim
swobodnie; podczas rejsu zachowują pozycję względem statku. Sternik zajmuje osobne stanowisko i steruje W/S oraz A/D.
Modele i podłoga podążają za łodzią, a kolizja sprawdza wodę pod całym kadłubem, przeszkody nad nim i odstęp od innych statków.
Wąskie rzeki, płytkie pomosty i niskie mosty zatrzymują duży statek. Statek nie wycina terenu ani nie wymusza chunków.
Przy odtwarzaniu model pojawia się dopiero przy graczu i załadowanym, wolnym akwenie.

Komendy:

- `wejdz`: członek kraju wchodzi na pokład do swobodnego chodzenia. Nadal obowiązuje limit graczy danego typu statku.
- `steruj`: gracz z prawem dowodzenia zajmuje wolny ster na własnym statku. Shift zwalnia ster i stawia sternika na pokładzie; podczas chodzenia Shift oznacza zwykłe kucanie.
- `zejdz`: zejście na bezpieczny pobliski brzeg; gdy brzegu nie ma w zasięgu, do wody obok burty z 30 sekundami oddychania pod wodą i 20 sekundami łaski delfina. Można też samodzielnie zejść za burtę. Statek i jego ładunek pozostają na miejscu. Zewnętrzny teleport i śmierć usuwają powiązanie pasażera ze statkiem.
- `zaladuj`: zaokrętowanie wybranego przez `/armia zamna` oddziału. Żołnierze muszą być fizycznie załadowani, do 12 bloków od
  łodzi i widzieć ją. Zachowują swoje identyfikatory, HP, typ, żołd i miejsce w limicie 1000; pole `transportShip` wskazuje statek.
  Encje znikają, a spawn/follow/relocate/naprawa encji są zablokowane na czas przewozu. Do czterech postaci na pokładzie pokazuje ładunek wojskowy.
- `desant`: spójrz na brzeg do 24 bloków od statku. Wyładunek tworzy żołnierzy na osobnych suchych, bezpiecznych pozycjach,
  na własnej ziemi, w dziczy albo na ziemi kraju w stanie wojny. Sojusznicy i neutralni pozostają chronieni. Gdy brakuje miejsc,
  reszta wojska czeka w zapisanym ładunku; ponów desant lub wybierz inny brzeg. Flotą dowodzą gracze, AI żołnierzy działa po wyładunku.
- `salwa`: gracz z prawem dowodzenia na własnej karaweli spogląda na wrogi statek do 48 bloków. Renesans i odkrycie prochu są
  wymagane. Widoczna kula kosztuje 30 md, przeładowanie trwa 10 s; trafienie zadaje 30 HP, poruszający się cel może uniknąć kuli.
  Maksymalnie 24 kule morskie naraz. Kule morskie zmieniają wyłącznie HP wrogiego statku w aktualnej wojnie; nie niszczą terenu.
- `lista`: pozycje, HP i liczba przewożonych NPC własnej floty. `usun potwierdz`: Władca usuwa najbliższy pusty statek bez zwrotu pieniędzy.

Neutralni nie mogą wsiadać, przejmować ani uszkadzać obcego statku. Trafienia w prawdziwą łódź od gracza/żołnierza w wojnie
również obniżają HP (1–10 na trafienie, nie częściej niż raz na 0,5 s na kadłub). Przy zatonięciu pasażerowie trafiają na
bliski dozwolony brzeg albo do wody w miejscu statku i dostają 30 s oddychania pod wodą oraz 20 s łaski delfina.
NPC wysiadają na bliski bezpieczny brzeg; bez takiego brzegu giną jako strata wojenna. Upadek/usunięcie kraju usuwa jego flotę.

`LandingSupport.canLand` udostępnia Kraje dowód transportu dla `/kraj desant`: własny, aktywny statek w tym świecie do 48 bloków
od chunka, co najmniej 24 faktycznie przepłynięte bloki z ludźmi/wojskiem i obecna siła na pokładzie albo przywieziona siła
nadal obecna w okolicy. Dowód wyładunku wygasa po 120 s i wymaga, aby statek nadal był blisko. O rozpoczęciu oblężenia,
kosztach i przyczółku decyduje Kraje; wyładunek sam nie zmienia właściciela ziemi.

Flota jest w `fleet.json` z `.tmp` → atomową podmianą i `.bak`; pozycje zapisują się co 30 s oraz przy komendach,
obrażenia co 5 s. Żołnierze nadal mają jeden zapis w `soldiers.json`. Modele są nietrwałe i oznaczone PDC; po załadowaniu
chunków osierocone części są usuwane, a statki odtwarzane z rejestru. Chodzący pasażer po powrocie pozostaje w miejscu
wylogowania; członkostwo wraca wyłącznie na pokładzie zapisanego statku własnego kraju. Stare zapisy siedzeń mogą jednorazowo
przenieść gracza na pokład tego samego statku do 12 bloków; daleki statek nie teleportuje gracza z brzegu.
Uszkodzone dane próbują odzyskać `.bak`; błąd obu plików wyłącza flotę i zachowuje dane. Żołnierze wskazujący brakujący statek
pozostają w kwarantannie, z komunikatem w logu dla administratora do odzyskania pasującego `fleet.json` z kopii.

Merge samych brakujących komunikatów/pomocy: `naval-config-merge.json`; zachowaj także wcześniejszy `siege-config-merge.json`.
Testy geometrii i izolowany serwer Paper sprawdzają fizyczne collidery, obrót, ruch i usuwanie modelu. Sterowanie gracza
i zgodność z Grim wymagają sprawdzenia w grze po aktywacji pakietu. Nowy JAR w `plugins/update/` zostanie aktywowany przy
następnym uruchomieniu serwera. Nie używaj przeładowywania pluginów jako zamiennika uzgodnionej aktywacji.

## AI państw NPC: desanty, flota, przyczółki, unie i zamknięta pula (2026-10-29)

**Desant AI.** Kraj NPC w wojnie z wrogiem, z którym nie ma granicy lądowej (albo który zajął jego ziemię za morzem),
co tick decyzji sprawdza `logic/NavalPlanner`: postawa obronna i tryb kryzysowy płyną wyłącznie po utracone ziemie;
poza tym wymagane są agresja ≥ `ai.naval.min-aggression` i stosunek sił ≥ `min-advantage`. Cel: nadbrzeżny, załadowany
chunk wroga przy graczach (jak oblężenia lądowe), którego utrata nie rozcina wroga i który nie jest stolicą; rejs
24–`max-range` bloków; co najmniej `min-soldiers` wolnych żołnierzy i pieniądze na statek (o ile nie stoi wolny) oraz
desant (`siege.cost + siege.landing.extra-cost` z Kraje) ponad rezerwę na żołd. Operację prowadzi `ai/NavalAiService`
(co sekundę, budżet 2 ms, tylko załadowane chunki): wodowanie transportowca (karawela w renesansie przy zapasie
pieniędzy; te same limity 3/24 i epoki co gracze), zbiórka na brzegu i zaokrętowanie, rejs autopilotem po wodzie
(objazdy przy przeszkodach, statek nie wycina terenu ani nie wczytuje chunków), wyładunek na suchym brzegu chunka
celu i `KrajeApi.startSiege` — Kraje traktuje nieprzylegający chunk jako desant i sprawdza dowód transportu
`LandingSupport`. Brak postępu przez `timeout-minutes` kończy operację: żołnierze schodzą na własny brzeg albo
dziczy; nikt nie zostaje porzucony na pokładzie. Jedna operacja na kraj, `cooldown-minutes` między nimi i
`max-operations` na serwer. AI **nie** wypowiada nowych wojn zza morza — działa tylko w trwających wojnach.

**Obrona wybrzeża i przyczółki.** Desant na kraj NPC (oblężenie chunka bez sąsiedniej ziemi atakującego) ściąga
1,5× więcej obrońców z promienia 160 bloków. Wrogi statek z ludźmi/wojskiem do 3 chunków od wybrzeża wywołuje alarm:
do 6 strażników na brzeg na 3 minuty. Przyczółki: reguła spójności oblężeń jest teraz taka sama jak w Kraje
(`ChunkPlanner.siegeKeepsConnectivity`) — wcześniej kraj NPC nie oblegał żadnego chunka lądu kraju, który miał
przyczółek. Utracony przyczółek jest odbijany lądem (gdy przylega) albo desantem.

**Machiny w oblężeniach AI.** Katapulty/armaty wysłane do oblężenia NPC ostrzeliwują oblegany chunk jak `/armia ostrzal`
(te same reguły `canSiegeBreak`, amunicja ze skarbca, `ai.sieges.artillery-shots-per-run`). TNT pozostaje mechaniką
graczy (zweryfikowany zapłon); AI go nie używa.

**Unie i ambasady.** Partner unii personalnej nigdy nie jest celem wojny (dawniej AI ponawiało odrzucaną wojnę).
Ambasada w którąkolwiek stronę daje +10, unia +40 do opinii używanej przy wyborze wojen i sojuszy. Od 2026-10-29 Kraje
domyślnie oddaje te decyzje SI (`union.npc-auto-refuse: false`, `embassy.npc-auto-decide: false`): kraj NPC odpowiada
na `UnionProposalEvent` / `EmbassyRequestEvent` po ok. 1 s, a wnioskodawca dostaje odpowiedź na czacie (`logic/RelationBonds`).
Zasady unii: tylko z **istniejącym sojusznikiem**, przy wysokiej opinii i dyplomacji ≥ 40, nigdy w wojnie ani przy
wrogim nastawieniu; dumny, agresywny władca odmawia. Unia z krajem NPC **nie daje przejęcia państwa**: monarcha gracza
jest tytularny (Kraje nie dodaje go do członków), kraj NPC zachowuje skarbiec, ziemię, wojsko i zarząd SI; gracz płaci
`union.form-cost`. SI rozwiązuje unię, gdy jej opinia o kraju monarchy spadnie do −20 − dyplomacja/5. Ponowna prośba
tego samego kraju w ciągu 60 min jest odrzucana bez rozważania (sojusznik może ponawiać prośbę o ambasadę). Bez Armii
prośby wygasają po czasie z Kraje — nic nie czeka w nieskończoność.

**Kolej (most dla pluginu Kolej).** `ArmiaPlugin.npcRailConsent(hostId, applicantId|null, baseFee) → "ACCEPT" |
"ACCEPT_FEE:<md>" | "REFUSE:<powód>"` — natychmiastowa odpowiedź gospodarza NPC: wojna/wrogie nastawienie → nie;
sojusz, unia, ambasada lub dobra opinia → za darmo; inaczej opłata rosnąca z nieufnością i bogactwem
(`ai.rail.base-fee`). Wpis w dzienniku AI. Pobranie opłaty (przelew do skarbca gospodarza) należy do wywołującego.

**Zamknięta pula pieniędzy.** Przy aktywnej puli Skarbu Świata (`ai.economy.closed-pool: auto`) „dochód kraju NPC”
nie powstaje z niczego: Skarbiec przenosi go z `world:payments` (budżet operacyjny, do którego trafiają żołd, opłaty
i koszty), a Armia bierze najwyżej `payments-share-per-tick` nadwyżki ponad `payments-floor` na kraj i tick.
Mnożnik podatku obywateli NPC idzie z budżetu obywateli (`world:households`). Armia AI nie rośnie ponad to, co dochód
i oszczędności opłacą przez `sustain-days` (bez spirali dezercji). Dodawanie żołnierzy przez admina/scenariusz/panel
nie przekracza już globalnego limitu 1000 (dawniej do 2000).

Ustawienia: `ai.naval.*`, `ai.economy.*`, `ai.rail.base-fee`, `ai.sieges.artillery*`. Testy logiki:
`NpcWave17DecisionsTest`. `ai.naval.require-players-near: false` służy wyłącznie próbie na izolowanym Paperze.

## Wyprawy dalekie — desant przez morze (2026-10-31, przygotowane)

Desant fizyczny (`ai.naval.max-range` 256 bloków, rejs tylko w wczytanych chunkach przy graczach) nie dosięga wroga
za tysiącami bloków niewczytanego morza. Wtedy `ai/NavalAiService` prowadzi **wyprawę daleką** (`ai.naval.long-range`):

1. **VOYAGE** — wybrani żołnierze (wolni strażnicy, najwyżej `max-soldiers`, zawsze zostaje `home-guard-share` armii,
   jeden taran na bramy) znikają z domu jako kampania `desant:<id>` — rekord zostaje (limit 1000, żołd), nic nie jest
   kopiowane. Statek (transportowiec, w renesansie przy zapasie karawela) jest opłacany przy wypłynięciu przelewem
   skarbiec → budżet operacyjny świata. Czas rejsu = `minutes-per-1000-blocks` × odległość (min/max). Obrońcy (gracze
   online kraju-celu) dostają wieść z kierunkiem i czasem przybycia, ostrzeżenie `approach-warning-minutes` przed i alarm u brzegu.
2. **OFFSHORE** — flota czeka (`wait-minutes`), aż przy nadbrzeżnym chunku wroga będą gracze (ta sama zasada co
   oblężenia lądowe). Wtedy statek pojawia się na wczytanej wodzie (dzicz/własna/wroga), nigdy w obszarze chronionym
   Kraje, a żołnierze trafiają na pokład (`transportShip`) — przebyta odległość to `sailed` statku.
3. **LAND/SIEGE** — zwykły desant: wyładunek tylko pod gołym niebem, `KrajeApi.startSiege` z prawdziwym dowodem
   `LandingSupport`, dalej zwykłe oblężenie (tarany, artyleria, stolica chroniona, bez dzielenia kraju).
4. **HOMEWARD** — po porażce, pokoju, zatopieniu statku, braku okazji lub `/nk desant … anuluj` ocaleli wracają w tle
   na swoje posterunki, statek odpływa (nie zostaje we flocie). Po zdobyciu przyczółka żołnierze na lądzie zostają jako załoga.

Stan jest w `npc.json` (+ `soldiers.json`/`fleet.json`) i przetrwa restart. AI sama płynie tylko na kraje graczy
(`vs-npc: false` — z NPC walczy w tle przez `ai.conquest`). Admin: `/nk desant <kraj> [cel]` (od razu, bez cech
charakteru/postawy/przerwy; reguły, limity i koszty bez zmian), `status`, `anuluj`, `teraz` (pomija resztę rejsu).
Log decyzji podaje teraz powód odmowy dla każdego wroga (wcześniej tylko dla ostatniego na liście).
Test: `verify-armia-desant-20261031/` (izolowany Paper, bot-obrońca, restart w trakcie rejsu).

## Wojny państw NPC w tle — podbój poza zasięgiem graczy (2026-10-29)

Wcześniej oblężenia AI działały tylko w załadowanych chunkach przy graczach, więc wojna dwóch odległych krajów NPC nie
przynosiła zdobyczy. Teraz `ai/ConquestService` co `ai.conquest.interval-minutes` (5) prowadzi **ofensywy w tle**:
abstrakcyjne oblężenie jednego chunka wroga, liczone w rundach (`logic/conquest/BattleMath`), bez wczytywania chunków
i w budżecie `budget-ms` (4 ms na sekundę, reszta w kolejnej sekundzie).

- **Start** (`ConquestRules.cannotStart`): kraj NPC z aktywną AI, w wojnie od `min-war-minutes`, bez fizycznego oblężenia,
  po `offensive-cooldown-minutes`, poniżej dziennych limitów (`max-chunks-per-day` kraju, `max-loss-per-day` ofiary,
  `global-max-chunks-per-day`), zmęczenie < `exhaustion.block-offensive`, co najmniej `min-soldiers` wolnych żołnierzy
  i pieniądze na koszt oblężenia z Kraje + zaopatrzenie. Postawa obronna — tylko odbijanie utraconych ziem.
  AI atakuje tylko cel, przy którym jej własny szacunek (ten sam wzór co bitwa, bez losowości) daje przewagę
  co najmniej `min-odds` (1,2) — bez tego słabsze państwo wykrwawiało się w beznadziejnych wyprawach (próba Paper).
  **Najwyżej jedna ofensywa na kraj** (także łącznie z fizycznym oblężeniem z `NpcAiService.siege`).
- **Cel** (`ConquestRules.targets`): najpierw front (chunk wroga przy naszej ziemi, utracone ziemie pierwsze), według
  zasad Kraje: bez rozcięcia wroga, stolica dopiero jako ostatni chunk. Bez wspólnej granicy — **wyprawa** na skraj ziem
  wroga do `expeditions.max-distance-chunks` (96); wygrana zakłada **przyczółek** (limit `siege.landing.max-bridgeheads`
  z Kraje), z którego kolejne ofensywy idą już frontem. Wojny AI mogą teraz dotyczyć krajów NPC w promieniu
  `war-range-chunks` (80), nie tylko bezpośrednich sąsiadów.
- **Wojsko**: do `max-force` (12) najsilniejszych wolnych żołnierzy (najwyżej 2 machiny), zawsze zostaje
  `home-guard-share` (35%) armii. Żołnierz w polu ma w `soldiers.json` pole `campaign` (stare zapisy bez niego), nie ma
  encji, ale nadal liczy się do limitów kraju, limitu **1000**, żołdu i morale. Żołnierz z encją idzie tylko, gdy nie ma
  przy nim gracza. Po ofensywie ocaleni wracają (pojawiają się w ostatnim miejscu, gdy chunk się wczyta).
- **Bitwa** (runda): siła = Σ(siła oblężnicza jednostki × epoka/HP × morale) × mnożniki. Atakujący: odległość od stolicy
  (`distance-penalty`), wyprawa ×`expeditions.strength` (+`naval-bonus` z własnym statkiem), zmęczenie wojną, rozległe
  terytorium; + `ally-share` siły sojuszników NPC w wojnie z tym wrogiem. Obrońca: żołnierze w promieniu
  `defence.garrison-radius-chunks` + `reinforcement-share` reszty, `militia`, premia stolicy (`capital-bonus`, maleje do 0 na
  `capital-bonus-range`), **umocnienia Ludnosci** (mury/bramy, wieże, zamki/pałace w promieniu `fort-radius-chunks` —
  z istniejącego `LudnoscApi.info`, tylko odczyt), które machiny atakującego częściowo niwelują (`engine-vs-fort`).
  Postęp rundy = (przewaga + losowość `luck`) × `progress-per-round`; straty obu stron są prawdziwe
  (`registry` + `destroy`, wpis w `warLosses`). Koniec: 100% = zdobycie; za mało żołnierzy, odparcie (0% po 3 rundach),
  `max-rounds` albo 3 rundy bez zaopatrzenia = porażka (cel blokowany na `target-retry-minutes`).
- **Gracze**: gdy gracz jest w `player-radius` (160 bl.) od celu, ofensywa staje („wstrzymana — walka fizyczna”), po 30 min
  kończy się; działa wtedy zwykły system oblężeń. **Ziemi krajów graczy nie zdobywa się w tle**
  (`abstract-vs-players: false` i niezależnie Kraje `siege.abstract.allow-player-countries: false`). AI nadal może
  wypowiadać wojny graczom jak dotąd.
- **Skutki** stosuje Kraje (most `KrajeBridge` → `KrajePlugin#abstractSiegeBegin/Conquer/abstractRevolt`, bez zmiany
  wspólnego API): koszt oblężenia (+ `siege.abstract.expedition-extra-cost` za wyprawę), `ClaimCause.CONQUEST`, łupy,
  przeniesienie obywateli i budynków Ludnosci (`ChunkOwnerChangeEvent`), upadek kraju przy ostatnim chunku. Kapitulacja
  i wasalstwo NPC→NPC — istniejące `maybeCapitulate`/`answerCapitulation` (utracone chunki liczą się jak przy oblężeniu).
  **Pokój z warunkami**: pokój bez kapitulacji po zdobyczach → przegrany płaci trybut (`peace.*`) przez `tribute-days`.
- **Równowaga**: zmęczenie wojną 0–100 (`exhaustion.*`: rośnie w wojnie, za straty, utracone ziemie i nieudane
  ofensywy; spada w pokoju; od 60 kraj sam proponuje pokój — a gdy w tej wojnie stracił więcej ziemi, niż zdobył,
  proponuje **kapitulację**; zwycięzca zmęczony od 60 przyjmuje kapitulację, choćby zwykłe zasady jej odmawiały
  (chciwy władca nadal żąda wyższego trybutu); od 75 kraj przyjmuje każdy pokój). Bez tego próba Paper kończyła się
  impasem: obie strony wyczerpane, pokój zablokowany minimalnym czasem wojny Kraje (2 h), silny zwycięzca odrzucał wasala. Rozległe terytorium: powyżej
  `overextension.free-chunks` (100) dzienny koszt zarządu `cost-per-chunk × nadwyżka × (1 + nadwyżka/100)` i słabsza armia.
  **Bunty**: ziemie zdobyte w tle przez `revolt.window-hours` (72 h) mogą wrócić do dawnego właściciela (szansa rośnie ze
  zmęczeniem i rozmiarem kraju; najwyżej `max-per-day`). **Odpadanie wasali** od wyczerpanego/rozległego suwerena NPC
  (`vassals.*`). **Koalicja**: kraj NPC z `coalition.share` (30%) ziem NPC albo `ratio` ×1,8 drugiego jest hegemonem —
  opinia innych o nim spada (do −60), a wrogo nastawieni dołączają do wojen przeciw niemu.
- **Pieniądze** (zamknięta pula): koszty wojny, zaopatrzenie i zarząd terytorium to `withdraw` (Skarbiec przenosi je do
  `world:payments`), łupy i trybuty to przelewy skarbiec→skarbiec; każdy z powodem w księdze i w dzienniku AI.
- **Widoczność**: zbiorcze ogłoszenia co `announce-minutes` (15) — „Księstwo X zdobywa 3 ziemie kraju Y”, bunty,
  odpadnięcia wasali, koalicje, trybuty (najwyżej 6 linii); upadek kraju osobno. Dziennik AI (kategoria WOJNA, także
  rundy przy `log-rounds: true`), granice na squaremap jak dotąd, **`/nk wojny [kraj]`** (wojny, ofensywy z postępem,
  siłami i stratami, zmęczenie, hegemon, ostatnie wydarzenia), dla panelu `ArmiaPlugin#npcConquestJson()`.
- **Restart**: stan w `plugins/Armia/conquest.json` (`.tmp` → atomowa podmiana, `.bak`); ofensywa trwa dalej z tym samym
  postępem, żołnierze zostają w polu. Przy niezgodności plików żołnierze bez ofensywy wracają do domu.

Merge configu produkcji: `deploy-npc-conquest-20261029/config/` (`ai.conquest`, 7 komunikatów `ai-conquest-*`,
`help-nk.entries.wojny`). Testy: `logic/conquest/BattleMathTest`, `ConquestRulesTest`; próba Paper:
`verify-npc-conquest-20261029/`.

## Administracyjna gwardia królewska

Administrator przebudowy królewskiej może wywołać na głównym wątku publiczną metodę pluginu
`ArmiaPlugin.deployRoyalGarrison(String json) -> int`. To wąskie wejście dla pomocnika projektu, bez komendy graczy
i bez nowej metody współdzielonego API. Manifest ma `{countryId,world?,posts:[{id,type,x,y,z,radius?,yaw?}],siegePosts?:[...]}`;
obsługuje wyłącznie kraj `9d1fe36e-4852-4d18-9b2a-a89146f43e66`, świat jego stolicy, do 40 unikatowych posterunków
gwardii i osobno do 10 pozycji istniejącej artylerii. Wynik metody liczy regularne posterunki (np. 32), dodatkowe
10 machin jest przywracane w tym samym wywołaniu.
Każdy punkt jest sprawdzany przed zmianami: aktualna własność, worldborder, załadowany chunk, podparcie i wolna,
bezpieczna przestrzeń. Pomocnik przebudowy powinien wcześniej asynchronicznie utrzymać potrzebne chunki.

Istniejące przypisanie `royalPost` ma pierwszeństwo, następnie zgodny typ broni, pozostali piesi i kawaleria.
Ponowne wywołanie i restart zachowują identyfikatory żołnierzy; przewóz morski, najemnicy, artyleria i obce kraje
pozostają nietknięte przez regularne `posts`; artyleria jest obsługiwana wyłącznie przez `siegePosts`. Reuse zachowuje dotychczasowy typ — `type` w manifeście jest preferencją i typem ewentualnego
nowego rekruta. Brakujących strażników można utworzyć darmowo wyłącznie w ramach autoryzowanego projektu, nadal
przy limicie globalnym najwyżej 1000 i kraju najwyżej 60 (lub niższym limicie konfiguracji). Typy muszą należeć do
epoki 1/2. Posterunki przyjmują tryb GUARD i czyszczą dowódcę/cele/patrol.

Przed zmianą terenu pomocnik wywołuje `beginRoyalRebuild() -> int`. Metoda przechowuje wszystkie własne lądowe jednostki
jako rekordy z `royalEvacuated=true`, zachowuje ich id, typ, aktualne HP i pozostałe dane, usuwa tylko encje/wygląd.
Nowe jednostki tego kraju podczas hold również pozostają schowane. Kontroler nie materializuje, nie naprawia ani nie
przenosi zawieszonej jednostki; przy ładowaniu chunków usuwa dawne encje z PDC. Nie stosuje to znacznika transportu morskiego.
Ostatnie 16 identyfikatorów wycofanych brainów jest zapisane w rekordzie, aby załadowanie dawnego chunku po finalizacji nie odtworzyło usuniętej encji.
Ponowne begin po częściowej finalizacji lub restarcie znów bezpiecznie przechwytuje wojsko. Nie usuwać kraju — zdarzenie
rozwiązania kraju zgodnie ze zwykłymi zasadami usuwa także jego armię.

Wszystkie wskazane pozycje i pełne pokrycie ewakuowanych jednostek są sprawdzane przed finalizacją. Brak pozycji dla
choć jednego schowanego żołnierza przerywa operację; nic nie jest cicho usuwane ani wypuszczane w zasypany teren.
`resumeRoyalRebuild() -> int` jest bezpieczną ścieżką przerwania: wznawia tylko jednostki, których dawne pozycje są
nadal własne, załadowane, w worldborder i bezpieczne. Pozostałe zachowują hold do świadomego odzyskania.

Begin, finalizacja i resume mają trwałą barierę `soldiers.json`: snapshot jest kolejkowany po wcześniejszych zapisach,
plik tymczasowy ma `force(true)`, następuje atomowa podmiana i na Linuxie fsync katalogu. Metoda wraca dopiero po
potwierdzeniu zapisu (maks. 10 s) albo rzuca wyjątek; writer pozostaje aktywny dla dalszych autosave. Pomocnik nie może
przejść do terenu/zapisać ukończenia etapu ani usunąć platformy ewakuacyjnej po błędzie tej bariery.

## Epoki (plugin Postep)

Postep jest szukany leniwie (refleksja po `ServicesManager`, `EraAdvanceEvent` rejestrowany dynamicznie) — Armia **nie** ma go w
`softdepend` (Postep ma Armię). Bez Postep wszystkie kraje są w epoce 1. Jednostki mają w configu `era`, `discovery`,
`replaces`, `camo`, `heal-per-second`: Renesans — pikinier, muszkieter i arkebuzer (proch; kusza z inną nazwą i wyglądem),
armata (obsługa bombardy w ciężkiej zbroi, siła oblężnicza 4, wolna); Przemysł — piechota liniowa (`karabin_gwintowany`),
kawaleria i artyleria (`stal`); Nowoczesność — żołnierz (mundur w kamuflażu barw kraju, hełm), snajper (zasięg 48),
pojazd opancerzony (`samochod`: załoga w netherycie na koniu w diamentowym pancerzu — bez modów to najmniej absurdalny
„pojazd”) i medyk (`antybiotyki`, leczy swoich w promieniu 8 bl.). Niedostępnej jednostki nie da się zrekrutować (komunikat
z brakującą epoką/odkryciem). `/armia przezbroj [typ] [ilość]` zmienia stare oddziały na najnowszy dostępny odpowiednik
(łańcuch, np. piechota → pikinier → piechota liniowa → żołnierz; koszt `eras.rearm-cost-share` ceny nowej jednostki); AI
przezbraja `eras.ai-rearm-per-tick` żołnierzy na tick i rekrutuje tylko aktualne jednostki. HP i obrażenia: ×(1 + 0,1 na
epokę powyżej pierwszej + `effect(kraj, "sila-armii")`%). Budżet NPC: opcjonalny udział **badań** (`setBudgetShares(npc,
wojsko, budowy, badania, rezerwa)`, `researchShare`) = `PostepApi.setNpcInvestShare` (procent dziennego dochodu inwestowany
przez Postep); brak udziału = auto (-1, decyduje Postep).

## Wioski → państwa NPC

`/nk wioska [nazwa] [kolor] [władca]` (stojąc w wiosce lub obok): Ludnosc (`findVillage`, promień `villages.find-radius` 96)
wykrywa wioskę, Armia pokazuje podgląd (chunki, łóżka, wieśniacy, stanowiska) i czeka na `/nk wioska potwierdz` (klik, 5 min).
Potem: kraj NPC ze stolicą przy dzwonie, zajęte (ADMIN, spójnie od stolicy) wszystkie chunki zabudowy, `adoptVillage` (domy
z łóżkami, pola, wieśniacy, plan miasta), władca przy dzwonie, losowa polska nazwa/kolor/władca/osobowość z honorem, gdy nie
podano. Panel: `admin.npc.fromVillage {x, z, world?, name?, color?, ruler?}`; API: `ArmiaApi.createNpcFromVillage`.

**Automat** (`villages.auto`, domyślnie włączony): jednorazowy skan pregenerowanego świata (`scan-radius` 4000, siatka
`scan-step` 800, jeden punkt co `interval-seconds` 5 s, postęp w logu) i wioski w nowo generowanych chunkach; każda wioska jest
losowana raz (`chance` 0,33), z dala od strefy spawnu (`min-spawn-distance` 600 + obszary chronione Kraje) i od każdego kraju
(`min-country-distance` 400), do `max-countries` (18 wszystkich państw NPC, scenariusz ma 12) i `max-auto-countries` (6 z
wiosek). Stan (skan, losowane wioski, państwa automatu) w `plugins/Armia/villages.json`. Ogłoszenie: „Na wschodzie, w okolicach
(x, z), powstało Księstwo Wiślan pod rządami księcia Bolesława!”. Nazwy i władcy: `villages.names`, `villages.rulers`.
## Uprawnienia

`armia.use` (wszyscy), `armia.admin` (op), `armia.bypass` (nikt domyślnie: żołnierze go nie atakują i nie chronią się przed nim).

## API

`ArmiaApi` w ServicesManager: odczyty `soldierCount`, `soldiersByType`, `soldierCountry`, `isAiActive`, oraz dla panelu WWW
(główny wątek, te same limity i koszty co komendy; uprawnień wywołującego nie sprawdzają): `recruit` (koszt ze skarbca,
pobór obywatela; żołnierze stają na straży przy koszarach z Ludnosc albo w stolicy), `disband`, `unitTypes`, `morale`,
`dailyWages`, `npcSettings`, `setNpcSetting`, `setNpcPaused`, `npcTick`, `npcLog`, `addSoldiers` (admin, bez kosztu),
`placeRuler` (tam, gdzie stał władca, albo w stolicy), `createNpcCountry` (kraj w Kraje + stan AI + władca).
Dla pluginu Wladcy (API 1.2): `rulerCountry(encja)` (kraj władcy NPC po PDC) i `logNpcDecision(kraj, tekst)` (wpis do logu
decyzji AI, np. wojna wypowiedziana w rozmowie).

**API 1.3 — pełne zarządzanie państwem NPC** (panel WWW v2, wszystkie metody `default`, mutacje na głównym wątku, stan w
`npc.json`, każda zmiana w dzienniku AI):

| Metoda | Opis |
|---|---|
| `opinion(npc, inny)`, `setOpinion(npc, inny, -100..100)` | opinia AI o kraju |
| **`adjustOpinion(npc, inny, delta)`** | zmiana opinii o `delta` (wynik przycięty do -100..100; `message()` = nowa wartość). **Dla Wladców**: trybut albo obelga w rozmowie z władcą → `armia.adjustOpinion(npc, krajGracza, +10)` |
| `stance` / `setStance(npc, inny, "auto\|przyjazne\|wrogie\|nigdy_wojny\|cel_ataku")` | nastawienie (`NpcStance`, przyjmuje też nazwy enum) |
| `budgetShares` / `setBudgetShares(npc, wojsko, budowy, rezerwa)` | podział budżetu w %, suma 100 (`NpcBudget.custom=false` = wyliczony z osobowości) |
| `posture` / `setPosture(npc, "obronna\|zrownowazona\|ofensywna")` | postawa (`NpcPosture`) |
| `siegeTarget` / `setSiegeTarget(npc, kraj\|null)`, `autoSiegeTarget(npc)` | cel oblężeń (null = auto) i cel wybrany przez AI |
| `aiGoal(npc)` (tekst), `aiGoalDetails(npc)` (`NpcGoal`: kind, text, target, progress) | aktualny cel AI |
| `economyBreakdown(npc)` | `NpcEconomy`: przepływy z 24 h (dochód AI, podatki NPC — szacunek z Ludnosc × mnożnik, żołd, rekrutacja, budowy, ziemia i wojny z historii skarbca Kraje, trybuty) |
| `demandTribute(npc, cel, md)`, `tributeMax()`, `tribute(npc, inny)` | żądanie dziennego trybutu (`NpcReply.accepted`: NPC decyduje od razu, gracz klika w czacie `/armia trybut przyjmij\|odrzuc`) |
| `npcPropose(npc, cel, ALLIANCE\|NEUTRAL)`, `npcDeclareWar(npc, cel)`, `pendingProposal(npc, inny)` | dyplomacja z panelu (wojna przez `KrajeApi.declareWar`; `nigdy_wojny` ją blokuje) |
| `taxMultiplier` / `setTaxMultiplier(npc, 0..300)` | mnożnik podatku obywateli NPC: Armia dopłaca/pobiera różnicę względem podatku Ludnosc przy każdym ticku AI |
| `outposts(npc)` | posterunki straży (żołnierze w trybie straży wg chunka) |
| `npcLog(npc, kategoria, szukaj, limit)`, `logNpcDecision(kraj, kategoria, tekst)` | dziennik z kategoriami `WAR, DIPLOMACY, BUILD, ARMY, ECONOMY, OTHER` (też po polsku: wojna, dyplomacja, budowy, armia, gospodarka); szukanie bez wielkości liter i polskich znaków |

**AI v2** (czysta logika w `logic/Policy`, `Capitulation`, `BudgetPlan`, `Crisis`, `GoalPlanner`, `Ledger`; strojenie w `ai.v2`):
- Nastawienie: `nigdy_wojny` blokuje każdą wojnę (także z panelu) i przyjmuje pokój po minimalnym czasie wojny; `cel_ataku`
  ×3 waga celu, niższa wymagana przewaga i próg agresji, pierwszeństwo w oblężeniach; `przyjazne` +30 do opinii (rzadsze
  wojny, łatwiejszy sojusz, propozycje sojuszu nawet przy niższej dyplomacji); `wrogie` −30.
- Postawa: obronna nie wypowiada wojen, +25% straży na granicy, oblega tylko utracone ziemie; ofensywna częściej wypowiada
  wojny (×1,5) i oblega 2× częściej. W nocy (poza kryzysem) część straży zostaje w centrum osady.
- Budżet: rezerwa = `rezerwa%` skarbca (nie mniej niż rezerwa na żołd), reszta dzielona wojsko : budowy. W wojnie 60%
  udziału budów idzie na wojsko, w kryzysie 85% (i pół rezerwy); po pokoju (6 h) połowa udziału wojska wraca na budowy.
- Obrona i odbudowa: chunki zdobyte przez wroga są zapamiętywane (kto, kiedy) — w wojnie oblegane najpierw, po pokoju (po
  `reclaim-cooldown-minutes`) są powodem nowej wojny. Agresor traci 40 pkt opinii (+15 za chunk, +10 za oblężenie), ujemna
  opinia wraca 4× wolniej. Tryb kryzysowy (wojna + strata chunka w ostatnich 2 h albo oblężenie): 2× szybszy pobór, +30%
  armii, mury i wieże (`orderBuildingAuto("mur"/"wieza")`), prośba o pomoc — sojusznik NPC może dołączyć do wojny.
  Odbudowa po pokoju: najpierw `dom`, potem `spichlerz`, dalej `developSettlement`.
- Kapitulacje: AI odpowiada na `CapitulationOfferEvent` (dyplomacja i zmęczenie za, agresja i duża przewaga przeciw;
  chciwy władca — dyplomacja < 40, agresja > 60 — odrzuca trybut niższy niż 15 md × siła przegranego). Przegrywające państwo
  NPC (siła < 0,6 wroga i ≥ 2 utracone chunki albo −40% skarbca, albo ≤ 2 chunki) samo oferuje kapitulację; dumny władca
  (agresja ≥ 85) dopiero tuż przed upadkiem. Wasal NPC wykupuje się, gdy po wykupie zostaje 1,5 × rezerwa; suweren zwalnia
  wasala przy opinii ≥ 85 (±10 wg agresji).

**Władcy NPC są nieśmiertelni**: każde obrażenie jest anulowane (także od graczy w trybie kreatywnym); `ruler.admin-kill: true`
pozwala zranić władcę kucającemu graczowi z `armia.admin`. Zniknięty władca wraca na swoje miejsce (co 30 s, po załadowaniu
chunka, w ticku AI). Klikalne komunikaty używają `<cmd>…</cmd>` (komendę wstawia kod); stare configi z
`<click:run_command:'<cmd>'>` są poprawiane przy wczytaniu (ostrzeżenie w logu).
Kliknięcia odbiera niewidzialna encja `Interaction` (0,8 × 2 bloki) stojąca na władcy (Mannequin z `setImmovable` nie zawsze
dostaje PPM); ma te same PDC i cykl życia co władca. **Skiny**: władca bez skina, żołnierze i najemnicy z `skin: losowy`
dostają profil z losowym, stałym UUID bez tekstur (klient pokazuje jeden z domyślnych skinów); `skin` jednostki może być nickiem
Mojang albo listą nicków do losowania (`[Nick1, Nick2]`, wybór stały dla żołnierza).

## Najemnicy

`mercenaries.*`: typy najemników (`types`, np. Najemnik piechur, Kusznik genueński, Rycerz-najemnik) to kopie jednostki
bazowej z własną nazwą, kompanią, skinem i szarą/czarną zbroją bez barw kraju. Koszt ×2,5, żołd ×2 (`cost-multiplier`,
`wage-multiplier`), bez poboru obywateli i bez koszar, kontrakt `contract-hours` (72 h realnych) — potem odchodzą, chyba że
kontrakt przedłużysz (`/armia przedluz`, `extend-cost-share` = 50% kosztu). Wliczają się w limit wojska, a najwyżej
`max-share` (30%) limitu to najemnicy. Bez żołdu odchodzą od razu (bez spadku morale); z `defect-to-enemy: true` przy skarbcu
poniżej `defect-treasury-below` przechodzą do najbogatszego wroga. Nigdy nie wracają do ludności. AI w trybie kryzysowym,
przy ludności ≤ `ai.max-population` i skarbcu ≥ `ai.min-treasury`, najmuje do `ai.per-tick` najemników na tick (dziennik: armia).
API: `ArmiaApi.mercenaries(kraj)` (`NpcMercenary`: typ, nazwa, liczba, koniec kontraktu, żołd); panel: pole `mercenaries` w
`admin.npc.army`.

## Scenariusze startowe

Pliki `plugins/Armia/scenarios/<id>.yml` (przy starcie rozpakowuje się `europa1350.yml`, edytowany plik nie jest nadpisywany).
**Nazwy, kolory, władców, pozycje, osobowości, skarbiec, wojsko, osadników, jedzenie i relacje edytuje się w pliku.**
`/nk scenariusz pokaz <id>` pokazuje państwa i cele, `zaladuj <id> potwierdz` tworzy brakujące (istniejące nazwy są pomijane):
co 2 s jedno państwo — najbliższa wioska w `promien-wioski` od x/z (stolica przy dzwonie), inaczej suche i płaskie miejsce przy
x/z; zawsze z dala od obszarów chronionych Kraje (`protected-areas.json`, np. spawn) o `odleglosc-od-spawnu` i od innych stolic o
`odleglosc-miedzy-panstwami` (chunki wczytywane asynchronicznie, postęp na czacie). Potem: osobowość, skarbiec, wojsko
(`addSoldiers`), osadnicy i jedzenie (Ludnosc), sojusze, opinie i nastawienia z `relacje`; bez wojen na start (cooldown wojny
liczy się od utworzenia). Nazwy państw jak w Kraje: litery, cyfry, `_` i `-`, bez spacji, 3–24 znaki — stąd np.
`Królestwo_Polskie`. Gdy cel wypada na wodzie, ląd jest szukany po spirali (`scenario.search-step` 48 bl., do
`scenario.max-search-radius` 800 bl., najpierw bliżej celu i środka mapy; chunki asynchronicznie, `step-timeout-seconds` na krok;
postęp na czacie, odskok wymuszony odstępami w logu). Ponowne `zaladuj` tworzy tylko brakujące państwa i uzupełnia `honor`
istniejącym, jeśli admin go nie ustawił. Plik ma pole `wersja`; starszy plik z serwera (bez wersji i bez `honor`) jest przy
starcie zastępowany wersją z jara (kopia `.yml.old`). API dla Menu: `ArmiaApi.npcCapitalSpot(kraj)` — bezpieczne miejsce obok władcy.

Honor władcy (API 1.3): `NpcSettings.honor` 0–100, ustawiany w scenariuszu (pole `honor`) albo `/nk ustaw <kraj> honor
<0–100|auto>`; bez ustawienia liczony z osobowości (`NpcSettings.deriveHonor`). Używa go plugin Wladcy do opisu postaci.

Okno dyplomacji władcy ma przycisk **„Porozmawiaj”** (slot 18), widoczny tylko gdy włączony jest plugin Wladcy; klik
wykonuje `/wladca rozmowa` jako gracz (bez twardej zależności Armii od Wladcy).

## Modele statków i machin oblężniczych (BetterModel, 2026-10-29)

Statki floty i machiny oblężnicze mają animowane modele BetterModel. **Model jest tylko wyglądem.**
Fizyczny pokład z wave 16 pozostaje bez zmian i nadal odpowiada za ruch: nośna łódź, 12–16 niewidzialnych skalowanych
shulkerów na nośnikach Interaction, ster `/armia flota steruj`, przenoszenie chodzących i zejścia.
Model jest wysyłany pakietami (DummyTracker), więc nie tworzy encji serwera, kolizji ani zapisu na dysku.
Bez BetterModel (softdepend) albo przy `modele.yml: wlaczone: false` zostaje dotychczasowy wygląd z bloków.
Gdy BetterModel przeładuje się bez modeli Armii, statki przełączają się na bloki, a po ich powrocie wracają do modelu.

- **Koga** (`transportowiec`, epoka 1): klinkierowy kadłub, kasztele dziobowy i rufowy nad głowami (≥1,94 bloku),
  rejowy żagiel z krzyżem w barwie kraju, tarcze na blankach, latarnia rufowa, rumpel ze sterem zawiasowym, kotwica.
- **Karawela** (epoka 2): wyższy kasztel rufowy nad sternikiem, żagiel rejowy i łaciński bezan, bukszpryt,
  działa w furtach poniżej pokładu (nie stoją na pokładzie).
- Animacje: `fala` (kołysanie, ruch pokładu modelu ≤0,11 bloku), `zagle_rozwin` po ruszeniu, `zagle_zwin` po 5 s postoju,
  `lopot` w czasie żeglugi, `flagi` (bandera i proporce zawsze), `kotwica_opusc` po 10 s postoju i `kotwica_podnies` przy wypłynięciu.
  Ster (płetwa i rumpel) obraca się według klawiszy kapitana albo skrętu autopilota AI.
- Flaga, proporce, krzyż na żaglu i tarcze są barwione kolorem kraju z Kraje (odświeżane co 10 s).
  Chorągiew kraju z wgranym obrazkiem: gdy Kraje ma most `KrajePlugin#flagShow` (chorągwie krajów), Armia podnosi ją
  na drzewcu rufowym (`armia:statek:<id>`, skala 0,6, płótno w stronę rufy) i chowa własną banderę; bez mostu zostaje
  bandera barwiona. Dodatkowe punkty podpięcia: usługa `pl.medivalrpg.armia.visual.FlagArt` w ServicesManager albo
  `modele.yml: flagi.model-przedmiotu` (np. `medivalrpg:flagi/{kraj}`).

**Chodzenie po pokładzie.** Widoczny pokład leży dokładnie na górze shulkerów (`DeckRules.deckHeight`) i obejmuje
dokładnie obszar `DeckRules.walkableHalfLength` × szerokość pokładu, także podczas obrotu i żeglugi
(model i kolizja dostają w tym samym ticku tę samą pozycję i obrót). Na pokładzie nie ma widocznych ścian bez kolizji:
nadburcie ma 7/16 bloku (przechodzi się nad nim — zejście za burtę działa jak dotąd), luk, kratownica i zwoje lin
są niższe niż krok (0,6), kasztele są nad głową, a maszty, wanty i rumpel są cienkie. Na izolowanym Paperze idący
po pokładzie byt (0,6×1,95, fizyka serwera) stoi na pokładzie, przechodzi go wzdłuż i wszerz, schodzi za burtę i zostaje
na pokładzie w czasie rejsu.

**Machiny**: `armia_trebusz`, `armia_katapulta`, `armia_taran`, `armia_armata`. Jednostka `katapulta` ma na stałe
(według UUID żołnierza) wygląd katapulty albo trebusza, `armata`/`artyleria` — armaty. Mapowanie: `modele.yml: machiny.modele`.
Taran ma model i animacje (`uderzenie`, `ladowanie`, `jazda`) — jednostka `taran`, opis w sekcji „Taran”.
Animacja strzału startuje w tym samym ticku, w którym `SiegeEngineService.fire` wypuszcza pocisk; wyrzut/uderzenie jest
w pierwszych 0–0,12 s klipu, a `ladowanie` jest rozciągane tak, by skończyć się, gdy logika pozwala na kolejny strzał.
Armata: odrzut lufy i lawety, błysk (`glow_`) i kłęby dymu; koła toczą się zgodnie z prędkością marszu.
Rozstawiona machina (stoi ≥2 s) dostaje małą niewidzialną kolizję (0,9–1,5 bloku) przed obsługą. Kolizja nie powstaje,
gdy ktoś stoi w jej obrysie, i znika od razu przy ruchu/obrocie machiny albo gdy gracz znajdzie się w środku —
machinę można obejść i stanąć obok, nikt nie zostaje uwięziony. Wyłączenie: `machiny.kolizja: false`.

**Wydajność.** Modele statków: do 24 jednocześnie, wysyłane graczom do 96 bloków (chowane od 112).
Machiny: do 96 modeli, tylko gdy gracz jest do 64 bloków (`machiny.tylko-przy-graczach`), chowane od 80.
BetterModel nie liczy animacji modelu, którego nikt nie ogląda. Kolizje machin są nietrwałe i mają tag PDC
`armia:part=siege_model`, więc osierocone są usuwane przy starcie i wczytaniu chunka (jak dotychczasowe części).

**Instalacja.** Przy starcie Armia kopiuje `armia_*.bbmodel` do `plugins/BetterModel/models` (swoje starsze wersje
aktualizuje według `plugins/Armia/modele-zainstalowane.json`, plików zmienionych ręcznie nie rusza). Jeśli BetterModel
ich nie wczytał, Armia jednorazowo prosi go o przeładowanie (`przeladuj-bettermodel`). Paczka zasobów rośnie
o 160 830 B (~157 KB; 2 304 125 → 2 464 955 B na izolowanym Paperze, `verify-models-20261029/latest-run.json`).

Źródło modeli: `tools/generate_models.py` (deterministyczne, `--check`), podglądy `tools/preview_models.py`
i `tools/render_blender.py` (Blender w tle). Testy: `visual/*Test` (animacje, synchronizacja z kolizją, zawartość modeli);
izolowany Paper: `verify-models-20261029/` (`run.py`, 4 fazy, port 25607).

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
