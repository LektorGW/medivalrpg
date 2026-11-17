// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.depot;

import java.util.*;
import java.util.concurrent.Future;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kolej.AuditMutationWatch;
import pl.medivalrpg.kolej.visual.RailEffects;
import pl.medivalrpg.kolej.visual.TrainModels;
import pl.medivalrpg.kolej.world.*;

/** Physical block turntable. Caller exclusively locks the depot and moves its locomotive in the callback. */
public final class TurntableService implements AutoCloseable {
    public record State(UUID world, int x, int railY, int z, int baseYaw, double fromYaw, double toYaw) { }
    public record Snapshot(int version, List<State> tables) { public Snapshot { tables = List.copyOf(tables); } }
    private final Plugin plugin;
    private final WorksProtection protection;
    private final AtomicJsonFile<Snapshot> store;
    private final Map<String, State> tables = new LinkedHashMap<>();
    private Future<?> pending;
    private Active active;
    private boolean closed;
    private String reason = "";
    private String status = "IDLE";
    /** Packet-only bridge model following the real block rotation (BetterModel optional). */
    public static final String BRIDGE_MODEL = "kolej_most_obrotnicy";
    private TrainModels bridge;
    private String bridgeKey;
    private Location bridgeAt;
    private int bridgeLinger;
    private static final class Active {
        final World world;
        final State site;
        final Map<TurntablePlan.Cell,String> background;
        final Consumer<Location> callback;
        final double target;
        double current;
        boolean intentReady;
        Active(World world, State site, Map<TurntablePlan.Cell,String> background, Consumer<Location> callback, double target) {
            this.world=world; this.site=site; this.background=background; this.callback=callback; this.target=target; current=site.fromYaw;
        }
    }
    public TurntableService(Plugin plugin, Predicate<Location> allowed) {
        main(); this.plugin=Objects.requireNonNull(plugin);
        protection=new WorksProtection(null); Objects.requireNonNull(allowed);
        protection.setClaimValidator((at,country)->allowed.test(at));
        store=new AtomicJsonFile<>(plugin.getDataFolder().toPath().resolve("works/turntables.json"), Snapshot.class);
        try { restore(store.read(new Snapshot(1,List.of()))); }
        catch (Exception ex) { store.close(); throw new IllegalStateException("Nie można odczytać obrotnic",ex); }
        try { if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) bridge=new TrainModels(); }
        catch (RuntimeException | LinkageError ex) { bridge=null; plugin.getLogger().warning("Obrotnica bez modelu BetterModel: "+ex); }
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required"); }
    private void open() { main(); if (closed) throw new IllegalStateException("Turntable closed"); }
    private static String key(State s) { return s.world+":"+s.x+":"+s.railY+":"+s.z; }
    /**
     * centre Y = rail height, yaw = FIXED depot orientation (0/90/180/270), NOT the locomotive yaw.
     * Index 0..5 selects a shed; 6 resets alignment to baseYaw (incoming track).
     * Callback runs each physical frame at the centre with the locomotive's new Bukkit yaw.
     * Caller must hold an exclusive depot reservation and ensure no other vehicles occupy the sweep.
     */
    public boolean start(World world, Location center, float yaw, int index, Consumer<Location> callback) {
        open(); reason="";
        if (active != null || pending != null) return deny("Obrotnica zajęta lub zapis w toku");
        if (world == null || center == null || !world.equals(center.getWorld()) || !Float.isFinite(yaw) || callback == null || index<0 || index>6)
            return deny("Niepoprawne parametry obrotnicy");
        int base = Math.floorMod(Math.round(yaw),360);
        if (base%90!=0 || Math.abs(yaw-Math.round(yaw))>.001) return deny("Orientacja bazy musi być wielokrotnością 90 stopni");
        State initial = new State(world.getUID(),center.getBlockX(),center.getBlockY(),center.getBlockZ(),base,base,base);
        State saved = tables.getOrDefault(key(initial),initial);
        if (saved.baseYaw!=base) return deny("Orientacja nie zgadza się z zapisaną bazą");
        int turns=base/90;
        Map<TurntablePlan.Cell,String> background=new LinkedHashMap<>();
        // Canonical static ring + approach rails, with the initial bridge removed.
        for (var op : DepotPlan.plan(0,0,0)) if (op.y()>=0 && op.y()<=1 && Math.abs(op.x())<=TurntablePlan.PIT_RADIUS && Math.abs(op.z())<=TurntablePlan.PIT_RADIUS) {
            int[] p=PlanRotation.point(op.x(),op.z(),turns);
            var cell=new TurntablePlan.Cell(p[0],op.y(),p[1]);
            background.put(cell,op.data());
        }
        for (var cell : TurntablePlan.bridge(base).keySet()) background.put(cell,TurntablePlan.pit(cell));
        Active candidate=new Active(world,saved,background,callback,TurntablePlan.bayYaw(base,index));
        String invalid=validate(candidate,layout(candidate,saved.fromYaw),layout(candidate,saved.toYaw),true);
        if (invalid!=null) return deny(invalid);
        // Persist the recovery intent before touching blocks, including the very first use.
        active=candidate; tables.put(key(saved),saved); pending=store.write(snapshot()); status="ROTATING";
        return true;
    }
    private boolean deny(String message) { reason=message; if (active==null) status="BLOCKED"; return false; }
    private Map<TurntablePlan.Cell,String> layout(Active a,double yaw) {
        Map<TurntablePlan.Cell,String> result=new LinkedHashMap<>(a.background); result.putAll(TurntablePlan.bridge(yaw)); return result;
    }
    private Location at(Active a,TurntablePlan.Cell c) { return new Location(a.world,a.site.x+c.x(),a.site.railY-1+c.y(),a.site.z+c.z()); }
    private String validate(Active a,Map<TurntablePlan.Cell,String> before,Map<TurntablePlan.Cell,String> after,boolean recovery) {
        for (var entry : after.entrySet()) {
            Location at=at(a,entry.getKey());
            String denied=protection.territory(a.world,at.getBlockX(),at.getBlockY(),at.getBlockZ(),null);
            if (denied!=null) return denied;
            var block=at.getBlock();
            if (block.getState() instanceof TileState) return "Kontener na obrotnicy";
            String actual=block.getBlockData().getAsString(), old=canonical(before.get(entry.getKey())), next=canonical(entry.getValue());
            if (!actual.equals(old) && !(recovery && actual.equals(next))) return "Zmieniony lub nieukończony most/niecka obrotnicy";
        }
        // Never rotate through a bystander. Runtime must additionally reserve non-entity train models.
        Location middle=new Location(a.world,a.site.x+.5,a.site.railY+1,a.site.z+.5);
        for (var entity : a.world.getNearbyEntities(middle,TurntablePlan.PIT_RADIUS,2,TurntablePlan.PIT_RADIUS))
            if (entity instanceof org.bukkit.entity.Player) return "Gracz w polu ruchu obrotnicy";
        return null;
    }
    private static String canonical(String data) { return Bukkit.createBlockData(data).getAsString(); }
    /** Visual only: the model shows the bridge at the angle of the last physical frame; failures never touch blocks. */
    private void showBridge(Active a) {
        if (bridge==null) return;
        try {
            bridgeKey="turntable/"+key(a.site)+"/";
            bridgeAt=new Location(a.world,a.site.x+.5,a.site.railY,a.site.z+.5,(float)a.current,0);
            bridge.move(bridgeKey,BRIDGE_MODEL,bridgeAt,0); bridgeLinger=40;
            RailEffects.turntable(bridgeAt,RailEffects.now());
        } catch (RuntimeException | LinkageError ex) { bridge.remove("turntable/"); bridgeKey=null; }
    }
    private void lingerBridge() {
        if (bridge==null || bridgeKey==null) return;
        if (--bridgeLinger<=0) { bridge.remove(bridgeKey); bridgeKey=null; bridgeAt=null; }
    }
    public void tick() {
        open(); if (active==null) { lingerBridge(); return; }
        if (pending!=null) {
            if (!pending.isDone()) return;
            try { pending.get(); pending=null; active.intentReady=true; }
            catch (Exception ex) { fail("Błąd zapisu obrotnicy: "+ex); return; }
        }
        Active a=active;
        State state=tables.get(key(a.site));
        if (!a.intentReady) {
            double delta=angleDelta(a.current,a.target);
            double next=a.current+Math.copySign(Math.min(5,Math.abs(delta)),delta);
            State intent=new State(a.site.world,a.site.x,a.site.railY,a.site.z,a.site.baseYaw,a.current,next);
            tables.put(key(intent),intent); pending=store.write(snapshot()); return;
        }
        var before=layout(a,state.fromYaw); var after=layout(a,state.toYaw);
        String invalid=validate(a,before,after,true);
        if (invalid!=null) { fail(invalid); return; }
        // Clear the old rail layer before setting decks, then place the new rail layer. No physics cascades.
        for (int y=1;y>=0;y--) for (var e : after.entrySet()) if (e.getKey().y()==y) {
            var block=at(a,e.getKey()).getBlock();
            var desired=Bukkit.createBlockData(e.getValue());
            if (!block.getBlockData().equals(desired)) {
                block.setBlockData(desired,false);
                AuditMutationWatch.afterMutation(block);
            }
        }
        a.current=state.toYaw; a.intentReady=false;
        showBridge(a);
        Location locomotive=new Location(a.world,a.site.x+.5,a.site.railY,a.site.z+.5,(float)a.current,0);
        try { a.callback.accept(locomotive); }
        catch (RuntimeException ex) { fail("Callback lokomotywy przerwał obrót: "+ex); return; }
        if (Math.abs(angleDelta(a.current,a.target))<.001) { active=null; status="DONE"; }
    }
    public static double angleDelta(double from,double to) { return ((to-from)%360+540)%360-180; }
    /** Runtime supplies its real pivot-to-head distance; tender remains behind head in its own pose calculation. */
    public static Location headPose(Location pivot,double forwardOffset) {
        if (!Double.isFinite(forwardOffset)) throw new IllegalArgumentException("Invalid head offset");
        double angle=Math.toRadians(pivot.getYaw());
        return pivot.clone().add(-Math.sin(angle)*forwardOffset,0,Math.cos(angle)*forwardOffset);
    }
    private void fail(String message) { reason=message; status="BLOCKED"; active=null; plugin.getLogger().warning(message); if (bridge!=null && bridgeKey!=null) { bridge.remove(bridgeKey); bridgeKey=null; } }
    public boolean busy() { main(); return active!=null; }
    public String status() { main(); return status; }
    public String reason() { main(); return reason; }
    public Snapshot snapshot() { main(); return new Snapshot(1,List.copyOf(tables.values())); }
    public void restore(Snapshot snapshot) {
        main(); if (active!=null) throw new IllegalStateException("Turntable active");
        if (snapshot.version!=1) throw new IllegalArgumentException("Version");
        Map<String,State> checked=new LinkedHashMap<>();
        for (State s:snapshot.tables) {
            if (s.world==null || s.baseYaw%90!=0 || !Double.isFinite(s.fromYaw) || !Double.isFinite(s.toYaw) || checked.put(key(s),s)!=null)
                throw new IllegalArgumentException("Invalid turntable state");
        }
        tables.clear(); tables.putAll(checked);
    }
    @Override public void close() { main(); if (closed) return; closed=true; active=null; store.close(); if (bridge!=null) bridge.close(); }
}
