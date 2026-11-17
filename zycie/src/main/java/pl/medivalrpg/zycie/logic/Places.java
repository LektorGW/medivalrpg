// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Destination} into an ordered list of places to try (pure). The service takes the first place that
 * exists for the villager: a building of the listed types (Ludnosc), the villager's vanilla job site, the bell (its
 * vanilla meeting point) or the capital centre.
 *
 * @param market types of the market (noon)
 * @param tavern types of the tavern (evening)
 * @param church types of the church (Sunday mass)
 * @param square types of the town square (festival, protest; bell fallback)
 * @param gate   types of the gate (levy during a siege of the capital)
 */
public record Places(List<String> market, List<String> tavern, List<String> church, List<String> square, List<String> gate) {

    public enum Kind {
        /** Nearest building of {@link Place#tokens()}. */
        BUILDINGS,
        /** The villager has a vanilla workstation: let vanilla walk there (no steering). */
        JOB_SITE,
        /** The villager's meeting point (bell). */
        BELL,
        /** The country's festival / protest spot (square next to the capital). */
        SQUARE_SPOT,
        /** Centre of the capital chunk. */
        CENTER,
        /** The citizen's permanent workplace from Ludnosc (PDC {@code ludnosc:workplace}). */
        LUDNOSC_WORK,
        /** The citizen's home / bed from Ludnosc (PDC {@code ludnosc:home}). */
        LUDNOSC_HOME
    }

    public record Place(Kind kind, List<String> tokens) {

        static Place of(final Kind kind) {
            return new Place(kind, List.of());
        }
    }

    public static Places defaults() {
        return new Places(List.of("targ", "rynek"), List.of("karczma", "browar"), List.of("katedra", "kosciol"),
            List.of("rynek", "targ", "ratusz", "studnia"), List.of("brama", "mur", "wieza"));
    }

    public List<Place> candidates(final Destination d, final List<String> jobBuildings) {
        final List<Place> out = new ArrayList<>();
        switch (d) {
            case WORKPLACE -> {
                out.add(Place.of(Kind.LUDNOSC_WORK));
                if (jobBuildings != null && !jobBuildings.isEmpty()) {
                    out.add(new Place(Kind.BUILDINGS, jobBuildings));
                }
                out.add(Place.of(Kind.JOB_SITE));
                out.add(Place.of(Kind.BELL));
                out.add(Place.of(Kind.CENTER));
            }
            case MARKET -> {
                out.add(new Place(Kind.BUILDINGS, this.market));
                out.add(Place.of(Kind.BELL));
                out.add(Place.of(Kind.CENTER));
            }
            case TAVERN -> {
                out.add(new Place(Kind.BUILDINGS, this.tavern));
                out.add(Place.of(Kind.BELL));
                out.add(Place.of(Kind.CENTER));
            }
            case BELL -> {
                out.add(Place.of(Kind.BELL));
                out.add(new Place(Kind.BUILDINGS, this.square));
                out.add(Place.of(Kind.CENTER));
            }
            case CHURCH -> {
                out.add(new Place(Kind.BUILDINGS, this.church));
                out.add(Place.of(Kind.BELL));
                out.add(Place.of(Kind.CENTER));
            }
            case HOME -> {
                out.add(Place.of(Kind.LUDNOSC_HOME));
                out.add(Place.of(Kind.BELL));
            }
            case FESTIVAL, PROTEST -> out.add(Place.of(Kind.SQUARE_SPOT));
            case GATE -> {
                out.add(new Place(Kind.BUILDINGS, this.gate));
                out.add(Place.of(Kind.SQUARE_SPOT));
            }
            case NONE, SHELTER -> {
                // vanilla
            }
        }
        return out;
    }
}
