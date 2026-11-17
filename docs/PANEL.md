# Panel — zarządzanie państwami przez przeglądarkę

Strona po polsku (klimat średniowieczny), logowanie nickiem i hasłem z serwera (nLogin), albo linkiem z gry (`/panel`).
Gracz zarządza SWOIM krajem wg rangi; admin zarządza wszystkim (w tym państwami NPC). Konwencje i autorstwo: patrz `SYSTEM.md`.

## Architektura (jak „Głos”)
```
Przeglądarka ──HTTPS (cookie sid)──► Cloudflare Worker "panel-mc" + Durable Object "PanelHub" ◄──wss (Bearer PANEL_SECRET)── plugin Paper "Panel"
```
- Worker serwuje SPA (static assets) i `/api/*`. DO trzyma sesje i przekazuje RPC do pluginu, czeka na odpowiedź (timeout 10 s; brak pluginu → 503 `{"error":"Serwer gry jest offline"}`).
- **Plugin jest autorytatywny**: przy KAŻDYM RPC sam liczy uprawnienia użytkownika (`uuid`) — DO przekazuje tylko tożsamość z sesji.
- Wzorzec kodu: `glos/worker/` (Worker+DO, Hibernation API, sekret w czasie stałym, testy z `wrangler dev`) i `glos/plugin/.../WorkerConnection.java` (java.net.http.WebSocket, kolejka wysyłki, reconnect).

## Protokół DO ↔ plugin (JSON, pole `t`)
| kierunek | t | pola |
|---|---|---|
| P→DO | `hello` | `v:1` |
| DO→P | `auth` | `id, name, password, ip` — plugin sprawdza `nLoginAPI.getApi().comparePassword(name, password)` (rate-limit per nick i per ip, np. 5/15 min), odpowiada `auth-result` |
| P→DO | `auth-result` | `id, ok, uuid?, name?, error?` (error po polsku) |
| P→DO | `link-token` | `token, uuid, name` — z komendy `/panel` (32 znaki, 10 min, jednorazowy) |
| DO→P | `rpc` | `id, user:{uuid,name}, method, params` |
| P→DO | `rpc-result` | `id, ok, data?, error?` |
| P→DO | `revoke` | `uuid` — unieważnij sesje gracza (np. `/panel wyloguj`, zmiana hasła) |

## HTTP (przeglądarka ↔ Worker)
- `POST /api/login` `{name,password}` → ustawia cookie `sid` (HttpOnly, Secure, SameSite=Strict, 7 dni) i zwraca `me`. Błędy: 401/429.
- `GET /login?t=TOKEN` → link z gry: tworzy sesję, przekierowuje na `/`.
- `POST /api/logout`; `POST /api/rpc` `{method, params}` → `{ok,data}`/`{ok:false,error}` (sesja wymagana; inaczej 401).
- Ochrona: SameSite=Strict + nagłówek `X-Requested-With: panel` wymagany dla POST, rate-limit RPC per sesja (np. 10/s), max body 16 KB, CSP bez inline-script.

## Metody RPC (plugin; walidacja wszystkiego; mutacje na głównym wątku)
Uprawnienia: R = Władca, A = Doradca+, M = członek, * = zalogowany, ADM = admin panelu (`plugins/Panel/config.yml` `admins: [nick...]`; offline gracz nie ma uprawnień Bukkit → lista w configu; `isOp()` tylko przy `ops-are-admins: true`, domyślnie wyłączone — patrz „Role panelu” niżej), NPCM = zarządca państw NPC.
- `*` `me` → `{uuid,name,admin,balance,country:{id,name,rank}|null}`
- `*` `overview` → `{online,maxPlayers,countries:[{id,name,color,npc,ruler,members,population,soldiers,chunks,areaBlocks,allies[],wars[]}]}`
- `*` `country.get {id}` → szczegóły; części wrażliwe tylko dla uprawnionych: `members` (nick, ranga, online, dług podatkowy) — M; `treasury {balance, log[]}` — A; `npc {settings, paused, log[]}` — ADM; `population` (Ludnosc: ludność, mieszkania, spichlerz, budynki, place budowy), `army` (typy, morale, żołd), `relations`, `sieges` — publiczne.
- `R` `country.setColor {id,color}`, `country.setDescription {id,text}`, `country.setTax {id,amount}`, `country.transferRuler {id,player}`
- `R` `member.setRank {id,player,rank}` (ADVISOR/CITIZEN), `A` `member.kick {id,player}` (nie władcy, nie wyższej rangi), `A` `member.invite {id,name}` (zaproszenie ważne jak w grze; gracz offline dostaje je po wejściu — albo błąd „gracz musi być online”, wybór implementującego)
- `R` `relation.propose {id,target,relation:"ALLIANCE"|"NEUTRAL"}`, `R` `relation.declareWar {id,target}`, `R` `relation.breakAlliance {id,target}`
- `M` `treasury.deposit {id,amount}` (z konta bankowego gracza), `R` `treasury.withdraw {id,amount}` (na konto władcy)
- `A` `army.recruit {id,type,count}` (żołnierze pojawiają się przy koszarach/stolicy), `R` `army.disband {id,type,count}`
- `ADM` `admin.npc.create {name,color,ruler,world,x,z}`, `admin.npc.set {id,param,value}`, `admin.npc.pause {id,paused}`, `admin.npc.tick {id}`, `admin.npc.armyAdd {id,type,count}`, `admin.npc.ruler {id,name,skin}`
- `ADM` `admin.country.delete {id}`, `admin.country.setRuler {id,player}`, `admin.relation.set {a,b,relation}`, `admin.treasury.adjust {id,amount}` (±), `admin.economy.adjust {player,amount}` (±)
- `ADM` `admin.players` → `[{uuid,name,online,country,rank,balance,lastSeen}]`, `admin.audit {limit}` → log działań panelu
- `ADM` `admin.health {range?}` → wydajność serwera: TPS, MSPT, gracze, chunki, encje, NPC pluginów, ostrzeżenia (szczegóły: „Wydajność serwera” na końcu)
- **Role panelu** (plugin, `plugins/Panel/config.yml`): `admins: []` = ADM (pełny admin); `ops-are-admins: false` (domyślnie opy NIE są adminami panelu; admina dodaje konsola albo istniejący admin: `/panel admin dodaj|usun <nick>`). `npc-managers: []` = **NPCM** „Zarządca państw NPC” (globalny): dostęp TYLKO do `admin.npc.*` i do części `npc` w `country.get` krajów NPC; NIE ma `admin.country.*`, `admin.relation.*`, `admin.treasury.*`, `admin.economy.*`, `admin.players`, `admin.audit`. Zarządca **przypisany** do konkretnego kraju NPC (plik danych Panelu) ma te same prawa, ale tylko dla tego `id`. Komendy: `/panel zarzadca dodaj|usun <nick>`, `/panel zarzadca lista`, `/panel zarzadca przypisz <kraj> <nick|->` (admin lub konsola).
  - `admin.npc.*` (z `id`): ADM albo NPCM globalny albo zarządca przypisany do `id`; `admin.npc.create`: tylko ADM i NPCM globalny.
  - `ADM` `admin.npc.setManager {id, player|null}` → przypisuje/usuwa zarządcę kraju NPC.
  - `me` zwraca dodatkowo `roles:{admin:bool, npcManager:bool, managedNpcCountries:[id...]}` (pole `admin` zostaje dla zgodności; `npcManager` = globalny zarządca; `managedNpcCountries` = id krajów NPC, którymi użytkownik może zarządzać — dla ADM i NPCM globalnego wszystkie kraje NPC).
- Każda mutacja: wpis w `plugins/Panel/audit.log` (kto, co, kiedy, IP z DO) + powiadomienie w grze dla członków kraju („[Panel] <nick> zmienił podatek na …”).

## Rozszerzenia API (medival-api) potrzebne panelowi
Metody `default` (żeby stare implementacje się kompilowały), implementowane w Kraje i Armii:
- `KrajeApi`: `setColor`, `setDescription`, `setTax`, `setRank`, `kickMember`, `transferRuler`, `invite(countryId, inviterUuid, targetName)`, `declareWar(from,to)` (reguły/koszt jak komenda), `breakAlliance`, `treasuryDeposit(countryId, playerUuid, amount)`, `treasuryWithdraw(countryId, playerUuid, amount)`, `treasuryLog(countryId, limit)`, `taxDebt(countryId, player)`, `setRuler(countryId, player)` (admin) — wszystkie zwracają `KrajeResult`, główny wątek.
- `ArmiaApi`: `recruit(countryId, unitType, count, @Nullable UUID byPlayer)`, `disband(countryId, unitType, count)`, `unitTypes()` (id, nazwa, koszt, żołd), `morale(countryId)`, `npcSettings(countryId)`, `setNpcSetting(countryId, key, value)`, `setNpcPaused(countryId, paused)`, `npcTick(countryId)`, `npcLog(countryId, limit)`, `addSoldiers(countryId, unitType, count)` (admin, bez kosztu), `placeRuler(countryId, name, skin)`; `createNpcCountry(name, rgb, Location, rulerName)` (Armia ustawia AI + władcę).
- `LudnoscApi` już ma odczyty; dodać `default` `info(countryId)` → rekord z ludnością, mieszkaniami, jedzeniem, listą budynków i placów budowy.

## Odpowiedzi pluginu (stan implementacji `panel/plugin`, informacyjnie)
Kwoty zawsze w miedziakach (`long`), obok często `*Text` (np. `"3 zł 5 sr"`); kolory `"#RRGGBB"`; rangi `RULER|ADVISOR|CITIZEN` (+ `rankName` po polsku); czasy w ms epoch.
- `country.get`: `id,name,color,npc,description,ruler,rulerUuid,capital{world,x,z,blockX,blockZ},chunks,areaBlocks,memberCount,tax,taxText,myRank,access{member,advisor,ruler,admin,npcManage}`, `members[{uuid,name,rank,rankName,online,taxDebt,debtDays,joinedAt,lastSeen}]` (M), `treasury{balance,balanceText,log[{time,type,amount,amountText,actor}]}` (A), `npc{available,settings{agresja,ekspansja,dyplomacja,bogactwo,max-chunki,max-zolnierze,aktywna},paused,globalPaused,nextTickAt,log[{time,text}],manager{uuid,name}|null}` (ADM/zarządca), `population{population,housing,food,foodCapacity,foodBalance,dailyTax,hungryDays,homelessDays,buildings[{type,name,function,beds,world,x,y,z}],sites[{type,name,percent,…}]}|null`, `army{total,byType,types[{id,name,description,cost,wage,count,…}],morale,wages}|null`, `relations{allies[{id,name,color,npc}],wars[…]}`, `sieges[{id,attacker{…},defender{…},world,cx,cz,progress(0..1),startedAt,endsAt}]`. Części bez uprawnień są pominięte.
- `admin.players`: `[{uuid,name,online,country{id,name}|null,rank|null,balance,lastSeen}]`; `admin.audit`: `[{time,uuid,name,ip,action,params,ok,error}]` (najnowsze pierwsze).
- Mutacje zwracają `{ok:true, …}` (np. `message` z Armii, nowe `balance`); `admin.npc.create` → `{ok,id,aiAvailable}`. `member.invite`: tylko gracz online (inaczej błąd „musi być online”). IP do audytu: `user.ip` albo `ip` z wiadomości `rpc`.

## Rozszerzenie v2: pełne zarządzanie państwem NPC (zamówione 2026-10-26)
Dostęp: ADM, zarządca globalny, zarządca przypisany do kraju (jak `admin.npc.*`). Wszystkie mutacje w audycie.

### Zakładki strony „Państwo NPC” (SPA)
1. **Przegląd** — kafelki (chunki, żołnierze, ludność, skarbiec, jedzenie, poziom osady), **wykresy historii** (skarbiec, ludność, żołnierze, chunki, jedzenie — ostatnie 7 dni, próbkowane co godzinę przez plugin Panel do `plugins/Panel/history/<kraj>.json`), aktualny „cel AI” (np. „odkłada na pałac”, „prowadzi wojnę z X”).
2. **Stosunki** — tabela wszystkich krajów: relacja (wojna/sojusz/neutralne/wasal/suweren), **opinia AI o kraju (-100…100, suwak)**, **nastawienie** (auto / przyjazne / wrogie / nigdy wojny / preferowany cel ataku), przyciski: zaproponuj sojusz/pokój (od NPC), wypowiedz wojnę, wymuś relację (admin), zażądaj trybutu.
3. **Gospodarka** — skarbiec (korekta ±), **rozbicie przychodów i wydatków z ostatniej doby** (podatki NPC, dochód AI, żołd, utrzymanie ziemi, budowy, trybuty), **podział budżetu AI (suwaki %: wojsko / budowy / rezerwa)**, mnożnik dochodu (= bogactwo), log skarbca.
4. **Ludność i jedzenie** — ludność / mieszkania, **jedzenie (stan, pojemność, bilans dzienny; korekta ±)**, **dodaj/usuń osadników**, podatek od obywatela (mnożnik), dni głodu/bezdomności.
5. **Budowy** — lista budynków, place budowy (postęp), **kolejka (zmiana kolejności, anulowanie)**, **„Zamów budynek”**: wybór typu (z wymaganiami i kosztem, z informacją czego brakuje), lokalizacja automatyczna przez planer albo współrzędne x/z, przycisk „Rozbuduj teraz (budżet …)” → `developSettlement`.
6. **Armia** — liczebność wg typów, morale, żołd; dodaj/rozwiąż; **postawa: obronna / zrównoważona / ofensywna**; **cel oblężeń** (kraj lub auto); posterunki (lista).
7. **Osobowość** (istniejące suwaki) + **Dziennik AI** z filtrami (wojna, dyplomacja, budowy, armia, gospodarka) i wyszukiwarką.

