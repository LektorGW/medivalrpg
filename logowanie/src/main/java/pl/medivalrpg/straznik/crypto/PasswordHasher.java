// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.crypto;

import java.util.concurrent.Semaphore;

/**
 * Hashing and verification with the configured Argon2id parameters. Every computation needs tens of MiB of memory,
 * so at most {@code permits} run at the same time (callers from any thread block until a permit is free).
 * Never call from the main server thread.
 */
public final class PasswordHasher {

    private final int memoryKiB;
    private final int iterations;
    private final int lanes;
    private final Semaphore permits;

    public PasswordHasher(final int memoryKiB, final int iterations, final int lanes, final int permits) {
        this.memoryKiB = memoryKiB;
        this.iterations = iterations;
        this.lanes = lanes;
        this.permits = new Semaphore(Math.max(1, permits), true);
    }

    public String hash(final String password) {
        this.permits.acquireUninterruptibly();
        try {
            return PhcArgon2.hash(Argon2.Type.ARGON2ID, password, this.memoryKiB, this.iterations, this.lanes);
        } finally {
            this.permits.release();
        }
    }

    /** {@code false} for a wrong password and for an unreadable hash. */
    public boolean verify(final String encoded, final String password) {
        this.permits.acquireUninterruptibly();
        try {
            return PhcArgon2.verify(encoded, password);
        } finally {
            this.permits.release();
        }
    }

    public boolean needsRehash(final String encoded) {
        return PhcArgon2.needsRehash(encoded, this.memoryKiB, this.iterations, this.lanes);
    }

    public String describe() {
        return "Argon2id m=" + this.memoryKiB + " KiB, t=" + this.iterations + ", p=" + this.lanes;
    }
}
