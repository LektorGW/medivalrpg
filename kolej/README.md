# Kolej — królewski przewoźnik

Paper 26.3 build 218, Java 25. Kolej jest uzgodnionym wyjątkiem od ograniczenia epok: działa od początku.
Sieć, budowę i rozkład ustala zarządca (`kolej.admin`). Firmy należące do graczy pozostają zapowiedzią.
Na MEDIVALRPG lista `firma.manager-uuids` wskazuje wyłącznie konto WorldSteward. Sam OP innego gracza nie daje
zarządu koleją; licencja maszynisty nie zmienia tej listy. Konsola pozostaje dostępna operatorowi serwera.

## Instalacja i modele

Wymagane Kraje i Skarbiec. BetterModel 3.6.0-SNAPSHOT-535 i aktualna serwerowa paczka są potrzebne do jazdy
z widocznymi modelami. Plugin instaluje dziewięć modeli taboru i szesnaście modeli infrastruktury `.bbmodel` w `plugins/BetterModel/models`, bez nadpisywania
istniejących. Po instalacji trzeba wczytać modele przez BetterModel i rozesłać przebudowaną paczkę przez
istniejące Interakcje. Dopóki modele nie są gotowe, domyślnie odjazd jest blokowany.
Kod nie uruchamia ani nie restartuje serwera produkcyjnego.

Parowóz, tender, wagon pasażerski, salonowy, kryty, węglarka, platforma, cysterna i hamulcowy mają własne tekstury.
Koła obracają się w ruchu z prędkością zależną od jazdy; dym i odgłosy powstają w grze.
Wnętrza i punkty siedzeń opisano w `tools/MODELS.md`. Podgląd PNG nie zastępuje sprawdzenia w kliencie.

## Firma i budowa

1. `/kolej firma przypisz <kraj>` — państwo właściciela, przed założeniem sieci.
2. `/kolej firma wplac <md>` — przelew z własnego konta bankowego na `kolej:przewoznik`.
3. `/kolej stacja dodaj <id> [0|90|180|270]`, następnie `/kolej stacja potwierdz`.
4. `/kolej tor planuj <od> <do>`, następnie `/kolej tor potwierdz`. Projektant bada już wygenerowany teren,
   wybiera przebieg, mosty i nasypy oraz trakt obok toru. Do długich tras dodawaj stacje pośrednie.
   Przed pomiarem możesz zaznaczyć do 12 miejsc poleceniem `/kolej tor punkt`; `/kolej tor wyczysc` usuwa punkty.
5. `/kolej droga planuj <od> <do>` tworzy projekt bez szyn; potwierdza się przez `/kolej tor potwierdz`.
6. `/kolej tor status`, `/kolej tor wznow <id>`, `/kolej tor anuluj <id>`.

### Styl toru — kod przygotowany, nieaktywny

`/kolej tor planuj <od> <do> [realistyczny|klasyczny]`; bez trzeciego argumentu obowiązuje `budowa.styl-toru`
(domyślnie `realistyczny`). Wnioski z panelu używają ustawienia z configu. `/kolej droga planuj` zawsze buduje
klasyczny trakt bez szyn. Styl wybiera się raz, przy pomiarze; plan i przyjęte zlecenie już się nie zmieniają.
Nie dotyczy to ukończonej bazy, istniejących zleceń ani zatwierdzonego, zahashowanego planu głównej linii.

Styl realistyczny (bloki vanilla, bez modów klienta) to warstwa na niezmienionym planie klasycznym:
podkłady z okorowanego świerku co drugi metr i tłuczeń (żwir) pomiędzy, pobocze tłucznia i skos z płyt andezytu,
poszerzona skrajnia budowli (±2 bloki do 4 m nad szyną), rowy odwadniające w przekopach i na równym terenie,
stopień skarpy przekopu, trawiaste skarpy nasypów, trakt po prawej stronie dwa bloki dalej od toru
(gdzie teren nie pozwala na trakt, także tam powstaje rów lub skarpa), murowane wiadukty
(filary na pełną szerokość, łuki z odwróconych schodów, ciągły murek) oraz słupki hektometrowe co 100 komórek
toru i dwukrotnie wyższe kilometrowe co 1000. Na szlaku nie ma latarni. Projektant stosuje nachylenie do 4%
oraz droższe i rzadsze załamania kierunku; gdy nie znajdzie przebiegu, dodaj punkt pośredni albo wybierz `klasyczny`.
Żaden dodany blok stały nie wchodzi w skrajnię pociągu 3×3 ani w strefę kontroli odbioru (9×9 na łukach);
słupek czy murek w takim miejscu jest pomijany. Cena to nadal liczba operacji planu, pokazana przed potwierdzeniem;
plan realistyczny jest droższy od klasycznego. Szerszy obrys zwiększa szansę trafienia drzewa lub zabudowy,
które blokują wycenę tak samo jak dotąd.

