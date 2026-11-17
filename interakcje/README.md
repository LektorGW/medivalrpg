# Interakcje

Plugin Paper z interakcjami RP między graczami (całus, przytulenie, trzymanie za rękę, pogłaskanie, piątka, liść, pomachanie, ukłon), pozami ciała (siadanie, leżenie), tańcami i emotkami dla serwera **MEDIVALRPG**. Z **BetterModel** wszystko to są **animacje postaci** na modelu gracza z jego skinem; bez BetterModel działają cząsteczki i dźwięki. Gracze nie potrzebują modów, tylko paczki zasobów serwera (wysyłanej automatycznie przy wejściu).

- Paper 26.3 (build 218), Java 25, `api-version: '26.3'`, `softdepend: [BetterModel]` (testowane z BetterModel 3.6.0-SNAPSHOT-535).
- Build: `./gradlew build`, wynik: `build/libs/Interakcje-1.0.0.jar`. API BetterModel jest kompilowane z jara pluginu `../downloads/bettermodel/bettermodel-3.6.0-SNAPSHOT-535-paper.jar` (`compileOnly`; inną ścieżkę podasz przez `-PbetterModelJar=...`). Na Maven Central jest tylko `bettermodel-bukkit-api` 3.5.1, snapshoty 3.6 wymagają logowania do GitHub Packages.

## Komendy

Wszystkie komendy są rejestrowane przez Brigadier (`LifecycleEvents.COMMANDS`). Komendy interakcji powstają dynamicznie z `config.yml`.

| Komenda | Aliasy | Zgoda | Opis |
|---|---|---|---|
| `/pocaluj <gracz>` | `/calus`, `/kiss` | tak | serduszka między głowami, cichy „cmok” |
| `/przytul <gracz>` | `/hug` | tak | serca wokół obu graczy, obaj obracają głowę do siebie i machają rękami |
| `/trzymaj <gracz>` | `/zareke`, `/holdhand` | tak | trzymanie za rękę przez 30 s, co 0,5 s małe serduszko między graczami |
| `/pusc` | `/puść`, `/release` | – | puszcza trzymaną dłoń |
| `/poglaszcz <gracz>` | `/pat` | nie | zielone iskierki nad głową celu |
| `/piatka <gracz>` | `/highfive` | nie | obaj machają ręką, iskra i klaśnięcie |
| `/liść <gracz>` | `/lisc`, `/slap` | nie | lekki odrzut bez obrażeń; nie działa na graczy w trybie creative i spectator |
| `/pomachaj [gracz]` | `/wave` | nie | bez celu: do wszystkich w pobliżu |
| `/uklon [gracz]` | `/bow` | nie | krótkie pochylenie głowy i powrót |
| `/usiadz` | `/sit`, `/siad` | – | siadanie na ziemi; ponowna komenda albo kucnięcie: wstajesz |
| `/poloz` | `/lay`, `/lez` | – | leżenie; kucnięcie, ruch albo ponowna komenda: wstajesz |
| `/taniec [1-3\|stop]` | `/tancz`, `/dance` | – | taniec w pętli (1 ludowy, 2 dworski, 3 biesiadny); bez numeru: przerywa taniec albo zaczyna pierwszy |
| `/uscisk <gracz>` | `/dlon`, `/handshake` | tak | uścisk dłoni z trzema potrząśnięciami i skinieniem |
| `/toast <gracz>` | `/wznies`, `/kufle`, `/cheers` | tak | stuknięcie kuflami (napitek w ręce widać w dłoni) i łyk |
| `/reweranse <gracz>` | `/dworski`, `/courtbow` | tak | wzajemny ukłon dworski: nadawca reweranse z „zamachem kapeluszem”, cel dyg |
| `/pasuj <gracz>` | `/pasowanie`, `/knight` | tak | pasowanie: cel klęka, nadawca dotyka mieczem (z ręki) prawego i lewego ramienia; od władcy kraju tytuł Sir/Pani |
| `/lenno <gracz>` | `/przysiega`, `/fealty` | tak | przysięga lenna: nadawca klęka i składa złożone dłonie w dłonie celu (tylko animacja, relacje Kraje bez zmian) |
| `/koronuj [gracz]` | `/koronacja`, `/crown` | tak (z celem) | koronacja władcy kraju: cel klęka, korona (item display) idzie za dłońmi i spoczywa na głowie, obecni się kłaniają; bez celu władca koronuje się sam |
| `/emotka <nazwa> [gracz]` | `/emote`, `/em` | – | `klaskanie`, `salut` (rycerski), `nie_wiem`, `facepalm`, `modlitwa` i `kleczenie` (pętle, do ruchu albo `/emotka stop`), `poklon`, `salut_miecz`, `kufel`; z graczem (modlitwa, klęczenie, pokłon, salut mieczem) najpierw obracasz się do niego |

