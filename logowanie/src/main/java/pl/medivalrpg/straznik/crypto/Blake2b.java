// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.crypto;

import java.util.Arrays;

/**
 * BLAKE2b (RFC 7693) without key, salt or personalisation, output length 1..64 bytes. Used only by {@link Argon2}.
 * Not thread-safe; one instance per hash.
 */
final class Blake2b {

    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    };

    private static final byte[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    };

    private final long[] h = new long[8];
    private final byte[] buffer = new byte[128];
    private final long[] m = new long[16];
    private final long[] v = new long[16];
    private final int outLength;
    private int bufferLength;
    private long counter;

    Blake2b(final int outLength) {
        if (outLength < 1 || outLength > 64) {
            throw new IllegalArgumentException("BLAKE2b output length must be 1..64");
        }
        this.outLength = outLength;
        System.arraycopy(IV, 0, this.h, 0, 8);
        this.h[0] ^= 0x01010000L ^ outLength;
    }

    Blake2b update(final byte[] data) {
        return this.update(data, 0, data.length);
    }

    Blake2b update(final byte[] data, final int offset, final int length) {
        int off = offset;
        int len = length;
        while (len > 0) {
            if (this.bufferLength == 128) {
                // Only compress a full buffer when more data follows: the last block is compressed in digest().
                this.counter += 128;
                this.compress(this.buffer, 0, false);
                this.bufferLength = 0;
            }
            final int n = Math.min(len, 128 - this.bufferLength);
            System.arraycopy(data, off, this.buffer, this.bufferLength, n);
            this.bufferLength += n;
            off += n;
            len -= n;
        }
        return this;
    }

    Blake2b updateIntLe(final int value) {
        return this.update(new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)});
    }

    byte[] digest() {
        this.counter += this.bufferLength;
        Arrays.fill(this.buffer, this.bufferLength, 128, (byte) 0);
        this.compress(this.buffer, 0, true);
        final byte[] out = new byte[this.outLength];
        for (int i = 0; i < this.outLength; i++) {
            out[i] = (byte) (this.h[i >>> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    static byte[] hash(final int outLength, final byte[] data) {
        return new Blake2b(outLength).update(data).digest();
    }

    private void compress(final byte[] block, final int offset, final boolean last) {
        for (int i = 0; i < 16; i++) {
            this.m[i] = Argon2.readLongLe(block, offset + i * 8);
        }
        System.arraycopy(this.h, 0, this.v, 0, 8);
        System.arraycopy(IV, 0, this.v, 8, 8);
        this.v[12] ^= this.counter;
        // counter high word stays 0: inputs are far below 2^64 bytes
        if (last) {
            this.v[14] = ~this.v[14];
        }
        for (int round = 0; round < 12; round++) {
            final byte[] s = SIGMA[round % 10];
            this.g(0, 4, 8, 12, s[0], s[1]);
            this.g(1, 5, 9, 13, s[2], s[3]);
            this.g(2, 6, 10, 14, s[4], s[5]);
            this.g(3, 7, 11, 15, s[6], s[7]);
            this.g(0, 5, 10, 15, s[8], s[9]);
            this.g(1, 6, 11, 12, s[10], s[11]);
            this.g(2, 7, 8, 13, s[12], s[13]);
            this.g(3, 4, 9, 14, s[14], s[15]);
        }
        for (int i = 0; i < 8; i++) {
            this.h[i] ^= this.v[i] ^ this.v[i + 8];
        }
    }

    private void g(final int a, final int b, final int c, final int d, final int x, final int y) {
        final long[] w = this.v;
        w[a] = w[a] + w[b] + this.m[x];
        w[d] = Long.rotateRight(w[d] ^ w[a], 32);
        w[c] = w[c] + w[d];
        w[b] = Long.rotateRight(w[b] ^ w[c], 24);
        w[a] = w[a] + w[b] + this.m[y];
        w[d] = Long.rotateRight(w[d] ^ w[a], 16);
        w[c] = w[c] + w[d];
        w[b] = Long.rotateRight(w[b] ^ w[c], 63);
    }
}
