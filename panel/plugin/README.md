# Panel — plugin Paper (most strona WWW ↔ gra)

Plugin łączy się z Workerem Cloudflare `panel-mc` (Durable Object `PanelHub`) przez `wss://<worker>/plugin`
(nagłówek `Authorization: Bearer <plugin-secret>`) i obsługuje protokół z `PANEL.md`: logowanie hasłem z nLogin
(`auth` → `auth-result`), link logowania z gry (`/panel` → `link-token`), wywołania RPC (`rpc` → `rpc-result`)
i unieważnianie sesji (`revoke`). **Plugin jest autorytatywny**: przy każdym wywołaniu sam liczy uprawnienia
użytkownika na podstawie stanu Kraje (ranga) i ról panelu.

Wymaga: Paper 26.3, Java 25, **Kraje**, **Skarbiec**. Opcjonalnie: **Armia** (wojsko, państwa NPC), **Ludnosc**
(ludność, budynki), **nLogin** (logowanie hasłem; bez niego działa tylko link z `/panel`).

## Komendy

| Komenda | Kto | Opis |
|---|---|---|
| `/panel` | gracz | Jednorazowy link logowania (32 znaki, ważny 10 min) |
| `/panel wyloguj` | gracz | Unieważnia wszystkie sesje panelu gracza |
| `/panel pomoc` | gracz | Pomoc (klikalne komendy) |
| `/panel reload`, `/panel status` | admin panelu / konsola | Przeładowanie configu, stan połączenia |
| `/panel admin dodaj\|usun <nick>`, `/panel admin lista` | admin panelu / konsola | Admini panelu |
| `/panel zarzadca dodaj\|usun <nick>`, `/panel zarzadca lista` | admin panelu / konsola | Globalni zarządcy państw NPC |
| `/panel zarzadca przypisz <kraj> <nick\|->` | admin panelu / konsola | Zarządca jednego państwa NPC (`-` usuwa) |
| `/panel wydajnosc` | admin panelu / konsola / `panel.health.alerts` | TPS, MSPT, gracze, chunki, encje, NPC i aktywne ostrzeżenia |

## Wydajność serwera

Co `health.interval-seconds` (10 s) plugin mierzy TPS, MSPT, graczy, załadowane chunki i encje oraz liczy NPC
pluginów Ludnosc / Armia / Skarbiec / Wladcy / Zycie po znacznikach PDC (`health/NpcCensus`). Historia: 1 h co pomiar
+ 24 h agregatów minutowych, zapis `plugins/Panel/health.json` (co `save-minutes` i przy wyłączeniu). Progi
`health.thresholds.*` dają ostrzeżenia (w danych, w panelu i — z limitem `alerts.cooldown-minutes` — na czacie dla
`panel.health.alerts` oraz adminów panelu). Panel WWW: Administracja → „Wydajność serwera” (RPC `admin.health`, ADM).

## Role i uprawnienia

- **R / A / M** (Władca / Doradca+ / członek) — liczone z Kraje dla kraju z `params.id`; admin panelu przechodzi wszędzie.
- **Admin panelu (ADM)** — `admins: [nick...]` w `config.yml`. `ops-are-admins: false` (domyślnie) — opy nie są adminami.
- **Zarządca państw NPC** — globalny (`npc-managers`) albo przypisany do jednego kraju NPC (`data.json`): tylko
  `admin.npc.*` i część `npc` w `country.get`; `admin.npc.create` tylko admin i zarządca globalny.
- Uprawnienia Bukkit nie są używane do RPC (gracz na stronie zwykle jest offline).

## Państwo NPC v2 (pełne zarządzanie)

Metody z `PANEL.md` („Rozszerzenie v2”, kształty w „Kształty odpowiedzi v2”), dostęp jak `admin.npc.*` (ADM, zarządca
globalny, zarządca przypisany do kraju), tylko dla krajów NPC; każda mutacja w `audit.log` i w dzienniku AI:

- odczyty: `admin.npc.history` (wykresy z `plugins/Panel/history`, `current` z poziomem osady i celem AI),
  `relations`, `economy` (rozbicie 24 h z Armii), `buildings`, `army`, `log` (kategorie + wyszukiwanie);
- mutacje: `setOpinion`, `setStance`, `propose`, `declareWar` (koszt jak w Kraje, „nigdy wojny” blokuje), `demandTribute`,
  `setBudget` (suma 100), `food`, `settlers`, `order` (planer albo x/z), `develop`, `queue` (up/down/cancel),
  `setPosture`, `setSiegeTarget`, `treasury` (korekta ±, nie poniżej 0), `disband`, `setTaxMultiplier` (`percent`, też `value`, 0–300 %).

