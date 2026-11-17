// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EscrowLedgerTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void feesAndPoolAddUp() {
        final EscrowLedger l = new EscrowLedger("s1", "Turniej", "turnieje:s1");
        l.sourceAccount = "country:x";
        l.funded = 1000;
        l.addFee(this.a, 50);
        l.addFee(this.b, 50);
        assertEquals(100, l.totalFees());
        assertEquals(1100, l.held());
        assertEquals(1100, l.prizePool());
        assertEquals(50, l.removeFee(this.a), "wypisz refunds the fee");
        assertEquals(0, l.removeFee(this.a));
        assertEquals(1050, l.held());
    }

    @Test
    void refundPlanReturnsFeesToPlayersAndFundedToSourceNeverMinted() {
        final EscrowLedger l = new EscrowLedger("s2", "T", "turnieje:s2");
        l.sourceAccount = "serwer:turnieje";
        l.funded = 400;
        l.minted = 999;
        l.addFee(this.a, 30);
        final EscrowLedger.RefundPlan plan = l.refundPlan();
        assertEquals(Map.of(this.a, 30L), plan.fees());
        assertEquals(400, plan.toSource());
        assertEquals(430, plan.total(), "minted money was never taken from anybody");
        assertEquals(1429, l.prizePool());
    }

    @Test
    void noSourceNothingToReturnButFees() {
        final EscrowLedger l = new EscrowLedger("s3", "T", "turnieje:s3");
        l.minted = 500;
        l.addFee(this.b, 10);
        assertEquals(10, l.refundPlan().total());
        l.clear();
        assertEquals(0, l.prizePool());
        assertTrue(l.fees.isEmpty());
    }

    @Test
    void zeroFeeIsIgnoredNegativeRejected() {
        final EscrowLedger l = new EscrowLedger("s4", "T", "turnieje:s4");
        l.addFee(this.a, 0);
        assertTrue(l.fees.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> l.addFee(this.a, -1));
    }

    @Test
    void survivesJsonRoundTrip() {
        final EscrowLedger l = new EscrowLedger("s5", "Turniej Księstwa", "turnieje:s5");
        l.sourceAccount = "country:abc";
        l.funded = 700;
        l.addFee(this.a, 25);
        l.reopenWeeklyKey = "2026-10-31";
        final EscrowLedger back = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(l), EscrowLedger.class);
        assertEquals(l.refundPlan(), back.refundPlan());
        assertEquals("2026-10-31", back.reopenWeeklyKey);
        assertEquals(725, back.held());
    }

    @Test
    void rankingOrder() {
        final PlayerStats x = new PlayerStats(UUID.randomUUID(), "Xawery");
        x.titles = 1;
        x.wins = 3;
        x.losses = 2;
        final PlayerStats y = new PlayerStats(UUID.randomUUID(), "Ygor");
        y.wins = 10;
        y.losses = 1;
        final PlayerStats z = new PlayerStats(UUID.randomUUID(), "Zbyszko");
        z.wins = 10;
        z.losses = 4;
        final PlayerStats idle = new PlayerStats(UUID.randomUUID(), "Nikt");
        final List<PlayerStats> r = PlayerStats.ranking(List.of(z, idle, y, x));
        assertEquals(List.of(x, y, z), r, "titles first, then wins, then fewer losses; no-fight players hidden");
        assertEquals(91, y.winPercent());
        assertEquals(0, idle.winPercent());
        assertEquals(List.of(z), PlayerStats.page(r, 2, 2));
        assertEquals(List.of(z), PlayerStats.page(r, 99, 2), "page clamped");
        assertEquals(2, PlayerStats.pages(3, 2));
        assertEquals(1, PlayerStats.pages(0, 10));
    }
}
