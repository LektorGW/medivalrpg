// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.freight;

import java.util.Objects;

/** Requires actual displacement and increasing route distance after the load, not a node-name change. */
public final class JourneyProof {
    public record Position(String world,double x,double y,double z) {
        public double distance(Position b) {
            if(b==null || !Objects.equals(world,b.world))return Double.POSITIVE_INFINITY;
            return Math.sqrt(Math.pow(x-b.x,2)+Math.pow(y-b.y,2)+Math.pow(z-b.z,2));
        }
    }
    private String route;
    private double previousDistance,travelled;
    private Position previous;
    private boolean wasRunning;
    public JourneyProof(String route,double distance,Position position) {this.route=route;previousDistance=distance;previous=position;}
    public boolean observe(boolean running,String route,double distance,Position position,Position source,Position destination) {
        double delta=distance-previousDistance, displacement=position.distance(previous);
        double minimum=source.distance(destination);
        // RailRuntime clears running at arrival and can finish the last <=2.35m.
        // Include that small terminal step only after actually observed movement.
        boolean terminalStep=wasRunning && travelled>0 && delta<=3;
        if((running||terminalStep) && Objects.equals(this.route,route) && Double.isFinite(delta) && delta>0 && displacement>0 && Double.isFinite(displacement) && displacement<=delta+3) travelled+=Math.min(delta,displacement);
        wasRunning=running;
        this.route=route;previousDistance=distance;previous=position;
        return minimum>.1 && Double.isFinite(minimum) && travelled>=Math.max(.1,minimum*.95) && position.distance(source)>Math.min(2,minimum/3);
    }
}
