package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CraftingContinuationPartialSubmitTest {
    @Test
    void interceptsIgnoreMissingSimulationPlanForAutoSelectedNativeCpu() {
        ICraftingPlan plan = plan(true);

        assertTrue(CraftingContinuationPartialSubmit.shouldInterceptPartialSubmit(
                plan,
                null,
                CraftingContinuationMode.IGNORE_MISSING));
    }

    @Test
    void keepsDefaultSimulationPlanOnStockAe2Path() {
        ICraftingPlan plan = plan(true);

        assertFalse(CraftingContinuationPartialSubmit.shouldInterceptPartialSubmit(
                plan,
                null,
                CraftingContinuationMode.DEFAULT));
    }

    @Test
    void keepsCompletePlanOnStockAe2Path() {
        ICraftingPlan plan = plan(false);

        assertFalse(CraftingContinuationPartialSubmit.shouldInterceptPartialSubmit(
                plan,
                null,
                CraftingContinuationMode.IGNORE_MISSING));
    }

    @Test
    void acceptsNativeOrAutoSelectedCpuTargetsOnly() {
        assertTrue(CraftingContinuationPartialSubmit.supportsPartialSubmitTarget(null));
    }

    @Test
    void nativeSubmissionPlanIsExecutableByStockAe2() {
        ICraftingPlan plan = plan(true);
        ICraftingPlan nativePlan = CraftingContinuationPartialSubmit.createNativeSubmissionPlan(plan);

        assertFalse(nativePlan.simulation());
        assertTrue(nativePlan.usedItems().isEmpty());
        assertTrue(nativePlan.missingItems().isEmpty());
        assertSame(plan.emittedItems(), nativePlan.emittedItems());
        assertSame(plan.patternTimes(), nativePlan.patternTimes());
        assertEquals(plan.finalOutput(), nativePlan.finalOutput());
        assertEquals(plan.bytes(), nativePlan.bytes());
        assertEquals(plan.multiplePaths(), nativePlan.multiplePaths());
    }

    private static ICraftingPlan plan(boolean simulation) {
        AEItemKey output = AEItemKey.of(Items.CRAFTING_TABLE);
        AEItemKey used = AEItemKey.of(Items.OAK_PLANKS);
        AEItemKey missing = AEItemKey.of(Items.STICK);
        AEItemKey emitted = AEItemKey.of(Items.COBBLESTONE);
        KeyCounter usedItems = counter(used, 4L);
        KeyCounter missingItems = counter(missing, 2L);
        KeyCounter emittedItems = counter(emitted, 1L);

        return new TestCraftingPlan(
                new GenericStack(output, 1L),
                1024L,
                simulation,
                true,
                usedItems,
                emittedItems,
                missingItems,
                Map.of()
        );
    }

    private static KeyCounter counter(AEItemKey key, long amount) {
        KeyCounter counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    private record TestCraftingPlan(
            GenericStack finalOutput,
            long bytes,
            boolean simulation,
            boolean multiplePaths,
            KeyCounter usedItems,
            KeyCounter emittedItems,
            KeyCounter missingItems,
            Map<IPatternDetails, Long> patternTimes
    ) implements ICraftingPlan {
    }
}
