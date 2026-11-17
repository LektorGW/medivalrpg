# MEDIVALRPG — zewnętrzny synchronizator komend Discord

Worker Cloudflare (cron co 5 minut, SQLite Durable Object `CommandsSynchronizer`), który utrzymuje kanał Discord
z listą komend dla graczy zgodnie z opublikowanym katalogiem panelu. Nie wymaga restartu serwera.


## Zakres

- Trwałe ID własnych wiadomości przechowuje `commands-v1` w DO. Nie usuwać singletona, namespace ani historii podczas wdrożeń.
- **Zakres (polecenie użytkownika 2026-10-28):** tylko komendy, które gracze mają w pomocy w grze. Bez admin/op,
  `/nk`, komend Papera, WorldEdit, WorldGuard i wewnętrznych. Jedno źródło danych:
  `menu/src/main/resources/pomoc.yml` (ten sam plik czyta `/pomoc` w Menu).
- Układ: 1. wiadomość — wstęp i spis treści, potem **jedna kategoria = jedna wiadomość** (🧭 Start, 🏰 Kraj,
  🛡️ Dyplomacja i wojna, 💰 Ekonomia, 👥 Ludność i budowa, ⚔️ Armia i flota, 🔭 Postęp i epoki, 🚂 Kolej,
  🐎 Handel, szlaki i zlecenia, 🏆 Turnieje, 🎭 Postać i towarzystwo, 📜 Nowości), na końcu krótka stopka.
  Czysty Markdown Discorda (bez embedów). Podgląd: `verification/preview.md`.
- Opublikowano 201 komend; każdą sprawdzono z rejestrem komend aktywnych jarów
  (`verify-commands-20261028/catalog-full-raw.json`; 188 dokładnych ścieżek, 13 pod ogólnym argumentem Kolei/Ludnosci,
  `/register` i `/login` z deklaracji nLogin). Wpisy z `permission` (np. maszynista) i `discord: false` nie trafiają na kanał.

Worker aktualizuje **opublikowany katalog**, nie odczytuje rejestru Bukkit. Cron edytuje zmienione własne
wiadomości, usuwa własne wiadomości stron, które zniknęły z katalogu, i nie publikuje całego spisu od nowa.

## Odświeżanie po zmianach pomocy

1. Zmienić `menu/src/main/resources/pomoc.yml` (opisy, kategorie, nowe komendy). Na kanał trafiają tylko komendy
   **aktywne na produkcji**: funkcje przygotowane do przerwy dodawać z `discord: false` albo dopiero po aktywacji.
   Jedna kategoria musi mieścić się w jednej wiadomości (≤1900 znaków); generator przerywa z błędem, gdy nie.
2. Wygenerować katalog, podgląd i sprawdzić komendy z rejestrem aktywnych jarów:

   ```powershell
   python -B tools/build_player_help_catalog.py --preview commands-discord/verification/preview.md --registry verify-commands-20261028/catalog-full-raw.json
   python -B -m unittest tools/test_build_player_help_catalog.py
   ```

   Wynik jest deterministyczny (bez znaczników czasu): niezmieniony `pomoc.yml` nie edytuje żadnej wiadomości.
3. Opublikować **wyłącznie** `public/commands/catalog.json`, bez cudzych, niedokończonych zmian w `panel/web/src`.
   Wzorzec 2026-10-29: skopiować `panel/web` do własnego katalogu tymczasowego (z junction do `node_modules`),
   wziąć dokładnie wdrożony `dist/` (zgodność z aktywnymi zasobami sprawdzona po SHA-256), podmienić w nim tylko
   `dist/public/commands/catalog.json`, przeliczyć jego wpis w `dist/manifest.json` i porównać manifest
   (różnica musi dotyczyć tylko katalogu), `npm run check:dist`, potem
   `node node_modules/wrangler/bin/wrangler.js deploy --config dist/wrangler.jsonc`. Wrangler powinien zgłosić
   „Uploaded 1 of 1 asset”. Pełny `npm run build` z bieżących źródeł robić tylko, gdy `src` jest zgodne z produkcją.
4. Z katalogu głównego `python tools/sync_discord_commands.py --rounds 5` (prywatny token z pliku sesji; sprawdza
   zgodność opublikowanego katalogu z lokalnym plikiem). Powtarzać po zakończeniu rundy aż `pending=0`, `held=0`,
   `current`; ostatnie wywołanie ma dać `writes=0`.

Pełny rejestr runtime (`tools/compact_discord_commands.py`, eksporter w `../verify-commands-20261028/`) służy już
tylko do walidacji; nie publikować go na kanale graczy.

## Kontrakt z eksporterem katalogu

Koordynator publikuje atomowo JSON przez statyczny zasób HTTPS panelu.
Eksporter odpowiada za kompletność i jawne ograniczenia
komend; Worker publikuje każdą stronę, bez filtrowania uprawnień czy modułów.

