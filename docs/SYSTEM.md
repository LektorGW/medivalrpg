# MEDIVALRPG — system państw (ekonomia, ludność, armie, państwa NPC)

Aktualna decyzja 2026-10-26 (wave 11): maksymalna epoka = Renesans (`Postep: awans.maksymalna-epoka: 2`).
Poniższy historyczny opis epok 3/4 pozostaje katalogiem opcjonalnym. Nie włączać przemysłu/nowoczesności bez decyzji użytkownika.
Wyjątek (decyzja 2026-10-27): kolej parowa (plugin Kolej — parowozy z modelami BetterModel, węgiel, woda, stacje, tory, firma
transportowa zarządzana przez admina) jest dostępna od startu we wszystkich epokach. Reszta przemysłu pozostaje wyłączona.
Powracający gracze zostają w miejscu wylogowania (`Menu: spawn.always-on-join: false`); pierwszy raz → spawn.
Globalny limit armii: 1000 (`Armia: limits.global-max`); indywidualne limity kraju są nadal konfigurowane osobno.
Wave 16: panel Spot ma codzienne zadanie o 03:00 (czas panelu) `squaremap fullrender minecraft:overworld`.
Pełny render mapy w przeglądarce działa po stronie hostingu; nie regeneruje świata Minecraft.
Armia wave 16 przygotowana w `plugins/update/Armia-1.0.0.jar`: fizyczny pokład do chodzenia i osobny ster
`/armia flota steruj`; Shift zwalnia ster na pokład, `/armia flota zejdz` schodzi na brzeg lub do wody.
Kod wymaga następnego uruchomienia serwera przez użytkownika; samo wgranie nie aktywuje nowych klas.
Porządek katalogów (2026-10-29): na decyzję użytkownika trwale usunięto `archiwum/` (fale 2–16, ich kopie, verify-wave13,
jednorazowe skrypty wave 15–17). Najstarsze zachowane wdrożenie to wave 17. Buduj do `build/`.
Walidacja: 192 testy Armii oraz 3505 asercji modelu pokładu na izolowanym Paper. Klient/Grim do sprawdzenia po aktywacji.
Wave 13 (kod do aktywacji przy uzgodnionym restarcie): desant morski może ustanowić trwały przyczółek
na oddalonej ziemi wroga. Wymaga wojny, wybrzeża i potwierdzenia transportu przez opcjonalny
`LandingSupport` z ServicesManager; brak providera odmawia desantu. Zdobyty przyczółek jest własną ziemią,
z której można rozwijać dalszy podbój. Nadal obowiązuje ochrona stolicy i zasady oblężenia.
Armia rozwija flotę transportowców i karawel w epokach 1/2, z fizycznym przewozem graczy i oddziałów.
Mapy i mury muszą obsługiwać odległe składowe terytorium bez skanowania prostokąta pustego oceanu.
Decyzja użytkownika wave 13: zwykłe, zweryfikowane TNT kraju niszczy jego własne bloki oraz bloki
wroga w aktywnie obleganym chunku. `protection.explosions` pozostaje `false`; selektywne
`protection.player-tnt` nie daje tego wyjątku creeperom, nieznanym ładunkom ani wózkom TNT.
Kontenery i obszary chronione pozostają wyłączone z niszczenia. TNT dziedziczy kraj ze zweryfikowanego
zapłonu, także w łańcuchu; kraju nie wolno odgadywać z miejsca końcowego wybuchu.
Wave 12 (nowy kod wymaga aktywacji przy uzgodnionym restarcie): Zycie obsługuje pożary, dzwon, ewakuację,
ekipy z wodą i trwałe zlecenia płatnej odbudowy budynków ze szablonem w Ludnosc. Panel ma edytor planu dla
państw graczy oraz prostokątne zaznaczenie niwelacji z podglądem kosztu i jednorazowym potwierdzeniem.
Władca zapisuje cały plan, doradca może go czytać i zlecać niwelację, administrator dowolny kraj. Nowe API
ma metody domyślne dla starszych konsumentów. Zapisane plany graczy realizują się stopniowo z normalnymi kosztami.
Wave 11: realne wyczerpywalne kamieniołomy, mosty traktów, bezpieczniejsze wejścia i wyrównywanie kwartałów,
wózki kopalniane (tramwaje/dźwigi tylko w wyłączonych epokach 3/4), warstwa squaremap „Osady i budynki”,
poziom osady w LudnoscInfo i maks. +10% HP/obrażeń armii zależnie od poziomu.

Serwer: Paper 26.3 build 226 (hosting 2026-10-28; izolowane testy na buildzie 218), Java 25, Linux, offline mode (UUID offline), ~20 graczy, gracze bez modów.
Modele BetterModel używają uzgodnionej serwerowej paczki zasobów (animacje oraz kolej); klient nie instaluje modów.
Wszystkie teksty dla graczy po polsku (MiniMessage w configach), kod po angielsku.
Autorstwo w każdym pliku: `// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl`; plugin.yml `authors: [Maciej Kaczmarczyk]`, `website: https://lektoro.pl`;
README po polsku ze stopką `© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)`; linia w logu przy starcie „<Plugin> by Maciej Kaczmarczyk — lektoro.pl”.

