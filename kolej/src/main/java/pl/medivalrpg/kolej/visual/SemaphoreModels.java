// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.visual;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.*;

/** One packet-only model per bounded signal, never per track cell. Bone callbacks only read immutable pose data. */
public final class SemaphoreModels implements AutoCloseable {
    public static final String MODEL="kolej_semafor_mechaniczny";
    private record Entry(DummyTracker tracker,AtomicReference<SemaphorePose> pose,boolean[] target) {}
    private final Map<String,Entry> entries=new HashMap<>();
    public boolean ready(){return BetterModel.modelOrNull(MODEL)!=null;}
    public SemaphorePose pose(String id){var entry=entries.get(id);return entry==null?SemaphorePose.STOP:entry.pose().get();}
    public boolean show(String id,Location at,boolean clear) {
        var renderer=BetterModel.modelOrNull(MODEL);if(renderer==null){remove(id);return false;}
        Entry entry=entries.get(id);
        if(entry==null || entry.tracker().isClosed()) {
            DummyTracker t=renderer.create(BukkitAdapter.adapt(at));
            var arm=t.bone("arm");var body=t.bone("body");
            // Refuse a changed/unknown arm origin; don't rotate an entire mast or silently show an old CLEAR.
            if(arm==null || body==null || arm.getGroup().getPosition().lengthSquared()>1e-8
                || !arm.uuid().equals(UUID.fromString("f0ea75d3-6143-5d06-8503-982f6280a834"))) {t.close();return false;}
            var pose=new AtomicReference<>(SemaphorePose.STOP);
            // BetterModel space has model X negated (arm along -X): mirror the pose so the arm rises (upper quadrant).
            boolean rotation=arm.addLocalRotModifier(b->b==arm,q->q.rotateZ(pose.get().boneAngle()));
            boolean translation=arm.addPositionModifier(b->b==arm,v->{var p=pose.get();return v.add(p.boneX(),p.y(),0);});
            if(!rotation || !translation){t.close();return false;}
            // Interpolate between the 2-tick pose steps on the client: smooth arm movement.
            t.update(TrackerUpdateAction.moveDuration(2));
            entry=new Entry(t,pose,new boolean[]{false});entries.put(id,entry);
        }
        // Visual only: the authority (aspects) is already updated; the arm follows in ~0.8 s.
        if(entry.target()[0]!=clear){entry.target()[0]=clear;RailEffects.semaphore(at);}
        var current=entry.pose().get();
        if(!current.settled(clear)){entry.pose().set(current.toward(clear,SemaphorePose.STEP));entry.tracker().forceUpdate(true);}
        var t=entry.tracker();t.location(BukkitAdapter.adapt(at));float yaw=at.getYaw()+TrainModels.MODEL_YAW_OFFSET;t.rotation(()->new ModelRotation(0,yaw));
        for(var p:Bukkit.getOnlinePlayers()) {
            if(p.getWorld().equals(at.getWorld()) && p.getLocation().distanceSquared(at)<96*96) {if(!t.isSpawned(p.getUniqueId()))t.spawn(BukkitAdapter.adapt(p));}
            else t.remove(BukkitAdapter.adapt(p));
        }
        return true;
    }
    public void remove(String id){var entry=entries.remove(id);if(entry!=null)entry.tracker().close();}
    public void close(){entries.values().forEach(e->e.tracker().close());entries.clear();}
}
