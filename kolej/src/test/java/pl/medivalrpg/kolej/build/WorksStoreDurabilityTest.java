// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.kolej.network.CellKind;
import pl.medivalrpg.kolej.network.TrackCell;
import static org.junit.jupiter.api.Assertions.*;

/** railperf 2026-10-29: background, coalesced, copy-forward writes of jobs.json in the unchanged format. */
class WorksStoreDurabilityTest {
    @TempDir Path dir;
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID COUNTRY = UUID.fromString("9d1fe36e-4852-4d18-9b2a-a89146f43e66");

    static List<RailwayWorks.Entry> entries(int n, int seed) {
        Random r = new Random(seed); List<RailwayWorks.Entry> list = new ArrayList<>(n);
        String[] before = {"minecraft:grass_block[snowy=false]", "minecraft:dirt", "minecraft:air", "minecraft:water[level=0]",
            "minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]", "minecraft:stone_bricks"};
        String[] target = {"minecraft:gravel", "minecraft:stone_bricks", "minecraft:air", "minecraft:rail[shape=north_south,waterlogged=false]",
            "minecraft:stripped_spruce_log[axis=x]", "minecraft:andesite_slab[type=bottom,waterlogged=false]"};
        for (int i = 0; i < n; i++) {
            int x = seed * 1000 + i, y = 60 + r.nextInt(20), z = -r.nextInt(5000);
            BlockOp op = switch (i % 4) {
                case 0 -> BlockOp.raw(x, y, z, target[r.nextInt(target.length)], BlockOp.Mode.PLACE);
                case 1 -> BlockOp.clear(x, y, z);
                case 2 -> BlockOp.place(x, y, z, BlockOp.Part.BED);
                default -> BlockOp.raw(x, y, z, "minecraft:oak_sapling[stage=0]", BlockOp.Mode.PLACE);
            };
            String t = op.part() == BlockOp.Part.RAW ? op.data() : op.mode() == BlockOp.Mode.CLEAR ? "minecraft:air" : "minecraft:gravel";
            String support = i % 4 == 3 ? "minecraft:grass_block[snowy=false]" : null;
            list.add(new RailwayWorks.Entry(op, i % 4 == 3 ? "minecraft:air" : before[r.nextInt(before.length)], t, support));
        }
        return List.copyOf(list);
    }
    static RailwayWorks.Job job(String id, List<RailwayWorks.Entry> e, int cursor, String status) {
        var cell = new TrackCell(5, 70, -7, CellKind.BRIDGE);
        var check = new RailReuse.Check(cell, "north_south", List.of(new RailReuse.Voxel(5, 71, -7)));
        boolean rich = id.hashCode() % 2 == 0;
        return new RailwayWorks.Job(id, WORLD, rich ? null : COUNTRY, e, cursor, e.size(), status,
            status.equals("BLOCKED") ? "Teren zmienił się po przyjęciu <&='\">" : "", rich ? List.of(check) : List.of(), !rich,
            rich ? List.of(cell, new TrackCell(6, 70, -7, CellKind.GROUND)) : List.of());
    }
    static byte[] gson(RailwayWorks.Snapshot s) { return new Gson().toJson(s).getBytes(StandardCharsets.UTF_8); }
    static RailwayWorks.Snapshot snap(RailwayWorks.Job... jobs) { return new RailwayWorks.Snapshot(1, List.of(jobs)); }
    Path file() { return dir.resolve("works/jobs.json"); }

    @Test void bytesAreExactlyGsonIncludingCopiedEntriesNullsEscapesAndPolishText() throws Exception {
        var a = entries(3000, 1); var b = entries(0, 2); var c = entries(1500, 3);
        WorksStore store = new WorksStore(file());
        try {
            store.read();
            var s1 = snap(job("mr-preclear-01", a, 3000, "DONE"), job("pusty", b, 0, "HELD"), job("mr-factory-s01-p01", c, 10, "QUEUED"));
            store.save(s1).get(10, TimeUnit.SECONDS);
            assertArrayEquals(gson(s1), Files.readAllBytes(file()));
            assertEquals(0, store.copiedBytes());
            for (int cursor = 11; cursor < 15; cursor++) {
                var next = snap(job("mr-preclear-01", a, 3000, "DONE"), job("pusty", b, 0, "HELD"), job("mr-factory-s01-p01", c, cursor, cursor == 14 ? "BLOCKED" : "QUEUED"));
                long before = store.copiedBytes();
                store.save(next).get(10, TimeUnit.SECONDS);
                assertArrayEquals(gson(next), Files.readAllBytes(file()), "save " + cursor);
                assertTrue(store.copiedBytes() > before, "unchanged entries are copied, not serialized again");
            }
            var reordered = snap(job("mr-factory-s01-p01", c, 1500, "DONE"), job("nowe", entries(10, 9), 0, "QUEUED"), job("mr-preclear-01", a, 3000, "DONE"));
            store.save(reordered).get(10, TimeUnit.SECONDS);
            assertArrayEquals(gson(reordered), Files.readAllBytes(file()));
            assertEquals(reordered, store.read());
        } finally { store.shutdown(); }
    }

