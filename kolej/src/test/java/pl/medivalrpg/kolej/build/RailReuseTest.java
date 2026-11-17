// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class RailReuseTest {
    private static TrackCell cell(int x,int y,int z){return new TrackCell(x,y,z,CellKind.values()[0]);}
    private static final class Terrain implements RailReuse.View {
        final Map<RailReuse.Voxel,RailReuse.Sample> samples=new HashMap<>();
        final RailReuse.Sample air=new RailReuse.Sample(null,false,true,true);
        public RailReuse.Sample at(int x,int y,int z){return samples.getOrDefault(new RailReuse.Voxel(x,y,z),air);}
        void put(int x,int y,int z,String shape,boolean support,boolean passable,boolean allowed){samples.put(new RailReuse.Voxel(x,y,z),new RailReuse.Sample(shape,support,passable,allowed));}
        void rail(int x,int y,int z,String shape){put(x,y,z,shape,false,true,true);put(x,y-1,z,null,true,false,true);}
    }
    @Test void exactHeightAndVanillaShapeAreRequiredEvenInsideACompletedStation() {
        var check=RailReuse.expected(List.of(cell(0,70,0),cell(0,70,1),cell(0,70,2)),1);
        Terrain t=new Terrain();t.rail(0,70,1,"north_south");assertNull(RailReuse.problem(check,t));
        t.rail(0,70,1,"east_west");assertNotNull(RailReuse.problem(check,t));
        t.samples.clear();t.rail(0,71,1,"north_south");assertNotNull(RailReuse.problem(check,t));
        t.samples.clear();assertNotNull(RailReuse.problem(check,t));
    }
    @Test void missingSupportChangedPermissionAndBlockedHeadroomRejectReuse() {
        var check=RailReuse.expected(List.of(cell(0,70,0),cell(0,70,1),cell(0,70,2)),1);
        Terrain t=new Terrain();t.rail(0,70,1,"north_south");
        t.put(0,69,1,null,false,true,true);assertNotNull(RailReuse.problem(check,t));
        t.rail(0,70,1,"north_south");t.put(0,70,1,"north_south",false,true,false);assertNotNull(RailReuse.problem(check,t));
        t.rail(0,70,1,"north_south");t.put(1,74,1,null,false,false,true);assertNotNull(RailReuse.problem(check,t));
        t.put(1,74,1,null,false,true,false);assertNotNull(RailReuse.problem(check,t));
    }
    @Test void slopesAndCurvesRequireTheirActualShapeAndSweptClearance() {
        var slope=RailReuse.expected(List.of(cell(0,70,0),cell(1,70,0),cell(2,71,0)),1);
        assertEquals("ascending_east",slope.shape());
        Terrain t=new Terrain();t.rail(1,70,0,"east_west");assertNotNull(RailReuse.problem(slope,t));
        t.rail(1,70,0,"ascending_east");assertNull(RailReuse.problem(slope,t));
        assertTrue(slope.clearance().stream().anyMatch(v->v.y()==75));
        var curve=RailReuse.expected(List.of(cell(0,70,0),cell(0,70,1),cell(1,70,1)),1);
        assertEquals("north_east",curve.shape());t=new Terrain();t.rail(0,70,1,"north_east");
        assertNull(RailReuse.problem(curve,t));t.put(4,72,5,null,false,false,true);assertNotNull(RailReuse.problem(curve,t));
    }
    @Test void incrementalInspectionReadsExactlyOneVoxelAndMatchesFullValidation() {
        var check=RailReuse.expected(List.of(cell(0,70,0),cell(0,70,1),cell(1,70,1)),1);
        Terrain t=new Terrain();t.rail(0,70,1,"north_east");int[] reads={0};
        RailReuse.View counted=(x,y,z)->{reads[0]++;return t.at(x,y,z);};
        for(int i=0;i<RailReuse.inspections(check);i++){int before=reads[0];assertNull(RailReuse.problemAt(check,i,counted));assertEquals(1,reads[0]-before);}
        assertEquals(RailReuse.inspections(check),reads[0]);
    }
}
