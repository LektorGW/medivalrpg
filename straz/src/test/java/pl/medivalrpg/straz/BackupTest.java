// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.straz.backup.BackupPlan;
import pl.medivalrpg.straz.backup.BackupWriter;

class BackupTest {

    static BackupPlan.Entry e(final String name, final long bytes) {
        return new BackupPlan.Entry(name, BackupPlan.parse(name).orElseThrow(), bytes);
    }

    @Test
    void namesParseAndIgnoreForeignFiles() {
        assertEquals("plugins-20261030.zip", BackupPlan.nightlyName(LocalDate.of(2026, 10, 30)));
        assertEquals("plugins-20261030-1412.zip", BackupPlan.manualName(LocalDateTime.of(2026, 10, 30, 14, 12)));
        assertEquals(LocalDateTime.of(2026, 10, 30, 0, 0), BackupPlan.parse("plugins-20261030.zip").orElseThrow());
        assertEquals(LocalDateTime.of(2026, 10, 30, 14, 12), BackupPlan.parse("plugins-20261030-1412.zip").orElseThrow());
        assertTrue(BackupPlan.parse("world-20261030.zip").isEmpty());
        assertTrue(BackupPlan.parse("plugins-20261399.zip").isEmpty());
        assertTrue(BackupPlan.parse("plugins-20261030.zip.tmp").isEmpty());
    }

    @Test
    void rotationKeepsNewestDays() {
        final List<BackupPlan.Entry> list = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            list.add(e(BackupPlan.nightlyName(LocalDate.of(2026, 10, 10).plusDays(d)), 8_000_000));
        }
        list.add(e("plugins-20261030-1412.zip", 8_000_000));   // manual on a kept day
        list.add(e("plugins-20261012-0900.zip", 8_000_000));   // manual on an old day
        final List<String> del = BackupPlan.toDelete(list, 14);
        assertEquals(7, del.size(), "days 11..16 nightly + old manual");
        assertTrue(del.contains("plugins-20261011.zip"));
        assertTrue(del.contains("plugins-20261016.zip"));
        assertFalse(del.contains("plugins-20261017.zip"));
        assertTrue(del.contains("plugins-20261012-0900.zip"));
        assertFalse(del.contains("plugins-20261030-1412.zip"));
        assertFalse(del.contains("plugins-20261030.zip"));
        assertTrue(BackupPlan.toDelete(List.of(e("plugins-20261030.zip", 1)), 0).isEmpty(), "never the newest");
    }

    @Test
    void healthChecksAgeAndSize() {
        final LocalTime at = LocalTime.of(4, 30);
        final LocalDateTime morning = LocalDateTime.of(2026, 10, 30, 9, 0);
        assertFalse(BackupPlan.health(List.of(), morning, at, 26, 1_000_000).ok());
        final var fresh = BackupPlan.health(List.of(e("plugins-20261030.zip", 8_400_000), e("plugins-20261029.zip", 8_300_000)),
            morning, at, 26, 1_000_000);
        assertTrue(fresh.ok(), fresh.summary());
        assertEquals("plugins-20261030.zip", fresh.latest().name());
        final var old = BackupPlan.health(List.of(e("plugins-20261028.zip", 8_400_000)), morning, at, 26, 1_000_000);
        assertFalse(old.ok());
        assertTrue(old.summary().contains("starsza"));
        final var small = BackupPlan.health(List.of(e("plugins-20261030.zip", 500)), morning, at, 26, 1_000_000);
        assertTrue(small.summary().contains("mała"));
        final var half = BackupPlan.health(List.of(e("plugins-20261030.zip", 3_000_000), e("plugins-20261029.zip", 8_000_000)),
            morning, at, 26, 1_000_000);
        assertTrue(half.summary().contains("połowa"));
    }

    @Test
    void writerMakesConsistentVerifiedZip(@TempDir final Path dir) throws Exception {
        final Path plugins = dir.resolve("plugins");
        Files.createDirectories(plugins.resolve("Skarbiec/bank"));
        Files.createDirectories(plugins.resolve("Kolej/works"));
        Files.createDirectories(plugins.resolve("Straz/raporty"));
        Files.writeString(plugins.resolve("Skarbiec/bank/accounts.json"), "{\"seq\":1,\"accounts\":{\"a\":100}}");
        Files.writeString(plugins.resolve("Skarbiec/bank/accounts.json.bak"), "{\"seq\":0,\"accounts\":{\"a\":100}}");
        Files.writeString(plugins.resolve("Skarbiec/config.yml"), "a: 1\n");
        // half-written JSON (simulates a plugin without atomic save) → the valid .bak goes into the zip
        Files.writeString(plugins.resolve("Kolej/works/jobs.json"), "{\"version\":1,\"jobs\":[");
        Files.writeString(plugins.resolve("Kolej/works/jobs.json.bak"), "{\"version\":1,\"jobs\":[]}");
        Files.writeString(plugins.resolve("Kolej/works/jobs.json.tmp"), "temporary");
        Files.writeString(plugins.resolve("Straz/stan.json"), "{}");
        Files.writeString(plugins.resolve("Straz/raporty/2026-10-30.md"), "# raport");
        final BackupWriter w = new BackupWriter(plugins, List.of("Skarbiec", "Kolej", "Straz", "Brak"),
            List.of("**/*.tmp", "Straz/raporty/**"), 0);
        final Path target = dir.resolve("backups/plugins-20261030.zip");
        final BackupWriter.Result r = w.write(target, "test");
        assertTrue(r.ok(), r.error());
        assertEquals(6, r.files());
        assertFalse(Files.exists(dir.resolve("backups/plugins-20261030.zip.tmp")));
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("jobs.json"));
        final Map<String, String> content = new HashMap<>();
        try (ZipFile z = new ZipFile(target.toFile(), StandardCharsets.UTF_8)) {
            z.stream().forEach(en -> {
                try {
                    content.put(en.getName(), new String(z.getInputStream(en).readAllBytes(), StandardCharsets.UTF_8));
                } catch (final java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        assertEquals("{\"version\":1,\"jobs\":[]}", content.get("plugins/Kolej/works/jobs.json"), "valid .bak stored");
        assertFalse(content.containsKey("plugins/Kolej/works/jobs.json.tmp"));
        assertFalse(content.containsKey("plugins/Straz/raporty/2026-10-30.md"));
        assertTrue(content.containsKey("plugins/Straz/stan.json"));
        final JsonObject manifest = JsonParser.parseString(content.get(BackupWriter.MANIFEST)).getAsJsonObject();
        assertEquals(6, manifest.getAsJsonArray("files").size());
        assertEquals(7, BackupWriter.verify(target));
        // a second run replaces the file atomically
        assertTrue(w.write(target, "test").ok());
    }

    @Test
    void writerReportsFailure(@TempDir final Path dir) throws Exception {
        final Path plugins = dir.resolve("plugins");
        Files.createDirectories(plugins.resolve("Skarbiec"));
        Files.writeString(plugins.resolve("Skarbiec/a.yml"), "x");
        // target directory is a file → cannot create
        Files.writeString(dir.resolve("blocked"), "file");
        final BackupWriter.Result r = new BackupWriter(plugins, List.of("Skarbiec"), List.of(), 0)
            .write(dir.resolve("blocked/plugins-20261030.zip"), "test");
        assertFalse(r.ok());
        assertFalse(r.error().isBlank());
    }
}
