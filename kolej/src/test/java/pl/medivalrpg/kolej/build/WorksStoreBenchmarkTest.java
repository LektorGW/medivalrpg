// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Timing of one jobs.json save on a synthetic snapshot the size of the main line after the mr-factory parts
 * (~620k entries). Opt-in: KOLEJ_BENCH=1 (needs ~2.5 GB test heap), result appended to KOLEJ_BENCH_OUT.
 */
@EnabledIfEnvironmentVariable(named = "KOLEJ_BENCH", matches = "1")
class WorksStoreBenchmarkTest {
    @TempDir Path dir;

    static RailwayWorks.Snapshot mainline(int factoryEntries, int cursor) {
        List<RailwayWorks.Job> jobs = new ArrayList<>();
        for (int i = 1; i <= 51; i++) jobs.add(WorksStoreDurabilityTest.job("mr-preclear-" + i, WorksStoreDurabilityTest.entries(200, i), 200, "DONE"));
        int[] parts = {101651, 101143, 80418, 87853, 83815, 79783, 42171, 130000, 98743}; // maxCost of the factory parts
        long sum = Arrays.stream(parts).sum(); int left = factoryEntries;
        for (int i = 0; i < parts.length; i++) {
            int n = i == parts.length - 1 ? left : (int) (factoryEntries * (parts[i] / (double) sum)); left -= n;
            var e = WorksStoreDurabilityTest.entries(n, 100 + i);
            boolean active = i == parts.length - 1;
            jobs.add(WorksStoreDurabilityTest.job("mr-factory-" + i, e, active ? Math.min(cursor, n) : n, active ? "QUEUED" : "DONE"));
        }
        return new RailwayWorks.Snapshot(1, jobs);
    }
    static RailwayWorks.Snapshot withCursor(RailwayWorks.Snapshot s, int cursor) {
        List<RailwayWorks.Job> jobs = new ArrayList<>(s.jobs()); var j = jobs.getLast();
        jobs.set(jobs.size() - 1, new RailwayWorks.Job(j.id(), j.world(), j.country(), j.entries(), cursor, j.cost(), j.status(), j.reason(), j.reused(), j.financeHeld(), j.routeCells()));
        return new RailwayWorks.Snapshot(1, jobs);
    }

    @Test void mrFactorySizedSave() throws Exception {
        int total = Integer.parseInt(System.getenv().getOrDefault("KOLEJ_BENCH_ENTRIES", "620000"));
        var base = mainline(total, 1000);
        int entries = base.jobs().stream().mapToInt(j -> j.entries().size()).sum();
        StringBuilder out = new StringBuilder("jobs.json benchmark, entries=" + entries + ", jobs=" + base.jobs().size() + "\n");

        Path legacyFile = dir.resolve("legacy/jobs.json");
        LegacyWorksStore legacy = new LegacyWorksStore(legacyFile);
        List<Long> legacyMs = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                long t = System.nanoTime();
                legacy.save(withCursor(base, 1000 + i)).get(10, TimeUnit.MINUTES);
                legacyMs.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t));
            }
        } finally { legacy.shutdown(); }
        out.append("a31656bb (legacy) save ms: ").append(legacyMs).append(", size MB ").append(Files.size(legacyFile) / 1_000_000).append('\n');

        Path newFile = dir.resolve("new/jobs.json");
        WorksStore store = new WorksStore(newFile);
        List<Long> newMs = new ArrayList<>();
        try {
            store.read();
            for (int i = 0; i < 8; i++) {
                long t = System.nanoTime();
                store.save(withCursor(base, 1000 + i)).get(10, TimeUnit.MINUTES);
                newMs.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t));
            }
            out.append("railperf save ms (1st = full serialization after start, then copy-forward): ").append(newMs)
                .append(", copied MB ").append(store.copiedBytes() / 1_000_000).append(", serialized MB ").append(store.serializedBytes() / 1_000_000).append('\n');
            // Burst: 1000 progress saves submitted without waiting (workers never block); measure until all durable.
            long t = System.nanoTime(); java.util.concurrent.CompletableFuture<Void> last = null; long writesBefore = store.writes();
            for (int i = 0; i < 1000; i++) last = store.save(withCursor(base, 2000 + i));
            long submitUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t);
            last.get(10, TimeUnit.MINUTES);
            out.append("burst 1000 saves: submit total us ").append(submitUs).append(", durable after ms ")
                .append(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t)).append(", physical writes ").append(store.writes() - writesBefore).append('\n');
            assertArrayEquals(WorksStoreDurabilityTest.gson(withCursor(base, 2999)), Files.readAllBytes(newFile));
        } finally { store.shutdown(); }
        assertTrue(Files.size(dir.resolve("new/jobs.json.bak")) > 0);
        System.out.print(out);
        String target = System.getenv("KOLEJ_BENCH_OUT");
        if (target != null) Files.writeString(Path.of(target), out, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
