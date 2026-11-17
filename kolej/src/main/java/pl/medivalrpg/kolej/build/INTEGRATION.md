# Infrastruktura kolejowa — kontrakt integracji

```java
RailwayWorks works = new RailwayWorks(plugin, plugin::allowed);
works.onComplete(job -> {
    // RailwayProjects: idempotentnie ustaw powiązany segment na BUILT i zapisz RailData.
});
List<BlockOp> ops = DepotPlan.plan(x, groundY, z, quarterTurns);
RailwayWorks.Preview quote = works.preview(player, ops);
// Autoryzacja i obciążenie konta kolej:przewoznik pozostają po stronie runtime.
RailwayWorks.Result result = works.submit(jobId, world, ops);
// projects.tick(), wywoływany co 2 ticki serwera:
works.tick();
// onDisable:
works.close();
```

## API

- `RailwayWorks(Plugin, Predicate<Location>)`: polityka terenu z `KolejPlugin.allowed(Location)`; aktualne zgody i sojusze sprawdzane ponownie przed każdym blokiem.
- `RailwayWorks(Plugin, KrajeApi)`: domyślnie dzicz; warianty `preview(..., UUID country)` i `submit(..., UUID country)` dopuszczają również kraj właściciela.
- `setClaimValidator(BiPredicate<Location,UUID>)`: zastępuje tylko politykę claims. Nie zastępuje ochrony WorldGuard, kontenerów ani zabudowy.
- `preview(Player,List<BlockOp>)`, `preview(Player,List<BlockOp>,UUID)` → `Preview(cost,materials,obstacles,blocks)`, `allowed()`.
- `quote(World,List<BlockOp>,UUID)` → ten sam `Preview`, wyłącznie walidacja/wycena, bez gracza, podglądu, schedulera i zmian świata. Można użyć do przygotowanego autozlecenia po aktywacji; wywołanie wymaga main thread i załadowanych chunków.
- `submit(String,World,List<BlockOp>)`, `submit(String,World,List<BlockOp>,UUID)` → `Result(accepted,id,cost,reason)`.
- `job(String)` → `Optional<Job>`; `snapshot()` → `Snapshot(version,jobs)`; `restore(Snapshot)` zastępuje bieżącą kolejkę i zapisuje nowy stan.
- `onComplete(Consumer<Job>)`: callback po trwałym zapisie DONE; odtwarzany po restarcie, idempotentność wymagana. Wyjątek odbiorcy powoduje ponowienie. Odbiorca nie powinien modyfikować kolejki podczas wywołania.
- `resume(String)`, `cancel(String)` → boolean. Anulowanie nie cofa bloków i nie nalicza zwrotu.
- `persistenceFailure()` → `Optional<String>`; błąd zapisu wstrzymuje całą budowę do naprawy i ponownego utworzenia usługi.
- `lastPreparationNanos()` → czas ostatniej kompletnej walidacji świata w `preview`/`submit`, do pomiaru na rzeczywistym Paper.
- `tick()`, `clearPreview(Player)`, `close()`.

Wszystkie powyższe metody wywołuj na main thread. Nie ma automatycznego schedulera budowy. Porcja globalna z `budowa.bloki-na-tick` (domyślnie 8, techniczny zakres 1–256), limit czasu 4 ms sprawdzany między operacjami. Przy wywołaniu co 2 ticki serwera daje to maksymalnie 80 bloków/s. Wolny zapis, niskie TPS i budżet czasu zmniejszają tę wartość. Nie wymusza ładowania chunków. Wywołuj jedną instancję usługi na plugin, zamknij starą przed utworzeniem kolejnej.

Koszt to dokładnie `ops.size()` md, także dla już zgodnych i pominiętych bloków. `materials` i `blocks` pokazują faktyczne zmiany po scaleniu współrzędnych, więc nie są podstawą ceny. Powtórzony identyfikator jest odrzucany; `accepted` oznacza kolejkę zapisu, nie potwierdzenie płatności ani trwałego zakończenia. Odtworzone wcześniejsze zlecenie może mieć cenę zapisaną przez wcześniejszą wersję.

