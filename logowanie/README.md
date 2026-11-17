<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Strażnik — logowanie graczy MEDIVALRPG

Własny plugin logowania (następca nLogin) dla serwera w trybie offline (`online-mode=false`). Zachowuje hasła graczy
z nLogin bez ich resetowania. Plugin `Straznik`, katalog `logowanie/`, Paper 26.3, Java 25, bez zależności
zewnętrznych (Argon2 w czystej Javie, SQLite i log4j z Papera).

## Format hashy nLogin (ustalony, nie zgadywany)

- Źródła: konfiguracja produkcji (`security.hashing.algorithm: ARGON2ID`, `argon2: iterations 5, memory 32, parallelism 1`),
  kształt rekordów `nlogin.db` (sprawdzony bez wypisywania wartości) oraz kod bajtowy nLogin 2.0.25 (`javap`):
  klasa hashera wywołuje `Argon2Factory.create(ARGON2id)`, `Argon2.hash(iterations, memory*1024, parallelism, password.toCharArray())`
  i `Argon2.verify(hash, password.toCharArray())` z biblioteki argon2-jvm (przeniesionej do `com.nickuc.login.lib.argon2`).
  Stałe `memory*1024` w kodzie są zaciemnione, a po obliczeniu dają 1024.
- Wynik: ciąg PHC biblioteki libargon2 `$argon2id$v=19$m=32768,t=5,p=1$<sól>$<hash>`. Sól ma 16 B, a znacznik 32 B;
  oba są w Base64 bez dopełnienia. Hasło jest kodowane jako UTF-8 (domyślny zestaw znaków argon2-jvm), bez klucza
  tajnego (pepper) i bez danych dodatkowych.
- Weryfikacja w Strażniku: `crypto/Argon2.java` (RFC 9106, port implementacji referencyjnej) + `crypto/PhcArgon2.java`.
  Testy: wektory RFC 9106 (d/i/id), 120 losowych przypadków bajt w bajt z Bouncy Castle (wersje 0x10/0x13, 1–4 tory,
  znaczniki do 153 B), hash w kształcie nLogin. Na izolowanym Paperze prawdziwy nLogin 2.0.25 przyjął hasło do hasha
  wygenerowanego naszą implementacją, a Strażnik zalogował konta założone przez prawdziwy nLogin (`verify-auth-20261029/`).

## Hasła po migracji

- Import oznacza konta `schemat = nlogin`. Przy pierwszym udanym `/login` hasło jest haszowane od nowa:
  Argon2id v19, `m=65536 KiB, t=3, p=1`, nowa losowa sól 16 B (`haslo.argon2.*`). Zmiana parametrów w configu
  powoduje ponowne haszowanie przy kolejnym logowaniu.
- Hashe są zawsze w formacie PHC Argon2id, więc w razie powrotu nLogin zweryfikuje je bez zmian (patrz Wycofanie).

## Komendy

| Komenda | Opis |
|---|---|
| `/register <hasło> <hasło>` (`/reg`, `/zarejestruj`) | Założenie konta. Min. 6 znaków, bez spacji, bez nicku i spoza listy `slabe-hasla.txt`. |
| `/login <hasło>` (`/l`, `/log`, `/zaloguj`) | Logowanie. |
| `/zmienhaslo <stare> <nowe>` (`/changepassword`, `/changepass`) | Zmiana hasła. Kończy sesje IP i sesje panelu WWW. |
| `/wyloguj` (`/logout`) | Wyjście z serwera i zakończenie sesji. |
| `/straznik` (`/auth`, `straznik.admin`) | Komendy: `stan`, `info <gracz>` (bez hasła), `haslo <gracz> <nowe>`, `wyrejestruj <gracz>`, `odblokuj <gracz\|ip>`, `import nlogin [potwierdz]`, `eksport nlogin`, `przeladuj`, `pomoc`. |

Hasło, import i eksport są domyślnie dostępne tylko z konsoli (`admin.hasla-tylko-z-konsoli`).

## Przed zalogowaniem

