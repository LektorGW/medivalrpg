// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkReplyTest {
    @Test void refusedRetryIsShownToTheManagerInsteadOfAGenericServerError() {
        assertEquals("Brak wstrzymanej wyceny do ponowienia.", KolejPlugin.networkReply(() -> {throw new IllegalStateException("Brak wstrzymanej wyceny do ponowienia.");}));
        assertEquals("Ponowienie dostępne tylko po błędzie wyceny przed płatnością, najwyżej 3 razy na część.",
            KolejPlugin.networkReply(() -> {NetworkCommission.checkPreparationRetry(true, 3, false); return "nie";}));
        assertEquals("Ponowienie jest teraz niedostępne.", KolejPlugin.networkReply(() -> {throw new IllegalStateException();}));
        assertEquals("Ponowienie wyceny p1 (1/3)", KolejPlugin.networkReply(() -> "Ponowienie wyceny p1 (1/3)"));
    }
    @Test void otherFailuresStillReachTheGenericHandlerAndTheActionRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("ok", KolejPlugin.networkReply(() -> {calls.incrementAndGet(); return "ok";}));
        assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> KolejPlugin.networkReply(() -> {throw new IllegalArgumentException("x");}));
        assertThrows(NullPointerException.class, () -> KolejPlugin.networkReply(() -> {throw new NullPointerException();}));
    }
}
