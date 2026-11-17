// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Test helper: writes Java maps/lists as NBT (compound root named ""). Integers → TAG_Int, Double → TAG_Double. */
final class NbtWriter {

    private NbtWriter() {
    }

    static byte[] write(final Map<String, Object> root) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        payload(out, root);
        out.flush();
        return bytes.toByteArray();
    }

    private static int type(final Object o) {
        if (o instanceof Byte) {
            return 1;
        }
        if (o instanceof Short) {
            return 2;
        }
        if (o instanceof Integer) {
            return 3;
        }
        if (o instanceof Long) {
            return 4;
        }
        if (o instanceof Float) {
            return 5;
        }
        if (o instanceof Double) {
            return 6;
        }
        if (o instanceof String) {
            return 8;
        }
        if (o instanceof List) {
            return 9;
        }
        if (o instanceof Map) {
            return 10;
        }
        throw new IllegalArgumentException("type " + o);
    }

    @SuppressWarnings("unchecked")
    private static void payload(final DataOutputStream out, final Object o) throws IOException {
        switch (type(o)) {
            case 1 -> out.writeByte((Byte) o);
            case 2 -> out.writeShort((Short) o);
            case 3 -> out.writeInt((Integer) o);
            case 4 -> out.writeLong((Long) o);
            case 5 -> out.writeFloat((Float) o);
            case 6 -> out.writeDouble((Double) o);
            case 8 -> out.writeUTF((String) o);
            case 9 -> {
                final List<Object> list = (List<Object>) o;
                out.writeByte(list.isEmpty() ? 0 : type(list.get(0)));
                out.writeInt(list.size());
                for (final Object e : list) {
                    payload(out, e);
                }
            }
            case 10 -> {
                for (final Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    out.writeByte(type(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(0);
            }
            default -> throw new IllegalStateException();
        }
    }

    /** A genuine coin item compound as Paper stores it. */
    static Map<String, Object> coin(final String denom, final int count) {
        return Map.of("id", "minecraft:gold_nugget", "count", count,
            "components", Map.of("minecraft:custom_data", Map.of("PublicBukkitValues", Map.of("skarbiec:coin", denom))));
    }
}
