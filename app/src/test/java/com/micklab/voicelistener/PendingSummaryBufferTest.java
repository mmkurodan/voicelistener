package com.micklab.voicelistener;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PendingSummaryBufferTest {
    @Test
    public void appendEntry_joinsNormalizedEntriesWithNewlines() {
        String appended = PendingSummaryBuffer.appendEntry("最初の発話", "  次の発話  ");

        assertEquals("最初の発話\n次の発話", appended);
    }

    @Test
    public void removeConsumedPrefix_preservesNewerEntries() {
        String remaining = PendingSummaryBuffer.removeConsumedPrefix(
            "A\nB\nC",
            "A\nB"
        );

        assertEquals("C", remaining);
    }

    @Test
    public void removeConsumedPrefix_returnsCurrentWhenPrefixDoesNotMatch() {
        String remaining = PendingSummaryBuffer.removeConsumedPrefix(
            "A\nB\nC",
            "X\nY"
        );

        assertEquals("A\nB\nC", remaining);
    }
}
