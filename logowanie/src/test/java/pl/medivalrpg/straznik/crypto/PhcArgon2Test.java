// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhcArgon2Test {

    @Test
    void parsesNloginShape() {
        final String phc = PhcArgon2.hash(Argon2.Type.ARGON2ID, "Abcdef12", 64, 2, 1);
        final PhcArgon2.Parsed p = PhcArgon2.parse(phc).orElseThrow();
        assertEquals(Argon2.Type.ARGON2ID, p.type());
        assertEquals(0x13, p.version());
        assertEquals(64, p.memoryKiB());
        assertEquals(2, p.iterations());
        assertEquals(1, p.lanes());
        assertEquals(16, p.salt().length);
        assertEquals(32, p.hash().length);
        assertTrue(phc.startsWith("$argon2id$v=19$m=64,t=2,p=1$"));
    }

    @Test
    void rejectsGarbageAndHostileParameters() {
        assertTrue(PhcArgon2.parse(null).isEmpty());
        assertTrue(PhcArgon2.parse("").isEmpty());
        assertTrue(PhcArgon2.parse("$SHA$abc$def").isEmpty());
        assertTrue(PhcArgon2.parse("$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz12345").isEmpty());
        assertTrue(PhcArgon2.parse("$argon2id$v=19$m=99999999,t=5,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaA").isEmpty());
        assertTrue(PhcArgon2.parse("$argon2id$v=19$m=65536,t=500,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaA").isEmpty());
        assertTrue(PhcArgon2.parse("$argon2id$v=18$m=64,t=1,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaA").isEmpty());
        assertTrue(PhcArgon2.parse("$argon2id$v=19$m=64,t=1,p=1,x=3$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaA").isEmpty());
        assertTrue(PhcArgon2.parse("$argon2id$v=19$m=64,t=1,p=1$***$aGFzaA").isEmpty());
        assertFalse(PhcArgon2.verify("nonsense", "x"));
    }

    @Test
    void version10WithoutVField() {
        final byte[] salt = "0123456789abcdef".getBytes();
        final byte[] tag = Argon2.hash(Argon2.Type.ARGON2I, Argon2.VERSION_10, "pw".getBytes(), salt, 2, 64, 1, 32, null, null);
        final java.util.Base64.Encoder b = java.util.Base64.getEncoder().withoutPadding();
        final String phc = "$argon2i$m=64,t=2,p=1$" + b.encodeToString(salt) + "$" + b.encodeToString(tag);
        assertTrue(PhcArgon2.verify(phc, "pw"));
        assertFalse(PhcArgon2.verify(phc, "pW"));
    }

    @Test
    void saltIsRandomAndRehashRules() {
        final String a = PhcArgon2.hash(Argon2.Type.ARGON2ID, "same", 64, 1, 1);
        final String b = PhcArgon2.hash(Argon2.Type.ARGON2ID, "same", 64, 1, 1);
        assertNotEquals(a, b);
        assertTrue(PhcArgon2.verify(a, "same") && PhcArgon2.verify(b, "same"));
        assertFalse(PhcArgon2.needsRehash(a, 64, 1, 1));
        assertTrue(PhcArgon2.needsRehash(a, 128, 1, 1));
        assertTrue(PhcArgon2.needsRehash(a, 64, 2, 1));
        assertTrue(PhcArgon2.needsRehash("$argon2i$v=19$m=64,t=1,p=1$c2FsdHNhbHRzYWx0c2FsdA$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g", 64, 1, 1));
        final PasswordHasher h = new PasswordHasher(64, 1, 1, 1);
        assertTrue(h.needsRehash("$argon2id$v=19$m=32768,t=5,p=1$c2FsdHNhbHRzYWx0c2FsdA$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g"));
        final String own = h.hash("Tajne-Haslo-9");
        assertFalse(h.needsRehash(own));
        assertTrue(h.verify(own, "Tajne-Haslo-9"));
        assertFalse(h.verify(own, "tajne-haslo-9"));
    }

    @Test
    void unicodePasswordsAreUtf8() {
        final String phc = PhcArgon2.hash(Argon2.Type.ARGON2ID, "Zażółć-gęślą-jaźń", 64, 1, 1);
        assertTrue(PhcArgon2.verify(phc, "Zażółć-gęślą-jaźń"));
        assertFalse(PhcArgon2.verify(phc, "Zazolc-gesla-jazn"));
    }
}