### Nowe metody RPC (plugin Panel)
`admin.npc.history {id, days}` · `admin.npc.relations {id}` · `admin.npc.setOpinion {id, target, value}` · `admin.npc.setStance {id, target, stance}` · `admin.npc.propose {id, target, relation}` · `admin.npc.declareWar {id, target}` · `admin.npc.demandTribute {id, target, amount}` · `admin.npc.economy {id}` (rozbicie 24 h) · `admin.npc.setBudget {id, army, buildings, reserve}` · `admin.npc.food {id, delta}` · `admin.npc.settlers {id, delta}` · `admin.npc.buildings {id}` · `admin.npc.order {id, type, x?, z?, rotation?}` · `admin.npc.develop {id, budget}` · `admin.npc.queue {id, action:"up"|"down"|"cancel", index}` · `admin.npc.army {id}` · `admin.npc.setPosture {id, posture}` · `admin.npc.setSiegeTarget {id, target|null}` · `admin.npc.log {id, category?, query?, limit}`.

### Rozszerzenia API (metody `default`)
- **ArmiaApi**: `opinion(npc, other)`, `setOpinion(npc, other, int)`, `stance(npc, other)`, `setStance(npc, other, String)`, `budgetShares(npc)`, `setBudgetShares(npc, army, buildings, reserve)`, `posture(npc)`, `setPosture(npc, String)`, `siegeTarget(npc)`, `setSiegeTarget(npc, UUID|null)`, `aiGoal(npc)` (tekst), `economyBreakdown(npc)` (rekord z kwotami 24 h), `demandTribute(npc, target, amount)`, `npcLog(npc, category, query, limit)`.
- **LudnoscApi**: `adjustFood(country, delta)`, `adjustSettlers(country, delta)`, `queue(country)` (lista pozycji), `moveQueue(country, index, delta)`, `cancelQueued(country, index)`, `buildingRequirements(country)` (typ → spełnione?, czego brakuje, koszt), `orderBuildingAuto(country, type)` (planer wybiera miejsce dla danego typu), `settlementTier(country)`.
- AI Armii MUSI respektować opinię, nastawienie (np. „nigdy wojny” blokuje wojnę; „preferowany cel” zwiększa wagę), postawę i podział budżetu.

### Kształty odpowiedzi v2 (kontrakt SPA ↔ plugin, 2026-10-26)
Konwencje jak wyżej: kwoty w miedziakach (`long`, obok opcjonalnie `*Text`), kolory `"#RRGGBB"`, czasy w ms epoch, procenty jako liczby całkowite. Istniejące metody i ich kształty się nie zmieniają.

**Wspólne zasady**
- Każda metoda bierze `id` kraju NPC. Uprawnienia jak `admin.npc.*`: ADM, NPCM globalny albo zarządca przypisany do `id`.
  - Brak uprawnień → `{ok:false,error:"Brak uprawnień"}`.
  - Kraj nie-NPC → `{ok:false,error:"To nie jest państwo NPC"}`.
- Mutacje odpowiadają `{ok:true, message?, …}`. Jeśli przyjdzie `message` (po polsku), panel pokaże je w toaście zamiast własnego tekstu. Błędy walidacji wracają jako `{ok:false, error}` po polsku.
- Kody (zawsze WIELKIMI literami):
  - relacja: `ALLIANCE | NEUTRAL | WAR | VASSAL | SUZERAIN`, z perspektywy NPC (`VASSAL` = tamten kraj jest wasalem tego NPC, `SUZERAIN` = tamten kraj jest suwerenem tego NPC);
  - nastawienie: `AUTO | FRIENDLY | HOSTILE | NEVER_WAR | PREFERRED_TARGET`;
  - postawa: `DEFENSIVE | BALANCED | OFFENSIVE`;
  - kategoria dziennika: `WAR | DIPLOMACY | BUILD | ARMY | ECONOMY | OTHER`;
  - rodzaj celu AI (`goal.kind`): `SAVING | WAR | EXPANSION | BUILDING | RECRUITING | DIPLOMACY | IDLE`.
- DO trzyma odczyty v2 w cache (`admin.npc.history|relations|economy|buildings|army|log`), żeby działał tryb offline, tak jak przy `country.get`.

**Odczyty**
- `admin.npc.history {id, days}`. `days` ma zakres 1..7, domyślnie 7; większa wartość jest obcinana do 7.
  ```json
  {"id":"npc-mazowsze","days":7,"intervalMs":3600000,"from":1760900000000,"to":1761504800000,
   "points":[{"t":1760900000000,"treasury":52000,"population":15,"soldiers":14,"chunks":44,"food":null}],
   "current":{"treasury":71400,"population":22,"housing":24,"soldiers":23,"maxSoldiers":40,"chunks":58,"maxChunks":120,
              "food":900,"foodCapacity":1000,"tier":2,"tierName":"Gród",
              "goal":{"kind":"SAVING","text":"Odkłada na pałac","target":null,"progress":0.62}}}
  ```
  - `points` rośnie po `t` co `intervalMs`. Próbki pochodzą z `plugins/Panel/history/<kraj>.json`, najwyżej `days*24+1`.
  - Miara, której brakuje w próbce (np. przy wyłączonym Ludnosc), ma wartość `null`. Brak pliku → `points: []`.
  - `current` to stan teraz (kafelki Przeglądu).
  - `goal` może być `null`. `goal.target` to `{id,name,color}|null`, a `goal.progress` to 0..1 albo `null`.
- `admin.npc.relations {id}` zwraca wszystkie pozostałe kraje:
  ```json
  {"id":"npc-mazowsze","warCost":1000,"tributeMax":100000,
   "countries":[{"id":"c-polanie","name":"Królestwo Polan","color":"#B02E26","npc":false,"ruler":"Bolko","soldiers":11,"chunks":46,
                 "relation":"WAR","opinion":-62,"stance":"PREFERRED_TARGET","pending":null,"tribute":null}]}
  ```
  - `opinion` to liczba całkowita -100..100.
  - `pending` to `{relation:"ALLIANCE"|"NEUTRAL", direction:"OUT"|"IN", at}|null`. OUT oznacza propozycję od NPC, IN propozycję do NPC.
  - `tribute` to `{amount, direction:"PAYS"|"RECEIVES", until|null}|null`, czyli dzienny trybut, który NPC płaci albo dostaje.
- `admin.npc.economy {id}` zwraca rozbicie z ostatnich 24 h:
  ```json
  {"id":"npc-mazowsze","balance":71400,"from":1761418400000,"to":1761504800000,
   "income":[{"key":"npcTax","label":"Podatki od ludności","amount":2200},{"key":"aiIncome","label":"Dochód AI","amount":1450},{"key":"tribute","label":"Trybuty","amount":200}],
   "expenses":[{"key":"wages","label":"Żołd","amount":1350},{"key":"land","label":"Utrzymanie ziemi","amount":290},{"key":"buildings","label":"Budowy","amount":900}],
   "incomeTotal":3850,"expensesTotal":2540,"net":1310,
   "budget":{"army":45,"buildings":35,"reserve":20},
   "wealth":60,"incomeMultiplier":1.1,"taxMultiplier":100,
   "log":[{"time":1761504000000,"type":"tax","amount":930,"actor":"","note":"Podatki od ludności"}]}
  ```
  - Klucze `income`: `npcTax | aiIncome | tribute | other`.
  - Klucze `expenses`: `wages | land | buildings | recruit | tribute | war | other`.
  - `amount` jest zawsze ≥ 0; kategorie z zerem można pominąć. `label` jest opcjonalne, bo panel ma własne nazwy.
  - `budget` sumuje się do 100; `budget.research` to udział badań w % albo `null` (auto, decyduje Postep). Patrz `admin.npc.setBudget`.
  - `wealth` to ustawienie `bogactwo` (0..100). `incomeMultiplier` to wynikający z niego mnożnik dochodu, tylko informacyjnie.
  - `taxMultiplier` to mnożnik podatku od obywatela w % (100 = normalny).
  - `log` ma kształt jak `country.get.treasury.log` (od najnowszych), z opcjonalnym `note`.
- `admin.npc.buildings {id}`:
  ```json
  {"id":"npc-mazowsze","tier":2,"tierName":"Gród","nextTier":{"tier":3,"name":"Miasto","missing":["Kościół","30 mieszkańców (jest 22)"]},
   "buildings":[{"type":"dom","name":"Dom","function":"HOUSING","beds":4,"world":"world","x":1450,"y":68,"z":-300}],
   "sites":[{"type":"spichlerz","name":"Spichlerz","percent":30,"placed":120,"total":400,"world":"world","x":1470,"y":68,"z":-280,"startedAt":1761490000000}],
   "queue":[{"index":0,"type":"dom","name":"Dom","cost":800,"auto":true,"x":null,"z":null,"rotation":0,"orderedBy":"AI","orderedAt":1761500000000}],
   "types":[{"type":"targ","name":"Targ","function":"MARKET","description":"Handel i dochód","cost":2500,"beds":0,"minTier":2,"count":0,"max":1,
             "available":false,"missing":["Kuźnia"]}],
   "develop":{"suggested":5000,"max":20000}}
  ```
  - `nextTier` może być `null` (najwyższy poziom).
  - `queue[].index` liczy się od 0 (0 = następny do budowy). `orderedBy` to nick albo `"AI"`.
  - `types` zawiera wszystkie typy budynków. Przy `available:false` lista `missing` jest niepusta i mówi po polsku, czego brakuje (budynek, ludność, poziom osady, pieniądze).
  - `max` może być `null` (bez limitu).
  - `develop.max` nie przekracza stanu skarbca.
- `admin.npc.army {id}`:
  ```json
  {"id":"npc-mazowsze","total":23,"max":40,"morale":64,"wages":457,
   "types":[{"id":"piechota","name":"Piechota","description":"Miecz, tarcza i kolczuga","cost":250,"wage":12,"count":12}],
   "posture":"BALANCED","siegeTarget":null,"autoTarget":{"id":"c-polanie","name":"Królestwo Polan","color":"#B02E26"},
   "outposts":[{"name":"Strażnica nad Narwią","world":"world","x":1520,"z":-410,"soldiers":4}],
   "sieges":[]}
  ```
  - `morale` ma zakres 0..100.
  - `siegeTarget` to `{id,name,color}|null`, gdzie `null` = auto.
  - `autoTarget` to cel, który AI wybrała albo wybrałaby przy auto; może być `null`.
  - `sieges` ma kształt jak `country.get.sieges`.
  - `mercenaries` (dodane 2026-10-26, Armia API 1.3): najemnicy pogrupowani wg typu i końca kontraktu, od najwcześniejszego:
    `[{"type":"kusznik-genuenski","name":"Kusznik genueński","count":3,"contractUntil":1761750000000,"wage":60}]`; pusta lista, gdy brak.
    Najemnicy wliczają się w `total`, ale nie w `types[].count` (typów najemników nie ma na liście `types`).
- `admin.npc.log {id, category?, query?, limit}`:
  - `limit` 1..500, domyślnie 100;
  - `category` pominięte albo `"ALL"` = wszystkie kategorie;
  - `query` do 64 znaków: szukanie podciągu bez rozróżniania wielkości liter i polskich znaków.
  ```json
  {"id":"npc-mazowsze","total":17,
   "counts":{"ALL":17,"WAR":4,"DIPLOMACY":3,"BUILD":4,"ARMY":3,"ECONOMY":2,"OTHER":1},
   "entries":[{"time":1761504000000,"category":"WAR","text":"Wypowiedziano wojnę: Królestwo Polan"}]}
  ```
  - `entries` są od najnowszych, już po obu filtrach i po `limit`.
  - `total` to liczba wpisów pasujących do obu filtrów, przed `limit`.
  - `counts` liczy się dla samego `query`, bez filtra kategorii, bo panel pokazuje je na chipsach.

