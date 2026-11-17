// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import pl.medivalrpg.skarbiec.money.Denomination;

/**
 * Read-only census of physical coins stored in world files (pure Java): region files ({@code region/r.X.Z.mca},
 * block entities), entity files ({@code entities/r.X.Z.mca}) and player files ({@code players/data/<uuid>.dat}).
 * Files are only read, never locked or written. A chunk payload is parsed only when its bytes contain the
 * {@code skarbiec:coin} marker, so most chunks cost one decompression. Coins are genuine only by their persistent
 * data ({@code PublicBukkitValues."skarbiec:coin"}), exactly like in the game.
 */
public final class DiskCoinScanner {

    public static final byte[] MARKER = "skarbiec:coin".getBytes(StandardCharsets.UTF_8);
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final Set<String> SKIP = Set.of("SelectedItem", "sections", "Sections", "Heightmaps", "structures", "Structures",
        "block_ticks", "fluid_ticks", "PostProcessing", "Lights", "CarvingMasks", "Brain", "Attributes", "attributes");
    private static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024;

    /** Coins of one chunk of a region/entity file. */
    public record ChunkCoins(int cx, int cz, List<CoinTally.Found> found) {
    }

    /** Result of one file. {@code errors} are chunk-level problems; the rest of the file is still counted. */
    public record FileResult(long size, long mtime, List<ChunkCoins> chunks, List<String> errors) {
        public long total() {
            long t = 0;
            for (final ChunkCoins c : this.chunks) {
                for (final CoinTally.Found f : c.found()) {
                    t += f.value();
                }
            }
            return t;
        }
    }

    private DiskCoinScanner() {
    }

