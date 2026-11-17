# Głos — proximity voice chat (protokół)

Język interfejsu dla graczy: **polski**. Kod/identyfikatory: angielskie.

## Architektura

```
Przeglądarka gracza  <── WebRTC audio P2P (mesh, tylko gracze w zasięgu) ──>  Przeglądarka
        │  wss://<worker>/ws?t=TOKEN                                               │
        └──────────────►  Cloudflare Worker + Durable Object "VoiceRoom"  ◄────────┘
                                   ▲  wss://<worker>/plugin  (Authorization: Bearer PLUGIN_SECRET)
                                   │
                          Plugin Paper 26.3 (Java 25, java.net.http.WebSocket, Gson z Papera)
```

- Plugin jest **autorytatywny**: liczy pozycje WZGLĘDNE, zasięgi, wyciszenia, stłumienie pod wodą.
  Przeglądarka nigdy nie dostaje absolutnych współrzędnych.
- Durable Object (jedna instancja `idFromName("main")`) to głupi router: uwierzytelnia, trzyma tokeny,
  przekazuje wiadomości. Używa Hibernation API (`ctx.acceptWebSocket(ws, tags)`), tagi:
  `"plugin"` dla pluginu, `"c:<uuid>"` dla przeglądarki gracza. Identyfikacja przez `ws.serializeAttachment`.
- Dźwięk liczy przeglądarka z Web Audio: `MediaStreamSource -> BiquadFilter(lowpass) -> Gain -> PannerNode(HRTF)`.
  Współrzędne Minecrafta (x wschód, y góra, z południe) są prawoskrętne jak Web Audio → podajemy je wprost.
  Słuchacz w (0,0,0), orientacja z yaw/pitch: forward = (-sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch)), up = (0,1,0).

Wszystkie wiadomości to JSON (tekst) z polem `t`. UUID w formie z myślnikami, małe litery.

## Plugin → DO

| t | pola | znaczenie |
|---|------|-----------|
| `hello` | `v:1` | po połączeniu; DO odpowiada `clients` |
| `token` | `token, id, n` | rejestruje jednorazowy link: token (32 znaki [A-Za-z0-9]) → gracz (uuid, nick). Ważny 15 min do pierwszego użycia; po użyciu działa dalej do `kick`/`forget` (umożliwia odświeżenie strony). Nowy token dla tego samego id unieważnia stare. |
| `state` | `to: { "<uuid>": Payload, ... }` | wysyłane co 4 ticki (5 Hz), tylko gdy są podłączeni klienci. DO wysyła każdemu klientowi `{t:"state", ...Payload}`. |
| `speaking` | `id, on` | (nieużywane przez plugin — patrz DO→plugin) |
| `kick` | `id, reason` | DO zamyka socket klienta (`{t:"kicked", reason}` potem close 4000) i unieważnia jego tokeny. Wysyłane przy wyjściu gracza z serwera i `/glos rozlacz`. |

### Payload (dla jednego słuchacza)
```json
{
  "me":   { "yaw": 12.5, "pitch": -3.0, "mode": "normal", "range": 24, "muted": false, "uw": false },
  "peers": [
    { "id": "uuid", "n": "Nick", "x": 3.1, "y": 0.0, "z": -7.4, "r": 24, "m": false, "mu": false }
  ]
}
```
- `me.mode` ∈ `whisper | normal | shout`; `me.range` = zasięg głosu TEGO gracza w blokach; `me.muted` = wyciszony przez admina (przeglądarka ma wtedy wyłączyć wysyłanie mikrofonu i pokazać komunikat); `me.uw` = słuchacz pod wodą.
- `peers` = inni PODŁĄCZENI do głosu gracze w tym samym świecie w odległości ≤ maks. zasięg (krzyk) + 8.
  `x,y,z` = pozycja oczu peera minus pozycja oczu słuchacza (zaokrąglone do 0.01).
  `r` = zasięg głosu peera (zależny od jego trybu), `m` = stłumienie (peer lub słuchacz pod wodą), `mu` = peer wyciszony przez admina.
- Yaw/pitch w stopniach jak w Bukkit (yaw 0 = +Z/południe, rośnie zgodnie z ruchem wskazówek; pitch dodatni = w dół).

## DO → Plugin

| t | pola | znaczenie |
|---|------|-----------|
| `clients` | `ids: [uuid...]` | aktualna lista podłączonych przeglądarek; po każdej zmianie i po `hello` |
| `speaking` | `id, on` | gracz zaczął/przestał mówić (VAD w przeglądarce) |
| `mode` | `id, mode` | gracz zmienił tryb w przeglądarce (plugin waliduje i ustawia) |

## Przeglądarka → DO

| t | pola | znaczenie |
|---|------|-----------|
| `signal` | `to, data` | sygnalizacja WebRTC (`data` = `{sdp}` albo `{candidate}`); DO przekazuje tylko jeśli `to` jest podłączony |
| `speaking` | `on` | zmiana stanu VAD; DO przekazuje pluginowi i wszystkim przeglądarkom (`{t:"speaking", id, on}`) |
| `mode` | `mode` | DO przekazuje pluginowi jako `{t:"mode", id, mode}` |

## DO → Przeglądarka

| t | pola | znaczenie |
|---|------|-----------|
| `welcome` | `id, n, ice: RTCIceServer[]` | po udanym połączeniu |
| `state` | `me, peers` | patrz Payload |
| `signal` | `from, data` | sygnalizacja od peera |
| `speaking` | `id, on` | kto mówi (UI) |
| `kicked` | `reason` | rozłączenie (np. "Zalogowano z innego miejsca", "Wyszedłeś z serwera") |

Zła/nieważna/wygasła wartość tokenu → DO akceptuje WS, wysyła `{t:"kicked", reason:"Link jest nieważny. Wpisz /glos w grze, aby dostać nowy."}` i zamyka.
Nowe połączenie z tym samym uuid zamyka stare (`kicked`, "Połączono w innej karcie").
Gdy plugin jest niepodłączony, przeglądarki zostają, ale nie dostają `state`.

## WebRTC
- Inicjator oferty: peer z leksykograficznie mniejszym uuid. Łączymy się z każdym z `peers`; rozłączamy, gdy peer zniknie z `peers` na > 5 s.
- ICE: Worker zwraca w `welcome` serwery: zawsze `stun:stun.cloudflare.com:3478`; dodatkowo TURN Cloudflare Realtime, jeśli ustawione sekrety `TURN_KEY_ID` i `TURN_KEY_API_TOKEN`
  (POST `https://rtc.live.cloudflare.com/v1/turn/keys/{TURN_KEY_ID}/credentials/generate-ice-servers`, body `{"ttl":86400}`, nagłówek `Authorization: Bearer {TURN_KEY_API_TOKEN}`; cache w pamięci DO ~12 h).

## Endpointy Workera
- `GET /` i statyczne pliki: strona klienta (Workers static assets, katalog `worker/public`). Link dla gracza: `https://<worker>/?t=TOKEN`.
- `GET /ws?t=TOKEN` (Upgrade: websocket) → DO.
- `GET /plugin` (Upgrade: websocket, `Authorization: Bearer <PLUGIN_SECRET>`) → DO; zły sekret → 401.
- Sekret `PLUGIN_SECRET` jako Worker secret.
