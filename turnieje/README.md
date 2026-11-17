# Turnieje — cotygodniowe turnieje rycerskie

Paper 26.3 build 218, Java 25. Drabinka rycerska, walki konne, melee i pojedynki; harmonogram tygodniowy, ranking, nagrody i skarbiec kraju. Moduł nie zmienia epok, limitów armii ani globalnego miejsca logowania.

## Build

W `turnieje/`: `./gradlew build` (Windows: `.\gradlew.bat build`). Wymagany istniejący `../medival-api/build/libs/medival-api.jar`. Wspólne API pozostaje bez zmian i nie trafia do jara.

Wynik: `build/libs/turnieje-1.0.0.jar`. Testy: `build/reports/tests/test/index.html`. Pakiet nie jest wdrożony; serwer nie był zatrzymywany ani restartowany.

Opcjonalne zależności: Skarbiec (pieniądze), Kraje (organizator i skarbiec kraju), WorldEdit (zaznaczenie). Wpisowe wymaga dostępnej ekonomii. Kwoty konfiguracji są w miedziakach: 1 sr = 10 md, 1 zł = 100 md. Domyślna pula pochodzi z konta `serwer:turnieje`; tworzenie pieniędzy wymaga `source: mint` i jawnego `allow-mint: true`.

## Arena i komendy

1. Zaznacz rogi: `/turniej arena rog 1`, `/turniej arena rog 2` lub użyj WorldEdit.
2. `/turniej arena stworz rynek turniej` — typy: `turniej`, `pojedynek`, `melee`.
3. W punktach walki wykonaj co najmniej dwa razy `/turniej arena spawn rynek dodaj`. Dla melee zapewnij odpowiednio rozstawione punkty.
4. `/turniej arena widownia rynek` ustawia bezpieczne miejsce oczekiwania i widownię.
5. Opcjonalnie: `/turniej arena konno rynek tak`, `/turniej arena ekwipunek rynek zestaw`, `/turniej arena kraj rynek <kraj|publiczna>`.
6. W `weekly` ustaw arenę, dzień, godzinę i strefę `Europe/Warsaw`, potem `/turniej reload`. Domyślnie sobota 20:00, zapisy 30 minut wcześniej. Pusta strefa oznacza strefę serwera.

Gracze: `/turniej zapisz`, `wypisz`, `info`, `ranking [strona]`, `ogladaj [arena]`, `wyzwij <gracz>`, `przyjmij`, `odrzuc`, `pomoc`. Alias: `/arena`.

Organizator: `/turniej zorganizuj <arena> <pula> [wpisowe] [minuty]`; wymagana ranga z konfiguracji i środki kraju. Admin: `/turniej start [arena] [minuty]`, `stop [arena]`, `reload`, `arena lista`, `arena usun <arena>`. Uprawnienia: `turnieje.use`, `turnieje.organize`, `turnieje.admin`, `turnieje.bypass`. `MENU-ADDITIONS.yml` zawiera propozycje akcji do dopasowania do schematu Menu, bez modyfikacji innego pluginu.

## Śmierć, logout i odzyskanie

Tryb `zestaw` przechowuje pełny ekwipunek, zbroję, drugą rękę, pozycję, zdrowie, głód i tryb gry. Kursor musi być pusty. Snapshot zostaje zapisany przez wątek I/O przed zabraniem rzeczy; główny wątek czeka na zakończenie. Zapis używa pliku tymczasowego, `force(true)`, podmiany i `.bak`. Błąd zapisu przerywa operację. Tryb `wlasny` nie kopiuje rzeczy: zużycie trwałości i amunicji jest kosztem gracza.

Śmiertelne obrażenia oznaczają porażkę bez dropu. Faktyczna śmierć nie wyrzuca rzeczy ani doświadczenia; odzyskanie następuje po odrodzeniu. Logout eliminuje gracza, usuwa pożyczony zestaw i pozostawia snapshot do wejścia. Poprawne wyłączenie anuluje sesje i odtwarza dostępnych graczy. Restart nie wznawia walk: uruchamia rozliczenie i odzyskanie rzeczy.

Odzyskany ekwipunek i potwierdzenie odzyskania są zapisywane razem przez `Player.saveData()` przed usunięciem snapshotu. Zapisane potwierdzenie zapobiega ponownemu odtworzeniu rzeczy. Do zakończenia odzyskiwania obowiązują blokady ekwipunku, dropu, podnoszenia, kontenerów i stawiania bloków. Uczestnicy nie mogą craftować ani przenosić rzeczy skrótami okna ekwipunku. Strzały uczestników nie nadają się do podnoszenia.

