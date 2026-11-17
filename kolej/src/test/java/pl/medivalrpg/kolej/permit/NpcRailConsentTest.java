// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.permit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.permit.NpcRailConsent.*;

class NpcRailConsentTest {
    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test void parsesTheArmiaContractAndRefusesAnythingElse() {
        assertEquals(Kind.ACCEPT, NpcRailConsent.parse("ACCEPT").kind());
        Answer fee = NpcRailConsent.parse("ACCEPT_FEE:750");
        assertEquals(Kind.ACCEPT_FEE, fee.kind()); assertEquals(750, fee.fee());
        Answer refuse = NpcRailConsent.parse("REFUSE:wojna z sąsiadem");
        assertEquals(Kind.REFUSE, refuse.kind()); assertEquals("wojna z sąsiadem", refuse.reason());
        for (String raw : new String[] {null, "", "accept", "ACCEPT_FEE:", "ACCEPT_FEE:-5", "ACCEPT_FEE:0", "ACCEPT_FEE:abc", "ACCEPT_FEE:999999999999", "MAYBE"})
            assertEquals(Kind.REFUSE, NpcRailConsent.parse(raw).kind(), String.valueOf(raw));
        assertEquals("odmowa władcy", NpcRailConsent.parse("REFUSE").reason());
    }

    @Test void jointProjectRuleOnlyForAnApplicantCountry() {
        Answer accept = NpcRailConsent.parse("ACCEPT");
        assertSame(accept, NpcRailConsent.decide(accept, false, false), "private carrier: no country relation needed");
        assertEquals(Kind.REFUSE, NpcRailConsent.decide(accept, true, false).kind());
        assertSame(accept, NpcRailConsent.decide(accept, true, true));
        Answer refuse = NpcRailConsent.parse("REFUSE:nie");
        assertSame(refuse, NpcRailConsent.decide(refuse, true, true), "joint project never overrides a refusal");
    }

    @Test void cooldownAndLimit() {
        assertEquals(1_000 + 60 * 60_000L, NpcRailConsent.cooldownUntil(1_000, 60));
        assertEquals(1_000, NpcRailConsent.cooldownUntil(1_000, 0));
        assertTrue(NpcRailConsent.withinLimit(856184, 600000, 1000, 255184));
        assertFalse(NpcRailConsent.withinLimit(856184, 600000, 1000, 255185));
        assertFalse(NpcRailConsent.withinLimit(856184, 0, 0, 0));
        assertFalse(NpcRailConsent.withinLimit(0, 0, 0, 1));
    }

    @Test void ledgerIsIdempotentAndBlocksOnUnresolvedIntent() {
        Ledger ledger = new Ledger();
        Entry intent = ledger.intent(HOST, "kolej:budzet_0123456789abcdef", 500, 1);
        assertTrue(ledger.blocked(HOST));
        assertThrows(IllegalStateException.class, () -> ledger.intent(HOST, "kolej:budzet_0123456789abcdef", 500, 2), "no second intent");
        assertEquals(0, ledger.paidFrom("kolej:budzet_0123456789abcdef"));
        ledger.settle(intent.key, State.PAID);
        assertFalse(ledger.blocked(HOST));
        assertEquals(500, ledger.paidFrom("kolej:budzet_0123456789abcdef"));
        assertThrows(IllegalStateException.class, () -> ledger.intent(HOST, "kolej:budzet_0123456789abcdef", 500, 3), "paid key is never charged again");
        assertThrows(IllegalStateException.class, () -> ledger.settle(intent.key, State.FAILED), "PAID is final");
        Entry other = ledger.intent(HOST, "kolej:przewoznik", 200, 4);
        ledger.settle(other.key, State.FAILED);
        assertEquals(0, ledger.paidFrom("kolej:przewoznik"));
        assertDoesNotThrow(() -> ledger.intent(HOST, "kolej:przewoznik", 200, 5), "a failed transfer may be retried");
        ledger.validate();
    }

    @Test void validationRejectsTamperedEntries() {
        Ledger ledger = new Ledger();
        ledger.entries.add(new Entry("zgoda:x", HOST, "kolej:przewoznik", 1, State.PAID, 0));
        assertThrows(IllegalArgumentException.class, ledger::validate);
        Ledger negative = new Ledger();
        negative.entries.add(new Entry(NpcRailConsent.key(HOST, "kolej:przewoznik"), HOST, "kolej:przewoznik", 0, State.PAID, 0));
        assertThrows(IllegalArgumentException.class, negative::validate);
    }
}
