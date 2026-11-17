// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PhaseTest {

    @Test
    void happyPathOfABracketTournament() {
        final PhaseMachine m = new PhaseMachine(Phase.SIGNUPS);
        m.moveTo(Phase.PREPARING);
        m.moveTo(Phase.INTERMISSION);
        for (int i = 0; i < 3; i++) {
            m.moveTo(Phase.COUNTDOWN);
            m.moveTo(Phase.FIGHTING);
            m.moveTo(Phase.INTERMISSION);
        }
        m.moveTo(Phase.CELEBRATION);
        m.moveTo(Phase.FINISHED);
        assertTrue(m.phase().terminal());
    }

    @Test
    void quitDuringCountdownGoesBackToIntermission() {
        final PhaseMachine m = new PhaseMachine(Phase.COUNTDOWN);
        assertTrue(m.tryMoveTo(Phase.INTERMISSION));
    }

    @Test
    void illegalTransitionsAreRefused() {
        final PhaseMachine m = new PhaseMachine(Phase.SIGNUPS);
        assertThrows(IllegalStateException.class, () -> m.moveTo(Phase.FIGHTING));
        assertFalse(m.tryMoveTo(Phase.CELEBRATION));
        assertEquals(Phase.SIGNUPS, m.phase());
        final PhaseMachine done = new PhaseMachine(Phase.CELEBRATION);
        assertFalse(done.tryMoveTo(Phase.CANCELLED), "prizes already paid: no refund-cancel any more");
        done.moveTo(Phase.FINISHED);
        assertFalse(done.tryMoveTo(Phase.SIGNUPS));
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"SIGNUPS", "PREPARING", "INTERMISSION", "COUNTDOWN", "FIGHTING"})
    void everyActivePhaseCanBeCancelledWithRefund(final Phase p) {
        assertTrue(p.canMoveTo(Phase.CANCELLED));
        assertTrue(p.refundable());
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void terminalPhasesHaveNoExit(final Phase p) {
        assertEquals(p.terminal(), p.next().isEmpty());
    }

    @Test
    void flags() {
        assertTrue(Phase.FIGHTING.matchLive());
        assertTrue(Phase.COUNTDOWN.matchLive());
        assertFalse(Phase.INTERMISSION.matchLive());
        assertFalse(Phase.SIGNUPS.running());
        assertTrue(Phase.CELEBRATION.running());
        assertFalse(Phase.CELEBRATION.refundable());
        assertFalse(Phase.FINISHED.running());
    }
}
