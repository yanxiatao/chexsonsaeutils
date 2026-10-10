package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 使用 AE2 真实加工样板验证压缩输入槽、放大样板及批量投入的材料守恒。 */
class ProcessingTaskInputAccountingTest {
    private static final AEItemKey RAW = AEItemKey.of(Items.RAW_IRON);
    private static final AEItemKey OUT = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BYPRODUCT = AEItemKey.of(Items.STONE);
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static AEProcessingPattern pattern(List<GenericStack> inputs, List<GenericStack> outputs) {
        ItemStack definition = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(definition, inputs, outputs);
        return new AEProcessingPattern(AEItemKey.of(definition));
    }
    private static KeyCounter holder(AEKey key, long amount) {
        var holder = new KeyCounter(); holder.add(key, amount); return holder;
    }
    private static RecipeSignature signature(long raw, long out) {
        return new RecipeSignature(null, List.of(new RecipeSignatureInput(RAW, raw)), List.of(new GenericStack(OUT, out)));
    }

    @Test void replacementMatchesOnlyRealMachineRecipesAndProcessesFullBatch() {
        var replacement = git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwarePatternContractTest.wrap(
                pattern(List.of(new GenericStack(RAW, 4)), List.of(new GenericStack(OUT, 2))));
        var alternate = git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwarePatternContractTest.ALT;
        var originalRecipe = signature(2, 1);
        var alternateRecipe = new RecipeSignature(null, List.of(new RecipeSignatureInput(alternate, 2)),
                List.of(new GenericStack(OUT, 1)));
        var index = new MachineRecipeIndex(MachineIdentity.fromBindingStack(new ItemStack(Items.FURNACE)),
                java.util.Set.of(), List.of(), new java.util.LinkedHashSet<>(List.of(originalRecipe, alternateRecipe)),
                MachineSupportStatus.SUPPORTED_GENERIC, MachineSupportReasonCode.NONE, 1);
        var matches = index.matchingSignatures(replacement);
        assertEquals(List.of(originalRecipe, alternateRecipe), matches);
        var compatibility = PatternCompatibility.supported(MachineSupportStatus.SUPPORTED_GENERIC, replacement, matches);
        var holder = holder(alternate, 40);
        var task = ProcessingTask.compile(compatibility, new KeyCounter[]{holder}, 1);
        assertNotNull(task); assertEquals(20, task.executionCount());
        assertEquals(Map.of(OUT, 20L), DirectProcessingStackSupport.stackAmounts(task.buildOutputPayload()));
        assertEquals(40, holder.get(alternate));
        assertNull(ProcessingTask.compile(compatibility, new KeyCounter[]{holder(alternate, 42)}, 1));
        assertNull(ProcessingTask.compile(compatibility, new KeyCounter[]{holder(BYPRODUCT, 40)}, 1));
        assertNull(ProcessingTask.compile(PatternCompatibility.supportedGeneric(replacement, originalRecipe),
                new KeyCounter[]{holder}, 1));
    }
    @Test void unsupportedOriginalCandidateIsRemovedBeforePlanning() {
        var replacement = git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwarePatternContractTest.wrap(
                pattern(List.of(new GenericStack(RAW, 4)), List.of(new GenericStack(OUT, 2))));
        var alternate = git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwarePatternContractTest.ALT;
        var recipe = new RecipeSignature(null, List.of(new RecipeSignatureInput(alternate, 2)),
                List.of(new GenericStack(OUT, 1)));
        var compatibility = PatternCompatibility.supported(MachineSupportStatus.SUPPORTED_GENERIC,
                replacement, List.of(recipe));
        assertArrayEquals(new GenericStack[]{new GenericStack(alternate, 4)},
                compatibility.pattern().getInputs()[0].getPossibleInputs());
        assertEquals(replacement.getDefinition(), compatibility.pattern().getDefinition());
        assertNotNull(ProcessingTask.compile(compatibility, new KeyCounter[]{holder(alternate, 40)}, 1));
    }

    @Test void overlappingCandidatesCanReassignWithoutGreedyMismatch() {
        var pattern = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern.of(OUT, 1,
                git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput.multi(1, RAW, BYPRODUCT),
                git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput.multi(1, RAW));
        var recipe = new RecipeSignature(null, List.of(new RecipeSignatureInput(RAW, 1),
                new RecipeSignatureInput(BYPRODUCT, 1)), List.of(new GenericStack(OUT, 1)));
        assertFalse(DirectProcessingStackSupport.patternInputs(pattern, recipe).isEmpty());
    }

