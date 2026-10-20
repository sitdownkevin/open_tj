package io.github.sitdownkevin.opentj.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test fun trafficFormattingHandlesUnitsAndInvalidValues() {
        assertEquals("0 B", formatBytes(-1.0))
        assertEquals("0 B", formatBytes(Double.NaN))
        assertEquals("512 B", formatBytes(512.0))
        assertEquals("1.0 KB", formatBytes(1024.0))
        assertEquals("1.5 MB", formatBytes(1572864.0))
    }
}
