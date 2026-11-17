// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Argon2 hashes in the PHC string format used by libargon2 / argon2-jvm and therefore by nLogin:
 * {@code $argon2id$v=19$m=32768,t=5,p=1$<salt>$<hash>} — salt and hash in standard Base64 without padding, the
 * {@code v=} field missing for version 0x10. The password is hashed as its UTF-8 bytes, without secret key or
 * associated data. Thread-safe.
 */
public final class PhcArgon2 {

    /** Parsed PHC string. */
    public record Parsed(Argon2.Type type, int version, int memoryKiB, int iterations, int lanes, byte[] salt,
                         byte[] hash) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PhcArgon2() {
    }

    /** Parses a PHC Argon2 string; empty when it is not one (or parameters are outside the safe range). */
    public static Optional<Parsed> parse(final String encoded) {
        if (encoded == null || encoded.length() > 512 || !encoded.startsWith("$")) {
            return Optional.empty();
        }
        final String[] parts = encoded.split("\\$", -1);
        // ["", type, (v=..), params, salt, hash]
        if (parts.length != 5 && parts.length != 6) {
            return Optional.empty();
        }
        final Argon2.Type type = Argon2.Type.byPhcName(parts[1]);
        if (type == null) {
            return Optional.empty();
        }
        int idx = 2;
        int version = Argon2.VERSION_10;
        if (parts.length == 6) {
            if (!parts[2].startsWith("v=")) {
                return Optional.empty();
            }
            final Integer v = parseInt(parts[2].substring(2));
            if (v == null || (v != Argon2.VERSION_10 && v != Argon2.VERSION_13)) {
                return Optional.empty();
            }
            version = v;
            idx = 3;
        }
        Integer m = null;
        Integer t = null;
        Integer p = null;
        for (final String kv : parts[idx].split(",", -1)) {
            final int eq = kv.indexOf('=');
            if (eq <= 0) {
                return Optional.empty();
            }
            final Integer value = parseInt(kv.substring(eq + 1));
            if (value == null) {
                return Optional.empty();
            }
            switch (kv.substring(0, eq)) {
                case "m" -> m = value;
                case "t" -> t = value;
                case "p" -> p = value;
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (m == null || t == null || p == null || t < 1 || t > Argon2.MAX_ITERATIONS || p < 1 || p > Argon2.MAX_LANES
            || m < 8 * p || m > Argon2.MAX_MEMORY_KIB) {
            return Optional.empty();
        }
        final byte[] salt;
        final byte[] hash;
        try {
            salt = B64D.decode(parts[idx + 1]);
            hash = B64D.decode(parts[idx + 2]);
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
        if (salt.length < 8 || salt.length > 64 || hash.length < 4 || hash.length > 128) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(type, version, m, t, p, salt, hash));
    }

    private static Integer parseInt(final String s) {
        if (s.isEmpty() || s.length() > 9) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return null;
            }
        }
        return Integer.parseInt(s);
    }

    /** Constant-time check of {@code password} against a PHC string; {@code false} for anything unparseable. */
    public static boolean verify(final String encoded, final String password) {
        final Optional<Parsed> parsed = parse(encoded);
        if (parsed.isEmpty()) {
            return false;
        }
        final Parsed p = parsed.get();
        final byte[] computed = Argon2.hash(p.type(), p.version(), password.getBytes(StandardCharsets.UTF_8), p.salt(),
            p.iterations(), p.memoryKiB(), p.lanes(), p.hash().length, null, null);
        return MessageDigest.isEqual(computed, p.hash());
    }

    /** New Argon2 (version 0x13) PHC string with a random 16-byte salt and a 32-byte tag. */
    public static String hash(final Argon2.Type type, final String password, final int memoryKiB, final int iterations,
                              final int lanes) {
        final byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encode(type, password, salt, memoryKiB, iterations, lanes, 32);
    }

    /** Deterministic encoding (tests, compatibility checks). */
    public static String encode(final Argon2.Type type, final String password, final byte[] salt, final int memoryKiB,
                                final int iterations, final int lanes, final int tagLength) {
        final byte[] tag = Argon2.hash(type, Argon2.VERSION_13, password.getBytes(StandardCharsets.UTF_8), salt,
            iterations, memoryKiB, lanes, tagLength, null, null);
        return "$" + type.phcName + "$v=" + Argon2.VERSION_13 + "$m=" + memoryKiB + ",t=" + iterations + ",p=" + lanes
            + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(tag);
    }

    /** {@code true} when the string is not Argon2id v19 with exactly these parameters. */
    public static boolean needsRehash(final String encoded, final int memoryKiB, final int iterations, final int lanes) {
        final Optional<Parsed> parsed = parse(encoded);
        if (parsed.isEmpty()) {
            return true;
        }
        final Parsed p = parsed.get();
        return p.type() != Argon2.Type.ARGON2ID || p.version() != Argon2.VERSION_13 || p.memoryKiB() != memoryKiB
            || p.iterations() != iterations || p.lanes() != lanes || p.salt().length < 16 || p.hash().length < 32;
    }
}
