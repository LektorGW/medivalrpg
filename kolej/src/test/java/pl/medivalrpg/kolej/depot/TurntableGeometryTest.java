// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.depot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurntableGeometryTest {
    @Test void everyFrameHasConnectedSupportedRailsAndStaysInsidePit() {
        for (int yaw=-180;yaw<=180;yaw+=5) {
            var bridge=TurntablePlan.bridge(yaw);
            List<TurntablePlan.Cell> rails=bridge.keySet().stream().filter(c->c.y()==1).toList();
            assertTrue(rails.size()>=19);
            for (int i=0;i<rails.size();i++) {
                var c=rails.get(i);
                assertTrue(Math.hypot(c.x(),c.z())<TurntablePlan.PIT_RADIUS);
                assertEquals("minecraft:dark_oak_planks",bridge.get(new TurntablePlan.Cell(c.x(),0,c.z())));
                if (i>0) assertEquals(1,Math.abs(c.x()-rails.get(i-1).x())+Math.abs(c.z()-rails.get(i-1).z()));
            }
        }
    }
    @Test void staticBayTracksConnectToBridgeAndMatchPhysicalPlan() {
        Map<String,String> blocks=new HashMap<>();
        DepotPlan.plan(0,0,0).forEach(op->blocks.put(op.x()+":"+op.y()+":"+op.z(),op.data()));
        var links=DepotPlan.trackLinks(0,1,0,0); assertEquals(7,links.size());
        for (var link:links) {
            for (int i=0;i<link.cells().size();i++) {
                var c=link.cells().get(i);
                assertTrue(blocks.get(c.x()+":1:"+c.z()).startsWith("minecraft:rail["));
                if(i>0){var p=link.cells().get(i-1);assertEquals(1,Math.abs(c.x()-p.x())+Math.abs(c.z()-p.z()));}
            }
            if(link.bayIndex()<0) continue;
            var rails=TurntablePlan.bridge(TurntablePlan.bayYaw(0,link.bayIndex())).keySet().stream().filter(c->c.y()==1).toList();
            var tip=rails.getLast();var first=link.cells().getFirst();
            assertEquals(1,Math.abs(tip.x()-first.x())+Math.abs(tip.z()-first.z()));
        }
    }
    @Test void rotatedStaticLinksStayFourConnected() {
        for(int q=-3;q<=4;q++) for(var link:DepotPlan.trackLinks(-800,70,1200,q))
            for(int i=1;i<link.cells().size();i++) {
                var a=link.cells().get(i-1);var b=link.cells().get(i);
                assertEquals(70,a.y());assertEquals(1,Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z()));
            }
    }
    @Test void everyRotatedBayMeetsItsBridge() {
        for(int q=0;q<4;q++) for(var link:DepotPlan.trackLinks(0,1,0,q)) {
            if(link.bayIndex()<0) continue;
            var rails=TurntablePlan.bridge(TurntablePlan.bayYaw(q*90,link.bayIndex())).keySet().stream().filter(c->c.y()==1).toList();
            var tip=rails.getLast();var first=link.cells().getFirst();
            assertEquals(1,Math.abs(tip.x()-first.x())+Math.abs(tip.z()-first.z()),"rotation="+q+" bay="+link.bayIndex());
        }
    }
    @Test void bayYawAndShortestRotation() {
        assertEquals(50,TurntablePlan.bayYaw(0,0));assertEquals(-50,TurntablePlan.bayYaw(0,5));
        assertEquals(20,TurntableService.angleDelta(350,10)); assertEquals(-20,TurntableService.angleDelta(10,350));
        assertEquals(90,TurntablePlan.bayYaw(90,6));
        assertThrows(IllegalArgumentException.class,()->TurntablePlan.bayYaw(0,7));
        assertThrows(IllegalArgumentException.class,()->TurntablePlan.bridge(Double.NaN));
    }
}
