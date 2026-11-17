# Infrastruktura kolei — zestaw przygotowany

16 samodzielnych modeli Blockbench 5.0 `free`, wyłącznie teksturowane kostki,
osadzone PNG RGBA 128×128 oraz identyczne pliki w `textures/`.
Zasoby własne, generowane deterministycznie; bez pobieranych tekstur i aplikacji Blender.
Wspólna paleta: stal, żelazo, drewno, kamień, mosiądz, patynowana miedź.
Tekstury są atlasem materiałowym z jawnymi UV każdej ściany; współdzielenie wysp
materiału jest zamierzone. Brak unikalnego bake/AO i brak współczesnej elektryki.

## Zawartość i skala

Żuraw wodny, wieża ciśnień, pompa ręczna, zbiornik stacjonarny, bunkier z zsypem
węgla, semafor mechaniczny, dźwignia zwrotnicy, kozioł oporowy, tor referencyjny,
rozjazd referencyjny, most obrotnicy, zegar, ławka, tablica, skrzynie z beczką
i narzędziami, latarnia olejowa.

16 jednostek = 1 blok. +Y w górę, tor wzdłuż Z. Początek infrastruktury zwykle
na gruncie, pośrodku obiektu; most obrotnicy ma konstrukcję poniżej Y=0.
Rozstaw szyn 38 jednostek odpowiada osiom kół istniejącego taboru X=±19.
Referencyjna główka szyny ma Y=3; kontakt kół istniejącego taboru ma Y=0,
więc tabor ustawiony przy tym samym początku wymaga przesunięcia o +3/16 bloku.
Nie stosować szerokości vanilla 1 bloku jako prześwitu taboru: pojazdy mają ponad
2,5 bloku szerokości. Żuraw i zbiorniki ustawiać obok skrajni, nie na osi toru.
Dokładne gabaryty po obrotach i liczby kostek: `asset-index.json`.
Podgląd dopasowuje każdy obiekt osobno — nie przedstawia wspólnej skali.

Semafor przedstawia sygnał STOP; kość `arm` (UUID i początek 0,0,0 bez zmian) obraca kod pluginu
(`SemaphoreModels`, płynnie w ~0,8 s wokół zawiasu). Tablica pozostaje celowo bez tekstu.

## Animacje (2026-10-29)

| Model | Kości (początek) | Animacje |
|---|---|---|
| żuraw wodny | `boom` (oś słupa), `valve` (0,16,−5) | `service_pz` / `service_nz` — raz 10 s: wysięgnik 90° nad tender (+Z/−Z modelu), zawór, powrót |
| wieża ciśnień | `valve` (29,14,−3) | `service` — raz 6 s, dwa obroty zaworu w obie strony |
| pompa ręczna | `handle` (0,33,0) | `service` — raz 6 s, cztery ruchy dźwigni |
| zbiornik stacjonarny | `valve` (0,15,29) | `service` — raz 6 s |
| bunkier węglowy | `gate`, `valve` (13,47,23) | `service` — raz 6 s, zasuwa w górę o 9 j., zawór |
| dźwignia zwrotnicy | `lever` (0,9,0) | `throw_reverse` / `throw_normal` — 1 s, zatrzymanie na końcu (lustrzane odbicie dźwigni) |
| zegar stacyjny | `minute_n/s`, `hour_n/s` (0,57,±2,7) | `time` — pętla 12 h, każda tarcza zgodnie z ruchem wskazówek |

Most obrotnicy nie ma animacji: plugin pokazuje model w trakcie obrotu z kątem rzeczywistego mostu
(`TurntableService`). Spoczynkowe kostki, gabaryty i `asset-index.json` bounds są bez zmian.
Zegar nie jest rozmieszczany przez plugin (zasób gotowy do użycia).
Latarnia ma otwartą ramę i płomień, bez szkła, emisji światła ani efektów.
Rozjazd to geometria referencyjna, bez ruchomych iglic ani obietnicy zgodności
z przebiegiem sieci runtime. Obrotnica to sam most, bez dołu i mechanizmu napędu.

## Warianty blokowe toru

`block-pack/assets/medivalrpg/` to fragment zasobów do przyszłego scalenia,
**nie gotowa aktywna paczka**. Nie zawiera `pack.mcmeta`, nadpisań vanilla ani
przydzielonych blockstates. Integrator musi dobrać docelowy format paczki,
nośniki stanów bloków, ich orientację i sposób zachowania mechaniki szyn.
Modele są zwykłymi sześcianami w lokalnym X/Z 0…16, bez obrotów elementów;
nie wymagają żadnych encji na metr. Wysokość pochylenia sięga Y=18,
co mieści się w standardowym zakresie elementów -16…32.

`asset-index.json` zawiera listę kafli, przesunięcia w blokach i identyfikatory:

- Prosta: pas szerokości 3 bloków i długości 1; środki szyn X=5 i 43,
  oś X=24. Ciągłość uzyskuje się powtarzaniem wzdłuż +Z.
- Pochylenie: ten sam pas, wzrost 16 jednostek na 16 długości w +Z.
  Geometria schodkowa, nie gładka powierzchnia i nie kolizja.
- Łuk: ćwiartka koła na siatce 5×5 (zapisywane tylko niepuste kafle),
  środek (0,0), promień osi 48 jednostek = 3 bloki. Końce osi: (48,0),
  (0,48); promienie szyn 29 i 67. Łuk jest rasteryzowany w kostkach 1/16 bloku.
  Tak ciasny łuk jest demonstracją formatu blokowego, **nie zatwierdzonym
  promieniem dla długiego taboru**. Docelowa trasa wymaga szerszego łuku,
  skrajni i dopasowania do runtime. Osobnego blokowego rozjazdu jeszcze nie ma.

Nie ustawiać tych modeli jako dodatkowej warstwy przez encje. W przyszłości
potrzebne jest mapowanie kafli na stany bloków oraz mechanika szyn po stronie
pluginu/świata; fragment zasobów sam tego nie implementuje. Nie usuwać istniejących
szyn podczas eksperymentów integracyjnych bez osobnego projektu.

## Odtworzenie i odbiór

Z katalogu repozytorium:

```powershell
python tools/generate_rail_infrastructure.py --preview
python tools/generate_rail_infrastructure.py --check
python tools/test_rail_infrastructure.py
```

Generator zapisuje wyłącznie ten katalog. Nie zmienia istniejących modeli
taboru, pluginu, manifestów wydania ani instalacji. Opcjonalny preview wymaga
Pillow; generowanie i walidacja używają standardowej biblioteki Pythona.
`preview.png` to deterministyczny poglądowy render ortograficzny: kolory próbkowane
z atlasu, proste cieniowanie i sortowanie ścian, bez odwzorowania całej tekstury,
AO, przezroczystości, oświetlenia gry czy pełnego bufora głębi.
Nie zastępuje importu Blockbench, testu BetterModel ani oględzin z klienta gry.

Woda, węgiel, sygnały i wyposażenie są tutaj **wyłącznie wizualne**.
Nie ma obsługi zapasów, naliczania kosztów, kolizji ani integracji interakcji;
animacje uruchamia kod pluginu (`pl.medivalrpg.kolej.visual`). Nie wdrożono na serwerze produkcyjnym. Próba wczytania wszystkich 16 modeli przez
BetterModel na izolowanym Paper zakończyła się powodzeniem (24/24 sprawdzenia,
`verify-economy-20261028/latest-infrastructure-run.json`). Nie wykonano jeszcze
oględzin modeli z klienta gry ani testów interakcji.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
