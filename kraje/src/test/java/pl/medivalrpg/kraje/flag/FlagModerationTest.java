// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.medivalrpg.kraje.model.Rank;

/** Moderation state machine, permissions, pack batching and persistence. */
class FlagModerationTest {

    private static final UUID KRAJ = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID INNY = UUID.fromString("99999999-2222-3333-4444-555555555555");
    private static final UUID GRACZ = UUID.randomUUID();
    private static final long MIN = 60_000L;

    private static FlagBook.Pending pending(final String id, final long at) {
        return new FlagBook.Pending(id, at, GRACZ, "Doradca1", "PNG", 320, 200, 5000, 12);
    }

    @Test
    void uploadNeverReachesTheGameWithoutApproval() {
        final FlagBook book = new FlagBook();
        book.submit(KRAJ, pending("aaaaaaaaaaaaaaaa", 1000), MIN, 20);
        assertNull(book.current(KRAJ), "pending image must not become the flag");
        assertFalse(book.live(KRAJ));
        assertTrue(book.desiredPack().isEmpty(), "pending image must not go into the pack");
        assertTrue(book.waitingForPack().isEmpty());
        book.approve(KRAJ, "aaaaaaaaaaaaaaaa", "WorldSteward", 2000, 20);
        assertEquals("aaaaaaaaaaaaaaaa", book.current(KRAJ));
        assertEquals(Map.of(KRAJ, "aaaaaaaaaaaaaaaa"), book.desiredPack());
        assertEquals(Set.of(KRAJ), book.waitingForPack());
        assertFalse(book.live(KRAJ), "approved but not yet in the pack");
        book.packBuilt(book.desiredPack(), 3000);
        assertTrue(book.live(KRAJ));
        assertTrue(book.waitingForPack().isEmpty());
        assertEquals(FlagBook.Status.APPROVED, book.get(KRAJ).history().get(0).status());
        assertEquals(FlagBook.Status.APPROVED, book.decisions().get(0).status());
    }