## Pluginy i zależności

### Stan operacyjny 2026-10-28 — nadrzędny wobec historycznych planów

Serwer działa po pakiecie `rail-economy-final2`; gotowość potwierdzono o 21:49:25 czasu polskiego.
Aktywne: Skarbiec economy-release8, Panel map-release1, Kolej rail-release3,
Ludnosc/Wladcy supply-release3 oraz Szlaki double-chest-release1. Walidacja: 1016 testów modułów,
41693 asercje wspólnego Papera, 8 restartowych i 24 modeli. Szczegóły i ograniczenia: `MEMORY.md`.

Obieg świata jest zamknięty: 100000000 md = 1000000 zł, osobisty zarządca WorldSteward.
Migracja zachowała istniejące salda poza trzema uzgodnionymi korektami krajów. Późniejsze przelewy są
normalnym obiegiem; nie przywracać sald ze snapshotu. Wypłaty i alokacje wymagają istniejącego źródła.
Koleją zarządza WorldSteward jako gracz; zgoda gospodarza na infrastrukturę nie wymaga sojuszu.
Dyspozytornia działa w panelu `#/kolej`, zarząd Skarbem Świata w `#/skarbswiata`.
Wdrożono 16 modeli infrastruktury w paczce zasobów. Nie oznacza to postawienia całej sieci.

Baza jest ukończona (17696/17696). Budowa linii 7090 m i czterech peronów spawnu nie ruszyła:
pierwsza wycena zatrzymała się na fingerprintcie liści 3365:70:775, przed płatnością/zleceniem.
Nowsza poprawka `kolej/build/rail-live-leaf-fix` ma 241 testów, lecz NIE jest aktywna i wymaga
jeszcze sprawdzenia na Paperze oraz osobno uzgodnionej przerwy. Nie ogłaszać czynnej linii.
Zgody na poprzednie 10+5 minut zostały wykorzystane; nie uprawniają do następnego restartu.

Ogłoszenie pakietu na Discordzie wysłano. Kanał Server-command ma osobny synchronizator
`commands-discord/`; aktualny stan publikacji, granice katalogu i sposób odświeżania opisuje jego README.
Sekrety webhooków pozostają poza repozytorium i dokumentacją.

| Plugin | Katalog | Dostarcza API | Zależy od (plugin.yml) |
|---|---|---|---|
| **Skarbiec** (ekonomia) | `skarbiec/` | `pl.medivalrpg.skarbiec.api` | — |
| **Kraje** (istnieje, rozbudowa) | `kraje/` | `pl.medivalrpg.kraje.api` (+ `kraje.model.Rank/Relation`) | softdepend: squaremap, Skarbiec, Ludnosc, Armia |
| **Ludnosc** (obywatele NPC, budowanie) | `ludnosc/` | `pl.medivalrpg.ludnosc.api` | depend: Kraje, Skarbiec; softdepend: WorldEdit, Armia |
| **Armia** (żołnierze NPC + AI państw NPC) | `armia/` | `pl.medivalrpg.armia.api` | depend: Kraje, Skarbiec; softdepend: Ludnosc |
| **Kolej** (królewski przewoźnik) | `kolej/` | — | depend: Kraje, Skarbiec; softdepend: BetterModel, WorldGuard, Ludnosc, Armia, nLogin |
| **Szlaki** (karawany i konwoje) | `szlaki/` | — | patrz `szlaki/src/main/resources/plugin.yml` |
| **Turnieje** (areny, turniej tygodniowy) | `turnieje/` | — | patrz `turnieje/src/main/resources/plugin.yml` |
| **Firmy** (firmy graczy: cechy, kompanie, manufaktury; etapy 1–3, przygotowane) | `firmy/` | — (most refleksyjny `firmyPanel`) | depend: Kraje, Skarbiec; softdepend: Ludnosc, Wladcy, Szlaki, Kolej, Postep, Menu, Straznik, squaremap |
| **Straz** (raport dzienny, alerty, nocna kopia danych pluginów; tylko odczyt) | `straz/` | — | softdepend: Skarbiec, Kraje, Ludnosc, Armia, Kolej i pozostałe nasze |
| **DiscordBot** (most gra↔Discord, role, układ kanałów, kronika wojen, server-info, regulamin) | `discordbot/` | — | softdepend: Kraje, Armia, Wladcy, Skarbiec, Straznik, nLogin, Straz, Nowosci, Kolej |
| **Pory** (wizualne pory roku: biomy w pakietach, śnieg i liście tylko u klienta) | `pory/` | — | softdepend: Ludnosc (zegar pór roku `LudnoscApi.SeasonInfo`) + datapack `medival-pory-roku.zip` |

### Zmiany 2026-10-31 (stan wdrożenia: `MEMORY.md`)

