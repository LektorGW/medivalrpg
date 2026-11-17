# Panel — strona zarządzania państwami (MEDIVALRPG)

Cloudflare Worker `panel-mc` + Durable Object `PanelHub` + SPA po polsku (czysty JS, bez frameworków i CDN).
Protokół i metody RPC: `../../PANEL.md`. Plugin Paper: `../plugin/`.

## Struktura
- `src/index.js` — Worker: `/plugin` (WebSocket pluginu, `Authorization: Bearer PANEL_SECRET`, porównanie w czasie stałym), `/api/*` i `/login` → DO, reszta to pliki z `public/`.
- `src/hub.js` — DO `PanelHub` (Hibernation API): sesje (w storage tylko SHA-256 tokenu), link z gry (jednorazowy, 10 min), limity logowania (per IP i per nick, trwałe), limit RPC per sesja, przekazywanie `auth`/`rpc` do pluginu (timeout 10 s, brak pluginu → 503), cache ostatnich odczytów do trybu offline, `revoke`.
- `public/` — SPA (hash routing): `js/app.js` (powłoka, router, odświeżanie co 30 s), `js/views/*` (ekrany), `js/money.js` (kwoty w formacie Skarbca), `css/style.css`, `_headers` (CSP bez `unsafe-inline`, `Referrer-Policy: no-referrer`).
  - Państwo NPC (v2, 7 zakładek): `js/views/npc.js` (lista, tworzenie, strona z zakładkami, osobowość i dziennik AI), `js/views/npc-panels.js` (Przegląd, Stosunki, Gospodarka, Ludność i jedzenie), `js/views/npc-build.js` (Budowy, Armia), `js/views/npc-common.js` (słowniki kodów, przełącznik segmentowy, korekty ±), `js/chart.js` (lekkie wykresy SVG). Kontrakt metod: `PANEL.md` → „Kształty odpowiedzi v2”.
  - Edytor planu państwa NPC (zakładka „Plan”): `js/plan/model.js` (walidacja jak w pluginie, geometria, chunki, historia cofnij/ponów — bez DOM), `js/plan/editor.js` (płótno i narzędzia), `js/views/npc-plan.js` (pasek narzędzi, panel boczny, RPC), `css/plan.css`. Nastrój (Zycie): `js/views/mood.js`. Kontrakt: `PANEL.md` → „Kształty odpowiedzi edytora”.
- `test/` — testy jednostkowe kwot, integracyjne (`wrangler dev` + fałszywy plugin) i E2E (headless Chrome, zrzuty w `test/screenshots/`). Dane v2 fałszywego pluginu: `test/fake-npc-v2.mjs`; zrzuty v2 mają prefiks `60-`…`79-`. Postęp (epoki, drzewko odkryć): widok `js/views/postep.js`, dane testowe `test/fake-postep.mjs`, zrzuty `80-`…`88-`. Edytor planu i teren: dane `test/fake-plan.mjs` (podkład PNG generowany proceduralnie), kroki `test/e2e-plan.mjs`, zrzuty `90-`…`98-`; nastrój i wymuszenia: `test/e2e-extra.mjs`, zrzuty `15-`, `73-`, `74-`. Testy modelu edytora: `test/plan.test.mjs`.

## Testy
```
npm install
npm run build        # dist/public + dist/worker.js + konfiguracja i manifest SHA-256
npm run check:dist   # dry-run gotowej paczki, bez wdrażania
npm run check        # wrangler deploy --dry-run
npm run test:unit    # parser i formatter kwot, model edytora planu
npm run test:int     # wrangler dev + fałszywy plugin (ws)
npm run test:e2e     # headless Chrome (puppeteer-core, Chrome z systemu; CHROME_PATH)
```
Lokalnie sekret jest w `.dev.vars` (`PANEL_SECRET=...`, plik nie trafia do repozytorium).

Gotowa paczka jest w `dist/`: `public/` zawiera SPA (zminifikowany bundle `js/app.js`), `worker.js` zawiera Worker i Durable Object, `wrangler.jsonc` zachowuje bindingi, migracje i trasowanie z konfiguracji źródłowej (`no_bundle: true`), a `manifest.json` podaje rozmiary i SHA-256. Build używa przypiętego `esbuild@0.28.2`, czyści wyłącznie zweryfikowany katalog `panel/web/dist`, nie kopiuje `.dev.vars` i niczego nie wdraża.

