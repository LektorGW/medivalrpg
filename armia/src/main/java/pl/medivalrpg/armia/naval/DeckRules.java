// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry for a moving deck; coordinates do not load or change a world. */
public final class DeckRules {
    public record Frame(double x,double y,double z,float yaw) { }
    public record Point(double x,double y,double z) { }
    public record Tile(double x,double z,double scale) { }
    private DeckRules() { }

    public static double tileScale(final NavalRules.Type type) {
        requireType(type);
        return type.halfWidth();
    }

    public static double deckHeight(final NavalRules.Type type) {return 1+tileScale(type);}

    /** Two overlapping strips of square collision tiles, wholly inside the visible hull. */
    public static List<Tile> tiles(final NavalRules.Type type) {
        final double scale=tileScale(type),end=type.halfLength()-.7-scale/2;
        if(end<0) throw new IllegalArgumentException("Deck is shorter than its collision tiles");
        final int intervals=(int)Math.ceil(2*end/(scale*.9));
        if(2L*(intervals+1)>16) throw new IllegalArgumentException("Deck exceeds collision tile budget");
        final List<Tile> result=new ArrayList<>(2*(intervals+1));
        for(int row=0;row<=intervals;row++) {
            final double z=intervals==0?0:-end+2*end*row/intervals;
            result.add(new Tile(-scale*.45,z,scale));
            result.add(new Tile(scale*.45,z,scale));
        }
        return List.copyOf(result);
    }

    public static Point toLocal(final Frame frame,final Point point) {
        requireFinite(frame,point);
        final double yaw=Math.toRadians(frame.yaw()),c=Math.cos(yaw),s=Math.sin(yaw);
        final double dx=point.x()-frame.x(),dz=point.z()-frame.z();
        return new Point(dx*c+dz*s,point.y()-frame.y(),-dx*s+dz*c);
    }

    public static Point toWorld(final Frame frame,final Point point) {
        requireFinite(frame,point);
        final double yaw=Math.toRadians(frame.yaw()),c=Math.cos(yaw),s=Math.sin(yaw);
        return new Point(frame.x()+point.x()*c-point.z()*s,frame.y()+point.y(),frame.z()+point.x()*s+point.z()*c);
    }

    public static Point carry(final Frame before,final Frame after,final Point point) {
        return toWorld(after,toLocal(before,point));
    }

    public static float yawDelta(final float before,final float after) {
        if(!Float.isFinite(before) || !Float.isFinite(after)) throw new IllegalArgumentException("Non-finite yaw");
        double delta=((double)after-before)%360;
        if(delta>180) delta-=360;
        if(delta< -180) delta+=360;
        return (float)delta;
    }

    public static boolean onDeck(final NavalRules.Type type,final Frame frame,final Point point) {
        if(!validType(type) || !finite(frame,point)) return false;
        final Point local=toLocal(frame,point);
        return Math.abs(local.x())<=type.halfWidth()-.12 && Math.abs(local.z())<=walkableHalfLength(type)
            && local.y()>=deckHeight(type)-.2 && local.y()<=deckHeight(type)+1.65;
    }

    /** Safe at every yaw, including corners where axis-aligned collision tiles turn away. */
    public static double walkableHalfLength(final NavalRules.Type type) {
        final double scale=tileScale(type),end=type.halfLength()-.7-scale/2;
        final double lateral=Math.max(scale*.45,type.halfWidth()-.12-scale*.45);
        // A radius inside the Minkowski sum of a tile AABB and the player's 0.6-wide AABB.
        final double radius=scale/2+.3-.005;
        return Math.min(type.halfLength()-.55,end+Math.sqrt(Math.max(0,radius*radius-lateral*lateral)));
    }

    public static boolean walkableOnDeck(final NavalRules.Type type,final Frame frame,final Point point) {
        return onDeck(type,frame,point);
    }

    private static boolean validType(final NavalRules.Type type) {
        return type!=null && Double.isFinite(type.halfWidth()) && Double.isFinite(type.halfLength())
            && type.halfWidth()>.12 && type.halfLength()>.55;
    }
    private static void requireType(final NavalRules.Type type) {
        if(!validType(type)) throw new IllegalArgumentException("Invalid deck dimensions");
    }
    private static boolean finite(final Frame frame,final Point point) {
        return frame!=null && point!=null && Double.isFinite(frame.x()) && Double.isFinite(frame.y())
            && Double.isFinite(frame.z()) && Float.isFinite(frame.yaw()) && Double.isFinite(point.x())
            && Double.isFinite(point.y()) && Double.isFinite(point.z());
    }
    private static void requireFinite(final Frame frame,final Point point) {
        if(!finite(frame,point)) throw new IllegalArgumentException("Non-finite deck coordinates");
    }
}