Każdy projekt pokazuje koszt przed potwierdzeniem. Robotnicy stawiają rzeczywiste bloki porcjami; plac budowy
przetrwa restart. Chronione są kontenery, istniejąca zabudowa i regiony WorldGuard. Drzewa nie są automatycznie
usuwane. Bloki naturalnego kamienia czy ziemi nie zawierają informacji, czy położył je gracz — służy do tego
ochrona kraju/WG. Budowa poza załadowanymi chunkami czeka. Anulowanie nie cofa ukończonych prac i nie zwraca kosztu.

Przewoźnik jest prywatny: zarząd należy do wskazanego gracza, niezależnie od obywatelstwa i sojuszy.
Na ziemi państwa wymagana jest zgoda władcy `/kolej zgoda tak`; `/kolej zgoda nie` ją odwołuje.
Zgody sprawdzane są ponownie podczas robót i przejazdów. Migracja zachowuje wcześniejszą zgodę kraju
założyciela tylko raz; po jej cofnięciu nie jest nadawana ponownie. `ownerCountry` pozostaje historycznym
źródłem finansowania bazy, nie warunkiem międzynarodowego dostępu.
Do kraju NPC zarządca składa jawny wniosek `/kolej zgoda <kraj-NPC>`; władcy przyjmują handlowe wnioski,
gdy `zgody.npc-przyjmuja-wnioski: true`. Bez zapisanego wniosku nie ma zgody.

## Składy i rozkłady

- `/kolej pociag kup <id> <stacja>`; `/kolej wagon dodaj <pociag> <typ>` kupuje wagon.
- Typy: `pasazerski`, `salonowy`, `kryty`, `weglarka`, `platforma`, `cysterna`, `hamulcowy`.
- `/kolej wagon odczep <pociag>` przenosi wagony pustego, stojącego składu do rezerwy firmy.
  `/kolej wagon dolacz <pociag> <typ>` korzysta z tej rezerwy.
- `/kolej linia utworz <id> <stacja1> <stacja2> [kolejne]`.
- `/kolej linia rozklad <id> <minuty>` lub `<HH:MM,HH:MM>`; czas Europe/Warsaw.
- `/kolej linia przydziel <linia> <pociag>` i `/kolej linia wlacz|wylacz <linia>`.
- `/kolej linia bilet <linia> <md>` ustala bilet. Pasażer płaci przelewem na konto firmy.
- `/kolej`, `/kolej wejdz <pociag>`, `/kolej zejdz` — wybór składu i miejsca.

Ciśnienie rośnie po rozpaleniu, a jazda zużywa węgiel i wodę. Brak paliwa zatrzymuje parowóz; ogrzewanie suchego
kotła uszkadza go. Sygnalizacja rezerwuje jednotorowe odcinki, a automat i nadzór człowieka hamują przed sygnałem.
Na stacjach tender pobiera rzeczywisty węgiel i wodę z zarejestrowanych kontenerów. Opłata obsługi przechodzi
z konta firmy na `kolej:obsluga`; sam przelew nie tworzy surowców. W bazie osobno opłacany jest serwis naprawczy.
Końcowy postój umożliwia przejazd powrotny. Fizyczne obieganie składu przez lokomotywę nie jest symulowane.

## Stanowiska wody i węgla — kod przygotowany do testów, bez wdrożenia

Zarządca celuje w pojedynczą skrzynię lub beczkę w odległości do 6 bloków:

