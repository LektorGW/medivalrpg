// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.crypto;

/**
 * Argon2 (RFC 9106, versions 0x10 and 0x13; types d, i, id), a direct port of the reference implementation
 * ({@code phc-winner-argon2}, {@code core.c}/{@code ref.c}). Pure Java, no dependencies, lanes computed sequentially.
 * Checked in the tests against Bouncy Castle and the RFC 9106 test vectors.
 */
public final class Argon2 {

    /** Argon2 type, with the id written into H0 and the name used in PHC strings. */
    public enum Type {
        ARGON2D(0, "argon2d"), ARGON2I(1, "argon2i"), ARGON2ID(2, "argon2id");

        final int id;
        public final String phcName;

        Type(final int id, final String phcName) {
            this.id = id;
            this.phcName = phcName;
        }

        public static Type byPhcName(final String name) {
            for (final Type t : values()) {
                if (t.phcName.equals(name)) {
                    return t;
                }
            }
            return null;
        }
    }

    public static final int VERSION_10 = 0x10;
    public static final int VERSION_13 = 0x13;

    private static final int BLOCK_LONGS = 128;
    private static final int BLOCK_BYTES = 1024;
    private static final int SYNC_POINTS = 4;
    /** Upper bound for memory (KiB) accepted from stored hashes: 1 GiB, protects the server from absurd strings. */
    public static final int MAX_MEMORY_KIB = 1 << 20;
    public static final int MAX_ITERATIONS = 64;
    public static final int MAX_LANES = 64;

    private Argon2() {
    }

    /**
     * Raw Argon2 tag.
     *
     * @param memoryKiB  m (KiB); rounded down to a multiple of 4·p like the reference implementation
     * @param secret     optional key K (may be {@code null})
     * @param associated optional associated data X (may be {@code null})
     */
    public static byte[] hash(final Type type, final int version, final byte[] password, final byte[] salt,
                              final int iterations, final int memoryKiB, final int lanes, final int tagLength,
                              final byte[] secret, final byte[] associated) {
        if (version != VERSION_10 && version != VERSION_13) {
            throw new IllegalArgumentException("Unsupported Argon2 version");
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS || lanes < 1 || lanes > MAX_LANES
            || memoryKiB < 8 * lanes || memoryKiB > MAX_MEMORY_KIB || tagLength < 4 || salt.length < 8) {
            throw new IllegalArgumentException("Argon2 parameters out of range");
        }
        final byte[] k = secret == null ? new byte[0] : secret;
        final byte[] x = associated == null ? new byte[0] : associated;

        final int segmentLength = memoryKiB / (lanes * SYNC_POINTS);
        final int laneLength = segmentLength * SYNC_POINTS;
        final int blocks = laneLength * lanes;

        final byte[] h0 = new Blake2b(64)
            .updateIntLe(lanes).updateIntLe(tagLength).updateIntLe(memoryKiB).updateIntLe(iterations)
            .updateIntLe(version).updateIntLe(type.id)
            .updateIntLe(password.length).update(password)
            .updateIntLe(salt.length).update(salt)
            .updateIntLe(k.length).update(k)
            .updateIntLe(x.length).update(x)
            .digest();

        final long[] memory = new long[blocks * BLOCK_LONGS];
        try {
            final byte[] seed = new byte[64 + 8];
            System.arraycopy(h0, 0, seed, 0, 64);
            for (int lane = 0; lane < lanes; lane++) {
                for (int j = 0; j < 2; j++) {
                    writeIntLe(seed, 64, j);
                    writeIntLe(seed, 68, lane);
                    final byte[] block = hPrime(BLOCK_BYTES, seed);
                    final int base = (lane * laneLength + j) * BLOCK_LONGS;
                    for (int i = 0; i < BLOCK_LONGS; i++) {
                        memory[base + i] = readLongLe(block, i * 8);
                    }
                }
            }
            final Filler filler = new Filler(type, version, memory, iterations, lanes, laneLength, segmentLength, blocks);
            for (int pass = 0; pass < iterations; pass++) {
                for (int slice = 0; slice < SYNC_POINTS; slice++) {
                    for (int lane = 0; lane < lanes; lane++) {
                        filler.fillSegment(pass, lane, slice);
                    }
                }
            }
            final long[] last = new long[BLOCK_LONGS];
            System.arraycopy(memory, (laneLength - 1) * BLOCK_LONGS, last, 0, BLOCK_LONGS);
            for (int lane = 1; lane < lanes; lane++) {
                final int base = (lane * laneLength + laneLength - 1) * BLOCK_LONGS;
                for (int i = 0; i < BLOCK_LONGS; i++) {
                    last[i] ^= memory[base + i];
                }
            }
            final byte[] lastBytes = new byte[BLOCK_BYTES];
            for (int i = 0; i < BLOCK_LONGS; i++) {
                writeLongLe(lastBytes, i * 8, last[i]);
            }
            return hPrime(tagLength, lastBytes);
        } finally {
            java.util.Arrays.fill(memory, 0L);
        }
    }

