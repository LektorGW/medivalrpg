<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Straż MEDIVALRPG — raport dzienny, alerty i nocna kopia danych pluginów

Mały plugin nadzoru dla właściciela (WorldSteward). **Tylko odczytuje** dane innych pluginów (API z ServicesManager,
pliki danych) i nie zmienia mechaniki gry. Pisze wyłącznie do `plugins/Straz/` i do katalogu kopii `backups/`.

## Dlaczego plugin, a nie Worker Cloudflare

Większość danych istnieje tylko na serwerze: log `logs/latest.log` (błędy ERROR/SEVERE), TPS i długie ticki,
liczba graczy, żywy status budowy Kolei, API Ludnosci/Armii/Kraje oraz pliki danych do kopii. Worker widzi tylko
to, co serwer sam mu wyśle (panel RPC), więc i tak potrzebny byłby kod na serwerze, a kopii plików Worker nie zrobi.
Plugin działa sam, bez zewnętrznej infrastruktury; Discord dostaje gotowe wiadomości przez zwykły webhook.

## Raport dzienny (domyślnie 09:00 Europe/Warsaw)

Sekcje: 💰 zamknięta pula (suma wszystkich kont `Skarbiec/bank/accounts.json` = 100 000 000 md + rozbicie na główne
konta) · 🏠 `world:households` i `world:merchant` (saldo, wpływy/wydatki 24 h z dziennika transakcji, zasilenia z rezerwy
osobno, prognoza dni ze średniej do 7 dni) · 🛑 błędy ERROR/SEVERE z 24 h według pluginów, deduplikowane podpisem
(liczby/UUID/współrzędne nie tworzą nowych rodzajów) · 🚂 budowa kolei (części DONE/w toku/zatrzymane/czeka, bloki,
budżet, wydane md, status na żywo z `KolejPlugin#networkCommissionStatus`) · 👥 szczyt graczy, TPS średni/minimalny,
lag spikes (tick > 500 ms) · 💾 kopie (najnowsza, rozmiar, wynik weryfikacji) · 🏘️ obywatele, urodzenia, ubytki
z ewidencji, zabici, ukończone budowy i place w toku · ⚔️ żołnierze wobec limitu 1000.

Plik zawsze: `plugins/Straz/raporty/RRRR-MM-DD.md` (raport na żądanie: `RRRR-MM-DD-HHmm.md`), pełna lista błędów na
końcu pliku. Z webhookiem — embed na Discord (limity Discorda pilnowane, `allowed_mentions: []`, `@everyone/@here`
zneutralizowane). Bez webhooka — cały raport w logu serwera. Raport nadrabia się po restarcie tego samego dnia.

## Alerty natychmiastowe (osobne wiadomości)

| Alert | Warunek | Powtórka |
|---|---|---|
| 💰 rozbieżność puli | suma ≠ `alerty.pula-oczekiwana` lub konto ujemne w 2 kolejnych sprawdzeniach (co 300 s) | raz na kwotę; „pula znów zgodna” po powrocie |
| 🛑 nowy SEVERE/ERROR | podpis niewidziany od 7 dni; nowe rodzaje zbierane 20 s w jedną wiadomość | raz na podpis (`alerty.ignoruj-bledy` wycisza) |
| 🚂 budowa kolei zatrzymana | status „Budowa wstrzymana/Zatrzymane” albo zlecenie części BLOCKED/CANCELLED/HELD; opcjonalnie brak postępu 12 h | raz na przyczynę |
| 🐢 TPS | 1-min TPS < 15 przez `tps-minut` (5) minut z rzędu | raz na epizod |
| 💾 kopia | nieudana, za mało miejsca, podejrzanie mała (< 1 MB albo < 50 % poprzedniej) | raz na plik |

Limit 12 alertów/h (reszta w logu i pliku, licznik pominiętych w następnym alercie). Wszystkie alerty także w logu
(WARN) i w `plugins/Straz/raporty/alerty-RRRR-MM-DD.md`.

## Nocna kopia danych pluginów (domyślnie 04:30)

`backups/plugins-RRRRMMDD.zip` obok `plugins/` (ręczna: `plugins-RRRRMMDD-HHmm.zip`), rotacja 14 dni (usuwane są
tylko pliki o tym wzorcu). W tle na wątku o najniższym priorytecie, z limitem odczytu 20 MB/s.
Spójność: wspólne API nie ma metody „flush”, ale wszystkie nasze pluginy zapisują atomowo (tmp + przeniesienie,
poprzednia wersja `.bak`). Straż czyta każdy plik w całości i przyjmuje go tylko, gdy rozmiar i czas modyfikacji nie
zmieniły się w trakcie; plik `.json`, który się nie parsuje, zastępuje poprawną `.bak` (zapisane w manifeście).
Archiwum powstaje jako `.tmp`, jest w całości odczytywane (CRC każdego wpisu) i dopiero wtedy przenoszone.
W środku `STRAZ-MANIFEST.json` (SHA-256 każdego pliku, źródło live/bak, ostrzeżenia). Przed kopią sprawdzane jest
wolne miejsce (3× ostatnia kopia + 100 MB). Pobieranie na komputer: `python tools/pull_backups.py`.

## Komendy (`straz.admin`, domyślnie op)

`/straz status` · `/straz raport` (ostatni raport na czacie) · `/straz raport teraz` · `/straz alerty` ·
`/straz kopia lista` · `/straz kopia teraz` · `/straz test` · `/straz przeladuj` (config bez restartu) · `/straz pomoc`.
Przyciski: `MENU-ADDITIONS.yml` (podmenu „Straż” w menu admina). Komendy admina nie trafiają do `pomoc.yml`.

## Budowa i testy

```powershell
$env:JAVA_HOME="$HOME\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2"; .\gradlew.bat --offline build
```

Testy jednostkowe: formatowanie (liczby, odmiana, raport, embed i limity Discorda), alerty (bramka, TPS),
deduplikacja i log (parser, tail, restart logu), pula, przepływy, kolej, ludność, kopie (zapis, .bak, rotacja,
zdrowie), stan. Próba na izolowanym Paperze: `../verify-straz-20261029/`.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