`Job(id,world,country,entries,cursor,cost,status,reason)` oraz `Entry(op,before,target)` są niezmiennymi rekordami. Statusy typu `String`: `QUEUED`, `BLOCKED`, `DONE`, `CANCELLED`. Brak świata lub załadowanego chunka pozostawia QUEUED. BLOCKED wymaga jawnego `resume`, które ponownie sprawdzi pierwotny stan terenu; nie zatwierdza zmian gracza. `cursor` liczy zakończone wpisy znormalizowanego planu. DONE oznacza zakończenie w tym silniku; statusy grafu (BUILT) należą do runtime.

## Projekty

Wszystkie generatory zwracają `List<BlockOp>`, y oznacza poziom nawierzchni, tor leży na y+1:

- `DepotPlan.plan(int x,int groundY,int z)` — czysta geometria bez serwera.
- `DepotPlan.plan(int x,int groundY,int z,int quarterTurns)` — main thread, obrót danych bloków Bukkit; centrum obrotnicy jest kotwicą planu.
- `DepotPlan.bays()` → sześć `Bay(index,doorX,doorZ,endX,endZ)` w lokalnych współrzędnych.
- `DepotPlan.trackLinks(int x,int railY,int z,int quarterTurns)` → siedem `TrackLink(bayIndex,cells)`: dojazd `-1`, stanowiska `0..5`. Komórki to istniejące `TrackCell` z bezwzględną wysokością szyn. Nie zawierają dynamicznego połączenia przez środek obrotnicy; runtime sam tworzy węzły i odcinki. Brama DEPOT na `center+rot(0,-18)` leży na dojeździe.
- `Factory.station(int x,int groundY,int z,int length)` — peron z wiatą, tor wzdłuż +Z, peron po +X; długość 8–128.
- `Factory.road(int x,int groundY,int z,int length,int width)` — wzdłuż +Z, szerokość po +X; długość 1–1024, szerokość 1–9.
- `Factory.grade(int x,int groundY,int z,int width,int depth,int maxCut,int maxFill,ToIntBiFunction<Integer,Integer> surfaceTop)` — czysty plan niwelacji prostokąta od minimalnego narożnika. Wykop ograniczony do maxCut, płytki podkład do maxFill, ziemna powierzchnia. Nie rozszerza listy dozwolonych bloków: drzewa i zabudowę odrzuci `quote`. Pomiar obejmujący powierzchnię wody nie zastępuje pomiaru dna: przed użyciem przy wodzie sprawdzić osobno głębokość i podparcie, bo generator nie bada podwodnego terenu.
- `Factory.segment(List<TrackCell>,ToIntBiFunction<Integer,Integer> groundTop,ToIntBiFunction<Integer,Integer> seabed,ToIntBiFunction<Integer,Integer> water,CellWorks.Settings)` — istniejący CellWorks, poprawne kształty szyn, ścieżka czterospójna, nachylenie ≤1, zakaz zakrętu na stoku. Brak wody = Integer.MIN_VALUE.
- `PlanRotation.transform(List<BlockOp>,int x,int y,int z,int quarterTurns)` — main thread; obraca plan lokalny i przenosi do świata. Nie stosować do już przetłumaczonego planu bez odjęcia jego kotwicy.

Baza ma obrys 77×61 bloków, od x−38 do x+38 i z−30 do z+30, wysokość −2…+11. Obejmuje sześć wachlarzowych hal, nieckę i most obrotnicy, warsztat z kominem, wieżę wody z wysięgnikiem, zasobnię węgla, biuro, kwatery z czterema łóżkami i dziedziniec. To bazowa geometria blokowa, bez modeli i obsługi ruchu pojazdów. Fizyczny most obraca osobna `TurntableService` opisana niżej. Sześć torów dochodzi do krawędzi niecki; nie stanowią jednoczesnego połączenia z prostym mostem.

## Ochrona i trwałość

Podgląd wysyła do 512 pozornych bloków w promieniu 64 bloków na 5 s i przywraca rzeczywiste dane; nie edytuje świata. Pełny plan jest walidowany niezależnie od limitu podglądu. Limit z `budowa.max-blokow`, domyślnie 250000, maksymalnie 500000 operacji. Plany scalają operacje na tej samej współrzędnej: ostatnia wygrywa. FILL nie zmienia istniejącego podłoża. Budowa używa fizycznych bloków z wyłączoną natychmiastową fizyką; instalację warto obejrzeć w grze po integracji.

