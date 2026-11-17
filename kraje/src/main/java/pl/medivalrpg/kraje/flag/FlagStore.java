// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Persistence of {@link FlagBook} ({@code plugins/Kraje/flags/flags.json}, tmp + atomic move + {@code .bak}) and of the
 * normalised images ({@code flags/images/<imageId>.png}). Writes go through one background thread in submission order;
 * reads of the small PNGs happen on the caller's thread.
 */
public final class FlagStore {

    static final Pattern IMAGE_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final Path images;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Kraje-flagi-zapis");
        t.setDaemon(true);
        return t;
    });

    public FlagStore(final Path dir, final Logger logger) {
        this.dir = dir;
        this.images = dir.resolve("images");
        this.logger = logger;
    }

    public Path dir() {
        return this.dir;
    }

    // ------------------------------------------------------------------ images

    public Path imagePath(final String imageId) {
        if (imageId == null || !IMAGE_ID.matcher(imageId).matches()) {
            throw new IllegalArgumentException("Nieprawidłowy identyfikator flagi.");
        }
        return this.images.resolve(imageId + ".png");
    }

    public boolean hasImage(final String imageId) {
        return imageId != null && IMAGE_ID.matcher(imageId).matches() && Files.isRegularFile(this.imagePath(imageId));
    }

    /** Synchronous write (the upload must be durable before the panel says "czeka na zatwierdzenie"). */
    public void writeImage(final String imageId, final byte[] png) throws IOException {
        final Path target = this.imagePath(imageId);
        Files.createDirectories(this.images);
        if (Files.isRegularFile(target) && Files.size(target) == png.length) {
            // same image uploaded again: refresh the age so a garbage collection queued earlier keeps it
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return;
        }
        final Path tmp = target.resolveSibling(imageId + ".png.tmp");
        Files.write(tmp, png);
        move(tmp, target);
    }

    public byte[] readImage(final String imageId) {
        try {
            return Files.readAllBytes(this.imagePath(imageId));
        } catch (final IOException e) {
            return null;
        }
    }

    /** Files younger than this are never collected (the reference snapshot may predate their upload). */
    static final long GC_MIN_AGE_MILLIS = 60 * 60_000L;

    /**
     * Deletes image files nobody references any more (asynchronously). {@code referenced} is a main-thread snapshot that
     * can be older than a concurrent upload, so only files older than {@link #GC_MIN_AGE_MILLIS} are removed.
     */
    public void collectGarbage(final Set<String> referenced) {
        this.writer.execute(() -> {
            if (!Files.isDirectory(this.images)) {
                return;
            }
            try (var files = Files.list(this.images)) {
                for (final Path p : files.toList()) {
                    final String name = p.getFileName().toString();
                    if (name.endsWith(".png") && !referenced.contains(name.substring(0, name.length() - 4))
                        && System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() > GC_MIN_AGE_MILLIS) {
                        Files.deleteIfExists(p);
                    }
                }
            } catch (final IOException e) {
                this.logger.log(Level.WARNING, "Sprzątanie obrazków flag nie powiodło się", e);
            }
        });
    }

    // ------------------------------------------------------------------ book

    public FlagBook load() {
        final Path file = this.dir.resolve("flags.json");
        for (final Path p : new Path[] {file, this.dir.resolve("flags.json.bak")}) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                return decode(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (final IOException | RuntimeException e) {
                this.logger.log(Level.SEVERE, "Nie udało się wczytać " + p + " — próbuję kopii.", e);
            }
        }
        return new FlagBook();
    }

    public void saveAsync(final FlagBook book) {
        final String json = GSON.toJson(encode(book));
        this.writer.execute(() -> this.write(json));
    }

    public void shutdown(final FlagBook book) {
        if (book != null) {
            final String json = GSON.toJson(encode(book));
            this.writer.execute(() -> this.write(json));
        }
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis flag nie zakończył się w 10 s.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(final String json) {
        try {
            Files.createDirectories(this.dir);
            final Path file = this.dir.resolve("flags.json");
            final Path tmp = this.dir.resolve("flags.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.isRegularFile(file)) {
                Files.copy(file, this.dir.resolve("flags.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            move(tmp, file);
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać flags.json", e);
        }
    }

    static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ codec

    static JsonObject encode(final FlagBook book) {
        final JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("lastRebuildAt", book.lastRebuildAt);
        root.addProperty("nextRebuildAt", book.nextRebuildAt);
        final JsonObject packed = new JsonObject();
        book.packed.forEach((k, v) -> packed.addProperty(k.toString(), v));
        root.add("packed", packed);
        final JsonObject spots = new JsonObject();
        book.capitalSpots.forEach((k, s) -> {
            final JsonObject o = new JsonObject();
            o.addProperty("world", s.world());
            o.addProperty("x", s.x());
            o.addProperty("y", s.y());
            o.addProperty("z", s.z());
            o.addProperty("yaw", s.yaw());
            spots.add(k.toString(), o);
        });
        root.add("capitalSpots", spots);
        final JsonArray decisions = new JsonArray();
        for (final FlagBook.Decision d : book.decisions) {
            final JsonObject o = new JsonObject();
            o.addProperty("country", d.country().toString());
            o.addProperty("imageId", d.imageId());
            o.addProperty("status", d.status().name());
            o.addProperty("at", d.at());
            o.addProperty("by", d.by());
            o.addProperty("note", d.note());
            decisions.add(o);
        }
        root.add("decisions", decisions);
        final JsonObject countries = new JsonObject();
        for (final Map.Entry<UUID, FlagBook.Country> e : book.countries.entrySet()) {
            final FlagBook.Country c = e.getValue();
            final JsonObject o = new JsonObject();
            o.addProperty("current", c.current);
            o.addProperty("approvedAt", c.approvedAt);
            o.addProperty("approvedBy", c.approvedBy);
            o.addProperty("lastUploadAt", c.lastUploadAt);
            if (c.pending != null) {
                final FlagBook.Pending p = c.pending;
                final JsonObject po = new JsonObject();
                po.addProperty("imageId", p.imageId());
                po.addProperty("uploadedAt", p.uploadedAt());
                po.addProperty("uploadedBy", p.uploadedBy() == null ? null : p.uploadedBy().toString());
                po.addProperty("uploaderName", p.uploaderName());
                po.addProperty("format", p.format());
                po.addProperty("width", p.width());
                po.addProperty("height", p.height());
                po.addProperty("bytes", p.bytes());
                po.addProperty("colors", p.colors());
                o.add("pending", po);
            }
            if (c.lastRejection != null) {
                final JsonObject ro = new JsonObject();
                ro.addProperty("imageId", c.lastRejection.imageId());
                ro.addProperty("at", c.lastRejection.at());
                ro.addProperty("by", c.lastRejection.by());
                ro.addProperty("reason", c.lastRejection.reason());
                o.add("lastRejection", ro);
            }
            final JsonArray history = new JsonArray();
            for (final FlagBook.Entry h : c.history) {
                final JsonObject ho = new JsonObject();
                ho.addProperty("imageId", h.imageId());
                ho.addProperty("status", h.status().name());
                ho.addProperty("at", h.at());
                ho.addProperty("by", h.by());
                ho.addProperty("note", h.note());
                history.add(ho);
            }
            o.add("history", history);
            countries.add(e.getKey().toString(), o);
        }
        root.add("countries", countries);
        return root;
    }

    static FlagBook decode(final JsonObject root) {
        final FlagBook book = new FlagBook();
        book.lastRebuildAt = getLong(root, "lastRebuildAt");
        book.nextRebuildAt = getLong(root, "nextRebuildAt");
        if (root.has("packed") && root.get("packed").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("packed").entrySet()) {
                final UUID id = uuid(e.getKey());
                if (id != null && e.getValue().isJsonPrimitive() && IMAGE_ID.matcher(e.getValue().getAsString()).matches()) {
                    book.packed.put(id, e.getValue().getAsString());
                }
            }
        }
        if (root.has("capitalSpots") && root.get("capitalSpots").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("capitalSpots").entrySet()) {
                final UUID id = uuid(e.getKey());
                if (id == null || !e.getValue().isJsonObject()) {
                    continue;
                }
                final JsonObject o = e.getValue().getAsJsonObject();
                book.capitalSpots.put(id, new FlagBook.Spot(getString(o, "world"), getDouble(o, "x"), getDouble(o, "y"),
                    getDouble(o, "z"), (float) getDouble(o, "yaw")));
            }
        }
        if (root.has("decisions") && root.get("decisions").isJsonArray()) {
            for (final JsonElement el : root.getAsJsonArray("decisions")) {
                final JsonObject o = el.getAsJsonObject();
                final UUID id = uuid(getString(o, "country"));
                final FlagBook.Status st = status(getString(o, "status"));
                if (id != null && st != null) {
                    book.decisions.add(new FlagBook.Decision(id, getString(o, "imageId"), st, getLong(o, "at"),
                        getString(o, "by"), nz(getString(o, "note"))));
                }
            }
        }
        if (root.has("countries") && root.get("countries").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("countries").entrySet()) {
                final UUID id = uuid(e.getKey());
                if (id == null || !e.getValue().isJsonObject()) {
                    continue;
                }
                final JsonObject o = e.getValue().getAsJsonObject();
                final FlagBook.Country c = book.getOrCreate(id);
                final String cur = getString(o, "current");
                c.current = cur != null && IMAGE_ID.matcher(cur).matches() ? cur : null;
                c.approvedAt = getLong(o, "approvedAt");
                c.approvedBy = getString(o, "approvedBy");
                c.lastUploadAt = getLong(o, "lastUploadAt");
                if (o.has("pending") && o.get("pending").isJsonObject()) {
                    final JsonObject p = o.getAsJsonObject("pending");
                    final String img = getString(p, "imageId");
                    if (img != null && IMAGE_ID.matcher(img).matches()) {
                        c.pending = new FlagBook.Pending(img, getLong(p, "uploadedAt"), uuid(getString(p, "uploadedBy")),
                            nz(getString(p, "uploaderName")), nz(getString(p, "format")), (int) getLong(p, "width"),
                            (int) getLong(p, "height"), (int) getLong(p, "bytes"), (int) getLong(p, "colors"));
                    }
                }
                if (o.has("lastRejection") && o.get("lastRejection").isJsonObject()) {
                    final JsonObject r = o.getAsJsonObject("lastRejection");
                    c.lastRejection = new FlagBook.Rejection(getString(r, "imageId"), getLong(r, "at"), nz(getString(r, "by")),
                        nz(getString(r, "reason")));
                }
                if (o.has("history") && o.get("history").isJsonArray()) {
                    for (final JsonElement el : o.getAsJsonArray("history")) {
                        final JsonObject h = el.getAsJsonObject();
                        final FlagBook.Status st = status(getString(h, "status"));
                        if (st != null) {
                            c.history.add(new FlagBook.Entry(getString(h, "imageId"), st, getLong(h, "at"), nz(getString(h, "by")),
                                nz(getString(h, "note"))));
                        }
                    }
                }
            }
        }
        return book;
    }

    private static FlagBook.Status status(final String s) {
        try {
            return s == null ? null : FlagBook.Status.valueOf(s);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID uuid(final String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    private static String getString(final JsonObject o, final String k) {
        final JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static long getLong(final JsonObject o, final String k) {
        final JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0L : e.getAsLong();
    }

    private static double getDouble(final JsonObject o, final String k) {
        final JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0.0 : e.getAsDouble();
    }
}
