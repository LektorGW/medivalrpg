// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.UUID;

/**
 * Life of one citizen beyond the registry record ({@link CitizenRecord}): permanent home and bed, workplace, savings
 * share and family. Kept in {@code plugins/Ludnosc/zycie-obywateli.json}, keyed by the citizen's entity id (moved to
 * the real id when a pending citizen materialises). Every field is optional: citizens of old saves simply have no
 * entry yet and get one at the next assignment. Main thread only.
 */
public final class CitizenLife {

    /** Country whose citizens' account holds {@link #savings}. */
    public UUID country;
    /** Housing building and bed index in it (-1 = none). */
    public UUID home;
    public int bed = -1;
    /** Workplace (building or construction site) and its kind ({@code Workplaces.Kind#key}). */
    public UUID workplace;
    public String work;
    /** Share of the citizens' account ({@code ludnosc:obywatele_<kraj>}), md. */
    public long savings;
    /** Last wage paid (md), the wage owed that day and consecutive days without full pay. */
    public long lastWage;
    public long lastDue;
    public int unpaidDays;
    public long earned;
    public long spent;
    /** Family. */
    public UUID partner;
    public UUID mother;
    public UUID father;
    /** Birth (epoch millis) of children born in the country; 0 = unknown (arrived as an adult). */
    public long born;
    /** Last child born to this citizen (epoch millis), children count. */
    public long lastChild;
    public int children;
    /**
     * Age: start of the life (epoch millis) and its length in real days, drawn once (0 = not drawn yet — old saves get
     * a random safe age once, then it is kept).
     */
    public long lifeStart;
    public long lifespan;

    public CitizenLife() {
    }

    public static CitizenLife of(final UUID country) {
        final CitizenLife l = new CitizenLife();
        l.country = country;
        return l;
    }

    /** Points every reference to {@code from} at {@code to} (a pending citizen got its real entity). */
    public void rekey(final UUID from, final UUID to) {
        if (from.equals(this.partner)) {
            this.partner = to;
        }
        if (from.equals(this.mother)) {
            this.mother = to;
        }
        if (from.equals(this.father)) {
            this.father = to;
        }
    }
}
