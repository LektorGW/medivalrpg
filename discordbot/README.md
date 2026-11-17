<!-- Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl -->
# DiscordBot MEDIVALRPG — kronika wojen

## Stan 2026-10-31 — aktywny `aaf6255b` (server-info, regulamin, angielskie nazwy)

Pakiet `deploy-discordbot-serverinfo-20261030/` (110 testów JUnit), aktywny od restartu 31.10 01:25. Zawiera poprawki
`e6525bde` (układ) i `596396fc` (rejestracja komend). Starsze opisy poniżej są historyczne.

- **Kanały po angielsku** (treści wiadomości po polsku): 📊 Statistics, 📜 Information, 🏰 Server, 💬 Chat, ⚔️ Countries,
  🛡️ Administration, 🗄️ Archive; `👋-welcome`, `✅-verification`, `📄-rules`, `🌐-server-info`, `📡-status`, `📯-war-chronicle`,
  `🎉-events`, `🏆-ranking`, `🎮-game-chat`, `💬-general`, `🛒-trading`, `❓-help`, `🛡️-moderation`, `country-<slug>`.
  Istniejące kanały zmienia `/discord admin uklad zastosuj potwierdz` (kanały krajów — automat provision).
- **`🌐-server-info`** (`v3:channel:serverinfo`, Information, widoczny dla @everyone): jedna wiadomość bota z przyciskami
  „Mapa świata” i „Panel zarządzania”. Config `server-info.enabled|address|map-url|panel-url` (tylko http/https; puste
  pole pomijane). Wiadomość jest edytowana przy starcie, gdy zmieni się treść (`ServerInfo`, odcisk SHA-256 w links.db).
- **Regulamin:** pliki `plugins/DiscordBot/regulamin/*.md` w kolejności nazw; linia `# Tytuł` = tytuł pierwszej strony
  (`RulesBook`, embedy ≤3900 znaków, maks. 20). Publikacja po starcie i po `/discord admin tresci odswiez`; po zmianie
  bot usuwa tylko własne poprzednie wiadomości i publikuje od nowa. Domyślnie (gdy folderu brak) `2-regulamin-rp.md` —
  regulamin RP odzyskany z CDN 31.10. Ogólny regulamin i „System wojenny i dyplomacji” (PL/EN) przepadły z usuniętym kanałem.
- Testy: `LayoutPlannerTest` (server-info, zmiana nazw PL→EN, podział regulaminu, dołączony plik mieści się w limitach).

Plugin Paper / Java 25 z JDA, kontynuacja odtwarzalnej bazy `2921a2e6`.
Kod jest przygotowany lokalnie. Produkcja: aktywny `4e10d027`, oczekujący `2921a2e6`;
nowy jar ma zastąpić oczekujący plik dopiero po decyzji właściciela. Token i config produkcyjny nie były odczytywane.

Funkcje: powiązania, adopcja istniejących ról, bezpieczny układ i osobne usuwanie kanałów z eksportem,
weryfikacja przyciskiem, role krajów, most dla powiązanych, obecność i liczniki. Nowa kronika ma publiczny kanał
czytany przez wszystkich, embedy z kolorem kraju, grupy co 5 minut, trwałe deduplikowanie i limit 20 wiadomości/h,
raport o 21:00 Europe/Warsaw. Zbieranie faktów działa też bez logowania bota.

[Instrukcja aktywacji i rollback](INSTRUKCJA.md) · [Źródła faktów, zakres i ograniczenia kroniki](KRONIKA.md).
Nie wszystkie rodzaje starć mają końcowy event w obecnych dostawcach; brakujące wyniki nie są zgadywane.

Nowy pakiet: `deploy-discordbot-kronika-20261030/`. Config w pakiecie to wyłącznie nowe klucze `chronicle`.
Menu jest poprawionym zasobowo kandydatem `9f690a61` z trzema przyciskami pauzy Kolei i pomocą kroniki;
klasy Menu pozostają bajt w bajt identyczne. Pełny zestaw testów uruchamia się na dokładnym kandydacie jara.

Budowanie przy małym RAM (PowerShell, JAVA_HOME wskazuje lokalny JDK 25):

```powershell
./discordbot/gradlew.bat -p discordbot -I tools/low-memory.init.gradle --no-daemon build
python discordbot/tools/prepare_chronicle_menu.py
./menu/gradlew.bat -p menu -I ../discordbot/tools/menu-kronika.init.gradle -I ../discordbot/tools/low-memory.init.gradle --no-daemon test
python discordbot/tools/verify_help.py
```

Zadania wykonywać kolejno. Testowy Paper tylko po zamknięciu JVM Gradle/testów; `smoke_chronicle.py` sprawdza procesy,
ma -Xmx2G, port 25632 i kończy wyłącznie własny proces przez konsolowe `stop`. Wymaga syntetycznej atrapy z
`tools/probe/ChronicleProbe.java`, której NIE wolno wdrażać. Wyniki JUnit, prób i hashe zawiera manifest pakietu.
Poprzednie `package_layout.py` / `prepare_community_menu.py` są narzędziami starszej rewizji — nie używać do kroniki.

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