Wymaga Armii (API 1.3) i Ludnosc (API v2) — starsze wersje dają czytelny błąd „zaktualizuj plugin”, a odczyty pola `null`.

**Historia**: co godzinę próbka (skarbiec, ludność, żołnierze, chunki, jedzenie) każdego państwa NPC do
`plugins/Panel/history/<id kraju>.json` (zapis w tle, atomowa podmiana), przechowywane `history.keep-days` (30) dni;
`history.player-countries: true` zapisuje też kraje graczy.

## Edytor planu państwa NPC, teren, nastrój, wymuszenia

Kształty w `PANEL.md` („Kształty odpowiedzi edytora”, „Nastrój mieszkańców”, „Wymuszenia budowy”); dostęp jak `admin.npc.*`.

- `admin.npc.mapRender` — widok z góry ziemi kraju + margines jako PNG (`map/MapRenderService`: snapshoty chunków porcjami na
  głównym wątku, niezaładowane chunki `getChunkAtAsync` z limitem, kolor z `BlockData#getMapColor()`, cieniowanie jak
  mapy vanilla, woda półprzezroczysta; obraz poza głównym wątkiem; cache 60 s; częściowa mapa, gdy czas RPC się kończy).
  Do tego chunki kraju, sąsiedzi, obszary chronione (`plugins/Kraje/protected-areas.json`), budynki (obrysy z tabeli
  `plan/BuildingCatalog`), koszt i limit terenu. Ustawienia `map-render.*`.
- `admin.npc.getPlan`, `setPlan` (walidacja i normalizacja w `plan/PlanValidator`, limity 200/200/50/500), `clearPlan`,
  `planPreview` → `LudnoscApi.designPlan`/`setDesignPlan`/`clearDesignPlan`/`designPlanPreview`.
- `admin.npc.territory` — zajmowanie i zwalnianie chunków (`plan/TerritoryPlanner`: cudze kraje, obszary chronione,
  sąsiedztwo, stolica, spójność; koszt jak w Kraje albo `territory.*`), wykonywane porcjami na głównym wątku.
- `country.get.mood` (plugin Zycie, opcjonalny) i `admin.npc.festival` (konsola: `zycie admin swieto <kraj>`).
- `admin.npc.forceRedesign`, `forceRoads`, `forceModernize` → odpowiednie metody `LudnoscApi`.

## Bezpieczeństwo

- Walidacja typów, zakresów i długości każdego parametru (`Params`), kwoty jako liczba miedziaków albo tekst `3zł 5sr`.
- Mutacje wykonywane na głównym wątku z limitem czasu (`limits.rpc-timeout-ms`, mniej niż 10 s Workera); wywołanie,
  które przekroczyło czas, nie zostanie już wykonane.
- Limit nieudanych logowań per nick i per IP (`limits.login-attempts` / `login-window-minutes`, domyślnie 5 / 15 min),
  limit zmian na użytkownika (`limits.mutations-per-minute`).
- Hasła i tokeny nie trafiają do logów (także w trybie `debug`). Zmiana hasła w nLogin unieważnia sesje panelu.
- `plugins/Panel/audit.log` — każda zmiana i logowanie (kto, co, kiedy, IP z Workera, wynik), JSON w linii, rotacja 5 MB.
- Powiadomienia w grze dla członków kraju („[Panel] Nick zmienił podatek na …”), `notify-in-game`.

## Edytor planu państw graczy i wyrównywanie terenu (wave 12)

- Wspólne RPC `country.plan.get`, `mapRender`, `preview`, `buildings`, `set`, `clear` obsługują kraje graczy i NPC.
  Odczyt i zlecanie wyrównania: Doradca+ własnego kraju, uprawniony zarządca właściwego NPC lub administrator.
  Zapis/usunięcie całego planu: Władca, właściwy zarządca NPC lub administrator. Obywatel ani zarządca NPC
  nie dostaje przez te metody praw do obcego państwa gracza.
- `country.get.access` zawiera `planRead`, `planWrite`, `planLevel`, `planTerritory`. Narzędzie przejmowania
  chunków nadal korzysta z ograniczonego `admin.npc.territory`; edytor gracza nie dostaje wymuszonego zajmowania ziemi.
- `country.plan.levelPreview {id, world?, minX, minZ, maxX, maxZ, targetY?, free?}` zwraca
  `{id, token, expiresAt, preview, costText, free}`. `targetY: null` wybiera poziom automatyczny. Najwyżej 4096 kolumn,
  w świecie stolicy. `preview.available: false` oznacza brak tokenu i wyjaśnienie w `preview.reason`.