```json
{
  "version": 1,
  "pages": [
    { "key": "kraje-001", "content": "**Kraje**\n/kraj — pomoc i zarządzanie krajem" }
  ],
  "metadata": { "generatedAt": "2026-10-28T12:00:00Z" }
}
```

- `version` to liczba `1`; `pages` to maksymalnie 250 stron.
- `key`: unikalny, stabilny identyfikator, 1–128 jednostek UTF-16, bez znaków
  sterujących ani białych znaków na początku/końcu. Klucze mogą zawierać Unicode.
- `content`: niepusty tekst, maksymalnie 1900 jednostek UTF-16.
- Wszystkie pozostałe pola, w tym metadane, adresy URL, identyfikatory wiadomości,
  webhooki i ustawienia powiadomień, są ignorowane. Worker nie pobiera URL-i z JSON.
- Pusta lista jest poprawna i wycofuje istniejące strony: publikować ją świadomie.
  Niekompletny eksport powinien kończyć się błędem HTTP, a nie uciętą listą stron.
- Pobranie katalogu: HTTPS, publiczna nazwa DNS, bez danych logowania, niestandardowego
  portu i przekierowań; najwyżej 1 MiB. Operator odpowiada za publiczny DNS źródła.
  HTTP 304, przekierowanie, błąd sieci i niepoprawny JSON pozostawiają wiadomości bez zmian.

## Konfiguracja dla koordynatora

Wymagane ustawienia środowiska Workera:

| Nazwa | Wartość |
| --- | --- |
| `CATALOG_URL` | Publiczny HTTPS URL JSON; var albo secret. Docelowo `https://panel.example.workers.dev/commands/catalog.json` (statyczny asset panelu) |
| `DISCORD_WEBHOOK_URL` | Secret: dedykowany webhook kanału z komendami |
| `SYNC_TOKEN` | Secret: losowy token o długości 32–256 znaków |

Akceptowany webhook to wyłącznie dokładny adres
`https://discord.com/api/webhooks/<17–20 cyfr>/<token>` albo wariant `/api/v10/webhooks/`.
Token URL ma 30–200 znaków ASCII z `A-Z a-z 0-9 _ -`. Niedozwolone są query,
fragment, końcowy slash, inne hosty, porty i dodatkowe segmenty ścieżki.
Potrzebny jest zwykły kanał tekstowy; wątki/forum wymagające dodatkowych parametrów
nie są obsługiwane. Worker nigdy nie listuje kanału ani nie przyjmuje ID od klienta.

W `commands-discord/`, po uzgodnieniu produkcyjnego wdrożenia, koordynator może
wprowadzić sekrety interaktywnie (nie przekazywać ich w linii poleceń):

```powershell
node ../panel/web/node_modules/wrangler/bin/wrangler.js secret put CATALOG_URL --config wrangler.jsonc
node ../panel/web/node_modules/wrangler/bin/wrangler.js secret put DISCORD_WEBHOOK_URL --config wrangler.jsonc
node ../panel/web/node_modules/wrangler/bin/wrangler.js secret put SYNC_TOKEN --config wrangler.jsonc
```

Webhook utworzono i skonfigurowano na polecenie użytkownika. Sekrety trzech wskazanych
nazw są w Cloudflare; ich wartości nie należą do repozytorium. Cron jest aktywny w `wrangler.jsonc`.

## API i pierwsza publikacja

- `GET /health`: wyłącznie odczyt statusu DO. Brak zapisu, pobierania katalogu i
  ruchu do Discorda. Zwraca liczniki, kursor, czas ostatniej rundy, cooldown i stały
  kod błędu; nie zwraca sekretów, URL-i, treści, kluczy stron ani ID wiadomości.
- `POST /sync` z nagłówkiem `Authorization: Bearer <SYNC_TOKEN>`: jedna runda
  synchronizacji przez ten sam prywatny DO co cron. Body i parametry URL są ignorowane.
- Brak albo błędny token: HTTP 401; zła metoda: 405; inna ścieżka: 404.
- Nakładająca się runda: 202 `busy`, bez kolejkowania kolejnych zadań.
- HTTP 200 może zawierać `status: pending`, `deferred` lub `held`; należy czytać JSON.
  `pending` liczy pracę możliwą do automatycznego wykonania; `held` liczy wpisy
  wymagające rozstrzygnięcia przez operatora.

Po wdrożeniu koordynator wywołuje `/sync` ręcznie kolejnymi rundami aż `pending=0`,
`held=0`, `status=current`. Każda runda wykonuje najwyżej 10 zapisów do Discorda
i ma budżet około 20 s (pojedyncze żądanie do 5 s). Przy 250 stronach potrzeba
co najmniej 25 rund; limity Discorda mogą wymagać więcej. `deferred` oznacza,
że należy odczekać do `cooldownUntil` (Unix ms), a `busy` — do końca poprzedniej rundy.
Kursor jest trwały, więc następne rundy kontynuują pracę. Później cron co 5 minut
pobiera aktualny katalog i aktualizuje tylko zmienione strony. Nie ma innych
wiadomości ani powiadomień poza dedykowanym webhookiem.

