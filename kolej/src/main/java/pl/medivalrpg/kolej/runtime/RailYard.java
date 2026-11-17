// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.runtime;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import pl.medivalrpg.kolej.KolejPlugin;
import pl.medivalrpg.kolej.data.RailData.*;
import pl.medivalrpg.kolej.depot.*;
import pl.medivalrpg.kolej.network.*;

/** Slow yard shunting, with one exclusive turntable/approach reservation. Empty locomotives only. */
public final class RailYard implements AutoCloseable {
    private final KolejPlugin plugin;
    private final TurntableService table;
    private String rotatingTrain;
    private long ticks;
    static final double GATE=-18, PARK=21, HEAD_ON_TABLE=4;
    private static final Set<String> PHASES=Set.of("ALIGN_IN","IN","ROTATE_IN","TO_BAY","PARKED","ALIGN_EXIT","FROM_BAY","ROTATE_OUT","TO_GATE");
    public RailYard(KolejPlugin plugin){
        this.plugin=plugin;table=new TurntableService(plugin,plugin::allowed);
        for(Train t:plugin.data().trains.values())if(t.yardBase!=null){
            Depot d=plugin.data().depots.get(t.yardBase);
            if(d!=null && validState(t) && consistentPose(t,d) && plugin.graph().node(t.yardBase)!=null){
                ensureBayNode(t.yardBase,d,t.yardBay);t.running=false;t.manualDriver=null;t.yardSpeed=0;
                if("PARKED".equals(t.yardPhase))t.node=bayId(t.yardBase,t.yardBay);
                // Routes are intentionally detached for shunting; preserve the saved world pose.
                plugin.runtime().detachRoute(t);
            }else {t.steam.fire=false;t.yardSpeed=0;t.status="Niepoprawny zapis manewrów — wymagana naprawa";}
        }
    }
    public void command(Player p,String[] a) {
        if(a.length<3)throw new IllegalArgumentException("/kolej baza wprowadz <pociag> <baza> <stanowisko-1..6> albo wyprowadz <pociag>");
        Train t=plugin.train(a[2]);
        if(plugin.infrastructure()!=null && plugin.infrastructure().holds(t))throw new IllegalArgumentException("Trwa zapis tankowania lub kontrola transakcji infrastruktury.");
        if(a[1].equals("wprowadz")) {
            if(a.length<5)throw new IllegalArgumentException("/kolej baza wprowadz <pociag> <baza> <stanowisko-1..6>");
            String base=a[3];Depot depot=plugin.data().depots.get(base);int bay=Integer.parseInt(a[4])-1;
            if(depot==null || bay<0 || bay>5)throw new IllegalArgumentException("Nie ma bazy lub stanowiska.");
            if(t.running || t.yardBase!=null || !base.equals(t.node) || !t.cars.isEmpty() || t.manualDriver!=null)throw new IllegalArgumentException("Pusty parowóz z tendrem musi stać przy bramie bazy, bez gracza przy sterze. Odczep wagony.");
            if(plugin.data().trains.values().stream().anyMatch(other->other!=t && base.equals(other.yardBase) && other.yardBay==bay))throw new IllegalArgumentException("Stanowisko jest zajęte.");
            TrackNode gate=plugin.graph().node(base);
            if(gate==null || !gate.isDepot())throw new IllegalArgumentException("Baza nie jest gotowa.");
            Location at=plugin.runtime().location(t,0);
            if(at==null || !at.getWorld().getName().equals(gate.world()) || at.distanceSquared(point(base,depot,depot.rotation()*90,GATE))>1
                    || Math.abs(TurntableService.angleDelta(at.getYaw(),depot.rotation()*90))>5)
                throw new IllegalArgumentException("Ustaw parowóz przy bramie -18, przodem do obrotnicy.");
            if(!plugin.runtime().lockYard(base,t.id))throw new IllegalArgumentException("Podejście lub obrotnica zajęte.");
            ensureBayNode(base,depot,bay);
            plugin.runtime().detachRoute(t);t.running=false;t.yardBase=base;t.yardBay=bay;t.yardPhase="ALIGN_IN";pose(t,at);plugin.save();
        }else if(a[1].equals("wyprowadz")) {
            if(!"PARKED".equals(t.yardPhase) || !validState(t) || duplicateBay(t,plugin.data().trains.values()) || !plugin.data().depots.containsKey(t.yardBase))throw new IllegalArgumentException("Parowóz nie stoi w prawidłowo zapisanej parowozowni lub stanowisko ma konflikt.");
            if(!plugin.runtime().lockYard(t.yardBase,t.id))throw new IllegalArgumentException("Podejście lub obrotnica zajęte.");
            t.yardPhase="ALIGN_EXIT";plugin.save();
        }else throw new IllegalArgumentException("wprowadz lub wyprowadz");
        plugin.message(p,"Rozpoczęto powolne manewry — obserwuj bazę.");
    }
    private String bayId(String base,int bay){return base+"_stan_"+(bay+1);}
    private void ensureBayNode(String base,Depot d,int bay) {
        TrackNode gate=plugin.graph().node(base);if(gate==null)return;
        double yaw=TurntablePlan.bayYaw(d.rotation()*90,bay),rad=Math.toRadians(yaw);
        int x=(int)Math.floor(d.x()+.5-Math.sin(rad)*PARK),z=(int)Math.floor(d.z()+.5+Math.cos(rad)*PARK);
        plugin.graph().putNode(new TrackNode(bayId(base,bay),"Stanowisko "+(bay+1),NodeType.DEPOT,gate.world(),x,d.groundY()+1,z,-1,1));
    }
    private Location point(String base,Depot d,double yaw,double ahead) {
        TrackNode node=plugin.graph().node(base);World w=node==null?null:Bukkit.getWorld(node.world());
        double rad=Math.toRadians(yaw);
        return new Location(w,d.x()+.5-Math.sin(rad)*ahead,d.groundY()+1+TrackSegment.RAIL_TOP,d.z()+.5+Math.cos(rad)*ahead,(float)yaw,0);
    }
    private static void pose(Train t,Location l){t.yardPose=new double[]{l.getX(),l.getY(),l.getZ(),l.getYaw()};}
    private void phase(Train t,String next){t.yardPhase=next;plugin.save();}
    private boolean move(Train t,Location goal) {
        if(!validPose(t.yardPose) || goal.getWorld()==null){t.yardSpeed=0;return false;}
        double[] next=stepPose(t.yardPose,new double[]{goal.getX(),goal.getY(),goal.getZ(),goal.getYaw()},.12);
        Location at=new Location(goal.getWorld(),next[0],next[1],next[2],(float)next[3],0);
        if(!clearance(at)){t.yardSpeed=0;t.status="Manewry wstrzymane: przeszkoda, niezaładowany chunk lub brak zgody";return false;}
        t.yardPose=next;
        boolean arrived=Math.hypot(goal.getX()-next[0],goal.getZ()-next[2])<1e-6;
        t.yardSpeed=arrived?0:1.2;return arrived;
    }
    static boolean validPose(double[] p){return p!=null && p.length==4 && Arrays.stream(p).allMatch(Double::isFinite);}
    static boolean validState(Train t){return validPose(t.yardPose) && t.yardBay>=0 && t.yardBay<6 && PHASES.contains(t.yardPhase==null?"":t.yardPhase) && t.cars.isEmpty();}
    static boolean duplicateBay(Train t,Collection<Train> trains){return t.yardBase!=null && trains.stream().anyMatch(other->other!=t && t.yardBase.equals(other.yardBase) && t.yardBay==other.yardBay);}
    static boolean consistentPose(Train t,Depot d){
        if(d.rotation()<0 || d.rotation()>3 || !validState(t) || Math.abs(t.yardPose[1]-(d.groundY()+1+TrackSegment.RAIL_TOP))>.15)return false;
        double yaw=d.rotation()*90;
        if(Set.of("TO_BAY","PARKED","ALIGN_EXIT","FROM_BAY").contains(t.yardPhase))yaw=TurntablePlan.bayYaw(yaw,t.yardBay);
        if(t.yardPhase.startsWith("ROTATE_"))yaw=t.yardPose[3];
        double a=Math.toRadians(yaw),dx=t.yardPose[0]-(d.x()+.5),dz=t.yardPose[2]-(d.z()+.5);
        double along=-Math.sin(a)*dx+Math.cos(a)*dz,side=Math.cos(a)*dx+Math.sin(a)*dz;
        if(Math.abs(side)>.25 || Math.abs(TurntableService.angleDelta(t.yardPose[3],yaw))>5)return false;
        if(t.yardPhase.startsWith("ROTATE_"))return Math.abs(along-HEAD_ON_TABLE)<.25;
        if("PARKED".equals(t.yardPhase) || "ALIGN_EXIT".equals(t.yardPhase))return Math.abs(along-PARK)<.25;
        if(Set.of("TO_BAY","FROM_BAY").contains(t.yardPhase))return along>=HEAD_ON_TABLE-.25 && along<=PARK+.25;
        return along>=GATE-.25 && along<=HEAD_ON_TABLE+.25;
    }
    static double[] stepPose(double[] current,double[] goal,double maxStep){
        if(!validPose(current) || !validPose(goal) || !Double.isFinite(maxStep) || maxStep<=0)throw new IllegalArgumentException("Invalid yard pose");
        double dx=goal[0]-current[0],dz=goal[2]-current[2],distance=Math.hypot(dx,dz);
        if(distance<=maxStep)return goal.clone();
        return new double[]{current[0]+dx/distance*maxStep,goal[1],current[2]+dz/distance*maxStep,goal[3]};
    }
    private boolean clearance(Location head){
        double a=Math.toRadians(head.getYaw()),dx=-Math.sin(a),dz=Math.cos(a);
        World w=head.getWorld();
        for(double along=-11;along<=4;along++)for(double side:new double[]{-.7,0,.7}){
            Location at=head.clone().add(dx*along+Math.cos(a)*side,.4,dz*along+Math.sin(a)*side);
            if(at.getY()<w.getMinHeight()+1 || at.getY()+3>=w.getMaxHeight() || !w.isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4) || !w.getWorldBorder().isInside(at) || !plugin.allowed(at))return false;
            if(side==0 && !w.getBlockAt(at.getBlockX(),head.getBlockY()-1,at.getBlockZ()).getType().isSolid())return false;
            for(int y=0;y<3;y++)if(!at.clone().add(0,y,0).getBlock().isPassable())return false;
        }
        return true;
    }
    private boolean rotate(Train t,Depot depot,int index,boolean locomotive) {
        if(rotatingTrain!=null){if(!rotatingTrain.equals(t.id))return false;if(table.busy())return false;rotatingTrain=null;if(!table.status().equals("DONE")){t.status="Obrotnica: "+table.reason();return false;}return true;}
        Location center=point(t.yardBase,depot,0,0);center.setY(depot.groundY()+1);
        boolean started=table.start(center.getWorld(),center,depot.rotation()*90,index,pivot->{if(locomotive){Location front=TurntableService.headPose(pivot,4);front.add(0,TrackSegment.RAIL_TOP,0);pose(t,front);}});
        if(started)rotatingTrain=t.id;else t.status="Obrotnica: "+table.reason();return false;
    }
    public void tick() {
        Train rotating=rotatingTrain==null?null:plugin.data().trains.get(rotatingTrain);
        Depot rotatingDepot=rotating==null?null:plugin.data().depots.get(rotating.yardBase);
        if(rotatingTrain==null || rotating!=null && (plugin.infrastructure()==null || !plugin.infrastructure().holds(rotating)) && rotatingDepot!=null && consistentPose(rotating,rotatingDepot)
                && !duplicateBay(rotating,plugin.data().trains.values()) && plugin.runtime().lockYard(rotating.yardBase,rotatingTrain))table.tick();ticks++;
        for(Train t:plugin.data().trains.values())if(t.yardBase!=null && t.yardPhase!=null) {
            if(plugin.infrastructure()!=null && plugin.infrastructure().holds(t)){t.yardSpeed=0;t.steam.fire=false;t.status="Zapis tankowania / kontrola transakcji";continue;}
            Depot d=plugin.data().depots.get(t.yardBase);t.yardSpeed=0;
            if(d==null || !validState(t) || !consistentPose(t,d) || duplicateBay(t,plugin.data().trains.values()) || plugin.graph().node(t.yardBase)==null || Bukkit.getWorld(plugin.graph().node(t.yardBase).world())==null){t.status="Brak bazy/świata, konflikt stanowiska lub niepoprawny zapis manewrów";t.steam.fire=false;continue;}
            if(t.yardPhase.equals("PARKED")){t.status="Parowozownia, stanowisko "+(t.yardBay+1);t.steam.fire=false;t.yardSpeed=0;continue;}
            if(!plugin.runtime().lockYard(t.yardBase,t.id)){t.status="Oczekiwanie na wolne podejście";continue;}
            double incoming=d.rotation()*90,bay=TurntablePlan.bayYaw(incoming,t.yardBay);
            t.status="Manewry: "+t.yardPhase;t.steam.fire=true;
            switch(t.yardPhase) {
                case "ALIGN_IN" -> {if(rotate(t,d,6,false))phase(t,"IN");}
                case "IN" -> {if(move(t,point(t.yardBase,d,incoming,4)))phase(t,"ROTATE_IN");}
                case "ROTATE_IN" -> {if(rotate(t,d,t.yardBay,true))phase(t,"TO_BAY");}
                case "TO_BAY" -> {if(move(t,point(t.yardBase,d,bay,PARK))){t.node=bayId(t.yardBase,t.yardBay);phase(t,"PARKED");plugin.runtime().unlockYard(t.yardBase);}}
                case "ALIGN_EXIT" -> {if(rotate(t,d,t.yardBay,false))phase(t,"FROM_BAY");}
                case "FROM_BAY" -> {if(move(t,point(t.yardBase,d,bay,4)))phase(t,"ROTATE_OUT");}
                case "ROTATE_OUT" -> {if(rotate(t,d,6,true))phase(t,"TO_GATE");}
                case "TO_GATE" -> {Location gate=point(t.yardBase,d,incoming,GATE);if(move(t,gate)){String base=t.yardBase;t.node=base;t.running=false;t.yardBase=null;t.yardPhase=null;t.yardPose=null;t.yardBay=-1;plugin.runtime().unlockYard(base);plugin.save();}}
                default -> t.status="Nieznany stan manewrowy — wymagany zarządca";
            }
        }
        if(ticks%20==0 && plugin.data().trains.values().stream().anyMatch(t->t.yardBase!=null))plugin.save();
    }
    public void close(){table.close();}
}
