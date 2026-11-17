// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.hub;
import java.util.*;
import pl.medivalrpg.kolej.build.*;
import org.junit.jupiter.api.Test;

/** Standalone pure regression runner, intentionally needs no Paper or live server. */
public final class HubPlanTest {
    @Test void plannedHubPassesAllPureGeometryChecks(){main(new String[0]);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        var p=HubPlan.create();int assertions=0;
        check(p.paths().size()==4 && p.platforms().size()==4,"four platforms");assertions++;
        check(p.bodyLength()==8.1 && p.bodyWidth()==2.625,"conservative stock envelope includes cysterna's offset");assertions++;
        check(HubPlan.sweepProblems(p).isEmpty(),"8.1m/2.625m body hits platform/canopy");assertions++;
        Set<String> columns=new HashSet<>();
        for(int j=0;j<4;j++) {
            var path=p.paths().get(j);var platform=p.platforms().get(j);
            check(platform.maxX()-platform.minX()+1==128,"platform length");assertions++;
            check(platform.minZ()==platform.railZ()+2 && platform.maxZ()==platform.railZ()+6,"platform offset");assertions++;
            check(path.cells().getFirst().x()==452 && path.cells().getFirst().z()==-122,"common mainline throat");assertions++;
            check(path.cells().getLast().x()==144 && path.cells().getLast().z()==platform.railZ(),"connected destination");assertions++;
            for(var arc:path.arcs()){check(arc.radius()>=32,"minimum radius");assertions++;}
            for(int i=1;i<path.cells().size();i++) {
                var a=path.cells().get(i-1);var b=path.cells().get(i);
                check(Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1 && a.y()==b.y(),"cardinal flat rails");assertions++;
            }
            for(var c:path.cells())columns.add(c.x()+":"+c.z());
        }
        var invalid=new HubPlan.Plan(p.paths(),p.platforms(),p.minX(),p.minZ(),p.maxX(),p.maxZ(),8.1,8,.25);
        check(!HubPlan.sweepProblems(invalid).isEmpty(),"sweep must detect an oversized body");assertions++;
        var ops=HubPlan.operations(p,(x,z)->40);Map<String,BlockOp> finals=new HashMap<>();
        for(var o:ops)finals.put(o.x()+":"+o.y()+":"+o.z(),o);
        for(String column:columns) {
            String[] v=column.split(":");int x=Integer.parseInt(v[0]),z=Integer.parseInt(v[1]);
            check(finals.get(x+":65:"+z).data().contains("rail[shape="),"actual rail block op");assertions++;
        }
        boolean failed=false;try{HubPlan.operations(p,(x,z)->-32768);}catch(IllegalArgumentException expected){failed=true;}
        check(failed,"unknown foundation must fail closed");assertions++;
        System.out.println("HubPlanTest passed: "+assertions+" assertions");
    }
}
