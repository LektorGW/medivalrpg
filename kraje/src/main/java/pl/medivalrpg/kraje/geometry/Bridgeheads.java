// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.geometry;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.util.ChunkKeys;

/** Overseas seeds survive losing their original shore, but disappear when the whole outpost is lost or joined. */
public final class Bridgeheads {
    private Bridgeheads() { }

    public static boolean canEstablish(final Country country,final ChunkPos target,final int limit) {
        return ChunkGraph.isAdjacent(country.claimsIn(target.world()),target.key()) || country.bridgeheads().size()<limit;
    }

    public static Set<Long> component(final Set<Long> land, final long seed) {
        final Set<Long> seen=new HashSet<>();
        if (!land.contains(seed)) return seen;
        final var queue=new ArrayDeque<Long>(); seen.add(seed); queue.add(seed);
        while (!queue.isEmpty()) for (long next:ChunkGraph.neighbours(queue.removeFirst()))
            if (land.contains(next) && seen.add(next)) queue.addLast(next);
        return seen;
    }

    /** Before removing a root, move it within the same existing component, never into another island. */
    public static void removing(final Country country, final ChunkPos removed) {
        if (!country.mutableBridgeheads().remove(removed)) return;
        final Set<Long> old=component(country.claimsIn(removed.world()),removed.key());
        old.remove(removed.key());
        old.stream().min(Long::compare).ifPresent(key -> country.mutableBridgeheads().add(
            new ChunkPos(removed.world(),ChunkKeys.x(key),ChunkKeys.z(key))));
    }

    /** Deduplicate joined outposts and release their slot when they join the capital mainland. */
    public static void normalize(final Country country) {
        if (country.bridgeheads().isEmpty()) return;
        final Set<ChunkPos> keep=new HashSet<>();
        final java.util.Map<String,Set<Long>> seen=new java.util.HashMap<>();
        seen.put(country.capital().world(),component(country.claimsIn(country.capital().world()),country.capital().key()));
        for (final ChunkPos root:country.bridgeheads().stream()
                .sorted(java.util.Comparator.comparing(ChunkPos::world).thenComparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z)).toList()) {
            final Set<Long> worldSeen=seen.computeIfAbsent(root.world(),w->new HashSet<>());
            if (!country.claimsIn(root.world()).contains(root.key()) || worldSeen.contains(root.key())) continue;
            keep.add(root); worldSeen.addAll(component(country.claimsIn(root.world()),root.key()));
        }
        country.mutableBridgeheads().clear(); country.mutableBridgeheads().addAll(keep);
    }
}