Opis „efekt” w tabeli dotyczy trybu bez animacji. Z BetterModel każda interakcja jest animacją postaci (opis niżej), a cząsteczki i dźwięki grają w chwili kontaktu.

Argument `<gracz>` podpowiada graczy w zasięgu danej interakcji (najbliżsi pierwsi, w podpowiedzi np. „2.1 bl.”). Gdy nikogo nie ma w pobliżu, podpowiada wszystkich widocznych graczy online.

`/interakcje` (alias `/int`):

| Podkomenda | Opis |
|---|---|
| `lista` | lista interakcji i póz z opisami (klik wpisuje komendę) |
| `wlacz` / `wylacz` | włącza lub wyłącza interakcje z tobą (zapisywane) |
| `blokuj <gracz>` / `odblokuj <gracz>` | osobista lista blokad: zablokowany gracz nie może cię wybrać |
| `zablokowani` | twoja lista blokad |
| `akceptuj [id]` / `odmow [id]` | odpowiedź na prośbę (tego używają przyciski [✔ Zgoda] / [✖ Odmowa]; bez id dotyczy najnowszej prośby) |
| `pakiet` | wysyła paczkę zasobów jeszcze raz (gdy coś poszło nie tak) |
| `tytul [sir\|pani\|ukryj\|pokaz]` | tytuł rycerski z pasowania (przed nickiem w displayName, czyli na czacie Kraje) |
| `npc status\|test <praca>\|ustaw <praca>\|stop\|usun` | animacje pracy NPC: stan, testowy Mannequin z twoim skinem, praca dla NPC, na którego patrzysz (`interakcje.admin`) |
| `status` | stan animacji (BetterModel, modele, katalog) i paczki (źródło, SHA-1, URL, ilu graczy ją ma) (`interakcje.admin`) |
| `reload` | przeładowanie configu (`interakcje.admin`) |
| `pomoc` | pomoc |

## Menu

**Shift + prawy klik** pustą ręką na gracza otwiera menu „Interakcje z &lt;nick&gt;” (27 slotów). Klik ikony wykonuje interakcję. Menu jest zabezpieczone: blokuje przeciąganie, shift-klik, klawisze numeryczne, wyrzucanie i klikanie we własny ekwipunek. Własne GUI plugin rozpoznaje po własnym `InventoryHolder`. Menu pokazuje tylko interakcje, do których gracz ma uprawnienia.

## Siadanie i leżenie

