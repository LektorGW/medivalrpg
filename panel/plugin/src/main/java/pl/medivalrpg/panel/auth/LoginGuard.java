// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.auth;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Brute-force protection of the password login: failed attempts are counted per nick and per IP
 * (e.g. 5 per 15 minutes each). A blocked nick or IP is rejected before the password is even checked.
 * A successful login clears the nick's counter (not the IP's). Thread-safe.
 */
public final class LoginGuard {

    private final RateLimiter byName;
    private final RateLimiter byIp;

    public LoginGuard(final int attempts, final long windowMillis) {
        this(attempts, windowMillis, System::currentTimeMillis);
    }

    public LoginGuard(final int attempts, final long windowMillis, final LongSupplier clock) {
        this.byName = new RateLimiter(attempts, windowMillis, clock);
        this.byIp = new RateLimiter(attempts, windowMillis, clock);
    }

    private static String name(final String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String ip(final String ip) {
        return ip == null || ip.isBlank() ? "?" : ip.trim();
    }

    /** Milliseconds the attempt must wait (0 = may try now). */
    public long blockedFor(final String name, final String ip) {
        return Math.max(this.byName.retryAfter(name(name)), this.byIp.retryAfter(ip(ip)));
    }

    public void failure(final String name, final String ip) {
        this.byName.record(name(name));
        this.byIp.record(ip(ip));
    }

    public void success(final String name) {
        this.byName.reset(name(name));
    }

    public void cleanup() {
        this.byName.cleanup();
        this.byIp.cleanup();
    }
}
