// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import java.util.Arrays;

/**
 * Rewrites biome ids inside serialized chunk sections (pure, no server classes, unit tested).
 *
 * <p>Wire format of Minecraft 26.3 (checked against {@code LevelChunkSection.write} and {@code PalettedContainer}):
 * every section is {@code short nonEmptyBlockCount, short fluidCount, blockStates, biomes}; a paletted container is
 * {@code byte bits}, then for {@code bits == 0} a single VarInt value, for an indirect palette a VarInt count plus that
 * many VarInt ids, for the global palette nothing; then the packed {@code long}s without a length prefix
 * ({@code ceil(entries / (64 / bitsInMemory))} of them). Blocks: 4096 entries, 1–4 bits are stored as 4, up to 8 bits
 * indirect. Biomes: 64 entries, 1–3 bits indirect, more = global ids.</p>
 *
 * <p>Only biome ids change. Block data is copied byte for byte (Paper anti-xray output stays untouched). For single
 * and indirect biome palettes only the palette ids are rewritten; global-palette biome data is repacked in place with
 * the same bit width.</p>
 */
public final class PaletteRemapper {

    public static final int BLOCK_ENTRIES = 4096;
    public static final int BIOME_ENTRIES = 64;
    static final int BLOCK_MAX_INDIRECT_BITS = 8;
    static final int BIOME_MAX_INDIRECT_BITS = 3;

    private PaletteRemapper() {
    }

    /** Malformed or unexpected data: callers must send the original packet unchanged. */
    public static final class MalformedDataException extends Exception {
        private static final long serialVersionUID = 1L;

        public MalformedDataException(final String message) {
            super(message);
        }
    }

    /**
     * Remaps the biomes of the full section buffer of a chunk packet.
     *
     * @param data sections as sent in {@code ClientboundLevelChunkPacketData}
     * @param map  new biome id by old id; ids outside the array stay unchanged
     * @return the rewritten buffer, or {@code null} when no biome id changed
     */
    public static byte[] remapChunkSections(final byte[] data, final int[] map) throws MalformedDataException {
        final Reader in = new Reader(data);
        final Writer out = new Writer(data.length + 64);
        boolean changed = false;
        while (in.remaining() > 0) {
            if (in.remaining() < 4) {
                throw new MalformedDataException("truncated section header");
            }
            final int sectionStart = in.pos;
            in.skip(4);
            skipContainer(in, BLOCK_ENTRIES, BLOCK_MAX_INDIRECT_BITS, true);
            out.copy(data, sectionStart, in.pos - sectionStart);
            changed |= remapBiomeContainer(in, out, map);
        }
        return changed ? out.toArray() : null;
    }

    /** Same for the biome-only buffer of {@code ClientboundChunksBiomesPacket.ChunkBiomeData}. */
    public static byte[] remapBiomeSections(final byte[] data, final int[] map) throws MalformedDataException {
        final Reader in = new Reader(data);
        final Writer out = new Writer(data.length + 64);
        boolean changed = false;
        while (in.remaining() > 0) {
            changed |= remapBiomeContainer(in, out, map);
        }
        return changed ? out.toArray() : null;
    }

    /** Number of packed longs of a container with this many bits in memory. */
    public static int longCount(final int entries, final int bitsInMemory) {
        if (bitsInMemory <= 0) {
            return 0;
        }
        final int perLong = 64 / bitsInMemory;
        return (entries + perLong - 1) / perLong;
    }

    static int blockBitsInMemory(final int bits) {
        return bits >= 1 && bits <= 4 ? 4 : bits;
    }

    private static void skipContainer(final Reader in, final int entries, final int maxIndirect, final boolean blocks)
        throws MalformedDataException {
        final int bits = in.u8();
        if (bits == 0) {
            in.varInt();
            return;
        }
        if (bits > 32) {
            throw new MalformedDataException("bits " + bits);
        }
        if (bits <= maxIndirect) {
            final int n = in.varInt();
            if (n < 0 || n > entries) {
                throw new MalformedDataException("palette size " + n);
            }
            for (int i = 0; i < n; i++) {
                in.varInt();
            }
        }
        in.skip(8L * longCount(entries, blocks ? blockBitsInMemory(bits) : bits));
    }