- **Siadanie**: gracz jedzie na niewidzialnym, nietrwałym `BlockDisplay` (bez bloku, bez grawitacji, z tagiem PDC `interakcje:seat`). Prawy klik pustą ręką w górną powierzchnię schodów albo dolnej półpłyty sadza gracza na środku bloku, zwróconego w stronę zgodną ze schodami (`sit.right-click-stairs`). Kucnięcie wstaje, a po wstaniu gracz jest przenoszony na bezpieczne miejsce: na schody lub półpłytę albo tam, skąd siadał.
- **Leżenie**: `Player#setPose(SLEEPING, true)`, czyli poza „jak w łóżku”, ale bez łóżka. Inni gracze widzą leżącą postać, a leżący widzi świat normalnie z pierwszej osoby (jego klient sam liczy własną pozę). Opcja `lay.pose: SWIMMING` daje zapasową pozę pełzania.
- Nie można siadać ani się kłaść w powietrzu, w wodzie, na pojeździe, w trakcie lotu ani gdy nad głową jest blok.
- Siedziska są sprzątane przy wyjściu, śmierci, teleporcie, zmianie świata, zniszczeniu bloku pod spodem (sprawdzane też co 0,5 s, więc łapie wybuchy i tłoki), przy wyłączeniu pluginu, przy starcie i przy wczytaniu chunków (osierocone siedziska). Poza i siedzenie nie przetrwają relogu.
- Gracz siedzący lub leżący może dostać „liścia”, ale bez odrzutu.

## Zasady interakcji

- Cel musi być w tym samym świecie, w zasięgu (domyślnie 3 bl., machanie i ukłon 10 bl.) i widoczny: nie w vanish (metadane `vanished`, konfigurowalne), nie w trybie spectator i widoczny dla nadawcy (`canSee`). Nie da się wybrać siebie.
- Interakcje ze zgodą wysyłają celowi klikalną prośbę ważną 30 s. Na parę graczy może czekać jedna prośba. Przy akceptacji zasięg jest sprawdzany ponownie. Odmowa i wygaśnięcie dają komunikat nadawcy.
- Opis RP (np. *Ala całuje Olka*) dostają gracze w promieniu 16 bl.
- Gracz z wyłączonymi interakcjami nie dostaje próśb i nie może być celem.
- Cooldown liczy się per nadawca i interakcja (domyślnie 5 s, liść 10 s). Do tego globalny anty-spam 1,5 s między dowolnymi interakcjami. Cooldown startuje też przy wysłaniu prośby.

## Animacje postaci (BetterModel)

Gdy na serwerze jest BetterModel, interakcje, pozy, tańce i emotki są animacjami całego ciała: model gracza z 12 części (głowa, tułów w trzech segmentach, ramiona i przedramiona, uda i łydki) z jego skinem, zbroją i przedmiotami w rękach.

### Jak to działa

