// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;

/** Our Argon2 against RFC 9106 vectors and Bouncy Castle (independent implementation). */
class Argon2Test {

    private static byte[] filled(final int n, final int value) {
        final byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private static byte[] rfc(final Argon2.Type type) {
        return Argon2.hash(type, Argon2.VERSION_13, filled(32, 1), filled(16, 2), 3, 32, 4, 32, filled(8, 3),
            filled(12, 4));
    }

    @Test
    void rfc9106Vectors() {
        final HexFormat hex = HexFormat.ofDelimiter(" ");
        assertArrayEquals(hex.parseHex("51 2b 39 1b 6f 11 62 97 53 71 d3 09 19 73 42 94 f8 68 e3 be 39 84 f3 c1 a1 3a 4d b9 fa be 4a cb"),
            rfc(Argon2.Type.ARGON2D));
        assertArrayEquals(hex.parseHex("c8 14 d9 d1 dc 7f 37 aa 13 f0 d7 7f 24 94 bd a1 c8 de 6b 01 6d d3 88 d2 99 52 a4 c4 67 2b 6c e8"),
            rfc(Argon2.Type.ARGON2I));
        assertArrayEquals(hex.parseHex("0d 64 0d f5 8d 78 76 6c 08 c0 37 a3 4a 8b 53 c9 d0 1e f0 45 2d 75 b6 5e b5 25 20 e9 6b 01 e6 59"),
            rfc(Argon2.Type.ARGON2ID));
    }

    private static byte[] bouncy(final Argon2.Type type, final int version, final byte[] pw, final byte[] salt, final int t,
                                 final int m, final int p, final int len, final byte[] secret, final byte[] ad) {
        final int bcType = switch (type) {
            case ARGON2D -> Argon2Parameters.ARGON2_d;
            case ARGON2I -> Argon2Parameters.ARGON2_i;
            case ARGON2ID -> Argon2Parameters.ARGON2_id;
        };
        final Argon2Parameters.Builder b = new Argon2Parameters.Builder(bcType)
            .withVersion(version).withIterations(t).withMemoryAsKB(m).withParallelism(p).withSalt(salt);
        if (secret != null) {
            b.withSecret(secret);
        }
        if (ad != null) {
            b.withAdditional(ad);
        }
        final Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(b.build());
        final byte[] out = new byte[len];
        gen.generateBytes(pw, out);
        return out;
    }

    @Test
    void matchesBouncyCastleOnRandomInputs() {
        final Random rnd = new Random(20261029L);
        for (int i = 0; i < 120; i++) {
            final Argon2.Type type = Argon2.Type.values()[i % 3];
            final int version = (i / 3) % 2 == 0 ? Argon2.VERSION_13 : Argon2.VERSION_10;
            final int p = 1 + rnd.nextInt(4);
            final int m = 8 * p + rnd.nextInt(600);
            final int t = 1 + rnd.nextInt(4);
            final int len = 4 + rnd.nextInt(150);
            final byte[] pw = new byte[rnd.nextInt(80)];
            rnd.nextBytes(pw);
            final byte[] salt = new byte[8 + rnd.nextInt(40)];
            rnd.nextBytes(salt);
            final byte[] secret = rnd.nextBoolean() ? null : new byte[1 + rnd.nextInt(40)];
            if (secret != null) {
                rnd.nextBytes(secret);
            }
            final byte[] ad = rnd.nextBoolean() ? null : new byte[1 + rnd.nextInt(40)];
            if (ad != null) {
                rnd.nextBytes(ad);
            }
            assertArrayEquals(bouncy(type, version, pw, salt, t, m, p, len, secret, ad),
                Argon2.hash(type, version, pw, salt, t, m, p, len, secret, ad),
                "case " + i + " " + type + " v" + version + " m=" + m + " t=" + t + " p=" + p + " len=" + len);
        }
    }

    @Test
    void addressBlockRefreshOnLargerSegments() {
        // segment length > 128 exercises the data-independent address block refresh (Argon2i / first half of id)
        final byte[] pw = "Zażółć gęślą jaźń 123!".getBytes(StandardCharsets.UTF_8);
        final byte[] salt = filled(16, 7);
        for (final Argon2.Type type : Argon2.Type.values()) {
            assertArrayEquals(bouncy(type, Argon2.VERSION_13, pw, salt, 2, 2048, 2, 32, null, null),
                Argon2.hash(type, Argon2.VERSION_13, pw, salt, 2, 2048, 2, 32, null, null), type.name());
        }
    }

    /** The exact shape of nLogin hashes (argon2-jvm defaults: 16-byte salt, 32-byte tag, UTF-8; m=32 MiB, t=5, p=1). */
    @Test
    void nloginShapedHashFromBouncyCastleVerifies() {
        final String password = "Synt3tyczne-Hasło!";
        final byte[] salt = new byte[16];
        new Random(7).nextBytes(salt);
        final byte[] tag = bouncy(Argon2.Type.ARGON2ID, Argon2.VERSION_13, password.getBytes(StandardCharsets.UTF_8), salt,
            5, 32768, 1, 32, null, null);
        final Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        final String phc = "$argon2id$v=19$m=32768,t=5,p=1$" + b64.encodeToString(salt) + "$" + b64.encodeToString(tag);
        assertEquals(22, b64.encodeToString(salt).length());
        assertEquals(43, b64.encodeToString(tag).length());
        assertTrue(PhcArgon2.verify(phc, password));
        assertFalse(PhcArgon2.verify(phc, password + "x"));
        assertFalse(PhcArgon2.verify(phc, "synt3tyczne-hasło!"));
        assertEquals(phc, PhcArgon2.encode(Argon2.Type.ARGON2ID, password, salt, 32768, 5, 1, 32));
    }
}
