package git.chexson.chexsonsaeutils.crafting.memory;

import static org.junit.jupiter.api.Assertions.*;

/** 精确数量与材料账本的行为场景；由类别测试统一调用。 */
public final class NumericScenarios {
    private NumericScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.memory",
                () -> HeapPressureCases.verify(temporaryRoot)
        );
    }

    /** HeapPressureCases 的独立场景夹具，按类别统一执行。 */
    public static final class HeapPressureCases {
        private static final long MIB = 1048576L;
        void unusedUncommittedHeapCountsAsHeadroom() {
            var sample = HeapPressureImpl.fromUsage(1024 * MIB, 512 * MIB, 128 * MIB, 256 * MIB);
            assertEquals(640 * MIB, sample.availableBytes());
            assertEquals(256 * MIB, sample.reserveBytes());
            assertFalse(sample.needsSpill(383 * MIB)); assertTrue(sample.needsSpill(384 * MIB));
        }
        void reserveUsesAtLeastTenPercentOfMaximumHeap() {
            var sample = HeapPressureImpl.fromUsage(8192 * MIB, 8192 * MIB, 512 * MIB, 256 * MIB);
            assertEquals(8192 * MIB / 10L, sample.reserveBytes());
            assertTrue(sample.needsSpill(0L));
        }
        void allocationProjectionNeverOverflowsAndDisabledPolicyStaysInactive() {
            var sample = new HeapPressure.Sample(Long.MAX_VALUE, Long.MAX_VALUE - 31L);
            assertFalse(sample.needsSpill(30)); assertTrue(sample.needsSpill(31));
            assertTrue(sample.needsSpill(Long.MAX_VALUE));
            assertFalse(HeapPressure.NONE.sample().needsSpill(256 * MIB));
            assertThrows(IllegalArgumentException.class, () -> sample.needsSpill(-1L));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("HeapPressureCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("HeapPressureCases.unusedUncommittedHeapCountsAsHeadroom", () -> new HeapPressureCases().unusedUncommittedHeapCountsAsHeadroom()),
                () -> org.junit.jupiter.api.Assertions.assertAll("HeapPressureCases.reserveUsesAtLeastTenPercentOfMaximumHeap", () -> new HeapPressureCases().reserveUsesAtLeastTenPercentOfMaximumHeap()),
                () -> org.junit.jupiter.api.Assertions.assertAll("HeapPressureCases.allocationProjectionNeverOverflowsAndDisabledPolicyStaysInactive", () -> new HeapPressureCases().allocationProjectionNeverOverflowsAndDisabledPolicyStaysInactive())
            );
        }
    }
}
