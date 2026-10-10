package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.stacks.AEKeyType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 精确工作量经过累计、实物回交、存档及 AE2 状态比例投影后仍报告实际进度。 */
final class ParallelElapsedTimeTrackerTest {
    @Test void wideWorkDoesNotFinishAtTheFirstLongSizedReturn() {
        var tracker = new ParallelElapsedTimeTracker();
        for (int batch = 0; batch < 100; batch++) tracker.addMaxItems(Long.MAX_VALUE, AEKeyType.items());
        for (int batch = 0; batch < 10; batch++) tracker.decrementItems(Long.MAX_VALUE, AEKeyType.items());
        assertEquals(0.1f, tracker.getProgress(), 0.000001f);
        assertEquals(0.1d, (tracker.getStartItemCount() - tracker.getRemainingItemCount())
                / (double) tracker.getStartItemCount(), 0.000001d);
        var resumed = new ParallelElapsedTimeTracker(tracker.writeToNBT());
        assertEquals(0.1f, resumed.getProgress(), 0.000001f);
        for (int batch = 0; batch < 90; batch++) resumed.decrementItems(Long.MAX_VALUE, AEKeyType.items());
        assertEquals(1.0f, resumed.getProgress());
        assertEquals(0L, resumed.getRemainingItemCount());
    }
    @Test void enormousBatchSurvivesPersistenceWithoutFloatingPointOverflow() {
        var tracker = new ParallelElapsedTimeTracker();
        var total = java.math.BigInteger.TEN.pow(400);
        tracker.addMaxItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total), AEKeyType.items());
        tracker.decrementItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total.divide(java.math.BigInteger.valueOf(4))), AEKeyType.items());
        var resumed = new ParallelElapsedTimeTracker(tracker.writeToNBT());
        assertEquals(0.25f, resumed.getProgress());
        assertTrue(resumed.getRemainingItemCount() > 0);
    }

    @Test void itemAndFluidWorkUsesTheirOwnUnits() {
        var tracker = new ParallelElapsedTimeTracker();
        tracker.addMaxItems(100, AEKeyType.items());
        tracker.addMaxItems(100L * AEKeyType.fluids().getAmountPerUnit(), AEKeyType.fluids());
        tracker.decrementItems(100, AEKeyType.items());
        assertEquals(0.5f, tracker.getProgress());
    }
    @Test void lastUnitCannotRoundAnUnfinishedOrderToComplete() {
        var tracker = new ParallelElapsedTimeTracker();
        var total = java.math.BigInteger.TEN.pow(400);
        tracker.addMaxItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total), AEKeyType.items());
        tracker.decrementItems(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(total.subtract(java.math.BigInteger.ONE)), AEKeyType.items());
        assertTrue(tracker.getProgress() < 1.0f);
        assertEquals(1L, tracker.getRemainingItemCount());
        tracker.decrementItems(1L, AEKeyType.items());
        assertEquals(1.0f, tracker.getProgress());
        assertEquals(0L, tracker.getRemainingItemCount());
    }
}
