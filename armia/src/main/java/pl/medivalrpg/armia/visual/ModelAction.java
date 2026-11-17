// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

/**
 * One animation command for a model. Priorities decide which clip a bone shows when several run at once
 * (BetterModel keeps one iterator per clip on every bone below an animated one): waves are the background,
 * sails/anchor/engine clips sit above them, cloth flutter and wheel roll are on top.
 */
public record ModelAction(boolean start, String animation, Kind kind, float speed, int priority) {
    public enum Kind { LOOP, ONCE, HOLD }
    /** Speed taken from the live supplier of the model (wheel roll follows the real ground speed). */
    public static final float LIVE_SPEED = -1;

    public static ModelAction start(final String animation, final Kind kind, final float speed, final int priority) {
        return new ModelAction(true, animation, kind, speed, priority);
    }

    public static ModelAction stop(final String animation) {
        return new ModelAction(false, animation, Kind.ONCE, 0, 0);
    }

    public static int priority(final String animation) {
        return switch (animation) {
            case ModelCatalog.WAVES -> 0;
            case ModelCatalog.FLAGS -> 15;
            case ModelCatalog.FLUTTER -> 20;
            case ModelCatalog.ROLL -> 30;
            default -> 10;
        };
    }
}