- Modele: `src/main/resources/bettermodel/players/interakcje.bbmodel` (głowa w pełni animowana) i `interakcje_look.bbmodel` (głowa podąża za kamerą, dla pętli: siedzenie, leżenie, trzymanie za rękę). Plugin przy starcie kopiuje je do `plugins/BetterModel/players/` (`animations.install-models`) i, jeśli BetterModel ich jeszcze nie wczytał, sam robi `/bm reload` (`animations.reload-bettermodel`).
- Model z katalogu `players/` to w BetterModel „limb”: `BetterModel.limb("interakcje")` → `ModelRenderer#create(BukkitAdapter.adapt(player), TrackerModifier.DEFAULT, ...)` tworzy `PlayerTracker`, czyli display entities ze skinem gracza, widoczne dla otaczających graczy. Prawdziwy gracz jest dla nich ukrywany pakietami (po `player-hide-delay` z configu BetterModel). `EntityTracker#animate(klip, AnimationModifier, koniec)` odgrywa klip, a `EntityTracker#close()` usuwa model i pokazuje prawdziwego gracza. Cały kod BetterModel jest w `anim/BetterModelBackend`; bez BetterModel ta klasa nie jest ładowana.
- Kierunek ciała modelu jest w trakcie animacji stały (`Tracker#rotation(...)`, `animations.fix-body-yaw`), więc obrót kamerą nie rozjeżdża przytulenia.
- Animacje dwuosobowe (`anim/AnimationService`): przed startem gracze są ustawiani **teleportem w miejscu** naprzeciw siebie (przytulenie, pocałunek, piątka, pogłaskanie, liść) albo obok siebie (trzymanie za rękę). Odległość i ustawienie pochodzą z katalogu animacji (`animations.json`), np. przytulenie 0,6 bl. twarzą w twarz. Środek między graczami zostaje, każdy przesuwa się najwyżej o `align-max-shift` (1,25 bl.); miejsce musi być wolne (stopy, głowa, podłoże). Obie animacje startują w tym samym ticku, tick po teleporcie. Cząsteczki i dźwięki interakcji grają w chwili kontaktu (np. klaśnięcie piątki po 0,5 s, uderzenie liścia, każde klaśnięcie emotki).
- W trakcie animacji nie da się chodzić: ruch dalej niż `move-tolerance` (0,3 bl.), skok, kucnięcie, obrażenia albo teleport przerywają animację obu graczy. Wyjątek to reakcja na liścia, którą odrzut ma wolno przesunąć. Przy trzymaniu za rękę ruch kończy tylko animację, a gdy obaj znów stoją (1 s), animacja wraca sama.
- Pozy: `/usiadz` i `/poloz` grają pętle. Siedzisko `BlockDisplay` zostaje fizycznym miejscem, wygląd daje animacja. Wariant siadania wybiera się sam: na ziemi po turecku, na schodach, półpłycie albo krawędzi bloku (przed tobą spadek o co najmniej blok) z nogami w dół. Na krawędzi siedzisko przesuwa się na brzeg, twarzą do spadku. Leżenie to leżenie na plecach z rękami pod głową i ugiętym kolanem. Prawdziwy gracz domyślnie nie dostaje pozy SLEEPING (`lay.animated-pose: NONE`).
- GrimAC: ustawienie pary to zwykły serwerowy teleport (`TeleportCause.PLUGIN`) o kilkadziesiąt centymetrów, bez zmiany prędkości. Grim go akceptuje (czeka na potwierdzenie teleportu przez klienta). Ryzyko: gdy gracz właśnie biegnie albo ma duży ping, pierwszy ruch po teleporcie może dać pojedynczy setback. Nie teleportujemy w powietrze ani w bloki (sprawdzenie miejsca), a w trakcie animacji niczego nie przesuwamy. Gdyby Grim zgłaszał problemy, wyłącz `animations.align-pairs`. Wtedy animacja gra tylko, gdy gracze sami stoją mniej więcej w dobrej odległości.

### Animacje i komendy

| Animacja | Komenda | Klipy | Czas | Ustawienie |
|---|---|---|---|---|
| przytulenie | `/przytul` | `hug_a`, `hug_b` | 3,0 s | twarzą w twarz, 0,6 bl. |
| pocałunek | `/pocaluj` | `kiss_a`, `kiss_b` | 2,5 s | twarzą w twarz, 0,6 bl. |
| trzymanie za rękę | `/trzymaj` | `hold_right`, `hold_left` (pętla) | 4,0 s | obok siebie, 0,8 bl. |
| piątka | `/piatka` | `highfive_a`, `highfive_b` | 1,6 s | twarzą w twarz, 0,9 bl. |
| pogłaskanie | `/poglaszcz` | `pat_a`, `pat_b` | 2,6 s | twarzą w twarz, 0,7 bl. |
| liść | `/liść` | `slap_a`, `slap_b` (reakcja) | 1,5 / 2,3 s | twarzą w twarz, 0,85 bl. |
| pomachanie | `/pomachaj` | `wave` | 2,4 s | obrót do celu |
| ukłon dworski | `/uklon` | `bow` | 3,2 s | obrót do celu |
| siadanie | `/usiadz`, PPM na schody | `sit_floor`, `sit_chair` (pętle) | 4,0 s | – |
| leżenie | `/poloz` | `lay_back` (pętla) | 4,5 s | – |
| tańce | `/taniec 1`, `2`, `3` | `dance_1` ludowy, `dance_2` dworski, `dance_3` biesiadny (pętle) | 4 / 6 / 3,2 s | – |
| emotki | `/emotka klaskanie`, `salut`, `nie_wiem`, `facepalm` | `emote_*` | 1,9–2,9 s | salut: obrót do celu |
| modlitwa, klęczenie | `/emotka modlitwa`, `kleczenie` | `emote_modlitwa`, `emote_kleczenie` (pętle, wejście 0,4/0,6 s) | 5 / 4,5 s | opcjonalnie obrót do celu |
| pokłon, salut mieczem, kufel | `/emotka poklon [gracz]`, `salut_miecz [gracz]`, `kufel` | `emote_poklon`, `emote_salut_miecz`, `emote_kufel` | 2,8 / 2,8 / 3,2 s | pokłon, salut: obrót do celu |

