// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.nowosci.Fixtures;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.storage.IoExecutor;

/** Each changelog entry reaches Discord once: across restarts, HTTP errors and a failing {@code discord.json} write. */
class DiscordPublisherTest {

    private static final Logger LOG = Logger.getLogger("DiscordPublisherTest");

    @TempDir
    Path dir;

    private final NowosciConfig cfg = Fixtures.format(Map.of("discord.webhook-url", "https://example.invalid/webhook"))
        .config();
    private final IoExecutor io = new IoExecutor(LOG);
    /** Ids of entries "posted" to Discord, in order. */
    private final List<Integer> posted = new CopyOnWriteArrayList<>();
    /** Next HTTP statuses to answer with; empty = 204. */
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        this.io.shutdown();
    }

    private DiscordPublisher publisher(final Path file) {
        return new DiscordPublisher(file, this.io, LOG, (payload, url) -> {
            final int status = this.statuses.isEmpty() ? 204 : this.statuses.removeFirst();
            if (status == -1) {
                throw new HttpTimeoutException("timeout");
            }
            if (status / 100 == 2) {
                final String text = payload.toString();
                final int at = text.indexOf("wpis #") + "wpis #".length();
                this.posted.add(Integer.parseInt(text.substring(at).replaceAll("^(\\d+).*$", "$1")));
            }
            return status;
        });
    }

    private static Changelog changelog(final int maxId) {
        final List<Entry> entries = new ArrayList<>();
        for (int id = 1; id <= maxId; id++) {
            entries.add(new Entry(id, LocalDate.of(2026, 10, 29), "Wpis " + id, "nowe", List.of("Punkt")));
        }
        return new Changelog(entries, maxId + 1);
    }

    private void publish(final DiscordPublisher p, final Changelog c) throws InterruptedException {
        p.publishNew(c, c.maxId(), this.cfg);
        this.drain();
    }

    /** Waits until every task queued on the I/O thread so far has run. */
    private void drain() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        this.io.submit("test", latch::countDown, null);
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    private static void writeState(final Path file, final int id) throws IOException {
        Files.writeString(file, "{\"lastPostedId\":" + id + "}", StandardCharsets.UTF_8);
    }

    private static int readState(final Path file) throws IOException {
        return Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).replaceAll("\\D", ""));
    }

    /** Makes {@code file} impossible to replace: a non-empty directory in its place. */
    private static void breakFile(final Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.createDirectories(file);
        Files.writeString(file.resolve("blocker"), "x");
    }

    private static void repairFile(final Path file) throws IOException {
        try (Stream<Path> walk = Files.walk(file)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void firstStartOnlyRecordsTheNewestEntry() throws Exception {
        final Path file = this.dir.resolve("discord.json");
        this.publish(this.publisher(file), changelog(5));
        assertEquals(List.of(), this.posted);
        assertEquals(5, readState(file));
    }

    @Test
    void newEntriesArePostedOnceOldestFirstAndNotAgainAfterRestart() throws Exception {
        final Path file = this.dir.resolve("discord.json");
        writeState(file, 3);
        final DiscordPublisher first = this.publisher(file);
        this.publish(first, changelog(5));
        assertEquals(List.of(4, 5), this.posted);
        assertEquals(5, readState(file));
        this.publish(first, changelog(5));

        final DiscordPublisher restarted = this.publisher(file);
        this.publish(restarted, changelog(5));
        assertEquals(List.of(4, 5), this.posted);
    }

    @Test
    void rateLimitOrTimeoutIsRetriedLaterWithoutSkippingOrRepeating() throws Exception {
        final Path file = this.dir.resolve("discord.json");
        writeState(file, 3);
        final DiscordPublisher p = this.publisher(file);
        this.statuses.add(429);
        this.publish(p, changelog(5));
        assertEquals(List.of(), this.posted);
        this.publish(p, changelog(5)); // waiting RETRY_MILLIS: nothing is sent
        assertEquals(List.of(), this.posted);

        p.retryNow();
        this.statuses.addAll(List.of(204, -1));
        this.publish(p, changelog(5));
        assertEquals(List.of(4), this.posted);
        assertEquals(4, readState(file));

        p.retryNow();
        this.publish(p, changelog(5));
        assertEquals(List.of(4, 5), this.posted);
        assertEquals(5, readState(file));
    }

    @Test
    void failedStateWriteNeverRepostsAndIsRetried() throws Exception {
        final Path file = this.dir.resolve("discord.json");
        writeState(file, 3);
        final DiscordPublisher p = this.publisher(file);
        breakFile(file);

        this.publish(p, changelog(5));
        assertEquals(List.of(4), this.posted, "stops after the entry whose id could not be saved");
        assertEquals(4, p.lastPosted());

        // The heartbeat calls publishNew every second: entry 4 must never go out again.
        for (int i = 0; i < 3; i++) {
            p.retryNow();
            this.publish(p, changelog(5));
        }
        assertEquals(List.of(4), this.posted);

        repairFile(file);
        p.retryNow();
        this.publish(p, changelog(5));
        assertEquals(List.of(4, 5), this.posted);
        assertEquals(5, readState(file));

        final DiscordPublisher restarted = this.publisher(file);
        this.publish(restarted, changelog(5));
        assertEquals(List.of(4, 5), this.posted);
    }

    @Test
    void failedWriteOnFirstStartIsRetriedWithoutPosting() throws Exception {
        final Path file = this.dir.resolve("discord.json");
        breakFile(file);
        // a directory in place of the file cannot be read either: treated as a first start
        final DiscordPublisher p = this.publisher(file);
        this.publish(p, changelog(2));
        p.retryNow();
        this.publish(p, changelog(2));
        assertEquals(List.of(), this.posted);

        repairFile(file);
        p.retryNow();
        this.publish(p, changelog(2));
        assertEquals(2, readState(file));
        assertEquals(List.of(), this.posted);
        assertFalse(Files.isDirectory(file));
    }
}
