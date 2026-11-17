// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/** Lighting follows actual gallery length and actual blocks, never an unpersisted runtime timer. */
public final class MineLighting {
    private MineLighting() { }
    public static boolean due(int oldLength, int newLength, int spacing, boolean alreadyLit) {
        int gap = Math.max(2, Math.min(32, spacing));
        return !alreadyLit && newLength > oldLength && (oldLength == 0 || oldLength / gap < newLength / gap);
    }
    public static String wallState(String supportDirection) {
        return "minecraft:wall_torch[facing=" + switch (supportDirection) {
            case "north" -> "south"; case "south" -> "north"; case "east" -> "west"; case "west" -> "east";
            default -> throw new IllegalArgumentException("direction");
        } + "]";
    }
    public static boolean canPlace(boolean air, boolean stableFullFace, boolean protectedSupport, boolean oreSupport) {
        return air && stableFullFace && !protectedSupport && !oreSupport;
    }
}
