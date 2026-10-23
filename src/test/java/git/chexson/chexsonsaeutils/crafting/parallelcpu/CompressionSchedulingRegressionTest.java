package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 复现实机缺料任务被同物品不同等级反复唤醒，以及删表后的断点漂移。 */
class CompressionSchedulingRegressionTest {
    @Test
    void arrivalOfOneCompressionTierOnlyWakesItsActualConsumer() {
        var job = job();
        var patterns = new ArrayList<IPatternDetails>();
        var keys = new ArrayList<AEItemKey>();
        for (int index = 0; index < 4000; index++) {
            var key = FakePlanGrid.dyedKey(Items.PAPER, index);
            keys.add(key);
            var pattern = FakePattern.of(AEItemKey.of(Items.DIAMOND), 1, new FakeInput(key, 9));
            patterns.add(pattern);
            ParallelCraftingCpuLogic.recordMaterialBackoff(job, pattern, 10);
        }
        ParallelCraftingCpuLogic.wakeMaterialBackoff(job, keys.get(137), null);
        assertFalse(job.materialBackoffUntil.containsKey(patterns.get(137)));
        assertEquals(3999, job.materialBackoffUntil.size());
        for (int index = 0; index < 4000; index++) {
            if (index != 137) assertTrue(job.materialBackoffUntil.containsKey(patterns.get(index)));
        }
    }

    @Test
    void fuzzyVariantStillWakesAConsumerThatActuallyAcceptsIt() {
        var job = job();
        var template = FakePlanGrid.dyedKey(Items.PAPER, 1);
        var variant = FakePlanGrid.dyedKey(Items.PAPER, 2);
        var pattern = FakePattern.of(AEItemKey.of(Items.BOOK), 1, FakeInput.anyVariant(template, 1));
        ParallelCraftingCpuLogic.recordMaterialBackoff(job, pattern, 10);
        ParallelCraftingCpuLogic.wakeMaterialBackoff(job, variant, null);
        assertTrue(job.materialBackoffUntil.isEmpty());
        assertTrue(job.materialBackoffMissing.isEmpty());
    }

    @Test
    void deletingCompletedPrefixDoesNotSkipTheNewlyReadyNextStage() {
        var tasks = new LinkedHashMap<IPatternDetails, Integer>();
        for (int index = 0; index < 4000; index++) {
            var key = FakePlanGrid.dyedKey(Items.PAPER, index);
            tasks.put(FakePattern.of(key, 1), index);
        }
        var snapshot = new ArrayList<>(tasks.entrySet());
        var next = snapshot.get(137).getKey();
        for (int index = 0; index < 137; index++) tasks.remove(snapshot.get(index).getKey());
        var remaining = new ArrayList<>(tasks.entrySet());
        assertEquals(0, ParallelCraftingCpuLogic.resumeTaskIndex(remaining, next, 137));
        assertSame(next, remaining.get(ParallelCraftingCpuLogic.resumeTaskIndex(remaining, next, 137)).getKey());
        tasks.remove(next);
        assertEquals(0, ParallelCraftingCpuLogic.resumeTaskIndex(new ArrayList<>(tasks.entrySet()), next, 137));
    }

    private static ParallelExecutingCraftingJob job() {
        var plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.DIAMOND), 1), 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        return new ParallelExecutingCraftingJob(plan, ignored -> {}, null, null);
    }
}