    @Test void rollbackBuildReadsNewFilesAndNewBuildReadsRollbackFiles() throws Exception {
        var s = snap(job("a", entries(500, 4), 200, "QUEUED"), job("b", entries(20, 5), 20, "DONE"));
        LegacyWorksStore legacy = new LegacyWorksStore(file());
        try { legacy.save(s).get(10, TimeUnit.SECONDS); } finally { legacy.shutdown(); }
        WorksStore store = new WorksStore(file());
        try {
            assertEquals(s, store.read());
            var t = snap(job("a", s.jobs().get(0).entries(), 300, "QUEUED"), s.jobs().get(1));
            store.save(t).get(10, TimeUnit.SECONDS);
            store.save(snap(job("a", s.jobs().get(0).entries(), 301, "QUEUED"), s.jobs().get(1))).get(10, TimeUnit.SECONDS);
        } finally { store.shutdown(); }
        LegacyWorksStore again = new LegacyWorksStore(file());
        try {
            assertEquals(301, again.read().jobs().get(0).cursor());
            // a31656bb keeps writing with its own rules on top of our file and backup
            again.save(snap(job("a", s.jobs().get(0).entries(), 302, "QUEUED"))).get(10, TimeUnit.SECONDS);
            assertEquals(302, again.read().jobs().get(0).cursor());
        } finally { again.shutdown(); }
        assertEquals(301, new WorksStore(file().resolveSibling("jobs.json.bak")).read().jobs().get(0).cursor());
    }

    @Test void coalescesBurstsAndAcknowledgesEveryCallerOnlyAfterADurableNewerSnapshot() throws Exception {
        var gate = new CountDownLatch(1); var entered = new CountDownLatch(1);
        WorksStore store = new WorksStore(file(), stage -> {
            if (stage.equals("tmp-synced") && entered.getCount() > 0) {
                entered.countDown();
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            }
        });
        try {
            store.read();
            var e = entries(2000, 6);
            List<CompletableFuture<Void>> acks = new ArrayList<>();
            acks.add(store.save(snap(job("f", e, 0, "QUEUED"))));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            for (int i = 1; i <= 200; i++) acks.add(store.save(snap(job("f", e, i, i == 200 ? "DONE" : "QUEUED"))));
            Thread.sleep(50);
            assertTrue(acks.stream().noneMatch(CompletableFuture::isDone), "nothing is acknowledged before fsync+rename");
            assertTrue(store.durableSeq() < store.submittedSeq(), "DONE is not durable yet");
            gate.countDown();
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            assertEquals(store.submittedSeq(), store.durableSeq());
            assertTrue(store.writes() <= 3, "201 saves coalesced into " + store.writes() + " writes");
            var last = store.read().jobs().getFirst();
            assertEquals(200, last.cursor()); assertEquals("DONE", last.status());
        } finally { gate.countDown(); store.shutdown(); }
    }

