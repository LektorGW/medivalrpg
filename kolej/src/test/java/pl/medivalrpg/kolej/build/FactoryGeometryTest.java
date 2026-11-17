// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class FactoryGeometryTest {
    @Test void gradeHasBoundedCutAndFillAndDoesNotWhitelistTrees() {
        var ops=Factory.grade(10,66,20,2,3,8,4,(x,z)->74);
        assertEquals(48,ops.stream().filter(o->o.mode()==BlockOp.Mode.CLEAR).count());
        assertEquals(24,ops.stream().filter(o->o.mode()==BlockOp.Mode.FILL).count());
        assertEquals(6,ops.stream().filter(o->o.mode()==BlockOp.Mode.PLACE).count());
        assertTrue(ops.stream().allMatch(o->o.y()>=62 && o.y()<=74));
        assertThrows(IllegalArgumentException.class,()->Factory.grade(0,66,0,1,1,8,4,(x,z)->83));
        assertThrows(IllegalArgumentException.class,()->Factory.grade(0,66,0,1,1,8,4,(x,z)->50));
    }
    @Test void stationHasSupportedRailAndNoPlatformOnTrack() {
        var ops = Factory.station(-100, 60, 200, 18);
        Map<String,BlockOp> map = new HashMap<>(); ops.forEach(o -> map.put(o.x()+":"+o.y()+":"+o.z(),o));
        for (int z = 200; z < 218; z++) {
            assertEquals(BlockOp.Part.BED, map.get("-100:60:"+z).part());
            assertEquals("minecraft:rail[shape=north_south]", map.get("-100:61:"+z).data());
        }
    }
    @Test void roadDimensionsAndHeadroom() {
        var ops = Factory.road(0,64,0,10,3);
        assertEquals(30, ops.stream().filter(o -> o.part() == BlockOp.Part.ROAD).count());
        assertEquals(90, ops.stream().filter(o -> o.mode() == BlockOp.Mode.CLEAR).count());
        assertThrows(IllegalArgumentException.class, () -> Factory.road(0,0,0,0,3));
        assertThrows(IllegalArgumentException.class, () -> Factory.station(0,0,0,7));
    }
    @Test void segmentUsesCellWorksWithRailCurves() {
        var cells = List.of(new TrackCell(0,65,0,CellKind.GROUND), new TrackCell(0,65,1,CellKind.GROUND), new TrackCell(1,65,1,CellKind.GROUND));
        var ops = Factory.segment(cells, (x,z)->64, (x,z)->64, (x,z)->Integer.MIN_VALUE, CellWorks.Settings.defaults());
        assertTrue(ops.stream().anyMatch(o -> o.x()==0 && o.z()==1 && "minecraft:rail[shape=north_east]".equals(o.data())));
        assertThrows(IllegalArgumentException.class, () -> Factory.segment(List.of(cells.getFirst(), cells.getLast()), (x,z)->64, (x,z)->64, (x,z)->0, CellWorks.Settings.defaults()));
    }
    @Test void slopesFaceUphillAndCurvesUseBothSides() {
        assertEquals("ascending_east", RailGeometry.shape(-1,64,0,0,64,0,1,65,0));
        assertEquals("ascending_north", RailGeometry.shape(0,65,-1,0,64,0,0,64,1));
        assertEquals("south_west", RailGeometry.shape(-1,0,0,0,0,0,0,0,1));
        assertEquals("east_west", RailGeometry.shape(-1,0,0,0,0,0,1,0,0));
    }
}
