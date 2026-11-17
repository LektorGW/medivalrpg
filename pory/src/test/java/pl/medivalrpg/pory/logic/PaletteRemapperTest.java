// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PaletteRemapperTest {

    /** Reference encoder of the 26.3 wire format (independent of the code under test). */
    static final class Enc {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void u8(final int v) {
            this.out.write(v);
        }

        void i16(final int v) {
            this.out.write(v >>> 8);
            this.out.write(v);
        }

        void varInt(final int value) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                this.out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            this.out.write(v);
        }

        void i64(final long v) {
            for (int i = 7; i >= 0; i--) {
                this.out.write((int) (v >>> (8 * i)));
            }
        }

        /** Container of {@code values} (length = entries) with the given wire bits; indirect if bits <= maxIndirect. */
        void container(final int[] values, final int bits, final int memBits, final int maxIndirect) {
            this.u8(bits);
            if (bits == 0) {
                this.varInt(values[0]);
                return;
            }
            int[] packed = values;
            if (bits <= maxIndirect) {
                final List<Integer> palette = new ArrayList<>(new LinkedHashSet<>(box(values)));
                this.varInt(palette.size());
                palette.forEach(this::varInt);
                final java.util.Map<Integer, Integer> index = new java.util.HashMap<>();
                for (int i = 0; i < palette.size(); i++) {
                    index.put(palette.get(i), i);
                }
                packed = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    packed[i] = index.get(values[i]);
                }
            }
            final int perLong = 64 / memBits;
            final int longs = (values.length + perLong - 1) / perLong;
            for (int l = 0; l < longs; l++) {
                long v = 0;
                for (int s = 0; s < perLong; s++) {
                    final int i = l * perLong + s;
                    if (i < values.length) {
                        v |= (long) packed[i] << (s * memBits);
                    }
                }
                this.i64(v);
            }
        }

        byte[] bytes() {
            return this.out.toByteArray();
        }
    }

    static List<Integer> box(final int[] a) {
        final List<Integer> l = new ArrayList<>();
        for (final int v : a) {
            l.add(v);
        }
        return l;
    }

    /** Reference decoder of biome containers: returns the 64 biome ids of every section. */
    static List<int[]> decodeBiomes(final byte[] data, final boolean withBlocks) throws Exception {
        final PaletteRemapper.Reader in = new PaletteRemapper.Reader(data);
        final List<int[]> out = new ArrayList<>();
        while (in.remaining() > 0) {
            if (withBlocks) {
                in.skip(4);
                final int bits = in.u8();
                if (bits == 0) {
                    in.varInt();
                } else {
                    if (bits <= 8) {
                        final int n = in.varInt();
                        for (int i = 0; i < n; i++) {
                            in.varInt();
                        }
                    }
                    in.skip(8L * PaletteRemapper.longCount(4096, bits <= 4 ? 4 : bits));
                }
            }
            final int bits = in.u8();
            final int[] ids = new int[64];
            if (bits == 0) {
                java.util.Arrays.fill(ids, in.varInt());
            } else {
                int[] palette = null;
                if (bits <= 3) {
                    palette = new int[in.varInt()];
                    for (int i = 0; i < palette.length; i++) {
                        palette[i] = in.varInt();
                    }
                }
                final int perLong = 64 / bits;
                final int longs = PaletteRemapper.longCount(64, bits);
                int e = 0;
                for (int l = 0; l < longs; l++) {
                    final long v = in.i64();
                    for (int s = 0; s < perLong && e < 64; s++, e++) {
                        final int raw = (int) ((v >>> (s * bits)) & ((1L << bits) - 1));
                        ids[e] = palette == null ? raw : palette[raw];
                    }
                }
            }
            out.add(ids);
        }
        return out;
    }

    private static int[] mapping(final int size) {
        final int[] map = new int[size];
        for (int i = 0; i < size; i++) {
            map[i] = i;
        }
        return map;
    }

    @Test
    void singleValueBiomeIsRewritten() throws Exception {
        final Enc e = new Enc();
        e.i16(4096);
        e.i16(0);
        e.container(new int[] {1}, 0, 0, 8); // all stone
        e.container(new int[] {5}, 0, 0, 3); // all biome 5
        final int[] map = mapping(300);
        map[5] = 200; // 2-byte VarInt: buffer grows
        final byte[] out = PaletteRemapper.remapChunkSections(e.bytes(), map);
        assertNotNull(out);
        assertEquals(e.bytes().length + 1, out.length);
        assertEquals(200, decodeBiomes(out, true).get(0)[0]);
    }

    @Test
    void unchangedReturnsNull() throws Exception {
        final Enc e = new Enc();
        e.i16(0);
        e.i16(0);
        e.container(new int[] {0}, 0, 0, 8);
        e.container(new int[] {7}, 0, 0, 3);
        assertNull(PaletteRemapper.remapChunkSections(e.bytes(), mapping(10)));
    }

    @Test
    void blockDataIsCopiedByteForByte() throws Exception {
        final Random rnd = new Random(7);
        final int[] blocks = new int[4096];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = rnd.nextInt(40) + 1;
        }
        final Enc e = new Enc();
        e.i16(4000);
        e.i16(12);
        e.container(blocks, 6, 6, 8);
        e.container(new int[] {3}, 0, 0, 3);
        final byte[] in = e.bytes();
        final int[] map = mapping(10);
        map[3] = 4;
        final byte[] out = PaletteRemapper.remapChunkSections(in, map);
        assertNotNull(out);
        // everything except the last byte (the biome id) is identical
        assertEquals(in.length, out.length);
        for (int i = 0; i < in.length - 1; i++) {
            assertEquals(in[i], out[i], "byte " + i);
        }
        assertEquals(4, out[out.length - 1]);
    }

    @Test
    void randomSectionsRoundTrip() throws Exception {
        final Random rnd = new Random(42);
        for (int round = 0; round < 300; round++) {
            final int registry = 64 + rnd.nextInt(400);
            final int globalBits = 32 - Integer.numberOfLeadingZeros(registry - 1);
            final int[] map = mapping(registry);
            for (int i = 0; i < registry; i++) {
                if (rnd.nextInt(3) == 0) {
                    map[i] = rnd.nextInt(registry);
                }
            }
            final Enc e = new Enc();
            final List<int[]> expected = new ArrayList<>();
            final int sections = 1 + rnd.nextInt(24);
            for (int s = 0; s < sections; s++) {
                e.i16(rnd.nextInt(4097));
                e.i16(rnd.nextInt(4097));
                // blocks: single, linear (wire bits 1..4 stored as 4), hashmap 5..8, global 15
                final int kind = rnd.nextInt(4);
                if (kind == 0) {
                    e.container(new int[] {rnd.nextInt(30000)}, 0, 0, 8);
                } else {
                    final int bits = kind == 1 ? 4 : kind == 2 ? 5 + rnd.nextInt(4) : 15;
                    final int distinct = kind == 3 ? 30000 : Math.min(1 << bits, 2 + rnd.nextInt(1 << bits));
                    final int[] blocks = new int[4096];
                    for (int i = 0; i < blocks.length; i++) {
                        blocks[i] = kind == 3 ? rnd.nextInt(distinct) : 1 + rnd.nextInt(Math.max(1, distinct - 1));
                    }
                    e.container(blocks, bits, bits, 8);
                }
                // biomes: single, indirect 1..3, global
                final int bkind = rnd.nextInt(3);
                final int[] biomes = new int[64];
                if (bkind == 0) {
                    java.util.Arrays.fill(biomes, rnd.nextInt(registry));
                    e.container(biomes, 0, 0, 3);
                } else if (bkind == 1) {
                    final int bits = 1 + rnd.nextInt(3);
                    final int[] pal = new int[1 << bits];
                    for (int i = 0; i < pal.length; i++) {
                        pal[i] = rnd.nextInt(registry);
                    }
                    for (int i = 0; i < 64; i++) {
                        biomes[i] = pal[rnd.nextInt(pal.length)];
                    }
                    if (new LinkedHashSet<>(box(biomes)).size() > (1 << bits)) {
                        throw new IllegalStateException();
                    }
                    e.container(biomes, bits, bits, 3);
                } else {
                    for (int i = 0; i < 64; i++) {
                        biomes[i] = rnd.nextInt(registry);
                    }
                    e.container(biomes, globalBits, globalBits, 3);
                }
                final int[] want = new int[64];
                for (int i = 0; i < 64; i++) {
                    want[i] = map[biomes[i]];
                }
                expected.add(want);
            }
            final byte[] in = e.bytes();
            final byte[] out = PaletteRemapper.remapChunkSections(in, map);
            final List<int[]> got = decodeBiomes(out == null ? in : out, true);
            assertEquals(expected.size(), got.size());
            for (int s = 0; s < got.size(); s++) {
                assertArrayEquals(expected.get(s), got.get(s), "round " + round + " section " + s);
            }
        }
    }

    @Test
    void biomeOnlyBufferRoundTrip() throws Exception {
        final Enc e = new Enc();
        e.container(new int[] {2}, 0, 0, 3);
        final int[] biomes = new int[64];
        for (int i = 0; i < 64; i++) {
            biomes[i] = i % 2 == 0 ? 2 : 9;
        }
        e.container(biomes, 1, 1, 3);
        final int[] map = mapping(20);
        map[2] = 12;
        map[9] = 19;
        final List<int[]> got = decodeBiomes(PaletteRemapper.remapBiomeSections(e.bytes(), map), false);
        assertEquals(12, got.get(0)[0]);
        assertEquals(12, got.get(1)[0]);
        assertEquals(19, got.get(1)[1]);
    }

    @Test
    void idempotentForIdempotentMapping() throws Exception {
        final Enc e = new Enc();
        e.i16(1);
        e.i16(0);
        e.container(new int[] {1}, 0, 0, 8);
        final int[] biomes = new int[64];
        for (int i = 0; i < 64; i++) {
            biomes[i] = i % 3;
        }
        e.container(biomes, 2, 2, 3);
        final int[] map = mapping(10);
        map[0] = 5;
        map[5] = 5;
        final byte[] once = PaletteRemapper.remapChunkSections(e.bytes(), map);
        assertNull(PaletteRemapper.remapChunkSections(once, map));
    }

    @Test
    void malformedDataIsRejected() {
        final int[] map = mapping(10);
        assertThrows(PaletteRemapper.MalformedDataException.class,
            () -> PaletteRemapper.remapChunkSections(new byte[] {0, 1, 0}, map));
        final Enc e = new Enc();
        e.i16(1);
        e.i16(0);
        e.u8(6); // indirect blocks, then nothing
        assertThrows(PaletteRemapper.MalformedDataException.class,
            () -> PaletteRemapper.remapChunkSections(e.bytes(), map));
        final Enc big = new Enc();
        big.u8(0);
        big.u8(0x80);
        big.u8(0x80);
        big.u8(0x80);
        big.u8(0x80);
        big.u8(0x80);
        assertThrows(PaletteRemapper.MalformedDataException.class,
            () -> PaletteRemapper.remapBiomeSections(big.bytes(), map));
    }

    @Test
    void globalIdTooWideIsRejected() {
        final Enc e = new Enc();
        final int[] biomes = new int[64];
        e.container(biomes, 4, 4, 3);
        final int[] map = mapping(64);
        map[0] = 40; // does not fit 4 bits
        assertThrows(PaletteRemapper.MalformedDataException.class,
            () -> PaletteRemapper.remapBiomeSections(e.bytes(), map));
    }

    @Test
    void longCountMatchesVanilla() {
        assertEquals(256, PaletteRemapper.longCount(4096, 4));
        assertEquals(342, PaletteRemapper.longCount(4096, 5));
        assertEquals(1024, PaletteRemapper.longCount(4096, 15));
        assertEquals(1, PaletteRemapper.longCount(64, 1));
        assertEquals(4, PaletteRemapper.longCount(64, 3));
        assertEquals(8, PaletteRemapper.longCount(64, 7));
        assertEquals(0, PaletteRemapper.longCount(64, 0));
        assertEquals(4, PaletteRemapper.blockBitsInMemory(1));
        assertEquals(6, PaletteRemapper.blockBitsInMemory(6));
    }
}