Testy gotowej paczki w PowerShell: ustaw `$env:PANEL_TEST_DIST='1'`, następnie uruchom `npm run test:int` i `npm run test:e2e`. Harness wskazuje konfigurację z `dist/` i przekazuje źródłowy plik sekretów przez `--env-file`, bez kopiowania go do paczki. `$env:E2E_HEALTH_ONLY='1'` ogranicza E2E do wydajności; usuń tę zmienną dla pełnego zestawu. Zrzuty paczki trafiają do `test/screenshots-dist/`. Podczas E2E nie edytuj obserwowanych plików i nie przebudowuj paczki — restart lokalnego Workera rozłącza atrapę pluginu.

Wynik weryfikacji i dokładna diagnoza esbuild: [BUILD-VERIFICATION.md](BUILD-VERIFICATION.md).

## Plan państwa i wyrównywanie terenu

Własny władca i administrator otwierają `#/kraj/<id>/plan` i zapisują plan do realizacji przez budowniczych z zasobów kraju. Doradca może czytać plan oraz zlecać wyrównanie, ale nie zapisuje ani nie usuwa planu. Uprawnienia z `country.get.access.planRead/planWrite/planLevel/planTerritory` są rozstrzygane ponownie w pluginie; `js/plan/access.js` steruje tylko interfejsem. Edytor państw graczy korzysta z `country.plan.get/buildings/mapRender/set/clear/preview`. Widok NPC zachowuje aliasy `admin.npc.*`, dzięki czemu dotychczasowy edytor działa również przed aktywacją nowych pluginów. Brak nowego RPC wyświetla komunikat wymaganej aktualizacji, a zapis z błędem odczytu jest zablokowany.

Realizacja zapisanego planu jest odczytywana przy otwarciu i po zapisie. Karta pokazuje zlecone, oczekujące i zablokowane budowy, konkretną przyczynę każdej blokady oraz termin kolejnej próby. „Sprawdź realizację” odświeża stan na żądanie i otwiera pełny podgląd. Powody pochodzą wyłącznie z pozycji danego planu (`orders[].reason/retryAt`); globalny błąd innego planisty nie jest przypisywany do planu.

„Wyrównaj teren” to osobne narzędzie: przeciągnij prostokąt do 4096 kolumn na własnej ziemi, wybierz automatyczny poziom albo wysokość Y, oblicz podgląd i zatwierdź koszt w oknie potwierdzenia. Podgląd `country.plan.levelPreview` wraca z krótkotrwałym tokenem; zlecenie `country.plan.level` przekazuje wyłącznie kraj i token. Zmiana zaznaczenia lub parametrów unieważnia podgląd w interfejsie. Serwer ponownie sprawdza rolę, teren i koszt. „Darmowo” widzi tylko administrator. Narzędzie „Teren” do zmiany granic pozostaje wyłącznie przy zarządzaniu NPC.

Testy `test/plan-access.test.mjs` sprawdzają role i geometrię, `test/e2e-player-plan.mjs` sprawdza pełny przepływ prostokąta, podglądu i potwierdzenia w lokalnym Chrome z fałszywym pluginem.

## Statystyki Skarbu Świata

Strona `#/skarbswiata` ma zakładki: Zarząd (przydziały z rezerwy), Bilans (`/bilans`), Konta (`/konta`), Przepływy (`/przeplywy`), Gospodarstwa (`/gospodarstwa`), Monety (`/monety`), Ruchy monet (`/ruchy`), Kupiec (`/kupiec`) oraz historię konta `#/skarbswiata/konto/<id>`. Widzi je wyłącznie osobisty zarządca WorldSteward (`me.roles.worldManager`); każda metoda `world.stats.*` jest dodatkowo sprawdzana w Skarbcu, a odmowa (także dla administratorów) daje kartę „Brak dostępu” bez żadnych danych. Metody `world.*` nie są cache'owane ani w Durable Object, ani w `sessionStorage`.