    @Test void repeatedInputSlotsProcessEveryDeliveredExecution() {
        var pattern = pattern(List.of(new GenericStack(RAW, 1), new GenericStack(RAW, 1)),
                List.of(new GenericStack(OUT, 2)));
        var holder = holder(RAW, 64);
        var task = ProcessingTask.compile(pattern, signature(2, 2), new KeyCounter[]{holder}, 1);
        assertNotNull(task);
        assertEquals(32, task.executionCount());
        assertEquals(Map.of(OUT, 64L), DirectProcessingStackSupport.stackAmounts(task.buildOutputPayload()));
        assertEquals(64, holder.get(RAW), "编译阶段只读取投入，调用方在接收成功后转移所有权");
    }

    @Test void scaledPatternAndBatchMultiplierAreIndependent() {
        var pattern = pattern(List.of(new GenericStack(RAW, 1), new GenericStack(RAW, 1)),
                List.of(new GenericStack(OUT, 4)));
        var task = ProcessingTask.compile(pattern, signature(1, 2), new KeyCounter[]{holder(RAW, 64)}, 1);
        assertNotNull(task);
        assertEquals(64, task.executionCount());
        assertEquals(Map.of(OUT, 128L), DirectProcessingStackSupport.stackAmounts(task.buildOutputPayload()));
        task.advance(31);
        assertFalse(task.isReadyToComplete());
        task.advance(1);
        assertTrue(task.isReadyToComplete());
    }

    @Test void mixedItemFluidBatchPreservesEveryOutputThroughQueueAndSave() {
        var pattern = pattern(List.of(new GenericStack(RAW, 1), new GenericStack(RAW, 1), new GenericStack(WATER, 250)),
                List.of(new GenericStack(OUT, 2), new GenericStack(BYPRODUCT, 1)));
        var recipe = new RecipeSignature(null,
                List.of(new RecipeSignatureInput(RAW, 2), new RecipeSignatureInput(WATER, 250)),
                List.of(new GenericStack(OUT, 2), new GenericStack(BYPRODUCT, 1)));
        var task = ProcessingTask.compile(pattern, recipe, new KeyCounter[]{holder(RAW, 64), holder(WATER, 8000)}, 1);
        assertNotNull(task);
        var restored = ProcessingTask.readFromTag(task.writeToTag(REGISTRIES), REGISTRIES);
        assertNotNull(restored);
        var queue = new ProcessingExecutionQueue(1);
        assertTrue(queue.offer(restored));
        var delivered = new ArrayList<GenericStack>();
        var host = new ProcessingTaskCompletionHost() {
            @Override public int getProcessingWorkUnitsPerTick() { return 64; }
            @Override public boolean isWaitingForOutputReturn() { return false; }
            @Override public void completeProcessingTask(ProcessingTask task) { delivered.addAll(task.buildOutputPayload()); }
        };
        queue.tick(host, 1);
        assertEquals(Map.of(OUT, 64L, BYPRODUCT, 32L), DirectProcessingStackSupport.stackAmounts(delivered));
        assertTrue(queue.isIdle());
    }

    @Test void imbalancedOrPartialPatternBatchIsRejectedWithoutChangingAnyHolder() {
        var pattern = pattern(List.of(new GenericStack(RAW, 2), new GenericStack(WATER, 250)),
                List.of(new GenericStack(OUT, 2)));
        var recipe = new RecipeSignature(null,
                List.of(new RecipeSignatureInput(RAW, 2), new RecipeSignatureInput(WATER, 250)),
                List.of(new GenericStack(OUT, 2)));
        var raw = holder(RAW, 64); var water = holder(WATER, 7750);
        assertNull(ProcessingTask.compile(pattern, recipe, new KeyCounter[]{raw, water}, 1));
        assertEquals(64, raw.get(RAW)); assertEquals(7750, water.get(WATER));
        var scaled = pattern(List.of(new GenericStack(RAW, 2)), List.of(new GenericStack(OUT, 4)));
        var partial = holder(RAW, 3);
        assertNull(ProcessingTask.compile(scaled, signature(1, 2), new KeyCounter[]{partial}, 1));
        assertEquals(3, partial.get(RAW));
    }

    @Test void quantityOverflowRejectsBeforeOwnershipTransfer() {
        var pattern = pattern(List.of(new GenericStack(RAW, 1)), List.of(new GenericStack(OUT, 2)));
        var raw = holder(RAW, Long.MAX_VALUE);
        assertNull(ProcessingTask.compile(pattern, signature(1, 2), new KeyCounter[]{raw}, 1));
        assertEquals(Long.MAX_VALUE, raw.get(RAW));
        var left = holder(RAW, Long.MAX_VALUE); var right = holder(RAW, 1);
        assertNull(ProcessingTask.compile(pattern, signature(1, 2), new KeyCounter[]{left, right}, 1));
        assertEquals(Long.MAX_VALUE, left.get(RAW)); assertEquals(1, right.get(RAW));
    }
}