- `/kolej infrastruktura dodaj <id> <zuraw|wieza|pompa|zbiornik|wegiel> <stacja> <0|90|180|270>`.
- `/kolej infrastruktura tankuj <id> <pociag> <1..64>` (alias `obsluz`) — polecenie niezależne od hitboxu modelu.
- `/kolej infrastruktura lista`, `status`, `usun <id>`.

Model jest umieszczany nad kontenerem. Stanowisko leży do 32 bloków od stacji/bazy, a stojąca lokomotywa
do 16 bloków od stanowiska. Model musi być wczytany w BetterModel; jego gabaryty po obrocie muszą mieścić się
w pustej, załadowanej przestrzeni poza skrajnią torów. Firma musi mieć zgodę terenu; WorldGuard jest respektowany.
Brak zgody, brak kontenera, inne PDC lub niewczytany chunk odmawiają obsługi. Magazyn ładunkowy i stanowisko
obsługi wymagają osobnych kontenerów. Nie zmienia się bloków terenu ani nie stawia encji na metr toru.

Zwykły `COAL` daje 10 kg, zwykły `WATER_BUCKET` 250 l i zwraca jedno `BUCKET` do kontenera. Przedmioty nazwane
lub z własnymi danymi są pomijane. Cała partia musi mieścić się w tendrze, a puste wiadra w magazynie.
Istniejące taryfy `koszty.wegiel-kg` oraz `koszty.woda-100l` wyznaczają koszt obsługi; zerowa taryfa nadal
zużywa przedmioty. Brak środków lub zapasów nie uzupełnia tendera. Automatyczna obsługa przy niskich zapasach
próbuje najwyżej raz na minutę po odmowie; udany transfer pozwala od razu uzupełnić drugi surowiec.
Powiadomienia zarządców o rzeczywistej przyczynie odmowy mają odstęp 2 minut na pociąg.
Shift+PPM w rzeczywisty kontener pokazuje informacje; kliknięcie hitboxu modelu nie jest obsługą tankowania.

Rejestr `infrastructure.json` jest ograniczony do 64 stanowisk i jednej transakcji naraz. Zapisuje zamiar
przed pobraniem opłaty i przedmiotów. Skład stoi bez spalania/iniektora do zakończenia zapisu.
Przed pobraniem przedmiotów potwierdza się trwałość opłaty: adapter konkretnego Skarbca wywołuje opcjonalne
publiczne `bankDurable()` przez refleksję. Bariera providera wymusza zapis wcześniejszych operacji bankowych
i przekazuje błąd zapisu do koordynatora. Future jest sprawdzany na głównym wątku; callback nie dotyka Bukkit.
Zamrożone wspólne API pozostaje bez zmian. Niezgodny provider lub brak potwierdzenia w 10 sekund pozostawia blokadę.
Po pobraniu przedmiotów zamiar przechodzi w `COMMITTING`; `kolej.json` zapisuje zapasy tendera wraz z
`supplyReceipt` równym identyfikatorowi transakcji. `RailStore` zwraca potwierdzenie dopiero po wymuszeniu zapisu
i atomowej podmianie pliku. Dopiero to potwierdzenie pozwala wyczyścić zamiar i zwolnić skład.
Jednoznaczna odmowa po pobraniu opłaty cofa opłatę; odmowa przyjęcia do tendera cofa również kontener,
jeśli jego stan wciąż dokładnie odpowiada zapisowi. Przy niejednoznacznej awarii lub niedokończonym zamiarze
po restarcie moduł blokuje obsługę i ruch dotkniętego składu, bez ponownego naliczania i bez zgadywania zwrotu.
Administrator musi porównać dziennik transakcji konta, PDC/zawartość kontenera, tender oraz oba pliki danych,
sprawdzić zapisany `supplyReceipt`, zachować kopie i dopiero po uzgodnieniu stanu naprawić zapis.
Sam istniejący kwit w pamięci lub kwit tendera przy niepotwierdzonych pozostałych danych nie zwalnia blokady.
Nie ma komendy omijającej tę blokadę.
Zapis chunków Minecraft, kolej.json i ledger ekonomii nie stanowią jednej transakcji systemu plików;
nie deklarujemy odporności na utratę zasilania w każdej mikrosekundzie. Nie przywracać samej starej kopii rejestru.

