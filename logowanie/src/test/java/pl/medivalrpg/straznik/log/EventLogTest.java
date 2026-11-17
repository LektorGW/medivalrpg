// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straznik.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EventLogTest {

    @Test
    void fieldsHaveNoWhitespaceOfAnyKind() {
        assertEquals("-", EventLog.clean(null));
        assertEquals("-", EventLog.clean(""));
        assertEquals("Gracz_1", EventLog.clean("Gracz 1"));
        // tab and line breaks must not split or forge a line of zdarzenia.log
        assertEquals("a_b_c_d", EventLog.clean("a\tb\nc\rd"));
        assertEquals("LOGIN_OK", EventLog.clean("LOGIN_OK"));
    }
}
