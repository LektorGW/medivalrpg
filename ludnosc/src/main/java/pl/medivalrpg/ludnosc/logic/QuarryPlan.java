// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.List;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/** Finite, top-down quarry below its original pit. Each deeper bench leaves an extra access step on the north. */
public final class QuarryPlan {
    public record Cell(int x, int y, int z) { }

    private QuarryPlan() { }

    /** Inside the structure only '_' preserved terrain may be mined; explicit foundation/decor/air cells stay intact. */
    public static List<Cell> cells(final BlockBox box, final int minWorldY, final BuildingTemplate rotated) {
        if (rotated == null) {
            return List.of();
        }
        return cells(box, minWorldY).stream().filter(c -> rotated.state(c.x() - box.minX(), c.y() - box.minY(),
            c.z() - box.minZ()) == null).toList();
    }

    public static List<Cell> cells(final BlockBox box, final int minWorldY) {
        final List<Cell> out = new ArrayList<>();
        final int width = box.sizeX() - 6;
        final int length = box.sizeZ() - 6;
        if (width < 3 || length < 3) {
            return out;
        }
        final int depth = Math.min(10, length - 2);
        for (int d = 0; d < depth && box.minY() + 4 - d > minWorldY; d++) {
            // Mine the near edge first: the crew enters from the existing stairs, one bench at a time.
            for (int z = box.minZ() + 3 + d; z <= box.maxZ() - 3; z++) {
                for (int x = box.minX() + 3; x <= box.maxX() - 3; x++) {
                    out.add(new Cell(x, box.minY() + 4 - d, z));
                }
            }
        }
        return List.copyOf(out);
    }
}
