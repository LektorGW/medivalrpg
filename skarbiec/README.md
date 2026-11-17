# Skarbiec — ekonomia MEDIVALRPG

Plugin Paper 26.3 (Java 25): konta bankowe, fizyczne monety, bankier i kupiec NPC, sklepy graczy, bezpieczny handel.
Dostarcza `pl.medivalrpg.skarbiec.api.SkarbiecApi` (ServicesManager) dla Kraje, Ludnosc i Armii.

## Pieniądze
- 1 złota korona (zł) = 10 srebrników (sr) = 100 miedziaków (md). Kwoty w komendach: `150`, `3zł`, `1zł 5sr`, `2 sr 3 md`.
- Monety: `COPPER_NUGGET` / `IRON_NUGGET` / `GOLD_NUGGET`, prawdziwe tylko z PDC `skarbiec:coin`. Nie da się ich
  użyć w craftingu, kowadle, piecu, u wieśniaków, moby ich nie podnoszą. Przy płaceniu reszta wydawana automatycznie.

## Kupcy, typy i dostawy (2026-10-29)

Katalog ma 163 towary epok 1–2 w 19 kategoriach (płody, przyprawy, zioła, żywność, ryby, nasiona, skóry, wełna, barwniki,
drewno, kamień, budulec, wyroby, paliwo, kruszce, narzędzia, broń, zbroja, jeździectwo). Generator i kontrola cen:
`config-merchant-catalog-20261029/catalog.py` (sprzedaż ≥ 1,25 × skup, brak zysku na „kup składniki → zrób → sprzedaj”).
- **Typy kupców** (`merchant.types`): `ogolny` (wszystko), `spozywczy`, `budowlany`, `kowal`, każdy z własnymi kategoriami.
  Typ ma NPC: `/skarbiec npc stworz kupiec ["nazwa"] [skin] [typ]`, zmiana `/skarbiec npc typ <id|najblizszy> <typ>`.
  Kupcy bez typu (wszyscy sprzed zmiany) są `ogolny` (`merchant.default-type`).
- **Wspólne**: jeden magazyn (`merchant-market.json`), jeden budżet `world:merchant`, wspólne dzienne limity sztuk i 500 zł zarobku —
  odwiedzanie kilku kupców nie omija limitów, a pula 100 000 000 md nie dostaje nowych kont.
- **Dostawy** (`merchant.assortment`): co 12 h (6:00 i 18:00) każdy typ ma towary stałe (`core`) i losowaną część katalogu
  (`rotating`), plus `seasonal` miejsc na towary pory roku z Ludnosc. Wybór liczy się z (typ, numer okresu, pora roku) — bez
  zapisu, ten sam po restarcie. `featured` towarów z oferty kupiec szuka (★). Okno pokazuje czas do nowej dostawy i podgląd
  następnej. Zapas towaru spoza oferty czeka w magazynie (bez wywozu nadwyżki) i wraca z towarem. `/skarbiec kupcy` — lista.
- **Bez arbitrażu między typami**: cena sprzedaży liczy się od NAJWYŻSZEJ ceny skupu tego towaru u wszystkich typów.
- Stary config bez `merchant.assortment`/`types` działa jak dawniej (jeden kupiec, cały cennik).

## Kramy kupców (BetterModel, opcjonalnie)

Modele `skarbiec_kram_<typ>` (ogólny, kowal, spożywczy, budowlany) z animacjami `wiatr` (daszek) i `dostawa` (towar na ladzie
przy nowej dostawie). Skarbiec kopiuje je przy starcie (onLoad) do `plugins/BetterModel/models/`. Admin: `/skarbiec kram postaw
[id|najblizszy]`, `przesun <id> <dx> <dy> <dz>`, `obroc <id> <stopnie>`, `usun`, `/skarbiec kram` (stan). Położenie zapisuje
`npcs.json`. Modele są tylko pakietowe (bez bytów w świecie): nie mają kolizji, nie zostają po awarii, po restarcie wracają.
Bez BetterModel kupcy działają bez kramów (`npc.stalls.*`).

