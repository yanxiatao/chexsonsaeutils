package git.chexson.chexsonsaeutils.crafting.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeapPressureTest {
    private static final long MIB = 1048576L;
    @Test void unusedUncommittedHeapCountsAsHeadroom() {
        var sample = HeapPressureImpl.fromUsage(1024 * MIB, 512 * MIB, 128 * MIB, 256 * MIB);
        assertEquals(640 * MIB, sample.availableBytes());
        assertEquals(256 * MIB, sample.reserveBytes());
        assertFalse(sample.needsSpill(383 * MIB)); assertTrue(sample.needsSpill(384 * MIB));
    }
    @Test void reserveUsesAtLeastTenPercentOfMaximumHeap() {
        var sample = HeapPressureImpl.fromUsage(8192 * MIB, 8192 * MIB, 512 * MIB, 256 * MIB);
        assertEquals(8192 * MIB / 10L, sample.reserveBytes());
        assertTrue(sample.needsSpill(0L));
    }
    @Test void allocationProjectionNeverOverflowsAndDisabledPolicyStaysInactive() {
        var sample = new HeapPressure.Sample(Long.MAX_VALUE, Long.MAX_VALUE - 31L);
        assertFalse(sample.needsSpill(30)); assertTrue(sample.needsSpill(31));
        assertTrue(sample.needsSpill(Long.MAX_VALUE));
        assertFalse(HeapPressure.NONE.sample().needsSpill(256 * MIB));
        assertThrows(IllegalArgumentException.class, () -> sample.needsSpill(-1L));
    }
}