Funkcjonalne są cztery stanowiska wody, bunkier węglowy oraz opisany niżej semafor. Pozostałe dziesięć modeli jest zasobami wizualnymi:
nie dodano sterowania modelem ręcznej zwrotnicy, mostem obrotnicy ani mapowania blokowych modeli torów.
Dotychczasowe rezerwacje i fizyczne zwrotnice runtime nadal sterują ruchem. Nowe stanowiska nie powstają
automatycznie w ukończonej bazie; trzeba je jawnie zarejestrować. Oględziny klienta i docelowy szlak wymagają
osobnego odbioru; nie zmieniano produkcji.

## Semafor mechaniczny i fizyczna kontrola torów — kod przygotowany do odbioru

Zarządca celuje w pełny blok fundamentu: `/kolej semafor dodaj <id> <wezel> <odcinek> <0|90|180|270>`.
Fundament musi leżeć do 16 bloków od chronionego węzła, poza skrajnią stacji, odcinka i manewrów bazy;
nad nim trzeba zostawić miejsce na maszt i cały ruch ramienia. Obrót odpowiada wyjazdowi na wskazany odcinek:
0 = południe (+Z), 90 = zachód (-X), 180 = północ (-Z), 270 = wschód (+X).
`/kolej semafor lista` pokazuje aktualny sygnał i powód STOP, `usun <id>` usuwa semafor z bliska.
Nie ma komendy wymuszającej JEDŹ. Limit to 64 zapisane semafory, po jednym modelu pakietowym na widoczny obiekt.

Semafor jest wizualnym powtórzeniem faktycznej rezerwacji, a nie nowym źródłem zezwolenia ruchu.
Kość `arm` istniejącego `kolej_semafor_mechaniczny` otrzymuje pozę 0° (STOP) lub 45° (JEDŹ).
Adapter BetterModel dodaje obrót i kompensację translacji wokół zawiasu na Y=77,5/16; plik modelu,
generator, tekstury i liczba 16 modeli infrastruktury pozostają bez zmian. Nie wykonano odbioru ramienia w kliencie.
Nie zakładamy, że kliknięcie modelu uruchamia komendę.

JEDŹ wymaga kompletnej klatki symulacji, aktywnego prowadzonego składu, aktualnej rezerwacji w poprawnym kierunku,
wyłącznego przypisania węzła/odcinka i bloku wejściowego, poprawnego ustawienia mijanej zwrotnicy oraz fizycznego
sprawdzenia całego wybranego odcinka. Brak danych, utrata rezerwacji, manewry, awaria, konflikt, brak szyny,
niewczytany chunk, złe ułożenie szyny lub przeszkoda oznaczają STOP. Po restarcie stan JEDŹ nie jest odtwarzany z pliku.
Kontrola wizualnego zezwolenia jest ograniczona do 224 m i 256 komórek; dłuższy niepotwierdzony odcinek zachowuje STOP.
Wskazanie nie zastępuje nadzoru ruchu w runtime i nie zmienia rozkładu.

Runtime sprawdza także zwykłe szyny przed każdym krokiem jazdy. Korzysta z kanonicznych `TrackCell`,
którym przypisuje monotoniczne odległości tego samego wygładzenia Chaikina, po którym porusza się pociąg.
Nie sprawdza `floor()` wygładzonej pozycji; łuk i pochylenie nie wskazują przez to sąsiedniego bloku zamiast szyny.
Odcinek musi pozostać BUILT i mieć niezmienioną, ciągłą geometrię. Kontrolowane są rzeczywista szyna, jej kształt
oraz załadowana skrajnia 3×3, 4 bloki w górę (5 na pochyleniu). Brak potwierdzenia ogranicza ruch przed komórką
z buforem 6 m obejmującym czoło lokomotywy. Kontrola ma limit 224 m / 256 komórek na wywołanie i nie wczytuje
ani nie generuje chunków. Po dojściu do limitu ruch pozostaje ograniczony do sprawdzonego fragmentu.

## Maszyniści

