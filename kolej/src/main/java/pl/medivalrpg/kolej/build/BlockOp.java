// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

/**
 * One block operation of a construction job.
 *
 * @param part  logical part (mapped to a block by the config), or {@link Part#RAW} with {@code data} = block data string
 * @param mode  how the block may be changed
 * @param data  block data for {@link Part#RAW} (e.g. {@code minecraft:oak_stairs[facing=east]}), else null
 */
public record BlockOp(int x, int y, int z, Part part, Mode mode, String data) {

    /** Logical parts; materials come from {@code budowa.materialy.*}. */
    public enum Part {
        AIR, BED, RAIL, FILL, BRIDGE_DECK, BRIDGE_PILLAR, BRIDGE_RAILING, TUNNEL_WALL, TUNNEL_CEILING, TUNNEL_LIGHT,
        ROAD, LAMP_POST, LAMP, SIGNAL_POST, SIGNAL_HEAD, PLATFORM, RAW
    }

    public enum Mode {
        /** Place, replacing air, fluids, plants and natural terrain (never man-made blocks). */
        PLACE,
        /** Remove natural terrain/plants (never man-made blocks) — an obstacle otherwise. */
        CLEAR,
        /** Place only where there is air, fluid or a replaceable plant (fills gaps, keeps terrain). */
        FILL
    }

    public static BlockOp place(final int x, final int y, final int z, final Part part) {
        return new BlockOp(x, y, z, part, Mode.PLACE, null);
    }

    public static BlockOp fill(final int x, final int y, final int z, final Part part) {
        return new BlockOp(x, y, z, part, Mode.FILL, null);
    }

    public static BlockOp clear(final int x, final int y, final int z) {
        return new BlockOp(x, y, z, Part.AIR, Mode.CLEAR, null);
    }

    public static BlockOp raw(final int x, final int y, final int z, final String data, final Mode mode) {
        return new BlockOp(x, y, z, Part.RAW, mode, data);
    }
}
