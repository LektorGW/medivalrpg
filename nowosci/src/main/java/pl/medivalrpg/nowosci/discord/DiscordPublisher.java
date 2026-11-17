// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.storage.AtomicFiles;
import pl.medivalrpg.nowosci.storage.IoExecutor;

/**
 * Posts changelog entries to a Discord channel webhook ({@code discord.webhook-url}). Remembers the highest posted
 * id in {@code discord.json}, so each entry is posted once — also entries uploaded while the server was off are
 * posted at the next start. The first start with a webhook only records the current newest id (no flood of history).
 * HTTP runs on the plugin's I/O thread, one entry after another, never on the main thread.
 */
public final class DiscordPublisher {

    private final Path file;
    private final IoExecutor io;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Sender sender;
    private final AtomicBoolean busy = new AtomicBoolean();
    /** After a failed post, wait this long before trying again. */
    static final long RETRY_MILLIS = 5 * 60 * 1000L;

    /** Sends one webhook payload and returns the HTTP status (seam for tests; the plugin uses {@link HttpClient}). */
    @FunctionalInterface
    interface Sender {
        int send(JsonObject payload, String url) throws IOException, InterruptedException;
    }

    /**
     * Highest entry id already posted; -1 = never initialized. Changed on the I/O thread only. Advanced in memory
     * as soon as a post succeeds, even when {@code discord.json} cannot be written, so the same entry is never posted
     * twice while the server runs.
     */
    private volatile int lastPosted = -1;
    /** Highest id stored in {@code discord.json}; lower than {@link #lastPosted} only after a failed write. */
    private volatile int persisted = -1;
    private volatile long retryAt;

    public DiscordPublisher(final Path file, final IoExecutor io, final Logger logger) {
        this(file, io, logger, null);
    }

    DiscordPublisher(final Path file, final IoExecutor io, final Logger logger, final Sender sender) {
        this.file = file;
        this.io = io;
        this.logger = logger;
        this.sender = sender != null ? sender : this::send;
        try {
            if (Files.exists(file)) {
                this.lastPosted = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject().get("lastPostedId").getAsInt();
                this.persisted = this.lastPosted;
            }
        } catch (final IOException | RuntimeException e) {
            logger.warning(file.getFileName() + ": nie można odczytać (" + e.getMessage() + ") — zacznę od najnowszego wpisu.");
        }
    }

    /**
     * Posts every entry newer than the last posted one, up to {@code upToId} (oldest first). {@code upToId} is the
     * newest id read from changelog.yml: entries still being written with {@code /nowosci dodaj|punkt} wait for
     * {@code /nowosci oglos} (or the next start). Cheap when there is nothing new, so it is
     * also called periodically — that retries a failed post (at most every {@link #RETRY_MILLIS}).
     * Call on the main thread; returns at once.
     */
    public void publishNew(final Changelog changelog, final int upToId, final NowosciConfig cfg) {
        final int maxId = Math.min(changelog.maxId(), upToId);
        final boolean unsaved = this.persisted < this.lastPosted;
        if (cfg.discordWebhook.isBlank() || maxId <= 0
            || (this.lastPosted >= 0 && maxId <= this.lastPosted && !unsaved)
            || System.currentTimeMillis() < this.retryAt
            || !this.busy.compareAndSet(false, true)) {
            return;
        }
        final List<Entry> newestFirst = changelog.entries();
        this.io.submit("discord", () -> {
            try {
                if (this.lastPosted < 0) {
                    if (this.remember(maxId)) {
                        this.logger.info("Discord: webhook ustawiony — kolejne wpisy (po #" + maxId + ") trafią na kanał.");
                    }
                    return;
                }
                if (this.persisted < this.lastPosted && !this.remember(this.lastPosted)) {
                    return; // still cannot save: post nothing new, so a restart re-posts at most one entry
                }
                for (final Entry e : newestFirst.reversed()) {
                    if (e.id() <= this.lastPosted || e.id() > maxId) {
                        continue;
                    }
                    if (!this.post(this.payload(e, cfg), cfg.discordWebhook)) {
                        this.retryAt = System.currentTimeMillis() + RETRY_MILLIS;
                        return;
                    }
                    if (!this.remember(e.id())) {
                        return;
                    }
                }
            } finally {
                this.busy.set(false);
            }
        }, () -> this.busy.set(false));
    }

    /** {@code /nowosci oglos}: posts {@code entry} even if it was posted before. Call on the main thread. */
    public void publish(final Entry entry, final NowosciConfig cfg) {
        if (cfg.discordWebhook.isBlank()) {
            return;
        }
        final JsonObject payload = this.payload(entry, cfg);
        this.io.submit("discord", () -> {
            if (this.post(payload, cfg.discordWebhook) && entry.id() > this.lastPosted) {
                this.remember(entry.id());
            }
        }, null);
    }

    private JsonObject payload(final Entry e, final NowosciConfig cfg) {
        return DiscordFormat.payload(e, cfg.category(e.category()), cfg.dateFormat, cfg.discordUsername,
            cfg.discordAvatarUrl, cfg.discordMention);
    }

    private int send(final JsonObject payload, final String url) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("User-Agent", "MEDIVALRPG-Nowosci (https://lektoro.pl, 1.0)")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
            .build();
        return this.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private boolean post(final JsonObject payload, final String url) {
        try {
            final int status = this.sender.send(payload, url);
            if (status / 100 == 2) {
                return true;
            }
            this.logger.warning("Discord odrzucił wpis (HTTP " + status + "). Adres i odpowiedź ukryto.");
        } catch (final IOException | IllegalArgumentException e) {
            this.logger.warning("Discord: nie udało się wysłać wpisu (" + e.getClass().getSimpleName() + ")");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * Marks {@code id} as posted: in memory first (never posted again while the server runs), then in
     * {@code discord.json}. A failed write is logged and retried by {@link #publishNew} after
     * {@link #RETRY_MILLIS}; returns {@code false} then.
     */
    private boolean remember(final int id) {
        this.lastPosted = Math.max(this.lastPosted, id);
        final JsonObject o = new JsonObject();
        o.addProperty("lastPostedId", this.lastPosted);
        try {
            AtomicFiles.write(this.file, o.toString());
            this.persisted = this.lastPosted;
            return true;
        } catch (final IOException | RuntimeException e) {
            this.retryAt = System.currentTimeMillis() + RETRY_MILLIS;
            this.logger.warning("Discord: wpis #" + this.lastPosted + " zapamiętany, ale nie można zapisać "
                + this.file.getFileName() + " (" + e.getClass().getSimpleName() + ") — ponowię zapis za "
                + RETRY_MILLIS / 60_000 + " min; do tego czasu nic nowego nie wyślę.");
            return false;
        }
    }

    /** Tests: forget the wait after a failure. */
    void retryNow() {
        this.retryAt = 0;
    }

    /** Highest id remembered as posted (tests, diagnostics). */
    int lastPosted() {
        return this.lastPosted;
    }
}