Salut mieczem i kufel używają przedmiotu z ręki: model BetterModel sam pokazuje trzymany miecz albo butelkę w dłoni. Ułożenie klipu zakłada uchwyt jak w vanilla, więc miecz stoi pionowo przed twarzą. Bez przedmiotu ruch wykonuje pięść. `emotes.click-trigger` (domyślnie wyłączone) uruchamia `kufel` po prawym kliku z butelką, miodem, mlekiem albo trunkiem BreweryX, bez komunikatów i z cooldownem. Picie vanilla działa normalnie. Klip może podać własny czas płynnego wejścia (`blend` w `animations.json`), co przydaje się w pozach, które startują daleko od stania.

### Kiedy grają same efekty

Bez BetterModel, przy `animations.enabled: false`, gdy modele jeszcze się nie wczytały, gdy gracz siedzi, leży, jedzie, lata albo pływa, gdy para stoi za daleko (więcej niż `align-max-shift`) albo na różnej wysokości, albo gdy miejsce na ustawienie jest zajęte. W tych przypadkach interakcja działa jak dawniej: cząsteczki, dźwięki, machnięcie ręką i obrót głowy.

### Gracz bez paczki zasobów

Paczka jest wymagana: odmowa kończy się rozłączeniem (`kick-on-decline`). Gdyby jednak ktoś grał bez niej (np. pobieranie się nie udało), przy `animations.only-for-pack-users: true` model animacji nie jest mu w ogóle wysyłany. Widzi wtedy zwykłe postacie, bez animacji: stoją w miejscu, a cząsteczki i dźwięki grają. Siedzący wyglądają jak na wierzchowcu, leżący stoją. Przy `false` prawdziwy gracz byłby przed nim ukryty, a zamiast części ciała zobaczyłby prawdopodobnie brakujące modele albo bazowy przedmiot BetterModel (`leather_horse_armor`). Tego nie sprawdzano.

Sam animowany gracz w widoku z pierwszej osoby widzi świat normalnie (ręka vanilla). W F5 widzi swój model (`show-to-self`). Przy niektórych ruchach (facepalm, przytulenie) fragment własnego modelu może wejść w kadr kamery.

## Ceremonie

- **Koronacja** (`requires: ruler`): koronowany (cel, a bez celu sam nadawca) musi być władcą kraju w Kraje
  (`KrajeBridge`, softdepend). Bez Kraje koronacja jest zablokowana (`coronation.require-kraje`). Korona to
  nietrwały `ItemDisplay` (`coronation.crown-item`, domyślnie złoty hełm) prowadzony po ścieżce z generatora
  (`props.crown` klipów `crown_a`/`crown_self` w `animations.json`: między dłońmi, potem na głowie); po ceremonii
  unosi się nad głową `hover-seconds`. W chwili koronacji gracze w promieniu `audience-radius`, stojący nieruchomo,
  z włączonymi interakcjami i bez blokady, sami robią `/emotka poklon` w stronę władcy (`audience-bow: auto`;
  `prompt` = tylko przycisk [Pokłoń się], `off`). Ruch przerywa ukłon.
- **Pasowanie** (`grant-title`): po drugim dotknięciu ramienia cel dostaje tytuł (`knighting.default-title`), jeśli
  pasujący jest władcą kraju (`knighting.require-ruler`). Tytuł jest w `players.yml` (`titles`) i trafia do
  `Player#displayName` („Sir Nick”) — czat Kraje go pokazuje, tablista (nick) nie. `/interakcje tytul pani|sir|ukryj|pokaz`.