## Zmienna oferta kupca

Co `merchant.rotation.period-hours` (3 h) kupiec zmienia ceny skupu o ±15 % i wybiera 6 towarów, których szuka:
+40 % ceny, dwukrotny limit sztuk, błysk i „★ Kupiec tego szuka!” w oknie. Zegar w oknie pokazuje, kiedy nowa
oferta. Oferta jest wyliczana z numeru okresu (bez zapisu), taka sama u wszystkich kupców. Cena skupu zawsze
zostaje poniżej ceny sprzedaży, więc odsprzedaż kupcowi nie daje zysku. Dzienny limit zarobku: 500 zł.

## Rynek kupca (stan magazynu i budżet)

Kupiec ma prawdziwy magazyn (`merchant-market.json`) i płaci z budżetu `world:merchant` w zamkniętym obiegu. Ceny zmieniają się płynnie, według krzywych:
- **Zapas.** Mnożnik to `(cel / zapas)^0,5`, w granicach 50–175 % ceny z cennika. Celem jest 32 partie towaru albo `target-stock`. Czego kupiec ma dużo, to kupuje i sprzedaje taniej; za brakujący towar płaci więcej i więcej za niego bierze.
- **Budżet.** Wpływa tylko na cenę skupu: `(budżet / 500 zł)^0,5`, w granicach 30–125 %. Limit sztuk maleje proporcjonalnie do budżetu. Przy pustym budżecie kupiec nie skupuje, a towar wraca do gracza.
- **Sprzedaż.** Kupiec sprzedaje wyłącznie z magazynu. Cena sprzedaży jest zawsze o co najmniej 25 % wyższa od ceny skupu, więc odsprzedaż nie daje zysku.
- **Ochrona przed manipulacją.** Zapas odniesienia dochodzi do rzeczywistego z półokresem 6 h. Skup liczy się od większego z nich, a sprzedaż od mniejszego. Każda kolejna partia w jednej transakcji ma cenę z krzywej.
- **Nadwyżka.** Nadwyżkę ponad cel kupiec wywozi w tempie 10 % dziennie. Znikają tylko przedmioty, pieniądze zostają. Przedmioty powstają jedynie przy towarach z jawnym `restock-per-day` (domyślnie żaden).
- **Bez zmian.** Dzienne limity i rotacja „★ Kupiec tego szuka” działają jak dotąd. GUI pokazuje cenę, ↑↓ względem doby, zapas („brak / mało / w normie / dużo”) i komunikat o braku pieniędzy.
- Ustawienia: `merchant.market.*` w config.yml (`enabled: false` przywraca stare stałe ceny).

## Przelewy Skarbu Świata do państw NPC (tylko WorldSteward)

Komendy (z podpowiedziami nazw krajów NPC i sald):
- `/skarbswiata kraj <kraj NPC> <kwota> <powód>` — przelew z rezerwy do skarbca kraju NPC;
- `/skarbswiata zwrotkraju <kraj NPC> <kwota> <powód>` — zwrot ze skarbca kraju do rezerwy.

- `/skarbswiata wszystkimnpc <kwota> <powód>` — ta sama kwota z rezerwy do skarbca KAŻDEGO państwa NPC: podgląd (lista, liczba,
  suma, rezerwa po), jedno potwierdzenie. Jedna operacja z kluczem: rejestr `bank/npc-bulk.json` zapisany na dysk przed przelewem,
  wszystkie przelewy atomowo w jednej operacji księgi (albo żaden — brak środków na całość = odmowa), każdy z powodem i znacznikiem
  `[npc-bulk:<klucz>]`. Ten sam klucz drugi raz niczego nie przelewa; przerwana operacja po restarcie jest rozstrzygana z księgi,
  a świadome ponowienie dopłaca tylko brakujące państwa. Panel: kierunek `toAllNpc` w `world.npcTransfer`, historia w `bulk`.

