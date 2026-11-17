# Modele kolei — przekazanie do integracji

Zasoby są przygotowane lokalnie. Nie wdrażano, nie uruchamiano ani nie restartowano serwera.
Zmiany ograniczone do `kolej/src/main/resources/models/` i `kolej/tools/`.

## Zestaw i jednostki

Blockbench 5.0, `model_format: free`, wyłącznie kostki z sześcioma teksturowanymi ścianami.
Każdy plik jest samowystarczalny: osadzony PNG 128×128 (RGBA) oraz identyczny plik w `textures/`.
Atlas obejmuje nitowane metale, drewno ze słojami i podziałem desek, węgiel, tapicerkę, mosiądz i malowaną blachę.
Brak zewnętrznych licencji/assetów, wszystkie zasoby generowane lokalnie.

| Runtime / nazwa pliku bez `.bbmodel` | Wyposażenie | Siedzenia |
|---|---|---:|
| kolej_parowoz | kocioł, obręcze, komin, kopuła, kabina, palenisko, latarnia, pomosty i poręcze | 2 |
| kolej_tender | skrzynia węgla, właz wodny, zawieszenie | 0 |
| kolej_pasazerski | otwarte okna, wejścia, pełne wnętrze, przejście, osiem foteli | 8 |
| kolej_salonowy | bordowe pudło, tapicerka, stół, dywan, lampy | 4 |
| kolej_kryty | drewniana skrzynia, okucia, drzwi przesuwne, uchwyty | 0 |
| kolej_weglarka | otwarta skrzynia, bryły węgla, pionowe okucia | 0 |
| kolej_platforma | deski pokładu, kłonice | 0 |
| kolej_cysterna | niebieski zbiornik wodny, obręcze, drabina, właz, zawór | 0 |
| kolej_hamulcowy | wnętrze, fotele, nadbudówka obserwacyjna, koło hamulca | 4 |

16 jednostek modelu = 1 blok = 1 m; skala instancji 1.0. Wszystkie pudła/pokłady mają 112 jednostek = 7 m.
Przód +Z, wysokość +Y, oś podłużna X=0. Początek (0,0,0) to środek podłużny pojazdu na poziomie styku koła z szyną.
Szerokość pokładu 38 jednostek = 2.375 m; koła sięgają około X=±20.6. Wagon nie mieści się na jednotorowym prześwicie szerokości 1 bloku.
Y=0 oznacza górę szyny, nie podstawę bloku pod szyną — offset encji względem toru ustala integrator.

Rozstaw środków kolejnych pojazdów: 128 jednostek = 8 m, czyli 1 m przerwy między pudłami.
Parowóz + tender: 7 + 1 + 7 = 15 m, bez zewnętrznych wystających zderzaków/sprzęgów.
Tender za parowozem ma środek (0,0,-128), oba skierowane +Z; cały zespół ma środek podłużny Z=-64 względem parowozu.
Sprzęgi i zderzaki wystają poza obrys pudła i wypełniają przerwę. Nie dodawać kolejnego odstępu 1 m do rozstawu 8 m.
Model tendera jest osobny — identyfikator `kolej_parowoz` nie zawiera go w geometrii.

## Hierarchia, koła i animacja

`body` ma pivot (0,0,0). Kości `wheel_<numer_osi>_<l|r>` są rodzeństwem `body` u korzenia.
Kości kół zawierają obręcz, oponę, szprychy i piastę; nieruchoma oś oraz resory należą do `body`.
W każdej parze X=-19/+19. Y i promień wynoszą 9 jednostek dla parowozu, 7 dla pozostałych.

| Pojazdy | Z osi (jednostki modelu) | Promień w m |
|---|---|---:|
| parowóz, osie 0–3 | -34, -10, 14, 36 | 0.5625 |
| wszystkie pozostałe, osie 0–1 | -35, 35 | 0.4375 |

