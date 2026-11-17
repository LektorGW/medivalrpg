// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.skarbiec.api.TxResult;

/** The atomic batch of Skarbiec is never repeated leg by leg once Skarbiec has run it (no double transfers). */
class SkarbiecBankTest {

    /** Stand-in for SkarbiecPlugin#transferBatch. */
    public static final class FakeSkarbiec {
        int calls;

        public String ok(final List<Map<String, Object>> legs) {
            this.calls++;
            return "OK";
        }

        public String poor(final List<Map<String, Object>> legs) {
            this.calls++;
            return "INSUFFICIENT_FUNDS";
        }

        public String boom(final List<Map<String, Object>> legs) {
            this.calls++;
            throw new IllegalStateException("Przelew zbiorczy przerwany mimo sprawdzenia");
        }

        public String weird(final List<Map<String, Object>> legs) {
            this.calls++;
            return "DONE";
        }
    }

    private static final Logger LOG = Logger.getLogger("test");

    private static TxResult call(final FakeSkarbiec s, final String name, final Object target) throws ReflectiveOperationException {
        final Method m = FakeSkarbiec.class.getMethod(name, List.class);
        return SkarbiecBank.invokeBatch(m, target, new ArrayList<>(), LOG);
    }

    @Test
    void resultsOfTheBatchArePassedOn() throws ReflectiveOperationException {
        final FakeSkarbiec s = new FakeSkarbiec();
        assertEquals(TxResult.OK, call(s, "ok", s));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, call(s, "poor", s));
    }

    @Test
    void aBatchThatThrewOrAnsweredNonsenseIsRefusedNotRepeatedLegByLeg() throws ReflectiveOperationException {
        final FakeSkarbiec s = new FakeSkarbiec();
        assertEquals(TxResult.INVALID_ACCOUNT, call(s, "boom", s), "legs moved before the exception must not move again");
        assertEquals(TxResult.INVALID_ACCOUNT, call(s, "weird", s));
        assertEquals(2, s.calls);
    }

    @Test
    void onlyABatchThatCouldNotBeCalledFallsBackToSingleTransfers() throws ReflectiveOperationException {
        final FakeSkarbiec s = new FakeSkarbiec();
        assertNull(call(s, "ok", "not a Skarbiec"), "wrong target: never ran, single transfers allowed");
        assertEquals(0, s.calls);
    }
}