Kwotę bez spacji podaje się jak `150zl` albo `15000`. Komenda pokazuje podgląd; przelew trzeba potwierdzić w ciągu 60 s (`/skarbswiata potwierdz` albo kliknięcie).
W panelu: karta na `#/skarbswiata` (RPC `world.npcTreasuries` / `world.npcTransfer`).

Zasady:
- przelew to jedno atomowe przeniesienie z/do `world:reserve`; nie powstaje ani jeden miedziak;
- przy braku środków w rezerwie (albo w skarbcu kraju przy zwrocie) przelew zostaje odrzucony;
- przelew do kraju gracza albo nieistniejącego kraju zostaje odrzucony;
- powód trafia do księgi (`WorldSteward: …`, kategoria „Skarb Świata — przydziały”);
- odpowiedź przychodzi po wymuszeniu zapisu dziennika na dysk.

## Domknięcie puli obywateli NPC (`world:households`)

### Robocizna z budów (`world-economy.labor-share`)
Udział robocizny to 60 % płatności za budowę, drogi, naprawę, modernizację, wyrównanie terenu, rozbudowę kopalni oraz chaty opłacane przez obywateli. Powody muszą mieć prefiksy z configu.
- **Zaległość.** Udział najpierw jest zaległością płatnika; pieniądze zostają w `world:payments`.
- **Zwroty.** Zwroty `Ludnosc: zwrot…` zmniejszają zaległość proporcjonalnie, a 100 % zwrotu z kolejki kasuje ją całą.
- **Rozliczenie.** Po 24 h zaległość przechodzi jednym przelewem `robocizna: …` do `world:households` (kategoria „Robocizna obywateli NPC”). Obowiązują dwa limity: najwyżej 3 000 md dziennie i nic ponad cel 400 000 md w puli.
- **Zwrot nigdy nie zostaje zablokowany.** Jeśli `world:payments` ma za mało na zwrot, brakującą część dokłada `world:households` w tej samej operacji księgi. Gdy nie wystarczą obie pule, zwrot jest odrzucany bez częściowych przesunięć.
- Zaległość zapisuje `bank/labor.json`. To plan rozliczeń, nie pieniądze.

### Jednorazowe zasilenia wdrożeniowe (`world-economy.grants`)
Dwa zasilenia z rezerwy: 312 600 md do `world:households` i 50 000 md do `world:merchant`. Każde ma własny klucz jednorazowości, zapisany w dwóch miejscach:
- w `bank/grants.json`, zapisywanym z fsync **przed** przelewem;
- w powodzie w księdze, jako znacznik `[grant:<klucz>]`.

Stany zasileń:
- **DONE:** nigdy nie zostanie wypłacone drugi raz.
- **PENDING ze znacznikiem w księdze:** liczy się jako DONE.
- **PENDING bez znacznika (UNCERTAIN, np. po utracie zasilania):** nie jest ponawiane automatycznie. Właściciel ponawia je świadomie: `/skarbswiata wdrozenie ponow <klucz>`.

Domyślnie `auto-on-start: false` (zalecany, bezpieczniejszy wariant). Właściciel najpierw ogląda stan: `/skarbswiata wdrozenie` albo kartę w panelu, gdzie widać saldo i dotychczasowe ręczne przydziały z rezerwy. Wykonanie: `/skarbswiata wdrozenie wykonaj <klucz|wszystkie>`. Odpowiedź przychodzi po wymuszeniu zapisu dziennika.

## Statystyki Skarbu Świata (panel, tylko WorldSteward)

