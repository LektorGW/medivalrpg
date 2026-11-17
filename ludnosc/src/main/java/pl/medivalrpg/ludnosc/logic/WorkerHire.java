// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a player company (plugin Firmy, stage 3 manufactories) may hire: only an <b>unemployed</b> adult citizen of the
 * company's seat country (decision of the owner, 2026-10-29), the nearest loaded one. Pure and unit tested; the service
 * builds the candidates from the registry, the citizens' life (homes/work) and the loaded villagers.
 *
 * <p>Rules: children, citizens with a workplace (production job, workshop, construction or store helper), villagers
 * busy with a player (trading, on a lead, in a vehicle) are never taken. Among the rest the nearest in the same world
 * wins; villagers players traded with (experience/level above 1) are taken last, like in conscription, so trading halls
 * are protected. Ties are broken by id so the choice is deterministic.</p>
 */
public final class WorkerHire {

    /** Same penalties as {@code CitizenService#conscript}. */
    static final double OTHER_WORLD = 1e12;
    static final double TRADED = 1e15;

    private WorkerHire() {
    }

    /**
     * A loaded citizen of the seat country.
     *
     * @param distanceSq squared distance to the workshop (ignored for another world)
     * @param employed   has a workplace (citizens' life) or a production job (job board)
     * @param busy       trading with a player, leashed or riding
     */
    public record Candidate(UUID id, boolean adult, boolean sameWorld, double distanceSq, boolean traded, boolean employed,
                            boolean busy) {
    }

    /** The citizen to hire, empty when the country has no unemployed adult right now. */
    public static Optional<UUID> pick(final List<Candidate> candidates) {
        Candidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (final Candidate c : candidates) {
            if (c == null || c.id() == null || !c.adult() || c.employed() || c.busy()) {
                continue;
            }
            double score = c.sameWorld() ? Math.max(0, c.distanceSq()) : OTHER_WORLD;
            if (c.traded()) {
                score += TRADED;
            }
            if (best == null || score < bestScore || score == bestScore && c.id().toString().compareTo(best.id().toString()) < 0) {
                best = c;
                bestScore = score;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.id());
    }

    /**
     * Savings share of a citizen who leaves to work in a manufactory. The money never leaves the citizens' account
     * ({@code ludnosc:obywatele_<kraj>}; the company pays the wage into it too): the partner keeps it, else the children
     * in the country share it, else nobody gets it in the book and the daily reconciliation spreads it over all the
     * citizens of the country. Unlike a death no transfer to the treasury happens. Sums are preserved exactly.
     *
     * @param heirs partner (alone) or the children, in a stable order
     * @return credit per heir (empty: the share stays in the account for everybody)
     */
    public static Map<UUID, Long> shareOut(final long savings, final List<UUID> heirs) {
        final Map<UUID, Long> out = new LinkedHashMap<>();
        if (savings <= 0 || heirs == null || heirs.isEmpty()) {
            return out;
        }
        final List<UUID> ids = new ArrayList<>(heirs);
        final long each = savings / ids.size();
        long rest = savings % ids.size();
        for (final UUID id : ids) {
            out.merge(id, each + (rest-- > 0 ? 1 : 0), Long::sum);
        }
        return out;
    }
}