- Przy wejściu (`AsyncPlayerSpawnLocationEvent`) gracz trafia do limbo (spawn świata albo `limbo.lokalizacja`),
  także przy ważnej sesji IP. Limbo zawsze ma tymczasowe `allowFlight=true` i `flying=true`.
  Klient nie dostaje prawdziwej pozycji.
- Prawdziwa pozycja, ekwipunek i latanie są zapisywane do `plugins/Straznik/limbo/<uuid>.json` (zapis atomowy z fsync),
  zanim ekwipunek zostanie ukryty. Po zalogowaniu gracz wraca na miejsce wylogowania z całym ekwipunkiem.
  Chunk docelowy i sąsiednie są wcześniej ładowane asynchronicznie. Jeśli miejsce ma bezpieczny grunt i wolną
  przestrzeń na postać, współrzędne (w tym ułamkowe Y, obrót i pochylenie) zostają dokładnie zachowane.
  W przeciwnym razie Strażnik szuka rzeczywistej powierzchni kolizji w tej samej kolumnie do 64 bloków w dół;
  brak gruntu oznacza sprawdzony spawn serwera (promień 8 bloków). Ciecz, zagrożenia i kolizja z ciałem wykluczają
  miejsce. O przeniesieniu gracz dostaje komunikat. Brak bezpiecznego spawnu odmawia wejścia, zachowując kopię.
  Dopiero po udanym teleporcie wracają poprzednie flagi latania (z uwzględnieniem creative/spectator), kończy się
  blokada ruchu i wysyłane jest `PlayerAuthenticatedEvent`. Nie stosujemy wyjątków ani wyłączeń GrimAC.
  Przedmioty dodane w międzyczasie przez inne pluginy są dokładane.
- Jeśli gracz wyjdzie bez logowania, minie limit czasu albo plugin zostanie wyłączony, stan wraca od razu.
  Po awarii serwera wraca przy następnym logowaniu z pliku.
- Zablokowane są: ruch (obrót głowy jest dozwolony), czat, komendy poza login/register, interakcje, obrażenia
  w obie strony, celowanie mobów, podnoszenie i wyrzucanie przedmiotów, ekwipunek, głód, łóżka, portale i obce
  teleporty. Podpowiedzi komend są ograniczone do logowania.
- Niezalogowani są niewidoczni dla innych, nie widzą innych graczy ani czatu.
- Gracz dostaje tytuł, pasek bossa z odliczaniem i przypomnienie co 10 s. Po 90 s następuje kick.
- **Okno logowania** (Paper Dialog API, `wyglad.okno: true`) otwiera się zaraz po wejściu:
  - logowanie: pole „Hasło”; rejestracja: pola „Hasło” i „Powtórz hasło”;
  - przyciski: „Zaloguj” / „Załóż konto” i „Wyjdź” (rozłącza);
  - okna nie da się zamknąć klawiszem Esc;
  - błędy pojawiają się w nowym oknie z pozostałym czasem i licznikiem prób: złe hasło, puste, różne, za krótkie,
    słabe, limit kont; blokada kończy się kickiem;
  - komendy czatu działają dalej jako zapas.
- Pole w oknie **nie jest maskowane**: tekstowe pole dialogu Minecrafta 26.3 nie ma takiej opcji (sprawdzone
  w paper-api: `TextDialogInput` ma tylko szerokość, etykietę, wartość początkową, maks. długość i wiele linii).
  Hasło widzi wyłącznie sam gracz na swoim ekranie. Do serwera trafia w pakiecie custom-click, nie przez komendę,
  więc nie pojawia się w żadnym logu.

## Bezpieczeństwo

- **Limity prób.** Po 3 złych hasłach w jednym połączeniu następuje kick. Po 5 nieudanych próbach na nick albo
  na IP w ciągu 15 min logowanie jest blokowane na 15 min. Zdejmuje to `/straznik odblokuj`.
- **Sesja IP.** Ten sam UUID z tego samego IP w ciągu 10 min od wyjścia nie wpisuje hasła. Korzysta z tego samego
  bezpiecznego powrotu co komenda i okno logowania. Sesja jest jednorazowa,
  restart ją kończy, a `sesja.wlaczona: false` wyłącza sesje całkiem.
