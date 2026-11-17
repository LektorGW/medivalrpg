# Spawn MEDIVALRPG — schemat WorldEdit

Średniowieczne miasteczko-spawn dla serwera MEDIVALRPG (Paper 26.3, WorldEdit 7.4.6, gracze bez modów i bez resource packa).
Schemat jest generowany kodem w Pythonie (`generate.py` + pakiet `generator/`), więc wynik jest powtarzalny co do bajtu i każdy stan bloku jest sprawdzany względem rejestru MC 26.3.

**Pliki do wgrania** (kopiujesz do `plugins/WorldEdit/schematics/` na serwerze):

| Plik | Do czego |
|---|---|
| `out/spawn.schem` | wariant główny 69 × 32 × 69: fundament i powietrze, które **czyści teren i drzewa** w całym obszarze |
| `out/spawn_noair.schem` | ten sam spawn bez górnych warstw powietrza (69 × 31 × 69), do wklejania przez `//paste -a`, które **nie nadpisuje** istniejącego terenu powietrzem |

Format: Sponge Schematic v3, `DataVersion` 5023 (MC 26.3, odczytane z `version.json` w `paper-26.3-218.jar`).

## Jak wkleić

1. Stań **na środku planowanego spawnu, na poziomie gruntu**: stoisz na bloku ziemi, a ten blok stanie się środkiem róży wiatrów (lodestone).
2. `//schem load spawn.schem`
3. `//paste` (wariant noair: `//schem load spawn_noair.schem`, potem `//paste -a`)
4. Coś nie tak? `//undo` cofa całe wklejenie.

> WorldEdit 7.4.6 w teście na konsoli przyjął tylko nazwę z rozszerzeniem (`spawn.schem`). Samo `spawn` dało błąd „Invalid value for <>”. Jeśli u gracza `//schem load spawn` nie zadziała, dopisz `.schem`.

**Punkt wklejenia i offset.** Offset schematu to `[-34, -5, -34]`. Pozycja twoich stóp (blok, w którym stoisz) to środek placu, tuż nad lodestone róży wiatrów.

| Co | Względem twoich stóp (X, Y, Z) |
|---|---|
| bruk placu / róża wiatrów | Y − 1 (blok, na którym stałeś) |
| fundament (4 warstwy kamienia i ziemi) | od Y − 5 do Y − 2 |
| czyszczenie powietrzem (`spawn.schem`) | do Y + 26 włącznie (27 bloków nad brukiem) |
| zasięg w poziomie | X − 34 … X + 34, Z − 34 … Z + 34 |

Wskazówki:
- Teren niższy niż 4 bloki pod brukiem zostawi „wiszący” fundament przy krawędziach, a teren wyższy niż Y + 26 zostanie. Najlepiej wybrać w miarę płaskie miejsce albo wyrównać brzegi ręcznie.
- `spawn_noair.schem` z `-a` nie usuwa niczego. Jeśli teren jest wyższy od bruku, zostanie wewnątrz budynków, dlatego ten wariant nadaje się na płaski albo wcześniej wyczyszczony teren.
- Pierwsze wklejenie lepiej zrobić z `//paste -s`, żeby zobaczyć zaznaczenie, a potem `//undo` albo zostawić.

## Ustawienie spawnu świata

Stań dokładnie na lodestone w środku róży wiatrów i wpisz:

```
/setworldspawn ~ ~ ~ 0 0
/gamerule respawn_radius 0
```

`0 0` ustawia patrzenie na południe, czyli na tablicę ogłoszeń. Fontanna z rycerzem i brama główna są za plecami. Bez `respawn_radius 0` gra rozrzuca nowych graczy w promieniu 10 bloków, więc mogliby pojawić się np. w fontannie.

## NPC: bankier i kupiec

Miejsca NPC oznaczają **blok złota pod dywanem**:

| NPC | Marker | Pozycja dywanu względem punktu wklejenia | Patrzy na |
|---|---|---|---|
| Bankier | czerwony dywan za ladą banku, za oknem bez krat | (X − 14, Y + 1, Z − 22) | wschód, na ladę i wejście |
| Kupiec | zielony (lime) dywan w środkowym straganie północnego rzędu targu, przy szyldzie „KUPIEC” | (X + 22, Y, Z − 5) | południe, na drogę |

