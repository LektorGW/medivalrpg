// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import pl.medivalrpg.kraje.model.ChunkPos;

/** Bounded shore check: at most 256 target columns and their immediate neighbours, loaded chunks only. */
public final class LandingCoast {
    public enum Surface { DRY, WATER, OTHER, UNKNOWN }
    public record Column(int height,Surface surface) { }
    @FunctionalInterface public interface ShoreView { Column column(int x,int z); }
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private LandingCoast() { }

    public static boolean coastal(final World world, final ChunkPos pos) {
        if (world==null || !world.isChunkLoaded(pos.x(),pos.z())) return false;
        return coastal(pos.x(),pos.z(),(x,z)-> {
            if (!world.isChunkLoaded(x>>4,z>>4)) return new Column(0,Surface.UNKNOWN);
            final int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);
            final Material type=world.getBlockAt(x,y,z).getType();
            final Surface surface=type==Material.WATER ? Surface.WATER
                : type.isSolid() && type!=Material.ICE && type!=Material.PACKED_ICE && type!=Material.BLUE_ICE ? Surface.DRY : Surface.OTHER;
            return new Column(y,surface);
        });
    }

    public static boolean coastal(final int chunkX,final int chunkZ,final ShoreView view) {
        final java.util.Map<Long,Column> cache=new java.util.HashMap<>();
        final ShoreView cached=(x,z)->cache.computeIfAbsent(pl.medivalrpg.kraje.util.ChunkKeys.key(x,z),k->view.column(x,z));
        final int ox=chunkX<<4,oz=chunkZ<<4;
        for (int z=oz;z<oz+16;z++) for (int x=ox;x<ox+16;x++) {
            final Column ground=cached.column(x,z);
            if (ground.surface()!=Surface.DRY) continue;
            for (final int[] d:DIRECTIONS) {
                final Column next=cached.column(x+d[0],z+d[1]);
                if (next.surface()==Surface.WATER && Math.abs(next.height()-ground.height())<=3)
                    return true;
            }
        }
        return false;
    }

}