- **Limit kont.** Domyślnie 3 konta na IP; liczy się IP rejestracji i ostatnie IP.
- **Nick.** Obowiązuje wzorzec `[a-zA-Z0-9_]{3,16}`.
  - Konto „Gracz” nie wpuszcza nicku „gracz” (w trybie offline to inny UUID).
  - Drugie połączenie na nick zalogowany z innego IP jest odrzucane.
- **Log konsoli.** Linie „issued server command: /login …” (oraz register, zmienhaslo i straznik ze wszystkimi aliasami,
  także `/execute … run login …`) i błędy Papera „Command exception: /login …” nie trafiają do konsoli ani do
  `logs/latest.log` (filtr log4j), więc nie widzi ich też Straż.
- **Log zdarzeń.** `plugins/Straznik/zdarzenia.log` zawiera czas, zdarzenie, nick i IP. Nigdy nie zawiera haseł
  ani hashy.
- **Fail-closed.** Gdy baza `konta.db` nie działa, serwer nikogo nie wpuszcza.
- **Współpraca z nLogin.** Gdy nLogin jest włączony, Strażnik jest bierny: nie ma komend, blokady ani API.

## Import z nLogin

Przy pierwszym starcie (`import.nlogin.przy-starcie: true`) import wykonuje się jednorazowo; znacznik zapisuje się
w `konta.db`. Źródłem jest `plugins/nLogin/nlogin.db`, czytane z kopii tymczasowej, więc plik nie jest zmieniany.

- **Co jest importowane:** nick z wielkością liter, offline UUID nicku, hash, ostatnie IP i daty.
- **Co jest pomijane:** konta bez hasła (premium/bedrock), nieprawidłowe nicki, nieznane formaty hashy i starsze
  duplikaty nicku.
- **Idempotentność:** istniejące konta Strażnika nigdy nie są nadpisywane, a ponowne uruchomienie zalicza je do
  „już było”.
- **Raport** zawiera wyłącznie liczby: `plugins/Straznik/import/nlogin-<data>.json`.
- **Ręcznie z konsoli:** `/straznik import nlogin` (próba) albo `/straznik import nlogin potwierdz`.

## Wycofanie (powrót do nLogin)

`/straznik eksport nlogin` (konsola) tworzy `plugins/Straznik/eksport/nlogin-<data>.db`. Jest to kopia bazy nLogin
z bieżącymi hasłami i z kontami założonymi w Strażniku; oryginał zostaje bez zmian. Kopię podstawia się jako
`plugins/nLogin/nlogin.db`. Sprawdzone na Paperze: nLogin 2.0.25 przyjmuje przehaszowane hasła i nowe konta.
Konta usunięte w Strażniku pozostają w kopii z nLogin.

## API dla innych pluginów

- `pl.medivalrpg.straznik.api.StraznikApi` w ServicesManager. Metody: `isAuthenticated(UUID)`, `isRegistered`,
  `registeredName`, `registeredUuid`, `verifyPassword` (blokująca, poza głównym wątkiem) i `remainingSeconds`.
- Zdarzenie `PlayerAuthenticatedEvent` (główny wątek, raz na połączenie, po oddaniu pozycji i ekwipunku) z `AuthMethod`
  `LOGIN`/`REGISTER`/`SESSION`.
- Zdarzenie `PasswordChangedEvent` (zmiana przez gracza/admina, wyrejestrowanie) służy np. do kończenia sesji panelu.
- Menu, Nowosci i Panel korzystają z API przez refleksję (bez zależności kompilacji). Gdy Strażnik nie jest aktywny,
  wracają do nLogin.

## Budowanie i testy

`./gradlew build` (Java 25). Testy jednostkowe obejmują:

- Argon2 (RFC 9106, Bouncy Castle, kształt nLogin), PHC i przehaszowanie;
- import/eksport na syntetycznej bazie o schemacie produkcji;
- blokady, sesje, zasady haseł, filtr konsoli i parsowanie komend.

Test na Paperze z botem offline: `verify-auth-20261029/`.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
