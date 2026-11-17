// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Safe file replacement: write {@code <file>.tmp}, keep the previous version as {@code <file>.bak}, then move. */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(final Path target, final String content) throws IOException {
        final Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (Files.exists(target)) {
            Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
