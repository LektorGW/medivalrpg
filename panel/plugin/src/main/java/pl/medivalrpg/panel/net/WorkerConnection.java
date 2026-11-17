// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Based on the Glos plugin WorkerConnection (same protocol style: hello, reconnect with backoff, ping/pong watchdog).
package pl.medivalrpg.panel.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Połączenie WebSocket z Durable Objectem ({@code wss://<worker>/plugin}).
 *
 * <p>Wątki:
 * <ul>
 *   <li>jeden wątek {@code io} ("Panel-Worker") robi łączenie, ponowne łączenie, serializację JSON
 *       i wysyłanie – dzięki temu {@code sendText} nigdy nie jest wołane równolegle;</li>
 *   <li>callbacki {@link WebSocket.Listener} (wątki HttpClienta) tylko składają fragmenty, parsują JSON
 *       i przekazują wszystko do {@link Handler} przez {@code callbacks} (wątek obsługi pluginu Panel, nie główny wątek serwera).</li>
 * </ul>
 * Klasa nie dotyka Bukkit API.
 */
public final class WorkerConnection {

    /** Wywoływane na wykonawcy {@code callbacks} (jeden wątek, w kolejności nadejścia). */
    public interface Handler {
        void onConnected();

        void onDisconnected();

        void onMessage(String type, JsonObject msg);
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long SEND_TIMEOUT_SEC = 10;
    private static final long PING_INTERVAL_SEC = 30;
    private static final long PONG_TIMEOUT_MS = 95_000;
    private static final int MAX_BACKOFF_SEC = 30;
    /** The Worker limits request bodies to 16 KB; anything far bigger is dropped. */
    private static final int MAX_MESSAGE_CHARS = 512 * 1024;

    private final Logger log;
    private final Executor callbacks;
    private final Handler handler;
    private final Gson gson = pl.medivalrpg.panel.util.Json.GSON;
    private final HttpClient http;
    private final ScheduledExecutorService io;

    private volatile URI uri;
    private volatile String secret;
    private volatile boolean running;
    private volatile boolean debug;
    private volatile Session current;

    // Tylko wątek io:
    private int attempt;
    private int failureStreak;
    private boolean connecting;
    private ScheduledFuture<?> reconnectTask;



    private final AtomicInteger sessionIds = new AtomicInteger();

    public WorkerConnection(Logger log, Executor callbacks, Handler handler) {
        this.log = log;
        this.callbacks = callbacks;
        this.handler = handler;
        this.io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Panel-Worker");
            t.setDaemon(true);
            return t;
        });
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        io.scheduleAtFixedRate(this::heartbeat, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /** Zamienia {@code https://host} na {@code wss://host/plugin}. */
    static URI pluginUri(String workerUrl) {
        String u = workerUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.startsWith("https://")) {
            u = "wss://" + u.substring("https://".length());
        } else if (u.startsWith("http://")) {
            u = "ws://" + u.substring("http://".length());
        } else if (!u.startsWith("wss://") && !u.startsWith("ws://")) {
            u = "wss://" + u;
        }
        return URI.create(u + "/plugin");
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /** Uruchamia (lub restartuje z nowymi danymi) połączenie. */
    public void start(String workerUrl, String pluginSecret) {
        URI newUri = pluginUri(workerUrl);
        io.execute(() -> {
            this.uri = newUri;
            this.secret = pluginSecret;
            this.running = true;
            closeCurrent("restart");
            cancelReconnect();
            attempt = 0;
            failureStreak = 0;
            connect();
        });
    }

    /** Rozłącza i przestaje łączyć ponownie (np. po wyczyszczeniu worker-url w /panel reload). */
    public void disconnect() {
        safeIo(() -> {
            running = false;
            cancelReconnect();
            closeCurrent("disabled");
        });
    }

    public boolean isOpen() {
        return current != null;
    }

    /** Wysyła wiadomość (obiekt serializowany przez Gson na wątku io). Zwraca {@code false}, gdy brak połączenia. */
    public boolean send(Object message) {
        if (current == null) {
            return false;
        }
        try {
            io.execute(() -> {
                Session s = current;
                if (s != null) {
                    doSend(s, gson.toJson(message));
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    /** Czyste zamknięcie; blokuje najwyżej ~3 s. */
    public void stop() {
        running = false;
        Session s = current;
        current = null;
        Future<?> f = null;
        try {
            f = io.submit(() -> {
                cancelReconnect();
                if (s != null) {
                    s.end("stop");
                    try {
                        s.ws.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disabled").get(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // i tak przerywamy poniżej
                    } finally {
                        s.ws.abort();
                    }
                }
            });
            f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (f != null) {
                f.cancel(true);
            }
            if (s != null) {
                s.ws.abort();
            }
        }
        io.shutdownNow();
        http.shutdownNow();
    }

    // ------------------------------------------------------------------ wątek io

    private void connect() {
        if (!running) {
            return;
        }
        connecting = true;
        Session session = new Session(sessionIds.incrementAndGet());
        if (debug) {
            log.info("Łączenie z " + uri + " ...");
        }
        http.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer " + secret)
                .buildAsync(uri, session)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        safeIo(() -> connectFailed(err));
                    }
                });
    }

    private void connectFailed(Throwable err) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        connecting = false;
        failureStreak++;
        String reason;
        if (cause instanceof WebSocketHandshakeException hs) {
            int code = hs.getResponse().statusCode();
            reason = code == 401
                    ? "Worker odrzucił sekret (HTTP 401). Sprawdź plugin-secret w configu i PLUGIN_SECRET w Workerze."
                    : "handshake odrzucony, HTTP " + code;
        } else {
            reason = cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        }
        if (failureStreak == 1 || failureStreak % 20 == 0 || debug) {
            log.warning("Nie udało się połączyć z Workerem (" + uri + "): " + reason
                    + (failureStreak > 1 ? " [próba " + failureStreak + "]" : ""));
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running || reconnectTask != null || connecting || current != null) {
            return;
        }
        long delay = Math.min(MAX_BACKOFF_SEC, 1L << Math.min(attempt, 5));
        attempt++;
        reconnectTask = io.schedule(() -> {
            reconnectTask = null;
            connect();
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void doSend(Session s, String text) {
        if (s.ended.get()) {
            return;
        }
        try {
            s.ws.sendText(text, true).get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (debug) {
                log.info("-> " + (text.contains("\"link-token\"") ? "link-token (token ukryty)" : text.length() > 300 ? text.substring(0, 300) + "..." : text));
            }
        } catch (TimeoutException e) {
            log.warning("Wysyłanie do Workera trwa zbyt długo – zrywam połączenie.");
            s.ws.abort();
            sessionEnded(s, "send timeout");
        } catch (ExecutionException e) {
            if (debug) {
                log.log(Level.INFO, "Błąd wysyłania do Workera", e.getCause());
            }
            s.ws.abort();
            sessionEnded(s, "send error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void heartbeat() {
        Session s = current;
        if (s == null) {
            return;
        }
        // Watchdog pongów włącza się dopiero, gdy serwer choć raz odpowiedział na ping.
        if (s.pongSeen && System.currentTimeMillis() - s.lastPong > PONG_TIMEOUT_MS) {
            log.warning("Brak odpowiedzi od Workera – zrywam połączenie i łączę ponownie.");
            s.ws.abort();
            sessionEnded(s, "pong timeout");
            return;
        }
        try {
            s.ws.sendPing(ByteBuffer.allocate(0)).get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            s.ws.abort();
            sessionEnded(s, "ping failed");
        }
    }

    // ------------------------------------------------------------------ wspólne

    /** Koniec sesji (wołane z dowolnego wątku, idempotentne). */
    private void sessionEnded(Session s, String why) {
        if (!s.end(why)) {
            return;
        }
        if (current == s) {
            current = null;
            log.warning("Rozłączono z Workerem (" + why + ").");
            callbacks.execute(handler::onDisconnected);
            safeIo(this::scheduleReconnect);
        }
    }

    /** Zamyka bieżącą sesję bez planowania ponownego połączenia (restart / podmiana). */
    private void closeCurrent(String why) {
        Session s = current;
        if (s == null) {
            return;
        }
        if (s.end(why)) {
            current = null;
            callbacks.execute(handler::onDisconnected);
        }
        s.ws.abort();
    }

    private void safeIo(Runnable r) {
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // wyłączanie pluginu
        }
    }

    private void dispatch(String text) {
        JsonObject obj;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) {
                return;
            }
            obj = el.getAsJsonObject();
        } catch (RuntimeException e) {
            log.warning("Nieprawidłowy JSON od Workera: " + e.getMessage());
            return;
        }
        JsonElement t = obj.get("t");
        if (t == null || !t.isJsonPrimitive()) {
            return;
        }
        String type = t.getAsString();
        if (debug) {
            log.info("<- " + debugIncoming(type, text));
        }
        callbacks.execute(() -> handler.onMessage(type, obj));
    }

    /**
     * Tekst przychodzącej wiadomości do logu w trybie debug: {@code auth} (w dowolnej wielkości liter — tak rozpoznaje go
     * {@code PanelPlugin.onMessage}) bez treści, bo niesie hasło; reszta skrócona do 300 znaków.
     */
    static String debugIncoming(String type, String text) {
        if (type == null || "auth".equalsIgnoreCase(type.trim())) {
            return "auth (hasło ukryte)";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    /** Jedna próba połączenia = jeden listener. Callbacki starych sesji są ignorowane. */
    private final class Session implements WebSocket.Listener {
        final int id;
        final AtomicBoolean ended = new AtomicBoolean();
        final StringBuilder buffer = new StringBuilder();
        boolean tooLarge;
        volatile WebSocket ws;
        volatile long lastPong = System.currentTimeMillis();
        volatile boolean pongSeen;

        Session(int id) {
            this.id = id;
        }

        boolean end(String why) {
            return ended.compareAndSet(false, true);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            if (!running || ended.get()) {
                webSocket.abort();
                return;
            }
            if (current != null && current != this) {
                closeCurrent("replaced");
            }
            current = this;
            safeIo(() -> {
                connecting = false;
                attempt = 0;
                failureStreak = 0;
            });
            log.info("Połączono z Workerem: " + uri);
            send(Map.of("t", "hello", "v", 1));
            callbacks.execute(handler::onConnected);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (buffer.length() + data.length() > MAX_MESSAGE_CHARS) {
                log.warning("Za duża wiadomość od Workera – pomijam.");
                buffer.setLength(0);
                tooLarge = !last;
                webSocket.request(1);
                return null;
            }
            if (tooLarge) {
                tooLarge = !last;
                webSocket.request(1);
                return null;
            }
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                if (!ended.get()) {
                    dispatch(text);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1); // protokół używa tylko tekstu
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPong = System.currentTimeMillis();
            pongSeen = true;
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            sessionEnded(this, "kod " + statusCode + (reason == null || reason.isEmpty() ? "" : ", " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            sessionEnded(this, "błąd: " + error.getClass().getSimpleName()
                    + (error.getMessage() != null ? " " + error.getMessage() : ""));
        }
    }
}
