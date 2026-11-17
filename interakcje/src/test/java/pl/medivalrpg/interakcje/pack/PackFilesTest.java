// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFilesTest {

    @Test
    void sha1IsLowerCaseHex() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PackFiles.sha1("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(40, PackFiles.sha1(new byte[0]).length());
    }

    @Test
    void urlJoinsAndBustsTheCache() {
        String sha = "d86c0af11afce5a78c596c9411c9fe36aecb7678";
        assertEquals("http://mc.example.org:8100/pack/medivalrpg.zip?v=d86c0af11afc",
                PackFiles.url("http://mc.example.org:8100", "pack/medivalrpg.zip", sha, true));
        assertEquals("http://host/pack/medivalrpg.zip", PackFiles.url("http://host/", "/pack//medivalrpg.zip", sha, false));
        assertEquals("http://host/my%20pack/p%C5%82.zip", PackFiles.url("http://host", "my pack\\pł.zip", null, true));
    }

    @Test
    void insideRejectsEscapes(@TempDir Path dir) {
        assertEquals(dir.toAbsolutePath().normalize().resolve("pack/x.zip"), PackFiles.inside(dir, "pack/x.zip"));
        assertNull(PackFiles.inside(dir, "../evil.zip"));
        assertNull(PackFiles.inside(dir, "pack/../../evil.zip"));
        assertNull(PackFiles.inside(dir, "."));
    }

    @Test
    void folderZipIsDeterministic(@TempDir Path dir) throws Exception {
        Path pack = dir.resolve("pack");
        Files.createDirectories(pack.resolve("assets/bettermodel/models"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(pack.resolve("assets/bettermodel/models/a.json"), "{}");
        byte[] first = PackFiles.zipFolder(pack);
        Files.setLastModifiedTime(pack.resolve("pack.mcmeta"), FileTime.fromMillis(1_000_000_000_000L));
        byte[] second = PackFiles.zipFolder(pack);
        assertArrayEquals(first, second, "timestamps do not change the hash");
        String stamp = PackFiles.stamp(pack);
        Files.writeString(pack.resolve("assets/bettermodel/models/a.json"), "{\"x\":1}");
        assertNotEquals(PackFiles.sha1(first), PackFiles.sha1(PackFiles.zipFolder(pack)));
        assertNotEquals(stamp, PackFiles.stamp(pack));
    }

    @Test
    void atomicWriteReplacesTheFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("web/pack/medivalrpg.zip");
        PackFiles.writeAtomically(target, new byte[]{1, 2, 3});
        PackFiles.writeAtomically(target, new byte[]{4, 5});
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("web/pack/medivalrpg.zip.tmp")));
        assertTrue(PackFiles.stamp(target).endsWith(":2"));
    }
}
