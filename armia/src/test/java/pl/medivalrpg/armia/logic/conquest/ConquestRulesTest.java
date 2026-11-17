// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic.conquest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.logic.ChunkPlanner;

class ConquestRulesTest {

    private static final ConquestSettings S = ConquestSettings.defaults();

    private static Set<Long> rect(final int x0, final int z0, final int x1, final int z1) {
        final Set<Long> out = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                out.add(ChunkPlanner.key(x, z));
            }
        }
        return out;
    }

    private static ConquestRules.StartView ok() {
        return new ConquestRules.StartView(true, true, 120, 999, 0, 0, 0, 10, 100_000, 1_000, false, false, false, false);
    }

    @Test
    void startConditions() {
        assertNull(ConquestRules.cannotStart(ok(), S));
        final ConquestRules.StartView v = ok();
        assertEquals("wojna trwa za krótko", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 5, 999, 0, 0, 0, 10, 100_000, 1_000,
            false, false, false, false), S));
        assertEquals("przerwa po ostatniej ofensywie", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 10, 0, 0, 0, 10,
            100_000, 1_000, false, false, false, false), S));
        assertEquals("dzienny limit zdobyczy kraju", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999,
            S.maxChunksPerDay(), 0, 0, 10, 100_000, 1_000, false, false, false, false), S));
        assertEquals("dzienny limit zdobyczy na serwerze", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0,
            S.globalMaxChunksPerDay(), 0, 10, 100_000, 1_000, false, false, false, false), S));
        assertEquals("zmęczenie wojną", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0, 0,
            S.exhaustion().blockOffensive(), 10, 100_000, 1_000, false, false, false, false), S));
        assertEquals("postawa obronna", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0, 0, 0, 10, 100_000, 1_000,
            true, false, false, false), S));
        assertNull(ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0, 0, 0, 10, 100_000, 1_000,
            true, true, false, false), S), "defensive posture still reclaims lost land");
        assertEquals("za mało wolnych żołnierzy", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0, 0, 0, 2,
            100_000, 1_000, false, false, false, false), S));
        assertEquals("brak pieniędzy na wyprawę", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0, 0, 0, 10,
            10, 1_000, false, false, false, false), S));
        assertEquals("trwa oblężenie fizyczne", ConquestRules.cannotStart(new ConquestRules.StartView(true, true, 120, 999, 0, 0, 0, 10,
            100_000, 1_000, false, false, true, false), S));
        assertEquals("AI wstrzymana", ConquestRules.cannotStart(new ConquestRules.StartView(false, true, 120, 999, 0, 0, 0, 10,
            100_000, 1_000, false, false, false, false), S));
        assertTrue(v.aiActive());
    }

    @Test
    void homeGuardStaysAndForceIsCapped() {
        assertEquals(6, ConquestRules.forceSize(10, 10, S)); // 35 % stay home
        assertEquals(S.maxForce(), ConquestRules.forceSize(100, 100, S));
        assertEquals(3, ConquestRules.forceSize(100, 3, S));
        assertEquals(0, ConquestRules.forceSize(0, 0, S));
    }

    @Test
    void rollingDayDropsOldEvents() {
        final long now = 10 * ConquestRules.DAY;
        final List<Long> t = new ArrayList<>(List.of(now - ConquestRules.DAY - 1, now - 5, now - ConquestRules.HOUR));
        assertEquals(2, ConquestRules.lastDay(t, now));
        assertEquals(2, t.size());
    }

    @Test
    void exhaustionGrowsAtWarAndRecoversAtPeace() {
        assertEquals(6.0, ConquestRules.exhaustion(0, 10, true, S), 1e-9);
        assertEquals(0.0, ConquestRules.exhaustion(10, 10, false, S), 1e-9);
        assertEquals(100.0, ConquestRules.exhaustion(99, 1000, true, S), 1e-9);
    }

    @Test
    void largeTerritoryCostsMoreAndMore() {
        assertEquals(0, ConquestRules.overextensionCost(100, S));
        final long c110 = ConquestRules.overextensionCost(110, S);
        final long c200 = ConquestRules.overextensionCost(200, S);
        assertEquals(Math.round(4 * 10 * 1.1), c110);
        assertTrue(c200 > 10 * c110 / 1.5, "superlinear " + c110 + " → " + c200);
        assertEquals(Math.round(4 * 100 * 2.0), c200);
    }

    @Test
    void revoltsOnlyOnFreshConquestsAndMoreWhenExhausted() {
        assertEquals(0, ConquestRules.revoltChancePerDay(S.revolt().windowHours() + 1, 50, 0, S), 1e-12);
        final double calm = ConquestRules.revoltChancePerDay(5, 50, 0, S);
        final double tired = ConquestRules.revoltChancePerDay(5, 50, 80, S);
        final double huge = ConquestRules.revoltChancePerDay(5, 300, 0, S);
        assertEquals(S.revolt().chancePerDay(), calm, 1e-12);
        assertTrue(tired > calm && huge > calm);
        assertEquals(0.5, ConquestRules.perCycle(0.5, 1440), 1e-9);
        assertTrue(ConquestRules.perCycle(0.5, 5) < 0.01);
        assertEquals(0, ConquestRules.perCycle(0, 5), 1e-12);
    }

    @Test
    void vassalsBreakAwayFromAWeakOverlordTheyDislike() {
        assertTrue(ConquestRules.secessionPossible(70, 50, -10, S));
        assertTrue(ConquestRules.secessionPossible(0, 200, -1, S));
        assertFalse(ConquestRules.secessionPossible(70, 50, 10, S));
        assertFalse(ConquestRules.secessionPossible(10, 50, -50, S));
    }

    @Test
    void hegemonAndCoalition() {
        final Map<UUID, Integer> m = new HashMap<>();
        final UUID big = new UUID(0, 1);
        m.put(big, 300);
        m.put(new UUID(0, 2), 100);
        m.put(new UUID(0, 3), 90);
        m.put(new UUID(0, 4), 80);
        assertEquals(big, ConquestRules.hegemon(m, S).orElse(null));
        m.put(big, 110);
        assertTrue(ConquestRules.hegemon(m, S).isEmpty());
        assertTrue(ConquestRules.hegemon(Map.of(big, 500, new UUID(0, 2), 1), S).isEmpty(), "needs 3 NPC countries");
        assertEquals(-8, ConquestRules.coalitionOpinion(0, 1, S));
        assertEquals(S.coalition().opinionFloor(), ConquestRules.coalitionOpinion(0, 100, S));
        assertEquals(-90, ConquestRules.coalitionOpinion(-90, 1, S));
    }

    @Test
    void peaceTributeOnlyAfterConquestsAndCapped() {
        assertEquals(0, ConquestRules.peaceTribute(1000, 0, S));
        assertEquals(150 + 40, ConquestRules.peaceTribute(1000, 2, S));
        assertEquals(S.peaceTributeMax(), ConquestRules.peaceTribute(10_000_000, 50, S));
    }

    @Test
    void frontTargetsKeepTheEnemyConnectedAndSpareItsCapital() {
        final Set<Long> own = rect(0, 0, 2, 2);
        final Set<Long> enemy = rect(3, 1, 6, 1); // a line, capital at the far end
        final long cap = ChunkPlanner.key(6, 1);
        final List<ConquestRules.Target> t = ConquestRules.targets(own, enemy, cap, Set.of(), Set.of(), S);
        assertEquals(1, t.size());
        assertEquals(ChunkPlanner.key(3, 1), t.get(0).key());
        assertFalse(t.get(0).expedition());
        // capital only as the very last chunk
        final List<ConquestRules.Target> last = ConquestRules.targets(rect(5, 1, 5, 1), Set.of(cap), cap, Set.of(), Set.of(), S);
        assertEquals(1, last.size());
        // blocked targets are skipped
        assertTrue(ConquestRules.targets(own, enemy, cap, Set.of(), Set.of(ChunkPlanner.key(3, 1)), S).isEmpty());
    }

    @Test
    void preferredLostChunksComeFirst() {
        final Set<Long> own = rect(0, 0, 0, 4);
        final Set<Long> enemy = rect(1, 0, 3, 4);
        final long cap = ChunkPlanner.key(3, 2);
        final long lost = ChunkPlanner.key(1, 4);
        final List<ConquestRules.Target> t = ConquestRules.targets(own, enemy, cap, Set.of(lost), Set.of(), S);
        assertEquals(lost, t.get(0).key());
    }

    @Test
    void expeditionsReachTheNearestEdgeWithinRange() {
        final Set<Long> own = rect(0, 0, 2, 2);
        final Set<Long> enemy = rect(40, 0, 44, 4);
        final long cap = ChunkPlanner.key(44, 2);
        final List<ConquestRules.Target> t = ConquestRules.targets(own, enemy, cap, Set.of(), Set.of(), S);
        assertFalse(t.isEmpty());
        assertTrue(t.get(0).expedition());
        assertEquals(40, ChunkPlanner.cx(t.get(0).key()));
        assertEquals(38, t.get(0).distance());
        for (final ConquestRules.Target x : t) {
            assertTrue(x.key() != ChunkPlanner.key(42, 2), "inner chunk is no expedition target");
            assertTrue(x.key() != cap);
        }
        final Set<Long> far = rect(400, 0, 404, 4);
        assertTrue(ConquestRules.targets(own, far, ChunkPlanner.key(404, 2), Set.of(), Set.of(), S).isEmpty());
        final ConquestSettings noExp = ConquestSettings.from(yaml("expeditions:\n  enabled: false\n"));
        assertTrue(ConquestRules.targets(own, enemy, cap, Set.of(), Set.of(), noExp).isEmpty());
        assertEquals(38, ConquestRules.gap(own, enemy));
    }

    private static org.bukkit.configuration.ConfigurationSection yaml(final String text) {
        final YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(text);
        } catch (final org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    @Test
    void configMatchesTheJarDefaultsAndPlayersAreOffByDefault() throws Exception {
        final YamlConfiguration jar = new YamlConfiguration();
        try (var in = ConquestRulesTest.class.getResourceAsStream("/config.yml")) {
            jar.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        final ConquestSettings fromJar = ConquestSettings.from(jar.getConfigurationSection("ai.conquest"));
        assertEquals(ConquestSettings.defaults(), fromJar, "code defaults = config.yml defaults");
        assertFalse(fromJar.abstractVsPlayers(), "background conquest of player land is off by default");
        assertTrue(fromJar.enabled());
    }

    /**
     * Simulated month of two NPC countries (same rules as the service): the stronger one gains land, but daily limits,
     * exhaustion and overextension stop it long before it eats the weaker one entirely within a day.
     */
    @Test
    void balanceStopsRunawayConquest() {
        final Random r = new Random(42);
        int taken = 0;
        double exhaustionA = 0;
        long dayStart = 0;
        int takenToday = 0;
        int maxPerDay = 0;
        for (int cycle = 0; cycle < 12 * 24 * 7; cycle++) { // one week, 5-minute cycles
            final long now = cycle * 5L * 60_000L;
            if (now - dayStart >= ConquestRules.DAY) {
                dayStart = now;
                takenToday = 0;
            }
            exhaustionA = ConquestRules.exhaustion(exhaustionA, 5 / 60.0, true, S);
            if (takenToday >= S.maxChunksPerDay() || exhaustionA >= S.exhaustion().blockOffensive()) {
                continue;
            }
            final BattleMath.Round round = BattleMath.round(12 * BattleMath.exhaustion(exhaustionA, S), 3, 12, 3, 0.9, S, r::nextDouble);
            if (round.progress() >= 1.0) {
                taken++;
                takenToday++;
                maxPerDay = Math.max(maxPerDay, takenToday);
                exhaustionA = ConquestRules.clamp(exhaustionA + round.attackerLosses() * S.exhaustion().perSoldierLost());
            }
        }
        assertTrue(maxPerDay <= S.maxChunksPerDay());
        assertTrue(taken <= S.maxChunksPerDay() * 7, "taken " + taken);
        assertTrue(exhaustionA > 50, "a week of war tires the attacker: " + exhaustionA);
    }
}
