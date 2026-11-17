// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/**
 * Decides the destination of one citizen (pure function of the situation and a stable per-villager random value).
 *
 * @param noonMarketShare share of the citizens going to the market at noon
 * @param tavernShare     share going to the tavern in the evening (the rest goes to the bell square)
 * @param massShare       share attending the Sunday mass
 * @param protestShare    share joining a protest
 * @param festivalShare   share celebrating at the festival
 * @param eveningHome     the citizens who do not go to the tavern go home in the evening (else to the bell square)
 */
public record DailyPlan(double noonMarketShare, double tavernShare, double massShare, double protestShare,
                        double festivalShare, boolean eveningHome) {

    /** Plan of older configs: the evening at the bell square. */
    public DailyPlan(final double noonMarketShare, final double tavernShare, final double massShare, final double protestShare,
                     final double festivalShare) {
        this(noonMarketShare, tavernShare, massShare, protestShare, festivalShare, false);
    }

    public static DailyPlan defaults() {
        return new DailyPlan(0.3, 0.5, 0.8, 0.6, 0.7, true);
    }

    /**
     * Situation of a citizen.
     *
     * @param phase    part of the day
     * @param adult    babies always follow vanilla
     * @param shelter  siege nearby or fleeing from an enemy
     * @param militia  part of the levy (stands at the gate)
     * @param festival a festival is held in the country
     * @param protest  the citizens protest
     * @param share    stable random value of this villager for today, 0..1
     */
    public record Situation(DayPhase phase, boolean adult, boolean shelter, boolean militia, boolean festival,
                            boolean protest, double share) {
    }

    public Destination destination(final Situation s) {
        if (s.shelter()) {
            return Destination.SHELTER;
        }
        if (!s.adult()) {
            return Destination.NONE;
        }
        if (s.militia()) {
            return Destination.GATE;
        }
        if (s.phase() == DayPhase.NIGHT) {
            return Destination.NONE;
        }
        if (s.festival() && s.share() < this.festivalShare) {
            return Destination.FESTIVAL;
        }
        if (s.protest() && s.phase() != DayPhase.MORNING && s.share() < this.protestShare) {
            return Destination.PROTEST;
        }
        return switch (s.phase()) {
            case MASS -> s.share() < this.massShare ? Destination.CHURCH : Destination.NONE;
            case WORK -> Destination.WORKPLACE;
            case NOON -> s.share() < this.noonMarketShare ? Destination.MARKET : Destination.WORKPLACE;
            case EVENING -> s.share() < this.tavernShare ? Destination.TAVERN : this.eveningHome ? Destination.HOME : Destination.BELL;
            case MORNING, MEET, NIGHT -> Destination.NONE;
        };
    }
}