    private static boolean remapBiomeContainer(final Reader in, final Writer out, final int[] map)
        throws MalformedDataException {
        final int bits = in.u8();
        out.u8(bits);
        if (bits == 0) {
            final int id = in.varInt();
            final int mapped = map(map, id);
            out.varInt(mapped);
            return mapped != id;
        }
        if (bits > 32) {
            throw new MalformedDataException("biome bits " + bits);
        }
        final int longs = longCount(BIOME_ENTRIES, bits);
        boolean changed = false;
        if (bits <= BIOME_MAX_INDIRECT_BITS) {
            final int n = in.varInt();
            if (n < 0 || n > BIOME_ENTRIES) {
                throw new MalformedDataException("biome palette size " + n);
            }
            out.varInt(n);
            for (int i = 0; i < n; i++) {
                final int id = in.varInt();
                final int mapped = map(map, id);
                changed |= mapped != id;
                out.varInt(mapped);
            }
            final int start = in.pos;
            in.skip(8L * longs);
            out.copy(in.data, start, 8 * longs);
            return changed;
        }
        // global palette: ids packed directly, same width before and after (same registry)
        final int perLong = 64 / bits;
        final long mask = (1L << bits) - 1;
        int entry = 0;
        for (int l = 0; l < longs; l++) {
            long value = in.i64();
            for (int slot = 0; slot < perLong && entry < BIOME_ENTRIES; slot++, entry++) {
                final int shift = slot * bits;
                final int id = (int) ((value >>> shift) & mask);
                final int mapped = map(map, id);
                if (mapped != id) {
                    if ((mapped & ~mask) != 0) {
                        throw new MalformedDataException("biome id " + mapped + " does not fit " + bits + " bits");
                    }
                    value = (value & ~(mask << shift)) | ((long) mapped << shift);
                    changed = true;
                }
            }
            out.i64(value);
        }
        return changed;
    }

    private static int map(final int[] map, final int id) {
        return id >= 0 && id < map.length ? map[id] : id;
    }

    /** Bounds-checked big-endian reader. */
    static final class Reader {
        final byte[] data;
        int pos;

        Reader(final byte[] data) {
            this.data = data;
        }

        int remaining() {
            return this.data.length - this.pos;
        }

        void skip(final long n) throws MalformedDataException {
            if (n < 0 || n > this.remaining()) {
                throw new MalformedDataException("truncated data");
            }
            this.pos += (int) n;
        }

        int u8() throws MalformedDataException {
            if (this.pos >= this.data.length) {
                throw new MalformedDataException("truncated data");
            }
            return this.data[this.pos++] & 0xFF;
        }

        long i64() throws MalformedDataException {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | this.u8();
            }
            return v;
        }

        int varInt() throws MalformedDataException {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                final int b = this.u8();
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new MalformedDataException("VarInt too long");
        }
    }

    /** Growable byte writer. */
    static final class Writer {
        private byte[] buf;
        private int size;

        Writer(final int capacity) {
            this.buf = new byte[Math.max(16, capacity)];
        }

        private void ensure(final int extra) {
            if (this.size + extra > this.buf.length) {
                this.buf = Arrays.copyOf(this.buf, Math.max(this.buf.length * 2, this.size + extra));
            }
        }

        void u8(final int v) {
            this.ensure(1);
            this.buf[this.size++] = (byte) v;
        }

        void i64(final long v) {
            this.ensure(8);
            for (int i = 7; i >= 0; i--) {
                this.buf[this.size++] = (byte) (v >>> (8 * i));
            }
        }

        void varInt(final int value) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                this.u8((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            this.u8(v);
        }

        void copy(final byte[] src, final int offset, final int length) {
            this.ensure(length);
            System.arraycopy(src, offset, this.buf, this.size, length);
            this.size += length;
        }

        byte[] toArray() {
            return Arrays.copyOf(this.buf, this.size);
        }
    }
}
