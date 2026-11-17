// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class ActivityAndCacheTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void afkAfterThresholdAndBackOnActivity() {
        final ActivityTracker t = new ActivityTracker();
        t.touch(A, 1_000);
        assertFalse(t.isAfk(A, 300_999, 300_000));
        assertTrue(t.isAfk(A, 301_000, 300_000));
        t.touch(A, 302_000);
        assertFalse(t.isAfk(A, 302_500, 300_000));
        assertFalse(t.isAfk(B, 10_000_000, 300_000), "unknown player is never AFK");
        assertFalse(t.isAfk(A, 10_000_000, 0), "0 = detection off");
        assertEquals(500, t.idleMillis(A, 302_500));
        t.remove(A);
        assertEquals(-1, t.idleMillis(A, 302_500));
        assertEquals(0, t.size());
    }

    @Test
    void ttlCacheReloadsOnlyAfterExpiry() {
        final TtlCache<UUID, Long> cache = new TtlCache<>(5_000);
        final AtomicInteger loads = new AtomicInteger();
        assertEquals(1L, cache.get(A, 0, () -> (long) loads.incrementAndGet()));
        assertEquals(1L, cache.get(A, 4_999, () -> (long) loads.incrementAndGet()));
        assertEquals(2L, cache.get(A, 5_000, () -> (long) loads.incrementAndGet()));
        cache.invalidate(A);
        assertEquals(3L, cache.get(A, 5_001, () -> (long) loads.incrementAndGet()));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void changeTrackerSendsOnlyChanges() {
        final ChangeTracker<Component> sent = new ChangeTracker<>();
        final Component name = Component.text("[Aldoria] ", NamedTextColor.GOLD).append(Component.text("Gracz"));
        assertTrue(sent.update(A, name), "first time: send");
        assertFalse(sent.update(A, Component.text("[Aldoria] ", NamedTextColor.GOLD).append(Component.text("Gracz"))),
            "equal content: no packet");
        assertTrue(sent.update(A, Component.text("[AFK] ").append(name)), "changed: send");
        assertTrue(sent.update(B, name), "per player");
        sent.forget(A);
        assertTrue(sent.update(A, name), "after quit/rejoin: send again");
        assertEquals(2, sent.size());
    }

    private record C(UUID id, String name) {
    }

    @Test
    void ordersCountriesAlphabeticallyAndRanksInside() {
        final UUID x = UUID.randomUUID();
        final UUID y = UUID.randomUUID();
        final UUID z = UUID.randomUUID();
        final Map<UUID, Integer> bases = TablistOrder.groupBases(
            List.of(new C(z, "zamek"), new C(x, "Aldoria"), new C(y, "bretania")), C::id, C::name);
        assertEquals(40, bases.get(x));
        assertEquals(30, bases.get(y));
        assertEquals(20, bases.get(z));
        // ruler (3) of a country stays above citizens (1) but below the previous country
        assertEquals(43, TablistOrder.order(bases, x, 3));
        assertEquals(41, TablistOrder.order(bases, x, 1));
        assertTrue(TablistOrder.order(bases, y, 3) < TablistOrder.order(bases, x, 1));
        assertEquals(0, TablistOrder.order(bases, null, 3), "no country: last");
        assertEquals(TablistOrder.STEP + 1, TablistOrder.order(bases, UUID.randomUUID(), 1), "unknown country");
    }
}
