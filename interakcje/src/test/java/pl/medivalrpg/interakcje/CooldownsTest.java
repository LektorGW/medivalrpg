// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownsTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void readyWhenNeverUsed() {
        Cooldowns c = new Cooldowns();
        assertEquals(0, c.remaining(a, "kiss", 1000));
        assertEquals(0, c.globalRemaining(a, 1500, 1000));
    }

    @Test
    void perInteractionCooldownCountsDown() {
        Cooldowns c = new Cooldowns();
        c.mark(a, "kiss", 5000, 10_000);
        assertEquals(5000, c.remaining(a, "kiss", 10_000));
        assertEquals(1000, c.remaining(a, "kiss", 14_000));
        assertEquals(0, c.remaining(a, "kiss", 15_000));
        assertEquals(0, c.remaining(a, "slap", 10_000), "other interaction is independent");
        assertEquals(0, c.remaining(b, "kiss", 10_000), "other player is independent");
    }

    @Test
    void globalGapAppliesToAnyInteraction() {
        Cooldowns c = new Cooldowns();
        c.mark(a, "pat", 0, 10_000);
        assertEquals(1500, c.globalRemaining(a, 1500, 10_000));
        assertEquals(500, c.globalRemaining(a, 1500, 11_000));
        assertEquals(0, c.globalRemaining(a, 1500, 11_500));
        assertEquals(0, c.remaining(a, "pat", 10_000), "zero cooldown is not stored");
    }

    @Test
    void purgeDropsExpiredOnly() {
        Cooldowns c = new Cooldowns();
        c.mark(a, "kiss", 5000, 0);
        c.mark(a, "slap", 10_000, 0);
        c.purge(6000, 1500);
        assertEquals(0, c.remaining(a, "kiss", 6000));
        assertEquals(4000, c.remaining(a, "slap", 6000));
    }

    @Test
    void secondsLeftRoundsUp() {
        assertEquals(1, Cooldowns.secondsLeft(1));
        assertEquals(1, Cooldowns.secondsLeft(1000));
        assertEquals(2, Cooldowns.secondsLeft(1001));
        assertEquals(1, Cooldowns.secondsLeft(0));
    }
}
