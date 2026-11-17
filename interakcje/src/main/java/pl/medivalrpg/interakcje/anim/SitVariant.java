// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import java.util.Locale;

/**
 * Which sitting animation fits the place: cross-legged on the floor, or "on a chair" with the legs
 * hanging down (stairs, a bottom slab, the edge of a ledge). Pure logic, unit-tested.
 */
public enum SitVariant {
    FLOOR("sit_floor"),
    CHAIR("sit_chair");

    private final String animation;

    SitVariant(String animation) {
        this.animation = animation;
    }

    /** Animation id in the catalogue. */
    public String animation() {
        return animation;
    }

    /** What the player sits on. */
    public enum Seat {
        GROUND, STAIRS, SLAB
    }

    /** {@code sit.variant} in config.yml. */
    public enum Mode {
        AUTO, FLOOR, CHAIR;

        public static Mode parse(String raw) {
            if (raw == null) {
                return AUTO;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    /**
     * @param seat        what the player sits on
     * @param ledgeAhead  on the ground: the block in front at foot level and the one below it are both
     *                    free (a drop of at least a block), so the legs can hang over the edge
     */
    public static SitVariant choose(Seat seat, boolean ledgeAhead, Mode mode) {
        if (mode == Mode.FLOOR) {
            return FLOOR;
        }
        if (mode == Mode.CHAIR) {
            return CHAIR;
        }
        if (seat == Seat.STAIRS || seat == Seat.SLAB) {
            return CHAIR;
        }
        return ledgeAhead ? CHAIR : FLOOR;
    }

    /**
     * Snaps a yaw to the nearest horizontal direction: 0 = south (+Z), 1 = west (-X), 2 = north (-Z),
     * 3 = east (+X) - the side of the block a sitter on a ledge faces.
     */
    public static int cardinal(float yaw) {
        float y = PairPlacement.normalizeYaw(yaw);
        int q = Math.round(y / 90f);
        return Math.floorMod(q, 4);
    }

    /** Unit (dx, dz) of {@link #cardinal}. */
    public static int[] cardinalOffset(int cardinal) {
        return switch (cardinal) {
            case 0 -> new int[]{0, 1};
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }
}