- **Przysięga lenna**: tylko animacja; nie zmienia wasalstwa w Kraje.
- Nowe interakcje z jara, których nie ma w starym `config.yml` serwera, są używane z wartościami domyślnymi bez
  zapisu pliku (`settings.add-new-defaults`, `DefaultEntries`); wyłączenie: wpis z `enabled: false`.

## Animacje pracy NPC (API dla innych modułów)

BetterModel potrafi założyć model gracza (limb) na dowolną encję z jawnym profilem skina
(`ModelRenderer#create(PlatformEntity, ModelProfile.Uncompleted, ...)` → `RenderSource$ProfiledEntity`), co
sprawdzono na Paperze na `Mannequin`. Interakcje udostępniają to jako `pl.medivalrpg.interakcje.api.WorkAnimations`
w ServicesManager: `play(npc, praca, loop)`, `stop(npc)`, `current(npc)`, `isShown(npc)`, `animations()`.
Prace (klipy w modelu `interakcje`, generator `animations-src/clips_npc.py`): `budowa`, `noszenie_plecy`,
`noszenie_rece`, `kilof`, `motyka`, `siekiera`, `kowadlo`, `zaproszenie`, `straz`, `odpoczynek` (pętle), `salut`,
`ziewanie` (gesty). Moduł ustawia stan, a `NpcAnimationService` co `scan-interval-ticks` wybiera NPC do pokazania
(`NpcSelection`: gracz z paczką w `range`, najbliżsi pierwsi, `max-active`, histereza), w budżecie `budget-ms`
i `max-starts-per-scan`. Bez widzów nie istnieje żaden tracker. Pętla zapisuje się w PDC (`interakcje:work`) i wraca
po wczytaniu chunka. Łatki dla Ludnosc, Skarbiec i Armia: `INTEGRATION-NPC.md`.

## Paczka zasobów

1. BetterModel przy starcie i przy `/bm reload` generuje paczkę `plugins/BetterModel/build.zip` (ścieżka z jego `build-folder-location`, `pack-type: zip`). Paczka ma około 1,8 MB: modele części ciała i zbroi, tekstury, bez core shaderów. Same animacje nie są w paczce, liczy je serwer.
2. `pack/ResourcePackService` dowiaduje się o nowej paczce ze zdarzenia BetterModel `PluginEndReloadEvent`, a zapasowo co `poll-seconds` (30 s) porównuje czas modyfikacji i rozmiar pliku. Liczy SHA-1 i atomowo kopiuje zip do `plugins/squaremap/web/pack/medivalrpg.zip` (`web-root` + `path`). Squaremap serwuje go pod `http://mc.example.org:8100/pack/medivalrpg.zip?v=<12 znaków SHA-1>` (dopisek `?v=` zmienia adres przy każdej wersji).
3. Przy wejściu (także w trakcie logowania nLogin) gracz dostaje `Player#sendResourcePacks(ResourcePackRequest)` ze stałym UUID paczki, SHA-1, `required: true` i polskim komunikatem. Nowa wersja paczki (inny SHA-1) jest od razu wysyłana graczom online i zastępuje starą (ten sam UUID).
4. `verify-download: true`: po publikacji plugin (w osobnym zadaniu async) pobiera paczkę spod adresu i porównuje SHA-1. Przy starcie serwer WWW/sieć mogą nie być gotowe, więc najpierw czeka `verify-initial-delay-seconds` (15 s), a potem ponawia z rosnącą przerwą (5, 10, 20, 40, 60 s; `verify-attempts: 6`, `verify-retry-seconds`, `verify-retry-max-seconds`). Ostrzeżenie pojawia się dopiero po ostatniej próbie, z czytelnym powodem (np. „połączenie odrzucone lub host nieosiągalny (ConnectException ← ClosedChannelException)” zamiast „(null)”). Gdy adres publiczny nie odpowiada z wnętrza serwera, plugin sprawdza ten sam plik lokalnie (`verify-local-url: auto` = `http://127.0.0.1:<port>`); jeśli lokalnie jest właściwy plik, w logu jest tylko informacja o braku NAT loopback, nie alarm. Nowa wersja paczki lub wyłączenie pluginu przerywa trwające sprawdzanie. Wynik („adres działa, SHA-1 zgodny” albo ostrzeżenie) jest w logu. `/interakcje status` pokazuje źródło, SHA-1, URL i liczbę graczy z wczytaną paczką.

