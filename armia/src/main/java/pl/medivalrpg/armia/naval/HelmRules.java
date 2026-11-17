// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

/** Server-owned steering: keyboard state cannot grant client-authoritative movement to a decorative seat. */
public final class HelmRules {
    public record Motion(float yaw,double x,double z) { }
    public static final double SPEED=.24;
    private HelmRules(){ }
    public static Motion step(float yaw,boolean pilot,boolean forward,boolean backward,boolean left,boolean right,double oldX,double oldZ){
        if(!Float.isFinite(yaw)||!Double.isFinite(oldX)||!Double.isFinite(oldZ))throw new IllegalArgumentException("Non-finite helm motion");
        if(!pilot){
            final double length=Math.hypot(oldX,oldZ),factor=length<.0001?0:Math.min(.75,SPEED/length);
            return new Motion(yaw,oldX*factor,oldZ*factor);
        }
        final int direction=(forward?1:0)-(backward?1:0),turn=(right?1:0)-(left?1:0);
        final float heading=(float)((((double)yaw+turn*2.4+180)%360+360)%360-180);
        final double speed=direction<0?-SPEED*.45:direction*SPEED,angle=Math.toRadians(heading);
        return new Motion(heading,-Math.sin(angle)*speed,Math.cos(angle)*speed);
    }
}