- **Pory** nie zmieniają świata na dysku: datapack dodaje sezonowe warianty biomów, plugin podmienia ID biomów w pakietach
  chunków per gracz według zegara Ludnosc; śnieg (1 warstwa, bez kolizji) i spadające liście istnieją tylko u klienta.
  Po aktualizacji Minecrafta datapack trzeba wygenerować ponownie (`pory/README.md`).
- **Armia — wyprawy dalekie:** gdy wróg (kraj graczy) nie ma wspólnej granicy i jest poza zasięgiem prawdziwego rejsu,
  rejs liczy się w tle (~6 min/1000 bloków, stan w npc.json), lądowanie dopiero przy graczach u brzegu wroga, dalej zwykłe
  oblężenie desantowe Kraje z prawdziwym dowodem transportu. Ziemi krajów graczy nadal nie zdobywa się w tle (`abstract-vs-players: false`).
  Admin: `/nk desant <kraj> [cel]|status|anuluj|teraz`. Ofensywy NPC↔NPC: `conquest.max-force` 24, garnizon z 3 chunków.
- **Kraje:** limit ziemi = 16 + 8 × gracz + 1 × obywatel NPC (`claims.per-npc-citizen: 1`), maks. 300.
- **Ludnosc w krajach graczy:** zamówienia tylko z listy `player-countries.allowed-buildings` (14 budynków gospodarczych +
  ratusz, karczma, kościół); mury narysowane w panelu są budowane (`player-countries.plan-walls: true`), odcinkami ciągłymi.
- **Firmy:** zrzutki (współzałożenie od 5 zł), tablica zleceń hurtowych z odległością, stawki hurtu NPC ×5
  (`hurt.mnoznik-stawek`), cech ma 1 warsztat w epoce 1 (`manufaktura.cech.*`), pełna manufaktura od epoki 2.
- **DiscordBot:** kanały po angielsku (treści po polsku), `🌐-server-info` (przyciski mapa/panel, config `server-info.*`),
  regulamin z plików `plugins/DiscordBot/regulamin/*.md` publikowany na `📄-rules` (`/discord admin tresci odswiez`).

### Wave 17 — aktywne na produkcji od 2026-10-28

Wdrożono 10 pluginów, modele i panel WWW. Końcowe wersje oraz kopie opisuje `deploy-wave17/DEPLOYMENT-REPORT.md`.
Kolej ma końcową poprawkę 17b: naturalny zanik roślin i zarastanie ziemi nie blokują zatwierdzonego projektu.
Zarejestrowano już odbudowaną istniejącą arenę `krolewska`, bez zmiany bloków. Jednorazowy bootstrap naprawczy
wyłączono po audycie zmienionego terenu. Baza przewoźnika ukończona (DONE17696/17696): X4824/Z1198, fundamentY109; koszt91829md.

### Archiwalny opis przygotowania pakietu wave 17

Zakres: zlecenia i reputacja we Wladcy, unie personalne i ambasady w Kraje, praca/kopalnie/pory roku w Ludnosc,
rotacja kupca w Skarbiec, Nowosci z przypomnieniami i webhookiem Discorda, wykresy wydajności Panelu oraz Kolej,
Szlaki i Turnieje. Wszystkie funkcje mają pozycje w Menu i Poradniku po integracji.
Uzupełnienie użytkownika 2026-10-28: produkcja kopalń, kamieniołomów i innych prac trafia do rzeczywistych skrzyń;
brak miejsca zatrzymuje pracę przed usunięciem kolejnego bloku, po opróżnieniu magazynu praca wznawia się.
Górnicy mają oświetlać wydrążone wnętrza prawdziwymi pochodniami. Testy i pakiet muszą obejmować także te zmiany.
Firmy należące do graczy są zapowiedzią; firmą przewozową i jej rozkładem zarządza administrator.
Potwierdzenie użytkownika 2026-10-28: zarząd koleją należy do niego; pakiet ustawia `Kolej: firma.manager-uuids`
na UUID WorldSteward. Ranga maszynisty daje prowadzenie przydzielonych składów, nie zmianę sieci ani rozkładu.
Kolej ma własne trwałe licencje maszynistów, NPC z żołdem, fizyczne projekty torów/dróg, magazyny z dziennikiem
przeładunków i bazę z obrotnicą/manewrami pustych parowozów. Osobny palacz NPC nie został wdrożony.
Katalog `deploy-wave17/` zawiera manifest, jary, prywatne konfiguracje i plan bazy; `backup-wave17/` to gorąca kopia
wybranych danych, konfiguracji, aktywnych jarów i dwóch regionów świata. Gorąca kopia nie jest atomowym snapshotem
całego świata. Wyniki izolowanych testów Paper są w `verify-wave17/` i odnoszą się do wskazanych hashy jarów.
Skrypty `package_wave17.py`/`stage_wave17.py` (usunięte 2026-10-29) składały pakiet i kopiowały go do odizolowanego
`plugins/.prepared-wave17/` bez aktywowania pluginów ani zmiany aktywnych konfiguracji. Sekrety SFTP i Discorda
są pobierane z tymczasowych plików sesji; nie wypisywać ich w raportach. Aktywację i restart należy uzgodnić zgodnie z AGENTS.md.