- Zarząd: przydziały z rezerwy do budżetów i przewoźnika oraz karta „Przelew do skarbca państwa NPC” (`world.npcTreasuries` — lista państw NPC z saldami i rezerwą; `world.npcTransfer {country, amount, confirm, reason, direction}` — kierunek „Do kraju” albo „Zwrot do rezerwy”, wymagany powód 3–120 znaków, modal z kwotą i saldami przed/po; `confirm` to ta sama kwota w miedziakach, którą serwer porównuje z `amount`). Przelew trafia do księgi w kategorii „Skarb Świata — przydziały” z powodem „WorldSteward: …”.
- Zarząd → „Zasilenia wdrożeniowe” (`world.grants`, `world.grantsApply {key, retry?}`): jednorazowe przydziały z rezerwy (pula obywateli NPC, budżet kupców) ze stanem Nowe/Wykonane/Niepewne, obecnym saldem konta i dotychczasowymi przydziałami z rezerwy. „Wykonaj” otwiera modal z kwotą i rezerwą przed/po; stan niepewny wymaga „Ponów świadomie” z przepisaniem klucza (`retry: "true"`).
- Gospodarstwa (`world.stats.households {days}`): saldo puli `world:households`, prognoza („wystarczy na N dni — do daty”, „pula samowystarczalna” albo „za mało danych”), średnie z 7 dni, wykres dzienny wpływów/wypływów z linią netto, saldo, karta „Robocizna z budów”, kategorie, kontrahenci, ostatnie operacje, okno 7/30/90 dni i CSV.
- Bilans: suma kont kontra pula, baner kontroli zgodności (ikona i słowo „rozbieżność”/„ostrzeżenie”, nie tylko kolor), pasek udziałów typów kont i stan spisu monet.
- Konta: wszystkie konta z filtrem typu, wyszukiwarką, sortowaniem, ruchem z 7 dni i eksportem CSV; wiersz prowadzi do historii konta (saldo w czasie, kategorie, kontrahenci, ostatnie transakcje, okno 7/30/90 dni).
- Przepływy: filtry w hashu (`?zakres=30d&kat=wages&typ=…&konto=…&q=…&min=…&str=…&ile=…`, własny zakres `od`/`do` jako daty), wykres dzienny według kategorii, wykres świat ↔ gospodarka, diagram Sankey i macierz przepływów między typami kont, kategorie, top konta, pluginy oraz stronicowana lista transakcji.
- Monety: spis monet fizycznych (`world.stats.census`, porcjami w tle, odpytywanie co 3 s), porównanie z kontem pokrycia, rodzaje miejsc, nominały i tabela miejsc z filtrem. Ruchy monet: log ruchów stosów z filtrami. Kupiec: budżet, skup, towary z poziomem zapasu i trendem oraz historia.
- Eksport CSV: Blob i `<a download>` (zgodne z CSP), UTF-8 z BOM, separator `;` (polski Excel otwiera plik dwuklikiem, bo przecinek jest separatorem dziesiętnym), komórki tekstowe zaczynające się od `= + - @`, tabulatora lub CR dostają prefiks `'`. Przepływy i ruchy monet pobierają wszystkie pasujące wiersze stronami po 1000 (najwyżej 50 000) z paskiem postępu.

Pliki: `js/views/world.js` (zakładki i odmowa), `js/views/world-npc-transfer.js` (przelew do państwa NPC), `js/views/world-grants.js` (zasilenia wdrożeniowe), `js/views/world-stats.js`, `js/views/world-stats-coins.js`, `js/views/world-stats-common.js`, `js/world-stats-charts.js` (SVG), `js/world-stats-model.js` (czyste funkcje: CSV, Sankey, serie dzienne, filtry, prognoza gospodarstw; słowniki zapasowe — 13 typów kont z `citizens` i kategorie `labor`, `citizens`), `css/world-stats.css`. Testy: `test/world-stats.test.mjs`, `test/world-stats-rpc.test.mjs`, atrapa `test/fake-world-stats.mjs`, E2E `test/e2e-world-stats.mjs` (zrzuty `114-`…`132-`; samo `E2E_WORLD_ONLY=1` uruchamia zarząd i statystyki).

## Chorągwie krajów i moderacja

