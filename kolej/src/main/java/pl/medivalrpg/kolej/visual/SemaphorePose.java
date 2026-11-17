// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

/** Existing canonical arm has origin (0,0,0). Compensate translation to rotate around its real hinge,
 * (0,77.5,0)/16, without editing the deterministic model or generating a seventeenth model.
 * Intermediate angles between STOP (0) and CLEAR (45°) give a smooth mechanical transition. */
public record SemaphorePose(float angle,float x,float y) {
    public static final double HINGE=77.5/16.0, CLEAR_ANGLE=Math.PI/4;
    /** 8 visual steps ≈ 0.8 s at the 2-tick semaphore refresh. */
    public static final double STEP=CLEAR_ANGLE/8;
    public static final SemaphorePose STOP=of(false),CLEAR=of(true);
    public static SemaphorePose of(boolean clear) {return at(clear?CLEAR_ANGLE:0);}
    /** Any angle within [0,45°], rotated around the hinge. */
    public static SemaphorePose at(double angle) {
        if(!Double.isFinite(angle))angle=0;
        angle=Math.max(0,Math.min(CLEAR_ANGLE,angle));
        return new SemaphorePose((float)angle,(float)(HINGE*Math.sin(angle)),(float)(HINGE*(1-Math.cos(angle))));
    }
    /** One bounded step from this pose towards the requested signal; returns the exact end pose on arrival. */
    public SemaphorePose toward(boolean clear,double step) {
        double target=clear?CLEAR_ANGLE:0,delta=target-angle;
        if(Math.abs(delta)<=Math.max(0,step)+1e-6)return clear?CLEAR:STOP; // float angle: tolerate rounding
        return at(angle+Math.copySign(step,delta));
    }
    public boolean settled(boolean clear){return this==(clear?CLEAR:STOP) || Math.abs(angle-(clear?CLEAR_ANGLE:0))<1e-6;}
    /** BetterModel bone space negates model X (and Z): the modifier rotation about Z and the X shift are mirrored. */
    public float boneAngle(){return -angle;}
    public float boneX(){return -x;}
    /** Same pose applied to a point given in BetterModel bone space (what the modifiers do). */
    public double[] boneTransform(double bx,double by) {
        double a=boneAngle();return new double[]{bx*Math.cos(a)-by*Math.sin(a)+boneX(),bx*Math.sin(a)+by*Math.cos(a)+y};
    }
    public double[] transform(double px,double py) {
        return new double[]{px*Math.cos(angle)-py*Math.sin(angle)+x,px*Math.sin(angle)+py*Math.cos(angle)+y};
    }
}
