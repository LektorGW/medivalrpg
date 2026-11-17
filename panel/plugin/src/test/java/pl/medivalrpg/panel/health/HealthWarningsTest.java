// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Thresholds, sustain, escalation and episodes of the health warnings. */
class HealthWarningsTest {

    static final HealthWarnings.Thresholds TH = new HealthWarnings.Thresholds(18, 15, 45, 50, 100, 200, 5000, 0, Map.of("armia", 10), 2);

    static HealthPoint p(final long t, final double tps, final double mspt, final int ludnosc, final int armia, final int entities) {
        return HealthPoint.probe(t, tps, mspt, 1, 100, entities, Map.of("ludnosc", ludnosc, "armia", armia), 0);
    }

    @Test
    void checkReportsEveryCrossedThresholdInPolish() {
        final List<HealthWarnings.Violation> v = HealthWarnings.check(p(0, 16.4, 47.25, 95, 12, 6000), TH);
        assertEquals(List.of("TPS", "MSPT", "NPC", "ENTITIES", "NPC_ARMIA"), v.stream().map(HealthWarnings.Violation::code).toList());
        assertEquals("Niski TPS: 16,4 (próg 18,0)", v.get(0).text());
        assertEquals(HealthWarnings.Level.WARN, v.get(0).level());
        assertEquals("Długi tick: 47,3 ms (próg 45,0 ms)", v.get(1).text());
        assertEquals("Dużo NPC: 107 (próg 100)", v.get(2).text());
        assertEquals("Dużo NPC (Armia): 12 (próg 10)", v.get(4).text());
        final List<HealthWarnings.Violation> crit = HealthWarnings.check(p(0, 12, 80, 300, 0, 0), TH);
        assertEquals(HealthWarnings.Level.CRITICAL, crit.get(0).level());
        assertEquals(15.0, crit.get(0).threshold());
        assertEquals(HealthWarnings.Level.CRITICAL, crit.get(1).level());
        assertEquals(HealthWarnings.Level.CRITICAL, crit.get(2).level());
        assertTrue(HealthWarnings.check(p(0, 20, 10, 1, 1, 100), TH).isEmpty());
    }

    @Test
    void criticalThresholdsWorkIndependentlyOfWarningThresholds() {
        final var th = new HealthWarnings.Thresholds(0, 15, 0, 50, 0, 200, 0, 5000, Map.of(), 1);
        final var violations = HealthWarnings.check(p(0, 12, 80, 300, 0, 6000), th);
        assertEquals(List.of("TPS", "MSPT", "NPC", "ENTITIES"), violations.stream().map(HealthWarnings.Violation::code).toList());
        assertTrue(violations.stream().allMatch(v -> v.level() == HealthWarnings.Level.CRITICAL));
        assertTrue(HealthWarnings.check(p(0, 15, 50, 200, 0, 5000), th).isEmpty());
    }

    @Test
    void persistenceSnapshotsDoNotShareMutableEpisodes() {
        final var w = new HealthWarnings();
        final var th = new HealthWarnings.Thresholds(18, 15, 0, 0, 0, 0, 0, 0, Map.of(), 1);
        w.update(p(1000, 17, 10, 0, 0, 0), th);
        final var saved = w.save(1000, false);
        w.update(p(2000, 12, 10, 0, 0, 0), th);
        w.update(p(3000, 20, 10, 0, 0, 0), th);
        assertEquals("WARN", saved.get(0).level);
        assertEquals(17, saved.get(0).worst);
        assertNull(saved.get(0).end);
        saved.get(0).text = "changed snapshot";
        assertEquals("Niski TPS: 12,0 (próg 15,0)", w.episodes().get(0).text);
    }

    @Test
    void disabledThresholdsNeverWarn() {
        final HealthWarnings.Thresholds off = new HealthWarnings.Thresholds(0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 1);
        assertTrue(HealthWarnings.check(p(0, 1, 999, 99_999, 99_999, 99_999), off).isEmpty());
    }

    @Test
    void sustainEscalationAndEpisodes() {
        final HealthWarnings w = new HealthWarnings();
        HealthWarnings.Update u = w.update(p(1000, 16, 10, 0, 0, 0), TH);
        assertTrue(u.active().isEmpty(), "one probe is not enough (sustain 2)");
        u = w.update(p(2000, 16, 10, 0, 0, 0), TH);
        assertEquals(List.of("TPS"), u.codes());
        assertEquals(1, u.raised().size());
        assertEquals(2000, u.active().get(0).since());
        u = w.update(p(3000, 17, 10, 0, 0, 0), TH);
        assertTrue(u.raised().isEmpty(), "still active, no new alert");
        u = w.update(p(4000, 12, 10, 0, 0, 0), TH);
        assertEquals(HealthWarnings.Level.CRITICAL, u.raised().get(0).level(), "escalation raises again");
        assertEquals(2000, u.active().get(0).since(), "since kept");
        u = w.update(p(5000, 20, 10, 0, 0, 0), TH);
        assertTrue(u.active().isEmpty());
        assertEquals("TPS", u.ended().get(0).code());
        final HealthWarnings.Episode e = w.episodes().get(0);
        assertEquals("CRITICAL", e.level);
        assertEquals(2000, e.start);
        assertEquals(5000L, e.end);
        assertEquals(12.0, e.worst);
        assertEquals("Niski TPS: 12,0 (próg 15,0)", e.text);
    }

    @Test
    void anInterruptedStreakDoesNotActivate() {
        final HealthWarnings w = new HealthWarnings();
        w.update(p(1000, 16, 10, 0, 0, 0), TH);
        w.update(p(2000, 20, 10, 0, 0, 0), TH);
        assertTrue(w.update(p(3000, 16, 10, 0, 0, 0), TH).active().isEmpty());
        assertTrue(w.episodes().isEmpty());
    }

    @Test
    void saveClosesOpenEpisodesAndRestoreDropsOldOnes() {
        final HealthWarnings w = new HealthWarnings();
        final HealthWarnings.Thresholds one = new HealthWarnings.Thresholds(18, 0, 0, 0, 0, 0, 0, 0, Map.of(), 1);
        w.update(p(1000, 10, 10, 0, 0, 0), one);
        final List<HealthWarnings.Episode> saved = w.save(9000, true);
        assertEquals(9000L, saved.get(0).end);
        final HealthWarnings r = new HealthWarnings();
        r.restore(saved, 10_000);
        assertEquals(1, r.episodes().size());
        assertTrue(r.active().isEmpty(), "warnings start fresh after a restart");
        final HealthWarnings old = new HealthWarnings();
        old.restore(saved, 9000 + HealthWarnings.EPISODE_KEEP_MS + 1);
        assertTrue(old.episodes().isEmpty());
        final HealthWarnings.Episode open = new HealthWarnings.Episode();
        open.code = "MSPT";
        open.start = 500;
        old.restore(List.of(open), 600);
        assertEquals(500L, old.episodes().get(0).end, "unclean stop: closed at its start");
    }
}
