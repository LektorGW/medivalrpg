// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Reward quotes (reputation multiplier, treasury caps, minted money) and who may accept what. */
class QuestRulesTest {

    private static final QuestConfig CFG = QuestGeneratorTest.cfg(Map.of());
    private static final QuestRules.RewardQuote OK = new QuestRules.RewardQuote(100, true);
    private static final long NOW = 1_000_000_000L;

    private static QuestRules.AcceptFacts facts(final RepTier tier, final QuestType type, final boolean premium) {
        return new QuestRules.AcceptFacts(true, tier, type, premium, 0, false, 0, 0, false, 0, 0, NOW, OK);
    }

    @Test
    void quoteAppliesReputationAndTreasuryCaps() {
        // Treasury 100 zł: 3% = 300 md per quest, 10% = 1000 md per day.
        final long treasury = 10_000;
        assertEquals(new QuestRules.RewardQuote(200, true), QuestRules.quote(200, RepTier.OBCY, CFG, treasury, 0));
        assertEquals(new QuestRules.RewardQuote(160, true), QuestRules.quote(200, RepTier.NIEUFNY, CFG, treasury, 0), "×0.8");
        assertEquals(new QuestRules.RewardQuote(300, true), QuestRules.quote(200, RepTier.BOHATER, CFG, treasury, 0), "×1.5");
        assertEquals(new QuestRules.RewardQuote(300, true), QuestRules.quote(1000, RepTier.OBCY, CFG, treasury, 0), "3% of the treasury per quest");
        assertEquals(new QuestRules.RewardQuote(100, true), QuestRules.quote(1000, RepTier.OBCY, CFG, treasury, 900), "what is left of today's 10%");
        assertFalse(QuestRules.quote(1000, RepTier.OBCY, CFG, treasury, 990).affordable(), "10 md < reward.min");
        assertFalse(QuestRules.quote(200, RepTier.OBCY, CFG, 0, 0).affordable(), "empty treasury");
        assertFalse(QuestRules.quote(200, RepTier.WROG, CFG, treasury, 0).affordable(), "enemies get nothing");
        final QuestConfig mint = QuestGeneratorTest.cfg(Map.of("zlecenia.reward.create-money", true));
        assertFalse(mint.createMoney, "closed money pool: create-money is ignored");
        assertTrue(mint.createMoneyIgnored);
        assertFalse(QuestRules.quote(1000, RepTier.PRZYJACIEL, mint, 0, 99_999).affordable(), "no reward without treasury funds");
        // Reserve for wages and upkeep: 10 000 md, wages 1 000/day ×2 + upkeep 200 ×2 = 2 400 kept.
        assertEquals(7_600, QuestRules.spendable(10_000, 1_000, 200, 2, 500));
        assertEquals(0, QuestRules.spendable(400, 0, 0, 2, 500), "never below the minimum reserve");
        assertEquals(new QuestRules.RewardQuote(228, true), QuestRules.quote(1000, RepTier.OBCY, CFG, QuestRules.spendable(10_000, 1_000, 200, 2, 500), 0),
            "3% of what is above the reserve");
    }

    @Test
    void reputationGatesOffers() {
        assertEquals(Optional.of(QuestRules.Denial.ENEMY), QuestRules.visible(RepTier.WROG, QuestType.DOSTAWA, false));
        assertTrue(QuestRules.visible(RepTier.NIEUFNY, QuestType.DOSTAWA, false).isEmpty());
        assertEquals(Optional.of(QuestRules.Denial.TIER_TYPE), QuestRules.visible(RepTier.NIEUFNY, QuestType.SUROWCE, false));
        assertEquals(Optional.of(QuestRules.Denial.TIER_TYPE), QuestRules.visible(RepTier.NIEUFNY, QuestType.TRANSPORT, false));
        assertTrue(QuestRules.visible(RepTier.OBCY, QuestType.TRANSPORT, false).isEmpty());
        assertEquals(Optional.of(QuestRules.Denial.PREMIUM), QuestRules.visible(RepTier.OBCY, QuestType.DOSTAWA, true));
        assertTrue(QuestRules.visible(RepTier.ZNAJOMY, QuestType.DOSTAWA, true).isEmpty());
    }

