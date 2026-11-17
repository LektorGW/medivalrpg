# Mapa świata MEDIVALRPG — panel krajów dla squaremap

Dodatek do strony mapy squaremap 1.4.3 (Leaflet 1.9.4):

- **panel po lewej stronie „Kraje świata”**: licznik, wyszukiwarka (nazwa kraju lub władca), sortowanie
  (powierzchnia / obywatele / nazwa), lista krajów z tarczą w kolorze kraju, „(NPC)”, władcą, powierzchnią
  („24 chunki · 6 144 bl²”), członkami, ludnością i żołnierzami (gdy > 0), ikonami ⚔ wojny i 🤝 sojuszu;
- **klik w kraj**: płynny przelot do stolicy (dobór przybliżenia do wielkości kraju, środek widocznej części mapy
  obok panelu), w razie potrzeby przełączenie świata, pulsujący obrys granicy z `markers.json` i rozwinięte
  szczegóły (opis, stolica, sojusze, wojny, data założenia, oblężenia; sojusznicy i wrogowie są klikalni);
- **stałe napisy z nazwami krajów** nad stolicami, w kolorze kraju z obrysem, wielkość zależna od zoomu,
  przy oddaleniu tylko kraje, które coś zajmują na ekranie; napisy nie nachodzą na siebie (pierwszeństwo
  mają większe kraje); przełącznik „Pokaż nazwy na mapie”;
- odświeżanie danych co 30 s bez przeładowania strony (co 60 s, gdy `kraje.json` jeszcze nie istnieje);
- panel zwijany przyciskiem ☰ (nad przyciskami zoomu); domyślnie otwarty na komputerze, a na telefonie
  zwinięty jako wysuwana szuflada; stan zapamiętywany w `localStorage`.

Bez zewnętrznych bibliotek i CDN-ów; całość to `kraje-panel.js` + `kraje-panel.css` i dwa tagi w `index.html`.

## Pliki

| Plik | Opis |
|---|---|
| `web/index.html` | `index.html` squaremap 1.4.3 z dopisanymi dwoma tagami (reszta bez zmian) |
| `web/kraje-panel.js` | logika panelu, napisów i podświetlenia |
| `web/kraje-panel.css` | wygląd |
| `patch-index.py` | dopisuje tagi do dowolnego `index.html` (idempotentnie), Python 3 bez zależności |
| `kraje.example.json` | przykładowe dane w formacie pluginu Kraje (do testów) |
| `test/` | lokalny serwer testowy i test w headless Chrome (`npm test`) |

## Wdrożenie

1. Zatrzymaj serwer albo przygotuj się na restart.
2. W `plugins/squaremap/config.yml` ustaw:
   ```yaml
   settings:
     web-directory:
       auto-update: false
   ```
   Bez tego squaremap przy każdym starcie nadpisuje pliki strony i panel znika.
3. Wgraj do `plugins/squaremap/web/` trzy pliki z katalogu `web/`:
   `index.html` (zastępuje istniejący), `kraje-panel.js`, `kraje-panel.css`.
   Nie ruszaj katalogów `assets/`, `images/`, `tiles/`.
4. Uruchom serwer i otwórz mapę. Jeśli przeglądarka trzyma stary `index.html`, wystarczy Ctrl+F5.
5. Plugin Kraje zapisuje `plugins/squaremap/web/kraje.json` (adres `/kraje.json`). Zanim plik powstanie,
   panel pokazuje „Brak krajów do wyświetlenia”.

### Gdy serwer ma już zmieniony `index.html` albo po aktualizacji squaremap

Nie kopiuj wtedy `web/index.html`, tylko załataj plik z serwera:

```sh
python patch-index.py plugins/squaremap/web/index.html        # robi kopię index.html.bak
python patch-index.py plugins/squaremap/web/index.html --check
```

Po aktualizacji squaremap do nowej wersji:
1. Na chwilę ustaw `auto-update: true`, uruchom serwer, żeby squaremap wgrał nowe pliki strony, i zatrzymaj go.
2. Ustaw z powrotem `auto-update: false`.
3. Uruchom `patch-index.py` na nowym `index.html` i dograj `kraje-panel.js` oraz `kraje-panel.css`.
4. Sprawdź mapę (patrz „Test lokalny”). Dodatek nie zależy od nazw plików w `assets/`.

Odinstalowanie: `python patch-index.py plugins/squaremap/web/index.html --remove` (albo `auto-update: true`
i restart) i usunięcie dwóch plików.

## Jak to jest podpięte do mapy

- squaremap nie wystawia globalnego obiektu mapy (`S` siedzi w module ES). Leaflet z paczki ustawia jednak
  `window.L` (UMD: `window.L = exports`) zaraz przed tym, jak moduł squaremap tworzy mapę (`new SquaremapMap()`,
  czyli `L.map("map", { crs: L.CRS.Simple, … })`).
- `kraje-panel.js` jest dołączony **przed** `<script type="module">` jako `defer`. Skrypty `defer` i moduły
  wykonują się w kolejności dokumentu, więc nasz kod zdąży założyć akcesor (`Object.defineProperty`) na
  `window.L`. Gdy Leaflet się przypisze, rejestrujemy `L.Map.addInitHook(...)`, który przechwytuje instancję
  mapy na końcu jej konstruktora, zanim squaremap doda warstwy. Zapas na wypadek, gdyby skrypt trafił za
  modułem: pierwsze zdarzenie mapy (`fire`) też przechwytuje instancję.
- Przeliczenie współrzędnych jest takie samo jak w `Squaremap.js`:
  `toLatLng(x, z) = L.latLng(-z * scale, x * scale)`, gdzie `scale = 1 / 2^zoom.max` świata (CRS.Simple;
  to samo co `map.unproject([x, z], zoom.max)`). `scale` czytamy z `map.options.scale`, które squaremap
  ustawia w `setScale()`.
- Bieżący świat: z adresu warstwy kafelków (`tiles/<świat>/{z}/{x}_{y}.png`). Zmiana świata: klik w link
  świata w pasku squaremap (`#worlds a#<świat>`), czyli to samo co klik gracza; gdyby linku nie było,
  przeładowanie z `?world=&x=&z=&zoom=`.
- Granice do podświetlenia: `tiles/<świat>/markers.json`, warstwa `kraje_countries`; kraj jest dopasowany po
  nazwie z tooltipu/popupu, a awaryjnie po stolicy leżącej w wielokącie albo po kolorze.

Parametry URL: `?show_kraje=false` wyłącza dodatek; przy `?show_controls=false` (tryb osadzenia squaremap)
zostają same napisy, bez panelu. Diagnostyka: `localStorage["kraje-panel.debug"] = "1"` pokazuje własne
ostrzeżenia w konsoli.

## Test lokalny

```sh
npm install                 # puppeteer-core (używa Chrome/Edge zainstalowanego w systemie)
npm run serve               # http://127.0.0.1:8099/  (--no-kraje: symulacja braku kraje.json)
npm test                    # test w headless Chrome + zrzuty w test/screenshots/
```

Serwer testowy podaje pliki squaremap z `../downloads/squaremap-web/`, nasze pliki z `web/`,
`kraje.example.json` jako `/kraje.json` i `markers.json` z dorysowanymi granicami krajów przykładowych
(plus testowy świat Nether). Kafelki pobiera z prawdziwego serwera i trzyma kopię w `test/cache/`.

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
