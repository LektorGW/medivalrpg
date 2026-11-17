// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** Daily boards: determinism, composition, needs from Ludnosc, rewards, premium offer. */
class QuestGeneratorTest {

    private static final List<QuestGenerator.Target> TARGETS = List.of(
        new QuestGenerator.Target("t1", "Gondor", 800, true),
        new QuestGenerator.Target("t2", "Rohan", 1500, false));

    static QuestConfig cfg(final Map<String, Object> overrides) {
        final YamlConfiguration y = TestSupport.yaml();
        overrides.forEach(y::set);
        return new QuestConfig(y, null);
    }

    private static Supplier<String> ids() {
        final AtomicInteger n = new AtomicInteger();
        return () -> "q" + n.incrementAndGet();
    }

    private static QuestGenerator.Input input(final String day, final List<String> needs, final List<QuestGenerator.Target> targets) {
        return new QuestGenerator.Input("kraj-1", day, 50, needs, targets, 0);
    }

    private static String fingerprint(final List<QuestData.Offer> offers) {
        final StringBuilder b = new StringBuilder();
        for (final QuestData.Offer o : offers) {
            b.append(o.type).append(o.material).append(o.amount).append(o.baseReward).append(o.targetCountry).append(o.premium).append(';');
        }
        return b.toString();
    }

    @Test
    void deterministicPerCountryAndDay() {
        final QuestConfig c = cfg(Map.of());
        final String a = fingerprint(QuestGenerator.generate(input("2026-10-27", List.of(), TARGETS), c, ids()));
        assertEquals(a, fingerprint(QuestGenerator.generate(input("2026-10-27", List.of(), TARGETS), c, ids())), "same board after a restart");
        assertNotEquals(a, fingerprint(QuestGenerator.generate(input("2026-10-28", List.of(), TARGETS), c, ids())), "a new board every day");
        assertNotEquals(a, fingerprint(QuestGenerator.generate(
            new QuestGenerator.Input("kraj-1", "2026-10-27", 50, List.of(), TARGETS, 1), c, ids())), "admin refresh (salt) changes it");
    }

    @Test
    void boardHasEveryTypeAndRespectsPools() {
        final QuestConfig c = cfg(Map.of());
        final List<QuestData.Offer> offers = QuestGenerator.generate(input("2026-10-27", List.of(), TARGETS), c, ids());
        assertEquals(c.offersPerDay, offers.size());
        final Set<QuestType> types = new HashSet<>();
        final Set<String> idSet = new HashSet<>();
        int premium = 0;
        for (final QuestData.Offer o : offers) {
            types.add(o.type());
            assertTrue(idSet.add(o.id), "unique ids");
            assertEquals("kraj-1", o.country);
            assertTrue(o.baseReward >= c.minReward && o.baseReward <= c.maxReward, "reward clamped");
            assertTrue(o.timeLimitMinutes > 0);
            if (o.premium) {
                premium++;
            }
            if (o.type() == QuestType.TRANSPORT) {
                assertTrue(o.targetCountry.equals("t1") || o.targetCountry.equals("t2"));
                assertEquals(1, o.amount);
                assertEquals(QuestRules.transportMinutes(o.distance, c), o.timeLimitMinutes);
            } else {
                final List<QuestConfig.PoolItem> pool = o.type() == QuestType.DOSTAWA ? c.deliveryPool : c.resources.get(o.need).pool();
                final QuestConfig.PoolItem item = pool.stream().filter(p -> p.material().equals(o.material)).findFirst().orElseThrow();
                assertTrue(o.amount >= item.min() && o.amount <= item.max(), "amount in range");
                assertEquals(item.name(), o.itemName);
            }
        }
        assertEquals(Set.of(QuestType.values()).size(), types.size(), "with 4 offers every type is present");
        assertEquals(1, premium, "exactly one premium offer");
    }

    @Test
    void noTransportWithoutDestinations() {
        final QuestConfig c = cfg(Map.of("zlecenia.offers-per-day", 8));
        for (final String day : List.of("2026-10-27", "2026-10-28", "2026-10-29")) {
            for (final QuestData.Offer o : QuestGenerator.generate(input(day, List.of(), List.of()), c, ids())) {
                assertNotEquals(QuestType.TRANSPORT, o.type());
            }
        }
    }