    /** Variable-length hash H' (RFC 9106 §3.3). */
    static byte[] hPrime(final int outLength, final byte[] input) {
        if (outLength <= 64) {
            return new Blake2b(outLength).updateIntLe(outLength).update(input).digest();
        }
        final byte[] out = new byte[outLength];
        final int r = (outLength + 31) / 32 - 2;
        byte[] v = new Blake2b(64).updateIntLe(outLength).update(input).digest();
        System.arraycopy(v, 0, out, 0, 32);
        int pos = 32;
        for (int i = 2; i <= r; i++) {
            v = Blake2b.hash(64, v);
            System.arraycopy(v, 0, out, pos, 32);
            pos += 32;
        }
        final byte[] tail = Blake2b.hash(outLength - 32 * r, v);
        System.arraycopy(tail, 0, out, pos, tail.length);
        return out;
    }

    /** Segment filling, compression function G and index computation (reference {@code ref.c}). */
    private static final class Filler {
        private final Type type;
        private final int version;
        private final long[] memory;
        private final int passes;
        private final int lanes;
        private final int laneLength;
        private final int segmentLength;
        private final int blocks;
        private final long[] r = new long[BLOCK_LONGS];
        private final long[] tmp = new long[BLOCK_LONGS];
        private final long[] zero = new long[BLOCK_LONGS];
        private final long[] input = new long[BLOCK_LONGS];
        private final long[] address = new long[BLOCK_LONGS];

        Filler(final Type type, final int version, final long[] memory, final int passes, final int lanes,
               final int laneLength, final int segmentLength, final int blocks) {
            this.type = type;
            this.version = version;
            this.memory = memory;
            this.passes = passes;
            this.lanes = lanes;
            this.laneLength = laneLength;
            this.segmentLength = segmentLength;
            this.blocks = blocks;
        }

        void fillSegment(final int pass, final int lane, final int slice) {
            final boolean independent = this.type == Type.ARGON2I
                || (this.type == Type.ARGON2ID && pass == 0 && slice < SYNC_POINTS / 2);
            if (independent) {
                java.util.Arrays.fill(this.input, 0L);
                this.input[0] = pass;
                this.input[1] = lane;
                this.input[2] = slice;
                this.input[3] = this.blocks;
                this.input[4] = this.passes;
                this.input[5] = this.type.id;
            }
            int start = 0;
            if (pass == 0 && slice == 0) {
                start = 2;
                if (independent) {
                    this.nextAddresses();
                }
            }
            int curr = lane * this.laneLength + slice * this.segmentLength + start;
            int prev = curr % this.laneLength == 0 ? curr + this.laneLength - 1 : curr - 1;
            for (int i = start; i < this.segmentLength; i++, curr++, prev++) {
                if (curr % this.laneLength == 1) {
                    prev = curr - 1;
                }
                final long pseudoRandom;
                if (independent) {
                    if (i % BLOCK_LONGS == 0) {
                        this.nextAddresses();
                    }
                    pseudoRandom = this.address[i % BLOCK_LONGS];
                } else {
                    pseudoRandom = this.memory[prev * BLOCK_LONGS];
                }
                int refLane = (int) Long.remainderUnsigned(pseudoRandom >>> 32, this.lanes);
                if (pass == 0 && slice == 0) {
                    refLane = lane;
                }
                final int refIndex = this.indexAlpha(pass, slice, i, pseudoRandom & 0xFFFFFFFFL, refLane == lane);
                final int ref = this.laneLength * refLane + refIndex;
                final boolean withXor = this.version != VERSION_10 && pass != 0;
                this.fillBlock(this.memory, prev * BLOCK_LONGS, this.memory, ref * BLOCK_LONGS,
                    this.memory, curr * BLOCK_LONGS, withXor);
            }
        }

