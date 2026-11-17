// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kolej.network.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelClearanceTest {
    static final int RAIL=64;
    static final double RAIL_TOP=.0625;
    static String key(int x,int y,int z){return x+":"+y+":"+z;}
    static List<TrackCell> line(CellKind kind,boolean east,boolean slope) {
        List<TrackCell> cells=new ArrayList<>();
        for(int i=-10;i<=10;i++)cells.add(new TrackCell(east?i:0,RAIL+(slope?i:0),east?0:i,kind));
        return cells;
    }
    static Map<String,BlockOp> finalPlan(List<BlockOp> ops) {
        Map<String,BlockOp> result=new HashMap<>();ops.forEach(o->result.put(key(o.x(),o.y(),o.z()),o));return result;
    }
    @Test void allNineAcceptedModelsIncludingChimneysAndRotatingBogiesFitActualStraightPlans() throws Exception {
        String[] names={"parowoz","tender","pasazerski","salonowy","kryty","weglarka","platforma","cysterna","hamulcowy"};
        for(CellKind kind:List.of(CellKind.TUNNEL,CellKind.BRIDGE,CellKind.GROUND,CellKind.CUTTING))for(boolean east:List.of(false,true)) {
            var cells=line(kind,east,false);
            var plan=finalPlan(Factory.segment(cells,(x,z)->80,(x,z)->40,(x,z)->Integer.MIN_VALUE,CellWorks.Settings.defaults()));
            for(String name:names) {
                // Read-only: no generator, parser probe, or resource writes.
                var model=JsonParser.parseString(Files.readString(Path.of("src/main/resources/models/kolej_"+name+".bbmodel"))).getAsJsonObject();
                Map<String,JsonObject> wheelByCube=new HashMap<>();
                Map<String,JsonObject> groups=new HashMap<>();
                model.getAsJsonArray("groups").forEach(g->groups.put(g.getAsJsonObject().get("uuid").getAsString(),g.getAsJsonObject()));
                wheels(model.getAsJsonArray("outliner"),groups,wheelByCube);
                for(var entry:model.getAsJsonArray("elements")) {
                    JsonObject cube=entry.getAsJsonObject();
                    double[] from=vector(cube,"from"),to=vector(cube,"to"),origin=vector(cube,"origin"),rotation=vector(cube,"rotation");
                    JsonObject wheel=wheelByCube.get(cube.get("uuid").getAsString());
                    for(int phase=0;phase<(wheel==null?1:16);phase++)for(int corner=0;corner<8;corner++) {
                        double[] p={((corner&1)==0?from:to)[0],((corner&2)==0?from:to)[1],((corner&4)==0?from:to)[2]};
                        rotate(p,origin,rotation);
                        if(wheel!=null)rotate(p,vector(wheel,"origin"),new double[]{phase*22.5,0,0});
                        double wx=.5+(east?p[2]:p[0])/16, wz=.5+(east?-p[0]:p[2])/16, wy=RAIL+RAIL_TOP+p[1]/16;
                        BlockOp op=plan.get(key((int)Math.floor(wx),(int)Math.floor(wy),(int)Math.floor(wz)));
                        String detail=name+" "+kind+" east="+east+" cube="+cube.get("name")+" wheelPhase="+phase+" at "+wx+","+wy+","+wz;
                        assertNotNull(op,"Uncleared envelope: "+detail);
                        assertTrue(op.mode()==BlockOp.Mode.CLEAR || op.part()==BlockOp.Part.RAW && op.data().startsWith("minecraft:rail["),"Obstacle "+op+": "+detail);
                    }
                }
            }
        }
    }
    static void wheels(JsonArray out,Map<String,JsonObject> groups,Map<String,JsonObject> result) {
        for(var item:out)if(item.isJsonObject()) {
            var node=item.getAsJsonObject();var group=groups.get(node.get("uuid").getAsString());
            if(group.get("name").getAsString().startsWith("wheel_"))for(var child:node.getAsJsonArray("children"))if(child.isJsonPrimitive())result.put(child.getAsString(),group);
            wheels(node.getAsJsonArray("children"),groups,result);
        }
    }
    static double[] vector(JsonObject object,String field) {
        if(!object.has(field))return new double[3];var a=object.getAsJsonArray(field);return new double[]{a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()};
    }
    static void rotate(double[] p,double[] pivot,double[] rotation) {
        for(int i=0;i<3;i++)p[i]-=pivot[i];
        for(int axis=0;axis<3;axis++) {
            int a=(axis+1)%3,b=(axis+2)%3;double angle=Math.toRadians(rotation[axis]),c=Math.cos(angle),s=Math.sin(angle),v=p[a];
            p[a]=v*c-p[b]*s;p[b]=v*s+p[b]*c;
        }
        for(int i=0;i<3;i++)p[i]+=pivot[i];
    }
    @Test void gradeHasExtraRoofMarginAndConfiguredHeightIsRespected() {
        for(boolean east:List.of(false,true))for(int configured:List.of(3,5,8)) {
            var cells=line(CellKind.TUNNEL,east,true);
            var settings=new CellWorks.Settings(configured,4,0,false,3,0);
            var ops=CellWorks.plan(cells,10,80,40,Integer.MIN_VALUE,(x,z)->east?z==0:x==0,(x,z)->80,settings);
            int height=Math.max(5,configured)+1;
            assertEquals(5,ops.stream().filter(o->o.part()==BlockOp.Part.TUNNEL_CEILING && o.y()==RAIL+height).count());
            for(int side=-2;side<=2;side++)for(int y=RAIL;y<RAIL+height;y++) {
                int x=east?0:side,z=east?side:0;int yy=y;
                assertTrue(ops.stream().anyMatch(o->o.x()==x&&o.z()==z&&o.y()==yy&&o.mode()==BlockOp.Mode.CLEAR));
            }
        }
    }
    @Test void bridgeDeckFiveWideRailingsAndLightsStayOutsideInnerThree() {
        for(boolean east:List.of(false,true)) {
            var cells=line(CellKind.BRIDGE,east,false);
            var settings=new CellWorks.Settings(5,4,1,false,3,0);
            var ops=CellWorks.plan(cells,10,40,40,Integer.MIN_VALUE,(x,z)->east?z==0:x==0,(x,z)->40,settings);
            assertEquals(5,ops.stream().filter(o->o.part()==BlockOp.Part.BRIDGE_DECK).count());
            assertEquals(2,ops.stream().filter(o->o.part()==BlockOp.Part.BRIDGE_RAILING).count());
            assertTrue(ops.stream().filter(o->o.part()==BlockOp.Part.BRIDGE_RAILING||o.part()==BlockOp.Part.LAMP).allMatch(o->Math.abs(east?o.z():o.x())==2));
        }
    }
    @Test void cuttingClearsTallSideHillsButNeverTouchesProtectedTrackColumnsOrOutsideFootprint() {
        for(CellKind kind:List.of(CellKind.GROUND,CellKind.CUTTING,CellKind.TUNNEL,CellKind.BRIDGE)) {
            var cells=line(kind,false,false);var settings=new CellWorks.Settings(5,4,0,false,3,0);
            var ops=CellWorks.plan(cells,10,80,40,Integer.MIN_VALUE,(x,z)->x==1,(x,z)->x==-1?90:80,settings);
            assertTrue(ops.stream().noneMatch(o->o.x()==1));
            assertTrue(ops.stream().allMatch(o->o.z()==0 && Math.abs(o.x())<=(kind==CellKind.TUNNEL?3:kind==CellKind.BRIDGE?2:1)));
            if(kind==CellKind.GROUND||kind==CellKind.CUTTING)for(int y=RAIL;y<=90;y++) {
                int yy=y;assertTrue(ops.stream().anyMatch(o->o.x()==-1&&o.y()==yy&&o.mode()==BlockOp.Mode.CLEAR));
            }
        }
    }
}
