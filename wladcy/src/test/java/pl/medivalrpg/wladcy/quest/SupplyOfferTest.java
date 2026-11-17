// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.testutil.TestSupport;

/** Regression scenarios for settlement-driven urgency and the lifetime of a funded resource contract. */
class SupplyOfferTest {

    private static final UUID COUNTRY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final QuestConfig CFG = QuestGeneratorTest.cfg(Map.of(
        "zlecenia.offers-per-day", 1, "zlecenia.weights.dostawa", 0, "zlecenia.weights.transport", 0));

    private static QuestRules.AcceptFacts facts(final QuestType type) {
        return new QuestRules.AcceptFacts(true, RepTier.OBCY, type, false, 0, false, 0, 0, false,
            0, 0, 100_000, new QuestRules.RewardQuote(100, true));
    }

    private static List<String> needs(final Map<String, Long> stock) {
        return QuestGenerator.needs(stock, null, CFG.resources);
    }

    private static QuestData.Offer offer() {
        return QuestGenerator.generate(new QuestGenerator.Input(COUNTRY.toString(), "2026-10-28", 50,
            needs(Map.of("drewno", 0L)), List.of(), 0), CFG, () -> "supply1").getFirst();
    }

    @Test
    void unsupportedAndMalformedStocksCannotClaimUrgency() {
        assertTrue(needs(null).isEmpty());
        assertTrue(needs(Map.of()).isEmpty(), "the frozen API's default implementation returns an empty map");
        assertTrue(needs(Map.of("nieznany", 0L)).isEmpty());
        assertTrue(needs(Map.of("drewno", -1L)).isEmpty());
        final Map<String, Long> nullable = new HashMap<>();
        nullable.put("drewno", null);
        assertTrue(needs(nullable).isEmpty());
        nullable.put("kamien", 0L);
        assertEquals(List.of("kamien"), needs(nullable), "known zero remains a real shortage");
    }

    @Test
    void replenishmentLocksAnExistingDailyOfferBeforeAcceptance() {
        final QuestData.Offer offer = offer();
        assertTrue(offer.urgent);
        assertEquals("drewno", offer.need);
        assertTrue(QuestRules.canAccept(facts(offer.type()), CFG, offer, needs(Map.of("drewno", 0L))).isEmpty());
        final long target = CFG.resources.get("drewno").threshold();
        assertEquals(List.of("drewno"), needs(Map.of("drewno", target - 1)));
        assertEquals(Optional.of(QuestRules.Denial.NEED_CHANGED),
            QuestRules.canAccept(facts(offer.type()), CFG, offer, needs(Map.of("drewno", target))));
        assertEquals(Optional.of(QuestRules.Denial.NEED_CHANGED),
            QuestRules.canAccept(facts(offer.type()).withoutTiming(), CFG, offer, needs(Map.of("drewno", target))),
            "ruler tools and context use the same check even when the short cooldown is ignored");
    }

    @Test
    void urgencyNeedsTheSameResourceAndAWorkingProvider() {
        final QuestData.Offer offer = offer();
        for (final List<String> current : List.of(List.<String>of(), List.of("kamien"))) {
            assertEquals(Optional.of(QuestRules.Denial.NEED_CHANGED),
                QuestRules.canAccept(facts(offer.type()), CFG, offer, current));
        }
        assertTrue(QuestRules.canAccept(facts(offer.type()), CFG, offer, List.of("kamien", "drewno")).isEmpty());
    }

    @Test
    void configuredThresholdControlsTheLiveAcceptanceDecision() {
        final QuestData.Offer offer = offer();
        final QuestConfig changed = QuestGeneratorTest.cfg(Map.of("zlecenia.surowce.resources.drewno.threshold", 100));
        assertTrue(QuestRules.canAccept(facts(offer.type()), CFG, offer, needs(Map.of("drewno", 150L))).isEmpty());
        assertEquals(Optional.of(QuestRules.Denial.NEED_CHANGED), QuestRules.canAccept(facts(offer.type()), changed, offer,
            QuestGenerator.needs(Map.of("drewno", 150L), null, changed.resources)));
    }

    @Test
    void ordinaryResourceDeliveryAndTransportRemainAvailableWithoutNeeds() {
        final QuestData.Offer offer = offer();
        offer.urgent = false;
        assertTrue(QuestRules.canAccept(facts(offer.type()), CFG, offer, List.of()).isEmpty());
        for (final QuestType type : List.of(QuestType.DOSTAWA, QuestType.TRANSPORT)) {
            offer.type = type.key();
            offer.urgent = true;
            assertTrue(QuestRules.canAccept(facts(type), CFG, offer, List.of()).isEmpty());
        }
    }

    @Test
    void existingReputationAndBudgetDenialsStillTakePrecedence() {
        final QuestData.Offer offer = offer();
        final QuestRules.AcceptFacts poor = new QuestRules.AcceptFacts(true, RepTier.OBCY, offer.type(), false,
            0, false, 0, 0, false, 0, 0, 100_000, new QuestRules.RewardQuote(0, false));
        assertEquals(Optional.of(QuestRules.Denial.NO_FUNDS), QuestRules.canAccept(poor, CFG, offer, List.of()));
        final QuestRules.AcceptFacts enemy = new QuestRules.AcceptFacts(true, RepTier.WROG, offer.type(), false,
            0, false, 0, 0, false, 0, 0, 100_000, new QuestRules.RewardQuote(100, true));
        assertEquals(Optional.of(QuestRules.Denial.ENEMY), QuestRules.canAccept(enemy, CFG, offer, List.of()));
    }

    @Test
    void foodIsIndependentOfMaterialSnapshotsAndUsesStrictQuarterBoundary() {
        assertEquals(List.of("zywnosc"), QuestGenerator.needs(Map.of(),
            new QuestGenerator.Food(249, 1000, 0, 0), CFG.resources));
        assertTrue(QuestGenerator.needs(Map.of(), new QuestGenerator.Food(250, 1000, 0, 0), CFG.resources).isEmpty());
        assertEquals(List.of("zywnosc"), QuestGenerator.needs(null,
            new QuestGenerator.Food(900, 1000, -1, 0), CFG.resources));
        assertEquals(List.of("zywnosc"), QuestGenerator.needs(null,
            new QuestGenerator.Food(900, 1000, 1, 1), CFG.resources));
    }

    @Test
    void acceptedContractSurvivesReplenishmentAndReloadWithOneCompletion() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-28T10:00:00Z"));
        final QuestBook book = new QuestBook(clock, null);
        final QuestData.Offer offer = offer();
        book.putBoard(COUNTRY, List.of(offer));
        final QuestData.Active accepted = book.accept(PLAYER, "Jan", offer, "Osada", 100, true, "funded1");
        final Gson gson = new Gson();
        final QuestBook restored = new QuestBook(clock, gson.fromJson(gson.toJson(book.snapshot()), QuestData.class));
        final QuestData.Active active = restored.active(PLAYER).getFirst();
        assertTrue(needs(Map.of("drewno", 1000L)).isEmpty());
        assertEquals(accepted.amount, active.amount);
        assertEquals(100, active.reward);
        assertTrue(active.escrow);
        assertTrue(restored.complete(active, 100), "existing promises remain valid after the need disappears");
        assertFalse(restored.complete(active, 100));
        assertEquals(100, restored.paidToday(COUNTRY));
        assertEquals(1, restored.completedToday(PLAYER));
    }
}
