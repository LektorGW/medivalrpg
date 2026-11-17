# Ładunki kolejowe — API integracji

Moduł jest konsumentem istniejących `KolejPlugin`, `RailData.Train` i grafu. Nie zmienia main/runtime/data,
nie rejestruje sam komend ani harmonogramu i nie wdraża nic na serwer.

## Podpięcie przez głównego agenta

```java
import pl.medivalrpg.kolej.freight.FreightService;
// pole klasy:
private FreightService freight;
// onEnable, po utworzeniu data/graph/runtime:
freight = new FreightService(this);
// istniejący timer, po runtime.tick():
freight.tick();
// gałąź "ladunek" w istniejącym dispatcherze:
freight.command(player(sender), a);
// onDisable, przed zamknięciem pozostałych usług:
if (freight != null) freight.close();
```

Wszystkie wywołania powyżej oraz `freight.status()` wymagają głównego wątku Bukkit.
`command(Player,String[])` przyjmuje tablicę z początkowym `ladunek` albo bez niego; zgłasza
`IllegalArgumentException` do istniejącego handlera komend. Wymaga `kolej.admin`.
Utworzyć dokładnie jedną instancję usługi. Nie wywoływać tick z asynchronicznego timera.
Nie trzeba dopisywać konfiguracji ani zależności Gradle. Nie trzeba rejestrować listenera.

## Komendy zarządcy

```text
/kolej ladunek magazyn <stacja>
/kolej ladunek zlec <pociag> <material> <ilosc> <od> <do>
/kolej ladunek regularnosc <pociag> <minuty|0>
/kolej ladunek status
```

Przykład: `zlec towarowy_1 COAL 512 kopalnia port`, następnie `regularnosc towarowy_1 30`.
`zlec` domyślnie oznacza jedną dostawę. Regularność 1–1440 minut od zakończenia rozładunku;
0 wyłącza kolejne cykle, ale nie anuluje partii już zamówionej/załadowanej.
Zmiana regularności ukończonego zlecenia na dodatnią wznawia je od nowego terminu.
Po wykonaniu jednorazowego zlecenia można zastąpić je nowym.
Jedno aktywne zlecenie i jeden rodzaj materiału na pociąg; brak mieszania partii i nadpisywania aktywnych zamówień.

Magazyn wybiera się celownikiem do 6 bloków: pojedyncza zwykła skrzynia, skrzynia-pułapka lub beczka.
Musi być do 32 bloków od stacji/bazy, w tym samym świecie i na terenie dostępnym firmie przez `plugin.allowed`.
Jeden kontener = jedna stacja. Podwójne skrzynie, shulkery, leje i piece są odrzucane.
Powiązania nie można przepinać komendą, a kontenera z istniejącym kwitem nie można ponownie zarejestrować.
Nie przerabiać zarejestrowanej pojedynczej skrzyni na podwójną. Zmiana typu/utrata kwitu blokuje przewozy przy następnej kontroli.

Usługa nie wybiera kontrahentów, materiałów, cen ani tras, nie uruchamia samodzielnie pociągów i nie ma AI handlu.
Pociąg obsługuje istniejący maszynista i rozkład linii. Regularność zlecenia określa najwcześniejszy kolejny załadunek;
nie zastępuje rozkładu jazdy i nie nadrabia wielu zaległych kursów naraz.

## Pojemność i przedmioty

| Wagon | Sloty | Przyjmowany ładunek |
|---|---:|---|
| KRYTY | 27 | whitelist surowców poza wodą |
| WEGLARKA | 27 | węgiel/drzewny, surowe metale i rudy, kamień, piasek, żwir, ziemia, glina, krzemień |
| PLATFORMA | 18 | kłody/deski, STONE, COBBLESTONE, IRON_INGOT, COPPER_INGOT |
| CYSTERNA | 9 | wyłącznie zwykły WATER_BUCKET |
| PASAZERSKI, SALONOWY, HAMULCOWY | 0 | brak towarów |

