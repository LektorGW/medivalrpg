// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/**
 * Which blocks inside a planned footprint refuse an order (pure, unit tested).
 *
 * <p>The builders excavate every template cell (air included) and the earthworks clear natural ground over the plot, so
 * natural terrain (earth, stone, sand, leaves, plants, snow) is never a reason to send a player with a shovel. A cut
 * limit stays only for the automatic planner of NPC countries, which must not dig its towns into mountains. Built
 * blocks (logs, planks, any player structure) always refuse: they remain protected.
 */
public final class TerrainObstruction {

    /** What a world block inside the footprint is. */
    public enum Cell { FREE, NATURAL, BUILT }

    /** Smallest cut allowed to the automatic planner, whatever the config says. */
    public static final int MIN_AUTOMATIC_CUT = 6;

    private TerrainObstruction() {
    }

    /**
     * Highest y at which natural terrain is still cut by the builders.
     *
     * @param automatic the order comes from the automatic planner of an NPC country
     */
    public static int cutLimit(final int groundY, final int configuredCut, final boolean automatic) {
        if (!automatic) {
            return Integer.MAX_VALUE;
        }
        return groundY + Math.max(configuredCut, MIN_AUTOMATIC_CUT);
    }

    /** Does this cell refuse the order? */
    public static boolean blocks(final Cell cell, final int y, final int cutLimit) {
        return cell == Cell.BUILT || cell == Cell.NATURAL && y > cutLimit;
    }

    /** Polish reason shown to the player: the first obstruction and how many there are in the footprint. */
    public static String reason(final String material, final int x, final int y, final int z, final Cell cell, final int count) {
        final String more = count > 1 ? " (w obrysie łącznie " + count + " takich bloków)" : "";
        if (cell == Cell.NATURAL) {
            return "Teren (" + material + " w " + x + " " + y + " " + z + ") wznosi się za wysoko nad podłogą budynku" + more
                + ". Przesuń budynek albo obniż teren.";
        }
        return "Blok " + material + " w " + x + " " + y + " " + z + " blokuje budowę" + more
            + ". Przeszkoda pozostaje chroniona; usuń ją ręcznie albo przesuń budynek.";
    }
}