`/kolej maszynista nadaj <gracz> [pociag|linia]`, `odbierz <gracz>`, `lista` — trwała licencja lokalna.
Alternatywnie istnieje uprawnienie `kolej.maszynista`; `kolej.admin` również pozwala prowadzić.
Licencja nie zmienia rang państwa ani nazw na czacie.

`/kolej prowadz <pociag>` zajmuje kabinę stojącego parowozu.
`/kolej jedz <pociag> <stacja>` ustala przejazd; `/kolej ster <pociag> <0..100>` i
`/kolej hamulec <pociag> <0..100>` sterują regulatorem i hamulcem. Zejście/rozłączenie uruchamia hamowanie.

`/kolej npc maszynista zatrudnij <imie>`, `lista`, `przydziel <id> <pociag>`, `zwolnij <id>`.
NPC jest widoczny w kabinie, prowadzi według rozkładu i otrzymuje dzienny żołd ze środków firmy.
Brak żołdu wstrzymuje jego pracę. Osobna rola palacza nie jest obecnie zaimplementowana.

## Baza przewoźnika

`/kolej baza zbuduj [0|90|180|270]` → `/kolej baza potwierdz`: sześć stanowisk wachlarzowej parowozowni,
obrotnica, warsztat z kanałem, skład węgla, wieża wodna, biuro i kwatery.
Przygotuj wolny teren. Ręczny projekt wymaga ziemi własnego kraju; jednorazowe zlecenie królewskiej bazy
może powstać na niezasiedlonym terenie do 128 bloków od granicy.

`/kolej baza wprowadz <pociag> <baza> <1..6>` wykonuje powolny manewr pustego parowozu z tendrem od bramy,
obrót rzeczywistego mostu i wjazd na stanowisko. `/kolej baza wyprowadz <pociag>` wykonuje manewr odwrotny.
Przed manewrem odczep wagony i opuść ster. Podejście i obrotnica mają wspólną wyłączną rezerwację.
Manewry wymagają dostępnego terenu, zgód i załadowanych chunków; gracz na moście blokuje obrót.

Opcjonalny `commission.json` zapisuje **wcześniej zleconą przez administratora** jedną bazę.
Format: `enabled,id,country,world,x,groundY,z,rotation` (ćwierćobroty 0–3), `maxCost` (md).
Po uruchomieniu wykonywana jest ponowna walidacja i wycena; środki pochodzą ze skarbca wskazanego kraju.
Cena nie może przekroczyć limitu. `commission-state.json` jest dziennikiem finansowym; stanu `BLOCKED`,
`CHARGING`, `FUNDED` lub `PAID` nie należy usuwać ani automatycznie ponawiać bez audytu wpłat i budowy.
Nieprawidłowy dziennik finansowy nie jest automatycznie cofany do `.bak`.

## Ładunki i trwałość

`FREIGHT-README.md` opisuje rejestrację rzeczywistych magazynów, zlecenia, regularność i dziennik przeładunków.
Towar rozładowuje się dopiero po potwierdzonym rzeczywistym przejeździe do wyznaczonej stacji.
Rozkład jazdy i zlecenie ładunku są osobnymi decyzjami zarządcy.

`kolej.json` przechowuje sieć, rozkład, zapasy, składy, licencje, NPC i pozycję manewrów. Dane są zapisywane
asynchronicznie przez jednego pisarza, z atomową podmianą i kopią. Część zależy także od danych świata i Skarbca;
nie jest to jedna wspólna transakcja odporna na awarię dysku. Niejasne operacje finansowe/przeładunki wymagają audytu.
Testy jednostkowe i lokalny Paper nie potwierdzają płynności z klientem ani zgodności pasażerów z GrimAC.