BetterModel nie ma własnego serwera WWW ani nie wysyła paczki graczom. Umie tylko scalić swoją paczkę z paczkami innych pluginów (`merge-with-external-resources`: Nexo, Oraxen, ItemsAdder, CraftEngine), a żadnego z nich tu nie ma. Dlatego najprostsza niezawodna droga to squaremap jako hosting i wysyłanie przez Paper API. Jeśli serwer ma już `resource-pack` w `server.properties`, wyłącz jedno z dwóch, bo inaczej gracz dostanie dwie paczki.

## Jak dodać nową animację

**Generator (zalecane)**: animacje są pisane kodem w `animations-src/` (Python 3, sama biblioteka standardowa):

- `rig.py`: szkielet gracza z BetterModel (kości `player_root`, `phip_hip`, `pw_waist`, `pc_chest`, `h_ph_head`/`ph_head`, `pra_right_arm`, `prfa_right_forearm`, `pla_left_arm`, `plfa_left_forearm`, `prl_right_leg`, `prfl_right_foreleg`, `pll_left_leg`, `plfl_left_foreleg`, `pri_`/`pli_` przedmioty, `cape_cape`), kinematyka i IK dwóch kości. Geometria pochodzi z `base/steve_base.bbmodel` (model `steve` z BetterModel, licencja MIT, © toxicity188).
- `motion.py`: klatki kluczowe z wygładzaniem (easing), warstwy proceduralne (oddech, kołysanie), IK i zapis do formatu Blockbench 5.
- `clips.py`: wszystkie animacje i katalog `ANIMATIONS` (typ, ustawienie, odległość, klipy ról). Ręce w kontakcie z partnerem są liczone IK względem jego rzeczywistego, animowanego ciała.
- `generate.py`: zapisuje `src/main/resources/bettermodel/` (`--report` wypisuje błędy zasięgu IK i odległości głów, `--check` sprawdza aktualność plików; robi to też test JUnit, gdy jest Python).
- `preview.py` (wymaga Pillow): rysuje klatki każdej animacji w czterech widokach do PNG.

Nowa animacja: dopisz funkcję w `clips.py`, dodaj ją w `build()` i w `ANIMATIONS`, uruchom `python animations-src/generate.py`, podepnij ją w `config.yml` (`animation: <id>` w interakcji albo nowy wpis w `dances`/`emotes`) i zbuduj plugin. Konwencja osi (Blockbench 5): postać patrzy w −Z, prawa strona to +X, 16 px = 1 blok. Tułów i głowa: X<0 to pochylenie do przodu, Y>0 obrót w lewo, Z>0 przechył w lewo. Ramię: X>0 unosi do przodu, prawe Z>0 i lewe Z<0 unoszą bokiem. Przedramię X>0 zgina łokieć. Udo X>0 unosi do przodu, łydka X<0 zgina kolano.

**Blockbench**: otwórz `plugins/BetterModel/players/interakcje.bbmodel` (format „Generic Model”), dodaj animację na kościach jak wyżej (nie zmieniaj nazw kości ani geometrii) i zapisz. Ustaw `animations.install-models: false`, żeby plugin nie nadpisał pliku. Opisz animację w `plugins/Interakcje/animations-custom.json` (format jak `bettermodel/animations.json`: `clips.<nazwa>` z `model`, `length`, `loop`, `events.impact`, a w `animations.<id>` `type`, `arrangement`, `distance`, `roles`). Potem `/bm reload` i restart albo `/interakcje reload`. Nowa komenda wymaga restartu.

