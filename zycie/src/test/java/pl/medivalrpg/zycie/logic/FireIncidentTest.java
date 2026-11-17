// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FireIncidentTest {
    private FireIncident incident() { return new FireIncident(UUID.randomUUID(),"world",new FireIncident.Cell(10,64,20),1000); }

    @Test void alarmIsRateLimitedAndTheCooldownSurvivesInTheState() {
        final var i=incident(); assertTrue(i.alarmDue(1000,30000));
        i.lastAlarmAt=1000; assertFalse(i.alarmDue(30999,30000)); assertTrue(i.alarmDue(31000,30000));
    }
    @Test void quietPeriodAloneCannotStartRecoveryWithoutAnObservedCompleteSweep() {
        final var i=incident(); i.fires.clear();
        assertFalse(i.recoveryDue(999999,20000));
        i.lastSweepAt=1001; assertFalse(i.recoveryDue(20999,20000)); assertTrue(i.recoveryDue(21000,20000));
    }
    @Test void aNewFlameDelaysRecoveryUntilAnotherCompleteQuietSweep() {
        final var i=incident(); i.fires.clear(); i.lastSweepAt=1001;
        i.observe(new FireIncident.Cell(11,64,20),20000,64);
        assertFalse(i.recoveryDue(999999,20000));
        i.fires.clear(); assertFalse(i.recoveryDue(999999,20000));
        i.lastSweepAt=20001; assertTrue(i.recoveryDue(40000,20000));
    }
    @Test void acceptedRecoveryIsNeverRequestedTwiceEvenWhenZeroBuildingsNeedWork() {
        final var i=incident(); i.fires.clear(); i.lastSweepAt=1001;
        i.recoveryResult(0,21000,30000);
        assertFalse(i.recoveryDue(999999,20000)); assertEquals(21000,i.recoveredAt);
    }
    @Test void unavailableRecoveryRetriesWithoutLosingTheIncident() {
        final var i=incident(); i.fires.clear(); i.lastSweepAt=1001;
        i.recoveryResult(-1,21000,30000);
        assertFalse(i.recoveryDue(50999,20000)); assertTrue(i.recoveryDue(51000,20000));
    }
    @Test void incidentDoesNotMergeAcrossOwnershipWorldsOrDistantFires() {
        final var i=incident(); final var at=new FireIncident.Cell(11,65,22);
        assertTrue(i.accepts(i.country,"world",at,24));
        assertFalse(i.accepts(UUID.randomUUID(),"world",at,24));
        assertFalse(i.accepts(i.country,"nether",at,24));
        assertFalse(i.accepts(i.country,"world",new FireIncident.Cell(50,64,20),24));
    }
    @Test void observedCellCapDoesNotPreventDamageBoundsFromGrowing() {
        final var i=incident(); i.observe(new FireIncident.Cell(20,70,30),2000,1);
        assertEquals(1,i.fires.size()); assertEquals(20,i.maxX); assertEquals(70,i.maxY); assertEquals(30,i.maxZ);
    }
}