    @Test
    void acceptDenialsInOrder() {
        assertTrue(QuestRules.canAccept(facts(RepTier.OBCY, QuestType.DOSTAWA, false), CFG).isEmpty());
        assertEquals(Optional.of(QuestRules.Denial.DISABLED), QuestRules.canAccept(
            new QuestRules.AcceptFacts(false, RepTier.OBCY, QuestType.DOSTAWA, false, 0, false, 0, 0, false, 0, 0, NOW, OK), CFG));
        assertEquals(Optional.of(QuestRules.Denial.ACTIVE_HERE), QuestRules.canAccept(
            new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, 1, true, 0, 0, true, 0, 0, NOW, OK), CFG));
        assertEquals(Optional.of(QuestRules.Denial.USED), QuestRules.canAccept(
            new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, 0, false, 0, 0, true, 0, 0, NOW, OK), CFG));
        assertEquals(Optional.of(QuestRules.Denial.COOLDOWN), QuestRules.canAccept(
            new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, 0, false, 0, 0, false, NOW + 1, 0, NOW, OK), CFG));
        assertEquals(Optional.of(QuestRules.Denial.MAX_ACTIVE), QuestRules.canAccept(
            new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, CFG.maxActive, false, 0, 0, false, 0, 0, NOW, OK), CFG));
        assertEquals(Optional.of(QuestRules.Denial.DAILY), QuestRules.canAccept(
            new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, 0, false, CFG.maxCompletedPerDay, 0, false, 0, 0, NOW, OK), CFG));
        assertEquals(Optional.of(QuestRules.Denial.COUNTRY_DAILY), QuestRules.canAccept(
            new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, 0, false, 0, CFG.maxPerCountryPerDay, false, 0, 0, NOW, OK), CFG));
        final QuestRules.AcceptFacts fast = new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA, false, 0, false, 0, 0, false, 0,
            NOW - 2_000, NOW, OK);
        assertEquals(Optional.of(QuestRules.Denial.TOO_FAST), QuestRules.canAccept(fast, CFG));
        assertTrue(QuestRules.canAccept(fast.withoutTiming(), CFG).isEmpty(), "listing ignores the short cooldown");
        assertEquals(Optional.of(QuestRules.Denial.NO_FUNDS), QuestRules.canAccept(new QuestRules.AcceptFacts(true, RepTier.OBCY, QuestType.DOSTAWA,
            false, 0, false, 0, 0, false, 0, 0, NOW, new QuestRules.RewardQuote(5, false)), CFG));
        assertEquals(Optional.of(QuestRules.Denial.ENEMY), QuestRules.canAccept(facts(RepTier.WROG, QuestType.DOSTAWA, false), CFG),
            "reputation is checked before limits");
        for (final QuestRules.Denial d : QuestRules.Denial.values()) {
            assertFalse(d.forModel().isBlank());
            assertTrue(d.key().matches("[a-z-]+"));
        }
    }

    @Test
    void expiryPlacesAndDurations() {
        final QuestData.Active a = new QuestData.Active();
        a.deadline = QuestRules.deadline(NOW, 90);
        assertEquals(NOW + 90 * 60_000L, a.deadline);
        assertFalse(QuestRules.expired(a, a.deadline - 1));
        assertTrue(QuestRules.expired(a, a.deadline));
        assertEquals("1 h 30 min", QuestRules.duration(90 * 60_000L));
        assertEquals("2 h", QuestRules.duration(120 * 60_000L));
        assertEquals("45 min", QuestRules.duration(45 * 60_000L + 5_000));
        assertEquals("mniej niż minuta", QuestRules.duration(-5));
        assertTrue(QuestRules.nearCapital(12, -3, 10, -2, 2));
        assertFalse(QuestRules.nearCapital(13, -3, 10, -2, 2));
        assertEquals(160, QuestRules.chunkDistance(0, 0, 6, 8), "6×16, 8×16 → 160 blocks");
        assertEquals(CFG.minReward, QuestRules.clampReward(-50, CFG));
        assertEquals(CFG.maxReward, QuestRules.clampReward(Long.MAX_VALUE, CFG));
    }

    @Test
    void reputationEffectsFromConfig() {
        assertEquals(0.0, CFG.rewardMultiplier(RepTier.WROG));
        assertEquals(1.5, CFG.rewardMultiplier(RepTier.BOHATER));
        assertEquals(0.0, CFG.giftMultiplier(RepTier.WROG));
        assertEquals(2.0, CFG.giftMultiplier(RepTier.BOHATER));
        assertTrue(CFG.repRefuse < 0 && CFG.repAbandon < CFG.repRefuse && CFG.repFail <= CFG.repAbandon, "fail hurts most, refusal least");
        assertEquals(15, CFG.repDailyGainCap);
        assertFalse(CFG.createMoney, "never create money by default");
        final QuestConfig clamped = QuestGeneratorTest.cfg(Map.of("zlecenia.offers-per-day", 99, "reputacja.refuse", 5,
            "reputacja.reward-multiplier.bohater", 50.0, "zlecenia.limits.max-active", 0));
        assertEquals(12, clamped.offersPerDay);
        assertEquals(0, clamped.repRefuse, "a refusal can never raise reputation");
        assertEquals(10.0, clamped.rewardMultiplier(RepTier.BOHATER));
        assertEquals(1, clamped.maxActive);
    }
}
