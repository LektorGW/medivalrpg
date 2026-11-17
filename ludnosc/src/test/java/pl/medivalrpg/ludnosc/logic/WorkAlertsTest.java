// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkAlertsTest {
    private static final long COOLDOWN = 600_000;
    private final WorkAlerts alerts = new WorkAlerts();
    private final WorkAlerts.Key key = new WorkAlerts.Key(new UUID(0, 1), new UUID(0, 2));
    private final List<WorkAlerts.Notice> messages = new ArrayList<>();
    private void blocked(long now) { alerts.observe(key, WorkAlerts.Reason.FULL, "Kopalnia", now); }
    private void send(long now) { alerts.dispatch(now, COOLDOWN, 32, n -> messages.add(n)); }

    @Test void repeatedWorkersShareOneIncidentAndCooldown() {
        for (int i = 0; i < 1000; i++) { blocked(i); send(i); }
        assertEquals(1, alerts.size()); assertEquals(1, messages.size());
        blocked(COOLDOWN); send(COOLDOWN);
        assertEquals(2, messages.size());
    }
    @Test void offlineOrRateLimitedDeliveryDoesNotSpendCooldown() {
        blocked(0); alerts.dispatch(0, COOLDOWN, 32, n -> false);
        send(1); assertEquals(1, messages.size());
    }
    @Test void recoveryIsImmediateOnceConfirmedAndNotRepeated() {
        blocked(0); send(0); alerts.recover(key, 1); send(1); send(2);
        assertEquals(2, messages.size()); assertTrue(messages.getLast().recovery());
    }
    @Test void unknownOrNeverAnnouncedIncidentDoesNotSendRecovery() {
        alerts.recover(key, 0); send(0);
        blocked(1); alerts.recover(key, 2); send(2);
        assertTrue(messages.isEmpty());
    }
    @Test void flappingDoesNotResetWarningCooldown() {
        blocked(0); send(0); alerts.recover(key, 1); send(1);
        blocked(2); send(2); assertEquals(2, messages.size());
        blocked(COOLDOWN); send(COOLDOWN); assertEquals(3, messages.size());
    }
    @Test void reasonChangeUpdatesDetailsWithoutBypassingCooldown() {
        blocked(0); send(0);
        alerts.observe(key, WorkAlerts.Reason.TORCHES, "Brak pochodni", 1); send(1);
        assertEquals(1, messages.size());
        alerts.observe(key, WorkAlerts.Reason.TORCHES, "Brak pochodni", COOLDOWN); send(COOLDOWN);
        assertEquals(WorkAlerts.Reason.TORCHES, messages.getLast().reason());
    }
    @Test void staleWorkDoesNotSendWarningsOrInventRecovery() {
        blocked(0); send(WorkAlerts.FRESH_MILLIS + 1);
        assertTrue(messages.isEmpty());
        send(WorkAlerts.RETAIN_MILLIS + 1); assertEquals(0, alerts.size());
    }
    @Test void offlineRecoveryIsKeptForBoundedRetention() {
        blocked(0); send(0); alerts.recover(key, 1);
        send(WorkAlerts.FRESH_MILLIS + 1000);
        assertTrue(messages.getLast().recovery());
    }
    @Test void disappearingOrTransferredWorkIsForgottenSilently() {
        blocked(0); send(0); alerts.forget(key); send(COOLDOWN);
        assertEquals(1, messages.size()); assertEquals(0, alerts.size());
    }
    @Test void roundRobinBudgetDoesNotStarveLaterIncidents() {
        for (int i = 0; i < 70; i++) alerts.observe(new WorkAlerts.Key(key.country(), new UUID(0, 10+i)), WorkAlerts.Reason.FULL, "Pole", 0);
        send(0); assertEquals(32, messages.size()); send(0); assertEquals(64, messages.size());
        send(0); assertEquals(70, messages.size());
    }
    @Test void incidentCapacityIsHardBounded() {
        for (int i = 0; i < 5000; i++) alerts.observe(new WorkAlerts.Key(key.country(), new UUID(0, i)), WorkAlerts.Reason.FULL, "Pole", 0);
        assertEquals(WorkAlerts.MAX_INCIDENTS, alerts.size());
    }
    @Test void foodAndDifferentCountriesHaveIndependentCooldowns() {
        blocked(0);
        alerts.observe(new WorkAlerts.Key(key.country(), null), WorkAlerts.Reason.FOOD, "0/50", 0);
        alerts.observe(new WorkAlerts.Key(new UUID(0, 3), key.workplace()), WorkAlerts.Reason.FULL, "Kopalnia", 0);
        send(0); assertEquals(3, messages.size());
    }
    @Test void rediscoveredProblemCancelsQueuedRecovery() {
        blocked(0); send(0); alerts.recover(key, 1); blocked(2); send(2);
        assertEquals(1, messages.size());
    }
    @Test void deletedWorkCanBeRemovedByTheDeliveryFilter() {
        blocked(0);
        alerts.dispatch(0, COOLDOWN, 32, n -> { alerts.forget(n.key()); return false; });
        assertEquals(0, alerts.size());
    }
    @Test void disabledAlertsDiscardPendingIncidentsWithoutRecovery() {
        blocked(0); send(0); alerts.recover(key, 1); alerts.clear(); send(1);
        assertEquals(1, messages.size()); assertEquals(0, alerts.size());
    }
}