`works/jobs.json` (zlecenia budowy; kod przygotowany 2026-10-29, pakiet `deploy-railperf-20261029/`, nieaktywny):
format bez zmian (bajty jak Gson, starszy jar czyta plik). Zapis w tle, jeden pisarz, koalescencja — nowszy stan zastępuje
oczekujący; potwierdzenie dopiero po fsync pliku tymczasowego i atomowej podmianie. Poprzedni plik nie jest ponownie
parsowany, `.bak` to twarde dowiązanie poprzedniej wersji, a niezmienione tablice wpisów są kopiowane z ostatniego pliku
(tylko gdy plik na dysku jest dokładnie tym zapisanym). Robotnicy nie czekają na zapis postępu (`budowa.zapis-postepu-ms`,
domyślnie 5000). DONE jest widoczne dla płatności, odbioru, `/kolej pociag kup`, uruchomienia linii i kolejnej części
importu dopiero po trwałym zapisie (`durablyDone`). Błąd zapisu zatrzymuje budowę (jak dotąd) i jest widoczny w `/kolej siec status`.
Przy zatrzymaniu serwer czeka do 60 s na ostatni zapis; po awarii robotnicy powtarzają najwyżej ostatnie kilka sekund
bloków (stan docelowy jest akceptowany bez zmiany).

## Pauza budowy sieci — pakiet przygotowany 2026-10-29, nieaktywny

Zarządca używa `/kolej siec wstrzymaj` i `/kolej siec wznow` bez restartu po aktywacji nowego jara.
`status` i `postep` pokazują PAUZĘ; dyspozytornia `#/kolej` otrzymuje ją przez istniejące pole `network.commission`.
Pauza jest zapisywana w `network-commission/paused` (niedokończony `paused.tmp` też blokuje start).
Nie zmienia formatu `works/jobs.json`, identyfikatorów ani kursorów zleceń. Dotyczy części bieżącego importu sieci;
inne ręczne budowy i ruch pociągów zachowują swoje zasady.

Komenda działa na głównym wątku po bieżącej operacji bloku. Zatrzymuje kolejne części importu, porcje wyceny,
pobieranie opłat, robotników, oznaczanie DONE i odbiór sieci. Już rozpoczęty zapis/odczyt w tle może się zakończyć;
przelew wykonany przed pauzą kończy wyłącznie zapis potwierdzenia PAID, bez następnego przelewu ani uruchomienia robót.
Przed płatnością transakcja zapisuje ponownie INTENT (dowód, że nie pobrano opłaty), więc czysty restart w pauzie
pozwala kontynuować ją bez drugiego zlecenia. Nagła awaria w niejednoznacznym DEBITTING nadal wymaga audytu.
Wznowienie zachowuje zabezpieczenia i błędy; błędna wycena wymaga osobnego `/kolej siec ponow` po usunięciu przyczyny.

Brak poprawnego `start.ready` przy starcie blokuje także istniejących robotników importu sieci i ich transakcje.
`wznow` sprawdza niezmienność planu i istniejący fingerprint źródła, zapisuje `start.ready` z SHA aktualnego request.json,
a dopiero potem usuwa pauzę. Nie trzeba przenosić `start.ready.before-batch2`; historyczny plik zostaje bez zmian.
Pauza ma pierwszeństwo nad poprawnym `start.ready`. Błąd zapisu pauzy zatrzymuje prace w pamięci, ale zgłasza brak trwałości.
Zapis znaczników odbywa się w tle (fsync i atomowa podmiana). Do potwierdzenia zapisu prace stoją;
`status` odróżnia zapis w toku, potwierdzoną PAUZĘ i błąd zapisu. Przy błędzie trzeba sprawdzić dysk i ponowić komendę.
Nie jest to transakcja obejmująca cały świat i bank.

Nowy jar wymaga pierwszej aktywacji podczas przyszłego restartu; samo przygotowanie/wgranie do `plugins/update/`
nie dodaje komendy do już działającego jara 04d3189f. Instrukcje i rollback: `deploy-kolej-pause-20261029/README.md`.
Wpisy Menu są dodatkami do scalenia, nie zostały wdrożone do Menu.

## Uruchomienie linii po odbiorze — kod przygotowany 2026-10-29, nieaktywny

`/kolej uruchom` (lista planów), `/kolej uruchom <plan>` (sprawdzenie i koszty, nic nie zmienia),
`/kolej uruchom <plan> potwierdz` (wykonanie). Plany są w `plugins/Kolej/uruchomienie.yml` (domyślny plan `glowna`:
linia `krolewska` baza_krolewska ↔ hub-j01). Działa wyłącznie po DONE wszystkich części i fizycznym odbiorze torów
(stacje z `commissionedNodeJobs`, odcinki BUILT w obie strony); nie oznacza niczego jako DONE.

