// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Spoils of war: percentages, rounding, splitting and behaviour without Skarbiec. */
class LootTest {

    /** In-memory economy (accounts start at 0). */
    static final class FakeEconomy implements Economy {
        final Map<String, Long> accounts = new HashMap<>();
        long burned;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public long balance(final String account) {
            return this.accounts.getOrDefault(account, 0L);
        }

        @Override
        public boolean deposit(final String account, final long amount, final String reason) {
            this.accounts.merge(account, amount, Long::sum);
            return true;
        }

        @Override
        public boolean withdraw(final String account, final long amount, final String reason) {
            if (amount <= 0 || this.balance(account) < amount) {
                return false;
            }
            this.accounts.merge(account, -amount, Long::sum);
            this.burned += amount;
            return true;
        }

        @Override
        public boolean transfer(final String from, final String to, final long amount, final String reason) {
            if (amount <= 0 || this.balance(from) < amount) {
                return false;
            }
            this.accounts.merge(from, -amount, Long::sum);
            this.accounts.merge(to, amount, Long::sum);
            return true;
        }

        @Override
        public String format(final long amount) {
            return amount + " md";
        }

        @Override
        public Optional<Long> parse(final String input) {
            return Optional.empty();
        }

        @Override
        public long countCoins(final Player player) {
            return 0;
        }

        @Override
        public boolean takeCoins(final Player player, final long amount) {
            return false;
        }
    }

    @Test
    void sharePercentagesAndRounding() {
        assertEquals(50, LootMath.share(1000, 0.05));
        assertEquals(99, LootMath.share(1999, 0.05), "rounded down");
        assertEquals(0, LootMath.share(19, 0.05), "under one copper: nothing");
        assertEquals(29, LootMath.share(100, 0.29), "no binary rounding error (0.29 * 100 is 28.99.. in double)");
        assertEquals(750, LootMath.share(1000, 0.75));
        assertEquals(712, LootMath.share(950, 0.75), "712.5 -> 712");
        assertEquals(1000, LootMath.share(1000, 1.5), "fraction clamped to 100 %");
        assertEquals(0, LootMath.share(1000, 0));
        assertEquals(0, LootMath.share(1000, -0.1));
        assertEquals(0, LootMath.share(0, 0.05));
        assertEquals(0, LootMath.share(-500, 0.05));
        assertEquals(0, LootMath.share(1000, Double.NaN));
        assertEquals(4_611_686_018_427_387_903L, LootMath.share(Long.MAX_VALUE, 0.5), "large balances stay exact");
    }

    @Test
    void splitAlwaysAddsUp() {
        assertArrayEquals(new long[] {80, 79, 79}, LootMath.split(238, 3));
        assertArrayEquals(new long[] {1, 1, 0, 0}, LootMath.split(2, 4));
        assertArrayEquals(new long[] {0, 0}, LootMath.split(0, 2));
        assertArrayEquals(new long[0], LootMath.split(100, 0));
        long sum = 0;
        for (final long v : LootMath.split(1_000_003, 7)) {
            sum += v;
        }
        assertEquals(1_000_003, sum);
    }

    @Test
    void conquestThenFallWithMembersRemainder() {
        final FakeEconomy eco = new FakeEconomy();
        eco.accounts.put("country:loser", 1000L);
        // last chunk: 5 % for the chunk, then 75 % of the rest, then the remainder to 3 former members
        assertEquals(50, Spoils.take(eco, "country:loser", "country:winner", 0.05, "chunk"));
        assertEquals(712, Spoils.take(eco, "country:loser", "country:winner", 0.75, "fall"));
        assertEquals(238, eco.balance("country:loser"));
        final long[] paid = Spoils.payOut(eco, "country:loser", List.of("player:a", "player:b", "player:c"), "share");
        assertArrayEquals(new long[] {80, 79, 79}, paid);
        assertEquals(0, eco.balance("country:loser"));
        assertEquals(762, eco.balance("country:winner"));
        assertEquals(80, eco.balance("player:a"));
        assertEquals(0, Spoils.burn(eco, "country:loser", "rest"), "nothing left to destroy");
    }

    @Test
    void fallWithDestroyedRemainder() {
        final FakeEconomy eco = new FakeEconomy();
        eco.accounts.put("country:loser", 401L);
        assertEquals(20, Spoils.take(eco, "country:loser", "country:winner", 0.05, "chunk"));
        assertEquals(285, Spoils.take(eco, "country:loser", "country:winner", 0.75, "fall")); // 381 * 0.75 = 285.75
        assertEquals(96, Spoils.burn(eco, "country:loser", "rest"));
        assertEquals(96, eco.burned);
        assertEquals(0, eco.balance("country:loser"));
        assertEquals(305, eco.balance("country:winner"));
    }

    @Test
    void consecutiveChunksTakeFromTheCurrentTreasury() {
        final FakeEconomy eco = new FakeEconomy();
        eco.accounts.put("d", 10_000L);
        assertEquals(500, Spoils.take(eco, "d", "a", 0.05, ""));
        assertEquals(475, Spoils.take(eco, "d", "a", 0.05, ""), "5 % of 9500, not of the original 10000");
        assertEquals(451, Spoils.take(eco, "d", "a", 0.05, ""));
    }

    @Test
    void withoutSkarbiecNothingHappens() {
        assertEquals(0, Spoils.take(Economy.NONE, "country:x", "country:y", 0.75, ""));
        assertArrayEquals(new long[] {0, 0}, Spoils.payOut(Economy.NONE, "country:x", List.of("player:a", "player:b"), ""));
        assertEquals(0, Spoils.burn(Economy.NONE, "country:x", ""));
    }

    @Test
    void emptyTreasuryGivesNothing() {
        final FakeEconomy eco = new FakeEconomy();
        assertEquals(0, Spoils.take(eco, "country:poor", "country:rich", 0.75, ""));
        assertArrayEquals(new long[] {0}, Spoils.payOut(eco, "country:poor", List.of("player:a"), ""));
        assertEquals(0, eco.balance("country:rich"));
    }
}