## Uprawnienia

| Uprawnienie | Domyślnie | Opis |
|---|---|---|
| `interakcje.use` | wszyscy | korzystanie z interakcji, póz i `/interakcje` |
| `interakcje.<id>` | wszyscy | konkretna interakcja, np. `interakcje.kiss`, `interakcje.slap` (rejestrowane automatycznie dla każdej interakcji z configu) |
| `interakcje.sit`, `interakcje.lay` | wszyscy | siadanie i leżenie |
| `interakcje.dance`, `interakcje.emote` | wszyscy | tańce i emotki |
| `interakcje.bypass.cooldown` | op | pomijanie cooldownów |
| `interakcje.admin` | op | `/interakcje reload` |

## Konfiguracja (`config.yml`)

- `settings`: promień opisu RP, czas ważności prośby, globalny anty-spam, `use-display-names` (nicki z pluginów od nicków), klucze metadanych vanish, komenda puszczania ręki.
- `menu`: `shift-right-click`, `require-empty-hand`, `filler` (wypełniacz pustych slotów).
- `interactions.<id>`: `name`, `description`, `command`, `aliases`, `icon`, `slot`, `consent`, `target` (`required`/`optional`), `range`, `cooldown`, `exempt-gamemodes`, `request-text`, `broadcast`, `broadcast-no-target`, `effects` (`swing`, `face-each-other`, `nod`, `knockback`, `knockback-up`, `particles`, `sounds`), opcjonalnie `hold` (`duration`, `max-distance`, `interval-ticks`, `particles`). Szczegóły są w komentarzach w pliku.
  - Cząsteczki: nazwy `org.bukkit.Particle` bez dodatkowych danych (np. `HEART`, `CRIT`, `HAPPY_VILLAGER`, `ELECTRIC_SPARK`, `END_ROD`).
  - Dźwięki: klucz z rejestru (`entity.chicken.egg`) albo nazwa stałej (`ENTITY_CHICKEN_EGG`).
  - Nieznane nazwy są logowane przy starcie i pomijane.
- `interactions.<id>.animation`: animacja postaci z katalogu (`hug`, `kiss`, `hold`, `pat`, `highfive`, `slap`, `wave`, `bow`; `""` = same efekty).
- `sit`, `lay`: nazwa, opis, komenda, aliasy, cooldown, opis RP (`""` wyłącza opis), `right-click-stairs`, `seat-height-offset`, `pose`, `sit.variant` (`AUTO`/`FLOOR`/`CHAIR`), `lay.animated-pose` (`NONE`/`SLEEPING`/`SWIMMING`).
- `dances`, `emotes`: komenda, aliasy, cooldown i lista (`name`, `description`, `animation`, `broadcast` z `<player>`, `effects`).
- `animations`: animacje postaci (opis kluczy w pliku), `resource-pack`: paczka zasobów (niżej).
- `messages`: wszystkie komunikaty (MiniMessage, tag `<prefix>`). Nicki są wstawiane jako zwykły tekst, nigdy nie są parsowane jako MiniMessage. Brakujące klucze biorą wartość domyślną z jara.

Dane graczy (wyłączone interakcje, blokady) trafiają do `plugins/Interakcje/players.yml` i są zapisywane asynchronicznie, przy wyłączeniu synchronicznie.

### Przeładowanie

`/interakcje reload` przeładowuje ustawienia, komunikaty, parametry interakcji (zasięg, cooldown, efekty, teksty, ikony) i menu. **Nowe interakcje, zmienione nazwy komend i aliasy wymagają restartu serwera**, bo komendy Brigadier są rejestrowane przy starcie. Usunięta lub wyłączona (`enabled: false`) interakcja przestaje działać od razu.

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