**Mutacje**
- `admin.npc.setOpinion {id, target, value}`: `value` to liczba całkowita -100..100 → `{ok:true, opinion}`.
- `admin.npc.setStance {id, target, stance}` → `{ok:true, stance}`. `NEVER_WAR` blokuje wojnę, także przez `admin.npc.declareWar`. `PREFERRED_TARGET` zwiększa wagę celu ataku.
- `admin.npc.propose {id, target, relation:"ALLIANCE"|"NEUTRAL"}` → `{ok:true, message, accepted:true|false|null}`. Między dwoma NPC rozstrzyga się od razu (`true` albo `false`). Gdy celem jest kraj gracza, władca dostaje propozycję w grze, a `accepted` to `null` (czeka).
- `admin.npc.declareWar {id, target}` → `{ok:true, message, cost}`. Zasady i koszt jak w `relation.declareWar`; płaci skarbiec NPC.
- `admin.npc.demandTribute {id, target, amount}`: `amount` to dzienny trybut w md, 1..`tributeMax` → `{ok:true, message, accepted:true|false|null}`. Kraj NPC decyduje od razu (na podstawie opinii i siły). Gracz dostaje żądanie w grze, a `accepted` to `null`.
- `admin.npc.setBudget {id, army, buildings, reserve, research?}`: liczby całkowite ≥ 0, suma musi wynosić 100 (inaczej błąd) → `{ok:true, budget:{army,buildings,reserve,research}}`.
  - `research` (dodane 2026-10-26, epoki z pluginu Postep) jest opcjonalne: udział badań w %, który Postep inwestuje z dziennego dochodu. Pominięte, `null` albo `"auto"` = Postep decyduje sam; wtedy `army + buildings + reserve = 100`, a w odpowiedzi `research: null`. Podane: suma czterech = 100.
- `admin.npc.food {id, delta}`: liczba całkowita ≠ 0, |delta| ≤ 100000. Wynik przycina się do 0..`foodCapacity` → `{ok:true, food, foodCapacity, applied}`, gdzie `applied` to faktyczna zmiana.
- `admin.npc.settlers {id, delta}`: liczba całkowita ≠ 0, |delta| ≤ 500. Ludność nie spada poniżej 0, a dodanie ponad liczbę mieszkań jest dozwolone (bezdomni) → `{ok:true, population, housing, applied}`.
- `admin.npc.order {id, type, x?, z?, rotation?}`:
  - `x` i `z` podaje się razem albo wcale; bez nich miejsce wybiera planer (`orderBuildingAuto`);
  - `rotation` to `0|90|180|270`, domyślnie 0;
  - niespełnione wymagania → błąd z listą braków.

  Wynik: `{ok:true, message, queued:{index,type,name,auto,x,z}}`.
- `admin.npc.develop {id, budget}`: budżet w md, 1..`develop.max` (`developSettlement`) → `{ok:true, message, ordered:[{type,name,cost}], spent}`.
- `admin.npc.queue {id, action:"up"|"down"|"cancel", index}` → `{ok:true, queue:[…]}` w kształcie jak w `admin.npc.buildings`. Ruch poza listę zwraca błąd.
- `admin.npc.setPosture {id, posture}` → `{ok:true, posture}`.
- `admin.npc.setSiegeTarget {id, target|null}`: `target` to dowolny inny kraj (AI oblega go, gdy jest z nim w wojnie), `null` = auto → `{ok:true, siegeTarget:{id,name,color}|null}`.

**Dodatkowe metody spoza listy wyżej (panel ich używa; dostęp jak `admin.npc.*`)**
- `admin.npc.treasury {id, delta}`: korekta skarbca NPC, md ≠ 0 → `{ok:true, balance}`. Skarbiec nie może zejść poniżej 0 (błąd). Zarządca nie ma uprawnień `admin.treasury.*`, dlatego potrzebna jest osobna metoda; admin może dalej używać `admin.treasury.adjust`.
- `admin.npc.disband {id, type, count}`: rozwiązanie żołnierzy NPC bez zwrotu kosztu, `count` od 1 do liczby żołnierzy danego typu → `{ok:true, message, count}`.
- `admin.npc.setTaxMultiplier {id, percent}`: mnożnik podatku od obywatela NPC, liczba całkowita 0..300 → `{ok:true, taxMultiplier}`.
- `admin.npc.fromVillage {x, z, world?, name?, color?, ruler?}` (dodane 2026-10-26; dostęp jak `admin.npc.create`: ADM i NPCM globalny): wioska najbliższa (x, z) w promieniu 96 bl. (`world` pominięty = pierwszy świat) staje się państwem NPC — stolica przy dzwonie, zajęte wszystkie chunki zabudowy (spójnie od stolicy), wieśniacy i domy przejęte przez Ludnosc, władca przy dzwonie; brak `name`/`color`/`ruler` = losowe polskie (`name` jak w Kraje: 3–24 znaki, litery, cyfry, `_`, `-`, bez spacji). Odpowiedź przychodzi po wykryciu wioski (kilka sekund) → `{ok:true, id, name, message, chunks, beds, villagers, adopted}` (`adopted` = przejęci wieśniacy albo `null`, gdy Ludnosc ich nie przejęła). Brak wioski / błąd → `{ok:false, error}` po polsku.
- „Wymuś relację” w zakładce Stosunki używa istniejącej metody `admin.relation.set {a,b,relation}`. Jest ona tylko dla ADM, więc panel pokazuje ten przycisk wyłącznie adminowi.

### AI Armii — obrona i odbudowa (zrobione: `logic/Crisis`, `logic/Capitulation`, `ai/VassalListener`)
- **Odbicie ziem**: chunki utracone przez `ClaimCause.CONQUEST` zapamiętane per kraj NPC (kto zabrał, kiedy); w wojnie z tym krajem mają najwyższy priorytet oblężeń; po pokoju — powód do ponownej wojny po cooldownie (zależnie od agresji).
- **Zemsta**: opinia o agresorze spada mocno i wraca powoli (config).
- **Tryb kryzysowy** (gdy kraj NPC jest w wojnie i traci chunki lub jest oblegany): szybszy pobór, wyższy udział budżetu na wojsko, priorytet murów/wież (przez LudnoscApi — typ hint), prośba o pomoc do sojuszników (propozycja/komunikat; sojusznik NPC może dołączyć do wojny wg dyplomacji/opinii).
- **Odbudowa po wojnie**: po pokoju budżet wraca na budowy; planer najpierw domy i spichlerz; naprawy budynków (Ludnosc) mają pierwszeństwo.
- Wszystko widoczne w dzienniku AI i w `aiGoal`.
- **Kapitulacja i wasalstwo (Kraje gotowe, API w kraje-api)**: AI Armii słucha `CapitulationOfferEvent` (sprawdź `toNpc()`), odpowiada `respondCapitulation` (przyjmuje wg agresji/przewagi/opinii; chciwy władca żąda wyższego trybutu — opcjonalnie odrzuca niskie); przegrywające państwo NPC samo oferuje kapitulację (`offerCapitulation`) zamiast upadać, gdy traci dużo i nie ma szans; wypowiadanie wojen przez AI ma używać `declareWar` (nie `setRelation`) albo sprawdzać `getOverlord`/`VassalRules`, żeby wasal nie atakował suwerena; wasal NPC płaci i może się wykupić (`buyout`), gdy go stać; suweren NPC może zwolnić wasala za wysoką opinię. Dziennik AI + `aiGoal`.

## Postęp — epoki i odkrycia (plugin Postep, dodane 2026-10-26)
Plugin Postep jest opcjonalny. Panel ma go w `softdepend`, a Postep ma w `softdepend` tylko Ludnosc, Armię i squaremap,
więc cyklu ładowania nie ma. Panel pobiera `PostepApi` leniwie przez ServicesManager i obsługuje `LinkageError`.
- Bez Postepu `postep.get` zwraca `{id, available:false}`, a mutacje kończą się błędem „Plugin Postep nie jest dostępny.”.
- `overview.countries[]` dostaje dwa pola: `era {id,name,years,color,icon,description}|null` i `eraProgress` (0..1 albo `null`).
  Ranking pokazuje z nich kolumnę „Epoka”.
- Statusy odkryć: `OWNED | AVAILABLE | LOCKED | ERA_LOCKED`.
  - `AVAILABLE`: epoka kraju jest osiągnięta i kraj ma wszystkie wymagania.
  - `LOCKED`: brakuje wymagań. Ustawienie takiego celu bada po kolei całą ścieżkę.
  - `ERA_LOCKED`: odkrycie z późniejszej epoki.

### Metody
Uprawnienie **A/NPCM** oznacza: Doradca albo Władca kraju `id`. Dla kraju NPC dochodzą ADM, NPCM globalny i zarządca przypisany do `id`.
- `*` `postep.get {id}`. Odpowiedź (odczyt trzymany w cache DO na tryb offline):
  ```json
  {"id":"c-polanie","available":true,
   "era":{"id":2,"name":"Renesans","years":"XV–XVII w.","color":"#C9A227","icon":"⚜","description":"…"},
   "nextEra":{"id":3,"name":"Epoka przemysłowa","years":"XIX w.","color":"#8E9AAF","icon":"⚙","description":"…"},
   "maxEra":false,"eraSince":1761000000000,"progress":0.396,"ready":false,
   "missing":["odkrycie: Proch strzelniczy","czas w epoce: jeszcze 12 dni","ludność ≥ 60 (jest 29)"],
   "points":240,"totalPoints":1420,"daily":46.5,"discoveryCount":9,"discoveryTotal":30,
   "target":{"id":"astronomia","name":"Astronomia","cost":340,"missing":100,"etaDays":3,"path":[{"id":"astronomia","name":"Astronomia","cost":340}]},
   "eras":[{"id":1,"name":"Średniowiecze","years":"V–XV w.","color":"#B08D57","icon":"⚔","description":"…"}],
   "tree":[{"id":"druk","name":"Druk","era":2,"cost":300,"requires":["uniwersytety"],"requiredForEra":true,"category":"nauka",
            "description":"…","effect":"+10% punktów badań","status":"OWNED","affordable":false}],
   "invest":{"investedToday":500,"investedTodayText":"5 zł","treasury":48250,"treasuryText":"482 zł 5 sr",
             "presets":[{"amount":100,"amountText":"1 zł","points":2.3}]},
   "access":{"discover":true,"invest":true,"admin":false},
   "ranking":[{"pos":1,"id":"c-polanie","name":"Królestwo Polan","color":"#B02E26","npc":false,"era":2,"eraName":"Renesans",
               "eraIcon":"⚜","eraColor":"#C9A227","progress":0.396,"discoveries":9,"totalPoints":1420}]}
  ```
  - `target` to `null`, gdy kraj nie ma celu badań. `path` to brakujące odkrycia ścieżki: najpierw wymagania, na końcu cel.
  - `etaDays` wynosi 0, gdy punktów już wystarcza, a `null`, gdy brak dziennego przyrostu punktów.
  - `invest` jest tylko dla uprawnionych A/NPCM. Zawiera podgląd punktów dla kwot 1, 5, 10 i 50 zł.
  - `affordable` jest `true`, gdy odkrycie jest `AVAILABLE` i kraj ma dość punktów.
  - `ranking` zawiera wszystkie kraje, posortowane po epoce, potem postępie, potem sumie punktów.
- `A/NPCM` `postep.investPreview {id, amount}` → `{amount, amountText, points}`. To odczyt (bez audytu), który strona woła z opóźnieniem 300 ms podczas wpisywania kwoty.
- `A/NPCM` `postep.discover {id, discovery}`: to samo co `/postep odkryj`. Gdy kraj ma dość punktów i wszystkie wymagania, odkrycie następuje od razu. W przeciwnym razie odkrycie zostaje celem badań.
  - Odpowiedź: `{ok:true, status:"UNLOCKED"|"TARGET_SET", discovery, missingPoints, message, points}`.
  - Błędy po polsku: kraj już zna to odkrycie, odkrycie z późniejszej epoki, nieznane odkrycie.
- `A/NPCM` `postep.invest {id, amount}`: pieniądze ze skarbca kraju zamieniają się w punkty badań, z malejącymi przychodami do dziennego sufitu.
  - Odpowiedź: `{ok:true, paid, paidText, points, researchPoints, investedToday, treasury, message}`.
  - Błędy: za mało pieniędzy w skarbcu, kwota poniżej minimum, dzienny sufit wyczerpany.
