// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.hub;

import java.util.*;
import java.util.function.ToIntBiFunction;
import pl.medivalrpg.kolej.build.*;
import pl.medivalrpg.kolej.network.*;

/** Pure proposal, never registers a graph node, charges money or submits work. */
public final class HubPlan {
    public static final int Y=65, RADIUS=32;
    // Cysterna's actual 2.6m width is offset: X=-1.2875..+1.3125m.
    // Symmetric planning also covers it in reverse without assuming pivot correction.
    public static final double BODY_LENGTH=8.1, BODY_WIDTH=2.625;
    public record Pose(double x,double z,double yaw) { }
    public record Arc(double x,double z,double radius,double fromAngle,double toAngle) { }
    public record Path(String id,List<Pose> poses,List<TrackCell> cells,List<Arc> arcs) { }
    public record Platform(String id,int railZ,int minX,int maxX,int minZ,int maxZ,int deckY,int canopyY) { }
    public record Plan(List<Path> paths,List<Platform> platforms,int minX,int minZ,int maxX,int maxZ,
                       double bodyLength,double bodyWidth,double samplingStep) { }
    private HubPlan() { }

    public static Plan create() {
        List<Path> paths=new ArrayList<>();List<Platform> platforms=new ArrayList<>();
        for(int i=0;i<4;i++) {
            int z=-122+i*16;
            List<Pose> poses=new ArrayList<>();List<Arc> arcs=new ArrayList<>();
            line(poses,452,-122,416,-122);
            double x=416,zz=-122;
            for(int branch=0;branch<i;branch++) {
                double turnX=416-branch*48;
                line(poses,x,zz,turnX,zz);
                double theta=Math.acos(1-16.0/(2*RADIUS));
                double endX=turnX-2*RADIUS*Math.sin(theta);
                Arc a=new Arc(turnX,zz+RADIUS,RADIUS,-Math.PI/2,-Math.PI/2-theta);
                Arc b=new Arc(endX,zz+16-RADIUS,RADIUS,Math.PI/2-theta,Math.PI/2);
                arc(poses,a);arc(poses,b);arcs.add(a);arcs.add(b);
                x=endX;zz+=16;
            }
            line(poses,x,zz,144,z);
            paths.add(new Path("platform-"+(i+1),List.copyOf(poses),raster(poses),List.copyOf(arcs)));
            platforms.add(new Platform("P"+(i+1),z,144,271,z+2,z+6,64,69));
        }
        return new Plan(List.copyOf(paths),List.copyOf(platforms),128,-135,416,-55,BODY_LENGTH,BODY_WIDTH,.25);
    }
    static void line(List<Pose> out,double ax,double az,double bx,double bz) {
        double length=Math.hypot(bx-ax,bz-az);if(length<1e-8)return;
        int n=(int)Math.ceil(length/.25);double yaw=Math.atan2(bz-az,bx-ax);
        for(int k=out.isEmpty()?0:1;k<=n;k++)out.add(new Pose(ax+(bx-ax)*k/n,az+(bz-az)*k/n,yaw));
    }
    static void arc(List<Pose> out,Arc a) {
        int n=(int)Math.ceil(Math.abs(a.toAngle-a.fromAngle)*a.radius/.25);
        double sign=Math.signum(a.toAngle-a.fromAngle);
        for(int k=1;k<=n;k++) {
            double t=a.fromAngle+(a.toAngle-a.fromAngle)*k/n;
            out.add(new Pose(a.x+a.radius*Math.cos(t),a.z+a.radius*Math.sin(t),t+sign*Math.PI/2));
        }
    }
    static List<TrackCell> raster(List<Pose> poses) {
        List<TrackCell> out=new ArrayList<>();Pose first=poses.getFirst();
        int x=(int)Math.round(first.x),z=(int)Math.round(first.z);
        out.add(new TrackCell(x,Y,z,CellKind.PLATFORM));
        for(Pose p:poses) {
            int tx=(int)Math.round(p.x),tz=(int)Math.round(p.z);
            while(x!=tx || z!=tz) {
                if(x!=tx && (z==tz || Math.abs(p.x-x)>=Math.abs(p.z-z)))x+=Integer.signum(tx-x);
                else z+=Integer.signum(tz-z);
                out.add(new TrackCell(x,Y,z,CellKind.PLATFORM));
            }
        }
        return List.copyOf(out);
    }
    /** Full oriented 8.1m x 2.625m body against every platform/canopy footprint (SAT). */
    public static List<String> sweepProblems(Plan plan) {
        List<String> issues=new ArrayList<>();
        for(Path path:plan.paths)for(Pose p:path.poses) {
            double c=Math.cos(p.yaw),s=Math.sin(p.yaw),hl=plan.bodyLength/2,hw=plan.bodyWidth/2;
            double[][] body=new double[4][2];int k=0;
            for(int[] q:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}})body[k++]=new double[]{p.x+.5+q[0]*hl*c-q[1]*hw*s,p.z+.5+q[0]*hl*s+q[1]*hw*c};
            for(Platform f:plan.platforms) {
                double[][] rect={{f.minX,f.minZ},{f.maxX+1,f.minZ},{f.maxX+1,f.maxZ+1},{f.minX,f.maxZ+1}};
                if(overlap(body,rect)) {issues.add(path.id+" collides with "+f.id+" at "+p.x+","+p.z);return issues;}
            }
            // Only the incoming lead may lie east of the commissioned footprint.
            for(double[] q:body)if(q[0]<plan.minX || q[1]<plan.minZ || q[1]>plan.maxZ+1) {
                issues.add("Swept body outside surveyed hub/lead at "+p);return issues;
            }
        }
        return List.copyOf(issues);
    }
    static boolean overlap(double[][] a,double[][] b) {
        for(double[][] polygon:new double[][][]{a,b})for(int i=0;i<4;i++) {
            double[] p=polygon[i],q=polygon[(i+1)%4];double nx=q[1]-p[1],nz=p[0]-q[0];
            double al=Double.POSITIVE_INFINITY,ah=-al,bl=al,bh=ah;
            for(double[] v:a){double d=v[0]*nx+v[1]*nz;al=Math.min(al,d);ah=Math.max(ah,d);}
            for(double[] v:b){double d=v[0]*nx+v[1]*nz;bl=Math.min(bl,d);bh=Math.max(bh,d);}
            if(ah<=bl+1e-8 || bh<=al+1e-8)return false;
        }
        return true;
    }
    /** Real BlockOps for Factory/RailwayWorks; pure ground lookup must fail on unknown columns.
     * One full stone deck; piers on 8m grid and all platform/canopy supports grounded.
     * Switch rail states are proposals: active route reservation is required before use.
     */
    public static List<BlockOp> operations(Plan plan,ToIntBiFunction<Integer,Integer> ground) {
        if(!sweepProblems(plan).isEmpty())throw new IllegalArgumentException("Hub swept body collision");
        List<BlockOp> ops=new ArrayList<>();
        for(int x=plan.minX;x<=plan.maxX;x++)for(int z=plan.minZ;z<=plan.maxZ;z++) {
            int g=ground.applyAsInt(x,z);
            if(g<16 || g>96)throw new IllegalArgumentException("Hub foundation out of bounds");
            for(int y=Math.max(g,70);y>=65;y--)ops.add(BlockOp.clear(x,y,z));
            if((x-plan.minX)%8==0 && (z-plan.minZ)%8==0)
                for(int y=g+1;y<64;y++)ops.add(BlockOp.place(x,y,z,BlockOp.Part.BRIDGE_PILLAR));
            ops.add(BlockOp.place(x,64,z,BlockOp.Part.BRIDGE_DECK));
        }
        for(Platform p:plan.platforms) {
            for(BlockOp o:Factory.station(0,64,0,128)) {
                String data=o.data();if(data!=null)data=data.replace("north_south","east_west");
                ops.add(new BlockOp(144+o.z(),o.y(),p.railZ+o.x(),o.part(),o.mode(),data));
            }
        }
        for(Path path:plan.paths)for(int i=0;i<path.cells.size();i++) {
            TrackCell c=path.cells.get(i),a=path.cells.get(Math.max(0,i-1)),b=path.cells.get(Math.min(path.cells.size()-1,i+1));
            String shape=RailGeometry.shape(a.x(),a.y(),a.z(),c.x(),c.y(),c.z(),b.x(),b.y(),b.z());
            ops.add(BlockOp.place(c.x(),64,c.z(),BlockOp.Part.BED));
            ops.add(BlockOp.raw(c.x(),Y,c.z(),"minecraft:rail[shape="+shape+"]",BlockOp.Mode.PLACE));
        }
        return List.copyOf(ops);
    }
}
