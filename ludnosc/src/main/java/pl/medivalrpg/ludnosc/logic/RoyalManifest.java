// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Deliberately narrow, reviewed reconstruction manifest for one authorised country and one exact rectangle. */
public final class RoyalManifest {
    public static final UUID COUNTRY=UUID.fromString("9d1fe36e-4852-4d18-9b2a-a89146f43e66");
    public int schema=1;
    public UUID countryId;
    public String world;
    public Rect rect;
    public int groundY=90,clearTopY=158,foundationMinY=54;
    public Point square;
    public Capital capital;
    public List<NativeBuilding> buildings=new ArrayList<>();
    public List<Road> roads=new ArrayList<>();
    public Walls walls;
    public Provisions provisions;
    public static final class Provisions {public long foodStock=10000;public int farmerTarget=100;}
    public static final class Rect {public int minChunkX,minChunkZ,widthChunks,depthChunks;}
    public static final class Point {public int x,y,z;}
    public static final class Capital {public int chunkX,chunkZ;}
    public static final class Footprint {public int minX,minZ,maxX,maxZ;}
    public static final class NativeBuilding {
        public String id,type,dye="blue";
        public int x,z,groundY=90,rotation,level;
        public Footprint box;
    }
    public static final class Road {
        public List<int[]> points=new ArrayList<>();
        public int width=9;
        public String material="stone_bricks";
        public String alignment="centered";
    }
    public static final class Walls {
        public int inset=4,height=8;
        public String material="stone_bricks";
        public List<Gate> gates=new ArrayList<>();
    }
    public static final class Gate {public String side;public int centre,width=7;}
    public int minX(){return rect.minChunkX*16;}
    public int minZ(){return rect.minChunkZ*16;}
    public int maxX(){return minX()+rect.widthChunks*16-1;}
    public int maxZ(){return minZ()+rect.depthChunks*16-1;}
    public boolean contains(int x,int z){return x>=minX()&&x<=maxX()&&z>=minZ()&&z<=maxZ();}
    public static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    public static int[] holdingCell(final int index){return new int[]{4849+Math.floorMod(index,15),163,1021+Math.floorMod(index/15,15)};}
    public static int[] publicCell(final int index){return new int[]{4852+Math.floorMod(index,9),91,1024+Math.floorMod(index/9,9)};}
    public static boolean mayBecomeFarmer(final boolean adult,final String profession,final long current,final int target){return adult&&"minecraft:none".equals(profession)&&current<target;}
    public static boolean accessCoordinate(final int x,final int y,final int z){return x>=4720&&x<=4991&&z>=848&&z<=1119&&y>=54&&y<=158&&(x<4724||x>4987||z<852||z>1115);}
    public static boolean templateAllowed(final UUID country,final String type){return !"palac_krola_maciusa".equalsIgnoreCase(type)||COUNTRY.equals(country);}
    public static java.util.Set<Long> desiredChunks(){final java.util.Set<Long> chunks=new java.util.LinkedHashSet<>();for(int z=53;z<=69;z++)for(int x=295;x<=311;x++)chunks.add(key(x,z));return chunks;}
    public static java.util.Set<Long> roadColumns(final Road road) {
        final java.util.Set<Long> cells=new java.util.LinkedHashSet<>();
        final int low=road.alignment.equals("positive")?0:-road.width/2,high=low+road.width-1;
        for(int n=1;n<road.points.size();n++) {
            final int[] a=road.points.get(n-1),b=road.points.get(n);final int length=Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]));
            for(int step=0;step<=length;step++) {
                final int x=length==0?a[0]:(int)Math.round(a[0]+(b[0]-a[0])*(step/(double)length));
                final int z=length==0?a[1]:(int)Math.round(a[1]+(b[1]-a[1])*(step/(double)length));
                for(int dx=low;dx<=high;dx++)for(int dz=low;dz<=high;dz++)cells.add(key(x+dx,z+dz));
            }
        }return cells;
    }

    public void validate() {
        if(schema!=1||!COUNTRY.equals(countryId)||!"world".equals(world))throw new IllegalArgumentException("Nieprawidłowy kraj, świat lub wersja manifestu.");
        if(rect==null||rect.minChunkX!=295||rect.minChunkZ!=53||rect.widthChunks!=17||rect.depthChunks!=17)
            throw new IllegalArgumentException("Dozwolony jest tylko uzgodniony prostokąt 295..311 × 53..69.");
        if(groundY!=90||clearTopY!=158||foundationMinY!=54)throw new IllegalArgumentException("Oczekiwane poziomy: fundament54, teren90, czyszczenie158.");
        if(square==null||square.x!=4856||square.z!=1028||square.y!=groundY+1)
            throw new IllegalArgumentException("Nieprawidłowy bezpieczny rynek.");
        if(capital==null||capital.chunkX!=303||capital.chunkZ!=61)throw new IllegalArgumentException("Nieprawidłowa nowa stolica.");
        if(buildings==null||buildings.isEmpty()||buildings.size()>500)throw new IllegalArgumentException("Brak budynków lub przekroczony limit500.");
        final HashSet<String> ids=new HashSet<>();
        boolean palace=false;
        for(final NativeBuilding b:buildings) {
            if(b==null||b.id==null||!b.id.matches("[a-zA-Z0-9_-]{1,80}")||!ids.add(b.id)
                ||b.type==null||!b.type.matches("[a-z0-9_-]{1,80}")||b.rotation<0||b.rotation>3||b.level<0||b.level>16
                ||b.groundY!=groundY||!contains(b.x,b.z))throw new IllegalArgumentException("Nieprawidłowy lub powtórzony budynek manifestu.");
            if(b.box!=null&&(!contains(b.box.minX,b.box.minZ)||!contains(b.box.maxX,b.box.maxZ)||b.box.minX>b.box.maxX||b.box.minZ>b.box.maxZ))
                throw new IllegalArgumentException("Budynek wychodzi poza uzgodniony teren: "+b.id);
            palace|="palac_krola_maciusa".equals(b.type);
        }
        if(!palace)throw new IllegalArgumentException("Brak uzgodnionego pałacu palac_krola_maciusa.");
        if(roads==null||roads.size()>200)throw new IllegalArgumentException("Nieprawidłowa lista dróg.");
        long roadWork=0;
        for(final Road road:roads) {
            if(road==null||road.width<1||road.width>15||(road.width&1)==0||road.points==null||road.points.size()<2||road.points.size()>500)
                throw new IllegalArgumentException("Nieprawidłowa droga.");
            if(!List.of("positive","centered").contains(road.alignment))throw new IllegalArgumentException("Nieznana geometria drogi.");
            final int low=road.alignment.equals("positive")?0:-road.width/2,high=low+road.width-1;
            for(final int[] p:road.points)if(p==null||p.length!=2||!contains(p[0]+low,p[1]+low)
                ||!contains(p[0]+high,p[1]+high))throw new IllegalArgumentException("Droga poza terenem.");
            for(int n=1;n<road.points.size();n++){final int[] a=road.points.get(n-1),b=road.points.get(n);roadWork+=(long)(Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]))+1)*road.width*road.width;}
            if(roadWork>5_000_000)throw new IllegalArgumentException("Manifest dróg wymaga zbyt dużej pracy przygotowawczej.");
        }
        if(provisions!=null&&(provisions.foodStock<0||provisions.foodStock>100000||provisions.farmerTarget<0||provisions.farmerTarget>200))
            throw new IllegalArgumentException("Nieprawidłowe zapasy lub liczba rolników.");
        if(walls==null||walls.inset!=4||walls.height!=8||walls.gates==null||walls.gates.size()!=4)
            throw new IllegalArgumentException("Wymagane mury8, odsunięcie4 oraz cztery bramy.");
        final HashSet<String> sides=new HashSet<>();
        for(final Gate g:walls.gates) {
            if(g==null||g.side==null||!List.of("north","south","east","west").contains(g.side)||!sides.add(g.side)||g.width!=7)
                throw new IllegalArgumentException("Nieprawidłowe bramy.");
            final int lo=g.side.equals("north")||g.side.equals("south")?minX():minZ();
            final int hi=g.side.equals("north")||g.side.equals("south")?maxX():maxZ();
            if(g.centre-3<lo+walls.inset||g.centre+3>hi-walls.inset)throw new IllegalArgumentException("Brama poza murem.");
        }
    }
}
