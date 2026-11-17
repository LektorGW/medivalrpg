// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.signal;

import java.util.*;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.infrastructure.SupplyKind.Bounds;

/** Anchor is the solid foundation block; the model origin is its top centre. No durable aspect is stored. */
public record SemaphorePlacement(String id,String node,String segment,String world,int x,int y,int z,int rotation,String foundation) {
    public void validate() {
        if(!RailData.identifier(id) || node==null || node.isBlank() || segment==null || segment.isBlank() || rotation<0 || rotation>3
            || Math.abs((long)x)>30_000_000 || Math.abs((long)z)>30_000_000 || y<-2048 || y>2048 || foundation==null || foundation.isBlank())throw new IllegalArgumentException("Nieprawidłowy zapis semafora");
        UUID.fromString(world);
    }
    /** Conservative union of both arm poses, including the unchanged mast. */
    public Bounds bounds() {
        double loX=Double.POSITIVE_INFINITY,loZ=loX,hiX=Double.NEGATIVE_INFINITY,hiZ=hiX;
        for(double px:new double[]{-.55,2.2})for(double pz:new double[]{-.55,.55}) {
            double rx=switch(rotation){case 1 -> -pz;case 2 -> -px;case 3 -> pz;default -> px;};
            double rz=switch(rotation){case 1 -> px;case 2 -> -pz;case 3 -> -px;default -> pz;};
            loX=Math.min(loX,rx);hiX=Math.max(hiX,rx);loZ=Math.min(loZ,rz);hiZ=Math.max(hiZ,rz);
        }
        return new Bounds(loX,loZ,hiX,hiZ,6.6);
    }
}