WorldGuard: każdy region i `__global__` odrzuca budowę, również gdy gracz jest administratorem. Brak działającego managera przy aktywnym WG oznacza odmowę. Chronione są wszystkie TileState i materiały spoza jawnej listy naturalnego terenu. Sam Bukkit nie rozróżnia gracza, który położył kamień/ziemię/żwir, od naturalnego podłoża: takie bloki wymagają ochrony claims/WG albo dodatkowego warunku w `allowed`. Nie wycina drzew ani rud. Baza wymaga uprzednio wybranego względnie płaskiego, wolnego miejsca; fundamenty są płytkie, nie wykonują dowolnej niwelacji zbocza. Koszt nie obejmuje osobnego przygotowania terenu.

`<plugin data>/works/jobs.json`: jeden executor, JSON UTF-8, fsync pliku tymczasowego, atomowa podmiana i poprzedni poprawny stan `.bak`. Operacje czekają na zapis przyjętego planu. Postęp zapisywany najwyżej raz na sekundę, status terminalny/blokada od razu, bez pełnego snapshotu przy każdym bloku. Podczas zapisu silnik czeka na jego wynik. Awaria między postawieniem bloku a zapisem kursora jest obsługiwana przez porównanie danych docelowych i idempotentne ponowienie, również przy już postawionym łóżku TileState (blok nie jest ponownie modyfikowany). Częściowo zbudowany świat nie jest transakcyjnie cofany. `close()` usuwa NPC, odtwarza podglądy i zleca końcowy zapis; kolejka executora kończy się asynchronicznie. Przy wymuszonym zakończeniu JVM odtworzenie korzysta z ostatniego trwałego stanu.

Budowniczowie to Mannequin z narzędziem, własnym PDC i bez AI. Usługa ustawia ich przy kolejnych operacjach w bezpiecznym załadowanym miejscu; nie jest to pathfinding ani symulacja noszenia materiałów. Osierocone NPC są usuwane przy starcie i ładowaniu chunków. Nie ma zmian maina, konfiguracji, komend, ekonomii ani wspólnego API.

## Obrotnica — kontrakt do podłączenia

```java
TurntableService turntable = new TurntableService(plugin, plugin::allowed);
Location pivot = new Location(world, depot.x(), depot.groundY()+1, depot.z());
boolean accepted = turntable.start(world, pivot, depot.rotation()*90f, bayIndex, pose -> {
    // pose = OŚ całego zestawu, nie head parowozu!
    Location head = TurntableService.headPose(pose, pivotToHeadDistance);
    // Ustaw head i tender zgodnie ze swoim modelem; zapisz pose w swoim runtime.
});
// Ten sam ticker co 2 ticki:
turntable.tick();
// Po turntable.status().equals("DONE") można zacząć wyjazd na stanowisko.
// onDisable:
turntable.close();
```

`start(World,Location center,float yaw,int index,Consumer<Location>)` → boolean. `yaw` jest **stałą orientacją BAZY w stopniach Bukkit 0/90/180/270**, odpowiadającą `rotation * 90`, nie bieżącemu yaw parowozu. `center.y` jest poziomem szyn. `index` 0–5 odpowiada `DepotPlan.bays()` i `trackLinks()`, **index=6 resetuje most do yaw bazy / dojazdu**. Kąt stanowiska = yaw + 50 − 20×index, z wyjątkiem index=6 → yaw. Uwaga: dojazd ma `TrackLink.bayIndex=-1`, a cel obrotu na dojazd ma index=6. Callback jest na main thread po każdej fizycznej klatce (krok maksymalnie 5°), również po odzyskaniu ostatniego zapisanego zamiaru; otrzymuje środek bloku z aktualnym yaw i pitch=0. `headPose(Location pivot,double forwardOffset)` jest pomocnikiem do przesunięcia head do przodu względem osi zestawu. Runtime wskazał offset 4 m dla obecnego parowozu+tendra bez wagonów.

`tick()`, `busy()`, `status()`, `reason()`, `snapshot()/restore(Snapshot)`, `close()` wywoływane na main thread. Statusy String: `IDLE`, `ROTATING`, `DONE`, `BLOCKED`. Jedna rotacja globalnie na instancję. `start=false` daje powód w `reason()`; po odmowie podczas trwającej rotacji status nadal ROTATING. Zlecenie nie obejmuje płatności. Stan zapisuje niezależnie do `works/turntables.json` (tmp/fsync/atomic/.bak), zamiar każdej klatki jest trwały przed zmianą bloków. Ponowne `start` odzyskuje ostatnią klatkę; bez callbacku runtime usługa sama po restarcie nie rusza pociągu. Nie wymusza ładowania chunków. Zmiana bloków, utrata zgody, WorldGuard, gracz w polu ruchu lub wyjątek callbacku blokują obrót. Przy błędzie callbacku most pozostaje w ostatniej fizycznej pozycji; runtime musi wznowić/ustawić pojazd świadomie.

