// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

/**
 * A plot the settlement planner must not propose again for a while: the order there was refused or the site was
 * cancelled because of an obstruction (kept in {@link CountryState#plannerAvoid}; older versions ignore it).
 *
 * @param until  epoch millis
 * @param reason Polish, for {@code /ludnosc budowy} and the log
 */
public record AvoidSpot(String world, String type, int minX, int minZ, int maxX, int maxZ, long until, String reason) {

    public boolean samePlace(final AvoidSpot o) {
        return o != null && java.util.Objects.equals(this.world, o.world) && java.util.Objects.equals(this.type, o.type)
            && this.minX == o.minX && this.minZ == o.minZ && this.maxX == o.maxX && this.maxZ == o.maxZ;
    }
}