Inne pluginy na serwerze: Glos (voice chat), Interakcje, squaremap 1.4.3, WorldEdit 7.4.6, WorldGuard 7.0.20, nLogin, GrimAC (anticheat — uważaj na teleporty/knockback NPC), BreweryX, SkinsRestorer.

### Wspólne API — `medival-api/` (ZAMROŻONE)
- Interfejsy już istnieją; **nie zmieniaj ich**. Potrzebujesz zmiany → opisz w raporcie (koordynator zdecyduje).
- Provider kompiluje SWÓJ moduł do swojego jara: `sourceSets.main { java.srcDir("../medival-api/<x>-api/src/main/java") }`.
- Konsument: `compileOnly(files("../medival-api/build/libs/medival-api.jar"))` (jar już zbudowany; przebuduj `./gradlew jar` w medival-api, jeśli go brak). Nigdy nie pakuj cudzych klas API do swojego jara.
- Rejestracja: `Bukkit.getServicesManager().register(XApi.class, impl, plugin, ServicePriority.Normal)` w onEnable; pobieranie: `load(XApi.class)` — przy softdepend sprawdzaj null i działaj bez tej funkcji.
- Pieniądze zawsze w `long` miedziakach (1 sr = 10 md, 1 zł = 100 md). Skarbiec kraju = konto `SkarbiecApi.countryAccount(countryId)`.

### Wspólne konwencje
- Bukkit API tylko na głównym wątku; zapis plików async (tmp + atomowa podmiana + .bak). Dane w JSON (Gson z Papera) albo SQLite (sterownik `org.sqlite` jest w Paperze) — wybór implementującego.
- PDC namespace = własny plugin. Encje NPC zawsze oznaczone PDC + sprzątanie osieroconych przy starcie i ładowaniu chunków.
- NPC wyglądające jak ludzie: **`org.bukkit.entity.Mannequin`** (jest w paper-api 26.3: `setProfile(ResolvableProfile)`, `setImmovable`, `setDescription`, `getEquipment`, `setMainHand`; NIE jest `Mob` → brak AI). Skiny przez `ResolvableProfile` po nicku Mojang (serwer ma internet). Statyczni NPC (bankier, kupiec, władca) = Mannequin `setImmovable(true)`, `setInvulnerable(true)`, `setPersistent(true)`, bez kolizji jeśli się da.
- Interaktywne GUI: własny `InventoryHolder`, blokuj shift-klik/drag/number keys/drop.
- Komendy przez Brigadier (`LifecycleEvents.COMMANDS`) z polskimi nazwami argumentów i tooltipami (wzór: `kraje/src/main/java/pl/medivalrpg/kraje/command/KrajCommandTree.java`, `glos/plugin/.../GlosCommand.java`) + interaktywna pomoc (wzór: `kraje/.../command/HelpBook.java`).
- Build: Gradle wrapper (skopiuj `gradle/`, `gradlew*` z `kraje/`), toolchain 25 przez foojay (`settings.gradle.kts` jak w `kraje/`), `paper-api:26.3.build.218-stable`, JUnit 6 jak w `kraje/build.gradle.kts`. `./gradlew build` musi przejść z testami. NIE wgrywaj nic na serwer.
- Sprawdzaj API w jarach z cache Gradle (`javap` z `the Gradle JDK toolchain`). Nie zgaduj.

## Skarbiec — ekonomia
- **Monety fizyczne**: miedziak (`COPPER_NUGGET`), srebrnik (`IRON_NUGGET`), złota korona (`GOLD_NUGGET`) z PDC wartości, nazwą, lore i (opcjonalnie) glintem. Prawdziwość tylko po PDC. Blokuj użycie monet w craftingu (nuggety → sztabki), w kowadle (zmiana nazwy) i jako paliwo/handel z wieśniakami.
- **Bank**: konto każdego gracza. Bankier NPC (Mannequin): GUI wpłać wszystkie monety / wypłać kwotę (przyciski 1 sr, 1 zł, 10 zł, „inna kwota” → wpis na czacie) . `/saldo`, `/zaplac <gracz> <kwota>` (przelew bankowy, config czy wszędzie), `/monety` (ile mam w monetach).
- **Skup u kupca NPC**: GUI z cennikiem (config: przedmiot → cena skupu, opcjonalnie cena sprzedaży), sprzedaż z ekwipunku, dzienny limit na gracza (anty-farma), opcjonalnie spadek ceny przy dużej podaży. Wypłata monetami do ekwipunku (config: albo na konto).
- **Sklepy graczy**: skrzynia + tabliczka (`[Sklep]`, `Sprzedam/Kupię`, ilość, cena). Ochrona skrzyni sklepu (tylko właściciel otwiera/niszczy, lejki nie wyciągają). Płatność monetami z ekwipunku kupującego; zarobek na konto właściciela. Integracja z Kraje: sklep tylko na terenie kraju właściciela albo w dziczy (config).
- **Handel** `/handel <gracz>`: GUI 2×4 sloty + monety, obie strony potwierdzają, odliczanie 3 s, anulowanie przy zmianie, zasięg 10 bl., zabezpieczenie przed dupe (rozłączenie, zamknięcie, śmierć).
- **Admin**: `/skarbiec admin daj|zabierz|ustaw <gracz|konto> <kwota>`, `/skarbiec npc stworz <kupiec|bankier> [nazwa] [skin]`, `usun`, `lista`, `/skarbiec log <gracz|konto>`, `reload`.
- Log transakcji (append, rotacja).