Teleporty kończą się na głównym wątku, więc opóźniony callback nie zabierze odzyskanego gracza ponownie na arenę. Może to wymagać krótkiego oczekiwania na wczytanie chunka. Trofea mają trwałą kolejkę i potwierdzenie wydania zapisywane razem z ekwipunkiem. Pełny ekwipunek pozostawia trofeum do następnego wejścia; nie ma dropu na ziemię.

## Escrow i niejednoznaczne operacje

Każda sesja ma konto `turnieje:<identyfikator>`. Anulowanie zwraca wpisowe płatnikom i finansowaną pulę do źródła; obiecany mint nie jest zwracany. Rozliczenie nagród ma trwałą listę odbiorców i kwot. Nieudana wypłata pozostaje zobowiązaniem, a udane wypłaty są zapisywane osobno. Restart kontynuuje rozliczenie nagród zamiast zwracać już rozdzieloną pulę jako wpisowe. Nieaktywne depozyty są ponawiane co minutę po odzyskaniu ekonomii.

API Skarbca nie zapewnia idempotentnych przelewów ani wspólnej transakcji z Turniejami. Przed zmianą salda powstaje `money-journal.json`, usuwany po zapisie odpowiadającego stanu. Pozostawiony po awarii dziennik blokuje przy następnym starcie operacje finansowe Turniejów. Wyjątek z API również blokuje dalsze operacje. Przelewy o nieznanym wyniku nie są automatycznie ponawiane.

Administrator musi zachować kopię danych obu pluginów, porównać dziennik, `state.json`, log Skarbca i rzeczywiste salda oraz uzgodnić operacje i pozostałe zobowiązania. Dopiero po ręcznym uzgodnieniu stanu można wycofać aktywny dziennik i ponownie uruchomić plugin w uzgodnionej przerwie. Samo wycofanie dziennika bez rozliczenia może powtórzyć płatność. Nie przywracać automatycznie `.bak`: stara kopia może zawierać już wydane rzeczy lub opłacone zobowiązania. Uszkodzony JSON zatrzymuje ładowanie i wymaga ręcznego odzyskania.

## Walidacja i ograniczenia

Wynik testów: `TEST-REPORT.md`. Testy obejmują drabinkę, melee, harmonogram i DST, podział nagród, częściowe rozliczenia, zwroty, mint, ponowienia, przepełnienie kwot, trwałość snapshotów i dziennika oraz błędy zapisu.

Nie wykonano testu z klientami na Paper, GrimAC, WorldGuard ani rzeczywistym Skarbcem. W osobnym środowisku należy sprawdzić śmierć wymuszoną, logout w odliczaniu i walce konnej, przerwanie procesu podczas przelewu i odzyskania, anulowany teleport oraz pełny ekwipunek przy nagrodzie. Brak gwarancji przy fizycznym uszkodzeniu dysku, niezależnym rollbacku gracza/ekonomii i pluginach ignorujących anulowanie zdarzeń. `Player.saveData()` oraz podmiana JSON nie tworzą wspólnej transakcji między pluginami. Snapshot nie przywraca efektów mikstur usuniętych przed walką.

## Wave17 / 2026-10-28 — opcjonalne przygotowanie istniejącej areny

Helper uruchamiany po odzyskaniu sesji odczytuje `plugins/Turnieje/bootstrap-arena.json`. **Brak pliku albo `enabled:false` oznacza brak jakiejkolwiek pracy i brak nowej areny.** Plik nie jest zasobem domyślnie kopiowanym przez plugin. Przygotowany manifest jest w `deploy-wave17/arena/bootstrap-arena.json`; samo jego przygotowanie nie wdraża go na serwer. Harmonogram, strefę i finansowanie tygodniowej nagrody ustawia zewnętrzny pakiet — ten zakres nie zmienia tych ustawień.

Zakres jest sztywno ograniczony do istniejącej królewskiej areny: właściciel `9d1fe36e-4852-4d18-9b2a-a89146f43e66`, świat `world`, obrys X4933..4955/Z892..910/Y90..97. Pitch X4938..4950/Z897..905 otrzymuje fundament `STONE_BRICKS` na Y89 i `SAND` na Y90; maksymalnie 234 przypisania, w hotbackupie 231 rzeczywistych zmian (14 ziemi i 217 powietrza; trzy piaski pozostają). Nie powstaje urobek, drop, produkcja górnicza, pochodnie ani transakcja finansowa. Jest to zerokosztowe utrzymanie istniejącego obiektu, bez niwelacji trybun i otoczenia. Budowa całej areny ani dowolnej nowej lokalizacji nie jest obsługiwana.

