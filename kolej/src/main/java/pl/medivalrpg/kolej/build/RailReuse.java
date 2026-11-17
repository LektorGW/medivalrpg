// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.*;
import pl.medivalrpg.kolej.network.TrackCell;

/** Exact physical rail reuse. A footprint alone is never proof that the route exists. */
public final class RailReuse {
    private RailReuse() { }
    public record Voxel(int x,int y,int z) { }
    public record Check(TrackCell cell,String shape,List<Voxel> clearance) {
        public Check {Objects.requireNonNull(cell);Objects.requireNonNull(shape);clearance=List.copyOf(clearance);}
    }
    public record Sample(String railShape,boolean support,boolean passable,boolean permitted) { }
    public interface View {Sample at(int x,int y,int z);}
    public static Check expected(List<TrackCell> cells,int index) {
        TrackCell c=cells.get(index),a=cells.get(Math.max(0,index-1)),b=cells.get(Math.min(cells.size()-1,index+1));
        String shape=RailGeometry.shape(a.x(),a.y(),a.z(),c.x(),c.y(),c.z(),b.x(),b.y(),b.z());
        boolean curve=a.x()!=b.x() && a.z()!=b.z(),grade=a.y()!=c.y() || b.y()!=c.y();
        int top=c.y()+4+(grade?1:0),sideX=a.x()==b.x()?1:0,sideZ=sideX==1?0:1;
        List<Voxel> clearance=new ArrayList<>();
        if(curve) {
            for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int y=c.y();y<=top;y++)
                if(dx!=0 || dz!=0 || y!=c.y())clearance.add(new Voxel(c.x()+dx,y,c.z()+dz));
        }else for(int side=-1;side<=1;side++)for(int y=c.y();y<=top;y++)
            if(side!=0 || y!=c.y())clearance.add(new Voxel(c.x()+sideX*side,y,c.z()+sideZ*side));
        return new Check(c,shape,clearance);
    }
    public static String problem(Check check,View view) {
        for(int i=0;i<inspections(check);i++){String problem=problemAt(check,i,view);if(problem!=null)return problem;}
        return null;
    }
    public static int inspections(Check check){return check.clearance.size()+2;}
    /** One voxel only, so the live adapter can honour the same per-tick budget for wide curves. */
    public static String problemAt(Check check,int index,View view) {
        TrackCell c=check.cell;
        if(index==0){Sample rail=view.at(c.x(),c.y(),c.z());if(!rail.permitted)return "Brak aktualnej zgody na używany tor";return check.shape.equals(rail.railShape)?null:"Brak toru na wymaganej wysokości lub niezgodny kształt szyny";}
        if(index==1){Sample bed=view.at(c.x(),c.y()-1,c.z());return bed.permitted && bed.support?null:"Brak bezpiecznego podparcia używanego toru";}
        Voxel v=check.clearance.get(index-2);Sample sample=view.at(v.x,v.y,v.z);
        return sample.permitted && sample.passable?null:"Brak zgody lub prześwitu używanego toru";
    }
}