- `ADM` `admin.postep.setEra {id, era}`: `era` od 1 do liczby epok. Kraj dostaje wymagane odkrycia wcześniejszych epok, czas w epoce liczy się od nowa, a w grze padają `EraAdvanceEvent` i ogłoszenie. Odpowiedź: `{ok:true, era, eraName, message}`.
- `ADM` `admin.postep.points {id, delta}`: `delta` to liczba całkowita ≠ 0, |delta| ≤ 1 000 000 → `{ok:true, points}`.
- `ADM` `admin.postep.grant {id, discovery}`: odkrycie razem z brakującymi wymaganiami, za darmo → `{ok:true, granted:[id…], message}`.

Wszystkie mutacje trafiają do audytu i do powiadomień w grze (`messages.notify.postep-*` i `notify.admin-postep-*`).

### API (`PostepApi`, metody `default`)
- `investedToday(kraj)`
- `discover(kraj, id, byPlayer)` → `DiscoverResult(ok, status, discovery, missingPoints, message)`. Statusy: `UNLOCKED | TARGET_SET | ALREADY_OWNED | LATER_ERA | UNKNOWN | NO_COUNTRY | UNSUPPORTED`.
- `setEra(kraj, era)`
- `addResearchPoints(kraj, delta)`
- `grantDiscovery(kraj, id)`

### Strona
Zakładka **„Postęp”** jest w widoku kraju (`#/kraj/<id>/postep`) i w widoku państwa NPC (`#/npc/<id>/postep`). Zawiera:
- kartę epoki: plakietka, opis, pasek postępu do następnej epoki, punkty, przyrost dzienny, odkrycia, czas w epoce;
- kartę „czego brakuje”;
- kartę celu badań ze ścieżką i szacowanym czasem;
- **drzewko odkryć** w czterech kolumnach epok:
  - kolor lewej krawędzi oznacza kategorię, ikona status, a ★ odkrycie wymagane do epoki;
  - kliknięcie otwiera szczegóły z przyciskiem „Odkryj teraz” albo „Ustaw jako cel badań”;
- inwestycję z szybkim wyborem kwoty i podglądem punktów;
- ranking epok;
- narzędzia admina.

W nagłówku kraju jest plakietka epoki, a ranking krajów ma kolumnę „Epoka” z sortowaniem. Zrzuty ekranu E2E mają prefiks `80-`…`88-`.

## Edytor planu państwa NPC (zamówione 2026-10-26)
Admin / zarządca rysuje w panelu, jak ma wyglądać państwo NPC; Ludnosc buduje według tego planu (plan wytyczny ma pierwszeństwo przed automatycznym planerem).

### Podkład mapy
- RPC `admin.npc.mapRender {id, margin?}` → plugin Panel renderuje **widok z góry** terytorium kraju (+ margines, domyślnie 32 bl.) do PNG (1 px = 1 blok; kolor = najwyższy blok wg mapy kolorów materiałów, z cieniowaniem wysokości), zwraca `{png: base64, originX, originZ, width, height, chunks:[[cx,cz]...], buildings:[{id,type,minX,minZ,maxX,maxZ}], roads:[[x,z]...]}`. Limit rozmiaru (np. 768×768); asynchronicznie przez snapshoty chunków (niezaładowane chunki ładowane async, z limitem).

### Format planu (JSON, wersja 1) — kontrakt Panel ↔ Ludnosc
```json
{
  "version": 1,
  "zones":   [ { "type": "mieszkalna|centrum|rzemieslnicza|rolnicza|las|kopalnia|port|obronna|park|zabytki|nie_ruszac",
                 "polygon": [[x,z],[x,z],...] } ],
  "roads":   [ { "kind": "glowna|boczna|trakt", "points": [[x,z],...], "width": 3 } ],
  "walls":   [ { "points": [[x,z],...], "closed": true, "gates": [[x,z],...] },
               { "mode": "follow-border", "inset": 2 },
               { "mode": "follow-town", "inset": 2 } ],
  "buildings": [ { "type": "palac", "x": 120, "z": -340, "rotation": 90, "priority": 1 } ],
  "options": { "allowDemolish": true, "keepExisting": true, "autoFill": true }
}
```
- `zones` ograniczają planer: w strefie mieszkalnej tylko domy/kamienice/bloki, w rolniczej pola i spichlerze, w `las` gospodarka leśna (drwale) i zakaz zabudowy, `nie_ruszac` = planer nic tam nie robi (ani wycinki, ani niwelacji), `zabytki` = nic nie wyburza.
- `roads` / `walls` = dokładne trasy (planer dopasowuje do terenu, ale trzyma przebieg); bramy w podanych punktach.
- `walls[].mode` (zamiast `points`, Ludnosc, 2026-10-26): `"follow-border"` = mur automatycznie wzdłuż granicy kraju, `inset` bloków (0–7) w głąb od krawędzi chunków granicznych; `"follow-town"` = mur wokół zabudowy (wypukła otoczka budynków + `inset`+4, po grzbietach, z pominięciem rzek/jezior/klifów, bramy na każdej przecinanej drodze). Oba tryby przeliczają się, gdy kraj/miasto się zmienia. `gates` działa także z trybami (dodatkowe bramy).
- Ostrzeżenia `setDesignPlan` (po polsku, jedno na element): `budynek #N (typ): obrys wychodzi poza ziemię kraju | koliduje z istniejącym budynkiem | koliduje z innym budynkiem planu | wymagania niespełnione (...) — poczeka | stoi w strefie nie_ruszac`, `droga/mur #N: K bl. na obcej ziemi — pominięte`, `strefa #N: nieznany typ ...`, `nieznany typ budynku`, `obrót musi być wielokrotnością 90`.
- `designPlanPreview` JSON: `{orders:[{type,x,z,rotation,priority,cost,status: czeka|zlecony|odrzucony}], demolitions:[{id,type}], roadMetres, wallMetres, totalCost, warnings:[...]}`.
- `buildings` = konkretne budynki w konkretnych miejscach (wchodzą do kolejki w kolejności `priority`; walidacja ziemi/kolizji; błąd raportowany per pozycja).
- `autoFill` = poza tym, co narysowane, planer może sam dopełniać strefy zgodnie z ich typem.
- Współrzędne blokowe świata kraju; tylko na ziemi kraju (reszta ignorowana z ostrzeżeniem).

### RPC
`admin.npc.getPlan {id}` → plan albo null · `admin.npc.setPlan {id, plan}` → walidacja + `{ok, warnings:[...]}` · `admin.npc.clearPlan {id}` · `admin.npc.planPreview {id}` → co planer zrobi (lista zleceń, rozbiórek, kosztów).
### API Ludnosc (default)
`setDesignPlan(UUID, String json)` → `List<String>` ostrzeżenia (pusta = OK; null = nieobsługiwane), `designPlan(UUID)` → `Optional<String>`, `clearDesignPlan(UUID)`, `designPlanPreview(UUID)` → `Optional<String>` (JSON podsumowania).

### Kształty odpowiedzi edytora (plugin Panel + SPA, 2026-10-26)
Dostęp do wszystkich metod jak `admin.npc.*`: ADM, NPCM globalny, zarządca przypisany do `id`; tylko kraje NPC. Mutacje (`setPlan`, `clearPlan`, `territory`) trafiają do audytu i do dziennika AI (kategoria BUILD/OTHER).
Zakładka **„Plan”** jest w widoku państwa NPC: `#/npc/<id>/plan` (między „Budowy” a „Armia”).

**Konwencje planu (Panel ↔ Ludnosc)**
- Punkty `[x,z]` to współrzędne bloków (liczby całkowite). `buildings[].x/z` to **środek obrysu**; obrys: szerokość szablonu wzdłuż X przy obrocie 0/180, zamieniona z głębokością przy 90/270, `minX = x − ⌊w/2⌋` (tak samo jak `DesignService.footprint` i `Placement.centered` w Ludnosc). Front (drzwi) przy obrocie 0 = południe.
- `priority` 1..100 (1 = najpierw), domyślnie 50. `rotation` 0|90|180|270, domyślnie 0. `roads[].width` 1..9, domyślnie główna 5 / boczna 3 / trakt 2.
- `walls[].mode`: `points` (domyślnie; `points` ≥ 2, przy `closed:true` ≥ 3; bramy najwyżej 2,5 bl. od linii muru) albo `follow-border` / `follow-town` (`points: []`, `closed: true`, `inset` 0..7, domyślnie 2; najwyżej jeden mur każdego trybu; bramy opcjonalne). Podgląd na płótnie: granica kraju przesunięta o `inset` do środka, a dla `follow-town` otoczka budynków z odstępem `max(2, inset+4)` (jak `WallRoute` w Ludnosc).
- `options` domyślnie `{allowDemolish:false, keepExisting:true, autoFill:true}`.
- Limity (Panel odrzuca): 200 stref, 200 dróg, 50 murów, 500 budynków, 256 punktów na element, 20 000 punktów razem, 32 bramy na mur. Punkty dalej niż `plan.margin` (32 bl.) od ziemi kraju = błąd; poza ziemią, ale w marginesie = ostrzeżenie. Typy budynków muszą być w `buildingRequirements` (gdy lista pusta — w `buildingTypes()`).
- Panel wysyła do `setDesignPlan` **kanoniczny** JSON: tylko znane pola, liczby całkowite, uzupełnione wartości domyślne.

**`admin.npc.mapRender {id, margin?, fresh?}`** (odczyt, odpowiedź asynchroniczna)
```json
{"id":"…","world":"world","png":"<base64>","originX":1312,"originZ":-416,"width":208,"height":176,"scale":1,"margin":32,
 "complete":true,"loadedChunks":143,"totalChunks":143,"renderedAt":1761504800000,"cached":false,
 "name":"Księstwo Mazowsze","color":"#5E7C16","chunks":[[90,-19]],"capital":{"cx":90,"cz":-19,"x":1448,"z":-296},
 "buildings":[{"id":"dom@1420,-312","type":"dom","name":"Dom","function":"mieszkanie","x":1420,"z":-312,"minX":1415,"minZ":-316,"maxX":1425,"maxZ":-308,"approx":true,"demolish":false}],
 "sites":[{"type":"wieza","name":"Wieża strażnicza","percent":82,"x":1460,"z":-330,"minX":1457,"minZ":-333,"maxX":1463,"maxZ":-327,"approx":true}],
 "roads":[],
 "others":[{"id":"…","name":"Królestwo Polan","color":"#B02E26","npc":false,"chunks":[[96,-20]]}],
 "protected":[{"id":"spawn","label":"Spawn","minCX":81,"minCZ":-23,"maxCX":83,"maxCZ":-16}],
 "territory":{"chunks":54,"limit":120,"costBase":50,"costPerChunk":5,"nextCost":320,"treasury":71400,"treasuryText":"714 zł","maxChanges":512}}
```
- Obraz obejmuje ziemię kraju poszerzoną o `margin` bloków (0..128, domyślnie `map-render.margin` = 32; przy więcej niż `max-chunks` chunkach margines maleje). `width`/`height` to piksele, `scale` bloków na piksel (1, 2, 4…): 1 px = 1 blok, a gdy bok przekracza `max-size` (768) albo PNG w base64 > `max-png-kb`, obraz jest zmniejszany 2:1. Blok `(x,z)` leży w pikselu `((x−originX)/scale, (z−originZ)/scale)`; piksele poza renderem są przezroczyste.
- Kolor = `BlockData#getMapColor()` najwyższego widocznego bloku (MapColor.NONE = przezroczysty), cieniowanie jak w mapach vanilla (blok na północ wyżej/równo/niżej → 255/220/180), woda półprzezroczysta nad dnem (im głębiej, tym więcej koloru wody).
- Snapshoty chunków na głównym wątku porcjami (`snapshots-per-tick`); niezaładowane, ale wygenerowane chunki ładowane `getChunkAtAsync(x, z, false)` (`parallel-loads` naraz, `max-async-loads` na mapę; niewygenerowanych nie generuje). Redukcja i PNG poza głównym wątkiem. Gdy praca trwa dłużej niż `wait-ms` (5,5 s), odpowiedź ma `complete:false` (część chunków przezroczysta), a mapa dokańcza się w tle; gotowa jest w pamięci `cache-seconds` (60 s) per kraj. `fresh:true` pomija cache (nie częściej niż co 5 s).
- `buildings`/`sites`: obrysy **przybliżone** (`approx:true`) — `LudnoscInfo` podaje tylko punkt budynku, a rozmiar Panel bierze ze swojej tabeli szablonów (`BuildingCatalog`). `demolish` = w `function` jest „do rozbiórki”.
- `roads` jest puste: `LudnoscApi` nie udostępnia sieci ulic (ścieżki widać na podkładzie).
- `others`: chunki innych krajów w obrębie renderu + 8 chunków; `protected`: obszary chronione Kraje (Panel czyta `plugins/Kraje/protected-areas.json`, bo `KrajeApi` nie ma ich listy).
- `territory.limit` = `max-chunki` AI (Armia) albo `null`; koszt jak w Kraje: `costBase + costPerChunk × liczba chunków` (z `plugins/Kraje/config.yml`, `economy.claim-cost-*`, inaczej `territory.cost-*` z configu Panelu).

