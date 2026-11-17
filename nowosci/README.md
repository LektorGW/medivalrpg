# Nowosci

Changelog dla graczy serwera MEDIVALRPG (Paper 26.3, Java 25). Po udanym `/login` albo `/register` w nLoginie
(ok. 3 s później, gdy zniknie title „Zalogowano” nLogina) gracz widzi nieprzeczytane nowości, a `/nowosci`
otwiera książkę z całą historią.

## Jak się pokazują (`show.mode`, domyślnie `[title, book, chat]`, można łączyć)

1. **title** — plansze na środku ekranu: „✦ Nowości ✦ / na serwerze MEDIVALRPG”, potem po jednej na każdy
   nieprzeczytany wpis (maks. `show.max-entries`): ikona i tytuł w kolorze kategorii, pod spodem pierwszy punkt
   skrócony do ~40 znaków. Fade 10 / stay 50 / fade 10 ticków, plansze jedna po drugiej, cichy dźwięk na każdej
   (wstęp: `ui.toast.challenge_complete`, wpisy: `block.note_block.chime` z rosnącą wysokością). Sekwencja
   przerywa się, gdy gracz wyjdzie.
2. **book** — po planszach (albo od razu, bez `title`) sama otwiera się książka: strona tytułowa w ramce
   z symboli (data, ile wpisów nowych), spis treści z klikalnymi skokami, nowe wpisy (★) na pierwszych stronach.
   `show.book-only-if-unread: true`, `show.book-max-auto-opens: 1` (ile razy na najnowszy wpis; potem już tylko
   title i czat). Książka otwarta samodzielnie **nie** liczy się jako przeczytanie (`show.auto-book-marks-read:
   false`) — dopiero `/nowosci` albo klik „✔ Przeczytane” na okładce.
3. **chat** — zwięzły blok „Nowości na serwerze” z przyciskami `[Pokaż wszystko]` i `[Nie pokazuj tych więcej]`
   (trwały ślad w historii czatu).

## Żeby każdy zobaczył

- **Pasek u góry ekranu** (`reminder.bossbar`) z liczbą nieprzeczytanych wpisów — wisi, dopóki gracz nie przeczyta.
- **Przypomnienie na czacie** z przyciskami co `reminder.chat-every-minutes` (10).
- `show.auto-mark-read-after-shows: 0` — wpisy nie znikają same po kilku logowaniach.
- **Automatyczne wczytywanie**: co `auto-reload.check-seconds` (15) plugin sprawdza, czy `changelog.yml` został
  podmieniony (SFTP, panel). Nowe wpisy — także po `/nowosci reload` — od razu dostają gracze w grze
  (czat, plansze, pasek), bez `/nowosci oglos`.
- **Discord** (`discord.webhook-url`): każdy nowy wpis z `changelog.yml` trafia raz na kanał jako ładna karta
  (kolor kategorii, komendy jako kod). Wpisy wgrane przy wyłączonym serwerze wysyłają się przy starcie; wpisy
  pisane komendami `/nowosci dodaj|punkt` czekają na `/nowosci oglos`. Stan w `discord.json`; nieudana wysyłka
  ponawia się co 5 min.

## Komendy

| Komenda | Opis |
|---|---|
| `/nowosci` (`/zmiany`, `/changelog`) | książka: spis treści z klikalnymi skokami, potem wpisy od najnowszego |
| `/nowosci czat [strona]` | to samo na czacie, strona po stronie |
| `/nowosci przeczytane` | „Nie pokazuj tych więcej” (przycisk w bloku po zalogowaniu) |
| `/nowosci pomoc` | interaktywna pomoc |
| `/nowosci dodaj <kategoria> <tytuł>` | nowy wpis z dzisiejszą datą (`nowosci.admin`) |
| `/nowosci punkt <tekst>` | dopisuje punkt (MiniMessage) do najnowszego wpisu |
| `/nowosci usun <id>` | usuwa wpis |
| `/nowosci oglos` (`ogłoś`) | wysyła najnowszy wpis wszystkim zalogowanym graczom (czat + plansze) |
| `/nowosci reload` | przeładowuje `config.yml` i `changelog.yml` |

Uprawnienia: `nowosci.use` (wszyscy), `nowosci.admin` (op).

## Pliki

- `changelog.yml` — wpisy (`id`, `date`, `title`, `category`: `nowe` / `zmiana` / `poprawka`, `points`).
  W punktach działają tagi `<cmd>/komenda</cmd>` i `<hl>wyróżnienie</hl>` (inne kolory na czacie i w książce).
  Po ręcznej edycji: `/nowosci reload`. Plik z błędem YAML nie jest nadpisywany (edycja komendami jest wtedy zablokowana).
- `config.yml` — tryby (`show.mode`), opóźnienia, liczba wpisów, nowi gracze (`new-player`: `welcome` / `newest` /
  `all` / `none`), plansze (`title-sequence`: czasy, skracanie, dźwięki), wymiary strony książki, kategorie
  (ikona, kolor), wszystkie teksty.
- `seen.json` — ostatnio przeczytany wpis każdego gracza (UUID) i licznik samoczynnych otwarć książki, zapisywany w tle.

## nLogin

Plugin nasłuchuje `com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent` (wywoływany po każdym udanym
uwierzytelnieniu: logowanie, rejestracja, sesja). Bez nLogina nowości pokazują się 3 s po wejściu na serwer.

## Budowanie

```
./gradlew build
```

Jar: `build/libs/Nowosci-1.0.0.jar`. Kompilacja wymaga `../downloads/nlogin/nLogin.jar` (tylko compileOnly).

---

© 2026 Maciej Kaczmarczyk — [lektoro.pl](https://lektoro.pl)
