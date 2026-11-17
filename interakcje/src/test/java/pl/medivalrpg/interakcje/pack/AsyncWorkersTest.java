// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plugin stop of the resource pack tasks: no task may outlive onDisable (zip file error, async-task nag). */
@Timeout(20)
class AsyncWorkersTest {

    @Test
    void closeInterruptsASleepingDownloadCheckAndWaitsForIt() throws Exception {
        AsyncWorkers workers = new AsyncWorkers();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        try {
            pool.execute(() -> workers.run(true, () -> {
                started.countDown();
                try {
                    Thread.sleep(60_000); // the 15 s initial delay / backoff of the check
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                finished.set(true);
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            assertEquals(0, workers.close(5_000));
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2), "close() must not wait for the sleep");
            assertTrue(interrupted.get());
            assertTrue(finished.get(), "the task ended before close() returned");
            assertEquals(0, workers.active());
            // the pooled thread is not left interrupted for the next task (another plugin's, on Paper)
            AtomicBoolean nextSeesInterrupt = new AtomicBoolean(true);
            pool.submit(() -> nextSeesInterrupt.set(Thread.currentThread().isInterrupted())).get(5, TimeUnit.SECONDS);
            assertFalse(nextSeesInterrupt.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closeWaitsForAFileTaskWithoutInterruptingIt() throws Exception {
        AsyncWorkers workers = new AsyncWorkers();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread t = new Thread(() -> workers.run(false, () -> {
            started.countDown();
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < end) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted.set(true);
                }
                Thread.onSpinWait();
            }
        }));
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(0, workers.close(5_000));
        assertFalse(interrupted.get(), "hashing / writing the pack is never interrupted");
        assertEquals(0, workers.active());
        t.join(5_000);
    }

    @Test
    void closeGivesUpAfterTheTimeoutAndReportsTheStragglers() throws Exception {
        AsyncWorkers workers = new AsyncWorkers();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> workers.run(false, () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        assertEquals(1, workers.close(200));
        long took = System.nanoTime() - t0;
        assertTrue(took >= TimeUnit.MILLISECONDS.toNanos(150) && took < TimeUnit.SECONDS.toNanos(3), "bounded wait");
        release.countDown();
        t.join(5_000);
        assertEquals(0, workers.active());
    }

    @Test
    void noNewWorkAfterClose() {
        AsyncWorkers workers = new AsyncWorkers();
        assertEquals(0, workers.close(100));
        assertTrue(workers.closed());
        AtomicBoolean ran = new AtomicBoolean();
        workers.run(false, () -> ran.set(true));
        workers.run(true, () -> ran.set(true));
        assertFalse(ran.get(), "a task queued before the stop must not start the check again");
    }

    @Test
    void errorsAreReportedWhileRunningAndDroppedWhileStopping() throws Exception {
        AsyncWorkers workers = new AsyncWorkers();
        IllegalStateException boom = new IllegalStateException("boom");
        assertThrows(IllegalStateException.class, () -> workers.run(false, () -> {
            throw boom;
        }));
        assertEquals(0, workers.active());

        // the error Paper used to log at stop: a class load after the class loader closed
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                workers.run(false, () -> {
                    started.countDown();
                    try {
                        closed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("The plugin classloader for Interakcje has thrown a zip file error.",
                            new IllegalStateException("zip file closed"));
                });
            } catch (Throwable e) {
                escaped.set(e);
            }
        });
        t.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> workers.close(5_000));
        closer.start();
        while (!workers.closed()) {
            Thread.onSpinWait();
        }
        closed.countDown();
        closer.join(5_000);
        t.join(5_000);
        assertEquals(null, escaped.get(), "nothing reaches Paper's task error log after the stop");
        assertEquals(0, workers.active());
    }

    @Test
    void interruptAbortsARealHttpDownloadThatGetsNoAnswer() throws Exception {
        // A web server that accepts the connection but never answers (squaremap stopping, a hung host):
        // before the fix the check sat in HttpClient.send for up to the 20 s request timeout during the stop.
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> accepted = new CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        accepted.add(server.accept());
                    }
                } catch (IOException ignored) {
                    // server closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            AsyncWorkers workers = new AsyncWorkers();
            var http = PackVerifier.httpClient(Duration.ofSeconds(5));
            PackVerifier.Fetcher fetcher = PackVerifier.httpFetcher(http, Duration.ofSeconds(60));
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/pack/medivalrpg.zip";
            PackVerifier verifier = new PackVerifier(fetcher, millis -> { }, new PackVerifier.Policy(3, 0, 0, 0));
            AtomicReference<Object> result = new AtomicReference<>();
            CountDownLatch inside = new CountDownLatch(1);
            Thread t = new Thread(() -> workers.run(true, () -> {
                inside.countDown();
                try {
                    result.set(verifier.run(url, "0".repeat(40), null, () -> false));
                } catch (InterruptedException e) {
                    result.set(e);
                } finally {
                    http.shutdownNow();
                }
            }));
            t.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // let the request reach the silent server
            long t0 = System.nanoTime();
            assertEquals(0, workers.close(5_000));
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(3), "the request is aborted, not waited out");
            t.join(5_000);
            assertInstanceOf(InterruptedException.class, result.get());
            for (Socket s : accepted) {
                s.close();
            }
        }
    }
}
