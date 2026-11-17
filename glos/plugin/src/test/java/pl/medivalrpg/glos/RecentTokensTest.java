// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecentTokensTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void reusesTokenOnlyWithinWindowAndPerPlayer() {
        RecentTokens rt = new RecentTokens(10_000);
        assertNull(rt.reusable(a, 1_000));
        rt.issued(a, "tokA", 1_000);
        assertEquals("tokA", rt.reusable(a, 1_000));
        assertEquals("tokA", rt.reusable(a, 10_999));
        assertNull(rt.reusable(b, 2_000), "inny gracz");
        assertNull(rt.reusable(a, 11_000), "okno minęło");
        assertNull(rt.reusable(a, 2_000), "wygasły wpis usunięty");
    }

    @Test
    void forgetAfterKickAndClearAfterDisconnect() {
        RecentTokens rt = new RecentTokens(10_000);
        rt.issued(a, "tokA", 0);
        rt.issued(b, "tokB", 0);
        rt.forget(a);
        assertNull(rt.reusable(a, 1), "kick unieważnia token");
        assertEquals("tokB", rt.reusable(b, 1));
        rt.clear();
        assertNull(rt.reusable(b, 1));
    }

    @Test
    void clockGoingBackwardsDoesNotReuse() {
        RecentTokens rt = new RecentTokens(10_000);
        rt.issued(a, "tokA", 5_000);
        assertNull(rt.reusable(a, 4_000));
    }
}
