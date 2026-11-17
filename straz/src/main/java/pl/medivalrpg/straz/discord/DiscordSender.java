// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends webhook messages one after another on its own daemon thread (never the main thread). Handles Discord rate
 * limits (429 + {@code retry_after}) and retries 5xx/network errors three times. Without a valid webhook nothing is
 * sent (the caller already wrote the report to the log and the file). The URL is never logged.
 */
public final class DiscordSender implements AutoCloseable {

    /** Outcome for the caller (command feedback, tests). */
    public record Outcome(boolean sent, int status, String info) {
    }

    private final Logger logger;
    private final Supplier<String> url;
    private final Supplier<Boolean> allowLoopback;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Straz-Discord");
        t.setDaemon(true);
        return t;
    });

    public DiscordSender(final Logger logger, final Supplier<String> url, final Supplier<Boolean> allowLoopback) {
        this.logger = logger;
        this.url = url;
        this.allowLoopback = allowLoopback;
    }

    public boolean configured() {
        final String u = this.url.get();
        return u != null && !u.isBlank();
    }

    public boolean valid() {
        return WebhookUrl.valid(this.url.get(), this.allowLoopback.get());
    }

    public CompletableFuture<Outcome> send(final JsonObject body, final String what) {
        if (!this.configured()) {
            return CompletableFuture.completedFuture(new Outcome(false, 0, "brak webhooka — tylko log i plik"));
        }
        if (!this.valid()) {
            this.logger.warning("Discord: discord.webhook-url ma niepoprawny format — " + what + " tylko w logu i pliku.");
            return CompletableFuture.completedFuture(new Outcome(false, 0, "niepoprawny adres webhooka"));
        }
        final String target = this.url.get().strip() + "?wait=true";
        final byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        return CompletableFuture.supplyAsync(() -> this.post(target, payload, what), this.thread);
    }

    private Outcome post(final String target, final byte[] payload, final String what) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                final HttpRequest req = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "Straz-MEDIVALRPG (lektoro.pl, 1.0)")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
                final HttpResponse<String> res = this.http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                final int code = res.statusCode();
                if (code >= 200 && code < 300) {
                    return new Outcome(true, code, "wysłano");
                }
                if (code == 429 && attempt <= 5) {
                    sleep(retryAfterMs(res.body()));
                    continue;
                }
                if (code >= 500 && attempt <= 3) {
                    sleep(2_000L * attempt);
                    continue;
                }
                this.logger.warning("Discord: " + what + " odrzucone (HTTP " + code + ") — zostaje w logu i pliku."
                    + (code == 401 || code == 404 ? " Sprawdź, czy webhook nie został usunięty." : ""));
                return new Outcome(false, code, "HTTP " + code);
            } catch (final java.io.IOException e) {
                if (attempt <= 3) {
                    sleep(2_000L * attempt);
                    continue;
                }
                this.logger.log(Level.WARNING, "Discord: " + what + " nie wysłano (" + e.getClass().getSimpleName() + ")");
                return new Outcome(false, 0, e.getClass().getSimpleName());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(false, 0, "przerwano");
            }
        }
    }

    static long retryAfterMs(final String body) {
        try {
            final JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            final double s = o.get("retry_after").getAsDouble();
            return Math.min(60_000L, Math.max(250L, (long) (s * 1000)));
        } catch (final RuntimeException e) {
            return 2_000L;
        }
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        this.thread.shutdown();
        try {
            this.thread.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
