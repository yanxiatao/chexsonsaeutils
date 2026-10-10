package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the parallel CPU fast planning path produces correct plans.
 *
 * <p>The fast path reuses AE2's planning tree/simulation state (so results match
 * the native algorithm) but re-drives them without per-tick time slicing or a
 * {@code TickHandler}/{@code Level} dependency. These tests build a synthetic grid
 * and assert the resulting plan, covering single-pattern, multi-branch, missing-item
 * (simulation) and large multi-branch scenarios.
 */
final class FastCraftingCalculationTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);
    private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);

    private static ICraftingPlan runFast(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var calc = new FastCraftingCalculation(
                null, grid, grid.requester, new GenericStack(output, amount), strategy, 0L);
        return calc.run();
    }

    private static ICraftingPlan runFast(FakeGrid grid, AEKey output, long amount,
            CalculationStrategy strategy, boolean forcePerItem) {
        FastLimitQtyBatcher.forcePerItemForTesting = forcePerItem;
        try {
            return runFast(grid, output, amount, strategy);
        } finally {
            FastLimitQtyBatcher.forcePerItemForTesting = false;
        }
    }

    @Test
    void singlePatternCraftProducesExpectedPlan() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(new GenericStack(IRON, 10), plan.finalOutput());
        assertEquals(10L, plan.patternTimes().get(ironFromGold));
        assertEquals(20L, plan.usedItems().get(GOLD));
        assertEquals(0L, plan.usedItems().get(IRON));
    }

    @Test
    void multiBranchUsesPatternsInOrderUntilExhausted() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, DIAMOND, 30L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 15, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertTrue(plan.multiplePaths());
        assertEquals(5L, plan.patternTimes().get(ironFromGold));
        assertEquals(10L, plan.patternTimes().get(ironFromDiamond));
        assertEquals(10L, plan.usedItems().get(GOLD));
        assertEquals(30L, plan.usedItems().get(DIAMOND));
    }

    @Test
    void insufficientIngredientsYieldSimulationPlan() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertTrue(plan.simulation());
        // 10 iron needs 20 gold; only 5 in storage, so 15 gold are reported missing.
        assertEquals(15L, plan.missingItems().get(GOLD));
    }

    @Test
    void craftLessBinarySearchFindsLargestCraftableAmount() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L))
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        // 10 gold -> 5 iron is the largest craftable amount below 32.
        assertEquals(5L, plan.finalOutput().amount());
        assertEquals(5L, plan.patternTimes().get(ironFromGold));
    }

    @Test
    void nestedCraftingChainsThroughIntermediate() {
        // IRON <- 1 GOLD ; GOLD <- 2 REDSTONE. Storage only has REDSTONE.
        FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L))
                .pattern(GOLD, goldFromRedstone)
                .pattern(IRON, ironFromGold)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(10L, plan.patternTimes().get(ironFromGold));
        assertEquals(10L, plan.patternTimes().get(goldFromRedstone));
        assertEquals(20L, plan.usedItems().get(REDSTONE));
    }

    @Test
    void largeMultiBranchPlanCompletesWithinBudget() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 1_000L, DIAMOND, 100_000L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        long start = System.nanoTime();
        // Generous budget so the fast path itself runs to completion.
        var calc = new FastCraftingCalculation(
                null, grid, grid.requester, new GenericStack(IRON, 20_000), CalculationStrategy.REPORT_MISSING_ITEMS, 30_000L);
        ICraftingPlan plan = calc.run();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(20_000L, plan.finalOutput().amount());
        // 500 iron from gold (1000 gold / 2), the rest from diamond.
        assertEquals(500L, plan.patternTimes().get(ironFromGold));
        assertEquals(19_500L, plan.patternTimes().get(ironFromDiamond));
        assertTrue(elapsedMillis < 30_000L, "fast path exceeded budget: " + elapsedMillis + "ms");
    }

    @Test
    void containerItemPatternCyclesAndLimitsRequiredExtract() {
        // 1 IRON <- 2 GOLD + 1 BUCKET, and the bucket is returned (container item).
        // Crafting must run one-by-one (limitQty) so the single bucket cycles, and the
        // plan must only require 1 bucket while consuming all 20 gold.
        FakePattern ironInBucket = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 2),
                new FakeInput(BUCKET, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 20L, BUCKET, 1L))
                .pattern(IRON, ironInBucket)
                .build();

        ICraftingPlan plan = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(new GenericStack(IRON, 10), plan.finalOutput());
        assertEquals(10L, plan.patternTimes().get(ironInBucket));
        assertEquals(20L, plan.usedItems().get(GOLD));
        // The single bucket is reused across all 10 crafts, so only 1 is extracted.
        assertEquals(1L, plan.usedItems().get(BUCKET));
    }

    @Test
    void nestedMultiBranchReusesPooledChildStates() {
        // TOP <- 2 MID ; MID has two branches (from GOLD or from DIAMOND). The nested
        // multi-branch forces the child-state pool to hand out distinct, nested
        // instances; results must still match the native accounting.
        FakePattern midFromGold = FakePattern.crafting(MID, 1, Map.of(GOLD, 2L));
        FakePattern midFromDiamond = FakePattern.crafting(MID, 1, Map.of(DIAMOND, 3L));
        FakePattern topFromMid = FakePattern.crafting(TOP, 1, Map.of(MID, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L, DIAMOND, 300L))
                .pattern(MID, midFromGold, midFromDiamond)
                .pattern(TOP, topFromMid)
                .build();

        ICraftingPlan plan = runFast(grid, TOP, 50, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(new GenericStack(TOP, 50), plan.finalOutput());
        // 50 TOP needs 100 MID; gold branch yields 50 MID (100 gold / 2), the rest from diamond.
        assertEquals(50L, plan.patternTimes().get(midFromGold));
        assertEquals(50L, plan.patternTimes().get(midFromDiamond));
        assertEquals(50L, plan.patternTimes().get(topFromMid));
        assertEquals(100L, plan.usedItems().get(GOLD));
        assertEquals(150L, plan.usedItems().get(DIAMOND));
    }

    @Test
    void limitQtyBatchMatchesPerItemForDirectIngredients() {
        // 1 IRON <- 1 GOLD + 1 BUCKET（桶回流）。干净场景：消耗输入 GOLD 直接取自库存。
        FakePattern ironInBucket = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 1),
                new FakeInput(BUCKET, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L, BUCKET, 1L))
                .pattern(IRON, ironInBucket)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 100, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 100, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        // 桶循环：无论 100 件只需 1 桶；100 件消耗 100 金。
        assertEquals(1L, batched.usedItems().get(BUCKET));
        assertEquals(100L, batched.usedItems().get(GOLD));
        assertEquals(100L, batched.patternTimes().get(ironInBucket));
    }

    @Test
    void limitQtyBatchMatchesPerItemForRecursiveIngredients() {
        // 1 IRON <- 1 GOLD + 1 BUCKET（桶回流），且 GOLD <- 2 REDSTONE 需递归。干净场景。
        FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
        FakePattern ironInBucket = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 1),
                new FakeInput(BUCKET, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 200L, BUCKET, 1L))
                .pattern(GOLD, goldFromRedstone)
                .pattern(IRON, ironInBucket)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 100, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 100, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        assertEquals(1L, batched.usedItems().get(BUCKET));
        assertEquals(200L, batched.usedItems().get(REDSTONE));
        assertEquals(100L, batched.patternTimes().get(ironInBucket));
        assertEquals(100L, batched.patternTimes().get(goldFromRedstone));
    }

    @Test
    void selfOutputBatchMatchesPerItemForCatalystIngredient() {
        // 1 IRON <- 1 GOLD + 1 REDSTONE，产出含 GOLD（自输出催化物，直连处理机/聚合模式
        // 样板的 getRemainingKey 恒为 null，其 limitQty 只会是这一类）。库存只有 1 份
        // GOLD：逐件路径反复“取 GOLD→回流”，批量等价记账必须逐位一致。
        FakePattern ironWithGoldCatalyst = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(REDSTONE, 1)},
                new GenericStack(IRON, 1), new GenericStack(GOLD, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 1L, REDSTONE, 100L))
                .pattern(IRON, ironWithGoldCatalyst)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 100, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 100, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        assertEquals(new GenericStack(IRON, 100), batched.finalOutput());
        assertEquals(100L, batched.patternTimes().get(ironWithGoldCatalyst));
        // 催化物循环：100 件也只提取 1 份 GOLD；消耗 100 份 REDSTONE。
        assertEquals(1L, batched.usedItems().get(GOLD));
        assertEquals(100L, batched.usedItems().get(REDSTONE));
    }

    @Test
    void selfOutputBatchMatchesPerItemWithRecursiveIngredient() {
        // 1 IRON <- 1 GOLD + 1 DIAMOND（催化），且 GOLD <- 2 REDSTONE 需递归：
        // 批量路径的消耗输入按 times 倍递归，循环输入只提取一次。
        FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
        FakePattern ironWithDiamondCatalyst = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(DIAMOND, 1)},
                new GenericStack(IRON, 1), new GenericStack(DIAMOND, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(DIAMOND, 1L, REDSTONE, 400L))
                .pattern(GOLD, goldFromRedstone)
                .pattern(IRON, ironWithDiamondCatalyst)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 200, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 200, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        assertEquals(1L, batched.usedItems().get(DIAMOND));
        assertEquals(400L, batched.usedItems().get(REDSTONE));
        assertEquals(200L, batched.patternTimes().get(ironWithDiamondCatalyst));
        assertEquals(200L, batched.patternTimes().get(goldFromRedstone));
    }

    @Test
    void selfOutputBatchMatchesPerItemInMultiBranchProbe() {
        // IRON 两个分支：分支一 [1 IRON <- 1 GOLD + 1 DIAMOND（催化）]，
        // 分支二 [1 IRON <- 3 REDSTONE]。分支一耗尽 GOLD 后转分支二。
        // 多分支指数探测对 limitQty 过程必须用复用感知批量，不得把催化物的
        // requiredExtract 记成 N 倍。
        FakePattern ironCatalyzed = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(DIAMOND, 1)},
                new GenericStack(IRON, 1), new GenericStack(DIAMOND, 1));
        FakePattern ironFromRedstone = FakePattern.crafting(IRON, 1, Map.of(REDSTONE, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(DIAMOND, 1L, GOLD, 5L, REDSTONE, 1_000L))
                .pattern(IRON, ironCatalyzed, ironFromRedstone)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 50, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 50, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        // 分支一 5 次（5 份 GOLD 耗尽），分支二补 45 次。
        assertEquals(5L, batched.patternTimes().get(ironCatalyzed));
        assertEquals(45L, batched.patternTimes().get(ironFromRedstone));
        assertEquals(1L, batched.usedItems().get(DIAMOND));
        assertEquals(5L, batched.usedItems().get(GOLD));
        assertEquals(135L, batched.usedItems().get(REDSTONE));
    }

    @Test
    void selfOutputBatchMatchesPerItemWhenOutputExceedsConsumption() {
        // 1 IRON <- 1 GOLD，产出含 2 GOLD（催化物放大，每次净 +1）。
        // 批量修正后的产出插入必须与逐件的净增量累计一致。
        FakePattern amplifying = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1)},
                new GenericStack(IRON, 1), new GenericStack(GOLD, 2));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 1L))
                .pattern(IRON, amplifying)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 64, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 64, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        assertEquals(new GenericStack(IRON, 64), batched.finalOutput());
        assertEquals(64L, batched.patternTimes().get(amplifying));
        assertEquals(1L, batched.usedItems().get(GOLD));
    }

    @Test
    void selfOutputBatchMatchesPerItemUnderCraftLess() {
        // 催化物 + 有限 REDSTONE：CRAFT_LESS 二分找最大可合成量，两条路径须一致。
        FakePattern ironWithGoldCatalyst = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(REDSTONE, 1)},
                new GenericStack(IRON, 1), new GenericStack(GOLD, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 1L, REDSTONE, 10L))
                .pattern(IRON, ironWithGoldCatalyst)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS, true);
        ICraftingPlan batched = runFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        // REDSTONE 只有 10 份，最大可合成 10 件。
        assertEquals(10L, batched.finalOutput().amount());
        assertEquals(1L, batched.usedItems().get(GOLD));
        assertEquals(10L, batched.usedItems().get(REDSTONE));
    }

    @Test
    void selfOutputBatchMatchesPerItemWithContainerAndCatalystMixed() {
        // 1 IRON <- 2 GOLD（消耗）+ 1 BUCKET（容器回流）+ 1 DIAMOND（催化）。
        // 容器与自输出为不同键时可同批处理。
        FakePattern mixed = FakePattern.withOutputs(
                new FakeInput[]{
                        new FakeInput(GOLD, 2),
                        new FakeInput(BUCKET, 1, BUCKET),
                        new FakeInput(DIAMOND, 1)
                },
                new GenericStack(IRON, 1), new GenericStack(DIAMOND, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 20L, BUCKET, 1L, DIAMOND, 1L))
                .pattern(IRON, mixed)
                .build();

        ICraftingPlan perItem = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS, true);
        ICraftingPlan batched = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS, false);

        FakePlanGrid.assertPlansEqual(perItem, batched);
        assertEquals(10L, batched.patternTimes().get(mixed));
        assertEquals(20L, batched.usedItems().get(GOLD));
        assertEquals(1L, batched.usedItems().get(BUCKET));
        assertEquals(1L, batched.usedItems().get(DIAMOND));
    }
}