    @Test
    void resourceQuestsFollowNeeds() {
        final QuestConfig c = cfg(Map.of("zlecenia.offers-per-day", 6, "zlecenia.weights.dostawa", 0, "zlecenia.weights.transport", 0));
        final List<QuestData.Offer> offers = QuestGenerator.generate(input("2026-10-27", List.of("zywnosc", "drewno", "kamien"), List.of()), c, ids());
        assertEquals(6, offers.size());
        for (final QuestData.Offer o : offers) {
            assertEquals(QuestType.SUROWCE, o.type());
            assertTrue(o.urgent);
            assertTrue(o.need.equals("zywnosc") || o.need.equals("drewno"), "only the two most urgent needs: " + o.need);
        }
        assertEquals("zywnosc", offers.get(0).need, "the most urgent first");
        final List<QuestData.Offer> random = QuestGenerator.generate(input("2026-10-27", List.of(), List.of()), c, ids());
        assertTrue(random.stream().noneMatch(o -> o.urgent), "without Ludnosc data nothing is urgent");
    }

    @Test
    void needsFromStoresAndGranary() {
        final QuestConfig c = cfg(Map.of());
        final Map<String, Long> stock = Map.of("drewno", 30L, "kamien", 350L, "rudy", 500L, "ryby", 100L);
        // drewno 30/300 = 0.1, kamien 350/400 = 0.875; rudy and ryby are above their thresholds.
        assertEquals(List.of("drewno", "kamien"), QuestGenerator.needs(stock, null, c.resources));
        final QuestGenerator.Food hungry = new QuestGenerator.Food(0, 1000, -20, 2);
        assertEquals(List.of("zywnosc", "drewno", "kamien"), QuestGenerator.needs(stock, hungry, c.resources), "hunger comes first");
        final QuestGenerator.Food fine = new QuestGenerator.Food(900, 1000, 5, 0);
        assertFalse(QuestGenerator.needs(Map.of(), fine, c.resources).contains("zywnosc"));
        final QuestGenerator.Food low = new QuestGenerator.Food(100, 1000, 5, 0);
        assertTrue(QuestGenerator.needs(Map.of(), low, c.resources).contains("zywnosc"), "granary below a quarter");
        assertTrue(QuestGenerator.needs(Map.of("nieznany", 0L), null, c.resources).isEmpty(), "missing keys are unknown, not empty stores");
    }

    @Test
    void rewardFormulas() {
        final QuestConfig.PoolItem wheat = new QuestConfig.PoolItem("WHEAT", "pszenica", 32, 64, 10, 16);
        assertEquals(30, wheat.rewardFor(48));
        assertEquals(0, wheat.rewardFor(-3));
        final QuestConfig c = cfg(Map.of());
        assertEquals(60 + 25 * 8, QuestRules.transportReward(850, c));
        assertEquals(20 + 4 * 9, QuestRules.transportMinutes(850, c));
        assertEquals(1.2, QuestRules.wealthFactor(100, 0.2), 1e-9);
        assertEquals(0.8, QuestRules.wealthFactor(0, 0.2), 1e-9);
        assertEquals(1.0, QuestRules.wealthFactor(50, 0.2), 1e-9);
        // A rich ruler pays more than a poor one for the same board.
        final List<QuestData.Offer> rich = QuestGenerator.generate(new QuestGenerator.Input("k", "d", 100, List.of(), TARGETS, 0), c, ids());
        final List<QuestData.Offer> poor = QuestGenerator.generate(new QuestGenerator.Input("k", "d", 0, List.of(), TARGETS, 0), c, ids());
        long r = 0;
        long p = 0;
        for (int i = 0; i < rich.size(); i++) {
            r += rich.get(i).baseReward;
            p += poor.get(i).baseReward;
        }
        assertTrue(r > p);
    }

    @Test
    void configPoolsUseRealMedievalItems() {
        final QuestConfig c = cfg(Map.of());
        final Set<String> forbidden = Set.of("REDSTONE", "PISTON", "RAIL", "MINECART", "TNT", "OBSERVER", "COMPARATOR", "REPEATER");
        final List<QuestConfig.PoolItem> all = new java.util.ArrayList<>(c.deliveryPool);
        c.resources.values().forEach(r -> all.addAll(r.pool()));
        assertFalse(all.isEmpty());
        // Enum constant names via reflection: Material's static initialiser needs a running server.
        final Set<String> materials = new HashSet<>();
        for (final java.lang.reflect.Field f : Material.class.getDeclaredFields()) {
            if (f.isEnumConstant() && !f.getName().startsWith("LEGACY_")) {
                materials.add(f.getName());
            }
        }
        assertTrue(materials.contains("WHEAT"));
        for (final QuestConfig.PoolItem i : all) {
            assertTrue(materials.contains(i.material()), "unknown material " + i.material());
            assertTrue(forbidden.stream().noneMatch(f -> i.material().contains(f)), "no industrial items: " + i.material());
            assertFalse(i.name().isBlank());
            assertTrue(i.min() <= i.max());
        }
        assertTrue(materials.contains(c.cargoMaterial));
        assertEquals(Set.of("drewno", "kamien", "rudy", "ryby", "zywnosc"), c.resources.keySet());
    }
}
