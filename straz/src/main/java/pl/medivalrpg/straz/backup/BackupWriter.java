// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.backup;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes one consistent zip of plugin data folders (background thread only, never the main thread).
 *
 * <p>Consistency: none of the MEDIVALRPG APIs offers a "flush" call, but every plugin saves its data atomically
 * (temporary file + atomic move, previous version kept as {@code .bak}). Each file is therefore read whole and
 * accepted only when its size and modification time did not change during the read; {@code .json} files must also
 * parse, otherwise the valid {@code .bak} is stored in their place (and noted in the manifest). The zip is written to
 * {@code .tmp}, re-read completely (CRC of every entry) and only then moved to its final name.</p>
 */
public final class BackupWriter {

    /** Result of one backup. */
    public record Result(boolean ok, String file, long bytes, int files, long durationMs, List<String> warnings, String error) {
    }

    private static final long STREAM_LIMIT = 64L * 1024 * 1024;
    public static final String MANIFEST = "STRAZ-MANIFEST.json";

    private final Path pluginsDir;
    private final List<String> dirs;
    private final List<PathMatcher> excludes;
    private final double bytesPerMs;

    /**
     * @param excludeGlobs globs relative to {@code plugins/} (e.g. {@code **}{@code /*.tmp})
     * @param limitMbPerSecond read limit, 0 = none
     */
    public BackupWriter(final Path pluginsDir, final List<String> dirs, final List<String> excludeGlobs, final double limitMbPerSecond) {
        this.pluginsDir = pluginsDir;
        this.dirs = List.copyOf(dirs);
        final List<PathMatcher> m = new ArrayList<>();
        for (final String g : excludeGlobs) {
            m.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        this.excludes = List.copyOf(m);
        this.bytesPerMs = limitMbPerSecond <= 0 ? 0 : limitMbPerSecond * 1_000_000 / 1000.0;
    }

    boolean excluded(final String rel) {
        final Path p = Path.of(rel);
        for (final PathMatcher m : this.excludes) {
            if (m.matches(p)) {
                return true;
            }
        }
        return false;
    }

    /** Writes {@code target} (overwriting it atomically). */
    public Result write(final Path target, final String serverInfo) {
        final long started = System.currentTimeMillis();
        final List<String> warnings = new ArrayList<>();
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        final List<Map<String, Object>> manifestFiles = new ArrayList<>();
        int count = 0;
        try {
            Files.createDirectories(target.getParent());
            final MessageDigest digest = sha256();
            try (OutputStream raw = Files.newOutputStream(tmp);
                 ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(raw, 1 << 16), StandardCharsets.UTF_8)) {
                zip.setLevel(6);
                for (final String dir : this.dirs) {
                    final Path root = this.pluginsDir.resolve(dir);
                    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    final List<Path> files;
                    try (Stream<Path> walk = Files.walk(root)) {
                        files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
                    }
                    for (final Path file : files) {
                        final String rel = this.pluginsDir.relativize(file).toString().replace('\\', '/');
                        if (this.excluded(rel)) {
                            continue;
                        }
                        final long t0 = System.currentTimeMillis();
                        final Copy copy;
                        try {
                            copy = this.readConsistent(file, rel, warnings);
                        } catch (final NoSuchFileException gone) {
                            continue; // removed by its plugin while walking (e.g. a finished temporary file)
                        }
                        final ZipEntry entry = new ZipEntry("plugins/" + rel);
                        entry.setLastModifiedTime(FileTime.fromMillis(copy.mtime));
                        zip.putNextEntry(entry);
                        digest.reset();
                        if (copy.bytes != null) {
                            zip.write(copy.bytes);
                            digest.update(copy.bytes);
                        } else {
                            try (InputStream in = Files.newInputStream(file)) {
                                final byte[] buf = new byte[1 << 16];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    zip.write(buf, 0, n);
                                    digest.update(buf, 0, n);
                                }
                            }
                        }
                        zip.closeEntry();
                        count++;
                        final Map<String, Object> mf = new LinkedHashMap<>();
                        mf.put("path", "plugins/" + rel);
                        mf.put("bytes", copy.size);
                        mf.put("sha256", HexFormat.of().formatHex(digest.digest()));
                        mf.put("source", copy.source);
                        manifestFiles.add(mf);
                        this.throttle(copy.size, System.currentTimeMillis() - t0);
                    }
                }
                final Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("generator", "Straz — Maciej Kaczmarczyk — lektoro.pl");
                manifest.put("takenAt", Instant.ofEpochMilli(started).toString());
                manifest.put("server", serverInfo);
                manifest.put("dirs", this.dirs);
                manifest.put("files", manifestFiles);
                manifest.put("warnings", warnings);
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest)
                    .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            final int entries = verify(tmp);
            if (entries != count + 1) {
                throw new IOException("Weryfikacja: " + entries + " wpisów zamiast " + (count + 1));
            }
            moveAtomic(tmp, target);
            final long size = Files.size(target);
            return new Result(true, target.getFileName().toString(), size, count, System.currentTimeMillis() - started,
                List.copyOf(warnings), "");
        } catch (final IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // nothing more to do
            }
            return new Result(false, target.getFileName().toString(), 0, count, System.currentTimeMillis() - started,
                List.copyOf(warnings), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static final class Copy {
        byte[] bytes;
        long size;
        long mtime;
        String source = "live";
    }

    /** Reads a file whose size and mtime are stable during the read; invalid JSON falls back to a valid .bak. */
    Copy readConsistent(final Path file, final String rel, final List<String> warnings) throws IOException {
        Copy last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            final BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
            final Copy c = new Copy();
            c.size = before.size();
            c.mtime = before.lastModifiedTime().toMillis();
            if (c.size <= STREAM_LIMIT) {
                c.bytes = Files.readAllBytes(file);
                c.size = c.bytes.length;
            }
            final BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
            last = c;
            if (after.size() == before.size() && after.lastModifiedTime().equals(before.lastModifiedTime())
                && (c.bytes == null || c.bytes.length == before.size())) {
                break;
            }
            last.source = "changing";
            sleep(150);
        }
        if ("changing".equals(last.source)) {
            warnings.add(rel + ": plik zmieniał się podczas odczytu (zapisano ostatni odczyt)");
        }
        if (last.bytes != null && rel.toLowerCase(Locale.ROOT).endsWith(".json") && !validJson(last.bytes)) {
            final Path bak = file.resolveSibling(file.getFileName() + ".bak");
            if (Files.isRegularFile(bak)) {
                final byte[] b = Files.readAllBytes(bak);
                if (validJson(b)) {
                    last.bytes = b;
                    last.size = b.length;
                    last.mtime = Files.getLastModifiedTime(bak).toMillis();
                    last.source = "bak";
                    warnings.add(rel + ": niepoprawny JSON — zapisano poprawną kopię .bak");
                    return last;
                }
            }
            last.source = "invalid-json";
            warnings.add(rel + ": niepoprawny JSON i brak poprawnej .bak (zapisano jak jest)");
        }
        return last;
    }

    static boolean validJson(final byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        try {
            JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return true;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** Reads every entry completely (ZipFile checks each CRC); returns the number of entries. */
    public static int verify(final Path zipFile) throws IOException {
        int n = 0;
        try (ZipFile z = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
            final Enumeration<? extends ZipEntry> en = z.entries();
            final byte[] buf = new byte[1 << 16];
            while (en.hasMoreElements()) {
                final ZipEntry e = en.nextElement();
                try (InputStream in = z.getInputStream(e)) {
                    while (in.read(buf) > 0) {
                        // reading to the end verifies the CRC
                    }
                }
                n++;
            }
        }
        return n;
    }

    private void throttle(final long bytes, final long spentMs) {
        if (this.bytesPerMs <= 0) {
            return;
        }
        final long wanted = (long) (bytes / this.bytesPerMs);
        if (wanted > spentMs) {
            sleep(Math.min(5_000, wanted - spentMs));
        }
    }

    private static void moveAtomic(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
