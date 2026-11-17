// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.straz.state.StateStore;
import pl.medivalrpg.straz.state.StrazState;

/** stan.json is saved by the I/O thread, the backup thread and the shutdown: parallel saves must not collide. */
class StateStoreTest {

    @TempDir
    Path dir;

    @Test
    void parallelSavesNeverFailOrCorruptTheFile() throws Exception {
        final StateStore store = new StateStore(this.dir.resolve("stan.json"), Logger.getAnonymousLogger());
        final StrazState state = store.load();
        for (int i = 0; i < 200; i++) {
            state.addRecentAlert("alert " + i + " " + "x".repeat(200));
        }
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 40; i++) {
                        synchronized (state) {
                            state.lastReportAt++;
                        }
                        store.save(state);
                    }
                } catch (final Exception e) {
                    failures.incrementAndGet();
                }
            });
            th.start();
            threads.add(th);
        }
        start.countDown();
        for (final Thread th : threads) {
            th.join();
        }
        assertEquals(0, failures.get(), "no save may fail");
        assertFalse(Files.exists(this.dir.resolve("stan.json.tmp")), "no temporary file left");
        final String json = Files.readString(this.dir.resolve("stan.json"), StandardCharsets.UTF_8);
        com.google.gson.JsonParser.parseString(json);
        final StrazState back = store.load();
        assertEquals(240, back.lastReportAt, "the last save wins with the final counter");
        assertTrue(back.recentAlerts.size() <= 30);
    }
}
