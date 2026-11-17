// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCoinScannerTest {

    @TempDir
    Path dir;

    private static byte[] zlib(final byte[] raw) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(out)) {
            z.write(raw);
        }
        return out.toByteArray();
    }

    /** Region file with the given chunk payloads at chunk indexes (zlib, compression type 2). */
    private Path region(final String name, final Map<Integer, byte[]> chunks) throws Exception {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final byte[] header = new byte[8192];
        int sector = 2;
        for (final Map.Entry<Integer, byte[]> e : chunks.entrySet()) {
            final byte[] data = zlib(e.getValue());
            final int length = data.length + 1;
            final int sectors = (length + 4 + 4095) / 4096;
            final int i = e.getKey() * 4;
            header[i] = (byte) (sector >> 16);
            header[i + 1] = (byte) (sector >> 8);
            header[i + 2] = (byte) sector;
            header[i + 3] = (byte) sectors;
            final byte[] rec = new byte[sectors * 4096];
            rec[0] = (byte) (length >>> 24);
            rec[1] = (byte) (length >>> 16);
            rec[2] = (byte) (length >>> 8);
            rec[3] = (byte) length;
            rec[4] = 2;
            System.arraycopy(data, 0, rec, 5, data.length);
            body.write(rec);
            sector += sectors;
        }
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(header);
        file.write(body.toByteArray());
        final Path p = this.dir.resolve(name);
        Files.write(p, file.toByteArray());
        return p;
    }

    @Test
    void findsCoinsInChestsAndNestedShulkersButNotFakes() throws Exception {
        final Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("id", "minecraft:chest");
        chest.put("x", 40);
        chest.put("y", 64);
        chest.put("z", -20);
        final Map<String, Object> shulker = Map.of("id", "minecraft:red_shulker_box", "count", 1,
            "components", Map.of("minecraft:container", List.of(Map.of("slot", 0, "item", NbtWriter.coin("gold", 3)))));
        // A gold nugget named like a coin but WITHOUT the persistent data is not money.
        final Map<String, Object> fake = Map.of("id", "minecraft:gold_nugget", "count", 64,
            "components", Map.of("minecraft:custom_name", "skarbiec:coin"));
        chest.put("Items", List.of(NbtWriter.coin("silver", 5), shulker, fake));
        final Map<String, Object> chunk = Map.of("xPos", 1, "zPos", -2, "block_entities", List.of(chest),
            "sections", List.of(Map.of("y", 0)));
        final Map<String, Object> empty = Map.of("xPos", 0, "zPos", 0, "block_entities", List.of());
        // region r.0.-1: chunk (1, -2) → local (1, 30) → index 30*32+1
        final Path file = this.region("r.0.-1.mca", new LinkedHashMap<>(Map.of(30 * 32 + 1, NbtWriter.write(chunk), 0, NbtWriter.write(empty))));
        final DiskCoinScanner.FileResult r = DiskCoinScanner.scanRegion(file, "world");
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals(1, r.chunks().size());
        assertEquals(1, r.chunks().get(0).cx());
        assertEquals(-2, r.chunks().get(0).cz());
        assertEquals(5 * 10 + 3 * 100, r.total());
        for (final CoinTally.Found f : r.chunks().get(0).found()) {
            assertEquals("CONTAINER", f.kind());
            assertEquals(40, f.x());
            assertEquals(-20, f.z());
            assertEquals("DISK", f.source());
            assertTrue(f.label().startsWith("Skrzynia"));
        }
    }

    @Test
    void groundItemsAndEntitiesHaveTheirPosition() throws Exception {
        final Map<String, Object> item = Map.of("id", "minecraft:item", "Pos", List.of(10.5, 70.0, -3.2), "Item", NbtWriter.coin("copper", 7));
        final Map<String, Object> frame = Map.of("id", "minecraft:item_frame", "Pos", List.of(1.0, 2.0, 3.0), "Item", NbtWriter.coin("gold", 1));
        final Map<String, Object> chunk = Map.of("Position", List.of(0, 0), "Entities", List.of(item, frame));
        final Path file = this.region("r.0.0.mca", Map.of(0, NbtWriter.write(chunk)));
        final var found = DiskCoinScanner.scanRegion(file, "world").chunks().get(0).found();
        assertEquals(2, found.size());
        final CoinTally.Found ground = found.stream().filter(f -> f.kind().equals("GROUND")).findFirst().orElseThrow();
        assertEquals(10, ground.x());
        assertEquals(-4, ground.z());
        assertEquals(7, ground.value());
        assertTrue(found.stream().anyMatch(f -> f.kind().equals("ENTITY") && f.value() == 100));
    }

    @Test
    void offlinePlayerInventoryAndEnderChestAreSeparated() throws Exception {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("Dimension", "minecraft:overworld");
        root.put("Pos", List.of(100.0, 65.0, 200.0));
        root.put("Inventory", List.of(NbtWriter.coin("gold", 2)));
        root.put("EnderItems", List.of(NbtWriter.coin("silver", 4)));
        root.put("SelectedItem", NbtWriter.coin("gold", 2)); // presentation copy of a slot: never counted twice
        final ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(NbtWriter.write(root));
        }
        final Path p = this.dir.resolve("6a0e1f2c-1111-2222-3333-444455556666.dat");
        Files.write(p, gz.toByteArray());
        final var found = DiskCoinScanner.scanPlayer(p, dim -> dim.equals("minecraft:overworld") ? "world" : dim);
        assertEquals(2, found.size());
        final CoinTally tally = new CoinTally();
        tally.addAll(found);
        assertEquals(240, tally.total());
        assertTrue(found.stream().anyMatch(f -> f.kind().equals("PLAYER_OFFLINE") && f.value() == 200 && "world".equals(f.world()) && f.x() == 100));
        assertTrue(found.stream().anyMatch(f -> f.kind().equals("ENDER_OFFLINE") && f.value() == 40));
        assertTrue(found.stream().allMatch(f -> "6a0e1f2c-1111-2222-3333-444455556666".equals(f.ownerId())));
    }

    @Test
    void tornChunkIsReportedWithoutLosingTheRestOfTheFile() throws Exception {
        final Map<String, Object> chest = Map.of("id", "minecraft:barrel", "x", 1, "y", 2, "z", 3, "Items", List.of(NbtWriter.coin("gold", 1)));
        final Path file = this.region("r.0.0.mca", new LinkedHashMap<>(Map.of(0, NbtWriter.write(Map.of("block_entities", List.of(chest))),
            1, NbtWriter.write(Map.of("block_entities", List.of(chest))))));
        final byte[] data = Files.readAllBytes(file);
        data[4 + 3] = (byte) 200; // second chunk claims sectors beyond the end of the file
        data[4 + 2] = (byte) 120;
        Files.write(file, data);
        final DiskCoinScanner.FileResult r = DiskCoinScanner.scanRegion(file, "world");
        assertEquals(1, r.errors().size());
        assertEquals(100, r.total());
    }

    @Test
    void markerSearchAndRegionNames() {
        assertEquals(2, DiskCoinScanner.indexOf("abskarbiec:coin".getBytes(), DiskCoinScanner.MARKER));
        assertEquals(-1, DiskCoinScanner.indexOf("skarbiec:coi".getBytes(), DiskCoinScanner.MARKER));
        assertEquals(-3, DiskCoinScanner.regionOf(Path.of("r.-3.7.mca"))[0]);
        assertFalse(DiskCoinScanner.regionOf(Path.of("c.1.2.mcc")) != null);
    }

    @Test
    void tallyGroupsByPlaceAndTruncates() {
        final CoinTally t = new CoinTally();
        for (int i = 0; i < 5; i++) {
            t.add(new CoinTally.Found("CONTAINER", "Skrzynia", null, "world", i, 64, 0, "LIVE", 2, i + 1));
        }
        t.add(new CoinTally.Found("CONTAINER", "Skrzynia", null, "world", 4, 64, 0, "LIVE", 0, 10));
        assertEquals(5, t.places());
        assertEquals(6, t.stacks());
        final Map<String, Object> m = t.toMap(3, id -> id);
        assertEquals(true, m.get("truncated"));
        final List<?> locations = (List<?>) m.get("locations");
        assertEquals(3, locations.size());
        assertEquals(510L, ((Map<?, ?>) locations.get(0)).get("amount"));
        assertEquals(1510L, m.get("total"));
    }
}
