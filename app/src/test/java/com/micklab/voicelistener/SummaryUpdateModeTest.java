package com.micklab.voicelistener;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SummaryUpdateModeTest {
    @Test
    public void fromPreference_defaultsToAutoForUnknownValues() {
        assertEquals(SummaryUpdateMode.AUTO, SummaryUpdateMode.fromPreference(null));
        assertEquals(SummaryUpdateMode.AUTO, SummaryUpdateMode.fromPreference("unknown"));
    }

    @Test
    public void fromPreference_readsManualCaseInsensitively() {
        assertEquals(SummaryUpdateMode.MANUAL, SummaryUpdateMode.fromPreference("manual"));
        assertEquals(SummaryUpdateMode.MANUAL, SummaryUpdateMode.fromPreference(" MANUAL "));
    }
}