`SkarbiecPlugin.worldStats(uuid, metoda, parametry)` obsługuje metody `world.stats.*` (kontrakt w `PANEL.md`). Każde wywołanie najpierw sprawdza UUID zarządcy (`WorldStats.requireOwner`).
- **Bilans** zestawia konta według typów, sprawdza sumę = 100 000 000 md, ujemne salda i nieznane konta oraz porównuje spis monet z pokryciem `world:cash`.
- **Przepływy** to dzienniki `bank/logs/transakcje-*.log`, czytane bez blokowania. Każda transakcja ma kategorię, plugin, dzienne serie, macierz przepływów między typami kont, top konta i historię salda.
- **Spis monet** (`/skarbswiata spis` albo panel):
  - załadowane chunki i gracze online są liczeni z pamięci, porcjami po 2 ms na tick;
  - pliki regionów, bytów i graczy offline są czytane w tle (tylko odczyt, 40 % czasu pracy wątku);
  - niezmienione pliki brane są z pamięci podręcznej;
  - wynik trafia do `stats/census.json`. To spis „na gorąco”: różnica jest pokazywana, nigdy korygowana.
- **Ruchy monet** zapisuje `stats/ruchy/ruchy-*.log`: podniesienie, upuszczenie, śmierć, pojemniki (różnica przy zamknięciu), lejki (sumowane co 30 s), a do tego wpłaty i wypłaty z księgi. Monety nie dostały numerów seryjnych, więc nadal się stackują.

## Komendy
| Komenda | Opis |
|---|---|
| `/saldo` | saldo konta i wartość monet w sakiewce |
| `/zaplac <gracz> <kwota>` | przelew (config `pay.mode`: wszędzie albo przy bankierze) |
| `/monety` | ile monet nosisz |
| `/handel <gracz>` | okno handlu 2×4 + monety, potwierdzenie obu stron, odliczanie 3 s |
| `/skarbiec pomoc`, `/skarbiec sklepy` | pomoc interaktywna, lista własnych sklepów |
| `/skarbiec admin daj\|zabierz\|ustaw <gracz\|konto\|kraj:nazwa> <kwota>` | saldo (admin) |
| `/skarbiec admin monety <gracz> <kwota>` | wydanie monet |
| `/skarbiec kupcy` | kupcy, dostawy, czego szukają, kiedy nowa dostawa |
| `/skarbiec npc stworz <kupiec\|bankier> ["nazwa"] [skin] [typ]`, `typ <id\|najblizszy> <typ>`, `usun [id]`, `lista` | NPC (Mannequin) |
| `/skarbiec kram [postaw\|przesun\|obroc\|usun]` | kramy kupców (BetterModel) |
| `/skarbiec log <gracz\|konto> [strona]`, `info`, `reload` | dziennik, statystyki, przeładowanie |
| `/skarbswiata info\|budzet\|przydziel\|zwroc\|spis\|kraj\|zwrotkraju\|wszystkimnpc\|potwierdz` | Skarb Świata (tylko WorldSteward); `spis` uruchamia spis monet |

Uprawnienia: `skarbiec.use` (wszyscy), `skarbiec.sklep` (wszyscy), `skarbiec.admin` (op).

## Sklep gracza
Tabliczka na skrzyni/beczce (albo nad nią): `[Sklep]` / `Sprzedam` lub `Kupię` / ilość / cena.
Towar = pierwszy przedmiot w skrzyni (albo w ręce). Kupujący płaci monetami, utarg trafia na konto właściciela;
w sklepie „Kupię” właściciel płaci ze swojego konta. Skrzynię otwiera i niszczy tylko właściciel (i admin), lejki,
wybuchy, tłoki i ogień jej nie ruszają. Z pluginem Kraje: config `shops.territory`.

## Dane (`plugins/Skarbiec/`)
- `bank/accounts.json` (+ `.bak`) — migawka kont; `bank/journal.log` — dziennik zapisu z wyprzedzeniem;
  `bank/logs/transakcje-RRRR-MM-DD.log` i `zdarzenia-RRRR-MM-DD.log` — log transakcji i zdarzeń z monetami.
  Po awarii stan = migawka + odtworzenie dziennika. Uwaga: pełny reset ekonomii wymaga usunięcia całego katalogu `bank/`.
- `npcs.json`, `shops.json`, `merchant.json` (dzienne limity kupca).

## Budowanie
`./gradlew build` → `build/libs/Skarbiec-1.0.0.jar` (wymaga `../medival-api/build/libs/medival-api.jar` do integracji z Kraje).

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