- `country.plan.level {id, token}` potwierdza zlecenie. Token działa 120 sekund, raz, tylko dla tego użytkownika
  i kraju. Zmiana ceny lub ilości prac wymaga nowego podglądu; własność i ochrona są ponownie sprawdzane przez Ludnosc.
  `free: true` jest dostępne wyłącznie administratorowi. Prace idą do trwałej kolejki budowniczych, mutacje do audytu.
- Starszy provider Ludnosc bez nowych metod zwraca czytelny komunikat o potrzebie aktualizacji. Dotychczasowe
  `admin.npc.*` zachowują uprawnienia i kompatybilność.

## Statystyki Skarbu Świata (tylko WorldSteward)

Metody `world.stats.summary|accounts|flows|account|coins|trail|merchant` są odczytami, a `world.stats.census` mutacją z audytem. Szczegóły: `PANEL.md`, `WorldMethods.stats`.
Panel przy każdym wywołaniu pyta Skarbiec o zarządcę (`worldMoneyManager`), a Skarbiec sprawdza UUID drugi raz. Role panelu i rangi krajów nie dają dostępu.
Przekazywane są tylko parametry z białej listy, każdy prosty i do 128 znaków. Odpowiedzi asynchroniczne (`CompletableFuture`) wysyła `RpcService` po zakończeniu.
Przelewy Skarbu Świata do skarbców państw NPC: odczyt `world.npcTreasuries {query?}` i mutacja z audytem `world.npcTransfer {country, amount, confirm, reason, direction}`.
Dostęp ma tylko WorldSteward; UUID sprawdzają i Panel, i Skarbiec. `confirm` musi być tą samą kwotą w md, powód ma 3–120 znaków.
Przelew to jedno przeniesienie z/do `world:reserve`. Odpowiedź przychodzi dopiero po wymuszeniu zapisu dziennika banku (`LedgerStore.durable`).
Testy: `WorldStatsMethodsTest` (odmowa dla admina, zarządcy NPC, władcy, gracza i tego samego nicku z innym UUID; druga bramka w Skarbcu; biała lista parametrów; błędy asynchroniczne) oraz `AccessMatrixTest`.

## Gospodarka kraju (`country.economy`)

Odczyt gospodarki jednego kraju dla strony `#/gospodarka/<id>` (kontrakt: PANEL.md „Gospodarka kraju”). Dostęp przy każdym
wywołaniu (`rpc/EconomyMethods.access`): władca i doradca kraju, admin, WorldSteward (UUID potwierdza Skarbiec), zarządca kraju NPC.
Dane z Ludnosci przez refleksję (`economy/LudnoscBridge`: `materialFlow`, `life().stats/countryLife`, `work().summary`) plus
`LudnoscApi.info`; brak pluginu, starszy jar, wyjątek albo nowe pola nie psują odpowiedzi (`economy/Plain`, `extra`).
`economy/EconomyReport` (czysty) normalizuje dane, liczy zapełnienie, zapas jedzenia w dniach, braki materiałów i ostrzeżenia
z podpowiedziami (`economy/Goods`: nazwy towarów, grupy materiałów i kto je wytwarza). `economy/EconomyTracker` (pamięć) daje
szacunek dziennej wydajności z przyrostu towaru, wykres doby i czas czekania budów; `economy/EconomyService` — odpowiedź z pamięci
15 s per kraj, próbnik w tle (jeden kraj gracza na sekundę najwyżej, tempo zależne od kosztu odczytu). Ustawienia `economy.*`.
Testy: `EconomyAccessTest`, `EconomyReportTest`, `EconomyTrackerTest`, `LudnoscBridgeTest`, `EconomyServiceTest`.
Próba na izolowanym Paperze: `../../verify-panel-economy-20261029/` (`run.py`, wynik `latest-run.json`).

## Konfiguracja

`worker-url` (domyślnie `https://panel.example.workers.dev`), `plugin-secret` (pusty = wygenerowany przy pierwszym
starcie; ustaw go w Workerze: `wrangler secret put PANEL_SECRET`), `admins`, `ops-are-admins`, `npc-managers`,
`limits.*`, `notify-in-game`, `history.*`, `health.*` (wydajność serwera), `debug`, `messages.*` (MiniMessage).

## Budowanie

```
gradlew build
```

Wynik: `build/libs/Panel-1.0.0.jar`. Kompilacja wymaga `../../medival-api/build/libs/medival-api.jar`
(`gradlew jar` w `medival-api`) i `../../downloads/nlogin/nLogin.jar` (tylko `compileOnly`). Testy JUnit: macierz
uprawnień (role × metody × kraje), walidacja parametrów, parser kwot, limity prób, serializacja wiadomości, config.

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
