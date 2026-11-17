// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Self-check of the published resource pack URL (diagnostics only, players download it on their own).
 *
 * <p>At server start the pack is published while the server is still starting, so the first download may fail only
 * because the web server (squaremap) or the network path is not ready yet. The check therefore waits
 * {@link Policy#initialDelayMillis()}, retries with exponential backoff and reports a failure only after the last
 * attempt. When the public address keeps failing with a network error, the same file is fetched once from the local
 * address: a host often cannot reach its own public address (no NAT loopback) although players can.
 *
 * <p>Plain Java (no Bukkit): the caller runs {@link #run} on an async thread and logs the {@link Outcome}.
 */
public final class PackVerifier {

    /** HTTP GET of a URL. */
    @FunctionalInterface
    public interface Fetcher {
        Response fetch(String url) throws IOException, InterruptedException;
    }

    /** Waits between attempts (real sleep in production, recorded in tests). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record Response(int status, byte[] body) {
    }

    /**
     * @param attempts           downloads of the public URL (at least 1)
     * @param initialDelayMillis wait before the first attempt
     * @param retryDelayMillis   wait before the second attempt, doubled for each next one
     * @param maxDelayMillis     upper bound of a single wait
     */
    public record Policy(int attempts, long initialDelayMillis, long retryDelayMillis, long maxDelayMillis) {
        public Policy {
            attempts = Math.max(1, attempts);
            initialDelayMillis = Math.max(0, initialDelayMillis);
            retryDelayMillis = Math.max(0, retryDelayMillis);
            maxDelayMillis = Math.max(retryDelayMillis, maxDelayMillis);
        }

        /** Wait before attempt {@code n} (1-based): initial delay, then retry * 2^(n-2), capped. */
        public long delayBefore(int n) {
            if (n <= 1) {
                return initialDelayMillis;
            }
            long d = retryDelayMillis;
            for (int i = 2; i < n && d < maxDelayMillis; i++) {
                d = d * 2;
            }
            return Math.min(d, maxDelayMillis);
        }

        /** Sum of all waits (how long a full failed check takes, without request time). */
        public long totalDelay() {
            long sum = 0;
            for (int n = 1; n <= attempts; n++) {
                sum += delayBefore(n);
            }
            return sum;
        }
    }

    public enum Kind {
        /** Public URL serves the expected file. */
        OK,
        /** Public URL unreachable from the server, but the local address serves the expected file. */
        LOCAL_ONLY,
        /** Public URL answered with an HTTP error. */
        HTTP_STATUS,
        /** Public URL serves a different file. */
        SHA_MISMATCH,
        /** Public URL unreachable (and the local check failed or was not possible). */
        UNREACHABLE,
        /** A newer pack was published or the plugin stopped. */
        CANCELLED
    }

    /**
     * @param attempts how many downloads of the public URL were made
     * @param reason   readable Polish reason of the last failure ({@code null} for OK / CANCELLED)
     * @param local    result of the local fallback ({@code null} when not tried)
     */
    public record Outcome(Kind kind, int attempts, int status, String reason, String localUrl, String local) {
        public boolean alarming() {
            return kind == Kind.HTTP_STATUS || kind == Kind.SHA_MISMATCH || kind == Kind.UNREACHABLE;
        }
    }

    private final Fetcher fetcher;
    private final Sleeper sleeper;
    private final Policy policy;

    public PackVerifier(Fetcher fetcher, Sleeper sleeper, Policy policy) {
        this.fetcher = fetcher;
        this.sleeper = sleeper;
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    /**
     * Downloads {@code url} until it serves a file with {@code sha1} or attempts run out.
     *
     * @param localUrl  same file on the local web server, tried once after network failures ({@code null} = skip)
     * @param cancelled checked before every attempt
     */
    public Outcome run(String url, String sha1, String localUrl, BooleanSupplier cancelled) throws InterruptedException {
        Kind kind = Kind.UNREACHABLE;
        int status = 0;
        String reason = "brak próby";
        int n = 0;
        while (n < policy.attempts()) {
            n++;
            long wait = policy.delayBefore(n);
            if (wait > 0) {
                sleeper.sleep(wait);
            }
            if (cancelled.getAsBoolean()) {
                return new Outcome(Kind.CANCELLED, n - 1, 0, null, null, null);
            }
            try {
                Response res = fetcher.fetch(url);
                if (res.status() != 200) {
                    kind = Kind.HTTP_STATUS;
                    status = res.status();
                    reason = "HTTP " + res.status();
                } else if (!PackFiles.sha1(res.body()).equals(sha1)) {
                    kind = Kind.SHA_MISMATCH;
                    status = 200;
                    reason = "pobrany plik ma inny SHA-1 niż opublikowany";
                } else {
                    return new Outcome(Kind.OK, n, 200, null, null, null);
                }
            } catch (IOException | RuntimeException e) {
                kind = Kind.UNREACHABLE;
                status = 0;
                reason = describe(e);
            }
        }
        if (kind != Kind.UNREACHABLE || localUrl == null || localUrl.equals(url) || cancelled.getAsBoolean()) {
            return new Outcome(kind, n, status, reason, null, null);
        }
        String local;
        try {
            Response res = fetcher.fetch(localUrl);
            if (res.status() == 200 && PackFiles.sha1(res.body()).equals(sha1)) {
                return new Outcome(Kind.LOCAL_ONLY, n, 0, reason, localUrl, "działa, SHA-1 zgodny");
            }
            local = res.status() != 200 ? "HTTP " + res.status() : "inny SHA-1";
        } catch (IOException | RuntimeException e) {
            local = describe(e);
        }
        return new Outcome(Kind.UNREACHABLE, n, 0, reason, localUrl, local);
    }

    /** Production fetcher: {@link HttpClient} GET following redirects (own client, never shut down). */
    public static Fetcher httpFetcher(Duration connectTimeout, Duration requestTimeout) {
        return httpFetcher(httpClient(connectTimeout), requestTimeout);
    }

    /** Client for {@link #httpFetcher(HttpClient, Duration)}; the caller shuts it down when the check ends. */
    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /**
     * Production fetcher on a given client. An interrupt of the calling thread aborts a running request with
     * {@link InterruptedException} (plugin stop).
     */
    public static Fetcher httpFetcher(HttpClient http, Duration requestTimeout) {
        return url -> {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestTimeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(res.statusCode(), res.body());
        };
    }

    /**
     * Local address of the published file: {@code base} ("auto" = {@code http://127.0.0.1:<port of public URL>})
     * plus the path and query of the public URL. {@code null} when disabled or not derivable.
     */
    public static String localUrl(String base, String publicUrl) {
        if (base == null || base.isBlank() || base.equalsIgnoreCase("off") || base.equalsIgnoreCase("false")) {
            return null;
        }
        try {
            URI pub = URI.create(publicUrl);
            String root;
            if (base.equalsIgnoreCase("auto")) {
                int port = pub.getPort() > 0 ? pub.getPort() : ("https".equalsIgnoreCase(pub.getScheme()) ? 443 : 80);
                root = "http://127.0.0.1:" + port;
            } else {
                root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            }
            String path = pub.getRawPath() == null || pub.getRawPath().isEmpty() ? "/" : pub.getRawPath();
            return root + path + (pub.getRawQuery() == null ? "" : "?" + pub.getRawQuery());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Readable Polish reason of a download failure. {@code java.net.http.HttpClient} throws e.g.
     * {@link ConnectException} without a message, which used to be logged as "(null)".
     */
    public static String describe(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t) && chain.size() < 8; t = t.getCause()) {
            chain.add(t);
        }
        String what = known(chain);
        String detail = null;
        for (Throwable t : chain) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) {
                detail = m.strip();
                break;
            }
        }
        StringBuilder sb = new StringBuilder(what).append(" (").append(chain.getFirst().getClass().getSimpleName());
        if (chain.size() > 1) {
            sb.append(" ← ").append(chain.getLast().getClass().getSimpleName());
        }
        if (detail != null) {
            sb.append(": ").append(detail);
        }
        return sb.append(')').toString();
    }

    /** Most specific known cause anywhere in the chain (a timeout is often wrapped in / wraps a ConnectException). */
    private static String known(List<Throwable> chain) {
        if (any(chain, HttpConnectTimeoutException.class)) {
            return "przekroczono czas nawiązywania połączenia";
        }
        if (any(chain, HttpTimeoutException.class) || any(chain, SocketTimeoutException.class)) {
            return "przekroczono czas oczekiwania na odpowiedź";
        }
        if (any(chain, UnknownHostException.class) || any(chain, UnresolvedAddressException.class)) {
            return "nie znaleziono hosta (DNS)";
        }
        if (any(chain, NoRouteToHostException.class)) {
            return "brak trasy do hosta";
        }
        if (any(chain, ConnectException.class)) {
            return "połączenie odrzucone lub host nieosiągalny";
        }
        if (any(chain, IllegalArgumentException.class)) {
            return "nieprawidłowy adres";
        }
        return "błąd sieci";
    }

    private static boolean any(List<Throwable> chain, Class<? extends Throwable> type) {
        for (Throwable t : chain) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
