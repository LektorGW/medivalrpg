<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# Raport walidacji Turniejów — 2026-10-27

Końcowa komenda: `.\gradlew.bat build --no-daemon --console=plain` w `turnieje/`.
Wynik: **BUILD SUCCESSFUL**, 38 s. **118 testów, 0 błędów, 0 porażek, 0 pominiętych**.
Pełny log: `build/final-build.log`; HTML: `build/reports/tests/test/index.html`.

| Zestaw | Testy | Porażki |
|---|---:|---:|
| ArenaModelTest | 6 | 0 |
| BracketTest | 36 | 0 |
| FreeForAllTest | 7 | 0 |
| PrizeSplitTest | 10 | 0 |
| TimeoutJudgeTest | 4 | 0 |
| EscrowLedgerTest | 6 | 0 |
| EscrowSettlementTest | 6 | 0 |
| StorageRecoveryTest | 7 | 0 |
| PhaseTest | 17 | 0 |
| PlayerCareRecoveryTest | 5 | 0 |
| TextsTest | 5 | 0 |
| WeeklyScheduleTest | 9 | 0 |

## Zastany stan i naprawy

Pierwszy build: 100 testów, 10 niepowodzeń. Naprawiono `List.of(...).contains(null)` powodujące NPE w melee oraz propagację wycofań po walkowerach w drabince. Test losowania remisów używa teraz jednego deterministycznego generatora zamiast skorelowanych pierwszych wyników kolejnych ziaren.

Zastany zapis snapshotu był asynchroniczny mimo deklaracji zapisu przed zabraniem rzeczy. Teraz krytyczne zapisy kończą się przed kolejną operacją, propagują błędy i nie odtwarzają automatycznie starych `.bak`. Dodano dziennik operacji finansowych, blokadę przy niejednoznacznym wyniku, trwałe zobowiązania nagród, ponawianie częściowych rozliczeń, odmowę pomijania niedofinansowanego zwrotu oraz kontrolę przepełnienia kwot.

Odzyskanie ekwipunku i wydanie trofeum używają potwierdzeń zapisanych razem z danymi gracza. Testy z atrapami API gracza sprawdzają pozostawienie snapshotu po śmierci, usunięcie pożyczonych rzeczy przy logout, brak ponownego odtworzenia po potwierdzeniu i zachowanie snapshotu przy błędzie `saveData`. Niepełny snapshot jest odrzucany. Blokady obejmują kontenery, kliknięcia/drag, crafting, drop, podnoszenie, stawianie oraz interakcje podczas oczekiwania na odzyskanie; rzut trójzębem jest zablokowany, strzały nie nadają się do podnoszenia. Teleporty kończą się synchronicznie; początek odliczania następuje przed montowaniem koni.

## Pakiet i zakres

Zweryfikowano strukturę jara: wyłącznie własne klasy `pl/medivalrpg/turnieje`, bez klas konsumowanych API i WorldEdit; wersja w `plugin.yml` poprawnie rozwinięta do 1.0.0. Sprawdzono obecność końcowej walidacji snapshotu w skompilowanej klasie. YAML konfiguracji, manifestu i `MENU-ADDITIONS.yml` przechodzi parsowanie. Dodano README, propozycje sześciu akcji menu i ten raport.

Artefakt: `build/libs/turnieje-1.0.0.jar`.
SHA-256: `b6234d53962a85b8542862a894c138c1b00114fbe79727be757a2132c8c5c7b3`.

Edycje i wyniki buildów wyłącznie w `turnieje/`. Nie zmieniono wspólnych API, innych modułów, pluginów serwera ani plików wdrożeniowych. Nie zatrzymywano ani nie restartowano serwera. Nie wdrażano jara. Katalog projektu nie udostępnia repozytorium Git, więc nie sporządzano diffu ani commita.

## Ograniczenia

Testy są jednostkowe i plikowe; cykl gracza wykorzystuje atrapy interfejsów Paper. Nie wykonano testów z klientami ani rzeczywistą instancją Paper/Skarbiec/GrimAC/WorldGuard, testu zabicia procesu ani awarii zasilania. Wymagane scenariusze integracyjne i procedura ręcznego uzgodnienia dziennika są opisane w README. Nie ma wspólnej transakcji pomiędzy JSON, danymi gracza i Skarbcem. Nieznany wynik operacji finansowej wymaga ręcznej kontroli zamiast automatycznego ponowienia; niezależny rollback lub uszkodzenie dysku wykracza poza gwarancje. Synchroniczne oczekiwanie na zapis i teleport może powodować krótkie opóźnienia ticka. Efekty mikstur nie są przywracane. Propozycje menu wymagają dopasowania do schematu Menu i nie zostały w nim zastosowane.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)


## Wydanie 2026-10-28 — bootstrap areny krolewska

140 testów PASS (22 bootstrapu), 0 błędów i 0 pominiętych. Build izolowany przez wave17-release.init.gradle do build/wave17-release, osobny project cache. Sprawdzono zakres 234 pozycji, snapshot 3933, 231 zmian rzeczywistych, własność 4 chunków, TileState/player props, trwały prefiks po awarii, status/fingerprint i zgodność rejestru z weekly.arena=krolewska. Nie uruchamiano scenariusza bootstrapu na Paper; walidacja NBT i pełny raport SHA256 w deploy-wave17/arena/release-report.json. Bez zmian konfiguracji live i test fixtures.
