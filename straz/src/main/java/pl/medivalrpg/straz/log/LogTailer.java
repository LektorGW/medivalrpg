// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Follows {@code logs/latest.log} from a remembered offset (I/O thread only). A new file (restart, rotation: other
 * inode/creation time or a size below the offset) is read from the beginning. At most {@code maxBytesPerPoll} per call, so a
 * flood of log lines never blocks the I/O thread for long.
 */
public final class LogTailer {

    /** Persisted position. */
    public static final class Position {
        /** Identity of the file: inode on Linux ({@code fileKey}), creation time where there is no key (Windows). */
        public String id = "";
        public long offset;
    }

    private final Path file;
    private final LogParser parser;
    private final Position position;
    private static final int MAX_BYTES_PER_POLL = 4 * 1024 * 1024;

    public LogTailer(final Path file, final ZoneId zone, final Position position) {
        this.file = file;
        this.parser = new LogParser(zone);
        this.position = position;
    }

    public Position position() {
        return this.position;
    }

    /** Reads what was appended since the last call and returns the finished error events. */
    public List<LogParser.Event> poll(final long now) throws IOException {
        if (!Files.isRegularFile(this.file)) {
            return List.of();
        }
        final BasicFileAttributes attrs = Files.readAttributes(this.file, BasicFileAttributes.class);
        final Object key = attrs.fileKey();
        final String id = key != null ? key.toString() : "c" + attrs.creationTime().toMillis();
        final long size = attrs.size();
        if (!id.equals(this.position.id) || size < this.position.offset) {
            this.position.id = id;
            this.position.offset = 0;
        }
        if (size == this.position.offset) {
            return this.parser.flush();
        }
        final long toRead = Math.min(size - this.position.offset, MAX_BYTES_PER_POLL);
        final byte[] buf = new byte[(int) toRead];
        try (RandomAccessFile raf = new RandomAccessFile(this.file.toFile(), "r")) {
            raf.seek(this.position.offset);
            raf.readFully(buf);
        }
        // only complete lines; the unfinished tail is read again next time
        int end = buf.length;
        while (end > 0 && buf[end - 1] != '\n') {
            end--;
        }
        if (end == 0) {
            if (buf.length == MAX_BYTES_PER_POLL) {
                end = buf.length; // a single giant line: consume it anyway
            } else {
                return List.of();
            }
        }
        this.position.offset += end;
        final String text = new String(buf, 0, end, StandardCharsets.UTF_8);
        final List<String> lines = new ArrayList<>(List.of(text.split("\r?\n")));
        final List<LogParser.Event> out = new ArrayList<>(this.parser.feed(lines, now));
        if (this.position.offset >= size) {
            out.addAll(this.parser.flush());
        }
        return out;
    }
}
