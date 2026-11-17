// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.ChangelogCodec;

/**
 * {@code changelog.yml} on disk. Loading is synchronous (startup, reload); saving goes to the I/O thread.
 * Remembers the size and modification time of the version it last read or wrote, so a file replaced from
 * outside (SFTP, hosting panel) can be noticed without {@code /nowosci reload}.
 */
public final class ChangelogStore {

    /** Size and modification time of the file; {@link #MISSING} when it cannot be read. */
    public record Stamp(long size, long modified) {

        static final Stamp MISSING = new Stamp(-1, -1);
    }

    private final Path file;
    private final IoExecutor io;
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private volatile Stamp known = Stamp.MISSING;

    public ChangelogStore(final Path file, final IoExecutor io) {
        this.file = file;
        this.io = io;
    }

    public ChangelogCodec.Result load() throws IOException {
        final Stamp before = this.stamp();
        final ChangelogCodec.Result result = ChangelogCodec.parse(Files.readString(this.file, StandardCharsets.UTF_8));
        this.known = before;
        return result;
    }

    /** Serializes on the calling (main) thread, writes in the background. */
    public void save(final Changelog changelog, final Runnable onFailure) {
        final String yaml = ChangelogCodec.write(changelog);
        this.pendingWrites.incrementAndGet();
        this.io.submit(this.file.getFileName().toString(), () -> {
            try {
                AtomicFiles.write(this.file, yaml);
                this.known = this.stamp();
            } finally {
                this.pendingWrites.decrementAndGet();
            }
        }, onFailure);
    }

    /**
     * The file was changed by someone else since it was last read or written by the plugin. Always
     * {@code false} while the plugin's own write is pending, so it never re-reads an older version.
     */
    public boolean changedOutside() {
        if (this.pendingWrites.get() > 0) {
            return false;
        }
        final Stamp now = this.stamp();
        return !now.equals(Stamp.MISSING) && !now.equals(this.known);
    }

    private Stamp stamp() {
        try {
            return new Stamp(Files.size(this.file), Files.getLastModifiedTime(this.file).toMillis());
        } catch (final IOException e) {
            return Stamp.MISSING;
        }
    }
}
