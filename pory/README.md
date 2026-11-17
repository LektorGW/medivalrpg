<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Pory — wizualne pory roku MEDIVALRPG

Plugin pokazuje graczom porę roku z zegara **Ludnosc** (jedyny zegar pór na serwerze, `/ludnosc admin pora ustaw`).
Zmienia wyłącznie to, co widzi klient. Świat na serwerze — biomy zapisane w regionach, bloki, uprawy, moby,
pogoda po stronie serwera — zostaje bez zmian. Gracze nie instalują modów; paczka zasobów się nie zmienia.

## Co widać

| Pora | Wygląd |
|---|---|
| Wiosna | świeża, jasna zieleń trawy i liści; w pierwszych dniach topniejące resztki śniegu (30% → 12% → 0) |
| Lato | zwykły świat (pakiety idą bez zmian, zero kosztu) |
| Jesień | pomarańczowe, rdzawe i złote liście (brzozy złote, ciemny las czerwonobrązowy), pożółkła trawa, ściółka, lekko zamglone niebo, spadające liście pod koronami drzew |
| Zima | szronowa, blada trawa, brunatne „nagie” korony (iglaste zostają ciemnozielone), zimniejsza woda i bledsze niebo, **śnieg zamiast deszczu**, biała warstwa śniegu na terenie, liściach i dachach (45% → 75% → 100% … 70% w ostatnim dniu) |

Pustynie, sawanny i badlands nie mają opadów — zimą tylko lekko szarzeją, bez śniegu. Biomy już śnieżne, jaskinie,
Nether i End są bez zmian.

## Jak to działa

- **Biomy:** datapack `medival-pory-roku` dodaje 118 wariantów (`pory:wiosna/…` 38, `pory:jesien/…` 39, `pory:zima/…` 41),
  generowanych skryptem `tools/generate_datapack.py` z definicji vanilla 26.3. Warianty nie mają struktur, mobów ani
  dekoracji i nigdy nie trafiają do świata. Handler Netty przy każdym połączeniu (przed `packet_handler`) podmienia
  identyfikatory biomów w pakietach chunków i pakietach biomów: tylko paleta biomów, dane bloków kopiowane bajt w bajt
  (także wynik anti-xray). Błąd formatu = gracz dostaje oryginalny pakiet.
- **Zmiana pory:** wysyłane są tylko biomy (`ChunksBiomes`, bez bloków i światła), najbliższe chunki najpierw,
  domyślnie 6 chunków/tick na gracza i 48/tick łącznie.
- **Śnieg:** bloki tylko po stronie klienta (pakiety zmian bloków do jednego gracza). Serwer ich nie ma — nie ma czego
  sprzątać, nic nie koliduje z Kolejem, szablonami budynków, WorldGuardem ani ekonomią; śnieg nie daje śnieżek.
  Kładziony na najwyższym bloku kolumny z pełną górną ścianą (teren, liście, dachy), nigdy na skrzyniach, piecach
  i innych blokach z danymi, wodzie, lodzie, płotach, torach, polach uprawnych; lista `snow.deny-blocks`.
  Gdy blok pod śniegiem zniknie, handler od razu usuwa też śnieg u klienta. Trawa pod śniegiem ma ośnieżone boki.
  Liczenie w budżecie czasu głównego wątku (domyślnie 2 ms/tick).
- **Liście jesienią:** kilka cząsteczek opadających liści pod koronami w pobliżu gracza (tylko dla niego).

## Komendy

- `/pory` — pora, dzień, kiedy następna, jak wygląda świat; `/pory pomoc`.
- `/pory admin podglad <wiosna|lato|jesien|zima|wylacz>` — podgląd pory tylko dla siebie.
- `/pory admin odswiez` — odśwież własny widok; `/pory admin status` — liczniki; `/pory admin reload`.

Uprawnienia: `pory.use` (wszyscy), `pory.admin` (op).

## Budowanie

`tools/gradle-9.8.2/bin/gradle -p pory --no-daemon --offline test jar` — kompiluje się przeciw lokalnemu runtime
Paper 26.3 #218 z `testserver/` (serwer 26.3 jest bez obfuskacji, nie ma remapowania). Datapack:
`python -I pory/tools/generate_datapack.py testserver/versions/26.3/paper-26.3.jar pory/datapack`.

Testy JUnit: przepisywanie palet (losowe sekcje wszystkich typów palet), mapowanie wariantów, pokrycie śniegiem,
śledzenie śniegu, konfiguracja. Test na izolowanym Paperze: `verify-pory-20261031/`.

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