1. Stań na dywanie i ustaw kierunek patrzenia, np. `/tp @s ~ ~ ~ -90 0` (bankier, wschód) albo `/tp @s ~ ~ ~ 0 0` (kupiec, południe).
2. `/skarbiec npc stworz bankier "Bankier"` albo `/skarbiec npc stworz kupiec "Kupiec"` (opcjonalnie skin jako ostatni argument). NPC powstaje w twojej pozycji.
3. Markery można potem usunąć: zamień blok złota na podłogę, a dywan może zostać.
   - bank: `/setblock X-14 Y Z-22 minecraft:polished_deepslate`
   - targ: `/setblock X+22 Y-1 Z-5 minecraft:spruce_planks`

   (X, Y, Z to pozycja, z której wklejałeś; współrzędne są też w `out/markers.json`.)

## Co jest w spawnie

Kierunki świata: północ = −Z. Odległości podane od punktu wklejenia (dx, dz).

- **Plac centralny** (ośmiokąt 29 × 29): bruk z mieszanki kamiennych cegieł, polerowanego i zwykłego andezytu, bruku, kamienia, mchu i żwiru. Przy środku jest gładszy, ku krawędziom bardziej zużyty. Ma obramowanie z kamiennych cegieł z rzeźbionymi akcentami, a wyloty dróg z polerowanego andezytu.
- **Róża wiatrów** (dx 0, dz 0): lodestone w środku, białe ramiona z polerowanego diorytu, ramię północne czerwone, ciemny pierścień. To punkt spawnu.
- **Fontanna z pomnikiem rycerza** (dz −9): ośmiokątna niecka z wodą, obrzeże z półpłyt (można na nim siadać). Rycerz ma żelazny tors, przyłbicę z żelaznej klapy, czerwoną pelerynę i tarczę z herbem (banery), a przed sobą wbity miecz z prętów End i łańcuchów. Wokół stoją 4 latarenki.
- **Tablica ogłoszeń** (dz +8, frontem na północ): zadaszenie z ciemnego dębu, 2 latarnie, 7 tabliczek ze świecącym tekstem (woskowane, nie da się ich edytować):
  „Witaj w MEDIVALRPG!”, „/menu — wszystko w jednym miejscu”, „/kraj pomoc — załóż państwo”, „/mapa — mapa świata”, „/glos — czat głosowy”, „Kupiec skupuje surowce, Bankier strzeże monet”, „/nowosci — co nowego”.
- **4 drogi** do bram, zużyte na brzegach (ścieżki, żwir, gruboziarnista ziemia), z latarniami na ramionach z łańcuchowo zawieszonymi lampionami.
- **Mur obronny**: niski, dwuwarstwowy, z blankami, chodnikiem na murze i skośnym cokołem. Schody na mur stoją przy bramie północnej i południowej. **4 wieżyczki narożne** mają strzelnice, drabinę na taras, flagę z herbem i wejścia z chodnika na murze.
- **Brama główna** (północ, dz −33…−29): dwie wieże z hełmami z ciemnego dębu, łuk, krata z żelaznych prętów na łańcuchach (podniesiona do połowy) i sztandary z herbem po obu stronach. **Bramy boczne** (wschód, zachód, południe) mają łuk, dwie wieżyczki, banery i latarnie.
- **Bank** (NW, wejście od alei głównej, dz −22): kamienny, z portykiem na 4 kolumnach z diorytu, schodami, frontonem, niskim dachem z łupków deepslate i szyldem „BANK / Skarbiec”. W środku jest szachownica na podłodze, lada z okienkami kasowymi z krat, skarbiec za kratą z żelaznymi drzwiami, skrzynie, beczki, regały, ławki dla klientów, czerwony chodnik, banery i żyrandole.
- **Kaplica z dzwonnicą** (NE): nawa ze skarpami, witraże czerwono-złote, ławki ze schodów (siedzi się przodem do ołtarza), ołtarz ze świecami, pulpit i banery z krzyżem. Wieża ma drabinę na dzwonnicę, dzwon, iglicę z łupków i krzyż. Obok jest mały **cmentarz**.
- **Targ** (wschód, przy drodze do bramy wschodniej): 6 straganów z pasiastymi daszkami z wełny (czerwono-białe, czerwono-złote, niebieskie, zielone, pomarańczowe, żółte), banerowymi lambrekinami, beczkami, skrzyniami, kompostownikami i towarem (dynie, melony, wełna, dzbany, księgi). W środkowym północnym jest stoisko **kupca** z szyldem „KUPIEC skup surowców”.
- **Karczma „Pod Złotym Dzikiem”** (SW, wejście od drogi południowej, dz +20): kamienny parter, nadwieszone piętro z belek z okorowanego ciemnego dębu i jasnego tynku (calcite), wsporniki z odwróconych schodów, stromy świerkowy dach i komin z dymiącym ogniskiem. W środku są 4 stoły z ławami ze schodów (siadanie przez `/usiadz` albo prawym klikiem), bar z beczkami i półkami, kominek, statyw alchemiczny i latarnie. Na górę prowadzi drabina, a tam są 3 łóżka i skrzynie. Na zewnątrz kwietniki pod oknami, szyld i banery.
- **Kuźnia** (SE): otwarty warsztat na słupach z okorowanego drewna. Ma kowadła, piec hutniczy i piece pod kamiennym okapem, kocioł z lawą, kocioł z wodą, stół kowalski, kamieniarkę, szlifierkę, dymiący komin, zaplecze ze skrzyniami i stos drewna.
- **Stajnia** (SW, przy drodze zachodniej): 3 boksy z furtkami. W każdym jest żłób (kompostownik), poidło (kocioł z wodą) i siano.
- **Studnia** (dx −24, dz −8) z daszkiem, łańcuchem i latarnią. Jest też **ogród warzywny** z kanałem wody i strachem na wróble, **plac ćwiczeń** dla armii (kukły ze słomy, tarcze strzelnicze), ogródek przy banku z kwietnikami i ławką.
- Do tego 17 drzew (dąb, ciemny dąb, świerk, azalia; liście `persistent=true`, nie opadają), ławki na placu, kwietniki, flagi, latarnie, łańcuchy, półpłyty, ściany, płotki i odwrócone schody pod okapami. Na trawie rosną kwiaty i paprocie.