## Kraje — rozbudowa
- **Migracja API**: przenieś `api/*` i `model/Rank.java`, `model/Relation.java` do `medival-api/kraje-api` (już tam są — nowa wersja API z polami npc/stolica/podatek/skarbiec i metodami mutującymi); usuń duplikaty z `kraje/src`, dodaj srcDir, zaimplementuj całe nowe `KrajeApi` + wszystkie eventy z `kraje.api.event`.
- **Skarbiec kraju** (jeśli Skarbiec jest): `/kraj skarbiec` (stan + ostatnie operacje), `/kraj wplac <kwota>` (z konta gracza lub monet), `/kraj wyplac <kwota>` (Władca, na swoje konto).
- **Koszty**: założenie kraju (config 5 zł), zajęcie chunka (bazowo 50 md + rosnąco z wielkością kraju), dzienne utrzymanie za chunk (np. 2 md/chunk). Brak środków → `TreasuryTickEvent.bankrupt`, po `grace-days` (3) kraj traci najdalsze od stolicy chunki (`ClaimCause.BANKRUPTCY`, nigdy stolicy).
- **Podatki**: `/kraj podatek <kwota>` (Władca, max w configu) od każdego obywatela-gracza z konta bankowego raz dziennie (godzina z configu, np. 18:00 czasu serwera; nadrabiaj po restarcie). Nie zapłacił → dług, powiadomienie; po N dniach zaległości Władca może wyrzucić bez kary. Podatek od obywateli NPC zbiera Ludnosc.
- **Wojna**: wypowiedzenie kosztuje (config), minimalny czas wojny przed pokojem (config 2 h), cooldown ponownej wojny. **PvP**: config `pvp.mode: vanilla | war-only` (domyślnie `vanilla` = zwykłe PvP wszędzie, decyzja użytkownika; `war-only` jako opcja). Nie psuj PvP w trybie `vanilla`.
- **Oblężenia**: `/kraj oblezenie` stojąc na chunku wroga (wojna, przyległy do własnego terenu — config), koszt, max czas 30 min, jedno oblężenie na kraj naraz. Postęp rośnie, gdy siła atakujących w chunku (gracze + `SiegeContributor`) > obrońców, maleje gdy mniej, stoi gdy brak walki. BossBar dla uczestników, ogłoszenia, marker ⚔ na squaremap. Podczas oblężenia w oblężonym chunku atakujący mogą niszczyć/stawiać bloki (config: lista wykluczeń, np. kontenery nie). Zdobycie → chunk do atakującego (`ClaimCause.CONQUEST`), musi zachować spójność atakującego. Stolica: można oblegać tylko gdy to ostatni chunk albo config; zdobycie ostatniego chunka = upadek kraju.
- **Kraje NPC**: flaga `npc`, brak graczy-członków, `rulerName` NPC, gracze nie mogą do nich dołączać (config). Chronione jak każdy kraj. W info/mapie oznaczenie „(NPC)”. Komendy dla graczy działają z nimi (sojusz/pokój → `RelationProposalEvent`, odpowiada AI).
- **Info/mapa**: jeśli są Ludnosc/Armia — ludność, mieszkania, liczba żołnierzy w `/kraj info` i popupie squaremap. Oblężenia na mapie.
- Zachowaj wszystkie istniejące funkcje i testy.

## Ludnosc — obywatele NPC i budowanie

Źródła z 2026-10-29 (pakiet `deploy-ludnosc-players-20261029`, **przygotowane, nieaktywne**):
kraje graczy mają wyłączony planista/przebudowę/samodzielne chatki/automatyczne naprawy; katalog nowych zleceń
ograniczony do 14 niezbędnych typów i wariantów (`player-countries.allowed-buildings`). NPC zachowują pełny katalog.
Anulowanie `/ludnosc buduj anuluj [id]` i kolejki dostępne dla Doradcy/Władcy z dotychczasowym procentem zwrotu,
bez podwójnych wypłat. Łagodniejsze chatki `courtesy.*`, formaty i API bez zmian. Opis i testy w README pakietu.
To nie jest opis aktywnego procesu; aktualny stan operacyjny w MEMORY.md.

