// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.medivalrpg.ludnosc.model.BlockBox;

/**
 * Areas water and lava must not flow into (active construction sites with their margin, mine galleries being dug,
 * underground works). Indexed by chunk, so the check for every {@code BlockFromToEvent} is a map lookup plus a few
 * box tests. Rebuilt wholesale by its owner (pure, unit tested; replaced atomically, read on the main thread).
 */
public final class FluidGuard {

    private final Map<String, Map<Long, List<BlockBox>>> index = new HashMap<>();

    private static long key(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void add(final BlockBox box) {
        final Map<Long, List<BlockBox>> w = this.index.computeIfAbsent(box.world(), k -> new HashMap<>());
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                w.computeIfAbsent(key(cx, cz), k -> new ArrayList<>(2)).add(box);
            }
        }
    }

    public boolean isEmpty() {
        return this.index.isEmpty();
    }

    /** True when a liquid must not flow into this block. */
    public boolean guarded(final String world, final int x, final int y, final int z) {
        final Map<Long, List<BlockBox>> w = this.index.get(world);
        if (w == null) {
            return false;
        }
        final List<BlockBox> boxes = w.get(key(x >> 4, z >> 4));
        if (boxes == null) {
            return false;
        }
        for (final BlockBox b : boxes) {
            if (b.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