    @Test void crashAtEveryStageLeavesTheLastDurableSnapshotReadableAndFailureIsSticky() throws Exception {
        for (String stage : List.of("tmp-partial", "tmp-synced", "bak-linked", "bak-moved", "renamed")) {
            Path f = dir.resolve(stage).resolve("jobs.json");
            var e = entries(800, 7);
            var s1 = snap(job("p", e, 100, "QUEUED")); var s2 = snap(job("p", e, 400, "QUEUED"));
            var s0 = snap(job("p", e, 10, "QUEUED"));
            var armed = new java.util.concurrent.atomic.AtomicBoolean(false);
            WorksStore crashing = new WorksStore(f, at -> { if (armed.get() && at.equals(stage)) throw new IOException("awaria dysku przy " + at); });
            try {
                crashing.read();
                crashing.save(s0).get(10, TimeUnit.SECONDS);
                crashing.save(s1).get(10, TimeUnit.SECONDS);
                armed.set(true);
                var failed = crashing.save(s2);
                var error = assertThrows(ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
                assertTrue(error.getCause().getMessage().contains(stage));
                assertTrue(crashing.failure().isPresent());
                assertThrows(ExecutionException.class, () -> crashing.save(s1).get(10, TimeUnit.SECONDS), "sticky: works stay stopped");
            } finally { crashing.shutdown(); }
            // Restart: a fresh store sees a complete version (the new one only once the rename happened).
            WorksStore restarted = new WorksStore(f);
            try {
                var read = restarted.read();
                assertEquals(stage.equals("renamed") ? s2 : s1, read, stage);
                assertArrayEquals(gson(read), Files.readAllBytes(f));
                var s3 = snap(job("p", e, 500, "QUEUED"));
                restarted.save(s3).get(10, TimeUnit.SECONDS);
                assertEquals(s3, new WorksStore(f).read());
                assertEquals(read, decode(f.resolveSibling("jobs.json.bak")), "backup = the version before the restart write");
            } finally { restarted.shutdown(); }
        }
    }

    @Test void tornTemporaryFilesFromAKilledProcessAreIgnoredAndOverwritten() throws Exception {
        var e = entries(300, 8); var s1 = snap(job("p", e, 30, "QUEUED"));
        WorksStore a = new WorksStore(file());
        try { a.read(); a.save(s1).get(10, TimeUnit.SECONDS); } finally { a.shutdown(); }
        byte[] full = gson(snap(job("p", e, 99, "QUEUED")));
        Files.write(file().resolveSibling("jobs.json.tmp"), Arrays.copyOf(full, full.length / 2));
        Files.write(file().resolveSibling("jobs.json.bak.tmp"), new byte[] {1, 2, 3});
        WorksStore b = new WorksStore(file());
        try {
            assertEquals(s1, b.read());
            var s2 = snap(job("p", e, 31, "QUEUED"));
            b.save(s2).get(10, TimeUnit.SECONDS);
            assertArrayEquals(gson(s2), Files.readAllBytes(file()));
            assertEquals(s1, decode(file().resolveSibling("jobs.json.bak")));
        } finally { b.shutdown(); }
    }

    @Test void damagedCurrentFileIsNeverPromotedToBackup() throws Exception {
        var e = entries(100, 10); var good = snap(job("p", e, 1, "QUEUED"));
        WorksStore a = new WorksStore(file());
        try { a.read(); a.save(snap(job("p", e, 0, "QUEUED"))).get(10, TimeUnit.SECONDS); a.save(good).get(10, TimeUnit.SECONDS); } finally { a.shutdown(); }
        Files.writeString(file(), "{\"version\":1,\"jobs\":[{\"id\":");
        WorksStore b = new WorksStore(file());
        try {
            var recovered = b.read();
            assertEquals(0, recovered.jobs().getFirst().cursor(), "falls back to .bak");
            b.save(snap(job("p", e, 2, "QUEUED"))).get(10, TimeUnit.SECONDS);
            assertEquals(recovered, decode(file().resolveSibling("jobs.json.bak")), "good backup kept");
        } finally { b.shutdown(); }
    }

    @Test void fileChangedBehindTheWriterIsSerializedAgainNotCopied() throws Exception {
        var e = entries(1000, 11); var other = entries(1000, 12);
        WorksStore store = new WorksStore(file());
        try {
            store.read();
            store.save(snap(job("p", e, 1, "QUEUED"))).get(10, TimeUnit.SECONDS);
            // Same size, different bytes, but a different modification time: the remembered ranges are void.
            byte[] alien = gson(snap(job("p", other, 1, "QUEUED")));
            Files.write(file(), alien);
            Files.setLastModifiedTime(file(), FileTime.fromMillis(1_000_000L));
            var next = snap(job("p", e, 2, "QUEUED"));
            long copied = store.copiedBytes();
            store.save(next).get(10, TimeUnit.SECONDS);
            assertEquals(copied, store.copiedBytes());
            assertArrayEquals(gson(next), Files.readAllBytes(file()));
        } finally { store.shutdown(); }
    }

    /** Restart in the middle of building: the file always holds a complete earlier snapshot, never a newer cursor. */
    @Test void restartDuringBuildResumesFromADurableCursorThatNeverRunsAhead() throws Exception {
        var e = entries(5000, 13);
        for (int crashAt : new int[] {3, 7, 15}) {
            Path f = dir.resolve("restart-" + crashAt).resolve("jobs.json");
            var writes = new java.util.concurrent.atomic.AtomicInteger();
            WorksStore store = new WorksStore(f, stage -> {
                if (stage.equals("tmp-partial") && writes.incrementAndGet() == crashAt) throw new IOException("kill -9");
            });
            int placed = 0, acknowledged = -1;
            try {
                store.read();
                Map<CompletableFuture<Void>, Integer> sent = new LinkedHashMap<>();
                for (int step = 0; store.failure().isEmpty() && step < 20_000; step++) {
                    placed = Math.min(e.size(), placed + 3); // the workers keep placing while earlier snapshots are written
                    sent.put(store.save(snap(job("mr-factory", e, placed, "QUEUED"))), placed);
                    if (step % 5 == 0) Thread.sleep(1);
                }
                assertTrue(store.failure().isPresent(), "simulated crash happened");
                for (var s : sent.entrySet()) if (!s.getKey().isCompletedExceptionally() && s.getKey().isDone()) acknowledged = Math.max(acknowledged, s.getValue());
            } finally { store.shutdown(); }
            var resumed = new WorksStore(f).read().jobs().getFirst();
            assertTrue(resumed.cursor() <= placed, "durable cursor never ahead of the world");
            assertTrue(resumed.cursor() >= acknowledged, "every acknowledged save survives the crash");
            assertEquals(e, resumed.entries());
        }
    }

    static RailwayWorks.Snapshot decode(Path p) throws IOException {
        var s = new Gson().fromJson(Files.readString(p), RailwayWorks.Snapshot.class);
        RailwayWorks.validateSnapshot(s); return s;
    }
}
