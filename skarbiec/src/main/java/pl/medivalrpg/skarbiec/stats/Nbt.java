// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal read-only NBT decoder for the coin census (pure Java). Compounds become {@link Map}, lists {@link List},
 * numbers boxed primitives, strings {@link String}; arrays stay primitive arrays. Depth and size are bounded so a
 * damaged file cannot exhaust memory.
 */
public final class Nbt {

    private static final int MAX_DEPTH = 512;
    private static final int MAX_LIST = 1 << 22;

    private Nbt() {
    }

    /** Named root tag (files and region chunks); returns the root payload. */
    public static Object read(final byte[] bytes) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        final int type = in.readUnsignedByte();
        if (type == 0) {
            return null;
        }
        in.readUTF();
        return payload(in, type, 0);
    }

    private static Object payload(final DataInput in, final int type, final int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT zbyt głęboko zagnieżdżone");
        }
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                final byte[] b = new byte[size(in.readInt())];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                final int elem = in.readUnsignedByte();
                final int n = size(in.readInt());
                final List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    list.add(payload(in, elem, depth + 1));
                }
                return list;
            }
            case 10: {
                final Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    final int t = in.readUnsignedByte();
                    if (t == 0) {
                        return map;
                    }
                    final String name = in.readUTF();
                    map.put(name, payload(in, t, depth + 1));
                }
            }
            case 11: {
                final int n = size(in.readInt());
                final int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                final int n = size(in.readInt());
                final long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default: throw new IOException("Nieznany typ NBT " + type);
        }
    }

    private static int size(final int n) throws IOException {
        if (n < 0 || n > MAX_LIST) {
            throw new IOException("Nieprawidłowa długość NBT " + n);
        }
        return n;
    }
}
