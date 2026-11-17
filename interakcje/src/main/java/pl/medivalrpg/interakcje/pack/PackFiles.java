// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** File helpers of the resource pack module (no server access, unit-tested). */
public final class PackFiles {
    private static final FileTime EPOCH = FileTime.fromMillis(315532800000L); // 1980-01-01, zip minimum

    private PackFiles() {
    }

    /** Lower-case hex SHA-1 (what the client compares against). */
    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }

    /**
     * Zips a pack folder (BetterModel with {@code pack-type: folder}) deterministically: sorted
     * entries, fixed timestamps - the same content always gives the same SHA-1.
     */
    public static byte[] zipFolder(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort((a, b) -> folder.relativize(a).toString().replace('\\', '/')
                .compareTo(folder.relativize(b).toString().replace('\\', '/')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path f : files) {
                ZipEntry e = new ZipEntry(folder.relativize(f).toString().replace('\\', '/'));
                e.setLastModifiedTime(EPOCH);
                zip.putNextEntry(e);
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** A cheap change marker of a file or folder: newest modification time and total size. */
    public static String stamp(Path source) throws IOException {
        if (Files.isDirectory(source)) {
            long newest = 0;
            long size = 0;
            int count = 0;
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
                    size += Files.size(p);
                    count++;
                }
            }
            return newest + ":" + size + ":" + count;
        }
        return Files.getLastModifiedTime(source).toMillis() + ":" + Files.size(source);
    }

    /** Writes {@code data} to {@code target} through a temporary file and an atomic move. */
    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Download URL: {@code base + "/" + path}, each path segment URL-encoded, plus {@code ?v=<sha1 prefix>}
     * so that every new version has a new URL (no stale HTTP caches in between).
     */
    public static String url(String base, String path, String sha1, boolean cacheBust) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        StringBuilder sb = new StringBuilder(b);
        for (String segment : path.replace('\\', '/').split("/")) {
            if (!segment.isEmpty()) {
                sb.append('/').append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        if (cacheBust && sha1 != null) {
            sb.append("?v=").append(sha1, 0, Math.min(12, sha1.length()));
        }
        return sb.toString();
    }

    /** Resolves {@code relative} inside {@code root}; {@code null} when it would escape it ("../"). */
    public static Path inside(Path root, String relative) {
        Path r = root.toAbsolutePath().normalize();
        Path p = r.resolve(relative).normalize();
        return p.startsWith(r) && !p.equals(r) ? p : null;
    }
}
