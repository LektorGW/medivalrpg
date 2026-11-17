// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import java.util.UUID;

/** Bounded vanilla naval gameplay and ownership rules, independent of Bukkit. */
public final class NavalRules {
    public enum ExitRoute { SHORE, WATER }
    /** Leaving a ship must never become a prison merely because the coast is outside the search radius. */
    public static ExitRoute exitRoute(final boolean safeShore) {return safeShore?ExitRoute.SHORE:ExitRoute.WATER;}
    public static boolean releaseForTeleport(final boolean seat,final boolean tag,final boolean internal,final boolean exiting) {
        return (seat || tag) && !internal && !exiting;
    }
    public static final int GLOBAL_MAX=24, COUNTRY_MAX=3;
    public static final long PROOF_MILLIS=120_000, RELOAD_MILLIS=10_000, VOLLEY_COST=30;
    public record Type(String id,int era,long cost,int soldiers,int seats,double health,double halfWidth,double halfLength) { }
    private NavalRules() { }
    public static Type type(final String id) {
        if ("transportowiec".equals(id)) return new Type(id,1,3000,16,4,100,1.5,4.5);
        if ("karawela".equals(id)) return new Type(id,2,6000,32,6,160,2,5.5);
        return null;
    }
    public static boolean canBuild(final Type type,final int era,final int global,final int country) {
        return type!=null && era>=type.era() && global>=0 && country>=0 && global<GLOBAL_MAX && country<COUNTRY_MAX;
    }
    public static boolean canLandOn(final UUID country,final UUID owner,final boolean war) {
        return country!=null && (owner==null || country.equals(owner) || war);
    }
    public static boolean enemy(final UUID country,final UUID other,final boolean war) {
        return country!=null && other!=null && !country.equals(other) && war;
    }
    public static double distanceToChunkSq(final double x,final double z,final int cx,final int cz) {
        final double minX=(long)cx*16,minZ=(long)cz*16;
        final double dx=Math.max(minX-x,Math.max(0,x-(minX+16)));
        final double dz=Math.max(minZ-z,Math.max(0,z-(minZ+16)));
        return dx*dx+dz*dz;
    }
    public static boolean transportProof(final boolean own,final boolean sameWorld,final boolean loaded,
                                         final double shipDistanceSq,final double sailed,final boolean forcePresent,
                                         final long proofAt,final long now) {
        return own && sameWorld && loaded && Double.isFinite(shipDistanceSq) && shipDistanceSq>=0 && shipDistanceSq<=48*48
            && Double.isFinite(sailed) && sailed>=24 && forcePresent && proofAt>=0 && proofAt<=now && now-proofAt<=PROOF_MILLIS;
    }
    public static boolean volley(final boolean karawela,final boolean enemy,final double distanceSq,
                                  final long lastVolley,final long now,final int flights) {
        return karawela && enemy && distanceSq>=16 && distanceSq<=48*48 && lastVolley>=0 && now>=lastVolley
            && now-lastVolley>=RELOAD_MILLIS && flights>=0 && flights<24;
    }
}
