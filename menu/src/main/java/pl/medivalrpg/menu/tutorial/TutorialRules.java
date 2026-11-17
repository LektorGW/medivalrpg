// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.tutorial;

import java.util.List;
import java.util.Set;

/**
 * Which tutorial steps the game state proves. Pure, so it is unit-tested.
 *
 * <p>Step ids are the {@code progress:} values of the buttons of the {@code samouczek} menu. A step without a rule
 * here (e.g. {@code wlasne}, {@code pomoc}) is informational: it counts as done once the player has clicked it.</p>
 */
public final class TutorialRules {

    public static final String MONEY = "pieniadze";
    public static final String COUNTRY = "kraj";
    public static final String LAND = "ziemia";
    public static final String HOUSING = "mieszkania";
    public static final String FOOD = "jedzenie";
    public static final String BUILDING = "budowa";
    public static final String PRODUCTION = "produkcja";
    public static final String ARMY = "armia";

    /** Steps decided by the game state; only these keep the login reminder alive. */
    public static final Set<String> STATE_STEPS = Set.of(MONEY, COUNTRY, LAND, HOUSING, FOOD, BUILDING, PRODUCTION, ARMY);

    public static final String GRANARY = "spichlerz";
    public static final String BARRACKS = "koszary";
    public static final List<String> PRODUCTION_BUILDINGS = List.of("kopalnia", "tartak", "kamieniolom");
    /** Building types asked from Ludnosc ({@code LudnoscApi#hasBuilding}). */
    public static final List<String> WATCHED_BUILDINGS = List.of("kopalnia", "tartak", "kamieniolom", GRANARY, BARRACKS);

    private TutorialRules() {
    }

    /**
     * @param moneyGoal copper the player must own for the first step
     * @return {@code TRUE} done, {@code FALSE} not yet, {@code null} = the state cannot tell (informational step or
     *         the value could not be read)
     */
    public static Boolean done(final String step, final WorldState s, final long moneyGoal) {
        if (step == null || s == null || !STATE_STEPS.contains(step)) {
            return null;
        }
        if (step.equals(MONEY)) {
            // A member of a country has found the way to money or to people who have it.
            if (Boolean.TRUE.equals(s.hasCountry())) {
                return true;
            }
            return s.money() == null ? null : s.money() >= moneyGoal;
        }
        if (step.equals(COUNTRY)) {
            return s.hasCountry();
        }
        if (s.hasCountry() == null) {
            return null;
        }
        if (!s.hasCountry()) {
            return false; // every later step needs a country
        }
        return switch (step) {
            case LAND -> s.chunks() == null ? null : s.chunks() >= 2;
            case HOUSING -> s.housing() == null ? null : s.housing() > 0;
            case FOOD -> s.buildings() == null ? null : s.buildings().contains(GRANARY);
            case BUILDING -> s.construction();
            case PRODUCTION -> s.buildings() == null ? null : PRODUCTION_BUILDINGS.stream().anyMatch(s.buildings()::contains);
            case ARMY -> has(s, BARRACKS) || (s.soldiers() != null && s.soldiers() > 0) ? Boolean.TRUE
                : s.buildings() == null ? null : Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean has(final WorldState s, final String type) {
        return s.buildings() != null && s.buildings().contains(type);
    }
}
