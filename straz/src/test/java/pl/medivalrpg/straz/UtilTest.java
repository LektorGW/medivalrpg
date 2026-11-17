// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.straz.discord.WebhookUrl;
import pl.medivalrpg.straz.util.DailyClock;
import pl.medivalrpg.straz.util.Fmt;

class UtilTest {

    static final ZoneId WAW = ZoneId.of("Europe/Warsaw");

    @Test
    void polishNumbers() {
        assertEquals("100 000 000", Fmt.groups(100_000_000));
        assertEquals("999", Fmt.groups(999));
        assertEquals("1 000", Fmt.groups(1000));
        assertEquals("−12 300", Fmt.groups(-12_300));
        assertEquals("0", Fmt.groups(0));
        assertEquals("512 587 md (5 125,87 zł)", Fmt.mdZl(512_587));
        assertEquals("100 000 000 md (1 000 000 zł)", Fmt.mdZl(100_000_000));
        assertEquals("+5", Fmt.signed(5));
        assertEquals("−5", Fmt.signed(-5));
        assertEquals("19,8", Fmt.dec1(19.84));
        assertEquals("23 %", Fmt.percent(233, 1000));
        assertEquals("8,4 MB", Fmt.bytes(8_400_000));
    }

    @Test
    void polishPlurals() {
        assertEquals("1 błąd", Fmt.count(1, "błąd", "błędy", "błędów"));
        assertEquals("3 błędy", Fmt.count(3, "błąd", "błędy", "błędów"));
        assertEquals("5 błędów", Fmt.count(5, "błąd", "błędy", "błędów"));
        assertEquals("12 błędów", Fmt.count(12, "błąd", "błędy", "błędów"));
        assertEquals("22 błędy", Fmt.count(22, "błąd", "błędy", "błędów"));
        assertEquals("0 błędów", Fmt.count(0, "błąd", "błędy", "błędów"));
    }

    @Test
    void dailyClockDueOncePerDay() {
        final LocalTime at = LocalTime.of(9, 0);
        final ZonedDateTime before = ZonedDateTime.of(2026, 10, 30, 8, 59, 0, 0, WAW);
        final ZonedDateTime after = ZonedDateTime.of(2026, 10, 30, 9, 0, 30, 0, WAW);
        final ZonedDateTime late = ZonedDateTime.of(2026, 10, 30, 15, 0, 0, 0, WAW);
        assertFalse(DailyClock.due(before, at, null, true));
        assertTrue(DailyClock.due(after, at, LocalDate.of(2026, 10, 29), true));
        assertFalse(DailyClock.due(after, at, LocalDate.of(2026, 10, 30), true), "already done today");
        assertTrue(DailyClock.due(late, at, LocalDate.of(2026, 10, 29), true), "catch-up after downtime");
        assertFalse(DailyClock.due(late, at, LocalDate.of(2026, 10, 29), false), "no catch-up");
        assertEquals(ZonedDateTime.of(2026, 10, 31, 9, 0, 0, 0, WAW), DailyClock.next(after, at, LocalDate.of(2026, 10, 30)));
        assertEquals(ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, WAW), DailyClock.next(before, at, LocalDate.of(2026, 10, 29)));
        assertEquals(LocalTime.of(4, 30), DailyClock.parse("zła", LocalTime.of(4, 30)));
    }

    @Test
    void dailyClockAcrossDstChange() {
        // 2027-03-28 02:30 does not exist in Warsaw (02:00 → 03:00): the slot moves to 03:30, still once
        final LocalTime at = LocalTime.of(2, 30);
        final ZonedDateTime t = ZonedDateTime.of(2027, 3, 28, 3, 31, 0, 0, WAW);
        assertTrue(DailyClock.due(t, at, LocalDate.of(2027, 3, 27), false));
        assertFalse(DailyClock.due(t, at, LocalDate.of(2027, 3, 28), false));
    }

    @Test
    void webhookValidationAndMask() {
        final String token = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEFGHIJ";
        final String ok = "https://discord.com/api/webhooks/123456789012345678/" + token;
        assertTrue(WebhookUrl.valid(ok, false));
        assertTrue(WebhookUrl.valid("https://discord.com/api/v10/webhooks/123456789012345678/" + token, false));
        assertFalse(WebhookUrl.valid(ok + "/", false));
        assertFalse(WebhookUrl.valid(ok + "?wait=true", false));
        assertFalse(WebhookUrl.valid("https://evil.example/api/webhooks/123456789012345678/" + token, false));
        assertFalse(WebhookUrl.valid("http://discord.com/api/webhooks/123456789012345678/" + token, false));
        assertFalse(WebhookUrl.valid("", false));
        final String local = "http://127.0.0.1:18765/api/webhooks/123456789012345678/" + token;
        assertFalse(WebhookUrl.valid(local, false));
        assertTrue(WebhookUrl.valid(local, true));
        final String masked = WebhookUrl.mask(ok);
        assertFalse(masked.contains(token), "token never in logs");
        assertTrue(masked.contains("5678"));
        assertEquals("(brak)", WebhookUrl.mask(""));
    }
}
