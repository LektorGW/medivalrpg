// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.*;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.animation.AnimationIterator;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import java.util.*;

/**
 * Isolated BetterModel adapter. No permanent entities and no hidden vanilla fallback.
 * Trackers are spawned only for players within 96 blocks and removed beyond 112; BetterModel sends
 * animation packets only to spawned viewers, so animations cost nothing for distant players.
 */
public final class TrainModels implements AutoCloseable {
    public static final String LOCOMOTIVE = "kolej_parowoz", LEVER = "kolej_dzwignia_zwrotnicy", FREIGHT_DOOR_MODEL = "kolej_kryty";
    public static final double SPAWN = 96, DESPAWN = 112;
    /**
     * BetterModel renders Blockbench geometry rotated 180° about Y (model X/Z negated; Blockbench north = forward),
     * while the generated rail models face +Z and the plugin computes bounds, seats and effects in that frame.
     * Adding 180° makes the rendered model match the plugin frame. Server-side evidence: verify-rail-anim-20261029.
     * Requires one client check; set to 0 to restore the previous behaviour.
     */
    public static final float MODEL_YAW_OFFSET = 180f;
    private static final AnimationModifier ONCE = AnimationModifier.builder().type(AnimationIterator.Type.PLAY_ONCE).build();
    private static final AnimationModifier HOLD = AnimationModifier.builder().type(AnimationIterator.Type.HOLD_ON_LAST).build();
    private final Map<String,DummyTracker> trackers = new HashMap<>();
    private final Map<String,String> models = new HashMap<>();
    private final Map<String,String> loops = new HashMap<>();
    private final Map<String,Boolean> braking = new HashMap<>();
    private final Map<String,Float> speeds = new HashMap<>();
    private final Map<String,LocomotiveAnimator> animators = new HashMap<>();
    private final Map<String,double[]> lastPose = new HashMap<>();
    private final Map<String,Long> doorStamps = new HashMap<>();
    private final Map<String,Location> places = new HashMap<>();
    private record Lever(String owner,boolean reverse,long seen) {}
    private final Map<String,Lever> levers = new HashMap<>();

    public boolean ready(String model) {return BetterModel.modelOrNull(model)!=null;}
    public boolean hasAnimation(String model,String animation) {var r=BetterModel.modelOrNull(model);return r!=null && r.animation(animation).isPresent();}
    public boolean has(String key) {var t=trackers.get(key);return t!=null && !t.isClosed();}

    private DummyTracker tracker(String key,String model,Location at) {
        var renderer=BetterModel.modelOrNull(model);
        if(renderer==null)return null;
        DummyTracker t=trackers.get(key);
        if(t==null || t.isClosed() || !model.equals(models.get(key))) {
            if(t!=null && !t.isClosed())t.close();
            t=renderer.create(BukkitAdapter.adapt(at));
            trackers.put(key,t);models.put(key,model);
            loops.remove(key);braking.remove(key);
        }
        t.location(BukkitAdapter.adapt(at));places.put(key,at.clone());
        float yaw=at.getYaw()+MODEL_YAW_OFFSET;
        t.rotation(()->new ModelRotation(0,yaw));
        viewers(t,at);
        return t;
    }
    static void viewers(Tracker t,Location at) {
        for(var p:Bukkit.getOnlinePlayers()) {
            boolean same=p.getWorld().equals(at.getWorld());
            double d=same?p.getLocation().distanceSquared(at):Double.MAX_VALUE;
            if(d<SPAWN*SPAWN){if(!t.isSpawned(p.getUniqueId()) && t instanceof DummyTracker dummy)dummy.spawn(BukkitAdapter.adapt(p));}
            else if(d>=DESPAWN*DESPAWN && t.isSpawned(p.getUniqueId()))t.remove(BukkitAdapter.adapt(p));
        }
    }
    /** Switch the single looping animation of a tracker; no packets when it is already playing. */
    private void loop(String key,DummyTracker t,String animation,float speed) {
        speeds.put(key,speed);
        String current=loops.get(key);
        if(Objects.equals(current,animation))return;
        if(current!=null)t.stopAnimation(current);
        loops.remove(key);
        if(animation!=null && t.renderer().animation(animation).isPresent()) {
            t.animate(animation,AnimationModifier.builder().type(AnimationIterator.Type.LOOP).speed(()->speeds.getOrDefault(key,1f)).build());
            loops.put(key,animation);
        }
    }

    /** Wagons, tender and static fixtures. Negative speed plays run_reverse when the model has it. */
    public void move(String key,String model,Location at,double speed) {
        DummyTracker t=tracker(key,model,at);if(t==null)return;
        boolean moving=Math.abs(speed)>.03;
        float wheel=LocomotiveAnimator.wheelSpeed(speed,model.equals(LOCOMOTIVE)?.5625:.4375);
        String run=!moving?null:speed<0 && t.renderer().animation("run_reverse").isPresent()?"run_reverse":"run";
        loop(key,t,run,wheel);
        String train=AnimationRequests.trainOfCar(key);
        if(train!=null && FREIGHT_DOOR_MODEL.equals(model)) {
            long stamp=AnimationRequests.freightDoorsStamp(train);
            if(stamp!=0 && !Objects.equals(doorStamps.put(key,stamp),stamp))doors(key);
        }
    }