Most ma nominalnie 19 bloków długości (szyny od −9 do +9), niecka promień 10. Dla zestawu 15 m pozostaje około 2 m rezerwy na każdym końcu przy poprawnym wycentrowaniu. To geometryczna rezerwa, nie walidacja gabarytów modeli: wymiary boczne i pionowe oraz offset head/tender nadal kontroluje runtime. Raster Minecraft daje schodkowe szyny w pozycjach pośrednich. Podczas obrotu pociąg powinien być unieruchomiony w zwykłym silniku jazdy i poruszany wyłącznie callbackiem; pośrednie szyny nie służą do jazdy vanilla wózkiem.

Usługa zmienia rzeczywiste bloki niecki/mostu, ale **nie tworzy graph links, nie rezerwuje tras, nie odczepia tendera i nie prowadzi pociągu do hali**. Graczy wykrywa sama; wyłączność pojazdów, także modeli bez encji, oraz blokadę placu budowy zapewnia runtime. Konieczne jest zakończenie budowy bazy przed startem. Nie podpinano usługi do maina ani RailwayProjects.

Ustalony kierunek manewrów w runtime: rezerwacja dojazdu i stanowiska → wjazd przez node DEPOT przy z−18 → zatrzymanie środka 15-metrowego zestawu na osi (head 4 m przed osią) → wyłączenie zwykłego ruchu → obrót callbackiem `headPose(pose,4)` → po DONE aktywacja tylko właściwego dynamicznego linku → wolny wyjazd po `trackLinks` na odległość 22 → zwolnienie obrotnicy po przejeździe całego tendera. Sześć hal to sześć osobnych zasobów; node capacity=1 przy bramie nie zastępuje rezerwacji obrotnicy. Powrót/reset do dojazdu: `start(...,6,callback)`.

Przygotowanie miejsca x5070/y66/z970: dla rotation=0 minimalny narożnik prostokąta to x5032/z940. Trawa do y74 wymaga maxCut=8 przy groundY=66. Odczyty y83 z logów/liści nie są zgodą na wycinkę. `grade` z maxCut=8 odrzuci taką kolumnę; nawet większy limit nie odblokuje logów/liści w `quote`. Bez ręcznego usunięcia drzew albo wyboru wolnego miejsca nie składać automatycznie odrzuconego planu. Rozpoznana zabudowa i wszystkie kontenery pozostają chronione; naturalny kamień/ziemia położone przez graczy nie mają rozpoznawalnego pochodzenia w samym Bukkit. Nie wykonano niwelacji ani budowy na serwerze.

## Weryfikacja

Izolowany Gradle kompiluje tylko build/depot/world oraz istniejące TrackCell/CellKind, testy tylko w dozwolonych katalogach. Nie weryfikuje maina ani innych zmieniających się modułów. Wynik końcowy: BUILD SUCCESSFUL, 16 testów, 0 błędów. Testy geometrii obejmują unikalność planu, fundamenty szyn, sześć hal, rzeczywiste wyposażenie i łóżka, ciągłość torów, obroty, wszystkie klatki mostu co 5°, punkty przyłączeń w czterech obrotach bazy, reset index=6, ograniczenia niwelacji i krzywizny/nachylenia. Komenda: `gradlew.bat --init-script build/works-check/isolated.gradle test --no-daemon --console=plain`.

Pomiar lokalny Java 25 (15 rozgrzewek, 40 prób, końcowa geometria): 20672 operacje planu bazy, mediana generowania 14,914 ms, p95 28,240 ms. Przy 80 operacjach/s teoretycznie 258,4 s dla całego planu; faktyczna liczba zmian zwykle mniejsza, natomiast IO/TPS/budżet czasu mogą wydłużyć wykonanie. Ten pomiar **nie obejmuje walidacji świata ani WorldGuard**. Jej rzeczywisty czas pokaże `lastPreparationNanos()` po preview/submit/quote; nie uruchamiano testu na działającym serwerze ani nie wykonywano restartu.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