        private int indexAlpha(final int pass, final int slice, final int index, final long pseudoRandom,
                               final boolean sameLane) {
            long area;
            if (pass == 0) {
                if (slice == 0) {
                    area = index - 1;
                } else if (sameLane) {
                    area = (long) slice * this.segmentLength + index - 1;
                } else {
                    area = (long) slice * this.segmentLength + (index == 0 ? -1 : 0);
                }
            } else {
                if (sameLane) {
                    area = this.laneLength - this.segmentLength + index - 1;
                } else {
                    area = this.laneLength - this.segmentLength + (index == 0 ? -1 : 0);
                }
            }
            long rel = (pseudoRandom * pseudoRandom) >>> 32;
            rel = area - 1 - ((area * rel) >>> 32);
            long startPosition = 0;
            if (pass != 0) {
                startPosition = slice == SYNC_POINTS - 1 ? 0 : (long) (slice + 1) * this.segmentLength;
            }
            return (int) ((startPosition + rel) % this.laneLength);
        }

        private void nextAddresses() {
            this.input[6]++;
            this.fillBlock(this.zero, 0, this.input, 0, this.address, 0, false);
            this.fillBlock(this.zero, 0, this.address, 0, this.address, 0, false);
        }

        /** next = G(prev, ref) (XOR next when {@code withXor}). Arrays may alias; offsets in longs. */
        private void fillBlock(final long[] prevArr, final int prevOff, final long[] refArr, final int refOff,
                               final long[] nextArr, final int nextOff, final boolean withXor) {
            final long[] rr = this.r;
            final long[] t = this.tmp;
            for (int i = 0; i < BLOCK_LONGS; i++) {
                rr[i] = refArr[refOff + i] ^ prevArr[prevOff + i];
            }
            if (withXor) {
                for (int i = 0; i < BLOCK_LONGS; i++) {
                    t[i] = rr[i] ^ nextArr[nextOff + i];
                }
            } else {
                System.arraycopy(rr, 0, t, 0, BLOCK_LONGS);
            }
            for (int i = 0; i < 8; i++) {
                final int b = 16 * i;
                round(rr, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                    b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15);
            }
            for (int i = 0; i < 8; i++) {
                final int b = 2 * i;
                round(rr, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                    b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113);
            }
            for (int i = 0; i < BLOCK_LONGS; i++) {
                nextArr[nextOff + i] = t[i] ^ rr[i];
            }
        }

        private static void round(final long[] v, final int v0, final int v1, final int v2, final int v3,
                                  final int v4, final int v5, final int v6, final int v7, final int v8,
                                  final int v9, final int v10, final int v11, final int v12, final int v13,
                                  final int v14, final int v15) {
            g(v, v0, v4, v8, v12);
            g(v, v1, v5, v9, v13);
            g(v, v2, v6, v10, v14);
            g(v, v3, v7, v11, v15);
            g(v, v0, v5, v10, v15);
            g(v, v1, v6, v11, v12);
            g(v, v2, v7, v8, v13);
            g(v, v3, v4, v9, v14);
        }

        private static void g(final long[] v, final int a, final int b, final int c, final int d) {
            v[a] = blaMka(v[a], v[b]);
            v[d] = Long.rotateRight(v[d] ^ v[a], 32);
            v[c] = blaMka(v[c], v[d]);
            v[b] = Long.rotateRight(v[b] ^ v[c], 24);
            v[a] = blaMka(v[a], v[b]);
            v[d] = Long.rotateRight(v[d] ^ v[a], 16);
            v[c] = blaMka(v[c], v[d]);
            v[b] = Long.rotateRight(v[b] ^ v[c], 63);
        }

        private static long blaMka(final long x, final long y) {
            return x + y + 2 * (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL);
        }
    }

    static long readLongLe(final byte[] b, final int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24
            | (b[off + 4] & 0xFFL) << 32 | (b[off + 5] & 0xFFL) << 40 | (b[off + 6] & 0xFFL) << 48
            | (b[off + 7] & 0xFFL) << 56;
    }

    private static void writeLongLe(final byte[] b, final int off, final long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (8 * i));
        }
    }

    private static void writeIntLe(final byte[] b, final int off, final int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
