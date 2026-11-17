// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollTest {

    private static final Payroll.Rules RULES = Payroll.Rules.defaults();

    @Test
    void everyonePaidWhenEnoughMoney() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final Payroll.Result r = Payroll.pay(List.of(new Payroll.Soldier(a, 20, 50), new Payroll.Soldier(b, 30, 95)), 1000, RULES);
        assertEquals(50, r.paid());
        assertEquals(2, r.paidIds().size());
        assertEquals(60, r.morale().get(a));
        assertEquals(100, r.morale().get(b)); // capped
        assertTrue(r.deserters().isEmpty());
        assertEquals(0, r.unpaid());
    }

    @Test
    void noMoneyLowersMoraleAndDesertsAfterThreeDays() {
        final UUID a = UUID.randomUUID();
        int morale = 100;
        final List<Integer> history = new ArrayList<>();
        Payroll.Result r = null;
        for (int day = 0; day < 3; day++) {
            r = Payroll.pay(List.of(new Payroll.Soldier(a, 20, morale)), 0, RULES);
            morale = r.morale().get(a);
            history.add(morale);
        }
        assertEquals(List.of(66, 32, 0), history);
        assertEquals(List.of(a), r.deserters());
        assertEquals(0, r.paid());
    }

    @Test
    void partialMoneyPaysLeastLoyalFirst() {
        final UUID loyal = UUID.randomUUID();
        final UUID wavering = UUID.randomUUID();
        final Payroll.Result r = Payroll.pay(List.of(new Payroll.Soldier(loyal, 20, 90), new Payroll.Soldier(wavering, 20, 10)), 25, RULES);
        assertEquals(List.of(wavering), r.paidIds());
        assertEquals(20, r.paid());
        assertEquals(20, r.morale().get(wavering));
        assertEquals(56, r.morale().get(loyal));
        assertEquals(1, r.unpaid());
        assertTrue(r.deserters().isEmpty());
    }

    @Test
    void unpaidLowMoraleDesertsImmediately() {
        final UUID a = UUID.randomUUID();
        final Payroll.Result r = Payroll.pay(List.of(new Payroll.Soldier(a, 20, 30)), 0, RULES);
        assertEquals(List.of(a), r.deserters());
    }

    @Test
    void freeSoldiersAlwaysPaid() {
        final UUID a = UUID.randomUUID();
        final Payroll.Result r = Payroll.pay(List.of(new Payroll.Soldier(a, 0, 50)), 0, RULES);
        assertEquals(List.of(a), r.paidIds());
        assertEquals(0, Payroll.dailyTotal(List.of(new Payroll.Soldier(a, 0, 50))));
    }

    @Test
    void negativeBalanceTreatedAsZero() {
        final Payroll.Result r = Payroll.pay(List.of(new Payroll.Soldier(UUID.randomUUID(), 5, 100)), -100, RULES);
        assertEquals(0, r.paid());
    }
}