- `#/kraj/<id>/flaga` (`js/views/flag.js`): duży pikselowy podgląd obecnej chorągwi (domyślna z koloru kraju / w grze / zatwierdzona i czekająca na przebudowę paczki zasobów z terminem), karta „Oczekuje na zatwierdzenie” (podgląd po przetworzeniu, kto i kiedy, „Wycofaj”), ostatnie odrzucenie z powodem, wgrywanie PNG/JPEG i historia z „Przywróć”. „Przywróć domyślną” i „Przywróć” wymagają potwierdzenia. Bez `access.upload`/`access.moderate` widać tylko obecną flagę (Kraje pomija wtedy `pending`, `history`, `lastRejection`).
- Wgrywanie: kontrola typu i rozmiaru wg `limits`, sprawdzenie sygnatury pliku, a gdy plik przekracza `maxBytes`, ma bardzo dużo pikseli albo nie zmieści się w 240 000 znaków base64 — pomniejszenie w `canvas` do ≤ 256×160 px i wysyłka jako PNG. Podgląd pokazuje wysyłany obrazek i przybliżenie 64×40 (wycinek ze środka, jak na serwerze).
- `#/flagi` (`js/views/flag-moderation.js`): kolejka (obecna i proponowana obok siebie, kraj w kolorze, kto/kiedy, oryginał), „Zatwierdź”, „Odrzuć” (powód 3–200 znaków sprawdzany też w UI), ostatnie decyzje, stan paczki i „Przebuduj paczkę teraz”. Bez roli `me.roles.flagModerator` albo przy odmowie RPC — karta „Brak dostępu”.
- Menu: „Chorągiew kraju” dla Doradcy/Władcy, „Moderacja chorągwi” dla moderatora. Metody `country.flag.*`/`admin.flag.*` idą przez `api.rpc` (bez kopii w `sessionStorage`) i nie są w cache offline Durable Object; `country.flag.upload` ma limit ciała 256 KB w Workerze.
- Czyste funkcje: `js/flag-model.js`; style: `css/flag.css`. Testy: `test/flag.test.mjs` (model i atrapa), atrapa Kraje `test/fake-flag.mjs`, E2E `test/e2e-flag.mjs` (zrzuty `150-`…`159-`; samo `E2E_FLAG_ONLY=1` uruchamia tylko ten zestaw i czyści tylko zrzuty `15x-`).

## Gospodarka kraju

`#/gospodarka/<id>` (pliki: `js/views/economy.js`, `js/economy-model.js`, `css/economy.css`): kafelki, braki i ostrzeżenia
z podpowiedziami, co kraj produkuje i wydajność dzienna (słupki towarów w kolorach rodzajów z palety Statystyk Skarbu Świata),
miejsca pracy i ich stan, towar w skrzyniach, magazyny z zapełnieniem (pełne oznaczone słowem i ikoną), spichlerz i wzrost
ludności, place budowy (czego brakuje, skąd, jak długo czekają, kiedy kupią na targu), tragarze, obywatele, wykres doby
i „Dane techniczne” (nowe pola Ludnosci). `#/gospodarka` — własny kraj albo lista (admin, WorldSteward, zarządca NPC). Link w menu
i przycisk „Gospodarka” w nagłówku widoku kraju. Odmowa → karta „Brak dostępu” bez danych. `country.economy` nie trafia do
`sessionStorage` ani do cache DO. Odświeżanie co 30 s jak cały panel, „Odśwież” wysyła `fresh: true`.
Testy: `test/economy.test.mjs` (model), `test/e2e-economy.mjs` z atrapą `test/fake-economy.mjs` (zrzuty `140-`…`147-`,
samo `E2E_ECONOMY_ONLY=1`), `test/paper-economy.mjs` — przeglądarka na prawdziwym Paperze (uruchamia
`verify-panel-economy-20261029/run.py`). Podczas E2E inne osoby edytujące `panel/web` restartują `wrangler dev` — uruchamiaj
z kopii katalogu.

## Firmy, przelew zbiorczy i próba wspólnego buildu

`#/firmy` (`js/views/firmy.js`, `js/firmy-model.js`), karta kredytów firm w `#/skarbswiata` (`js/views/world-firm-loans.js`) i przelew do wszystkich państw NPC (`js/views/world-npc-transfer.js`). Testy: `test/firmy.test.mjs` i `test/world-npc-bulk.test.mjs` (w `test:unit`), E2E `test/e2e-firmy.mjs` z atrapą `test/fake-firmy.mjs` (zrzuty `160-`…`169-`, samo `E2E_FIRMY_ONLY=1`) i `test/e2e-world-npc-bulk.mjs` (zrzut `133-`). Próba na prawdziwym Paperze z logowaniem hasłem przez Strażnika: `test/paper-release.mjs`, uruchamiana przez `../../verify-panel-release-20261029/run.py <Panel.jar> AB` (faza A — jary przygotowane, faza B — jary aktywne).

## Wdrożenie
```
npx wrangler secret put PANEL_SECRET   # długi losowy sekret, ten sam co plugin-secret w plugins/Panel/config.yml
npx wrangler deploy
```
W `plugins/Panel/config.yml` ustaw `worker-url` na adres Workera i `plugin-secret` na ten sam sekret.

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
