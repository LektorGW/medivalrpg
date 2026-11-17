// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.Map;
import pl.medivalrpg.kolej.data.RailData.Depot;
import pl.medivalrpg.kolej.depot.DepotPlan;
import pl.medivalrpg.kolej.depot.TurntablePlan;
import pl.medivalrpg.kolej.network.*;
import pl.medivalrpg.kolej.infrastructure.InfrastructureState.Fixture;

/** Swept train clearance, including physical factory rails that are not graph segments. Pure geometry. */
public final class InfrastructureClearance {
    private InfrastructureClearance(){}
    public static boolean track(Fixture f,double x,double y,double z) {
        var b=f.kind().bounds(f.rotation());
        return y+5>f.y()+1 && y<f.y()+1+b.height() && b.contains(x-f.x()-.5,z-f.z()-.5,2);
    }
    public static boolean conflicts(Fixture f,String world,TrackGraph graph,Map<String,Depot> depots) {
        for(TrackNode n:graph.nodes()) {
            if(!n.world().equals(world))continue;
            if(n.isStation() && (n.axis()==0 || n.axis()==1)) {
                // Factory.station(0,0,-64,128), rotated around the station origin.
                // The extra endpoint conservatively covers both 180-degree variants of the same axis.
                for(int d=-64;d<=64;d++)if(track(f,n.x()+.5+(n.axis()==1?d:0),n.y(),n.z()+.5+(n.axis()==0?d:0)))return true;
            } else if(track(f,n.x()+.5,n.y(),n.z()+.5))return true;
        }
        for(var entry:depots.entrySet()) {
            TrackNode node=graph.node(entry.getKey());if(node==null || !node.world().equals(world))continue;
            Depot d=entry.getValue();
            for(var link:DepotPlan.trackLinks(d.x(),d.groundY()+1,d.z(),d.rotation()))
                for(var cell:link.cells())if(track(f,cell.x()+.5,cell.y(),cell.z()+.5))return true;
            // The bridge sweeps the entire pit; a safe static model must clear all bridge headings.
            for(int dx=-TurntablePlan.PIT_RADIUS;dx<=TurntablePlan.PIT_RADIUS;dx++)for(int dz=-TurntablePlan.PIT_RADIUS;dz<=TurntablePlan.PIT_RADIUS;dz++)
                if(dx*dx+dz*dz<=TurntablePlan.PIT_RADIUS*TurntablePlan.PIT_RADIUS && track(f,d.x()+dx+.5,d.groundY()+1,d.z()+dz+.5))return true;
        }
        for(var segment:graph.segments()) {
            TrackNode from=graph.node(segment.from());if(from==null || !from.world().equals(world))continue;
            for(var cell:segment.cells())if(track(f,cell.x()+.5,cell.y(),cell.z()+.5))return true;
        }
        return false;
    }
}