    @Test
    void rejectionNeedsAReasonAndKeepsTheOldFlag() {
        final FlagBook book = new FlagBook();
        book.submit(KRAJ, pending("aaaaaaaaaaaaaaaa", 0), MIN, 20);
        book.approve(KRAJ, null, "WorldSteward", 10, 20);
        book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 2 * MIN), MIN, 20);
        assertThrows(IllegalArgumentException.class, () -> book.reject(KRAJ, "bbbbbbbbbbbbbbbb", "WorldSteward", " x ", 3 * MIN, 20));
        book.reject(KRAJ, "bbbbbbbbbbbbbbbb", "WorldSteward", "Nieodpowiednie treści", 3 * MIN, 20);
        assertEquals("aaaaaaaaaaaaaaaa", book.current(KRAJ));
        assertNull(book.get(KRAJ).pending());
        assertEquals("Nieodpowiednie treści", book.get(KRAJ).lastRejection().reason());
        assertFalse(book.referencedImages().contains("bbbbbbbbbbbbbbbb"), "rejected image is not kept");
        assertThrows(IllegalArgumentException.class, () -> book.approve(KRAJ, "bbbbbbbbbbbbbbbb", "WorldSteward", 4 * MIN, 20));
        // a rejected image can never be restored around the moderation
        assertThrows(IllegalArgumentException.class, () -> book.restore(KRAJ, "bbbbbbbbbbbbbbbb", "Doradca1", 5 * MIN, 20, id -> true));
    }

    @Test
    void staleDecisionsCooldownAndDuplicatesAreRefused() {
        final FlagBook book = new FlagBook();
        book.submit(KRAJ, pending("aaaaaaaaaaaaaaaa", 1_000), MIN, 20);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 10_000), MIN, 20))
            .getMessage().contains("Odczekaj"));
        final FlagBook.Pending replaced = book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 2 * MIN), MIN, 20);
        assertEquals("aaaaaaaaaaaaaaaa", replaced.imageId());
        // the moderator looked at the older image: approving it must fail
        assertTrue(assertThrows(IllegalArgumentException.class, () -> book.approve(KRAJ, "aaaaaaaaaaaaaaaa", "WorldSteward", 3 * MIN, 20))
            .getMessage().contains("inną flagę"));
        assertThrows(IllegalArgumentException.class, () -> book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 5 * MIN), MIN, 20));
        book.approve(KRAJ, "bbbbbbbbbbbbbbbb", "WorldSteward", 6 * MIN, 20);
        assertThrows(IllegalArgumentException.class, () -> book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 10 * MIN), MIN, 20));
        assertThrows(IllegalArgumentException.class, () -> book.approve(INNY, null, "WorldSteward", 6 * MIN, 20));
    }

    @Test
    void withdrawResetAndRestore() {
        final FlagBook book = new FlagBook();
        book.submit(KRAJ, pending("aaaaaaaaaaaaaaaa", 0), MIN, 20);
        book.approve(KRAJ, null, "WorldSteward", 1, 20);
        book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 2 * MIN), MIN, 20);
        book.withdraw(KRAJ, "Doradca1", 3 * MIN, 20);
        assertNull(book.get(KRAJ).pending());
        assertThrows(IllegalArgumentException.class, () -> book.withdraw(KRAJ, "Doradca1", 3 * MIN, 20));
        book.reset(KRAJ, "Wladca", 4 * MIN, 20);
        assertNull(book.current(KRAJ));
        assertThrows(IllegalArgumentException.class, () -> book.reset(KRAJ, "Wladca", 4 * MIN, 20));
        assertTrue(book.restorable(KRAJ, "aaaaaaaaaaaaaaaa", id -> true));
        assertFalse(book.restorable(KRAJ, "aaaaaaaaaaaaaaaa", id -> false), "file gone");
        assertThrows(IllegalArgumentException.class, () -> book.restore(KRAJ, "bbbbbbbbbbbbbbbb", "Wladca", 5 * MIN, 20, id -> true));
        book.restore(KRAJ, "aaaaaaaaaaaaaaaa", "Wladca", 5 * MIN, 20, id -> true);
        assertEquals("aaaaaaaaaaaaaaaa", book.current(KRAJ));
        assertEquals(FlagBook.Status.RESTORED, book.get(KRAJ).history().get(0).status());
    }

    @Test
    void lostImageFallsBackToDefault() {
        final FlagBook book = new FlagBook();
        book.submit(KRAJ, pending("aaaaaaaaaaaaaaaa", 1_000), MIN, 20);
        book.approve(KRAJ, null, "WorldSteward", 2_000, 20);
        book.dropLost(KRAJ, 3_000, 20);
        assertNull(book.current(KRAJ));
        assertTrue(book.waitingForPack().isEmpty(), "no endless rebuild for a missing file");
    }

    @Test
    void historyIsCapped() {
        final FlagBook book = new FlagBook();
        for (int i = 0; i < 30; i++) {
            book.submit(KRAJ, pending(String.format("%016x", i + 1), i * 2 * MIN), MIN, 5);
        }
        assertEquals(5, book.get(KRAJ).history().size());
    }

    @Test
    void permissions() {
        final Set<UUID> mods = Set.of(FlagSettings.OWNER);
        // other country's member: no rank in this country
        assertFalse(FlagAccess.manage(null, false, false, Rank.ADVISOR));
        assertFalse(FlagAccess.manage(Rank.CITIZEN, false, false, Rank.ADVISOR));
        assertTrue(FlagAccess.manage(Rank.ADVISOR, false, false, Rank.ADVISOR));
        assertFalse(FlagAccess.manage(Rank.ADVISOR, false, false, Rank.RULER));
        assertTrue(FlagAccess.manage(Rank.RULER, false, false, Rank.RULER));
        assertFalse(FlagAccess.manage(Rank.RULER, true, false, Rank.ADVISOR), "NPC countries: administration only");
        assertTrue(FlagAccess.manage(null, true, true, Rank.ADVISOR));
        assertTrue(FlagAccess.moderator(FlagSettings.OWNER, false, false, mods));
        assertTrue(FlagAccess.moderator(GRACZ, true, false, mods));
        assertTrue(FlagAccess.moderator(GRACZ, false, true, mods));
        assertFalse(FlagAccess.moderator(GRACZ, false, false, mods));
        assertFalse(FlagAccess.moderator(null, false, false, mods));
    }

    @Test
    void approvalsAreBatchedIntoRarePackRebuilds() {
        final FlagSettings s = FlagSettings.DEFAULT; // 15 min, debounce 60 s, forced 60 s
        final long last = 1_000_000L;
        // right after a rebuild: wait the full interval
        assertEquals(last + 15 * MIN, PackSchedule.afterChange(last + 1000, last, 0, s));
        // long after: only the debounce
        final long now = last + 60 * MIN;
        final long first = PackSchedule.afterChange(now, last, 0, s);
        assertEquals(now + 60_000L, first);
        // a second approval keeps the earlier schedule (one rebuild for both)
        assertEquals(first, PackSchedule.afterChange(now + 30_000L, last, first, s));
        assertFalse(PackSchedule.due(now + 59_000L, first));
        assertTrue(PackSchedule.due(now + 60_000L, first));
        assertFalse(PackSchedule.due(now, 0));
        assertEquals(last + 60_000L, PackSchedule.forced(last + 5_000L, last, s));
        assertEquals(now, PackSchedule.forced(now, last, s));
        assertEquals(now + 15 * MIN, PackSchedule.failed(now, s));
    }

    @Test
    void storeRoundTripAndGarbageCollection(@TempDir final Path dir) throws Exception {
        final FlagBook book = new FlagBook();
        book.submit(KRAJ, pending("aaaaaaaaaaaaaaaa", 0), MIN, 20);
        book.approve(KRAJ, null, "WorldSteward", 5, 20);
        book.submit(KRAJ, pending("bbbbbbbbbbbbbbbb", 2 * MIN), MIN, 20);
        book.capitalSpot(KRAJ, new FlagBook.Spot("world", 1.5, 70, -3.5, 90f));
        book.packBuilt(Map.of(KRAJ, "aaaaaaaaaaaaaaaa"), 777);
        book.nextRebuildAt = 999;
        final FlagBook back = FlagStore.decode(FlagStore.encode(book));
        assertEquals("aaaaaaaaaaaaaaaa", back.current(KRAJ));
        assertEquals("bbbbbbbbbbbbbbbb", back.get(KRAJ).pending().imageId());
        assertEquals(book.get(KRAJ).history(), back.get(KRAJ).history());
        assertEquals(book.capitalSpot(KRAJ), back.capitalSpot(KRAJ));
        assertTrue(back.live(KRAJ));
        assertEquals(777, back.lastRebuildAt());
        assertEquals(999, back.nextRebuildAt());

        final FlagStore store = new FlagStore(dir, Logger.getLogger("test"));
        store.writeImage("aaaaaaaaaaaaaaaa", new byte[] {1, 2, 3});
        store.writeImage("cccccccccccccccc", new byte[] {4});
        store.writeImage("dddddddddddddddd", new byte[] {5});
        // c: old and unreferenced → collected; d: unreferenced but fresh (upload racing the snapshot) → kept
        Files.setLastModifiedTime(dir.resolve("images/cccccccccccccccc.png"),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - FlagStore.GC_MIN_AGE_MILLIS - 60_000L));
        assertTrue(store.hasImage("aaaaaaaaaaaaaaaa"));
        assertThrows(IllegalArgumentException.class, () -> store.imagePath("../../evil"));
        store.collectGarbage(Set.of("aaaaaaaaaaaaaaaa"));
        store.saveAsync(book);
        store.shutdown(null);
        assertTrue(Files.exists(dir.resolve("images/aaaaaaaaaaaaaaaa.png")));
        assertFalse(Files.exists(dir.resolve("images/cccccccccccccccc.png")));
        assertTrue(Files.exists(dir.resolve("images/dddddddddddddddd.png")));
        assertEquals("aaaaaaaaaaaaaaaa", new FlagStore(dir, Logger.getLogger("test")).load().current(KRAJ));
    }

    @Test
    void base64Upload() {
        assertEquals(3, FlagService.decodeBase64("data:image/png;base64,AQID", 1024).length);
        assertEquals(3, FlagService.decodeBase64("AQID", 1024).length);
        assertThrows(IllegalArgumentException.class, () -> FlagService.decodeBase64("data:image/gif;base64,AQID", 1024));
        assertThrows(IllegalArgumentException.class, () -> FlagService.decodeBase64("data:image/svg+xml;base64,AQID", 1024));
        assertThrows(IllegalArgumentException.class, () -> FlagService.decodeBase64("<script>", 1024));
        assertThrows(IllegalArgumentException.class, () -> FlagService.decodeBase64("", 1024));
        assertThrows(IllegalArgumentException.class, () -> FlagService.decodeBase64("A".repeat(5000), 1024));
    }
}