Przed pierwszą zmianą helper sprawdza wszystkie cztery chunki kraju, dostępność usług, ochronę spawnu Bukkit, chronione obszary Kraje i nakładające się regiony WorldGuard (jeśli zainstalowany; niedostępny provider odmawia). Ładuje wyłącznie istniejące chunki bez generowania, utrzymuje cztery własne tickety i zwalnia je po zakończeniu/odmowie. Snapshot obejmuje **3933 pełne stany bloków**, także trybuny i dekoracje Y89..97. Zmieniony blok nawet poza pitchem, TileState, kontener lub encja/gracz w obszarze prac oznaczają odmowę przed wykonaniem kolejnej porcji. Znany snapshot chroni również przed nowymi konstrukcjami gracza z naturalnego materiału. NBT nie dowodzi pochodzenia identycznego stanu odtworzonego w tym samym miejscu; nie należy podmieniać manifestu po awarii, aby obchodzić blokadę.

Obie warstwy powstają po 8 pozycji na tick (dozwolone 1–16), wyłącznie na main thread, najpierw cały fundament, potem piasek. Cały snapshot i warunki kraju/ochrony są ponownie walidowane przed każdą porcją. Na czas pracy obrys jest chroniony przed stawianiem/niszczeniem, płynami, wiadrami, tłokami i eksplozjami. Zmiana wykryta w trakcie zatrzymuje dalsze prace; wykonana bezpieczna część pozostaje do audytu, bez automatycznego usuwania bloków. Podczas przygotowania pitch musi pozostać pusty.

`bootstrap-arena-state.json` jest zapisywany przez istniejący `Storage` i jego kolejkę I/O (temp, force, podmiana, backup). Przed każdą porcją zapisuje fingerprint manifestu i dozwolony prefiks zmian. Po restarcie akceptuje wyłącznie pierwotny stan albo wynik wcześniej zapisanej porcji; nie adoptuje nowych bloków gracza udających wynik naprawy bez zapisanego zamiaru. Kursor jest odtwarzany od początku, a przypisania poprawnych bloków są pomijane. Zmiana manifestu lub niezgodny/uszkodzony dziennik odmawia pracy. `BLOCKED` jest trwałe: administrator po audycie przyczyn może zachować fingerprint/prefiks i wznowić fazę `APPLYING` przy wyłączonym pluginie. Nie usuwać ślepo dziennika częściowego remontu. `DONE` nie rozpoczyna nowej naprawy później zmienionej podłogi.

Dopiero po kontroli wszystkich 117 kolumn podłoża i **dwóch bloków powietrza w każdej kolumnie**, sprawdzeniu widowni i zapisie świata rejestrowana jest arena **`krolewska`**, `ArenaType.TURNIEJ`, kraj właściciela, `mounted=false`, `gear=KIT`. Starty: `(4940.5,91,899.5,-90°)` i `(4948.5,91,899.5,90°)`; widownia: istniejąca platforma `(4936.5,93,907.5,-126.87°,12°)`. Rejestr używa mapy istniejącego Storage, `saveArenas()` i potwierdzonego zapisu przed `DONE`; w razie awarii między zapisem aren a markerem istniejąca identyczna arena jest rozpoznawana, a konfliktująca nie jest nadpisywana. Inne nakładające się areny lub aktywna sesja odmawiają przygotowania.

Izolowany build bez starego katalogu testów: `./gradlew.bat -I wave17-release.init.gradle --project-cache-dir .gradle-wave17-release build --offline --no-daemon`. Wynik w `build/wave17-release/libs/Turnieje-1.0.0.jar`; raport testów w `build/wave17-release/reports/tests/test/index.html`. Pure testy obejmują zakres, zachowanie konstrukcji/hay/kontenerów, własność wszystkich chunków, dwa wolne bloki, widownię, statusy/fingerprint, autoryzowany prefiks po awarii i trwałość poprzez Storage. Gotowy do weryfikacji pakiet i raport są w `deploy-wave17/arena/`. Nie wykonywano aktywacji na produkcji ani zmian innych pluginów/test fixtures. Nie dodano komendy admina, więc istniejące MENU-ADDITIONS pozostaje wystarczające (`/turniej arena lista`, `/turniej info`).

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
