// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.kolej.build.*;

class OwnStructurePermitTest {
    static final String STONE = "minecraft:stone_bricks";
    static final String TIE = "minecraft:stripped_spruce_log[axis=z]";
    static final UnaryOperator<String> EXACT = s -> s;
    @TempDir Path root;

    static BlockOp bed(int x, String target) { return BlockOp.raw(x, 109, 1198, target, BlockOp.Mode.PLACE); }

    @Test void ownPermitOnlyForMasonryBedIntoSleeperOrBallast() {
        var ops = List.of(bed(4842, TIE), bed(4843, "minecraft:gravel"));
        var policy = NaturalClearance.forPlan(ops, Map.of(), Map.of(), Map.of("4842:109:1198", STONE, "4843:109:1198", STONE));
        assertNull(policy.ownProblem(ops.get(0), STONE, false, EXACT));
        assertNull(policy.ownProblem(ops.get(1), STONE, false, EXACT));
        assertNotNull(policy.ownProblem(ops.get(0), "minecraft:cobblestone", false, EXACT), "fingerprint is exact");
        assertNotNull(policy.ownProblem(ops.get(0), STONE, true, EXACT), "never a tile entity");
        assertNull(policy.ownProblem(BlockOp.raw(1, 1, 1, TIE, BlockOp.Mode.PLACE), "minecraft:oak_planks", false, EXACT), "no permit elsewhere");
        for (String material : List.of("minecraft:oak_planks", "minecraft:chest[facing=north,type=single,waterlogged=false]", "minecraft:stone_brick_stairs[facing=east,half=top,shape=straight,waterlogged=false]", "minecraft:rail[shape=east_west,waterlogged=false]"))
            assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(ops, Map.of(), Map.of(), Map.of("4842:109:1198", material)), material);
        for (BlockOp other : List.of(bed(4842, "minecraft:stone"), bed(4842, "minecraft:stripped_spruce_log[axis=y]"), BlockOp.clear(4842, 109, 1198),
                                     BlockOp.place(4842, 109, 1198, BlockOp.Part.BED), bed(4842, "minecraft:rail[shape=east_west]")))
            assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(List.of(other), Map.of(), Map.of(), Map.of("4842:109:1198", STONE)), other.toString());
        assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(ops, Map.of(), Map.of(), Map.of("1:1:1", STONE)), "permit must belong to an operation");
        assertNull(NaturalClearance.forPlan(ops, Map.of(), Map.of()).ownBefore(ops.get(0)), "the natural-only overload grants nothing");
    }

    static Path gz(Path file, List<String> lines) throws Exception {
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) { for (String l : lines) out.write((l + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        return file;
    }
    static String row(int x, String data, String job) throws Exception {
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return "{\"xyz\":[" + x + ",109,1198],\"beforeBlockData\":\"" + data + "\",\"beforeSha256\":\"" + sha + "\",\"provenanceJob\":\"" + job + "\"}";
    }
    static String sha(Path p) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }

    @Test void ownEvidenceIsHashPinnedBoundToOperationsAndOneJob() throws Exception {
        var ops = List.of(bed(4842, TIE), bed(4843, "minecraft:gravel"));
        Path good = gz(root.resolve("own.jsonl.gz"), List.of(row(4842, STONE, "baza_krolewska"), row(4843, STONE, "baza_krolewska")));
        var own = NetworkCommission.decodeOwn(good, sha(good), ops);
        assertEquals("baza_krolewska", own.job()); assertEquals(2, own.before().size());
        assertThrows(java.io.IOException.class, () -> NetworkCommission.decodeOwn(good, "0".repeat(64), ops));
        Path twoJobs = gz(root.resolve("two.jsonl.gz"), List.of(row(4842, STONE, "baza_krolewska"), row(4843, STONE, "inna_baza")));
        assertThrows(java.io.IOException.class, () -> NetworkCommission.decodeOwn(twoJobs, sha(twoJobs), ops));
        Path foreign = gz(root.resolve("foreign.jsonl.gz"), List.of(row(5000, STONE, "baza_krolewska")));
        assertThrows(java.io.IOException.class, () -> NetworkCommission.decodeOwn(foreign, sha(foreign), ops));
        Path duplicate = gz(root.resolve("dup.jsonl.gz"), List.of(row(4842, STONE, "baza_krolewska"), row(4842, STONE, "baza_krolewska")));
        assertThrows(java.io.IOException.class, () -> NetworkCommission.decodeOwn(duplicate, sha(duplicate), ops));
    }

    @Test void provenanceRequiresTheExactTargetOfACompletedJob() {
        var own = new NetworkCommission.Own(Map.of("4842:109:1198", STONE), "baza_krolewska");
        var entry = new RailwayWorks.Entry(BlockOp.raw(4842, 109, 1198, STONE, BlockOp.Mode.PLACE), "minecraft:grass_block[snowy=false]", STONE);
        var done = new RailwayWorks.Job("baza_krolewska", UUID.randomUUID(), null, List.of(entry), 1, 1, "DONE", "");
        assertNull(NetworkCommission.provenanceProblem(own, Optional.of(done), EXACT));
        assertNotNull(NetworkCommission.provenanceProblem(own, Optional.empty(), EXACT));
        assertNotNull(NetworkCommission.provenanceProblem(own, Optional.of(new RailwayWorks.Job("baza_krolewska", UUID.randomUUID(), null, List.of(entry), 0, 1, "QUEUED", "")), EXACT));
        var otherTarget = new RailwayWorks.Entry(BlockOp.raw(4842, 109, 1198, "minecraft:cobblestone", BlockOp.Mode.PLACE), "minecraft:air", "minecraft:cobblestone");
        assertNotNull(NetworkCommission.provenanceProblem(own, Optional.of(new RailwayWorks.Job("baza_krolewska", UUID.randomUUID(), null, List.of(otherTarget), 1, 1, "DONE", "")), EXACT));
        var elsewhere = new RailwayWorks.Entry(BlockOp.raw(1, 109, 1, STONE, BlockOp.Mode.PLACE), "minecraft:air", STONE);
        assertNotNull(NetworkCommission.provenanceProblem(own, Optional.of(new RailwayWorks.Job("baza_krolewska", UUID.randomUUID(), null, List.of(elsewhere), 1, 1, "DONE", "")), EXACT));
        assertNull(NetworkCommission.provenanceProblem(NetworkCommission.Own.NONE, Optional.empty(), EXACT), "parts without own evidence are unaffected");
    }
    static final String FENCE = "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]";
    static final String WALL = "minecraft:stone_brick_wall[east=low,north=none,south=none,up=false,waterlogged=false,west=low]";
    @Test void ownRailingOnlyIntoStoneParapet() {
        var op = BlockOp.raw(900, 80, 50, WALL, BlockOp.Mode.PLACE);
        var policy = NaturalClearance.forPlan(List.of(op), Map.of(), Map.of(), Map.of("900:80:50", FENCE));
        assertNull(policy.ownProblem(op, FENCE, false, EXACT));
        assertNull(policy.ownProblem(op, "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]", false, EXACT), "derived fence connections may change");
        assertNotNull(policy.ownProblem(op, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=true,west=false]", false, EXACT), "water may not appear");
        assertNotNull(policy.ownProblem(op, "minecraft:spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]", false, EXACT), "other wood");
        assertNotNull(policy.ownProblem(op, "minecraft:air", false, EXACT));
        assertNotNull(policy.ownProblem(op, FENCE, true, EXACT));
        // Pairs are strict: fence never into ballast/sleeper/anything else; masonry bed never into a wall.
        for (String target : List.of("minecraft:gravel", TIE, "minecraft:cobblestone_wall[east=low,north=none,south=none,up=false,waterlogged=false,west=low]",
                "minecraft:stone_brick_wall[east=tall,north=none,south=none,up=false,waterlogged=false,west=low]", "minecraft:air"))
            assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(List.of(BlockOp.raw(900, 80, 50, target, BlockOp.Mode.PLACE)), Map.of(), Map.of(), Map.of("900:80:50", FENCE)), target);
        assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(List.of(op), Map.of(), Map.of(), Map.of("900:80:50", STONE)));
        assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(List.of(op), Map.of(), Map.of(), Map.of("900:80:50", "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=true,west=false]")));
        assertThrows(IllegalArgumentException.class, () -> NaturalClearance.forPlan(List.of(op), Map.of(), Map.of(), Map.of("900:80:50", "minecraft:oak_planks")));
    }
}