**`admin.npc.buildings`** — `types[]` dostaje dwa pola: `size {w, d, source:"catalog"|"default"}` (wymiary szablonu przy obrocie 0; `default` = 9×9, typ spoza tabeli) i `category` (kategoria szablonu Ludnosc: housing, economy, civic, military, defense, religious, representative, food, farm, infrastructure, highrise, other). `buildings[]` dostaje `demolish:boolean`.

**`admin.npc.getPlan {id}`** → `{id, available, supported, plan|null, margin, limits:{zones,roads,walls,buildings,pointsPerElement,points,gates}}`. `available` = jest Ludnosc; `supported` = Ludnosc nadpisuje `setDesignPlan` (starsza wersja ma tylko domyślne metody API → edytor ostrzega, że zapis nie zadziała). DO trzyma ten odczyt w cache (tryb offline).

**`admin.npc.setPlan {id, plan}`** → `{ok:true, message, warnings:[{ref:{kind,index}|null, text}], counts:{zones,roads,walls,buildings}, plan}`.
- `plan` w odpowiedzi to kanoniczna wersja, którą dostał Ludnosc. `ref.kind` ∈ `zones|roads|walls|buildings`, `ref.index` liczony **od 0**.
- Ostrzeżenia Panelu mają postać `buildings[3]: …`; ostrzeżenia Ludnosc `budynek #4 (dom): …`, `droga #1: …`, `mur #2: …`, `strefa #3: …` (numery od 1) Panel przelicza na `ref`; inne zostają z `ref:null`.
- Błędy walidacji → `{ok:false, error:"Plan ma błędy (N): zones[0]: …; buildings[4]: …"}` (do 8 pierwszych); SPA zaznacza elementy z tych odwołań. `setDesignPlan` = `null` → `{ok:false, error:"Zainstalowana wersja pluginu Ludnosc nie obsługuje planów państwa — zaktualizuj Ludnosc."}`.
- Worker przyjmuje ciało RPC do 256 KB tylko dla `admin.npc.setPlan` (inne metody: 16 KB → 413).

**`admin.npc.clearPlan {id}`** → `{ok:true, cleared, message}` (`cleared:false` = nie było planu); bez obsługi w Ludnosc → błąd jak wyżej.

**`admin.npc.planPreview {id}`** → `{id, supported, available, preview|null}`; `preview` to sparsowany JSON `designPlanPreview` (kształt opisany wyżej przez Ludnosc). Podgląd dotyczy planu **zapisanego**; SPA przy niezapisanych zmianach proponuje „Zapisz i odśwież podgląd” i dolicza własny szacunek kosztu budynków z planu.

**`admin.npc.territory {id, add:[[cx,cz],…], remove:[[cx,cz],…], free:boolean}`** (narzędzie „Teren”; najwyżej 512 zmian razem)
```json
{"ok":true,"message":"Teren: +24 / −3 chunków, pominięto 2, koszt 14 zł.","added":24,"removed":3,
 "skipped":[{"cx":96,"cz":-20,"code":"FOREIGN","reason":"należy do kraju Królestwo Polan"}],
 "cost":1400,"costText":"14 zł","free":false,"chunks":75}
```
- Kody `skipped[].code`: `FOREIGN` (cudzy kraj), `PROTECTED` (obszar chroniony), `NOT_ADJACENT` (dodany chunk nie łączy się krawędzią ze stolicą przez ziemię kraju), `ALREADY_OWN`, `NOT_OWN`, `CAPITAL` (stolicy nie można usunąć), `DISCONNECTS` (usunięcie rozdzieliłoby kraj), `CONFLICT` (ten sam chunk w `add` i `remove`), albo status `KrajeResult` przy odmowie Kraje.
- Panel sprawdza to sam, bo `ClaimCause.ADMIN` w Kraje pomija sąsiedztwo, obszary chronione i spójność; potem wykonuje `claimChunk`/`unclaimChunk(…, ClaimCause.ADMIN)` na głównym wątku porcjami (`territory.changes-per-tick`, najpierw zajęcia, potem zwolnienia). Odpowiedź przychodzi po ostatniej porcji.
- `free:false`: koszt jak w Kraje (rosnący chunk po chunku) pobierany z góry ze skarbca NPC, za niezajęte chunki zwrot; za mało pieniędzy → błąd bez zmian. Po zmianie Panel unieważnia cache mapy; SPA odświeża podkład (`fresh:true`).

**Strona (SPA)**: płótno (zoom kółkiem/szczypaniem, przesuwanie, siatka chunków, granica kraju, cudze kraje i obszary chronione, istniejące budynki z etykietami, współrzędne pod kursorem), narzędzia Zaznacz / Strefa (wielokąt albo prostokąty) / Droga / Mur (Ręczny, Wzdłuż granicy, Wokół miasta; „Bramy”) / Budynek (lista typów z ikoną kategorii, kosztem i rozmiarem; R obraca) / Gumka / Teren (pędzel, prostokąt, wypełnianie, „Poszerz o 1 pierścień”; Shift odwraca dodaj/usuń) / Przesuń mapę, cofnij/ponów (Ctrl+Z, Ctrl+Y), panel boczny (lista elementów z błędami i ostrzeżeniami, właściwości zaznaczonego, opcje planu, legenda), podgląd, zapis, czyszczenie, eksport/import JSON (`.json`, także obiekt `{plan:…}`), szkic w `localStorage` (`panel-plan-draft:<id>`). Telefon: przełącznik „Tryb: przesuwanie / rysowanie” i pasek „Zakończ / Zamknij mur / Cofnij punkt / Anuluj”. Zrzuty E2E `90-`…`98-`.

## Nastrój mieszkańców (plugin Zycie) w panelu (dodane 2026-10-26)
Zycie jest opcjonalne (`softdepend`); Panel pobiera `ZycieApi` leniwie z ServicesManager (jak Postep, z obsługą `LinkageError`).
- `country.get` (część publiczna, każdy kraj) ma pole `mood` albo `null` (bez Zycie):
  ```json
  {"value":31,"label":"Niezadowolenie","factors":[{"key":"wojna","label":"Wojna","value":-14},{"key":"jedzenie","label":"Jedzenie","value":8}],
   "concerns":["Synowie idą na wojnę z Polanami"],"multiplier":0.82,"festival":false,"unrest":true}
  ```
  `value` 0..100; `label`: ≥80 Zachwyt, ≥65 Zadowolenie, ≥50 Spokój, ≥35 Niepokój, ≥20 Niezadowolenie, niżej Bunt. `factors` bez zer, od największego wpływu; `label` czynnika = klucz z wielkiej litery. `multiplier` zaokrąglony do 0,01.
- `admin.npc.festival {id}` (jak `admin.npc.*`, audyt): wykonuje z konsoli `zycie admin swieto <nazwa kraju>` (nazwa ze spacją w cudzysłowie) → `{ok:true, festival, message}`. Błędy: brak Zycie, festyn już trwa, komenda nie zadziałała.
- Strona: kafelek „Nastrój” (wartość, etykieta, plakietki „Festyn”/„Protest”) i karta „Nastrój mieszkańców” (pasek 0–100, czynniki +/−, zmartwienia, mnożnik) w zakładce Przegląd kraju i państwa NPC; w państwie NPC przycisk „Ogłoś festyn”. Zrzuty `15-`, `73-`.

## Wymuszenia budowy państwa NPC (dodane 2026-10-26)
Metody (jak `admin.npc.*`, audyt, wpis w dzienniku AI) wołają `LudnoscApi` na głównym wątku:
- `admin.npc.forceRedesign {id, free?}` → `forceRedesign(id, free)` → `{ok:true, orders, free, message}`; wynik ujemny → błąd z powodem z `lastPlanFailure`.
- `admin.npc.forceRoads {id}` → `forceRoads(id)` → `{ok:true, message}`; `false` → błąd.
- `admin.npc.forceModernize {id}` → `forceModernize(id)` → `{ok:true, orders, message}`.
- Strona (zakładka Budowy): karta „Wymuszenia budowy” — przebudowa miasta z potwierdzeniem i podglądem z `admin.npc.planPreview` (albo komunikatem, gdy podglądu nie ma), opcja „Darmowo”; „Przebuduj drogi pod epokę” i „Modernizuj wszystkie budynki” z potwierdzeniem. Budynki z dopiskiem „do rozbiórki (plan v2)” mają plakietkę. Zrzut `74-`.

## Wydajność serwera (dodane 2026-10-27)
Plugin Panel co `health.interval-seconds` (domyślnie 10 s) na głównym wątku mierzy: TPS (`Bukkit.getTPS()`, średnia 1/5/15 min, obcięta do 20), MSPT (`getAverageTickTime()`), graczy online, załadowane chunki i encje każdego świata oraz **NPC pluginów MEDIVALRPG** po znacznikach PDC (co `npc-scan-every` pomiarów; przegląda `World.getEntities()` bez graczy, czas skanu widać w panelu):

| plugin | liczone jako NPC | encje pomocnicze (`helpers`, nie NPC) | kraj |
|---|---|---|---|
| Ludnosc | `ludnosc:country` (mieszkaniec-wieśniak), `ludnosc:builder` | `ludnosc:site_hologram`, `ludnosc:transport_visual` | `ludnosc:country` |
| Armia | `armia:part = brain` (jeden na żołnierza; stare encje: `armia:soldier` bez `part`), `armia:part = ruler` | `part` = `body`, `ruler_hitbox`, `arrow`, `siege_model`, `siege_shot` | `armia:country` |
| Skarbiec | `skarbiec:npc` (bankier, kupiec) | `skarbiec:npc_role = hitbox` | — |
| Wladcy | każda encja z kluczem `wladcy:*` (dziś Władcy nie tworzy encji; władcy NPC to Armia) | — | — |
| Zycie | `zycie:levy`, `zycie:fire_bucket` (wieśniak na służbie, gdy nie jest mieszkańcem) | `zycie:bubble`, `zycie:firework` | — |
| Kolej | `kolej:works_builder` STRING na Mannequin = budowniczy; `kolej:train-entity` STRING bez `:` na Mannequin = maszynista | `train-entity` na ArmorStand (np. `trainId:seatIndex`, również `:-1`) i inne oznaczone wizualizacje | — |
| Szlaki | `szlaki:shipment` STRING na Mannequin = woźnica/szyper, na DONKEY = zwierzę transportowe | oznaczona tym samym kluczem łódź OAK_BOAT i ITEM_DISPLAY ładunku | — |

Jedna encja liczy się raz, w kolejności Armia → Skarbiec → Ludnosc → Wladcy → Zycie → Kolej → Szlaki. Liczone są tylko encje w załadowanych chunkach. `plugins` i mapa `npc` obejmują także `kolej` i `szlaki`. `kinds` ma dodatkowe, zgodne wstecz pola `railBuilders`, `trainDrivers`, `freightDrivers`, `freightAnimals`; pojazdy i siedzenia zwiększają wyłącznie `helpers`, nie `npcTotal`. Transport nie jest przypisywany do kraju, bo te znaczniki nie zawierają identyfikatora kraju. Progi `npc-plugin.kolej` i `npc-plugin.szlaki` można dodać opcjonalnie.

- **Historia**: pomiary z ostatnich `fine-minutes` (60) w pełnej rozdzielczości i agregaty minutowe z `coarse-hours` (24) — średnia TPS/MSPT, najgorsza wartość (`tpsMin`, `msptMax`), maksimum liczników, suma ostrzeżeń. Zapis `plugins/Panel/health.json` (tmp + atomowa podmiana + `.bak`) co `save-minutes` (10) w tle i przy wyłączeniu; po starcie wczytywany (przeterminowane punkty odpadają).
- **Ostrzeżenia** (`health.thresholds`, 0 = wyłączony): `TPS` poniżej `tps` (18) / `tps-critical` (15), `MSPT` powyżej `mspt` (45) / `mspt-critical` (50), `NPC` powyżej `npc` (2000) / `npc-critical` (3000), `ENTITIES` powyżej `entities` (8000) / `entities-critical` (12000), `NPC_<PLUGIN>` powyżej `npc-plugin.<id>` (domyślnie `armia: 1000`). Ostrzeżenie włącza się po `sustain-samples` (2) kolejnych pomiarach ponad progiem, kończy przy pierwszym w normie. Każdy punkt ma `warn:[kody]`; każdy okres ostrzeżenia to epizod (24 h, najwyżej 200).
- **Komunikaty w grze** (`health.alerts`): przy nowym ostrzeżeniu i przy eskalacji do krytycznego — gracze z uprawnieniem `permission` (`panel.health.alerts`, domyślnie op) i (z `panel-admins: true`) admini panelu online; najwyżej raz na `cooldown-minutes` (15) na kod i poziom; `recovery: true` = komunikat o powrocie do normy (tylko po wysłanym ostrzeżeniu); `console: true` = wpis w logu. Komunikaty `messages.health-*`.
- **W grze**: `/panel wydajnosc` (admin panelu, konsola albo `panel.health.alerts`) — ostatni pomiar i aktywne ostrzeżenia. Propozycja przycisku w menu admina: `panel/MENU-ADDITIONS.yml`.

