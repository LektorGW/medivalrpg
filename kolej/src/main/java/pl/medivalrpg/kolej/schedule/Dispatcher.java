// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure dispatching decisions (dyspozytor): which train serves a departure, when to light the boiler ahead of it, how
 * late a running train is, and when a train should go to the depot. The Bukkit side feeds snapshots and executes.
 */
public final class Dispatcher {

    /** Snapshot of a train for dispatching. */
    public record Candidate(
        String id,
        String line,
        String atNode,
        boolean idle,
        boolean fire,
        boolean ready,
        boolean failed,
        double damage,
        double coalFraction,
        double waterFraction,
        boolean driverAvailable
    ) {
    }

    /** A planned stop of a trip. */
    public record PlannedStop(String node, LocalDateTime arrival, LocalDateTime departure) {
    }

    private Dispatcher() {
    }

    /**
     * Train for a departure of {@code line} from {@code firstStop}: an idle, non-failed train assigned to the line and
     * standing at the first stop, with a driver available; warm (ready) trains first, then the best supplied.
     */
    public static Optional<Candidate> pick(final String line, final String firstStop, final List<Candidate> trains) {
        return trains.stream()
            .filter(t -> line.equals(t.line()) && t.idle() && !t.failed() && t.driverAvailable() && firstStop.equals(t.atNode()))
            .min(Comparator.comparing((Candidate t) -> !t.ready())
                .thenComparing(t -> !t.fire())
                .thenComparingDouble(t -> -(t.coalFraction() + t.waterFraction())));
    }

    /** Whether to light the fire now for a departure at {@code departure} (warm-up time plus a safety margin). */
    public static boolean shouldLightFire(final LocalDateTime now, final LocalDateTime departure, final double warmupSeconds) {
        final long lead = (long) Math.ceil(warmupSeconds * 1.25) + 30;
        final Duration until = Duration.between(now, departure);
        return !until.isNegative() && until.getSeconds() <= lead;
    }

    /**
     * Planned arrival/departure at every stop: running time = distance / cruise speed (with a 15 % allowance for
     * acceleration and braking), plus the dwell time at intermediate stops.
     *
     * @param stopOffsets route positions (m) of the stops, the first is the origin (0)
     */
    public static List<PlannedStop> plan(final List<String> stops, final List<Double> stopOffsets, final LocalDateTime departure,
                                         final double cruiseSpeed, final int dwellSeconds) {
        final List<PlannedStop> out = new ArrayList<>();
        LocalDateTime t = departure;
        double last = 0;
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0) {
                final double seconds = (stopOffsets.get(i) - last) / Math.max(1, cruiseSpeed) * 1.15;
                t = t.plusSeconds((long) Math.ceil(seconds));
            }
            final LocalDateTime arrival = t;
            final boolean lastStop = i == stops.size() - 1;
            final LocalDateTime dep = i == 0 ? departure : lastStop ? arrival : arrival.plusSeconds(dwellSeconds);
            out.add(new PlannedStop(stops.get(i), i == 0 ? departure : arrival, dep));
            t = dep;
            last = stopOffsets.get(i);
        }
        return out;
    }

    /** Delay in seconds (positive = late) of an event against its plan. */
    public static long delaySeconds(final LocalDateTime planned, final LocalDateTime actual) {
        return Duration.between(planned, actual).getSeconds();
    }

    /**
     * Dwell at a stop: at least the timetable dwell, shortened (to a 15 s minimum) when the train is late, but always
     * long enough to finish refuelling when supplies are low.
     */
    public static int dwell(final int timetableDwell, final long delaySeconds, final boolean refuelling) {
        int d = timetableDwell;
        if (delaySeconds > 0) {
            d = (int) Math.max(15, timetableDwell - delaySeconds);
        }
        return refuelling ? Math.max(d, 20) : d;
    }

    /** True when a train should be sent to the depot (damage, or idle without a line at night). */
    public static boolean needsDepot(final Candidate t, final double repairThreshold) {
        return t.failed() || t.damage() >= repairThreshold;
    }
}