    /**
     * Locomotive: idle/run/run_reverse loop, brake overlay, start and whistle one-shots.
     * @param speed m/s along the track (≥0 on routes)
     * @param yard depot manoeuvre: direction is taken from the real displacement of the pose
     * @return the frame for particle/sound effects
     */
    public LocomotiveAnimator.Frame locomotive(String key,Location at,double speed,double brake,boolean fire,boolean yard) {
        double signed=Math.abs(speed);
        double[] previous=lastPose.put(key,new double[]{at.getX(),at.getZ()});
        if(yard && previous!=null) {
            int direction=LocomotiveAnimator.direction(previous[0],previous[1],at.getX(),at.getZ(),at.getYaw());
            if(direction<0)signed=-signed;
        }
        var frame=animators.computeIfAbsent(key,k->new LocomotiveAnimator()).update(RailEffects.now(),signed,brake,fire,!yard);
        DummyTracker t=tracker(key,LOCOMOTIVE,at);
        sweepLevers();
        if(t==null)return frame;
        loop(key,t,frame.loop().animation,frame.moving()?frame.wheelSpeed():1f);
        boolean wasBraking=braking.getOrDefault(key,false);
        if(frame.braking()!=wasBraking) {
            if(frame.braking())t.animate("brake",AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build());else t.stopAnimation("brake");
            braking.put(key,frame.braking());
        }
        if(frame.start())t.animate("start",ONCE);
        if(frame.whistle())t.animate("whistle",ONCE);
        return frame;
    }

    /** Play a one-shot animation if the tracker exists and the model defines it. */
    public boolean play(String key,String animation) {
        DummyTracker t=trackers.get(key);
        if(t==null || t.isClosed() || t.renderer().animation(animation).isEmpty())return false;
        return t.animate(animation,ONCE);
    }
    /** Boarding/loading: open, hold and close the doors of a car (5 s). */
    public boolean doors(String key) {
        boolean played=play(key,"doors");
        if(played)RailEffects.doors(places.get(key));
        return played;
    }

    /**
     * Completed tender service at a fixture already shown under {@code key}. The water crane swings towards
     * the tender side (service_pz/service_nz); other fixtures play "service". Effects are sent even without a model.
     */
    public boolean service(String key,String model,Location at,Location tender,boolean coal) {
        int quarter=Math.floorMod(Math.round(at.getYaw()/90f),4);
        double dx=tender==null || !at.getWorld().equals(tender.getWorld())?0:tender.getX()-at.getX(),dz=tender==null || !at.getWorld().equals(tender.getWorld())?0:tender.getZ()-at.getZ();
        RailEffects.service(at,coal);
        return play(key,ServiceAnimation.name(model,quarter,dx,dz));
    }
    /** Lever stand next to a physical junction while a train is near; reverse = diverging rail shape. */
    public void lever(String node,String owner,Location at,boolean reverse) {
        String key="lever/"+node;
        Lever before=levers.get(key);
        DummyTracker t=tracker(key,LEVER,at);if(t==null)return;
        levers.put(key,new Lever(owner,reverse,RailEffects.now()));
        if(before==null){if(reverse)t.animate("throw_reverse",HOLD);return;}
        if(before.reverse()!=reverse) {
            t.stopAnimation(reverse?"throw_normal":"throw_reverse");
            t.animate(reverse?"throw_reverse":"throw_normal",HOLD);
            RailEffects.lever(at);
        }
    }
    private void sweepLevers() {
        long now=RailEffects.now();
        levers.entrySet().removeIf(e->{if(now-e.getValue().seen()<=60)return false;close(e.getKey());return true;});
    }
    private void close(String key) {
        var t=trackers.remove(key);if(t!=null)t.close();
        models.remove(key);loops.remove(key);braking.remove(key);speeds.remove(key);animators.remove(key);lastPose.remove(key);doorStamps.remove(key);places.remove(key);
    }

    /** Removes trackers whose key starts with prefix and levers owned by that train ("&lt;train&gt;/"). */
    public void remove(String prefix) {
        for(String key:new ArrayList<>(trackers.keySet()))if(key.startsWith(prefix))close(key);
        levers.entrySet().removeIf(e->{if(!prefix.equals(e.getValue().owner()+"/"))return false;close(e.getKey());return true;});
    }
    public void close() {trackers.values().forEach(DummyTracker::close);trackers.clear();models.clear();loops.clear();braking.clear();speeds.clear();animators.clear();lastPose.clear();doorStamps.clear();levers.clear();places.clear();}
}