### `ADM` `admin.health {range?}` (odczyt, bez audytu i bez cache offline)

Autoryzacja: Worker uwierzytelnia połączenie pluginu sekretem Bearer, żądania WWW wymagają sesji i ochrony POST. Plugin przy każdym RPC odtwarza role użytkownika i wymaga `Requirement.ADMIN`; zarządca NPC ani samo uprawnienie do alertów w grze nie dają dostępu do RPC. DO nie zapisuje ani nie zwraca cache `admin.health`, również gdy w magazynie została starsza kopia: przy niedostępnym pluginie nie może zweryfikować odebrania roli administratora. SPA również nie zapisuje ani nie używa lokalnego cache tej metody; usuwa starszy wpis danego zakresu podczas odczytu.

Pomiary Bukkit i odczyty PDC wykonują się na głównym wątku. RPC czyta gotową historię, nie skanuje świata. Kopie historii i epizodów powstają przed zapisem; serializacja JSON oraz operacje tmp/atomowa podmiana/`.bak` wykonują się w pojedynczej kolejce I/O. Końcowy zapis trafia za wcześniejszymi, aby stara kopia go nie nadpisała. Oczekiwanie na opróżnienie kolejki przy wyłączeniu trwa do 5 s; wczytanie historii podczas startu pozostaje synchroniczne.

Progi zwykłe i krytyczne są niezależne: zero wyłącza wyłącznie dany próg. Brak pierwszego pomiaru, wyłączone pomiary lub dane archiwalne nie pokazują komunikatu „Wszystko w normie”. Wykres encji pokazuje oba progi.

Koszt skanu NPC jest liniowy względem liczby załadowanych encji; `npc-scan-every` zmniejsza częstotliwość skanów, a `scanMs` i `scannedAt` wskazują koszt i wiek ostatniego wyniku. Skan nie ładuje chunków, ale nie jest rozłożony na wiele ticków. Nie wykonywano pomiaru obciążenia na działającym serwerze.
`range`: `"1h"` (domyślnie; pomiary co `intervalMs`) | `"6h"` (agregaty minutowe) | `"24h"` (agregaty minutowe scalane do `max-points` = 720, czyli 2 min). Inna wartość → błąd walidacji.
```json
{"enabled":true,"intervalMs":10000,"npcScan":true,
 "range":"1h","resolutionMs":10000,"from":1761501200000,"to":1761504800000,
 "points":[{"t":1761504790000,"tps":19.87,"tpsMin":19.87,"mspt":31.2,"msptMax":31.2,"players":9,"chunks":2530,"entities":5238,
            "npcTotal":1673,"npc":{"ludnosc":616,"armia":1045,"skarbiec":6,"wladcy":0,"zycie":6},"helpers":1021,"warn":["NPC_ARMIA"]}],
 "current":{"t":1761504796000,"tps":19.7,"tps5":19.7,"tps15":18.9,"mspt":34.1,"players":9,"maxPlayers":40,"chunks":2530,"entities":5238,
            "npcScan":true,"npcTotal":1673,"npc":{"ludnosc":616,"armia":1045,"skarbiec":6,"wladcy":0,"zycie":6},
            "kinds":{"citizens":607,"builders":9,"soldiers":1038,"rulers":7,"merchants":6,"wladcy":0,"onDuty":6,"helpers":1021},"helpers":1021,
            "worlds":[{"name":"world","environment":"NORMAL","chunks":2320,"entities":4978,"players":8,"npc":1670}],
            "countries":[{"id":"…","name":"Księstwo Mazowsze","color":"#5E7C16","npc":true,"exists":true,"total":702,"citizens":241,"soldiers":460,"rulers":1}],
            "countriesTotal":4,"scanMs":3.42,"scannedAt":1761504796000},
 "plugins":[{"id":"ludnosc","name":"Ludność"},{"id":"armia","name":"Armia"},{"id":"skarbiec","name":"Skarbiec"},{"id":"wladcy","name":"Władcy"},{"id":"zycie","name":"Życie"}],
 "thresholds":{"tps":18.0,"tpsCritical":15.0,"mspt":45.0,"msptCritical":50.0,"npc":2000,"npcCritical":3000,"entities":8000,"entitiesCritical":12000,"npcPlugin":{"armia":1000},"sustain":2},
 "warnings":[{"code":"NPC_ARMIA","level":"WARN","text":"Dużo NPC (Armia): 1045 (próg 1000)","value":1045.0,"threshold":1000.0,"since":1761504260000}],
 "episodes":[{"code":"TPS","level":"CRITICAL","text":"Niski TPS: 12,4 (próg 15,0)","start":1761502400000,"end":1761502760000,"worst":12.4}]}
```
- `points` rosną po `t`; dla agregatów `t` = początek przedziału, `tps`/`mspt` = średnie, `tpsMin`/`msptMax` = najgorsze, liczniki = maksima, `warn` = suma kodów. `npc` ma klucze z `plugins` (zera włącznie). Przy `npcScan:false` NPC są puste/0.
- `current` = ostatni pomiar albo `null` (tuż po starcie). `countries` (najwyżej `country-limit` = 50, od największego `total`) liczy mieszkańców Ludnosc i żołnierzy/władców Armii; `exists:false` = kraj już usunięty (`name: null`). `kinds`: liczby według rodzaju (`helpers` = encje pomocnicze).
- `warnings` = aktywne teraz (`level` `WARN|CRITICAL`, `since` = początek), `episodes` = ostatnie 50 epizodów z doby, od najnowszych (`end: null` = trwa). Kody: `TPS | MSPT | NPC | ENTITIES | NPC_<PLUGIN>`.

### Strona
Administracja → zakładka **„Wydajność serwera”** (`#/admin/serwer?zakres=1h|6h|24h`, także osobna pozycja menu bocznego; tylko ADM): przełącznik zakresu, baner ostrzeżeń (albo „Wszystko w normie” z progami), 6 kafelków (TPS z 5/15 min, MSPT, gracze, NPC pluginów z rozbiciem, encje, chunki; przekroczony próg = obramowanie + ikona + słowo „ostrzeżenie/krytyczne”), wykresy TPS / MSPT / graczy / NPC razem / encji / chunków (oś godzin, przerywane linie progów, pasma okresów ostrzeżeń, wspólny kursor i podpowiedź z min/maks agregatu), NPC według pluginów (wykres na plugin z NPC; pluginy bez NPC wymienione pod spodem), NPC teraz (rodzaje, światy, kraje) i tabela epizodów z doby. Odświeżanie co 30 s jak cały panel. Zrzuty E2E `30-`…`33-`.

## Statystyki Skarbu Świata — tylko WorldSteward (przygotowane 2026-10-29, nieaktywne)

Strona `#/skarbswiata` ma zakładki Zarząd, Bilans, Konta, Przepływy, Monety, Ruchy monet i Kupiec, a także widok `#/skarbswiata/konto/<konto>`.
**Autoryzacja przy każdym odczycie, po stronie serwera, dwukrotnie:**
1. Plugin Panel (`WorldMethods.stats`) pyta Skarbiec `worldMoneyManager(uuid)`.
2. Skarbiec (`WorldStats.requireOwner`) ponownie porównuje UUID z `WorldMoney.MANAGER`, zanim odczyta jakiekolwiek dane.

Admin panelu, op, zarządca NPC i władca dostają `Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.`
Ogólna bramka `MethodSpec` to `LOGGED_IN`, tak samo jak dla `world.get`. Metody `world.*` nie trafiają do cache DO (`CACHEABLE`) ani do sessionStorage SPA.
Ciężkie odczyty (dzienniki, pliki) idą w tle; plugin zwraca `CompletableFuture` domykany na głównym wątku.
`world.stats.census` jest mutacją: trafia do audytu i do limitu zmian.

Monety nie mają numerów seryjnych (decyzja użytkownika). Ruchy stosów zapisuje dziennik `plugins/Skarbiec/stats/ruchy/ruchy-RRRR-MM-DD.log`.
Spis monet trafia do `plugins/Skarbiec/stats/census.json`, a pamięć podręczna plików świata do `census-cache.json`.

Wszystkie metody: tylko zarządca osobisty WorldSteward (UUID ff923cf7-f43c-37b8-85a3-77cbac357c9c).
Plugin Panel przekazuje je do Skarbca, który przy KAŻDYM wywołaniu sprawdza UUID. Admin panelu, op, władca → błąd
`{ok:false, error:"Tylko WorldSteward ma dostęp do statystyk Skarbu Świata."}`.
Wszystkie są `world.*`, więc: DO ich nie cache'uje (nie ma ich w CACHEABLE), SPA `api.read` ich nie zapisuje w sessionStorage
(warunek `!method.startsWith("world.")` w api.js już to robi). Kwoty zawsze w miedziakach (md, long). Czasy ms epoch.
Typy kont (`type`): reserve, cash, payments, merchant, households, players, countries, firms, railBudgets, escrow, server, other.
Etykiety typów przychodzą w `typeLabels` (summary/flows/accounts) — SPA może mieć własny słownik zapasowy.

### world.stats.summary {} → bilans
```json
{"generatedAt":1761700000000,"cap":100000000,"total":100000000,"difference":0,"closed":true,"seq":18342,
 "categories":[{"key":"reserve","label":"Rezerwa Skarbu Świata","amount":78900000,"accounts":1,"share":0.789},
               {"key":"players","label":"Konta graczy","amount":250000,"accounts":6,"share":0.0025}],
 "checks":[{"key":"sum","level":"OK","text":"Suma wszystkich kont = 100 000 000 md (pula)","expected":100000000,"actual":100000000,"difference":0},
           {"key":"census","level":"WARN","text":"Spis monet różni się od pokrycia o 120 md","expected":31085,"actual":30965,"difference":-120}],
 "census":{"status":"DONE","startedAt":1761690000000,"finishedAt":1761690090000,"total":30965,"cashAccount":31085,"difference":-120,"progress":1},
 "typeLabels":{"reserve":"Rezerwa Skarbu Świata","players":"Konta graczy"}}
```
- `level`: `OK | WARN | ERROR`. Każda rozbieżność (level≠OK) ma być wyraźnie oznaczona (czerwony/żółty baner + ikona + słowo).
- `categories` zawiera wszystkie 12 typów (także z 0), w stałej kolejności.
- `census.status`: `NONE | RUNNING | DONE | FAILED`; `difference = total - cashAccount` (null gdy brak spisu).

### world.stats.accounts {} → wszystkie konta
```json
{"generatedAt":…,"total":100000000,"count":41,"window":{"from":…,"to":…,"days":7},"typeLabels":{…},
 "accounts":[{"account":"player:6a0…","type":"players","typeLabel":"Konta graczy","owner":"Bolko","ownerId":"6a0…",
              "balance":12345,"share":0.000123,"lastActivity":1761699000000,"count7d":12,"in7d":5000,"out7d":3000}]}
```
- Pełna lista (≤ 10 000), bez stronicowania; sortowanie/filtr/wyszukiwanie po stronie SPA (konto, właściciel, typ, saldo, aktywność).
- `owner` może być null (konta techniczne mają opisową nazwę w `owner`, np. „Rezerwa”). `lastActivity` null = brak ruchu w retencji logów.

