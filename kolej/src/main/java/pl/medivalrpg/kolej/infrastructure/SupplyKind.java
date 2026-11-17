// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.infrastructure;

import java.util.Locale;

/** Only these five models are service facilities. Bounds are from infrastructure-models/asset-index.json. */
public enum SupplyKind {
    ZURAW("kolej_zuraw_wodny", false, -9, -9, 41.5, 9, 57),
    WIEZA("kolej_wieza_cisnien", false, -28, -29, 34, 28, 105),
    POMPA("kolej_pompa_reczna", false, -12.41603, -10, 14, 10, 33.62404),
    ZBIORNIK("kolej_zbiornik_stacjonarny", false, -15.5, -26, 20.5, 30.5, 43),
    WEGIEL("kolej_bunkier_weglowy", true, -28, -24, 30.5, 46.79251, 66);

    public final String model;
    public final boolean coal;
    private final double minX, minZ, maxX, maxZ, height;
    SupplyKind(String model, boolean coal, double minX, double minZ, double maxX, double maxZ, double height) {
        this.model=model;this.coal=coal;this.minX=minX/16;this.minZ=minZ/16;this.maxX=maxX/16;this.maxZ=maxZ/16;this.height=height/16;
    }
    public int units() {return coal?10:250;}
    public String material() {return coal?"COAL":"WATER_BUCKET";}
    public static SupplyKind parse(String text) {
        try {return valueOf(text.toUpperCase(Locale.ROOT));}
        catch (IllegalArgumentException e) {throw new IllegalArgumentException("Typ: zuraw|wieza|pompa|zbiornik|wegiel.");}
    }
    public record Bounds(double minX,double minZ,double maxX,double maxZ,double height) {
        public boolean contains(double x,double z,double margin) {return x>=minX-margin && x<=maxX+margin && z>=minZ-margin && z<=maxZ+margin;}
        public boolean intersects(Bounds b) {return minX<b.maxX && maxX>b.minX && minZ<b.maxZ && maxZ>b.minZ;}
    }
    /** Minecraft positive yaw rotates local +Z towards -X. */
    public Bounds bounds(int quarter) {
        if(quarter<0 || quarter>3)throw new IllegalArgumentException("Obrót: 0|90|180|270.");
        double loX=Double.POSITIVE_INFINITY,loZ=loX,hiX=Double.NEGATIVE_INFINITY,hiZ=hiX;
        for(double x:new double[]{minX,maxX})for(double z:new double[]{minZ,maxZ}) {
            double rx=switch(quarter){case 1 -> -z;case 2 -> -x;case 3 -> z;default -> x;};
            double rz=switch(quarter){case 1 -> x;case 2 -> -z;case 3 -> -x;default -> z;};
            loX=Math.min(loX,rx);hiX=Math.max(hiX,rx);loZ=Math.min(loZ,rz);hiZ=Math.max(hiZ,rz);
        }
        return new Bounds(loX,loZ,hiX,hiZ,height);
    }
}
