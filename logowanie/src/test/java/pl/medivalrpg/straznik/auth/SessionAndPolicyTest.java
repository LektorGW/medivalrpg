// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionAndPolicyTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void sessionSameAddressWithinTimeIsValidOnce() {
        final SessionStore s = new SessionStore();
        s.open(this.id, "10.0.0.1", 0, 600_000);
        assertTrue(s.valid(this.id, "10.0.0.1", 599_999));
        assertTrue(s.consume(this.id, "10.0.0.1", 599_999));
        assertFalse(s.consume(this.id, "10.0.0.1", 600_000), "one use");
    }

    @Test
    void sessionOtherAddressOrLateIsInvalid() {
        final SessionStore s = new SessionStore();
        s.open(this.id, "10.0.0.1", 0, 600_000);
        assertFalse(s.valid(this.id, "10.0.0.2", 1));
        assertFalse(s.valid(this.id, null, 1));
        assertFalse(s.valid(this.id, "10.0.0.1", 600_000));
        assertFalse(s.consume(this.id, "10.0.0.2", 1));
        assertFalse(s.valid(this.id, "10.0.0.1", 2), "a wrong address consumes the session");
    }

    @Test
    void sessionInvalidateAndDisabled() {
        final SessionStore s = new SessionStore();
        s.open(this.id, "10.0.0.1", 0, 600_000);
        s.invalidate(this.id);
        assertFalse(s.valid(this.id, "10.0.0.1", 1));
        s.open(this.id, "10.0.0.1", 0, 0);
        assertFalse(s.valid(this.id, "10.0.0.1", 0), "zero duration = sessions off");
        s.open(this.id, "10.0.0.1", 0, 100);
        s.cleanup(100);
        assertFalse(s.valid(this.id, "10.0.0.1", 50));
    }

    @Test
    void passwordRules() {
        final PasswordPolicy p = new PasswordPolicy(6, 64, true, Set.of("haslo123", "qwerty", "minecraft"));
        assertEquals(PasswordPolicy.TOO_SHORT, p.check("Gracz", "abc12"));
        assertEquals(PasswordPolicy.TOO_LONG, p.check("Gracz", "a".repeat(30) + "b".repeat(35)));
        assertEquals(PasswordPolicy.WHITESPACE, p.check("Gracz", "abc def1"));
        assertEquals(PasswordPolicy.WEAK, p.check("Gracz", "HASLO123"));
        assertEquals(PasswordPolicy.WEAK, p.check("Gracz", "aaaaaaa"));
        assertEquals(PasswordPolicy.WEAK, p.check("Gracz", "ababab"));
        assertEquals(PasswordPolicy.WEAK, p.check("Gracz", "123456789"));
        assertEquals(PasswordPolicy.WEAK, p.check("Gracz", "fedcba"));
        assertEquals(PasswordPolicy.CONTAINS_NICK, p.check("Gracz", "xGRACZ77"));
        assertNull(p.check("Gracz", "Zamek-Na-Wzgorzu7"));
        assertNull(p.check("Gracz", "żółw12"), "6 code points, Polish letters fine");
    }
}
