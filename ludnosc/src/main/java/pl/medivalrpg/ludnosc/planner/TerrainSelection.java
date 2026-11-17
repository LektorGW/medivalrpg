// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Bounded, non-destructive manual grading proposal shared by preview and execution. */
public final class TerrainSelection {
    public static final int MAX_AREA = 4096;
    public static final int MAX_CHANGE = 6;
    public static final int RAMP = 4;
    public record Result(int targetY, List<int[]> columns, int protectedColumns, int excessiveColumns, long blocks) { }
    private TerrainSelection() { }

    public static boolean valid(final int minX, final int minZ, final int maxX, final int maxZ) {
        return minX <= maxX && minZ <= maxZ && Math.abs((long) minX) <= 29_999_980
            && (long)maxX-minX+1<=128 && (long)maxZ-minZ+1<=128
            && Math.abs((long) maxX) <= 29_999_980 && Math.abs((long) minZ) <= 29_999_980
            && Math.abs((long) maxZ) <= 29_999_980
            && ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) <= MAX_AREA;
    }

    public static Result plan(final TerrainView view, final int minX, final int minZ, final int maxX, final int maxZ,
                              final Integer requestedY, final BiPredicate<Integer, Integer> protectedColumn) {
        if (!valid(minX,minZ,maxX,maxZ)) throw new IllegalArgumentException("Zaznaczenie może mieć najwyżej 4096 bloków.");
        final List<Integer> samples = new ArrayList<>();
        for (int z=minZ;z<=maxZ;z++) for (int x=minX;x<=maxX;x++) {
            if (!view.own(x,z)) throw new IllegalArgumentException("Całe zaznaczenie musi należeć do kraju.");
            if (view.height(x,z)==TerrainView.UNKNOWN) throw new IllegalArgumentException("Teren jest jeszcze skanowany. Spróbuj za chwilę.");
            if (!protectedColumn.test(x,z) && !view.obstacle(x,z) && !view.liquid(x,z)) samples.add(view.height(x,z));
        }
        if (samples.isEmpty()) throw new IllegalArgumentException("Brak wolnego, suchego terenu do wyrównania.");
        samples.sort(Integer::compare);
        final int target=requestedY==null ? samples.get(samples.size()/2) : requestedY;
        final Terraform.Plan terra=Terraform.plan(view::height,minX,minZ,maxX,maxZ,target,new Terraform.Settings(0,RAMP,2));
        final List<int[]> cols=new ArrayList<>();
        int protectedCount=0, excessive=0;
        long blocks=0;
        for (final Terraform.Column c:terra.columns()) {
            if (!c.changed()) continue;
            if (!view.own(c.x(),c.z()) || view.height(c.x(),c.z())==TerrainView.UNKNOWN || view.liquid(c.x(),c.z())
                || view.obstacle(c.x(),c.z()) || protectedColumn.test(c.x(),c.z())) { protectedCount++; continue; }
            if (Math.abs(c.newTop()-c.oldTop())>MAX_CHANGE) { excessive++; continue; }
            // Zero plot flag: never clear arbitrary player-built leftovers above this manually selected ground.
            cols.add(new int[]{c.x(),c.z(),c.oldTop(),c.newTop(),c.wallBottom(),c.wallTop(),0});
            blocks+=Math.abs(c.newTop()-c.oldTop());
        }
        return new Result(target,List.copyOf(cols),protectedCount,excessive,blocks);
    }
}
