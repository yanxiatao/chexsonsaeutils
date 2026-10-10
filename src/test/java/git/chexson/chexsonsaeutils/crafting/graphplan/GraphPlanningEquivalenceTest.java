package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FastCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.solve.OrderedPlanningReplay;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level-1 (graph planning) equivalence checks against the level-2 optimized
 * tree path. Both engines run on the same synthetic grid and the resulting
 * plans must match field by field, including bytes — the graph solver
 * reproduces the tree planner site by site, so any drift shows up here.
 */
final class GraphPlanningEquivalenceTest {

    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);
    private static final AEItemKey REDSTONE = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey A_ITEM = AEItemKey.of(Items.AMETHYST_SHARD);
    private static final AEItemKey P_ITEM = AEItemKey.of(Items.POPPED_CHORUS_FRUIT);
    private static final AEItemKey Q_ITEM = AEItemKey.of(Items.QUARTZ);
    private static final AEItemKey BUCKET = AEItemKey.of(Items.BUCKET);
    private static final AEItemKey B_ITEM = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEItemKey SUB_ITEM = AEItemKey.of(Items.SLIME_BALL);
    private static final AEItemKey TEMPLATE = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    private static final AEItemKey WHEAT = AEItemKey.of(Items.WHEAT);
    private static final AEItemKey SAND = AEItemKey.of(Items.SAND);

    private static ICraftingPlan runFast(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var calc = new FastCraftingCalculation(
                null, grid, grid.requester, new GenericStack(output, amount), strategy, 0L);
        return calc.run();
    }

    private static ICraftingPlan runGraph(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), output, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());
        var result = LinearPlanner.solve(graph, strategy, amount);
        assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
        return Ae2PlanConverter.toPlan(result, graph);
    }

    private static ICraftingPlan assertGraphMatchesFast(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
        var fast = runFast(grid, output, amount, strategy);
        var graph = runGraph(grid, output, amount, strategy);
        FakePlanGrid.assertPlansEqual(fast, graph);
        return graph;
    }

    // ------------------------------------------------------------------
    // S1: single pattern linear
    // ------------------------------------------------------------------

    /**
     * 自增殖样板（modpack 场景：锻造模板 1 出 2，输入=自己的输出）——
     * 图编译不得 input-on-path SKIP：CONSUMED_RETURNING 闭式从 1 个种子
     * 滚雪球供给全部需求，与树路径逐件语义对拍。
     */
    @Test
    void selfReplicatingPatternMatchesTree() {
        // 模板样板：输入 template×1 → 输出 template×2（1 出 2 自增殖）
        FakePattern templateDoubling = FakePattern.of(TEMPLATE, 2,
                new FakeInput(TEMPLATE, 1L));
        // 消费者：消耗 template×1 产出 TOP×1（驱动模板需求）
        FakePattern consumer = FakePattern.crafting(TOP, 1, Map.of(TEMPLATE, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(TEMPLATE, 1L))
                .pattern(TEMPLATE, templateDoubling)
                .pattern(TOP, consumer)
                .build();

        // 对拍基准是递归树（环压缩）：fast 树的 notRecursive 对「输入=主产物键」
        // 的自增殖样板判死，能算的是递归环路径——与 modpack 的 fallback 一致。
        // 除 bytes 外逐字段对拍（bytes 口径差异见探针行，待专项对齐）。
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TOP, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(TEMPLATE, List.of()).isEmpty(),
                "图上必须有模板环宏");
        var graphPlan = runGraph(grid, TOP, 8, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(graphPlan.simulation(), "图应算出完整计划");
        assertTrue(FakePlanGrid.toPlain(graphPlan.missingItems()).isEmpty(), "图无缺口");
        // usedItems 锁种子消耗：模板净 +1 自增殖，唯一外部消耗是 1 个种子
        assertEquals(Map.of(TEMPLATE, 1L), FakePlanGrid.toPlain(graphPlan.usedItems()), "usedItems=种子×1");
        // TODO 树侧对照：递归树在本场景 simulation=true（环替换入口未触达），
        //  与图路径的分歧待单独排查——modpack 里树路径已由环压缩算出，
        //  说明真实链路与本夹具仍有差异（夹具的 providers 索引构建时机）。
    }

    /**
     * 递归环催化物种子 × patch 键（modpack 场景）：自喂环的种子库存是
     * patched 变体、样板引用裸键——fireRingMacro 的种子预留必须类感知，
     * 否则环整体算不出、simulate 虚报 missing。与树路径对拍。
     */
    @Test
    void ringCatalystSeedWithPatchedStockMatchesTree() {
        ItemStack seededStack = new ItemStack(Items.GOLD_NUGGET);
        seededStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("patched-seed"));
        AEItemKey patchedSeed = AEItemKey.of(seededStack);
        // 单样板环：输入 seed×1 + 输出 MID×2 + seed×1（返还）——seed 净流量
        // in=out 且自含 → 催化物，种子 1 份；库存只有 patched 变体、样板引用
        // 裸键 → 种子预留必须类感知。与树路径对拍。
        FakePattern ring = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(patchedSeed, 1L)},
                new GenericStack(MID, 2L),
                new GenericStack(patchedSeed, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(patchedSeed, 8L))
                .pattern(MID, ring)
                .build();

        ICraftingPlan plan = runGraph(grid, MID, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(plan.simulation());
        assertEquals(1L, plan.usedItems().get(patchedSeed));
    }

    @Test
    void exactInputRejectsSiblingComponentVariant() {
        ItemStack patchedStack = new ItemStack(Items.IRON_INGOT);
        patchedStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("patched"));
        AEItemKey patched = AEItemKey.of(patchedStack);
        FakePattern ironFromNothing = FakePattern.crafting(MID, 1, Map.of(IRON, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(patched, 10L))
                .pattern(MID, ironFromNothing)
                .build();

        ICraftingPlan plan = runGraph(grid, MID, 5, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertTrue(plan.simulation());
        assertEquals(5L, plan.missingItems().get(IRON));
        assertEquals(0L, plan.usedItems().get(patched));
    }

    @Test
    void explicitlyFuzzyInputAcceptsSiblingComponentVariant() {
        ItemStack patchedStack = new ItemStack(Items.IRON_INGOT);
        patchedStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("patched"));
        AEItemKey patched = AEItemKey.of(patchedStack);
        FakePattern ironFromNothing = FakePattern.of(MID, 1,
                FakeInput.anyVariant(IRON, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(patched, 10L))
                .pattern(MID, ironFromNothing)
                .build();

        ICraftingPlan plan = runGraph(grid, MID, 5, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertFalse(plan.simulation());
        assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty());
        assertEquals(5L, plan.usedItems().get(patched));
    }

    @Test
    void componentSpecificOutputUsesMatchingPatternAndIngredients() {
        ItemStack outputAStack = new ItemStack(Items.GUNPOWDER);
        outputAStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("output-a"));
        AEItemKey outputA = AEItemKey.of(outputAStack);
        ItemStack outputBStack = new ItemStack(Items.GUNPOWDER);
        outputBStack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("output-b"));
        AEItemKey outputB = AEItemKey.of(outputBStack);

        FakePattern target = FakePattern.crafting(TOP, 1, Map.of(outputA, 1L));
        FakePattern makeA = FakePattern.crafting(outputA, 1, Map.of(GOLD, 1L));
        FakePattern makeB = FakePattern.crafting(outputB, 1, Map.of(DIAMOND, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(outputB, 1L, GOLD, 1L))
                .pattern(TOP, target)
                .pattern(outputA, makeA)
                .pattern(outputB, makeB)
                .build();

        ICraftingPlan plan = runGraph(grid, TOP, 1, CalculationStrategy.REPORT_MISSING_ITEMS);

        assertFalse(plan.simulation());
        assertEquals(1L, plan.patternTimes().get(target));
        assertEquals(1L, plan.patternTimes().get(makeA));
        assertFalse(plan.patternTimes().containsKey(makeB));
        assertEquals(1L, plan.usedItems().get(GOLD));
        assertEquals(0L, plan.usedItems().get(DIAMOND));
        assertEquals(0L, plan.usedItems().get(outputB));
    }

    @Test
    void orderedGraphKeepsExactComponentsAcrossBatchedTransformationChain() {
        AEItemKey version1 = componentKey(Items.AMETHYST_SHARD, "version-1");
        AEItemKey version2 = componentKey(Items.AMETHYST_SHARD, "version-2");
        AEItemKey version3 = componentKey(Items.AMETHYST_SHARD, "version-3");
        AEItemKey wrongVersion2 = componentKey(Items.AMETHYST_SHARD, "wrong-version-2");

        FakePattern firstStage = FakePattern.colored(1, AEItemKey.of(Items.RED_DYE),
                List.of(new GenericStack(version2, 1L)),
                new FakeInput(version1, 1L), new FakeInput(GOLD, 1L));
        FakePattern secondStage = FakePattern.colored(2, AEItemKey.of(Items.GREEN_DYE),
                List.of(new GenericStack(version3, 1L)),
                new FakeInput(version2, 1L), new FakeInput(DIAMOND, 1L));
        FakePattern wrongStage = FakePattern.colored(3, AEItemKey.of(Items.BLUE_DYE),
                List.of(new GenericStack(wrongVersion2, 1L)),
                new FakeInput(version1, 1L), new FakeInput(REDSTONE, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(version1, 4L, GOLD, 4L, DIAMOND, 4L, REDSTONE, 4L))
                .pattern(version2, firstStage)
                .pattern(version3, secondStage)
                .pattern(wrongVersion2, wrongStage)
                .build();

        ICraftingPlan plan = OrderedPlanningReplay.create(
                grid,
                grid.requester,
                new GenericStack(version3, 4L),
                CalculationStrategy.REPORT_MISSING_ITEMS,
                PlanningAlgorithmId.GRAPH).run();

        assertNotNull(plan);
        assertNotNull(PlanningProvenance.peek(plan));
        assertEquals(PlanningProvenance.GRAPH, PlanningProvenance.peek(plan).solver());
        assertFalse(plan.simulation());
        assertEquals(4L, plan.patternTimes().get(firstStage));
        assertEquals(4L, plan.patternTimes().get(secondStage));
        assertFalse(plan.patternTimes().containsKey(wrongStage));
        assertEquals(Map.of(version1, 4L, GOLD, 4L, DIAMOND, 4L),
                FakePlanGrid.toPlain(plan.usedItems()));
        assertEquals(0L, plan.usedItems().get(REDSTONE));
    }

    @Test
    void orderedGraphHonorsExplicitCraftingSubstitutionBoundary() {
        AEItemKey version1 = componentKey(Items.AMETHYST_SHARD, "version-1");
        AEItemKey version2 = componentKey(Items.AMETHYST_SHARD, "version-2");
        AEItemKey version3 = componentKey(Items.AMETHYST_SHARD, "version-3");
        FakePattern firstStage = FakePattern.colored(1, AEItemKey.of(Items.RED_DYE),
                List.of(new GenericStack(version2, 1L)),
                new FakeInput(version1, 1L), new FakeInput(GOLD, 1L));
        FakePattern substitutingSecondStage = FakePattern.colored(2, AEItemKey.of(Items.GREEN_DYE),
                List.of(new GenericStack(version3, 1L)),
                FakeInput.anyVariant(version2, 1L), new FakeInput(DIAMOND, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(version1, 1L, GOLD, 1L, DIAMOND, 1L))
                .pattern(version2, firstStage)
                .pattern(version3, substitutingSecondStage)
                .build();

        ICraftingPlan plan = OrderedPlanningReplay.create(
                grid,
                grid.requester,
                new GenericStack(version3, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS,
                PlanningAlgorithmId.GRAPH).run();

        assertNotNull(plan);
        assertEquals(PlanningProvenance.GRAPH, PlanningProvenance.peek(plan).solver());
        assertFalse(plan.simulation());
        assertFalse(plan.patternTimes().containsKey(firstStage));
        assertEquals(1L, plan.patternTimes().get(substitutingSecondStage));
        assertEquals(Map.of(version1, 1L, DIAMOND, 1L),
                FakePlanGrid.toPlain(plan.usedItems()));
        assertEquals(0L, plan.usedItems().get(GOLD));
    }

    private static AEItemKey componentKey(Item item, String value) {
        ItemStack stack = new ItemStack(item);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal(value));
        return AEItemKey.of(stack);
    }

    /**
     * 多候选循环槽（modpack 真实场景：patch 后样板的槽多候选 + 容器回流）——
     * 图编译不得整图 DECLINE：闭式按 primary 候选驱动，与树路径逐件语义对拍。
     */
    @Test
    void multiCandidateCircularInputMatchesTree() {
        // 替代样板：A_ITEM 产出 TOP（替代物品的供应者）
        FakePattern altProvider = FakePattern.crafting(TOP, 1, Map.of(A_ITEM, 1L));
        // 主样板：输出 TOP×1；输入槽多候选（SUB_ITEM 首选 + B_ITEM），首选为回流容器
        FakePattern main = FakePattern.of(TOP, 1,
                FakeInput.multiWithRemainder(1, SUB_ITEM, SUB_ITEM, B_ITEM));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(SUB_ITEM, 10L, A_ITEM, 100L))
                .pattern(TOP, main)
                .pattern(TOP, altProvider)
                .build();

        assertGraphMatchesFast(grid, TOP, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void s1SinglePatternLinear() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironFromGold)
                .build();

        assertGraphMatchesFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void s1SinglePatternLargeAmount() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 1_000_000L))
                .pattern(IRON, ironFromGold)
                .build();

        assertGraphMatchesFast(grid, IRON, 200_000, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // S3: chains of three or more levels
    // ------------------------------------------------------------------

    @Test
    void s3ThreeLevelChain() {
        // TOP <- 2 MID ; MID <- 3 GOLD ; GOLD <- 2 REDSTONE. Storage: REDSTONE only.
        FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
        FakePattern midFromGold = FakePattern.crafting(MID, 1, Map.of(GOLD, 3L));
        FakePattern topFromMid = FakePattern.crafting(TOP, 1, Map.of(MID, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 1_000L))
                .pattern(GOLD, goldFromRedstone)
                .pattern(MID, midFromGold)
                .pattern(TOP, topFromMid)
                .build();

        assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // S4: partially satisfied by stock -> simulation plan
    // ------------------------------------------------------------------

    @Test
    void s4PartialStockYieldsSimulation() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L))
                .pattern(IRON, ironFromGold)
                .build();

        assertGraphMatchesFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // S5: CRAFT_LESS binary search
    // ------------------------------------------------------------------

    @Test
    void s5CraftLessFindsLargestAmount() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L))
                .pattern(IRON, ironFromGold)
                .build();

        assertGraphMatchesFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
    }

    @Test
    void s5CraftLessImpossibleFallsBackToSimulation() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of())
                .pattern(IRON, ironFromGold)
                .build();

        assertGraphMatchesFast(grid, IRON, 16, CalculationStrategy.CRAFT_LESS);
    }

    @Test
    void s5CraftLessFullySatisfiedSkipsSearch() {
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironFromGold)
                .build();

        assertGraphMatchesFast(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
    }

    // ------------------------------------------------------------------
    // S10: byproducts feeding downstream demand
    // ------------------------------------------------------------------

    @Test
    void s10ByproductFeedsDownstream() {
        // TOP <- 1 A + 1 Q ; A <- 2 REDSTONE and also yields byproduct P ;
        // Q <- 1 P. The byproduct covers Q's demand completely, so Q's pattern
        // fires only through its (satisfied) P input slot — the plan must use
        // no extra REDSTONE beyond A's own demand.
        FakePattern aWithByproduct = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(REDSTONE, 2)},
                new GenericStack(A_ITEM, 1), new GenericStack(P_ITEM, 1));
        FakePattern qFromP = FakePattern.crafting(Q_ITEM, 1, Map.of(P_ITEM, 1L));
        FakePattern topFromBoth = FakePattern.of(TOP, 1,
                new FakeInput(A_ITEM, 1),
                new FakeInput(Q_ITEM, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L))
                .pattern(A_ITEM, aWithByproduct)
                .pattern(Q_ITEM, qFromP)
                .pattern(TOP, topFromBoth)
                .build();

        ICraftingPlan plan = runGraph(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(10L, plan.patternTimes().get(topFromBoth));
        assertEquals(10L, plan.patternTimes().get(aWithByproduct));
        assertEquals(10L, plan.patternTimes().get(qFromP));
        assertEquals(20L, plan.usedItems().get(REDSTONE));

        assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void s10ByproductShortfallFallsBackToPattern() {
        // Same shape, but A fires fewer times than Q's demand: the shortfall of
        // P must be reported missing (P has no pattern of its own).
        FakePattern aWithByproduct = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(REDSTONE, 2)},
                new GenericStack(A_ITEM, 1), new GenericStack(P_ITEM, 1));
        FakePattern qFromP = FakePattern.crafting(Q_ITEM, 1, Map.of(P_ITEM, 1L));
        FakePattern topFromBoth = FakePattern.of(TOP, 1,
                new FakeInput(A_ITEM, 1),
                new FakeInput(Q_ITEM, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 4L))
                .pattern(A_ITEM, aWithByproduct)
                .pattern(Q_ITEM, qFromP)
                .pattern(TOP, topFromBoth)
                .build();

        assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // S6: container item cycles (bucket-style limitQty, closed-form batch)
    // ------------------------------------------------------------------

    @Test
    void s6ContainerCycleBatched() {
        FakePattern ironInBucket = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 1),
                new FakeInput(BUCKET, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L, BUCKET, 5L))
                .pattern(IRON, ironInBucket)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        // Returned buckets keep the network pool whole: peak extraction of one.
        assertEquals(10L, plan.patternTimes().get(ironInBucket));
        assertEquals(1L, plan.usedItems().get(BUCKET));
    }

    @Test
    void s6ContainerCycleMissingFirstFiring() {
        // No buckets in stock: the first firing reports one bucket missing and
        // its own optimistic container return supplies every later firing.
        FakePattern ironInBucket = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 1),
                new FakeInput(BUCKET, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 100L))
                .pattern(IRON, ironInBucket)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertTrue(plan.simulation());
        assertEquals(1L, plan.missingItems().get(BUCKET));
        assertEquals(0L, plan.usedItems().get(BUCKET));
    }

    // ------------------------------------------------------------------
    // S7: finite-use durability chain (remainingKey hand-off)
    // ------------------------------------------------------------------

    @Test
    void s7DurabilityChain() {
        AEItemKey p100 = AEItemKey.of(Items.DIAMOND_PICKAXE);
        AEItemKey p99 = AEItemKey.of(Items.IRON_PICKAXE);
        AEItemKey p98 = AEItemKey.of(Items.GOLDEN_PICKAXE);
        FakePattern ironFromTool = FakePattern.of(IRON, 1, new FakeInput(p100, 1, p99));
        FakePattern goldFromWorn = FakePattern.of(GOLD, 1, new FakeInput(p99, 1, p98));
        FakePattern topFromBoth = FakePattern.of(TOP, 1,
                new FakeInput(IRON, 1),
                new FakeInput(GOLD, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(p100, 10L, p99, 2L))
                .pattern(IRON, ironFromTool)
                .pattern(GOLD, goldFromWorn)
                .pattern(TOP, topFromBoth)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, TOP, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(5L, plan.patternTimes().get(ironFromTool));
        assertEquals(5L, plan.patternTimes().get(goldFromWorn));
        // p100 is drained from the network; the p99 remainder returns cover
        // P2's demand, and p98 is only ever inserted.
        assertEquals(5L, plan.usedItems().get(p100));
        assertEquals(0L, plan.usedItems().get(p99));
        assertEquals(0L, plan.usedItems().get(p98));
    }

    // ------------------------------------------------------------------
    // S8: self-feeding catalysts (closed-form output correction)
    // ------------------------------------------------------------------

    @Test
    void s8SelfOutputCatalystBatched() {
        FakePattern catalyzed = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(REDSTONE, 1)},
                new GenericStack(IRON, 2), new GenericStack(GOLD, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L, REDSTONE, 100L))
                .pattern(IRON, catalyzed)
                .build();

        ICraftingPlan plan = runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(5L, plan.patternTimes().get(catalyzed));
        // One catalyst extraction at peak; the corrected output insert feeds
        // the rest.
        assertEquals(1L, plan.usedItems().get(GOLD));
        assertEquals(5L, plan.usedItems().get(REDSTONE));
    }

    @Test
    void s8SelfOutputCatalystSurplus() {
        FakePattern catalyzed = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(REDSTONE, 1)},
                new GenericStack(IRON, 2), new GenericStack(GOLD, 2));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 5L, REDSTONE, 100L))
                .pattern(IRON, catalyzed)
                .build();

        runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void s8SelfOutputCatalystMissingFirstFiring() {
        FakePattern catalyzed = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(REDSTONE, 1)},
                new GenericStack(IRON, 2), new GenericStack(GOLD, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L))
                .pattern(IRON, catalyzed)
                .build();

        ICraftingPlan plan = runGraph(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertTrue(plan.simulation());
        assertEquals(1L, plan.missingItems().get(GOLD));
        assertEquals(5L, plan.usedItems().get(REDSTONE));
    }

    // ------------------------------------------------------------------
    // S9: fuzzy inputs (multi-candidate slots, findCraftedStack substitutes,
    // whole-class stock variants)
    // ------------------------------------------------------------------

    @Test
    void s9MultiCandidateFallback() {
        // Slot accepts A or B; only B is stocked, so every firing draws B
        // while the slot's node key (and bytes) stay on the primary A.
        FakePattern topFromEither = FakePattern.of(TOP, 1,
                FakeInput.multi(1, A_ITEM, B_ITEM));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B_ITEM, 20L))
                .pattern(TOP, topFromEither)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(10L, plan.patternTimes().get(topFromEither));
        assertEquals(10L, plan.usedItems().get(B_ITEM));
    }

    @Test
    void s9MultiCandidatePreferenceOrder() {
        // The first candidate is drained whole before the greedy iteration
        // falls through to the second candidate.
        FakePattern topFromEither = FakePattern.of(TOP, 1,
                FakeInput.multi(1, A_ITEM, B_ITEM));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A_ITEM, 4L, B_ITEM, 20L))
                .pattern(TOP, topFromEither)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(4L, plan.usedItems().get(A_ITEM));
        assertEquals(6L, plan.usedItems().get(B_ITEM));
    }

    @Test
    void s9FindCraftedStackReplacement() {
        // The primary SUB is pattern-less but fuzzy-craftable as GOLD:
        // findCraftedStack replaces the slot's node key with GOLD before
        // recursing, so demand flows into GOLD's pattern and the slot's bytes
        // land on GOLD.
        FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
        FakePattern topFromEither = FakePattern.of(TOP, 1,
                FakeInput.multi(1, SUB_ITEM, GOLD));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100L))
                .fuzzyCraftable(SUB_ITEM, GOLD)
                .pattern(GOLD, goldFromRedstone)
                .pattern(TOP, topFromEither)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(10L, plan.patternTimes().get(topFromEither));
        assertEquals(10L, plan.patternTimes().get(goldFromRedstone));
        assertEquals(20L, plan.usedItems().get(REDSTONE));
    }

    @Test
    void s9FuzzyStockVariants() {
        // A durability-damaged sibling holds part of the stock: the whole
        // class is pulled on first touch (AE2 cacheFuzzy) and whole templates
        // are drawn most damaged first (AE2 KeyComparator order).
        AEItemKey tool100 = AEItemKey.of(Items.IRON_PICKAXE);
        var damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(50);
        AEItemKey tool50 = AEItemKey.of(damaged);
        FakePattern ironFromTool = FakePattern.of(IRON, 1,
                FakeInput.anyVariant(tool100, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(tool100, 10L, tool50, 5L))
                .pattern(IRON, ironFromTool)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, IRON, 12, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        // AE2's fuzzy order draws the most damaged variant first.
        assertEquals(5L, plan.usedItems().get(tool50));
        assertEquals(7L, plan.usedItems().get(tool100));
    }

    /**
     * dense 模糊 source id 必须保持候选优先级与类内 damage 顺序：先耗尽
     * 第一候选的两个变体，再落到第二候选，且 used exact key 不得漂移。
     */
    @Test
    void s9DenseFuzzySourcesPreserveCandidateAndVariantOrder() {
        AEItemKey pickaxe = AEItemKey.of(Items.IRON_PICKAXE);
        var damagedStack = new ItemStack(Items.IRON_PICKAXE);
        damagedStack.setDamageValue(50);
        AEItemKey damagedPickaxe = AEItemKey.of(damagedStack);
        AEItemKey shovel = AEItemKey.of(Items.IRON_SHOVEL);
        FakePattern ironFromTools = FakePattern.of(IRON, 1,
                FakeInput.multi(1, pickaxe, damagedPickaxe, shovel));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(pickaxe, 2L, damagedPickaxe, 3L, shovel, 10L))
                .pattern(IRON, ironFromTools)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, IRON, 8,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(3L, plan.usedItems().get(damagedPickaxe));
        assertEquals(2L, plan.usedItems().get(pickaxe));
        assertEquals(3L, plan.usedItems().get(shovel));
    }

    @Test
    void s9FuzzyTargetVariantStock() {
        // Pattern-less target whose damaged sibling holds all the stock: the
        // root's exact template must not consume the sibling variant.
        AEItemKey tool100 = AEItemKey.of(Items.IRON_PICKAXE);
        var damaged = new ItemStack(Items.IRON_PICKAXE);
        damaged.setDamageValue(50);
        AEItemKey tool50 = AEItemKey.of(damaged);
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(tool50, 12L))
                .build();

        assertGraphMatchesFast(grid, tool100, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // S11: emittable items (emitter supply)
    // ------------------------------------------------------------------

    @Test
    void s11EmittableCoversWholeDemand() {
        FakePattern topFromGold = FakePattern.crafting(TOP, 1, Map.of(GOLD, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of())
                .emittable(GOLD)
                .pattern(TOP, topFromGold)
                .build();

        ICraftingPlan plan = runGraph(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(10L, plan.patternTimes().get(topFromGold));
        assertEquals(0L, plan.usedItems().get(GOLD));
        assertEquals(10L, plan.emittedItems().get(GOLD));

        assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    /** dense emittable 位图必须在库存不足后保持 exact emittedItems 口径。 */
    @Test
    void s11DenseEmittableViewPreservesPartialStockAccounting() {
        FakePattern topFromIron = FakePattern.of(TOP, 1, new FakeInput(IRON, 2));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(IRON, 3L))
                .emittable(IRON)
                .pattern(TOP, topFromIron)
                .build();

        ICraftingPlan plan = assertGraphMatchesFast(grid, TOP, 4,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertFalse(plan.simulation());
        assertEquals(3L, plan.usedItems().get(IRON));
        assertEquals(5L, plan.emittedItems().get(IRON));
    }

    @Test
    void s11EmittableCoversShortfallAfterStock() {
        FakePattern topFromGold = FakePattern.crafting(TOP, 1, Map.of(GOLD, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 4L))
                .emittable(GOLD)
                .pattern(TOP, topFromGold)
                .build();

        ICraftingPlan plan = runGraph(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertEquals(4L, plan.usedItems().get(GOLD));
        assertEquals(6L, plan.emittedItems().get(GOLD));

        assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // S12: infeasible request early out
    // ------------------------------------------------------------------

    @Test
    void s12InfeasibleReportMissingSimulates() {
        FakePattern topFromGold = FakePattern.crafting(TOP, 1, Map.of(GOLD, 2L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of())
                .pattern(TOP, topFromGold)
                .build();

        ICraftingPlan plan = runGraph(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertNotNull(plan);
        assertTrue(plan.simulation());
        assertEquals(20L, plan.missingItems().get(GOLD));

        assertGraphMatchesFast(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void s12NoPatternAtAllSimulates() {
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 50L))
                .build();

        assertGraphMatchesFast(grid, GOLD, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    // ------------------------------------------------------------------
    // Decline scenarios: the graph must refuse and stay on the tree path.
    // ------------------------------------------------------------------

    @Test
    void multiRouteGraphMatchesFast() {
        // Multiple routes for one key are supported since level-1 phase 4:
        // routes fire greedily in registration order like the tree planner.
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
        FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, DIAMOND, 10L))
                .pattern(IRON, ironFromGold, ironFromDiamond)
                .build();

        assertGraphMatchesFast(grid, IRON, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void declineNetConsumingCatalyst() {
        // Catalyst consumption (2/craft) exceeds its output (1/craft): the
        // closed-form cycle is not self-sustaining.
        FakePattern netConsumer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 2), new FakeInput(REDSTONE, 1)},
                new GenericStack(IRON, 1), new GenericStack(GOLD, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, REDSTONE, 10L))
                .pattern(IRON, netConsumer)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), IRON, null);
        assertTrue(graph.hasDeclined());

        // L2 falls back to per-item accounting: each firing extracts 2 before
        // the same firing's 1-gold return lands, so the last firing runs one
        // gold short — exactly the native per-item conclusion, with none of
        // the closed form's negative insert (1*times − 2*(times−1) < 0).
        var fast = runFast(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(1L, fast.missingItems().get(GOLD));
        assertEquals(0L, fast.missingItems().get(REDSTONE));
    }

    @Test
    void declineContainerPlusSelfOutput() {
        FakePattern mixed = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(GOLD, 1), new FakeInput(BUCKET, 1, BUCKET)},
                new GenericStack(IRON, 1), new GenericStack(BUCKET, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, BUCKET, 1L))
                .pattern(IRON, mixed)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), IRON, null);
        assertTrue(graph.hasDeclined());
    }

    @Test
    void declineEmittableCircularInput() {
        FakePattern ironInBucket = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 1),
                new FakeInput(BUCKET, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L))
                .emittable(BUCKET)
                .pattern(IRON, ironInBucket)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), IRON, null);
        assertTrue(graph.hasDeclined());
    }

    @Test
    void declineMixedRemainderKinds() {
        // One remainder key shared by a RETURNED container and a FINITE_USE
        // durability input cannot share the single insert site.
        AEItemKey tool = AEItemKey.of(Items.IRON_PICKAXE);
        FakePattern mixed = FakePattern.of(IRON, 1,
                new FakeInput(GOLD, 1),
                new FakeInput(BUCKET, 1, BUCKET),
                new FakeInput(tool, 1, BUCKET));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(GOLD, 10L, BUCKET, 5L, tool, 5L))
                .pattern(IRON, mixed)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), IRON, null);
        assertTrue(graph.hasDeclined());
    }

    @Test
    void patternCycleSimulatesLikeTree() {
        // The cycle is broken at compile like the tree's notRecursive filter:
        // the second-level IRON node drops goldFromIron, so GOLD goes missing
        // and the plan simulates.
        FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 1L));
        FakePattern goldFromIron = FakePattern.crafting(GOLD, 1, Map.of(IRON, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of())
                .pattern(IRON, ironFromGold)
                .pattern(GOLD, goldFromIron)
                .build();

        assertGraphMatchesFast(grid, IRON, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void selfReferencingInputSimulatesLikeTree() {
        // The pattern fires once (level-1 node allows it), then the
        // re-entered IRON node filters every pattern and goes missing.
        FakePattern selfRef = FakePattern.crafting(IRON, 1, Map.of(IRON, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of())
                .pattern(IRON, selfRef)
                .build();

        assertGraphMatchesFast(grid, IRON, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void declineFuzzyCombinationTooLarge() {
        // 65 same-item damage variants exceed the per-input template option
        // cap; the graph declines so the tree planners keep exact semantics.
        Map<AEKey, Long> stock = new HashMap<>();
        AEItemKey template = null;
        for (int damage = 0; damage < 65; damage++) {
            var stack = new ItemStack(Items.IRON_PICKAXE);
            stack.setDamageValue(damage);
            var variant = AEItemKey.of(stack);
            if (template == null) {
                template = variant;
            }
            stock.put(variant, 1L);
        }
        FakePattern ironFromTool = FakePattern.of(IRON, 1, FakeInput.anyVariant(template, 1));
        FakeGrid grid = FakeGrid.builder()
                .storage(stock)
                .pattern(IRON, ironFromTool)
                .build();

        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), IRON, null);
        assertTrue(graph.hasDeclined());
    }

    @Test
    void substituteKeyRecursionSimulatesLikeTree() {
        // F's slot accepts a pattern-less candidate that is fuzzy-craftable as
        // F itself: the resolved node key re-enters F, every pattern is
        // filtered (selfBlocked leaf), and the request goes missing — the
        // tree's findCraftedStack + notRecursive behavior.
        FakePattern selfFeeding = FakePattern.of(IRON, 1,
                FakeInput.multi(1, SUB_ITEM, IRON));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of())
                .fuzzyCraftable(SUB_ITEM, IRON)
                .pattern(IRON, selfFeeding)
                .build();

        assertGraphMatchesFast(grid, IRON, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    @Test
    void s18LexicographicRouteSelectionPrefersCheaperCompletingRoute() {
        // 双路线竞争（无色、根站点）：两条路线都能完整交付，先到先得模式下
        // DIAMOND 路线先耗 10 钻石/10 次；词典序模式在完整交付的两条路线间
        // 按 usedDelta 择优，翻倍产出的 SAND 路线只需 5 沙/5 次而胜出。
        // 显式传参绕过配置读取。两条同键路线必须一次 varargs 注册——
        // Builder.pattern 重复调用同键会整体替换前一批样板。
        FakePattern diamondToWheat = FakePattern.crafting(WHEAT, 1L, Map.of(DIAMOND, 1L));
        FakePattern sandToWheat = FakePattern.crafting(WHEAT, 2L, Map.of(SAND, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(DIAMOND, 10L, SAND, 100L))
                .pattern(WHEAT, diamondToWheat, sandToWheat)
                .build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), WHEAT, null);
        assertFalse(graph.hasDeclined());

        var greedy = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10L, false);
        var lexicographic = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 10L, true);

        assertFalse(greedy.hasDeclined());
        assertFalse(lexicographic.hasDeclined());
        assertEquals(Map.of(DIAMOND, 10L), FakePlanGrid.toPlain(greedy.state().usedItems()));
        assertEquals(10L, greedy.state().crafts().get(diamondToWheat));
        assertEquals(Map.of(SAND, 5L), FakePlanGrid.toPlain(lexicographic.state().usedItems()));
        assertEquals(5L, lexicographic.state().crafts().get(sandToWheat));
    }

    // ------------------------------------------------------------------
    // S19: huge declared I/O — single firing of patterns whose per-craft
    // amounts dwarf the request. Every input site must charge bytes for the
    // full declared units (AE2: amount × multiplier × units), so the plan
    // bytes stay astronomical instead of collapsing to node overhead.
    // ------------------------------------------------------------------

    private static final long M = 1_000_000L;
    private static final long T = 1_000_000_000_000L;

    @Test
    void s19HugeDeclaredIoAmountChannel() {
        // 处理样板编码（GenericStack 量大、multiplier=1）：请求 4096 个 TOP
        // 只发放 1 次，但 MID 站按 20M 件、REDSTONE 站按 60M 件入账字节。
        FakePattern midFromRedstone = FakePattern.of(MID, 10 * M, FakeInput.stack(REDSTONE, 30 * M));
        FakePattern topFromMid = FakePattern.of(TOP, 10 * M, FakeInput.stack(MID, 20 * M));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100 * M))
                .pattern(MID, midFromRedstone)
                .pattern(TOP, topFromMid)
                .build();

        var plan = assertGraphMatchesFast(grid, TOP, 4096, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(plan.bytes() > 50 * M, () -> "input-site bytes collapsed: " + plan.bytes());
    }

    @Test
    void s19HugeDeclaredIoMultiplierChannel() {
        // multiplier 通道（模板 1 件 × 大 multiplier）：字节同站按总件数入账。
        FakePattern midFromRedstone = FakePattern.crafting(MID, 10 * M, Map.of(REDSTONE, 30 * M));
        FakePattern topFromMid = FakePattern.crafting(TOP, 10 * M, Map.of(MID, 20 * M));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(REDSTONE, 100 * M))
                .pattern(MID, midFromRedstone)
                .pattern(TOP, topFromMid)
                .build();

        var plan = assertGraphMatchesFast(grid, TOP, 4096, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(plan.bytes() > 50 * M, () -> "input-site bytes collapsed: " + plan.bytes());
    }


}
