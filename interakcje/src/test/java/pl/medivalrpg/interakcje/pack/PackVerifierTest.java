// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackVerifierTest {

    private static final String URL = "http://mc.example.org:8100/pack/medivalrpg.zip?v=67eec2c7aa9c";
    private static final String LOCAL = "http://127.0.0.1:44727/pack/medivalrpg.zip?v=67eec2c7aa9c";
    private static final byte[] PACK = "pack-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String SHA1 = PackFiles.sha1(PACK);
    private static final PackVerifier.Policy POLICY = new PackVerifier.Policy(6, 15_000, 5_000, 60_000);

    /** Scripted fetcher: each call takes the next step for that URL (an exception or a response). */
    private static final class Script implements PackVerifier.Fetcher {
        final Deque<Object> publicSteps = new ArrayDeque<>();
        final Deque<Object> localSteps = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public PackVerifier.Response fetch(String url) throws IOException {
            calls.add(url);
            Object step = (url.equals(URL) ? publicSteps : localSteps).poll();
            if (step instanceof IOException e) {
                throw e;
            }
            if (step == null) {
                throw new AssertionError("unexpected fetch " + url);
            }
            return (PackVerifier.Response) step;
        }
    }

    private static PackVerifier.Response ok() {
        return new PackVerifier.Response(200, PACK);
    }

    private static ConnectException refused() {
        // What java.net.http.HttpClient throws on a refused connection: no message, logged as "(null)" before.
        ConnectException e = new ConnectException();
        e.initCause(new ClosedChannelException());
        return e;
    }

    @Test
    void backoffWaitsFirstThenDoublesUpToTheCap() {
        assertEquals(15_000, POLICY.delayBefore(1));
        assertEquals(5_000, POLICY.delayBefore(2));
        assertEquals(10_000, POLICY.delayBefore(3));
        assertEquals(20_000, POLICY.delayBefore(4));
        assertEquals(40_000, POLICY.delayBefore(5));
        assertEquals(60_000, POLICY.delayBefore(6));
        assertEquals(60_000, POLICY.delayBefore(30));
        assertEquals(150_000, POLICY.totalDelay());
        PackVerifier.Policy odd = new PackVerifier.Policy(0, -1, -5, -10);
        assertEquals(1, odd.attempts());
        assertEquals(0, odd.totalDelay());
    }

    @Test
    void webServerStartingLateIsNotReported() throws InterruptedException {
        Script s = new Script();
        s.publicSteps.add(refused());
        s.publicSteps.add(new PackVerifier.Response(404, new byte[0]));
        s.publicSteps.add(ok());
        List<Long> waits = new ArrayList<>();
        PackVerifier.Outcome o = new PackVerifier(s, waits::add, POLICY).run(URL, SHA1, LOCAL, () -> false);
        assertEquals(PackVerifier.Kind.OK, o.kind());
        assertEquals(3, o.attempts());
        assertFalse(o.alarming());
        assertEquals(List.of(15_000L, 5_000L, 10_000L), waits);
        assertEquals(List.of(URL, URL, URL), s.calls);
    }

    @Test
    void warnsOnlyAfterAllAttemptsWithReadableReason() throws InterruptedException {
        Script s = new Script();
        for (int i = 0; i < 6; i++) {
            s.publicSteps.add(refused());
        }
        s.localSteps.add(refused());
        List<Long> waits = new ArrayList<>();
        PackVerifier.Outcome o = new PackVerifier(s, waits::add, POLICY).run(URL, SHA1, LOCAL, () -> false);
        assertEquals(PackVerifier.Kind.UNREACHABLE, o.kind());
        assertTrue(o.alarming());
        assertEquals(6, o.attempts());
        assertEquals(6, waits.size());
        assertEquals(LOCAL, s.calls.getLast());
        assertEquals("połączenie odrzucone lub host nieosiągalny (ConnectException ← ClosedChannelException)", o.reason());
        assertFalse(o.reason().contains("null"));
        assertEquals(o.reason(), o.local());
    }

    @Test
    void publicAddressUnreachableFromInsideButServedLocallyIsNotAnAlarm() throws InterruptedException {
        Script s = new Script();
        for (int i = 0; i < 6; i++) {
            s.publicSteps.add(refused());
        }
        s.localSteps.add(ok());
        PackVerifier.Outcome o = new PackVerifier(s, ms -> { }, POLICY).run(URL, SHA1, LOCAL, () -> false);
        assertEquals(PackVerifier.Kind.LOCAL_ONLY, o.kind());
        assertFalse(o.alarming());
        assertEquals(LOCAL, o.localUrl());
    }

    @Test
    void httpErrorsAndStaleFilesAreRetriedThenReportedWithoutLocalFallback() throws InterruptedException {
        Script s = new Script();
        for (int i = 0; i < 6; i++) {
            s.publicSteps.add(new PackVerifier.Response(404, new byte[0]));
        }
        PackVerifier.Outcome o = new PackVerifier(s, ms -> { }, POLICY).run(URL, SHA1, LOCAL, () -> false);
        assertEquals(PackVerifier.Kind.HTTP_STATUS, o.kind());
        assertEquals(404, o.status());
        assertEquals(6, s.calls.size());
        assertNull(o.localUrl());

        Script stale = new Script();
        stale.publicSteps.add(new PackVerifier.Response(200, "old".getBytes(StandardCharsets.UTF_8)));
        PackVerifier.Outcome o2 = new PackVerifier(stale, ms -> { }, new PackVerifier.Policy(1, 0, 0, 0))
                .run(URL, SHA1, LOCAL, () -> false);
        assertEquals(PackVerifier.Kind.SHA_MISMATCH, o2.kind());
        assertTrue(o2.alarming());
    }

    @Test
    void newerPackOrStopCancelsQuietly() throws InterruptedException {
        Script s = new Script();
        s.publicSteps.add(refused());
        AtomicBoolean cancelled = new AtomicBoolean();
        PackVerifier.Outcome o = new PackVerifier(s, ms -> {
            if (ms == 5_000) {
                cancelled.set(true); // stopped while waiting before the 2nd attempt
            }
        }, POLICY).run(URL, SHA1, LOCAL, cancelled::get);
        assertEquals(PackVerifier.Kind.CANCELLED, o.kind());
        assertEquals(1, o.attempts());
        assertFalse(o.alarming());
        assertEquals(List.of(URL), s.calls);
    }

    @Test
    void describeNamesTheCauseInsteadOfNull() {
        assertEquals("przekroczono czas nawiązywania połączenia (HttpConnectTimeoutException: HTTP connect timed out)",
                PackVerifier.describe(new HttpConnectTimeoutException("HTTP connect timed out")));
        ConnectException dns = new ConnectException();
        dns.initCause(new UnresolvedAddressException());
        assertEquals("nie znaleziono hosta (DNS) (ConnectException ← UnresolvedAddressException)", PackVerifier.describe(dns));
        assertEquals("błąd sieci (IOException: HTTP/1.1 header parser received no bytes)",
                PackVerifier.describe(new IOException("HTTP/1.1 header parser received no bytes")));
    }

    /**
     * Real HttpClient against a real local web server that starts only after the first attempt (like squaremap's
     * web server at startup): the first attempt is refused, a later one succeeds, nothing alarming is reported.
     */
    @Test
    void realHttpServerStartingAfterFirstAttempt() throws Exception {
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port + "/pack/medivalrpg.zip?v=" + SHA1.substring(0, 12);
        PackVerifier.Fetcher http = PackVerifier.httpFetcher(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5));

        // Without a server: the JDK client fails with a message-less exception; we still get a readable reason.
        IOException raw = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> http.fetch(url));
        assertFalse(PackVerifier.describe(raw).contains("null"));
        // Linux refuses at once; Windows retries the SYN and may hit the connect timeout first.
        String reason = PackVerifier.describe(raw);
        System.out.println("closed port: " + raw + " -> " + reason);
        assertTrue(reason.startsWith("połączenie odrzucone") || reason.startsWith("przekroczono czas"), reason);

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create();
        List<String> served = new ArrayList<>();
        server.createContext("/pack/medivalrpg.zip", ex -> {
            served.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(200, PACK.length);
            ex.getResponseBody().write(PACK);
            ex.close();
        });
        PackVerifier.Sleeper startServerOnSecondWait = ms -> {
            if (ms == 5) {
                try {
                    server.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 0);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                server.start();
            }
        };
        try {
            PackVerifier.Outcome o = new PackVerifier(http, startServerOnSecondWait, new PackVerifier.Policy(4, 1, 5, 20))
                    .run(url, SHA1, null, () -> false);
            assertEquals(PackVerifier.Kind.OK, o.kind(), String.valueOf(o.reason()));
            assertEquals(2, o.attempts());
            assertEquals(List.of("/pack/medivalrpg.zip?v=" + SHA1.substring(0, 12)), served);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localUrlKeepsPathAndQueryOnLoopback() {
        assertEquals(LOCAL, PackVerifier.localUrl("auto", URL));
        assertEquals("http://127.0.0.1:80/pack/a.zip", PackVerifier.localUrl("AUTO", "http://example.org/pack/a.zip"));
        assertEquals("http://10.0.0.5:8080/pack/medivalrpg.zip?v=67eec2c7aa9c",
                PackVerifier.localUrl("http://10.0.0.5:8080/", URL));
        assertNull(PackVerifier.localUrl("off", URL));
        assertNull(PackVerifier.localUrl("", URL));
        assertNull(PackVerifier.localUrl("auto", "http://bad host/x"));
    }
}