Pojemność sumuje wyłącznie wagony zgodne z materiałem, uwzględnia maksymalny stos:
64 dla tych surowców, 1 dla wiader. Dwie węglarki mieszczą 3456 sztuk COAL.
Cysterna przewozi istniejące pełne wiadra 1:1; nie tworzy wody ani pustych wiader.
Usunięcie odpowiednich wagonów wstrzymuje transfer do czasu przywrócenia pojemności.

Whitelist jest jawna w `FreightRules`: surowce sypkie, podstawowe metale i minerały, plony,
podstawowe drewno, skóra/sznurek/pióra/biała wełna. Brak narzędzi, mikstur, kontenerów i dowolnych materiałów spoza listy.
Oprócz typu wymagane jest `!hasItemMeta()` oraz podobieństwo do nowego standardowego `ItemStack` tego typu.
Przedmioty z nazwą, lore, enchantami, PDC (w tym monety Skarbca), custom model/data components itp.
nie są pobierane ani łączone ze zwykłymi stosami. Pozostają na swoich slotach jako zajęte miejsce.
Niezmieniane stosy zachowują oryginalne klony `ItemStack`, nie są odtwarzane z samej nazwy materiału.

Partia jest niepodzielna. Brak całości w magazynie źródłowym lub miejsca na całość w docelowym powoduje oczekiwanie.
Moduł nie generuje towarów, nie sprzedaje ich, nie wywołuje Skarbca ani nie zmienia `RailData.ACCOUNT`.

## Rzeczywista trasa i wspólny Train

Załadunek tylko przy zgodnym `Train.node`, faktycznej pozycji lokomotywy do 2.5 bloku od stacji,
prędkości ≤0.05 m/s i postoju (`!running` albo trwający dwell). Środek pociągu to istniejące `runtime.location(t,0)`.
W drodze freight obserwuje wzrost `Train.distance`, zgodność trasy i zmianę rzeczywistej pozycji.
Potwierdzenie przejazdu wymaga zaobserwowanej drogi co najmniej 95% odległości geometrycznej stacji i opuszczenia źródła.
Uwzględnia również końcowy krok do 3 m po już zaobserwowanym ruchu: istniejący runtime
ustawia `running=false` w ticku przyjazdu i może domknąć ostatnie 2.35 m do peronu.
Sama zmiana `node`, skok licznika bez ruchu lub teleport bez przyrostu dystansu nie uprawnia do rozładunku.
Rozładunek wymaga potwierdzenia przejazdu i rzeczywistego postoju wyłącznie w stacji docelowej.
Zatrzymanie w innej stacji nie opróżnia wagonów. Różne światy i stacje w tym samym punkcie są odrzucane.

`freight.json` jest źródłem prawdy dla partii. `Train.cargo` jest projekcją `{MATERIAL: liczba_sztuk}`;
`cargoDestination` zawiera ID docelowego węzła albo null przy pustym składzie.
Po restarcie freight odtwarza własną partię nawet wtedy, gdy `kolej.json` jest starszy; w ten sposób masa składu nadal zawiera ładunek.
Tylko dokładna poprzednia partia tego samego materiału albo pusty cargo może być zastąpiona przy pierwszej projekcji.
Obcy lub później zewnętrznie zmieniony cargo powoduje blokadę, nie jego usunięcie.
Nie dopisywać własnych dostaw do tych samych map poza modułem freight.

## Trwałość, journaling i restart

Każdy transfer przebiega tak:

1. Na main powstaje plan slotów i `pending=PREPARED` z UUID, kierunkiem, ilością, przed/po oraz czasem.
2. Osobny pojedynczy writer zapisuje UTF-8 do `.tmp`, wykonuje `FileChannel.force(true)`, tworzy `.bak`,
   atomowo podmienia `freight.json`. Brak obsługi atomic move kończy się blokadą, bez nieatomowego fallbacku.
3. Kolejny tick po sukcesie zapisu ponownie sprawdza stację, postój, pojemność, cargo, kwit i pełny oryginalny `ItemStack[]`.
   Jeżeli gracz/lejek zmienił zawartość lub pociąg odjechał, następuje bezpieczne anulowanie planu i zapis bez transferu.
4. W jednej operacji main zmienia się inwentarz, następnie świeży stan bloku otrzymuje PDC `kolej:freight_receipt=UUID`.
   Po tym freight aktualizuje własny ledger, projekcję cargo i zapisuje finalny stan bez `pending`.
