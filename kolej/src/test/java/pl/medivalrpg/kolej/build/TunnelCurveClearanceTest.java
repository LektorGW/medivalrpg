// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelCurveClearanceTest {
    List<TrackCell> corner(int quarterTurns,boolean reverse) {
        List<TrackCell> cells=new ArrayList<>();
        for(int z=-12;z<=0;z++)cells.add(turn(0,z,quarterTurns));
        for(int x=1;x<=12;x++)cells.add(turn(x,0,quarterTurns));
        if(reverse)Collections.reverse(cells);return cells;
    }
    TrackCell turn(int x,int z,int turns) {
        for(int k=0;k<turns;k++){int previous=x;x=-z;z=previous;}
        return new TrackCell(x,64,z,CellKind.TUNNEL);
    }
    @Test void sevenMetreBodyAndSeatedPlayerEnvelopeStayClearThroughEveryYawAndCornerOrientation() {
        double yaw45=Math.toRadians(45);
        assertEquals(3.3941125497,1.3*Math.cos(yaw45)+3.5*Math.sin(yaw45),1e-9);
        assertTrue(1.3*Math.cos(yaw45)+3.5*Math.sin(yaw45)>2.5,"Even a straight 5-column bore alone cannot fit this yaw.");
        for(int orientation=0;orientation<4;orientation++)for(boolean reverse:List.of(false,true)) {
            var cells=corner(orientation,reverse);
            var ops=Factory.segment(cells,(x,z)->90,(x,z)->50,(x,z)->Integer.MIN_VALUE,CellWorks.Settings.defaults());
            var map=ModelClearanceTest.finalPlan(ops);
            // Include the tail before/after the curve, not only the centre of the bend.
            for(int i=9;i<=15;i++) {
                TrackCell centre=cells.get(i);
                for(int degrees=0;degrees<=180;degrees+=5) {
                    double yaw=Math.toRadians(degrees),c=Math.cos(yaw),s=Math.sin(yaw);
                    // Both corners AND the interior/sitting-player volume: a wall inside
                    // the body is unsafe even when the four extreme corners are outside it.
                    for(int lateral=-2;lateral<=2;lateral++)for(int along=-14;along<=14;along++) {
                        double x=lateral*.65,z=along*.25;
                        int wx=(int)Math.floor(centre.x()+.5+x*c+z*s),wz=(int)Math.floor(centre.z()+.5-x*s+z*c);
                        for(int y=64;y<=68;y++) {
                            BlockOp op=map.get(ModelClearanceTest.key(wx,y,wz));
                            assertNotNull(op,"Unexcavated tail/player envelope at "+wx+","+y+","+wz);
                            assertTrue(op.mode()==BlockOp.Mode.CLEAR || op.part()==BlockOp.Part.RAW && op.data().startsWith("minecraft:rail["),
                                "Wall/ceiling/light inside wagon or seated player: "+op+" yaw="+degrees+" cell="+i);
                        }
                    }
                }
            }
        }
    }
    @Test void roomLiningFromNeighbourCellsCannotRecloseClearedVolume() {
        var cells=corner(0,false);
        var ops=Factory.segment(cells,(x,z)->90,(x,z)->50,(x,z)->Integer.MIN_VALUE,CellWorks.Settings.defaults());
        Set<String> air=new HashSet<>();
        ops.stream().filter(o->o.mode()==BlockOp.Mode.CLEAR).forEach(o->air.add(ModelClearanceTest.key(o.x(),o.y(),o.z())));
        for(var op:ops)if(op.part()==BlockOp.Part.TUNNEL_WALL || op.part()==BlockOp.Part.TUNNEL_LIGHT || op.part()==BlockOp.Part.TUNNEL_CEILING)
            assertFalse(air.contains(ModelClearanceTest.key(op.x(),op.y(),op.z())),"Lining occupies the union of clearances: "+op);
    }
    @Test void widenedWorkIsLocalAndKeepsTrackColumnExclusions() {
        var cells=corner(0,false);
        assertFalse(CellWorks.nearSharpCurve(cells,7));assertTrue(CellWorks.nearSharpCurve(cells,8));
        assertTrue(CellWorks.nearSharpCurve(cells,16));assertFalse(CellWorks.nearSharpCurve(cells,17));
        var straight=CellWorks.plan(cells,2,90,50,Integer.MIN_VALUE,(x,z)->false,(x,z)->90,CellWorks.Settings.defaults());
        assertTrue(straight.stream().allMatch(o->o.z()==-10 && Math.abs(o.x())<=3));
        var room=CellWorks.plan(cells,12,90,50,Integer.MIN_VALUE,(x,z)->x==1,(x,z)->90,CellWorks.Settings.defaults());
        assertTrue(room.stream().noneMatch(o->o.x()==1));
        assertTrue(room.stream().allMatch(o->Math.abs(o.x())<=5 && Math.abs(o.z())<=5));
        assertTrue(room.stream().anyMatch(o->o.x()==4&&o.z()==4&&o.y()==68&&o.mode()==BlockOp.Mode.CLEAR));
        assertTrue(room.stream().anyMatch(o->o.x()==5&&o.z()==5&&o.y()==68&&o.part()==BlockOp.Part.TUNNEL_WALL));
    }
}
