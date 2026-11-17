// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import pl.medivalrpg.zycie.logic.FireIncident.Cell;

/** Bounded dispatch and source geometry, independent of Bukkit. */
public final class FireTactics {
    private FireTactics() { }
    public record Probe(int x,int y,int z) { }
    private static final List<int[]> SOURCE_COLUMNS=new ArrayList<>();
    static {
        for(int ring=0;ring<=4;ring++) for(int x=-ring;x<=ring;x++) for(int z=-ring;z<=ring;z++)
            if(Math.max(Math.abs(x),Math.abs(z))==ring) SOURCE_COLUMNS.add(new int[]{x,z});
    }
    public static int wellProbeCount() {return SOURCE_COLUMNS.size()*13;}
    /** Highest exposed water is tested first; the standard well's water is base+4, not base+2. */
    public static Probe wellProbe(final int cursor) {
        final int index=Math.floorMod(cursor,wellProbeCount());
        final int[] column=SOURCE_COLUMNS.get(index/13);
        return new Probe(column[0],8-index%13,column[1]);
    }
    public static boolean reassign(final boolean oldFireActive,final boolean oldCrew,final double oldDistance,final double newDistance) {
        return !oldFireActive || !oldCrew && newDistance+16<oldDistance;
    }
    public static List<Cell> splash(final Cell centre,final Iterable<Cell> flames,final int maximum,final Predicate<Cell> reachable) {
        final List<Cell> nearby=new ArrayList<>();
        for(final Cell cell:flames) {
            final long dx=(long)cell.x()-centre.x(),dy=(long)cell.y()-centre.y(),dz=(long)cell.z()-centre.z();
            if(dx*dx+dy*dy+dz*dz<=3) nearby.add(cell);
        }
        nearby.sort(java.util.Comparator.comparingInt(c->Math.abs(c.x()-centre.x())+Math.abs(c.y()-centre.y())+Math.abs(c.z()-centre.z())));
        final List<Cell> result=new ArrayList<>();
        for(final Cell cell:nearby) if(result.size()<maximum && reachable.test(cell))result.add(cell);
        return result;
    }
}
