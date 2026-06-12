package git.chexson.chexsonsaeutils.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2ByteDisplayFormatterTest {

    private static final long BYTE = 1024L;
    private static final long KIB = BYTE;
    private static final long MIB = KIB * BYTE;
    private static final long GIB = MIB * BYTE;
    private static final long TIB = GIB * BYTE;

    @Test
    void formatsCommonAe2DisplayThresholds() {
        assertEquals("1023", Ae2ByteDisplayFormatter.format(1023L));
        assertEquals("1k", Ae2ByteDisplayFormatter.format(KIB));
        assertEquals("1G", Ae2ByteDisplayFormatter.format(GIB));
        assertEquals("1T", Ae2ByteDisplayFormatter.format(TIB));
        assertEquals("2E", Ae2ByteDisplayFormatter.format(Long.MAX_VALUE / 4L));
    }

    @Test
    void keepsComponentTextAlignedWithFormattedBytes() {
        assertEquals("1G", Ae2ByteDisplayFormatter.component(GIB).getString());
        assertEquals("2E", Ae2ByteDisplayFormatter.component(Long.MAX_VALUE / 4L).getString());
    }

    @Test
    void formatsAe2CrashBoundaryAndAboveWithoutOverflow() {
        long crashBoundary = 1000L * TIB;
        String justBelow = Ae2ByteDisplayFormatter.format(crashBoundary - 1L);
        String atBoundary = Ae2ByteDisplayFormatter.format(crashBoundary);
        String aboveBoundary = Ae2ByteDisplayFormatter.format(crashBoundary + TIB);

        assertTrue(justBelow.endsWith("T") || justBelow.endsWith("P"));
        assertTrue(atBoundary.endsWith("P"));
        assertTrue(aboveBoundary.endsWith("P"));
    }
}