Wszystkie modele zawierają `run`: pętla długości 1 sekundy, liniowe klatki obrotu X=0/90/180/270/360° przy t=0/.25/.5/.75/1.
Obrót wyłącznie wokół X przez środek osi; oba boki mają ten sam znak. Dla ruchu +Z dodatni obrót X daje toczenie.
Nie korzystać z interpolacji najkrótszą drogą 0→360: ćwierćobroty są celowe.
Uruchamiać `run` tylko podczas ruchu, zatrzymywać przy postoju; przy cofaniu `run_reverse`.
Jedna pętla odpowiada drodze 2πr: 3.534292 m parowóz, 2.748894 m wagony.
Mnożnik prędkości animacji = |prędkość m/s| / (2πr). Przy sterowaniu fazą: obrót X = droga/(2πr) × 360°.
Osobne modele wymagają różnych mnożników ze względu na różne średnice kół.
Cofanie: każdy pojazd ma `run_reverse` (klatki 0/−90/−180/−270/−360°, ten sam mnożnik co `run`).

### Animacje dodatkowe (2026-10-29, generator)

Wszystkie poza `run`/`run_reverse` mają `override: true`: kości bez klatek nie są resetowane, więc `run`
na kołach trwa pod nakładką. Animowane kości są kośćmi korzenia (rodzeństwo `body`).

| Model | Animacja | Typ | Kości | Opis |
|---|---|---|---|---|
| parowóz | `idle` | pętla 2 s | body | drżenie Y ±0,3 j., kołysanie Z ±0,2° |
| parowóz | `start` | raz 1,6 s | koła + body | poślizg kół 540°, szarpnięcie pudła |
| parowóz | `brake` | pętla 0,4 s | body | pochylenie przodu ~0,5–0,7°, drgania |
| parowóz | `whistle` | raz 1,5 s | `whistle_valve` | dźwignia zaworu gwizdka −30° |
| pasażerski | `doors` | raz 5 s | `door_front_l/r`, `door_rear_l/r` | skrzydła przesuwne ±7 j. w kieszenie ścian czołowych |
| kryty | `doors` | raz 5 s | `door_l`, `door_r` | drzwi przesuwne +17,5 j. wzdłuż szyny |

Nowa geometria (dopisana na końcu listy kostek, istniejące kostki i UUID bez zmian): gwizdek parowozu
(rura, dzwon, dźwignia; najwyżej Y=53 < komin 60) i cztery skrzydła drzwi pasażerskiego 1 j. za otworem.
Drzwi krytego i ich uchwyty przeniesiono do kości `door_l/door_r` bez zmiany kolejności/UUID kostek.
BetterModel 3.6.0-SNAPSHOT-535 parsuje kanały `sound`/`particle`, ale ich nie odtwarza — para, dym, iskry
i gwizd są cząsteczkami/dźwiękami vanilla w `pl.medivalrpg.kolej.visual.RailEffects`.
Animacje nie trafiają do paczki zasobów (wysyła je serwer); każda nowa kość to jeden model przedmiotu w paczce.

## Siedzenia i wnętrza

Pełna maszynowa lista współrzędnych i pivotów: `model_manifest.json` (pozycje w jednostkach modelu).
Kości `seat_00` itd. są pustymi punktami odniesienia wewnątrz `body`; nie zakładać automatycznego tworzenia pasażerów przez BetterModel.
Integrator powinien użyć manifestu, ponieważ importer może pomijać puste kości.
Pozycja to środek górnej powierzchni poduszki: Y=23 (1.4375 m nad szyną). Nie jest to pozycja stóp stojącego gracza.
Offset encji siedzenia i pasażera wymaga dopasowania przez integratora do sposobu montowania gracza; geometria nie zapewnia kolizji.
Yaw w manifeście: 0 = +Z, 180 = -Z, potem dodać obrót całego pojazdu.

| Pojazd | X (jednostki) | Z (jednostki) | Kolejność nazw |
|---|---|---|---|
| parowóz | -9, +9 | -39 | seat_00, seat_01; +Z |
| pasażerski | -11, +11 | -28, -9.333333, +9.333333, +28 | po dwa fotele na każdy Z; ujemne Z patrzą +Z, dodatnie -Z |
| salonowy, hamulcowy | -11, +11 | -25.666667, +21 | po dwa fotele na każdy Z; ujemne Z patrzą +Z, dodatnie -Z |