### world.stats.flows {from?, to?, account?, category?, type?, query?, minAmount?, offset?, limit?}
Parametry (wszystkie opcjonalne, wysyłane jako liczby albo stringi):
- `from`,`to` ms (domyślnie ostatnie 7 dni; zakres najwyżej do retencji logów, `to` ≤ teraz),
- `account` dokładny id konta (pasuje do nadawcy lub odbiorcy), `category` klucz kategorii, `type` typ konta (nadawca lub odbiorca),
- `query` do 64 znaków (podciąg w powodzie, kontach i nazwach, bez wielkości liter), `minAmount` md ≥ 0,
- `offset` ≥ 0, `limit` 1..1000 (domyślnie 200).
```json
{"from":…,"to":…,"retentionDays":90,"oldest":1761300000000,"total":5230,"offset":0,"limit":200,"sumAmount":8812345,
 "rows":[{"seq":18342,"time":…,"type":"TRANSFER","from":"country:…","fromLabel":"Królestwo Polan","fromType":"countries",
          "to":"world:payments","toLabel":"Budżet operacyjny","toType":"payments","amount":930,"fromAfter":47320,"toAfter":1200,
          "reason":"Armia: żołd 12 żołnierzy","category":"wages","categoryLabel":"Żołd","plugin":"Armia"}],
 "daily":[{"day":"2026-10-28","t":1761602400000,"count":812,"volume":1234567,"in":40000,"out":38000,"byCategory":{"wages":1350,"tax":2200}}],
 "categories":[{"key":"wages","label":"Żołd","count":27,"volume":1350}],
 "matrix":[{"from":"countries","to":"payments","amount":1350,"count":27}],
 "topSenders":[{"account":"country:…","label":"Królestwo Polan","type":"countries","amount":5000,"count":30}],
 "topReceivers":[…],
 "plugins":[{"plugin":"Armia","count":120,"volume":34000}],
 "categoryLabels":{"wages":"Żołd"},"typeLabels":{…}}
```
- `rows` od najnowszych, po filtrach i stronicowaniu; `total`, agregaty (`daily`, `categories`, `matrix`, `top*`, `plugins`, `sumAmount`) liczone ze WSZYSTKICH pasujących wierszy (przed `offset/limit`).
- `type`: `TRANSFER | DEPOSIT | WITHDRAW | SET` (DEPOSIT/WITHDRAW/SET to stare operacje sprzed zamkniętej puli; `from`/`to` może być null → etykieta „(emisja)” / „(umorzenie)”).
- `daily[].in`/`out`: gdy podano `account` — wpływy/wypływy tego konta; bez `account` — `in` = przepływ z budżetów świata (reserve, payments, merchant, households) do gospodarki, `out` = z gospodarki z powrotem do budżetów świata. `t` = północ dnia (Europe/Warsaw).
- `matrix` = suma kwot między typami kont (do diagramu Sankey/macierzy), `from != to` i też `from == to` (np. players→players).
- Kategorie (klucze): tax (Podatki), npcIncome (Dochód państw NPC), wages (Żołd), army (Wojsko i rekrutacja), construction (Budowy i naprawy), land (Ziemia i granice), research (Badania), trade (Handel i sklepy), merchant (Kupiec), transfer (Przelewy graczy), treasury (Skarbce krajów — wpłaty/wypłaty), cash (Monety: wpłaty i wypłaty), railway (Kolej), caravans (Szlaki i karawany), quests (Zlecenia władców), tournaments (Turnieje), diplomacy (Trybuty i wojny), world (Skarb Świata — przydziały), admin (Korekty administracyjne), other (Inne).
- Eksport CSV robi SPA: kolejne strony `limit:1000` (najwyżej 50 000 wierszy), z ochroną przed wstrzyknięciem formuł (=,+,-,@ → prefiks ').

### world.stats.account {account, days?}
`days` 1..90 (domyślnie 30).
```json
{"account":"player:…","label":"Bolko","type":"players","typeLabel":"Konta graczy","owner":"Bolko","balance":12345,
 "days":30,"from":…,"to":…,"in":5000,"out":3000,"count":42,
 "points":[{"t":…,"balance":10000}],
 "categories":[{"key":"trade","label":"Handel i sklepy","in":2000,"out":500,"count":9}],
 "counterparties":[{"account":"country:…","label":"Królestwo Polan","type":"countries","in":0,"out":930,"count":3}],
 "rows":[ …jak flows.rows, najwyżej 200 najnowszych… ]}
```
- `points` rosnąco po `t` (saldo po każdej operacji, najwyżej 500 punktów; pierwszy punkt = saldo na początku okna, ostatni = obecne saldo).

### world.stats.coins {} → ostatni spis monet fizycznych
```json
{"status":"DONE","startedAt":…,"finishedAt":…,"progress":1,"phase":"Gotowe","durationMs":91234,
 "cashAccount":31085,"total":30965,"difference":-120,"stacks":18,
 "byDenomination":{"copper":65,"silver":90,"gold":300},
 "byKind":[{"kind":"CONTAINER","label":"Skrzynie i kontenery","amount":30800,"stacks":12,"places":2}],
 "locations":[{"kind":"CONTAINER","label":"Skrzynia (CHEST)","owner":null,"ownerId":null,"world":"world","x":12,"y":64,"z":-30,
               "amount":30800,"stacks":12,"copper":0,"silver":80,"gold":300,"source":"DISK"}],
 "truncated":false,"filesScanned":962,"filesTotal":962,"chunksLive":2530,"playersOnline":3,"playersOffline":9,
 "errors":[{"file":"…/r.0.0.mca","error":"…"}],
 "notes":["Spis na gorąco: …"]}
```
- `kind`: PLAYER_ONLINE (Ekwipunki graczy online), ENDER_ONLINE (Ender chesty graczy online), PLAYER_OFFLINE (Ekwipunki graczy offline),
  ENDER_OFFLINE (Ender chesty graczy offline), CONTAINER (Skrzynie i kontenery), GROUND (Przedmioty na ziemi), ENTITY (Byty: ramki, wózki, zwierzęta), OTHER.
- `source`: LIVE (z pamięci serwera) albo DISK (z pliku regionu / pliku gracza).
- Gracze: `world`/`x,y,z` mogą być null dla graczy offline. `locations` posortowane malejąco po kwocie, najwyżej 2000 (`truncated`).
- Brak spisu: `{"status":"NONE", …zera…, "locations":[]}`. W trakcie: `status:"RUNNING"`, `progress` 0..1, `phase` tekst.

### world.stats.census {} (mutacja, audyt) → rozpoczyna spis
`{"ok":true,"started":true,"message":"Rozpoczęto spis monet…"}` albo `started:false` z komunikatem, gdy spis już trwa.
SPA odpytuje `world.stats.coins` co 3 s, dopóki `status === "RUNNING"`.

### world.stats.trail {from?, to?, player?, kind?, query?, offset?, limit?}
Log ruchów stosów monet fizycznych (bez numerów seryjnych). `player` = nick albo UUID; `kind` jak niżej; `limit` 1..1000 (domyślnie 200).
```json
{"from":…,"to":…,"total":312,"offset":0,"limit":200,"since":1761600000000,"dropped":0,
 "rows":[{"time":…,"kind":"CONTAINER_IN","kindLabel":"Włożenie do pojemnika","player":"Bolko","playerId":"…","amount":500,"stacks":2,
          "world":"world","x":12,"y":64,"z":-30,"target":"Skrzynia (CHEST)","note":""}],
 "byKind":[{"kind":"CONTAINER_IN","label":"Włożenie do pojemnika","count":40,"amount":12000}]}
```
- `kind`: PICKUP (Podniesienie z ziemi), DROP (Upuszczenie), DEATH (Wypadnięcie po śmierci), CONTAINER_IN (Włożenie do pojemnika),
  CONTAINER_OUT (Wyjęcie z pojemnika), HOPPER (Lejek / przenoszenie automatyczne), HOPPER_PICKUP (Lejek zebrał z ziemi),
  DEPOSIT (Wpłata monet do banku), WITHDRAW (Wypłata monet z banku), EVENT (Zdarzenie Skarbca: handel, sklep, kupiec).
- DEPOSIT/WITHDRAW pochodzą z księgi (`cash <uuid>: …`), EVENT z dziennika zdarzeń Skarbca.

### world.stats.merchant {} → rynek kupca NPC (budżet, zapas, ceny, historia)
```json
{"enabled":true,"budget":42000,"budgetTarget":50000,"budgetPercent":84,"buying":true,"budgetFactor":92,
 "updatedAt":1761700000000,"periodEndsAt":1761710000000,
 "goods":[{"key":"WHEAT","bundle":16,"stock":120,"target":256,"stockLevel":"LOW","stockLabel":"mało",
           "buyPrice":34,"sellPrice":52,"baseBuy":30,"baseSell":45,"buyPercent":113,"sellPercent":115,
           "trend":"UP","trendPercent":6,"featured":false,"soldToday":64,"boughtToday":16,"restockPerDay":0}],
 "history":[{"t":1761696400000,"budget":41000,"goods":{"WHEAT":{"stock":110,"buy":35,"sell":53}}}],
 "totals":{"stockValue":123400,"paidToday":5600,"receivedToday":2300}}
```
- `stockLevel`: `NONE | LOW | OK | HIGH` (etykiety: brak / mało / w normie / dużo). `trend`: `UP | DOWN | FLAT` (cena skupu względem ~24 h temu).
- `buyPrice` 0 = kupiec obecnie nie skupuje (np. pusty budżet → `buying:false`); `sellPrice` 0 = nie sprzedaje (brak towaru albo nie handluje).
- `history` co godzinę, rosnąco po `t`, najwyżej 168 punktów. `enabled:false` = rynek dynamiczny wyłączony (stare stałe ceny).
- Od Skarbca merchant-release1 (pola dodatkowe, stare bez zmian): w `goods[]` także `category`, `seasons`, `onOffer` (towar jest teraz w ofercie któregoś kupca), `merchants` (id typów, które go mają); `featured` = poszukiwany u któregokolwiek typu; `history` obejmuje tylko towary w ofercie albo na stanie. Nowe `assortment: {enabled, periodHours, period, nextDeliveryAt, season, catalogSize, defaultType, types:[{id,name,catalog,core[],rotating[],seasonal[],featured[],next:{delivery[],featured[]}}]}`.

### world.npcTreasuries {query?} / world.npcTransfer {country, amount, confirm, reason, direction} — przelewy Skarbu Świata do państw NPC
Dostęp ma tylko WorldSteward (UUID sprawdzają i Panel, i Skarbiec). `world.npcTreasuries` to odczyt nieobjęty cache, a `world.npcTransfer` to mutacja z audytem.
- `world.npcTreasuries` → `{"reserve":md,"countries":[{"id","name","color","npc":true,"account":"country:<id>","balance":md}]}`. Lista obejmuje tylko kraje NPC, posortowane po nazwie; `query` ma najwyżej 32 znaki.
- `world.npcTransfer`:
  - `country` to id albo nazwa kraju NPC, `amount` to kwota tekstowa albo md;
  - `confirm` musi być tą samą kwotą w md (potwierdzenie), `reason` ma 3–120 znaków i trafia do księgi;
  - `direction` to `toCountry` (domyślnie, z rezerwy) albo `toReserve` (zwrot do rezerwy);
  - odpowiedź `{"ok":true,"message","amount","direction","country","reserve","balance","seq","durable":true}` przychodzi po `LedgerStore.durable()`;
  - błędy: „Tylko WorldSteward zarządza Skarbem Świata.”, „Za mało środków w rezerwie Skarbu Świata.”, „Za mało środków w skarbcu kraju.”, „Nie ma takiego państwa NPC.”, „Potwierdzona kwota nie zgadza się z kwotą przelewu.”, „Podaj powód przelewu (3–120 znaków).”.
- **Przelew do wszystkich państw NPC** (Skarbiec merchant-release1, ta sama metoda, bez nowych nazw RPC): `world.npcTransfer {direction:"toAllNpc", amount, confirm, count, reason, key, retry?}`.
  - `amount` = kwota dla KAŻDEGO państwa, `confirm` = suma w md (amount × liczba), `count` = liczba państw z podglądu (inna = odmowa „Liczba państw NPC zmieniła się…”), `key` = klucz operacji `[a-z0-9-]{8,64}` (panel: `npcbulk-<16 hex>`, nowy na każdy podgląd).
  - Jedna operacja: rejestr `bank/npc-bulk.json` (PENDING, fsync) → wszystkie przelewy atomowo w księdze (powody z `[npc-bulk:<key>]`) → `durable` → DONE. Ten sam `key` ponownie: `{replayed:true}`, nic nie przelewa. Brak środków na całość / zero państw NPC = odmowa bez przelewów. PENDING z poprzedniej sesji rozstrzyga księga; `retry:"true"` dopłaca tylko brakujące.
  - Odpowiedź: `{ok, key, replayed, state:"DONE", perCountry, count, total, paidNow, reserve, totalMoney, legs:[{country,name,account,amount,balance}], message, durable:true}`.
  - `world.npcTreasuries` dodatkowo zwraca `bulk:[{key,state,time,perCountry,count,total,reason,legs:[{country,name,account}]}]` (10 ostatnich operacji zbiorczych) i `bulkAvailable`.

### Obieg puli obywateli i zasilenia wdrożeniowe (2026-10-29)
- **Nowe typy i kategorie.** Typ konta `citizens` („Oszczędności obywateli NPC”, konta `ludnosc:obywatele_<uuid>`). Kategorie `labor` („Robocizna obywateli NPC”) i `citizens` („Dochody i wydatki obywateli NPC”). Bilans ma 13 typów.
- **`world.stats.households {days?}`** (7..90, domyślnie 30). Odczyt, tylko właściciel, bez cache. Zwraca: `{account, balance, days, from, to, daily:[{day,t,in,out,net,byCategory}], totals:{in,out,net}, avg7:{in,out,net,days}, forecastDays|null, forecastDate|null, selfSustaining, categories:[{key,label,in,out,count}], counterparties, points, rows, labor:{percent,dailyCap,targetBalance,settleAfterHours,today,pending,due,settledTotal,cancelledTotal,prefixes,refundPrefixes}, grants:[{key,label,account,amount,state}], categoryLabels}`.
  - Prognoza bierze średnie netto z ostatnich do 7 pełnych dni. Dni bez ruchu liczą się jako 0, dzisiejszy dzień jest pomijany.
- **`world.grants {}`** (odczyt). Zwraca `{reserve, autoOnStart, grants:[{key,label,account,amount,state:NEW|DONE|UNCERTAIN,balance,fromReserveTotal,fromReserveCount,lastFromReserve}]}`.
- **`world.grantsApply {key|"wszystkie", retry?}`** (mutacja z audytem, tylko właściciel). Odpowiedź przychodzi po `LedgerStore.durable`: dane jak `world.grants` plus `{ok, applied, durable, message}`.


## Chorągwie krajów (przygotowane 2026-10-29, nieaktywne)

Most Panel → Kraje refleksją (bez zmiany medival-api): `KrajePlugin#flagPanel(UUID actor, String actorName, boolean panelAdmin, String method, Map<String,String> args)`
(Map albo — dla `country.flag.upload` — `CompletableFuture<Map>`; błąd reguły = `IllegalArgumentException` z polskim tekstem) oraz
`KrajePlugin#flagModerator(UUID, boolean panelAdmin)`. Panel (`rpc/FlagMethods`) przepuszcza tylko `id`, `imageId` (16 hex), `reason` (≤200),
`filename` (≤120), `data` (base64 PNG/JPEG, ≤240 000 znaków; Worker dopuszcza dla `country.flag.upload` ciało do 256 KB). `panelAdmin` liczy Panel.
W audycie `data` jest zastępowane opisem `<obraz N znaków>`. Odpowiedzi nie są cache'owane (DO ani sessionStorage). `me.roles.flagModerator`.

| metoda | Panel | Kraje sprawdza |
|---|---|---|
| `country.flag.get {id}` | `*` | prywatne części (pending, lastRejection, history) tylko Doradca+/Władca kraju (`flags.upload-rank`) albo moderator |
| `country.flag.upload {id,data,filename?}` | A | ranga w kraju; kraj NPC tylko moderator; walidacja i normalizacja obrazka |
| `country.flag.withdraw {id}`, `reset {id}`, `restore {id,imageId}` | A | jak wyżej; `restore` tylko obrazki wcześniej zatwierdzone |
| `admin.flag.queue {}` | `*` | moderator: admin panelu, `flags.moderators` (WorldSteward), `kraje.flagi.moderuj` |
| `admin.flag.approve {id,imageId}`, `reject {id,imageId,reason}` | `*` | moderator; `imageId` musi być aktualnie oczekującym; powód 3–200 znaków |
| `admin.flag.rebuild {}` | `*` | moderator; nie częściej niż `force-min-interval-seconds` |

Kształty: `country.flag.get` → `{id,name,color,npc,access:{view,upload,manage,moderate},current:{source:DEFAULT|UPLOAD,imageId,png,approvedAt,approvedBy,status:LIVE|WAITING_PACK,liveSince},pending?:{imageId,png,uploadedAt,uploadedBy,original:{width,height,format,bytes},colors}|null,lastRejection?:{imageId,at,by,reason}|null,history?:[{imageId,status,at,by,note,png|null,restorable}],limits:{maxBytes,width:64,height:40,maxColors,formats,minSourceWidth,minSourceHeight,maxSourcePixels},pack:{available,rebuilding,nextRebuildAt,lastRebuildAt,minIntervalMinutes,queued}}`;
`admin.flag.queue` → `{items:[{id,name,color,npc,imageId,png,uploadedAt,uploadedBy,original,colors,currentPng}],recent:[{id,name,imageId,status,at,by,note}],pack}`;
mutacje → `{ok,message[,pending|nextRebuildAt]}`. Obrazki to `data:image/png;base64,…` po normalizacji (64×40). Widoki SPA: `#/kraj/<id>/flaga`, `#/flagi` (szczegóły: panel/web/README.md).

## Gospodarka kraju (przygotowane 2026-10-29, nieaktywne)

Strona `#/gospodarka/<id kraju>` (tylko odczyt) i `#/gospodarka` (własny kraj albo lista krajów). Link: menu boczne
„Gospodarka kraju” oraz przycisk „Gospodarka” w nagłówku widoku kraju.

### `country.economy {id, fresh?}` (odczyt, bez audytu)
- **Dostęp sprawdza plugin przy KAŻDYM wywołaniu, zanim cokolwiek odczyta** (`EconomyMethods.access`): władca (`RULER`) i doradca
  (`ADVISOR`) kraju `id`, admin panelu (`ADMIN`, każdy kraj), osobisty zarządca WorldSteward (`OWNER`, każdy kraj — UUID potwierdza
  Skarbiec `worldMoneyManager`, jak przy `world.*`), zarządca tego kraju NPC (`NPC_MANAGER`). Obywatel, gracz bez kraju, władca
  obcego kraju i zarządca NPC dla kraju gracza: `{ok:false, error:"Brak dostępu do gospodarki tego kraju — widzą ją tylko władca
  i doradcy kraju."}` bez danych. Bramka `MethodSpec` to `LOGGED_IN` + `targetsId` (rola zależy od Skarbca).
- **Bez cache poza serwerem**: metody nie ma w `CACHEABLE` Durable Objectu, a SPA nie zapisuje jej w `sessionStorage`
  (`api.read`). Serwer trzyma gotową odpowiedź `economy.cache-seconds` (15 s) per kraj tylko w pamięci, po sprawdzeniu dostępu.
  `fresh: true` (przycisk „Odśwież”) buduje ją od nowa najwyżej co `economy.fresh-min-seconds` (5 s).
- **Źródła** (główny wątek, refleksja, odporna na brak pluginu/metody): `LudnoscPlugin#materialFlow(UUID)`, `life().stats(UUID)`,
  `life().countryLife(UUID)` (tylko gdy kraj ma obywateli), `work().summary(UUID)`, zamrożone `LudnoscApi.info`, historia
  `plugins/Panel/history` (wzrost ludności). Nieznane pola `materialFlow` (nowsza Ludnosc) trafiają do `extra` (zwykłe
  wartości, ograniczone), ich nazwy do `sources.unknown`; `sources.flowVersion` = pole `version` przepływu, jeśli jest.
- **Wydajność dzienna**: gdy miejsce pracy w `materialFlow` ma `daily|perDay|dailyYield|yield24h|produced24h|producedToday|output`
  (mapa materiał→ilość albo liczba) albo przepływ ma `production|daily` — `production.daily.source = "ludnosc"`. Inaczej
  `"estimate"`: panel porównuje towar skrzyń wczytanych w dwóch kolejnych próbkach (tragarze między nimi się znoszą,
  wczytana/wyładowana skrzynia się nie liczy), sumuje dodatni przyrost i przelicza na dobę po ≥ 30 min obserwacji (dolna
  granica — zużycie po drodze nie jest widoczne). `"none"` = za mało próbek. Historia jest w pamięci (restart ją zeruje).
- **Próbnik**: co sekundę najwyżej jeden kraj gracza, którego próbka jest starsza niż `economy.sample-seconds` (300 s); kraj z
  odczytem k × `budget-ms` (8 ms) jest próbkowany k razy rzadziej; runda pomijana przy średnim ticku > `skip-above-mspt` (45).
  Państwa NPC tylko na żądanie (`economy.sample-npc-countries: false`).

```json
{"version":1,"id":"…","name":"Królestwo Polan","color":"#B02E26","npc":false,"access":"RULER","generatedAt":…,"cached":false,
 "ageMs":0,"buildMs":4.7,"refreshSeconds":15,"available":true,"settlement":"Gród",
 "sources":{"flow":true,"life":true,"work":true,"flowVersion":null,"unknown":[],"errors":{}},
 "summary":{"workplaces":6,"working":3,"blocked":2,"unloaded":1,"stores":4,"fullStores":1,"usedSlots":58,"slots":108,"goods":3550,
            "foodDays":3.9,"haulers":2,"sites":3,"waitingSites":1,"errors":2,"warnings":4,"population":14,"housing":18},
 "production":{"workplaces":[{"id","short","function":"kopalnia","status":"Magazyn pełny — praca wstrzymana","state":"FULL",
                              "stateLabel":"pełna skrzynia — stoi","x","y","z","daily":[…]|null,"extra":{…}|null}],
               "byFunction":[{"function","count","working","blocked","full","unloaded"}],
               "daily":{"source":"ludnosc|estimate|none","items":[{"key":"minecraft:cobblestone","label":"Bruk","kind":"stone","amount":2304}],
                        "coveredMs","since","windowMs"},
               "today":{"food","foodCap","fish","fishCap","adults","assigned"}|null,
               "workers":[{"job":"FARMER","label":"rolnik","workers","slots","places"}]|null},
 "stores":{"list":[{"label":"kopalnia (340 58 -170)","kind":"kopalnia","used":27,"slots":27,"fill":1.0,"full":true,
                    "goods":[{"key","label","kind","amount"}],"goodsCount","world","x","y","z","extra"}],
           "totals":[…],"usedSlots","slots","fill","fullCount"},
 "granary":{"food","capacity","meal","days","fill","population","housing","freeBeds","foodBalance","hungryDays","homelessDays",
            "foodPerSettler","growth":{"possible","text"},"growth24h":{"delta","from","at","source":"history|panel"}|null,
            "hasGranary","storedFood","extra"},
 "haulers":{"count","walking":[{"text","amount","item","from","to"}],"recent":[{"time":"12:41:07"|null,"text"}]},
 "sites":[{"id","name","stage","percent","builders","status","state":"WAITING|BOUGHT|WORKING|OTHER","waitingFor","source",
           "waitedMs","buyInMs","waitedApprox","needs":[{"key":"drewno","label","units","have","missing"}],"x","y","z","extra"}],
 "materials":[{"key","label","needed","have","missing","producers":["kamieniołom"],"produced","build":"tartak"}],
 "warnings":[{"level":"error|warn|info","code","title","text","hint"}],
 "citizens":{"available","population","adults","children","housed","homeless","employed","unemployed","couples","savings",
             "average","unpaid","statsAt","taxDaily","taxSource":"settled|expected","taxDue","wagesDaily","wagesDue","income",
             "meals","market","workers","lastDay","huts"},
 "history":{"points":[{"t","food","population","goods","usedSlots","slots","haulers","waitingSites"}],"from","to"},
 "settings":{"materialWaitMinutes":60,"requireMaterials":true,"foodPerSettler":20},"extra":{…}|null}
```
- `state` miejsca pracy: `OK | WORKING | FULL | TORCHES | NO_STORE | UNLOADED | AUDIT | BLOCKED | OTHER | UNKNOWN` (z polskiego
  statusu Ludnosci). Kody ostrzeżeń: `FULL_STORE, TORCHES, NO_STORE, AUDIT, BLOCKED, UNLOADED, STORE_ALMOST_FULL, NO_WAREHOUSE, FOOD,
  FOOD_LOW, HUNGER, NO_GRANARY, NO_BEDS, MATERIAL, COINS_ONLY, HOMELESS, UNEMPLOYED, UNPAID, NO_LUDNOSC, NO_FLOW, NO_WORKPLACES`.
- `MATERIAL`: brak w skrzyniach względem potrzeb budów (jednostki jak w Ludnosci: kłoda = 4, deska/kamień = 1). Gdy nikt nie
  wytwarza: „Zbuduj tartak/hutę szkła/cegielnię/owczarnię… albo kup … u kupca” (`level:error`); gdy producent stoi — usuń przyczynę;
  inaczej — tragarze przyniosą, budowa czeka najwyżej `material-wait-minutes`. Grupy znane: drewno, kamień, szkło, cegła, wełna,
  glina, piasek, żelazo, jedzenie; nieznana grupa dostaje ogólną podpowiedź.
- `waitedMs` liczy od chwili, gdy panel pierwszy raz zobaczył „czeka na …” (`waitedApprox: true`), chyba że Ludnosc poda
  `sites[].waitingSince`. `buyInMs` = ile zostało do zakupu za monety zamówienia.
- Sugerowane pola dla Ludnosci (bez zmiany sygnatury `materialFlow`): `version`, `workplaces[].daily` (materiał → ilość z 24 h),
  `sites[].waitingSince` (ms epoch). Panel używa ich, gdy się pojawią.

