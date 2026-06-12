package git.chexson.chexsonsaeutils.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2CompactNumberFormatterTest {

    @Test
    void formatsCompactCountsAcrossAe2ParallelCpuRanges() {
        assertEquals("999", Ae2CompactNumberFormatter.format(999L));
        assertEquals("1k", Ae2CompactNumberFormatter.format(1_000L));
        assertEquals("1M", Ae2CompactNumberFormatter.format(1_000_000L));
        assertEquals("2.1G", Ae2CompactNumberFormatter.format(2_147_483_646L));
    }

    @Test
    void keepsComponentTextAlignedWithFormattedCounts() {
        assertEquals("2.1G", Ae2CompactNumberFormatter.component(2_147_483_646L).getString());
        assertEquals("9.2E", Ae2CompactNumberFormatter.component(Long.MAX_VALUE).getString());
    }

    @Test
    void formatsExtremeValuesWithoutThrowing() {
        String maxValue = Ae2CompactNumberFormatter.format(Long.MAX_VALUE);
        String minimumValue = Ae2CompactNumberFormatter.format(Long.MIN_VALUE);

        assertTrue(maxValue.endsWith("E"));
        assertTrue(minimumValue.startsWith("-"));
        assertTrue(minimumValue.endsWith("E"));
    }
}