Motyw kolorów: ciemny dąb i świerk, kamień, akcenty czerwieni i złota. Herb serwera to czerwony baner ze złotym rombem i bordiurą.

## Podgląd

`preview/top.png` (widok z góry) oraz `preview/iso_se.png`, `iso_ne.png`, `iso_nw.png`, `iso_sw.png` (izometria, patrzący stoi w danym narożniku). Kolory pochodzą z prostej mapy, więc w grze tekstury wyglądają lepiej.

## Generowanie i testy

```
cd spawn
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python generate.py                          # out/*.schem, out/markers.json, preview/*.png
.venv\Scripts\python -m unittest discover -s tests -v     # testy schematu
.venv\Scripts\python tests\server_test.py <mcharness.py> [katalog-serwera]   # prawdziwy WorldEdit na serwerze TESTOWYM
```

- `data/blocks-26.3.json` to raport danych serwera (`java -DbundlerMainClass=net.minecraft.data.Main -jar paper-26.3-218.jar --reports`). Każdy użyty stan bloku przechodzi przez `Registry.state()`. Nieznany blok, właściwość albo wartość kończy generowanie wyjątkiem `InvalidBlockState`. Paleta zawiera wyłącznie pełne stany (wszystkie właściwości).
- `data/banner_patterns-26.3.json`: lista wzorów banerów z vanilla datapacka 26.3. `data/version-26.3.json`: `version.json` z jara (DataVersion = `world_version`). Odświeżenie danych: `.venv\Scripts\python -m generator.refresh_data <paper.jar>`.
- Kształty schodów (narożniki dachów) i połączenia płotów, murków, krat i szyb są przeliczane jak w grze. Liście dostają poprawne `distance` i `persistent=true`.
- Tabliczki zapisane są w formacie 26.3: `front_text.messages` to lista komponentów tekstu jako NBT (np. `{text:"/menu",color:"gold",bold:1b}`), a nie stare napisy JSON. Format sprawdzony przez `setblock` i `data get` na serwerze 26.3.
- `tests/test_schem.py` wczytuje `.schem` niezależnym parserem NBT i sprawdza nagłówek, DataVersion, wymiary, offset, paletę (każdy stan zgodny z rejestrem), czyste powietrze nad budowlami, fundament bez dziur, markery NPC, block entities (pozycje, typy, teksty tabliczek, szerokość linii, wzory banerów) oraz to, że generator odrzuca złe stany.
- `tests/test_support.py` sprawdza, że nic nie odpadnie, nie wycieknie ani nie zmieni się po pierwszej aktualizacji bloku (latarnie, banery, tabliczki, drabiny, kwiaty, uprawy, drzwi, łóżka, woda, żwir, ścieżki i trawa pod blokami, kompostowniki).
- `tests/server_test.py` kopiuje schematy do `plugins/WorldEdit/schematics/` serwera testowego, wkleja je przez `//world`, `//schem load`, `//pos1`, `//placement pos1`, `//paste` i `//paste -a`, sprawdza 191 bloków komendą `execute if block` z pełnym stanem, czyta NBT tabliczek i banerów przez `data get block`, a na koniec cofa oba wklejenia przez `//undo`.

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
