package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import git.chexson.chexsonsaeutils.support.TestKeySupport;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelElapsedTimeTrackerTest {

    @Test
    void mirrorsAe2ProgressScalingAcrossStartedAndCompletedWork() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey key = new DummyKey("parallel_tracker");
        ParallelElapsedTimeTracker tracker = new ParallelElapsedTimeTracker();

        tracker.addMaxItems(16L, key.getType());
        tracker.decrementItems(4L, key.getType());

        assertEquals(Integer.MAX_VALUE, tracker.getStartItemCount());
        assertTrue(tracker.getRemainingItemCount() > 0L);
        assertTrue(tracker.getRemainingItemCount() < Integer.MAX_VALUE);
        assertTrue(tracker.getCompletedUnits() > 0.0d);

        tracker.decrementItems(12L, key.getType());

        assertEquals(0L, tracker.getRemainingItemCount());
        assertTrue(tracker.getElapsedTime() >= 0L);
    }
}
