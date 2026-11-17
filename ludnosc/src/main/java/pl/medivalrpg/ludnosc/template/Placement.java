// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;

/**
 * A (rotated) template placed in the world: footprint centred on the origin column, ground layer at the origin's y.
 * Pure geometry.
 */
public record Placement(String world, BuildingTemplate template, int minX, int minY, int minZ) {

    public static Placement centered(final String world, final BuildingTemplate rotated, final int originX,
                                     final int originY, final int originZ) {
        return new Placement(world, rotated, originX - rotated.width() / 2, originY - rotated.meta().groundLayer(),
            originZ - rotated.depth() / 2);
    }

    public BlockBox box() {
        return new BlockBox(this.world, this.minX, this.minY, this.minZ, this.minX + this.template.width() - 1,
            this.minY + this.template.height() - 1, this.minZ + this.template.depth() - 1);
    }

    /** World y of the ground layer (= origin y). */
    public int groundY() {
        return this.minY + this.template.meta().groundLayer();
    }

    public int x(final int index) {
        return this.minX + this.template.xOf(index);
    }

    public int y(final int index) {
        return this.minY + this.template.yOf(index);
    }

    public int z(final int index) {
        return this.minZ + this.template.zOf(index);
    }

    public BlockPos toWorld(final int[] local) {
        return new BlockPos(this.world, this.minX + local[0], this.minY + local[1], this.minZ + local[2]);
    }
}
