// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WagesTest {

    private static UUID id(final int n) {
        return new UUID(0, n);
    }

    @Test
    void accountNameIsAValidSkarbiecAccount() {
        final String acc = Wages.account(UUID.fromString("9d1fe36e-4852-4d18-9b2a-a89146f43e66"));
        assertTrue(acc.matches("[A-Za-z0-9_\\-]{1,24}:[A-Za-z0-9_\\-.:]{1,64}"), acc);
    }

    @Test
    void splitIsExactAndProportional() {
        final Map<UUID, Long> w = new LinkedHashMap<>();
        w.put(id(1), 3L);
        w.put(id(2), 3L);
        w.put(id(3), 1L);
        w.put(id(4), 0L);
        final Map<UUID, Long> s = Wages.split(10, w);
        assertEquals(10, Wages.sum(s));
        assertEquals(0L, s.get(id(4)));
        assertTrue(s.get(id(1)) >= s.get(id(3)));
        final Random r = new Random(5);
        for (int i = 0; i < 2000; i++) {
            final Map<UUID, Long> ws = new LinkedHashMap<>();
            for (int k = 0; k < 1 + r.nextInt(12); k++) {
                ws.put(id(k), (long) r.nextInt(50));
            }
            final long amount = r.nextInt(1000);
            final Map<UUID, Long> out = Wages.split(amount, ws);
            assertEquals(Wages.sum(ws) == 0 ? 0 : amount, Wages.sum(out));
            out.values().forEach(v -> assertTrue(v >= 0));
        }
    }

    @Test
    void wagesNeverExceedTheTreasury() {
        final Map<UUID, Long> due = Map.of(id(1), 3L, id(2), 2L, id(3), 2L);
        assertEquals(7, Wages.sum(Wages.pay(due, 100)));
        assertEquals(4, Wages.sum(Wages.pay(due, 4)));
        assertEquals(0, Wages.sum(Wages.pay(due, 0)));
        assertEquals(0, Wages.sum(Wages.pay(due, -50)), "an empty / negative treasury pays nothing");
    }

    @Test
    void mealsNeverTakeMoreThanThePayerHas() {
        final Map<UUID, Long> book = Map.of(id(1), 5L, id(2), 0L, id(3), 1L);
        final Map<UUID, UUID> payer = new LinkedHashMap<>();
        payer.put(id(1), id(1));
        payer.put(id(2), id(2));
        payer.put(id(3), id(3));
        payer.put(id(4), id(1)); // child of 1
        payer.put(id(5), id(3)); // child of 3
        final Map<UUID, Long> m = Wages.meals(book, payer, 2, 1.0);
        assertEquals(4L, m.get(id(1)), "parent pays for itself and its child");
        assertEquals(null, m.get(id(2)), "no savings, no payment (food was eaten anyway)");
        assertEquals(1L, m.get(id(3)), "only what it has");
        assertEquals(0, Wages.sum(Wages.meals(book, payer, 2, 0.0)), "nothing eaten, nothing paid");
        assertEquals(3L, Wages.meals(book, Map.of(id(1), id(1)), 6, 0.5).get(id(1)), "half fed pays half");
    }

    @Test
    void reconcileMatchesTheRealBalance() {
        final Map<UUID, Long> book = new LinkedHashMap<>(Map.of(id(1), 10L, id(2), 20L, id(9), 50L));
        final List<UUID> members = List.of(id(1), id(2), id(3));
        final Map<UUID, Long> up = Wages.reconcile(book, members, 100);
        assertEquals(100, Wages.sum(up));
        assertEquals(3, up.size(), "non-members dropped, new members included");
        final Map<UUID, Long> down = Wages.reconcile(book, members, 15);
        assertEquals(15, Wages.sum(down));
        down.values().forEach(v -> assertTrue(v >= 0));
        assertTrue(Wages.reconcile(book, List.of(), 100).isEmpty());
    }

    @Test
    void debitIsProportionalAndRefusesOverdraft() {
        final Map<UUID, Long> book = new LinkedHashMap<>(Map.of(id(1), 600L, id(2), 300L, id(3), 100L));
        final Map<UUID, Long> after = Wages.debit(book, 500);
        assertEquals(500, Wages.sum(after));
        assertEquals(300L, after.get(id(1)));
        assertNull(Wages.debit(book, 1001));
    }

    /**
     * A closed economy: a country treasury, the citizens' account and the world payments pool. Many days of wages,
     * meals and huts in random order: the sum of all balances never changes and nothing goes below zero; the share
     * book always equals the citizens' account.
     */
    @Test
    void noMoneyIsEverCreatedOrDestroyed() {
        final Random r = new Random(2026);
        final Map<String, Long> ledger = new HashMap<>();
        ledger.put("country", 5_000L);
        ledger.put("citizens", 0L);
        ledger.put("world:payments", 1_000_000L);
        final long total = ledger.values().stream().mapToLong(Long::longValue).sum();
        Map<UUID, Long> book = new LinkedHashMap<>();
        final List<UUID> members = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            members.add(id(i));
        }
        for (int day = 0; day < 400; day++) {
            if (r.nextInt(10) == 0) {
                members.remove(r.nextInt(members.size()));          // somebody left
                members.add(id(1000 + day));                         // somebody arrived
            }
            book = Wages.reconcile(book, members, ledger.get("citizens"));
            final Map<UUID, Long> due = new LinkedHashMap<>();
            for (final UUID m : members) {
                if (r.nextInt(4) > 0) {
                    due.put(m, 1L + r.nextInt(3));
                }
            }
            final Map<UUID, Long> paid = Wages.pay(due, ledger.get("country"));
            transfer(ledger, "country", "citizens", Wages.sum(paid));
            book = Wages.credit(book, paid);
            final Map<UUID, UUID> payer = new LinkedHashMap<>();
            members.forEach(m -> payer.put(m, m));
            final Map<UUID, Long> meals = Wages.meals(book, payer, 1, r.nextDouble());
            transfer(ledger, "citizens", "country", Wages.sum(meals));
            final Map<UUID, Long> afterMeals = new LinkedHashMap<>(book);
            meals.forEach((k, v) -> afterMeals.put(k, afterMeals.get(k) - v));
            book = afterMeals;
            if (Wages.sum(book) >= 1500 && r.nextBoolean()) {        // a hut paid from the savings
                transfer(ledger, "citizens", "world:payments", 1500);
                book = Wages.debit(book, 1500);
            }
            if (r.nextInt(20) == 0) {                                 // the treasury builds something
                transfer(ledger, "country", "world:payments", Math.min(ledger.get("country"), 2000));
            }
            assertEquals(total, ledger.values().stream().mapToLong(Long::longValue).sum(), "day " + day);
            ledger.values().forEach(v -> assertTrue(v >= 0));
            assertEquals(ledger.get("citizens").longValue(), Wages.sum(book), "book = account on day " + day);
            book.values().forEach(v -> assertTrue(v >= 0));
        }
    }

    @Test
    void incomeCoversTaxAndMeals() {
        assertEquals(7, Wages.income(5, 1.1, 1, 0), "ceil(5 x 1.1) + one meal");
        assertEquals(5, Wages.income(4, 1.0, 1, 0), "unemployed: no surplus but never below tax + meal");
        assertEquals(9, Wages.income(5, 1.1, 1, 2), "two children eat from the parent's income");
        assertEquals(0, Wages.income(0, 1.1, 0, 0));
        final Map<UUID, Long> book = Map.of(id(1), 10L, id(2), 3L);
        final Map<UUID, Long> shares = Map.of(id(1), 5L, id(2), 5L, id(3), 5L);
        final Map<UUID, Long> paid = Wages.collect(book, shares);
        assertEquals(5L, paid.get(id(1)));
        assertEquals(3L, paid.get(id(2)), "never more than the savings");
        assertEquals(null, paid.get(id(3)));
    }

    /**
     * Example days (report): a farmer (multiplier 1.5) and a miner (mason 1.8), tax 3 md x multiplier, income factor
     * 1.1, meal 1 md, wages field 2 / mine 3. Ten of each in a country: the savings grow every day.
     */
    @Test
    void exampleFarmerAndMinerDays() {
        final Map<UUID, Long> weights = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            weights.put(id(i), 4500L);
            weights.put(id(100 + i), 5400L);
        }
        final long tax = Math.round(10 * 4.5 + 10 * 5.4);
        final Map<UUID, Long> shares = Wages.split(tax, weights);
        assertEquals(tax, Wages.sum(shares));
        long farmerNet = 0;
        long minerNet = 0;
        for (int i = 0; i < 10; i++) {
            final long fs = shares.get(id(i));
            final long ms = shares.get(id(100 + i));
            farmerNet += Wages.income(fs, 1.1, 1, 0) + 2 - 1 - fs;
            minerNet += Wages.income(ms, 1.1, 1, 0) + 3 - 1 - ms;
        }
        assertTrue(farmerNet / 10.0 >= 2.0 && farmerNet / 10.0 <= 3.5, "farmer saves ~2.5 md a day: " + farmerNet / 10.0);
        assertTrue(minerNet / 10.0 >= 3.0 && minerNet / 10.0 <= 4.5, "miner saves ~3.6 md a day: " + minerNet / 10.0);
    }

    /**
     * Variant A over a year: households pool -> income -> meals and tax to the treasury -> wages back. The world's total
     * never changes, the book always equals the citizens' account, a fed town pays its whole tax.
     */
    @Test
    void taxFromCitizensKeepsTheWorldClosed() {
        final Random r = new Random(29);
        final Map<String, Long> ledger = new HashMap<>(Map.of("world:households", 200_000L, "country", 1_000L, "citizens", 0L));
        final long total = ledger.values().stream().mapToLong(Long::longValue).sum();
        Map<UUID, Long> book = new LinkedHashMap<>();
        final List<UUID> people = new ArrayList<>();
        final Map<UUID, Long> weights = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            people.add(id(i));
            weights.put(id(i), 3000L * (1 + r.nextInt(3)));
        }
        for (int day = 0; day < 365; day++) {
            book = Wages.reconcile(book, people, ledger.get("citizens"));
            final long tax = 3L * people.size() * 2;
            final Map<UUID, Long> shares = Wages.split(tax, weights);
            final Map<UUID, Long> income = new LinkedHashMap<>();
            shares.forEach((id, v) -> income.put(id, Wages.income(v, id.getLeastSignificantBits() % 4 == 0 ? 1.0 : 1.1, 1, 0)));
            final Map<UUID, Long> got = Wages.pay(income, ledger.get("world:households"));
            transfer(ledger, "world:households", "citizens", Wages.sum(got));
            book = Wages.credit(book, got);
            final Map<UUID, UUID> payer = new LinkedHashMap<>();
            people.forEach(p -> payer.put(p, p));
            final Map<UUID, Long> meals = Wages.meals(book, payer, 1, 1.0);
            transfer(ledger, "citizens", "country", Wages.sum(meals));
            book = Wages.minus(book, meals);
            final Map<UUID, Long> paid = Wages.collect(book, shares);
            transfer(ledger, "citizens", "country", Wages.sum(paid));
            book = Wages.minus(book, paid);
            assertEquals(tax, Wages.sum(paid), "a fed town always pays its whole tax");
            final Map<UUID, Long> wages = Wages.pay(new LinkedHashMap<>(Map.of(id(0), 2L, id(1), 3L)), ledger.get("country"));
            transfer(ledger, "country", "citizens", Wages.sum(wages));
            book = Wages.credit(book, wages);
            assertEquals(total, ledger.values().stream().mapToLong(Long::longValue).sum());
            assertEquals(ledger.get("citizens").longValue(), Wages.sum(book));
            book.values().forEach(v -> assertTrue(v >= 0));
        }
    }

    private static void transfer(final Map<String, Long> ledger, final String from, final String to, final long amount) {
        if (amount <= 0) {
            return;
        }
        if (ledger.get(from) < amount) {
            throw new AssertionError("overdraft " + from + " " + amount);
        }
        ledger.merge(from, -amount, Long::sum);
        ledger.merge(to, amount, Long::sum);
    }

    @org.junit.jupiter.api.Test
    void marketSpendsHalfOfTheSavingsAboveTheBuffer() {
        final java.util.UUID a = java.util.UUID.randomUUID();
        final java.util.UUID b = java.util.UUID.randomUUID();
        final java.util.UUID c = java.util.UUID.randomUUID();
        final java.util.Map<java.util.UUID, Long> book = new java.util.LinkedHashMap<>();
        book.put(a, 100L);
        book.put(b, 31L);
        book.put(c, 10L);
        final var buys = Wages.market(book, 30, 0.5);
        org.junit.jupiter.api.Assertions.assertEquals(35L, buys.get(a));
        org.junit.jupiter.api.Assertions.assertNull(buys.get(b)); // half of 1 rounds down
        org.junit.jupiter.api.Assertions.assertNull(buys.get(c));
        final var after = Wages.minus(book, buys);
        org.junit.jupiter.api.Assertions.assertEquals(65L, after.get(a));
        org.junit.jupiter.api.Assertions.assertTrue(after.values().stream().allMatch(v -> v >= 0));
        org.junit.jupiter.api.Assertions.assertEquals(Wages.sum(book), Wages.sum(after) + Wages.sum(buys));
    }
}