Podłoga ma górę Y=17, centralny korytarz między fotelami. Okna są otworami bez nieprzezroczystych szyb.
Wejścia na końcach kabin mają prześwit X=-7…7, stopnie i nadproże; poszycie nie wypełnia wnętrza.
Salonowy ma stół w przejściu: to umeblowany salon, a nie ciąg komunikacyjny na wylot.

## Odtworzenie i walidacja

Z katalogu repozytorium, bez zależności Pythona poza standardową biblioteką:

```powershell
python kolej/tools/generate_models.py
python kolej/tools/validate_models.py
python kolej/tools/preview_models.py
powershell -NoProfile -ExecutionPolicy Bypass -File kolej/tools/check_bettermodel.ps1
```

Generator ma deterministyczne UUID i tekstury. Ponowne uruchomienie nadpisuje wyłącznie wygenerowane modele, ich tekstury oraz manifest.
Walidator sprawdza nazwy, metadane, dodatnie wymiary, skończone współrzędne, UV, tekstury, CRC i piksele PNG,
jednoznaczność UUID i hierarchii, klatki `run`/`run_reverse`, zestaw i poprawność animacji dodatkowych (kości korzenia, kroki obrotu ≤90°, powrót do spoczynku, `override`), zgodność pivotów obracanych elementów z osiami, siedzenia i długości.
Testy negatywne uszkadzają w pamięci UV, geometrię, hierarchię, animację, teksturę, pivot, brak animacji, osierocony animator, kierunek cofania i `override` — każde uszkodzenie musi zostać odrzucone.

`models_preview.png` to pomocniczy rzut rzeczywistych kostek z ich obrotami, barwy bazowe atlasu i cieniowanie ścian.
Nie odwzorowuje tekstury per piksel ani renderera Minecraft. Kolejność: parowóz/tender/pasażerski; salonowy/kryty/węglarka; platforma/cysterna/hamulcowy.

## Lokalne źródła zgodności i ograniczenia testu

Zbadano `interakcje/src/main/resources/bettermodel/players/interakcje.bbmodel` (format 5.0, grupy/outliner, animatory i data_points)
oraz `testserver/plugins/BetterModel/models/blue_wizard.bbmodel` (teksturowane kostki), a także lokalne:
`downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar` i odpowiadający mu `-javadoc.jar`.
Javadoc: `kr.toxicity.model.api.data.blueprint` / ModelBlueprint, BlueprintLoadContext;
sygnatury z jar: `ModelData.GSON`, `assertSupported()`, `loadBlueprint(String, boolean)`, `ModelLoadResult.errors()`.
Nie używano internetu ani nowych pobrań.

Skrypt PowerShell używa JDK 25 z cache Gradle i bibliotek z lokalnego testserver.
W osobnej JVM rejestruje minimalną atrapę platformy: adapter udostępnia pusty przedmiot, config dostarcza lerpFrameTime=1.
Pozostałe nieobsłużone wywołania kończą próbę błędem; parser/blueprint/animacje pochodzą z prawdziwego jar BetterModel.
To walidacja parsera poza serwerem, nie próba wygenerowania i pobrania resource packa ani test klienta/graczy/kolizji.
Integrator musi wykonać końcowy test klienta podczas własnego, uzgodnionego wdrożenia.

Wynik lokalny 2026-10-27: 9/9 modeli przechodzi walidator Python; 6/6 celowych uszkodzeń odrzucono.
9/9 przechodzi `ModelData.GSON → assertSupported → loadBlueprint(name,true)`, listy `errors()` puste (`PARSER_FAILURES=0`).
Łącznie 1204 kostki, 40 kości kół, 18 punktów siedzeń. Zbadano także narożniki po obrotach pod kątem zejścia pod szynę.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