## Własność i zachowanie po błędzie

POST używa `wait=true`. Przed wysłaniem zapisuje i jawnie utrwala zamiar w DO.
Z odpowiedzi sprawdza własny `webhook_id` i tekstowy snowflake wiadomości, następnie
zapisuje jej ID i SHA-256 treści. PATCH zawsze korzysta wyłącznie z tych trwałych ID.
Każdy payload ma `allowed_mentions: { "parse": [] }`; tekst `@everyone` i `@here`
jest dodatkowo neutralizowany pełnoszerokim `＠`, bez zwiększania długości treści.

Utrata odpowiedzi POST, HTTP 5xx/408, przekierowanie, błędna odpowiedź lub awaria
zapisu ID po udanym POST oznacza **HELD**. Pozostały po awarii zamiar `posting`
również staje się HELD. Nigdy nie następuje ponowny POST w ciemno. Brakujące własne
wiadomości (PATCH 404) i niezgodne ID też są wstrzymywane; nie są zastępowane nowymi.
Potwierdzone odrzucenie POST (400/401/403/404/405/413/415/422) może być ponowione
w późniejszej rundzie. Jawne 429 odkłada pracę zgodnie z `retry_after`,
`Retry-After` i `X-RateLimit-Reset-After` (najdłuższy czas); bez poprawnego czasu
stosowany jest odstęp 60 s. Awaria PATCH pozwala bezpiecznie ponowić zmianę tego
samego ID. Nie ma pętli sleep ani natychmiastowych ponowień HTTP.

Strony usunięte z katalogu są kasowane `DELETE` wyłącznie po trwałym ID własnej wiadomości tego webhooka
(Discord odrzuca kasowanie cudzych wiadomości). Odpowiedź 2xx albo „Unknown Message” (kod 10008) zdejmuje ID ze stanu;
inne błędy, 5xx i brak odpowiedzi są ponawiane po 60 s (DELETE jest idempotentny), 429 według `retry_after`.
Ponowne pojawienie się klucza tworzy nową wiadomość. Nowe wiadomości powstają ściśle w kolejności katalogu;
wpis HELD blokuje tworzenie kolejnych, żeby nie zaburzyć kolejności kanału. Nowa strona wstawiona w środek
katalogu pojawi się jednak na dole kanału — przy zmianach zachowywać istniejące klucze (edycja w miejscu).
Historia najwyżej 500 różnych kluczy pozostaje trwała.
Przekroczenie tego limitu blokuje całą rundę kodem `state_capacity` przed zapisem
do Discorda; wymaga świadomej migracji. SHA-256 całego kanonicznego URL webhooka
wiąże stan z jednym webhookiem. Zmiana ID lub tokenu zatrzymuje synchronizację
kodem `webhook_binding_changed`, także po rotacji tokenu tego samego webhooka.

W przypadku HELD operator ręcznie sprawdza kanał Discord oraz trwały zamiar.
Odzyskanie może przypisać wyłącznie zweryfikowane ID wiadomości tego webhooka
do właściwego wpisu stanu, z hashem faktycznie wysłanej treści. Ponowny POST
wolno odblokować dopiero po potwierdzeniu, że poprzedni nie utworzył wiadomości.
Nie ma publicznego endpointu resetu, importowania ID ani usuwania stanu.
Nie kasować namespace DO, historii ani nie zmieniać nazwy singletona `commands-v1`
w ramach zwykłego wdrożenia: utrata historii może spowodować ponowną publikację.
Stan przechowuje tylko klucze, ID, hashe i status, bez treści i poświadczeń.

## Weryfikacja lokalna — bez sieci i bez deploy

Z katalogu głównego projektu:

```powershell
node --test commands-discord/tests/worker.test.mjs
```

Testy używają wyłącznie mocków `fetch` i storage ze structured clone, w tym
symulacji awarii zapisu/flush i restartów DO. Domyślny fetch testów odmawia sieci.
Obejmują (36 testów, 2026-10-29) granice 250/251 stron, stabilne edycje, DELETE wycofanych stron
(także 404 Unknown Message, ponowienia 5xx), kolejność tworzenia, blokadę przez HELD, powrót strony, walidację,
powiadomienia, limity, awarie POST/PATCH, 429, kursor, auth, cron i współbieżność.

Z `commands-discord/`, lokalna kompilacja Wrangler bez wdrożenia:

```powershell
$env:WRANGLER_SEND_METRICS = 'false'
node ../panel/web/node_modules/wrangler/bin/wrangler.js deploy --dry-run --config wrangler.jsonc --outdir .wrangler/dry-run
```

Podstawa zachowania: [Discord Webhook Resource](https://docs.discord.com/developers/resources/webhook)
oraz [Cloudflare Durable Objects Storage](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/).

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