    /** Region coordinates from {@code r.X.Z.mca}, or null. */
    public static int[] regionOf(final Path file) {
        final Matcher m = REGION.matcher(file.getFileName().toString());
        return m.matches() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /** Scans a region or entity file of {@code world}. */
    public static FileResult scanRegion(final Path file, final String world) throws IOException {
        final long size = Files.size(file);
        final long mtime = Files.getLastModifiedTime(file).toMillis();
        final byte[] data = Files.readAllBytes(file);
        final List<ChunkCoins> chunks = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final int[] region = regionOf(file);
        if (region == null) {
            throw new IOException("Nieznana nazwa pliku regionu " + file.getFileName());
        }
        if (data.length == 0) {
            return new FileResult(size, mtime, chunks, errors);
        }
        if (data.length < 8192) {
            throw new IOException("Ucięty nagłówek pliku regionu");
        }
        for (int index = 0; index < 1024; index++) {
            final int loc = ((data[index * 4] & 0xFF) << 16) | ((data[index * 4 + 1] & 0xFF) << 8) | (data[index * 4 + 2] & 0xFF);
            final int sectors = data[index * 4 + 3] & 0xFF;
            if (loc == 0 || sectors == 0) {
                continue;
            }
            final int cx = region[0] * 32 + (index & 31);
            final int cz = region[1] * 32 + (index >> 5);
            try {
                final byte[] payload = chunkPayload(file, data, (long) loc * 4096L, sectors, cx, cz);
                if (payload == null || indexOf(payload, MARKER) < 0) {
                    continue;
                }
                final List<CoinTally.Found> found = new ArrayList<>();
                walk(Nbt.read(payload), Ctx.root(world), found, 0);
                if (!found.isEmpty()) {
                    chunks.add(new ChunkCoins(cx, cz, List.copyOf(found)));
                }
            } catch (final IOException | RuntimeException e) {
                errors.add("chunk " + cx + "," + cz + ": " + e.getMessage());
            }
        }
        return new FileResult(size, mtime, chunks, errors);
    }

    private static byte[] chunkPayload(final Path file, final byte[] data, final long offset, final int sectors, final int cx, final int cz)
        throws IOException {
        if (offset < 8192 || offset + 5 > data.length) {
            throw new IOException("przesunięcie poza plikiem (zapis w toku?)");
        }
        final int o = (int) offset;
        final int length = ((data[o] & 0xFF) << 24) | ((data[o + 1] & 0xFF) << 16) | ((data[o + 2] & 0xFF) << 8) | (data[o + 3] & 0xFF);
        final int kind = data[o + 4] & 0xFF;
        if ((kind & 0x80) != 0) {
            final Path external = file.resolveSibling("c." + cx + "." + cz + ".mcc");
            if (!Files.exists(external)) {
                throw new IOException("brak zewnętrznego pliku " + external.getFileName());
            }
            return decompress(kind & 0x7F, Files.readAllBytes(external));
        }
        if (length < 1 || (long) length + 4 > (long) sectors * 4096L || o + 4L + length > data.length) {
            throw new IOException("ucięty zapis chunka (zapis w toku?)");
        }
        final byte[] raw = java.util.Arrays.copyOfRange(data, o + 5, o + 4 + length);
        return decompress(kind, raw);
    }

    static byte[] decompress(final int kind, final byte[] raw) throws IOException {
        return switch (kind) {
            case 1 -> readAll(new GZIPInputStream(new ByteArrayInputStream(raw)));
            case 2 -> readAll(new InflaterInputStream(new ByteArrayInputStream(raw)));
            case 3 -> raw;
            default -> throw new IOException("nieobsługiwana kompresja chunka " + kind + " (np. LZ4)");
        };
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        try (in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_CHUNK_BYTES) {
                    throw new IOException("chunk zbyt duży po rozpakowaniu");
                }
            }
            return out.toByteArray();
        }
    }

    /** Scans a player file; the uuid comes from the file name. {@code worldOf} maps a dimension id to a world name. */
    public static List<CoinTally.Found> scanPlayer(final Path file, final Function<String, String> worldOf) throws IOException {
        final byte[] data = Files.readAllBytes(file);
        final byte[] payload = data.length > 1 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B
            ? readAll(new GZIPInputStream(new ByteArrayInputStream(data))) : data;
        final List<CoinTally.Found> found = new ArrayList<>();
        if (indexOf(payload, MARKER) < 0) {
            return found;
        }
        final String name = file.getFileName().toString();
        final String uuid = name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
        final Object root = Nbt.read(payload);
        if (!(root instanceof Map<?, ?> map)) {
            return found;
        }
        String world = null;
        Integer x = null;
        Integer y = null;
        Integer z = null;
        if (map.get("Dimension") instanceof String dim) {
            world = worldOf.apply(dim);
        }
        if (map.get("Pos") instanceof List<?> pos && pos.size() == 3 && pos.get(0) instanceof Number px) {
            x = (int) Math.floor(px.doubleValue());
            y = (int) Math.floor(((Number) pos.get(1)).doubleValue());
            z = (int) Math.floor(((Number) pos.get(2)).doubleValue());
        }
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            final String key = String.valueOf(e.getKey());
            if (SKIP.contains(key)) {
                continue;
            }
            final boolean ender = key.equals("EnderItems");
            final Ctx ctx = new Ctx(ender ? "ENDER_OFFLINE" : "PLAYER_OFFLINE", ender ? "Ender chest" : "Ekwipunek gracza", uuid, world, x, y, z);
            walk(e.getValue(), ctx, found, 0);
        }
        return found;
    }

    /** Where the walk currently is (holder). */
    record Ctx(String kind, String label, String ownerId, String world, Integer x, Integer y, Integer z) {
        static Ctx root(final String world) {
            return new Ctx("OTHER", "Nieznane", null, world, null, null, null);
        }
    }

    static void walk(final Object node, final Ctx ctx, final List<CoinTally.Found> out, final int depth) {
        if (depth > 256) {
            return;
        }
        if (node instanceof Map<?, ?> tag) {
            Ctx here = ctx;
            final Object id = tag.get("id");
            if (id instanceof String sid) {
                final Integer bx = intOf(tag.get("x"));
                final Integer by = intOf(tag.get("y"));
                final Integer bz = intOf(tag.get("z"));
                if (bx != null && by != null && bz != null && !tag.containsKey("count") && !tag.containsKey("Count")) {
                    here = new Ctx("CONTAINER", CoinTally.holderLabel(sid), ctx.ownerId(), ctx.world(), bx, by, bz);
                } else if (tag.get("Pos") instanceof List<?> pos && pos.size() == 3 && pos.get(0) instanceof Number) {
                    final boolean item = sid.equals("minecraft:item");
                    here = new Ctx(item ? "GROUND" : "ENTITY", CoinTally.holderLabel(sid), ctx.ownerId(), ctx.world(),
                        (int) Math.floor(((Number) pos.get(0)).doubleValue()), (int) Math.floor(((Number) pos.get(1)).doubleValue()),
                        (int) Math.floor(((Number) pos.get(2)).doubleValue()));
                }
                final Denomination d = coin(tag);
                if (d != null) {
                    final Integer count = intOf(tag.containsKey("count") ? tag.get("count") : tag.get("Count"));
                    if (count != null && count > 0) {
                        out.add(new CoinTally.Found(here.kind(), here.label(), here.ownerId(), here.world(), here.x(), here.y(), here.z(),
                            "DISK", d.ordinal(), Math.min(count, 127)));
                    }
                    return;
                }
            }
            for (final Map.Entry<?, ?> e : tag.entrySet()) {
                if (!SKIP.contains(String.valueOf(e.getKey()))) {
                    walk(e.getValue(), here, out, depth + 1);
                }
            }
        } else if (node instanceof List<?> list) {
            for (final Object o : list) {
                walk(o, ctx, out, depth + 1);
            }
        }
    }

    /** Denomination of an item compound, from {@code components."minecraft:custom_data".PublicBukkitValues} (or legacy {@code tag}). */
    static Denomination coin(final Map<?, ?> item) {
        Object pdc = null;
        if (item.get("components") instanceof Map<?, ?> comp && comp.get("minecraft:custom_data") instanceof Map<?, ?> custom) {
            pdc = custom.get("PublicBukkitValues");
        }
        if (pdc == null && item.get("tag") instanceof Map<?, ?> tag) {
            pdc = tag.get("PublicBukkitValues");
        }
        if (pdc instanceof Map<?, ?> values && values.get("skarbiec:coin") instanceof String id) {
            return Denomination.byId(id);
        }
        return null;
    }

    private static Integer intOf(final Object o) {
        return o instanceof Number n && !(o instanceof Double) && !(o instanceof Float) ? n.intValue() : null;
    }

    static int indexOf(final byte[] hay, final byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            if (hay[i] != needle[0]) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
