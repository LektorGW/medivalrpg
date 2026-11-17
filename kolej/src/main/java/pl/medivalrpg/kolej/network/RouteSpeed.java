// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.network;

/** Segment limits apply to the complete consist; upcoming lower limits include a braking approach. */
public final class RouteSpeed {
    private RouteSpeed() {}
    public static double allowed(TrackGraph graph,Route route,double front,double length,double base,double decel) {
        if(route==null || !Double.isFinite(front) || !Double.isFinite(length) || length<0 || !Double.isFinite(base) || base<0 || !Double.isFinite(decel) || decel<=0)return 0;
        double result=base;
        for(var leg:route.legs()) {
            if(leg.end()<front-length)continue;
            var segment=graph.segment(leg.segmentId());if(segment==null || !Double.isFinite(segment.speedLimit()) || segment.speedLimit()<0)return 0;
            double limit=segment.speedLimit()==0?base:Math.min(base,segment.speedLimit());
            double approach=Math.max(0,leg.start()-front-2);
            result=Math.min(result,Math.sqrt(limit*limit+2*decel*.85*approach));
        }
        return result;
    }
}
