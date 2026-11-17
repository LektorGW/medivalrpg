# Glos – plugin Paper (proximity voice chat)

Serwerowa część systemu „Głos”. Plugin łączy się z Cloudflare Workerem (Durable Object `VoiceRoom`)
przez WebSocket i co 4 ticki wysyła każdemu podłączonemu graczowi względne pozycje osób w zasięgu.
Dźwięk idzie P2P (WebRTC) między przeglądarkami. Protokół opisuje `../PROTOCOL.md`.

Wymagania: Paper 26.3 i Java 25. Jar nie ma zewnętrznych zależności.

## Instalacja
1. Zbuduj jar: `gradlew build`. Wynik to `build/libs/Glos-1.0.0.jar`.
2. Wgraj jar do `plugins/` i uruchom serwer. Plugin utworzy `plugins/Glos/config.yml`
   i wygeneruje losowy `plugin-secret`.
3. W `config.yml` ustaw `worker-url`, np. `https://glos-mc.xxx.workers.dev`.
4. Ten sam sekret ustaw w Workerze: `wrangler secret put PLUGIN_SECRET`.
5. Wpisz `/glos reload` albo zrestartuj serwer. W konsoli powinien pojawić się komunikat `Połączono z Workerem`.

## Konfiguracja (najważniejsze)
| klucz | domyślnie | opis |
|---|---|---|
| `worker-url` | `""` | adres Workera; puste wyłącza głos |
| `plugin-secret` | generowany | `Authorization: Bearer …` przy `wss://…/plugin` |
| `ranges.whisper/normal/shout` | 6 / 24 / 48 | zasięg w blokach |
| `sneak-whisper` | `true` | kucanie daje szept (tylko z trybu normalnego) |
| `actionbar` | `true` | pasek z trybem głosu co ~1 s |
| `offline-kick-seconds` | 60 | rozłącza przeglądarkę gracza, którego nie ma na serwerze (0 = nigdy) |
| `indicator.attach` | `passenger` | `passenger` albo `follow` (patrz niżej) |
| `indicator.text.*` | ♪ / ♫ | tekst wskaźnika „kto mówi” (MiniMessage) |
| `kick-reasons.*` | | powody rozłączenia widoczne w przeglądarce |
| `messages.*` | | wszystkie komunikaty (MiniMessage, `<prefix>`) |
| `debug` | `false` | loguje ruch z Workerem |

Wyciszenia trzyma `plugins/Glos/mutes.yml` (`uuid: czas_wygaśnięcia_ms` albo `-1`).
Preferencje graczy (`/glos pasek`, `/glos wskaznik`) trzyma `plugins/Glos/prefs.json`.
Plik zawiera tylko wyłączone opcje, a domyślnie wszystko jest włączone. Gdy `actionbar: false`, nikt nie widzi paska.

Gdy w `config.yml` serwera brakuje komunikatu (np. po aktualizacji pluginu), plugin bierze domyślny tekst z jara.
Nowe klucze można też skopiować z `config.yml` w jarze, żeby je zmienić.

**Wskaźnik w trybie `passenger`.** Paper nie pozwala teleportować gracza z pasażerem do innego świata.
Gdy gracz mówi, teleport innego pluginu (np. `/spawn` do innego świata) się nie uda.
Tryb `follow` nie ma tego problemu, ale wskaźnik jest przesuwany teleportem co tick.

## Komendy
| komenda | uprawnienie | opis |
|---|---|---|
| `/glos` (`/vc`, `/voice`, `/audio`) | `glos.use` | link do czatu głosowego (jednorazowy, ważny 15 min) |
| `/glos szept` · `normalnie` · `krzyk` | `glos.use` | tryb głosu |
| `/glos rozlacz` | `glos.use` | rozłącza przeglądarkę i unieważnia linki |
| `/glos pasek [wlacz\|wylacz]` | `glos.use` | pokazuje lub ukrywa pasek „Głos: …” nad hotbarem; bez argumentu przełącza |
| `/glos wskaznik [wlacz\|wylacz]` | `glos.use` | pokazuje lub ukrywa ikonki mówienia nad innymi graczami; bez argumentu przełącza |
| `/glos pomoc` | `glos.use` | lista komend |
| `/glos mute <gracz> [czas]` | `glos.admin` | wycisza gracza; czas np. `10m`, `2h`, `1d`, `1h30m`; bez czasu wycisza na stałe |
| `/glos unmute <gracz>` | `glos.admin` | zdejmuje wyciszenie |
| `/glos lista` | `glos.admin` | kto jest podłączony, w jakim trybie, czy mówi |
| `/glos reload` | `glos.admin` | przeładowuje config i `mutes.yml` |

## Uprawnienia
- `glos.use` (domyślnie: wszyscy) to korzystanie z czatu głosowego.
- `glos.admin` (domyślnie: op) to wyciszanie, lista i reload. Zawiera `glos.use`.

---
© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
