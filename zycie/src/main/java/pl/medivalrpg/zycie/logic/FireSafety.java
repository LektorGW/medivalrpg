// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/** Safety rules shared by shelter and firefighter destination selection. Pure Java. */
public final class FireSafety {
    private FireSafety() { }

    public static boolean shelter(final double fireDistanceSquared, final boolean own, final boolean loaded,
                                  final boolean standable, final boolean dangerous, final double minimumDistance) {
        return own && loaded && standable && !dangerous
            && fireDistanceSquared >= minimumDistance * minimumDistance;
    }

    public static boolean mayFight(final boolean adult, final boolean busy, final boolean emptyHand,
                                   final int assigned, final int maximum) {
        return adult && !busy && emptyHand && assigned < maximum;
    }

    public static boolean wakeSleeper(final boolean sleeping,final double nearestFireSquared,final boolean burning) {
        return sleeping && (burning || nearestFireSquared<=64);
    }

    /**
     * Fire on this base burns forever by design (fireplaces, braziers on netherrack / soul sand / soul soil, the
     * vanilla flame on bedrock): decoration, never a town fire. {@code material} = Bukkit material name.
     */
    public static boolean eternalBase(final String material) {
        return switch (material) {
            case "NETHERRACK", "SOUL_SAND", "SOUL_SOIL", "MAGMA_BLOCK", "BEDROCK" -> true;
            default -> false;
        };
    }

    public static boolean mayExtinguish(final double distanceSquared,final boolean own,final boolean flame,final boolean visible) {
        return distanceSquared<=16 && own && flame && visible;
    }
}
