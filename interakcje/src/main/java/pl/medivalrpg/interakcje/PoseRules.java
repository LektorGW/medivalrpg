// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.block.BlockFace;

/** Pure rules for sitting / lying (no server access; unit-tested). */
public final class PoseRules {
    /** Lying ends when the player moves more than this horizontally (blocks). */
    public static final double LAY_MAX_HORIZONTAL = 0.25;
    /** ...or more than this vertically (jump / fall). */
    public static final double LAY_MAX_VERTICAL = 0.6;

    private PoseRules() {
    }

    /** Snapshot of what matters before taking a pose. */
    public record State(boolean riding, boolean onGround, boolean inWater, boolean flyingOrGliding,
                        boolean standingPose, boolean groundSolid, boolean headroom) {
    }

    /** @return message key of the reason the pose is not allowed, or {@code null} when it is. */
    public static String check(State s) {
        if (s.riding) {
            return "pose-riding";
        }
        if (s.inWater) {
            return "pose-water";
        }
        if (s.flyingOrGliding || !s.onGround || !s.groundSolid) {
            return "pose-air";
        }
        if (!s.standingPose) {
            return "pose-busy";
        }
        if (!s.headroom) {
            return "pose-headroom";
        }
        return null;
    }

    /** Did a lying player move enough to stand up? */
    public static boolean movedTooFar(double dx, double dy, double dz) {
        return dx * dx + dz * dz > LAY_MAX_HORIZONTAL * LAY_MAX_HORIZONTAL || Math.abs(dy) > LAY_MAX_VERTICAL;
    }

    /**
     * Yaw a player sitting on stairs should face: away from the high step. In Minecraft, stairs with
     * {@code facing=north} rise towards the north, so you sit facing south.
     */
    public static float stairsSitYaw(BlockFace stairsFacing) {
        return yaw(stairsFacing.getOppositeFace());
    }

    /** Bukkit yaw of a horizontal block face (south = 0, west = 90, north = 180, east = -90). */
    public static float yaw(BlockFace face) {
        return switch (face) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }
}