- **Obywatele = prawdziwe wieśniaki (`Villager`)** z PDC kraju. Wieśniak urodzony lub stojący (bez kraju) na terenie kraju → staje się obywatelem (config). Imiona z polskiej listy (custom name widoczny po najechaniu), profesje vanilla.
- **Mieszkania**: budynki (zbudowane przez system albo zgłoszone przez admina zaznaczeniem WorldEdit `/ludnosc dom zglos`) dają pojemność = łóżka w środku. Bez mieszkań ludność nie rośnie.
- **Wzrost**: co X min, jeśli ludność < mieszkania i spichlerz ma jedzenie → nowy osadnik (zużywa jedzenie). **Spichlerz**: budynek/skrzynia oznaczona komendą, gracze wkładają jedzenie; rolnicy NPC co dzień dokładają jedzenie (config). Kraje NPC dostają jedzenie wirtualnie (config).
- **Podatki NPC**: dziennie X md za obywatela (mnożniki zawodów w configu) do skarbca kraju (`SkarbiecApi.deposit` na `countryAccount`). Głód/brak mieszkań → mniejszy podatek, odchodzenie osadników.
- **Ochrona**: obcy nie mogą krzywdzić obywateli na terenie kraju poza wojną; w wojnie mogą (config). Handel z wieśniakami działa normalnie.
- **Pobór**: `LudnoscApi.conscript` (używa Armia) usuwa dorosłego obywatela.
- **Budowanie**: biblioteka budynków = szablony w kodzie/YAML (warstwy znaków → bloki; DOMYŚLNIE kilka gotowych w średniowiecznym stylu: chata (2 łóżka), dom (4), spichlerz, koszary, kuźnia, studnia, wieża strażnicza) + opcjonalnie pliki `.schem` z `plugins/Ludnosc/budynki/` przez WorldEdit API (softdepend). `/ludnosc buduj <typ>`: podgląd obrysu cząsteczkami, obrót, potwierdzenie, koszt ze skarbca (Doradca+), teren musi być w kraju i wolny. **Budowniczowie** (Mannequin z narzędziem albo wieśniak) stoją na placu budowy, bloki stawiane stopniowo (config bloków/s), plac budowy przetrwa restart. Gotowy budynek rejestruje funkcję (mieszkania, spichlerz, koszary…).
- `/ludnosc info [kraj]`, `/ludnosc budynki`, `/ludnosc spichlerz`, admin: `/ludnosc admin dodaj <kraj> <n>`, `usun`, `budynek anuluj`, `reload`.

## Armia — żołnierze NPC i AI państw NPC
- **Jednostki (config)**: piechota (miecz + tarcza, kolczuga), łucznik (łuk, skóra), rycerz (żelazo/diament, na koniu), opcjonalnie kusznik. Każda: koszt rekrutacji, dzienny żołd, HP, obrażenia, zasięg, prędkość, siła w oblężeniu, skin, wyposażenie w kolorach kraju (skórzana zbroja barwiona, sztandar). Rekrutacja zużywa obywatela z Ludnosc (config `require-citizen`), wymaga koszar (config) i pieniędzy ze skarbca.
- **Wygląd i AI**: Mannequin nie ma AI. Zbadaj i wybierz (config `visual: mannequin|mob`): (A) niewidzialny „mózg” `Mob` z Paper Pathfinder/Goal API + Mannequin jako ciało (pasażer lub teleport co tick — sprawdź płynność, GrimAC, kolizje, trafienia przechodzą na mózg), (B) widoczny humanoidalny Mob (Zombie/Skeleton/Piglin z ekwipunkiem; bez palenia w słońcu, bez dropu, bez naturalnych celów). Domyślnie to, co działa niezawodnie.
- **Tryby**: straż (posterunek + promień, patrol po punktach), oddział za dowódcą (`/armia zamna`, `stoj`, `wroc`, `atakuj`), **Buława dowódcy** (przedmiot: PPM blok → idź tam, PPM byt → atakuj, Shift+PPM → za mną/stój). Cele: potwory na terenie kraju, gracze i żołnierze krajów w wojnie (zawsze tylko w wojnie), nigdy swoi/sojusznicy/neutralni.
- **Oblężenia**: rejestruje `SiegeContributor` (siła jednostek w chunku). AI może zlecić oblężenie.
- **Utrzymanie**: żołd ze skarbca dziennie; brak → morale ↓ → dezercja (powrót do ludności lub zniknięcie). `/armia rozpusc` zwraca do ludności.
- **Limity wydajności**: max żołnierzy na kraj (bazowo + na chunk) i globalny cap (domyślnie 150), AI działa tylko w załadowanych chunkach, budżet ms na tick.
- **Trwałość**: rejestr żołnierzy w pliku (kraj, typ, posterunek, tryb, HP); encje trwałe z PDC; po załadowaniu chunka podpięcie; oddział za dowódcą dociąga teleportem przy dużym dystansie/zmianie świata.
- **AI państw NPC („Władcy NPC”)**: ustawienia na kraj: `agresja`, `ekspansja`, `dyplomacja`, `bogactwo` (0–100), `max-chunki`, `max-zolnierze`, `aktywna`. Tick co N min (config): dochód → skarbiec (od 2026-10-29 przy zamkniętej puli wyłącznie przelew z budżetu operacyjnego `world:payments`, ograniczony `ai.economy.*` — patrz armia/README); desanty AI (`ai.naval.*`) tylko w trwających wojnach bez granicy lądowej; rozrost o przyległe chunki dziczy (koszt jak gracz, `ClaimCause.NPC`); zamawianie budynków (LudnoscApi); rekrutacja do celu; rozstawianie straży na granicy; dyplomacja: odpowiedzi na `RelationProposalEvent` (sojusz/pokój zależnie od dyplomacji, siły, zmęczenia wojną), wypowiadanie wojen sąsiadom przy wysokiej agresji i przewadze (cooldowny, ogłoszenie), propozycje pokoju przy przegrywaniu; oblężenia tylko w załadowanych chunkach (gdy w pobliżu są gracze). Log decyzji.
- **Władca NPC**: Mannequin w stolicy; PPM → GUI dyplomacji (info o kraju, zaproponuj sojusz/pokój, zapłać trybut, wypowiedz wojnę) — dla Władcy kraju gracza; innym tylko info.
- **Admin `/npckraj` (alias `/nk`)**: `stworz <nazwa> <kolor> [wladca]` (w miejscu admina, przez `KrajeApi.createNpcCountry`), `usun`, `ustaw <kraj> <parametr> <wartość>`, `wladca <kraj> <imie> [skin]` (stawia władcę tutaj), `skarbiec <kraj> daj|zabierz <kwota>`, `armia <kraj> dodaj <typ> <n>`, `wojna|pokoj|sojusz <a> <b>`, `pauza <kraj|wszystkie>`, `wznow`, `info`, `lista`, `tick <kraj>`, `log <kraj>`.
- Gracze: `/armia` (alias `/wojsko`): `rekrutuj <typ> [ilość]`, `lista`, `zamna`, `stoj`, `wroc`, `straz [promień]`, `patrol dodaj|wyczysc`, `atakuj`, `rozpusc`, `bulawa`, `info`. Uprawnienia rang z Kraje (Doradca+ rekrutuje i dowodzi, konfigurowalne).