5. Nie ma blokującego czekania na futures na main, a worker IO nie dotyka Bukkit ani żywych obiektów Train/Inventory.

Wczytanie `pending` po restarcie zawsze blokuje cały moduł — nie ma automatycznego replay, cofania ani wydawania zastępczego towaru.
Finalny ledger jest porównywany z kwitami kontenerów: starszy/cofnięty chunk, zniszczenie magazynu lub brak kwitu blokują transfery.
To nie jest transakcja obejmująca zapis świata Minecraft i JSON w jednym filesystem commit.
Jeżeli crash wystąpi między tymi zapisami, strategią jest kwit + odmowa operacji przy niezgodności.
Nie odtwarzać samego `.bak` i nie kasować `pending`/kwitów, żeby „odblokować” dostawy — może to powielić ładunek.

Źródło i cel nie muszą być równocześnie w załadowanych chunkach. Przy załadunku potrzebne jest tylko źródło;
w tym samym uruchomieniu jego zweryfikowany kwit pozostaje zapamiętany po wyładowaniu chunka, więc można wyładować przy celu.
Usługa nigdy nie doczytuje chunków. Po restarcie cache weryfikacji jest pusty: dla ładunku na pokładzie
trzeba choć raz wczytać źródłowy magazyn i potwierdzić kwit, zanim wydanie do celu będzie dozwolone.
Projekcja masy/cargo może nastąpić wcześniej, ale nie materializuje przedmiotów.
Jeśli dowód przejazdu nie zdążył się zapisać przed restartem, freight nie zgaduje historii:
przewóz wymaga ponownego zaobserwowanego przejazdu z ładunkiem. Dowód zapisuje się przy osiągnięciu progu drogi.

Uszkodzony/nieobsługiwany ledger, nieudany zapis i wyjątek podczas zmiany kontenera również blokują moduł.
Brak `freight.json` przy obecnym `.tmp` lub `.bak` nie jest interpretowany jako nowa pusta firma.
`status` oraz log podają powód. Nie ma komendy „wymuś wydanie” ani automatycznego odblokowania.
Odzyskiwanie wymaga audytu przez administratora: wspólny stan świata, kolej.json, freight.json i jego dziennik;
naprawa tylko na podstawie policzonego rzeczywistego ładunku, przy nieaktywnym module.
Nie zatrzymywać serwera bez odrębnej zgody na konkretną przerwę.

`close()` blokuje dalsze zastosowanie planów, wyłącza executor i pozwala już zleconym zapisom dokończyć się asynchronicznie.
Nie czeka na IO na wątku głównym. Gwałtowne zamknięcie JVM może pozostawić PREPARED — następny start odmówi replay.

## Testy i ograniczenia walidacji

```powershell
cd kolej
.\gradlew.bat test --tests 'pl.medivalrpg.kolej.freight.*' --offline --no-daemon
```

Testy używają prawdziwego koordynatora, sterowanych potwierdzeń zapisu i modelu kontenerów.
Pokrywają kolejność zapisu przed inwentarzem, oba okna crasha, błędy IO przed/po zmianie,
cofnięty kwit, stary cargo, obcy cargo, zmiany gracza/lejka w czasie IO, usunięcie wagonów,
brak surowca/miejsca, regularność, dwie fazy chunków, przejazd i jego brak.
Losowe podziały stosów sprawdzają zachowanie liczby przedmiotów w 500 scenariuszach.
Testy plików sprawdzają atomowy zapis, backup, izolację snapshotów oraz odrzucanie uszkodzonych i osieroconych danych.
Adapter Bukkit kompiluje się z lokalnym Paper API; to nie zastępuje testu pojedynczej skrzyni/beczki,
hopperów i PDC w działającym Paper podczas uzgodnionej przez użytkownika integracji.
Weryfikacja 2026-10-27: pełne `gradlew test --offline --no-daemon` zakończone sukcesem:
63 testy, 0 błędów/porażek; w tym 36 testów freight oraz 500 losowych scenariuszy zachowania liczby przedmiotów.
Żadnego serwera nie uruchamiano i niczego nie wdrażano.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