Kolejność: wczytanie chunków stacji i magazynów (bez generowania) → pełne sprawdzenie (odbiór, modele BetterModel, beczki
wody/węgla z wolną bryłą modelu i skrajnią, tablice, limity, saldo) → trwały zapis zamiaru → płatności z `kolej:przewoznik`
(parowóz z tendrem `koszty.pociag`, każdy wagon `koszty.wagon`, zatrudnienie `koszty.zatrudnienie` + pierwszy żołd) — każdy
opłacony element powstaje w tym samym ticku → trwały zapis → linia, rozkład, bilet, tablice → rejestracja stanowisk wody
i węgla na istniejących beczkach. Ponowienie niczego nie kupuje drugi raz. Restart w trakcie płatności blokuje plan do
`/kolej uruchom <plan> rozlicz zaplacone|niezaplacone` po sprawdzeniu `/skarbiec log kolej:przewoznik` (wpisy `[uruchomienie:<id>]`).

- **Palacz NPC**: `/kolej npc palacz zatrudnij|przydziel|lista|zwolnij`; żołd `firma.dzienny-zold-palacza` (domyślnie 60 md),
  maszynista nadal `firma.dzienny-zold`. `zaloga.palacz-wymagany: true` — NPC prowadzi tylko z opłaconym palaczem.
  Brak żołdu wstrzymuje pracę.
- **Powrót wg rozkładu**: `powrot-po-minutach` linii — skład czeka na stacji końcowej do zaplanowanej godziny (dawniej
  odjeżdżał od razu); `/kolej odjazdy` i tablice pokazują także te powroty.
- **Tablice odjazdów**: tekst nad wskazanym blokiem peronu (TextDisplay, bez stawiania bloków), tylko gdy gracz jest w promieniu 48 m.
- **Tender do pełna na końcach linii** (`zapasy.do-pelna-na-koncowkach`, domyślnie true): przejazd pod górę do bazy zużywa
  ~364 kg węgla i ~3040 l wody z 500 kg / 4000 l, więcej niż 75% nad progiem niskich zapasów. Bez beczek z zapasem skład nie odjedzie.
- **Bilet** pobierany przy wsiadaniu (`/kolej wejdz`), przelew pasażer → `kolej:przewoznik`.
- **Postęp budowy**: `/kolej siec postep` (części DONE, opłacone md, bieżąca część, stan odbioru).
- **Własna konstrukcja**: oprócz cegły pod torem → podkład/tłuczeń dopuszczona para dębowa barierka → murek z kamiennej cegły
  (zlecenie poprawki barierek wiaduktów, `deploy-rail-operations-20261029/railing-fix/`).
- **Naturalne zmiany stanu** (`natural-drift.json`, wspólne z generatorem planu): wzrost kelp, odległość liści, snowy itd.

## Zgody krajów NPC i przebudowa własnej konstrukcji — kod przygotowany, nieaktywny

`/kolej zgoda <kraj-NPC> [firma|budzet]` pyta AI Armii (`ArmiaPlugin.npcRailConsent`, bez Armii jak dotąd zgoda).
Odpowiedź: zgoda, zgoda za opłatą albo odmowa (ponowienie po `zgody.npc-ponow-minuty`). Opłata trafia do `country:<gospodarz>`
raz na kraj i płatnika (`npc-consent-fees.json`: zamiar → przelew → potwierdzenie banku → zapłacone); płatnik `budzet` mieści się w limicie
budżetu budowy sieci. Nierozliczony zamiar blokuje ten kraj do kontroli administratora.

Część importu sieci może mieć `ownFile/ownSha`: listę bloków własnej konstrukcji przewoźnika (`minecraft:stone_bricks` pod szynami)
z pochodzeniem z ukończonego zlecenia (np. `baza_krolewska`). Wolno je zamienić wyłącznie na podkład (okorowany świerk, oś x/z)
albo tłuczeń. Importer sprawdza pochodzenie przed wyceną, robotnik ponownie sprawdza blok. Ogólna ochrona zabudowy się nie zmienia.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