## Zrobione: przejmowanie wiosek przez państwa NPC (decyzja 2026-10-26: komenda + automat)
Zaimplementowane w Armii (`village/VillageService`, `/nk wioska`, panel `admin.npc.fromVillage`); opis poniżej to specyfikacja.
- **Wykrywanie wioski** (Ludnosc): dzwon (POI meeting) jako centrum + łóżka (POI home), stanowiska pracy, pola, wieśniacy w promieniu ~64 bl.; zasięg wioski = obwiednia zabudowy.
- **Przejęcie** (`/nk wioska [nazwa] [kolor] [władca]` stojąc w wiosce; panel: `admin.npc.fromVillage {x,z,...}`): tworzy kraj NPC (`ArmiaApi.createNpcCountry`) ze stolicą w chunku dzwonu, zajmuje (force/ADMIN) wszystkie chunki z zabudową wioski (spójnie), rejestruje istniejące domy z łóżkami jako mieszkania („dom wiejski”), pola jako źródło jedzenia (bonus), adoptuje wszystkich wieśniaków (z ich zawodami), stawia władcę przy dzwonie, plan miasta startuje od istniejącego układu (drogi = istniejące ścieżki), AI rozbudowuje zamiast stawiać od zera.
- **Automat** (config, domyślnie włączony): przy ładowaniu/generowaniu chunków z wioskami (i jednorazowy skan pregenerowanego obszaru, rozłożony w czasie) — szansa `auto.chance` (np. 0.33), min. odległość od spawnu (`auto.min-spawn-distance` np. 600 bl.), od innych państw (`auto.min-country-distance` np. 400 bl.), limit `auto.max-countries` (np. 12), losowe nazwy/kolory/władcy/osobowości z puli (polskie średniowieczne), ogłoszenie „Na wschodzie powstało Księstwo X”. Wioski bliżej graczy (ich krajów) można wykluczyć.
- Testy: wykrywanie obwiedni wioski na syntetycznym zestawie POI, spójność zajętych chunków, reguły automatu.

