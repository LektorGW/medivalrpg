// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import pl.medivalrpg.armia.api.NpcStance;

/**
 * Wave 17 relations seen by the NPC AI: personal unions, embassies and consent for railway infrastructure. Pure.
 *
 * <p>A union partner is never a war target (Kraje refuses such a war anyway — before, the AI retried and spammed the
 * log). An embassy in either direction is a lasting sign of good will and adds {@link #EMBASSY_OPINION} to the opinion
 * used in war/alliance choices. Answers depend on personality, opinion and stance, never on randomness, so a player
 * gets the same answer for the same situation.</p>
 */
public final class RelationBonds {

    public static final int EMBASSY_OPINION = 10;
    public static final int UNION_OPINION = 40;

    private RelationBonds() {
    }

    /** Opinion used by the AI's choices: base + embassy/union bonus, clamped to −100..100. */
    public static int effectiveOpinion(final int base, final boolean union, final boolean embassy) {
        int v = base;
        if (union) {
            v += UNION_OPINION;
        } else if (embassy) {
            v += EMBASSY_OPINION;
        }
        return Math.max(-100, Math.min(100, v));
    }

    /**
     * Should an NPC country accept a personal union under {@code proposer}'s ruler? Only a friendly, diplomatic and not
     * proud country agrees: score = opinion·0.5 + dyplomacja·0.3 + 20 (alliance) + strength bonus − agresja·0.25,
     * threshold 55. War, hostile stance or a preferred target always refuse; {@code nigdy_wojny} alone does not accept.
     *
     * @param theirStrengthRatio proposer strength / our strength
     */
    public static boolean acceptUnion(final Personality p, final int opinion, final boolean atWar, final boolean allied,
                                      final double theirStrengthRatio, final NpcStance stance) {
        return unionScore(p, opinion, atWar, allied, theirStrengthRatio, stance) >= 55.0;
    }

    public static double unionScore(final Personality p, final int opinion, final boolean atWar, final boolean allied,
                                    final double theirStrengthRatio, final NpcStance stance) {
        // A union makes the NPC country defend the proposer's wars: only an existing ally may ask.
        if (atWar || !allied || stance == NpcStance.HOSTILE || stance == NpcStance.PREFERRED_TARGET || p.diplomacy() < 40) {
            return -100.0;
        }
        final double ratio = Double.isFinite(theirStrengthRatio) ? Math.max(0.0, Math.min(3.0, theirStrengthRatio)) : 1.0;
        double s = opinion * 0.5 + p.diplomacy() * 0.3 + (allied ? 20.0 : 0.0) + (ratio - 1.0) * 10.0 - p.aggression() * 0.25;
        if (stance == NpcStance.FRIENDLY) {
            s += 15.0;
        }
        return s;
    }

    /** Repeated union/embassy requests of the same country within this many minutes are refused without deliberation. */
    public static final long REQUEST_COOLDOWN_MINUTES = 60;

    /** Whether a new request of the same proposer may be considered (null = never asked). */
    public static boolean requestCooldownOver(final Long lastAnsweredAt, final long now) {
        return lastAnsweredAt == null || now - lastAnsweredAt >= REQUEST_COOLDOWN_MINUTES * 60_000L;
    }

    /** The NPC partner leaves a union whose monarch's country it came to resent (no minimum duration for the AI). */
    public static boolean leaveUnion(final int rawOpinionOfHome, final Personality p) {
        return rawOpinionOfHome <= -20 - p.diplomacy() / 5;
    }

    /** Should an NPC host accept an embassy of {@code guest}? Never in war or with a hostile/target stance. */
    public static boolean acceptEmbassy(final Personality p, final int opinion, final boolean atWar, final boolean allied, final NpcStance stance) {
        if (atWar || stance == NpcStance.HOSTILE || stance == NpcStance.PREFERRED_TARGET) {
            return false;
        }
        if (allied || stance == NpcStance.FRIENDLY) {
            return true;
        }
        // Diplomatic countries open up more easily; grudges close the door.
        return opinion >= 25 - p.diplomacy() / 2;
    }

    /** Answer of an NPC host to a railway infrastructure/passage request. */
    public enum RailAnswer { ACCEPT, ACCEPT_FEE, REFUSE }

    /** Decision with the fee (copper) asked for {@link RailAnswer#ACCEPT_FEE}, and a Polish reason for the log. */
    public record RailDecision(RailAnswer answer, long fee, String reason) {
    }

    /**
     * Host consent for railway infrastructure or passage (decision 2026-10-27: no alliance needed). Never blocks forever:
     * the answer is immediate. War/hostile → refuse; ally, union, embassy or good opinion → free; otherwise a fee that
     * grows with distrust and greed (bogactwo), so a neutral neighbour can always buy passage.
     *
     * @param baseFee fee at neutral opinion, copper
     */
    public static RailDecision railConsent(final Personality p, final int opinion, final boolean atWar, final boolean allied,
                                           final boolean union, final boolean embassy, final NpcStance stance, final long baseFee) {
        if (atWar) {
            return new RailDecision(RailAnswer.REFUSE, 0, "wojna z wnioskodawcą");
        }
        if (stance == NpcStance.HOSTILE || stance == NpcStance.PREFERRED_TARGET) {
            return new RailDecision(RailAnswer.REFUSE, 0, "wrogie nastawienie");
        }
        final int eff = effectiveOpinion(opinion, union, embassy);
        if (union || allied || stance == NpcStance.FRIENDLY || eff >= 30) {
            return new RailDecision(RailAnswer.ACCEPT, 0, union ? "unia personalna" : allied ? "sojusz" : "dobre stosunki");
        }
        if (eff <= -60) {
            return new RailDecision(RailAnswer.REFUSE, 0, "głęboka uraza (opinia " + eff + ")");
        }
        final double distrust = 1.0 + Math.max(0, 30 - eff) / 30.0;  // 1.0 .. 4.0
        final double greed = 0.5 + p.wealth() / 100.0;             // 0.5 .. 1.5
        final long fee = Math.max(0, Math.round(Math.max(0, baseFee) * distrust * greed));
        return new RailDecision(fee == 0 ? RailAnswer.ACCEPT : RailAnswer.ACCEPT_FEE, fee, "opłata za przejazd (opinia " + eff + ")");
    }
}