## Kierunek: EPOKI — od średniowiecza do 2026 (decyzja 2026-10-26: każde państwo osobno, ~2–3 miesiące do nowoczesności)
Plugin **Postep** (`postep/`, API `medival-api/postep-api`: `PostepApi`, `EraAdvanceEvent`) śledzi epokę i postęp każdego kraju (gracza i NPC).
- **Epoki**: 1 Średniowiecze → 2 Renesans (XV–XVII w.) → 3 Epoka przemysłowa (XIX w.) → 4 Nowoczesność (2026).
- **Postęp**: punkty badań dziennie z ludności, budynków (biblioteka, szkoła, akademia, uniwersytet, laboratorium…), inwestycji ze skarbca (`/postep inwestuj`), handlu/pokoju; **odkrycia** (drzewko: np. druk, proch, nawigacja, maszyna parowa, kolej, elektryczność, beton, komputery) — część odkryć wymagana do następnej epoki; **minimalny czas w epoce** (domyślnie: Średniowiecze ≥ 3 tyg., Renesans ≥ 3 tyg., Przemysł ≥ 3 tyg. → nowoczesność po ~9–12 tyg. dla dobrze rozwijanego państwa); progi ludności/poziomu osady.
- **Skutki epoki** (konsumenci API):
  - **Ludnosc**: nowe palety i szablony per epoka — Renesans (kamienice z attykami, pałace renesansowe, ratusze z wieżą, uniwersytet, port), Przemysł (ceglane fabryki z kominami, dworzec i tory, kamienice czynszowe, mosty stalowe, latarnie gazowe, kopalnie przemysłowe), Nowoczesność (bloki mieszkalne, **wieżowce 30–80 bl.** ze szkła i betonu, biurowce, galeria handlowa, szpital, stadion, szkoła, parlament, **drogi asfaltowe** z pasami, latarnie uliczne, parki, parkingi); **przebudowa miast** w czasie (modernizacja starych budynków do stylu epoki, wyburzanie i zastępowanie małych domów większymi); poziomy osady rozszerzone o Metropolię.
  - **Armia**: jednostki per epoka (Renesans: pikinierzy, muszkieterowie [kusza w roli muszkietu], arkebuzerzy, armaty jako jednostka oblężnicza; Przemysł: piechota liniowa, kawaleria, artyleria; Nowoczesność: żołnierze w mundurach polowych [barwiona skóra], snajperzy, „wozy pancerne” jako opancerzona jednostka na koniu/mułach albo inny vanilla-friendly pomysł), siła rośnie z epoką.
  - **Kraje/mapa/tablista/panel**: tytuły i nazwy (Królestwo → Królestwo/Republika wg ustroju), ikona epoki w `/kraj info`, popup mapy, panelu i tabliście; ogłoszenie przy awansie.
  - **Władcy (LLM)**: styl mowy zależny od epoki (średniowieczny → współczesny).
- Gracze też awansują swoje kraje.

## Narzędzia admina terenu i przebudowy (stan 2026-10-26, wave 10)
- **Kraje — teren:** `/kraj admin zajmij <kraj> promien <n>|zaznaczenie`, `/kraj admin przylacz <kraj> [pierścienie]`, `/kraj admin porzuc <kraj> [promien|zaznaczenie]` — podgląd z cząsteczkami, potem `potwierdz`, wykonanie porcjami.
- **Kraje — przywracanie terenu (WorldEdit regen z ziarna świata):** `/kraj admin przywroc <kraj> [promien <n>|zaznaczenie] [potwierdz]`, `/kraj admin przywroc teren promien <n>|zaznaczenie` (dzicz), `/kraj admin przywroc stop`, `/kraj admin usun <kraj> przywroc` (usuwa kraj, po `restore.delete-delay-ticks` regeneruje jego chunki). Chunki z `protected-areas.json` (spawn) zawsze pomijane. Config `restore:` (chunks-per-batch 4, pause-ticks 20). Każda porcja to przycinka serwera — duże kraje poza godzinami gry.
- **Kraje API 1.4:** `KrajeApi.claimChunkForPlanner` (default) — planista Ludnosc kraju NPC może zająć `claims.npc-planner-bonus` (64, -1 = bez limitu) chunków ponad zwykły limit; kraje graczy bez zmian.
- **Ludnosc — przebudowa v3:** kolejność rozbiórki → wyrównanie (`TownGrade`: jeden poziom na kwartał/rynek, rampy ≤ 1/2, kwartały z nasypem > 6 zostają naturalne, sprzątanie płyt/schodków/latarni) → ulice po wyrównaniu → budynki w kwartałach (kolejka dopełniana co 30 s). Nieudany typ budynku dostaje backoff (10 min → 12 h, `Backoff`). Most/port tylko gdy na ziemi kraju jest woda. Parcela po rozbiórce od razu zasypywana.
  Komendy: `/ludnosc admin przebudowa <kraj> [podglad|teraz|anuluj|reset] [darmowo]` — `teraz` przy trwającej przebudowie nic nie dokłada (pokazuje postęp); `anuluj` zwraca pieniądze za zamówienia w kolejce; `reset` = anuluj + nowy plan.
- **Panel WWW — zakładka Plan** (`#/npc/<id>/plan`): edytor planu (strefy, drogi, mury: ręcznie / wzdłuż granicy / wokół miasta, budynki), narzędzie Teren (`admin.npc.territory`), nastrój (Zycie) + festyn, wymuszenia (forceRedesign/forceRoads/forceModernize). Szczegóły i kształty RPC: PANEL.md. Rozmiary budynków w panelu to własna tabela (`BuildingCatalog`) — aktualizować przy zmianie szablonów.
