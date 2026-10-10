package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OrderedPlanningRecursiveCapabilityTest {
    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey A = AEItemKey.of(Items.STICK);
    private static final AEItemKey B = AEItemKey.of(Items.COAL);
    private static final AEItemKey LEAF = AEItemKey.of(Items.REDSTONE);

    @Test
    void everyConfiguredAlgorithmUsesSharedRecursiveKernel() {
        var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L, LEAF, 32L))
                .pattern(A, second)
                .pattern(B, first)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlans(grid, consumer, first, second);
    }

    @Test
    void retentionOnlyReseedsCatalystsExtractedFromNetwork() {
        var first = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var second = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L, LEAF, 32L))
                .pattern(A, second)
                .pattern(B, first)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(B, 1L);
        stock.add(LEAF, 32L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        var graph = kernel.session().graph();
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);

        assertFalse(result.hasDeclined(), result::declineReason);
        assertEquals(0L, result.state().usedAmount(A), "A must be supplied by the ring, not network stock");
        assertTrue(result.state().usedAmount(B) > 0L, "B is the actual network seed");
        assertTrue(result.state().ringExtractions().getLong(B) > 0L);
        assertEquals(0L, result.state().ringExtractions().getLong(A));
        assertNull(RetentionApplier.apply(result.state(), graph, result.finalAmount()));
        assertEquals(0L, result.state().ringExtractions().getLong(A));
        RecursivePlanContract contract = assertInstanceOf(RecursivePlanContract.class,
                Ae2PlanConverter.toPlan(result, graph));
        assertTrue(contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B) > 0L);
        assertEquals(0L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
        assertTrue(contract.chexsonsaeutils$dyeableRecursiveInternalItems().get(B) > 0L);
    }

    @Test
    void retentionPreservesCompleteMultiSeedStartupSet() {
        var intermediate = AEItemKey.of(Items.IRON_INGOT);
        var output = AEItemKey.of(Items.DIAMOND);
        var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(intermediate, 1L)),
                new FakeInput(A, 1L), new FakeInput(B, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                        new GenericStack(output, 1L)),
                new FakeInput(intermediate, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, B, 1L))
                .pattern(A, recycler)
                .pattern(B, recycler)
                .pattern(intermediate, starter)
                .pattern(output, recycler)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 1L);
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        var graph = kernel.session().graph();
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);

        assertFalse(result.hasDeclined(), result::declineReason);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertEquals(1L, result.state().ringExtractions().getLong(A));
        assertEquals(1L, result.state().ringExtractions().getLong(B));
        assertEquals(0L, result.state().ringExtractions().getLong(intermediate));
        assertNull(RetentionApplier.apply(result.state(), graph, result.finalAmount()));
        RecursivePlanContract directContract = assertInstanceOf(RecursivePlanContract.class,
                Ae2PlanConverter.toPlan(result, graph));
        assertEquals(1L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
        assertEquals(1L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B));
        assertEquals(0L, directContract.chexsonsaeutils$dyeableRecursiveInitialItems().get(intermediate));

        forEachAlgorithmPlan(grid, plan -> {
            RecursivePlanContract contract = assertInstanceOf(RecursivePlanContract.class, plan);
            assertEquals(1L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(A));
            assertEquals(1L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(B));
            assertEquals(0L, contract.chexsonsaeutils$dyeableRecursiveInitialItems().get(intermediate));
            assertEquals(8L, plan.patternTimes().get(consumer));
            assertTrue(plan.patternTimes().getOrDefault(starter, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(recycler, 0L) >= 8L);
        });
    }

    /**
     * 留种口径（用户要求）：网络里本来有一颗种子，计划可以把它提取进 CPU 用掉，
     * 但作业跑完这颗种子必须回到网络——所以计划必须多算一发。
     *
     * <p>这里的主计划恰好只吃种子、一次环都不走（{@code ringExtractions} 为空），
     * 早期版本整段早退，种子被吃干且无缺口可报。
     */
    @Test
    void retentionRefiresRingWhenPlanEatsTheOnlySeed() {
        var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var doubling = FakePattern.of(template, 2L, new FakeInput(template, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(template, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(template, 1L))
                .pattern(template, doubling)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(template, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "eating the only seed must still yield a plan");
        assertTrue(plan.missingItems().isEmpty(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
        assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 1L,
                "吃到唯一种子必须多算一发把种子还回网络, patterns=" + plan.patternTimes());
        // 多算那一发的剩余份留在 CPU 池子里，作业结束时由 storeItems 倒回网络；
        // 验收口径是计划全部发射跑完后网络投影仍留有种子（投影按整轮取整，可能多留）。
        assertTrue(projectedSeed(plan, template, 1L) >= 1L,
                "作业后网络必须仍留有 1 颗种子, patterns=" + plan.patternTimes());
    }

    /**
     * 做种那一发是<b>规划里的一发</b>：网络里缺的那枚原料如实报缺料，计划照出，
     * 而不是事后补种补不动、或者干脆拒掉整条递归规划。
     *
     * <p>实机形态是 {@code ae2:creative_storage_cell x 1}：留种要跑锻造模板环一发，
     * 环净吃一枚环外料（夹具用煤炭占这个位置），而网络里压根没有它。旧口径先解完再补，
     * 补种排不动就 {@code decline=retention net input ... has no reusable plan path}
     * → recursive 与 NATIVE 双双拒规划 → {@code Failed to start crafting job}。
     */
    @Test
    void unsourcableSeedMaterialIsPlannedInAndReportedMissing() {
        var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var doubling = FakePattern.of(template, 2L,
                new FakeInput(template, 1L), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(template, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(template, 1L))
                .pattern(template, doubling)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(template, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "原料不齐也要有计划可交");
        // 两发环：一发交付，一发还种，各吃一枚 B，网络里没有 ⇒ 如实缺 2 枚。
        assertEquals(2L, plan.missingItems().get(B),
                () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
        // 那一发是规划自己排出来的（种子不可花 → 只能造），不是解完之后附加的。
        assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 1L,
                () -> "times=" + plan.patternTimes());
    }

    /**
     * 实机形态：网络里的原料刚好够主计划吃光。留种生效的那条规则是<b>那颗种不是可花库存</b>，
     * 于是主计划自己得把补种那一发排出来，原料在同一次闭式解里竞争。
     *
     * <p>旧口径是解完再补账：沙盒已被主计划扣干（{@code plannedUsed == network}），
     * 补种排不动 → 报缺料甚至拒规划。夹具用煤炭占钻石的位置：库存 3 枚全被主链吃掉，
     * 环那一发还要 1 枚。
     */
    @Test
    void seedMaterialsAreReservedBeforeTheMainPlanSpendsThem() {
        var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var coal = AEItemKey.of(Items.COAL);
        var doubling = FakePattern.of(template, 2L,
                new FakeInput(template, 1L), new FakeInput(coal, 1L));
        var consumer = FakePattern.of(TOP, 1L,
                new FakeInput(template, 1L), new FakeInput(coal, 3L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(template, 1L, coal, 3L))
                .pattern(template, doubling)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(template, 1L);
        stock.add(coal, 3L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "做种占位之后仍要有计划可交");
        assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 1L,
                () -> "补种那一发必须在计划里, times=" + plan.patternTimes());
        // 三枚煤炭全被提进 CPU：主链一发 3 枚，还种那一发的份额排不到料，只能如实缺料。
        assertEquals(3L, plan.usedItems().get(coal),
                () -> "used=" + FakePlanGrid.toPlain(plan.usedItems()));
        assertTrue(projectedSeed(plan, template, 1L) >= 1L,
                "作业跑完网络里还得有一颗种子, patterns=" + plan.patternTimes());
    }

    /**
     * 实机形态（锻造模板环 1 出 2）：主链要吃 N 枚模板，网络里正好只有起步那 1 枚种。
     *
     * <p>环的起步预留会把那颗种重新塞回沙盒，于是计划按"N 枚消耗 = 环净产 N−1 + 种 1"排程，
     * 种被当成原料花掉 ⇒ {@code projected=0 SHORT}（16:58 实机：makes 2x63 / eats 1x63 + 1x64）。
     * 留种生效的口径是<b>要多接一发</b>：要 2 枚就按 3 枚规划，多那一发的原料在同一次闭式解里争。
     */
    @Test
    void ringStartupSeedIsRepaidByOneMoreCycle() {
        var template = AEItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var doubling = FakePattern.of(template, 2L,
                new FakeInput(template, 1L), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(template, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(template, 1L, B, 8L))
                .pattern(template, doubling)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(template, 1L);
        stock.add(B, 8L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 2L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "要还种也得有计划可交");
        // 吃 2 枚就得净产 2 枚：起步那枚种不算可花库存，环得多跑一发。
        assertTrue(plan.patternTimes().getOrDefault(doubling, 0L) >= 2L,
                () -> "times=" + plan.patternTimes());
        assertTrue(projectedSeed(plan, template, 1L) >= 1L,
                () -> "作业跑完网络里还得有 1 枚种, patterns=" + plan.patternTimes());
    }

    /**
     * 实机形态（544 任务那条 SHORT 的形状）：环的留种催化物同时是另一个净输出键的入口，
     * 网络里只有 1 枚种，而环外样板每发吃 2 枚。
     *
     * <p>改前红：起步预留把网络里那颗种插回沙盒后，主链的同键需求把它吃掉；即使发射向量
     * 按"实际借走 1 枚"多接了一发，那一发也被后续同键需求吸收 —— 判决行
     * {@code seed=1 projected=0 SHORT makes=[2x5] eats=[1x5, 2x3] graphStock=1}。
     * 留种成立需要两件事同时成立：起步预留不把种变成可花料（预留后立刻扣回可花账）＋
     * 还种轮数按实际借走量算、去重按环而不是按入口键。
     */
    @Test
    void ringSeedBorrowedByOtherEntryIsRepaidToNetwork() {
        var pring = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L), new GenericStack(LEAF, 3L)), new FakeInput(B, 1L));
        var outside = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 2L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L), new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L))
                .pattern(B, pring).pattern(LEAF, pring).pattern(A, outside)
                .pattern(TOP, consumer).build();
        var stock = new KeyCounter();
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 3L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);
        var graph = kernel.session().graph();
        assertEquals(1L, graph.retainedSeeds().getOrDefault(B, 0L), "种就是网络里那 1 枚催化物");
        assertTrue(graph.ringMacrosByEntry().size() >= 2, "同一环两个入口：" + graph.ringMacrosByEntry().keySet());

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "留种也得有计划可交");
        assertFalse(plan.simulation(), () -> "留种退化成模拟: missing="
                + FakePlanGrid.toPlain(plan.missingItems()));
        assertTrue(plan.missingItems().isEmpty(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
        assertTrue(projectedSeed(plan, B, 1L) >= 1L,
                () -> "作业跑完网络里还得有 1 枚种, patterns=" + plan.patternTimes());
        // 刚够交付是 5 发（净产 5 + 起步预留的 1 枚 = 环外要吃 6 枚），多接那一发才是留种。
        assertTrue(plan.patternTimes().getOrDefault(pring, 0L) >= 6L,
                () -> "还种那一发没排出来, times=" + plan.patternTimes());
    }

    /**
     * 同一环被两个入口取用的形状：入口键 ≠ 留种催化物键时规则不得退化成 no-op。
     *
     * <p>这条在改前就是绿的（守形状用，不是判别用例）：改前的判别红由
     * {@link #ringSeedBorrowedByOtherEntryIsRepaidToNetwork} 承担。
     */
    @Test
    void retainedSeedSurvivesWhenRingIsEnteredByBothKeys() {
        var pring = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L), new GenericStack(LEAF, 3L)), new FakeInput(B, 1L));
        var outside = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L), new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L))
                .pattern(B, pring).pattern(LEAF, pring).pattern(A, outside)
                .pattern(TOP, consumer).build();
        var stock = new KeyCounter();
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 3L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "双入口形状也要有计划可交");
        assertFalse(plan.simulation(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
        assertTrue(plan.missingItems().isEmpty(), () -> "missing=" + FakePlanGrid.toPlain(plan.missingItems()));
        assertTrue(plan.patternTimes().getOrDefault(pring, 0L) >= 3L,
                () -> "times=" + plan.patternTimes());
        assertTrue(projectedSeed(plan, B, 1L) >= 1L,
                () -> "作业跑完网络里还得有 1 枚种, patterns=" + plan.patternTimes());
    }

    /** stock + Σ(样板产出 − 样板输入)：计划跑完该键在网络侧的投影量。 */
    private static long projectedSeed(ICraftingPlan plan, AEKey seed, long networkStock) {
        long amount = networkStock;
        for (var entry : plan.patternTimes().entrySet()) {
            var details = entry.getKey();
            long times = entry.getValue();
            if (details == null || times <= 0L) {
                continue;
            }
            for (var output : details.getOutputs()) {
                if (output != null && seed.equals(output.what())) {
                    amount += output.amount() * times;
                }
            }
            for (var input : details.getInputs()) {
                var possible = input.getPossibleInputs();
                if (possible == null) {
                    continue;
                }
                for (var candidate : possible) {
                    if (candidate != null && seed.equals(candidate.what())) {
                        amount -= candidate.amount() * input.getMultiplier() * times;
                        break;
                    }
                }
            }
        }
        return amount;
    }

    @Test
    void everyConfiguredAlgorithmHandlesCrossPatternCatalyst() {
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, LEAF, 32L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlans(grid, consumer, producer, recycler);
    }

    @Test
    void everyConfiguredAlgorithmPreservesContractForFiniteUnsupportedCycle() {
        var forward = FakePattern.of(A, 1L, new FakeInput(B, 1L));
        var backEdge = FakePattern.of(B, 1L, new FakeInput(A, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(A, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1L))
                .pattern(A, forward)
                .pattern(B, backEdge)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        assertEquals(CraftGraph.CycleKind.UNSUPPORTED_CYCLE, kernel.session().graph().cycleKind());
        forEachAlgorithmPlan(grid, 1L, plan -> {
            assertEquals(1L, plan.finalOutput().amount());
            assertEquals(1L, plan.patternTimes().get(consumer));
            assertEquals(1L, plan.patternTimes().get(forward));
            assertEquals(0L, plan.patternTimes().getOrDefault(backEdge, 0L));
            assertEquals(1L, plan.usedItems().get(B));
        });
    }

    @Test
    void recursiveKernelRetriesWithoutExpiredOptimizationBudget() throws InterruptedException {
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L)),
                new FakeInput(A, 1L), new FakeInput(LEAF, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(B, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, LEAF, 32L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 1L);
        stock.add(LEAF, 32L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);

        var graph = kernel.session().graph();
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        assertFalse(graph.ringMacrosByEntry().getOrDefault(B, List.of()).isEmpty(),
                "recursive ring macro must be compiled for its entry key");
        Thread.sleep(20L);
        ICraftingPlan plan = kernel.solve();

        assertNotNull(plan, "expired optimization budget must not make recursive planning decline");
        assertFalse(plan.simulation(), () -> "fallback plan remained simulated: missing="
                + FakePlanGrid.toPlain(plan.missingItems()) + ", patterns=" + plan.patternTimes());
        assertInstanceOf(RecursivePlanContract.class, plan);
    }

    @Test
    void externallyDetectedRecursivePathRetriesWhenGraphClassificationIsNone() throws InterruptedException {
        var producer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1L))
                .pattern(TOP, producer)
                .build();
        var stock = new KeyCounter();
        stock.add(LEAF, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 1L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);

        assertEquals(CraftGraph.CycleKind.NONE, kernel.session().graph().cycleKind());
        Thread.sleep(20L);
        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "external recursive detection must force an exact retry");
        assertFalse(plan.simulation());
        assertTrue(plan.missingItems().isEmpty());
        assertEquals(1L, plan.patternTimes().get(producer));
    }

    @Test
    void everyConfiguredAlgorithmHandlesNonUniformRingRatio() {
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 3L)), new FakeInput(A, 2L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L), new GenericStack(LEAF, 1L)),
                new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 2L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(LEAF, recycler)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 2L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, kernel.session().graph().cycleKind());
        var leafMacros = kernel.session().graph().ringMacrosByEntry().getOrDefault(LEAF, List.of());
        assertFalse(leafMacros.isEmpty());
        assertEquals(1L, leafMacros.getFirst().executionRatio().get(producer));
        assertEquals(2L, leafMacros.getFirst().executionRatio().get(recycler));
        ICraftingPlan plan = kernel.solve();

        assertNotNull(plan, "non-uniform recursive ratio must have an internal exact plan");
        assertFalse(plan.simulation(), () -> "non-uniform ring remained simulated: missing="
                + FakePlanGrid.toPlain(plan.missingItems()) + ", patterns=" + plan.patternTimes());
        assertTrue(plan.missingItems().isEmpty());
        assertInstanceOf(RecursivePlanContract.class, plan);
        assertEquals(8L, plan.patternTimes().get(consumer));
        // 留种加成只看"实际从网络起步预留里借走了几枚留种催化物"：这里被扣的起步种是
        // 环中间产物（煤炭，netOutputs>0 才算留种对象），而网络里煤炭为 0 ⇒ 一枚都没借走
        // ⇒ 不加发（多算那一发既不还出任何东西，又抢主链的料）。所以发射量就是"刚好交付"
        // 那一版：每轮 producer 1 发 + recycler 2 发。真借走种的形状由
        // ringSeedBorrowedByRingEntryIsRepaidToNetwork 守着。
        assertEquals(9L, plan.patternTimes().get(producer));
        assertEquals(24L, plan.patternTimes().get(recycler));

        assertEveryAlgorithmPlans(grid, consumer, producer, recycler);
    }

    @Test
    void everyConfiguredAlgorithmHandlesNestedRings() {
        var inner = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(A, 2L), new GenericStack(LEAF, 1L)),
                new FakeInput(A, 1L));
        var outerSeed = AEItemKey.of(Items.IRON_INGOT);
        var outerIntermediate = AEItemKey.of(Items.GOLD_INGOT);
        var outerOutput = AEItemKey.of(Items.DIAMOND);
        var outerProducer = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(outerIntermediate, 2L)),
                new FakeInput(outerSeed, 1L), new FakeInput(LEAF, 1L));
        var outerRecycler = FakePattern.colored(3, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(outerSeed, 1L), new GenericStack(outerOutput, 1L)),
                new FakeInput(outerIntermediate, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(outerOutput, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, outerSeed, 1L, outerIntermediate, 1L))
                .pattern(A, inner)
                .pattern(LEAF, inner)
                .pattern(outerSeed, outerRecycler)
                .pattern(outerIntermediate, outerProducer)
                .pattern(outerOutput, outerRecycler)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlansNestedRings(
                grid, consumer, inner, outerProducer, outerRecycler);
    }

    @Test
    void everyConfiguredAlgorithmHandlesMultiOutputRing() {
        var firstOutput = AEItemKey.of(Items.IRON_INGOT);
        var secondOutput = AEItemKey.of(Items.GOLD_INGOT);
        var producer = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(B, 2L), new GenericStack(firstOutput, 1L),
                        new GenericStack(secondOutput, 1L)),
                new FakeInput(A, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var consumer = FakePattern.of(TOP, 1L,
                new FakeInput(firstOutput, 1L), new FakeInput(secondOutput, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, B, 1L))
                .pattern(A, recycler)
                .pattern(B, producer)
                .pattern(firstOutput, producer)
                .pattern(secondOutput, producer)
                .pattern(TOP, consumer)
                .build();

        assertEveryAlgorithmPlansMultiOutputRing(grid, consumer, producer, recycler);
    }

    private static void assertEveryAlgorithmPlansNestedRings(
            FakeGrid grid,
            FakePattern consumer,
            FakePattern inner,
            FakePattern outerProducer,
            FakePattern outerRecycler
    ) {
        forEachAlgorithmPlan(grid, plan -> {
            assertEquals(8L, plan.patternTimes().get(consumer));
            assertTrue(plan.patternTimes().getOrDefault(inner, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(outerProducer, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(outerRecycler, 0L) >= 8L);
        });
    }

    private static void assertEveryAlgorithmPlansMultiOutputRing(
            FakeGrid grid,
            FakePattern consumer,
            FakePattern producer,
            FakePattern recycler
    ) {
        forEachAlgorithmPlan(grid, plan -> {
            assertEquals(8L, plan.patternTimes().get(consumer));
            assertTrue(plan.patternTimes().getOrDefault(producer, 0L) >= 8L);
            assertTrue(plan.patternTimes().getOrDefault(recycler, 0L) >= 4L);
        });
    }

    private static void forEachAlgorithmPlan(
            FakeGrid grid,
            java.util.function.Consumer<ICraftingPlan> assertion
    ) {
        forEachAlgorithmPlan(grid, 8L, assertion);
    }

    private static void forEachAlgorithmPlan(
            FakeGrid grid,
            long amount,
            java.util.function.Consumer<ICraftingPlan> assertion
    ) {
        var service = PlanningAlgorithmOrderService.global();
        List<String> originalOrder = service.snapshot().order();
        try {
            for (PlanningAlgorithmId algorithm : PlanningAlgorithmId.values()) {
                moveFirst(algorithm);
                ICraftingPlan plan = new OrderedPlanningCalculation(
                        null, grid, grid.requester, new GenericStack(TOP, amount),
                        CalculationStrategy.REPORT_MISSING_ITEMS).run();
                assertFalse(plan.simulation(), algorithm + " must calculate the recursive rings");
                assertTrue(plan.missingItems().isEmpty(), algorithm + " must not report a false ring shortfall");
                assertInstanceOf(RecursivePlanContract.class, plan,
                        algorithm + " must preserve recursive metadata");
                assertion.accept(plan);
            }
        } finally {
            service.reload(originalOrder);
        }
    }

    private static void assertEveryAlgorithmPlans(
            FakeGrid grid,
            FakePattern consumer,
            FakePattern first,
            FakePattern second
    ) {
        var service = PlanningAlgorithmOrderService.global();
        List<String> originalOrder = service.snapshot().order();
        try {
            for (PlanningAlgorithmId algorithm : PlanningAlgorithmId.values()) {
                moveFirst(algorithm);
                ICraftingPlan plan = new OrderedPlanningCalculation(
                        null, grid, grid.requester, new GenericStack(TOP, 8L),
                        CalculationStrategy.REPORT_MISSING_ITEMS).run();
                assertFalse(plan.simulation(), algorithm + " must calculate the recursive ring");
                assertTrue(plan.missingItems().isEmpty(), algorithm + " must not report a false ring shortfall");
                assertInstanceOf(RecursivePlanContract.class, plan, algorithm + " must preserve recursive metadata");
                assertEquals(8L, plan.patternTimes().get(consumer));
                assertTrue(plan.patternTimes().getOrDefault(first, 0L) > 0L);
                assertTrue(plan.patternTimes().getOrDefault(second, 0L) > 0L);
            }
        } finally {
            service.reload(originalOrder);
        }
    }

    private static void moveFirst(PlanningAlgorithmId first) {
        var service = PlanningAlgorithmOrderService.global();
        // 服务侧的序现在是串名册：按枚举个名取名册形状，再把要置顶的那档挪到首位（顺位不许被 Set 打散）。
        var order = new ArrayList<>(java.util.Arrays.stream(PlanningAlgorithmId.values())
                .map(Enum::name)
                .toList());
        order.remove(first.name());
        order.addFirst(first.name());
        service.reload(order);
    }

    /**
     * 实机形态：请求量把某键需求顶过 long 上限（{@code ae2:creative_storage_cell x 1000000}，
     * {@code lava demand=1.6e19}）。旧行为是守恒闸门判"不闭合"→ 递归规划被拒 → 一路落到
     * NATIVE 也拒 → {@code IllegalStateException: Terminal planning algorithm declined: NATIVE}，
     * 连计划页面都进不去。用户口径的预期：<b>如实报缺料、计划照出</b>。
     */
    @Test
    void demandBeyondLongRangeReportsMissingInsteadOfDeclining() {
        var producer = FakePattern.of(TOP, 1L, new FakeInput(B, 3_000_000_000_000L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(B, 1000L))
                .pattern(TOP, producer)
                .build();
        var stock = new KeyCounter();
        stock.add(B, 1000L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 10_000_000L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 5000L, stock);

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, "需求超过 long 上限也要有计划可交");
        // 精确数字取决于哪一步先窄化，口径只看一件事：缺料按"装得下的最大量"如实报出来，
        // 而不是整条规划被拒。
        assertTrue(plan.missingItems().get(B) > Long.MAX_VALUE / 2,
                () -> "缺料该报到 long 天花板量级, missing=" + FakePlanGrid.toPlain(plan.missingItems()));
    }

    /**
     * 预算豁免这条腿（会话层 {@code BudgetPolicy.EXEMPT}）此前<b>零用例覆盖</b>：用例侧的预算
     * 一律传 0＝不计费，"环场景再解一趟免预算"这件事从来没被量到过。单求解器迁移把
     * {@code solveLedger} 收敛成直调 LinearPlanner，这一趟只是"顺带还在"，没有任何一条用例
     * 证明它还在——摘掉它的后果是实机默认 10 秒预算把环场景第二趟顶成 budgetExhausted ⇒
     * 落原生树 ⇒「递归样板卡死」那一族症状复现。
     *
     * <p>这里把会话层的两趟摆在一起量，两条前条件都自证：
     * <ul>
     * <li>先把共享预算真的耗干（轮询 {@code exhausted()}，不用固定 sleep），
     * {@code ENFORCED} 那一趟必须交回 {@code budgetExhausted}——顶住的位置必须是求解段；</li>
     * <li>{@code EXEMPT} 那一趟必须交出<b>精确解</b>：handled、不是 simulation、终产物量＝请求量、
     * 一个键都不缺。豁免一旦被顺手收掉，这一格立刻变成 budgetExhausted 而红。</li>
     * </ul>
     * 图用的是多种子起步那一型（{@code CycleKind.COMPRESSIBLE}），正是实机那一族症状的形状。
     */
    @Test
    void exhaustedBudgetStillYieldsExactRingPlanThroughTheExemptAttempt() {
        var intermediate = AEItemKey.of(Items.IRON_INGOT);
        var output = AEItemKey.of(Items.DIAMOND);
        var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(intermediate, 1L)),
                new FakeInput(A, 1L), new FakeInput(B, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                        new GenericStack(output, 1L)),
                new FakeInput(intermediate, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, B, 1L))
                .pattern(A, recycler)
                .pattern(B, recycler)
                .pattern(intermediate, starter)
                .pattern(output, recycler)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 1L);
        stock.add(B, 1L);
        var budget = new GraphPlanningBudget(1L);
        while (!budget.exhausted()) {
            Thread.onSpinWait();
        }
        var session = GraphPlanningSession.create(grid.crafting, stock, TOP,
                CalculationStrategy.REPORT_MISSING_ITEMS, budget, null, true, null, grid);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, session.graph().cycleKind(),
                "前条件：这张图确实成环，走的才是环场景那一趟");

        var enforced = session.solve(8L);
        assertFalse(enforced.handled(), "前条件：预算已耗尽，常规那一趟必须被顶住");
        assertEquals(GraphPlanningBudget.Phase.SOLVE, enforced.budgetExhaustedPhase(),
                () -> "顶住的位置必须是求解段, 实得=" + enforced.budgetExhaustedPhase());

        var exempt = session.solve(8L, GraphPlanningSession.BudgetPolicy.EXEMPT);
        assertTrue(exempt.handled(), () -> "免预算那一趟必须跑得完, 判决=" + exempt.declineReason());
        ICraftingPlan plan = exempt.plan();
        assertNotNull(plan);
        assertFalse(plan.simulation(), "交出的必须是精确解，不是模拟计划");
        assertEquals(8L, plan.finalOutput().amount(), "终产物量逐字等于请求量");
        assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(),
                () -> "一个键都不许缺: " + FakePlanGrid.toPlain(plan.missingItems()));
    }

    /**
     * 上面那条量的是<b>会话层</b>两趟，绕开了生产触发口：把 {@code RecursivePlanningKernel.solve}
     * 里那条免预算判定整条摘掉，门禁仍全绿——而玩家真正走的正是 kernel 那一趟
     * （{@code OrderedPlanningCalculation} 的 GRAPH 与 NATIVE 两档都经它）。
     *
     * <p>这条把入口换成 kernel：预算按生产口径交给 kernel（{@code budgetMillis=1L}，
     * 会话内共享），先把图编译出来（{@code session()}）再把墙钟耗干，然后调
     * {@code solveRecursive()}。前条件是真前条件：{@code planOnce} 进 SOLVE 段第一件事就是
     * {@code budget.check()}，墙钟只向前走，所以预算既已耗尽 ⇒ 计预算那一趟必然顶在 SOLVE 段
     * 交回 {@code budgetExhausted}；此时还能交出精确解的，只可能是免预算那一趟。
     * 摘掉那条判定，这里立刻变成 {@code plan == null}（落原生树＝「递归样板卡死」的形状）。
     */
    @Test
    void exhaustedBudgetStillYieldsExactRingPlanThroughTheProductionKernelEntry() {
        var intermediate = AEItemKey.of(Items.IRON_INGOT);
        var output = AEItemKey.of(Items.DIAMOND);
        var starter = FakePattern.colored(1, AEItemKey.of(Items.STONE),
                List.of(new GenericStack(intermediate, 1L)),
                new FakeInput(A, 1L), new FakeInput(B, 1L));
        var recycler = FakePattern.colored(2, AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(A, 2L), new GenericStack(B, 2L),
                        new GenericStack(output, 1L)),
                new FakeInput(intermediate, 1L));
        var consumer = FakePattern.of(TOP, 1L, new FakeInput(output, 1L));
        FakeGrid grid = FakeGrid.builder()
                .storage(Map.of(A, 1L, B, 1L))
                .pattern(A, recycler)
                .pattern(B, recycler)
                .pattern(intermediate, starter)
                .pattern(output, recycler)
                .pattern(TOP, consumer)
                .build();
        var stock = new KeyCounter();
        stock.add(A, 1L);
        stock.add(B, 1L);
        var kernel = new RecursivePlanningKernel(
                null, grid, grid.requester, new GenericStack(TOP, 8L),
                CalculationStrategy.REPORT_MISSING_ITEMS, 1L, stock);
        var session = kernel.session();
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, kernel.graphCycleKind(),
                "前条件：这张图确实成环，kernel 的环臂才可能触发免预算那一趟");
        while (!session.budget().exhausted()) {
            Thread.onSpinWait();
        }
        assertTrue(session.budget().exhausted(),
                "前条件：kernel 交给会话的共享预算必须已耗尽，计预算那一趟必然被顶住");

        ICraftingPlan plan = kernel.solveRecursive();

        assertNotNull(plan, () -> "环 + 预算耗尽必须仍由免预算那一趟交出计划, kernel 判决落空"
                + "（这正是落原生树、递归样板卡死的那一型）");
        assertFalse(plan.simulation(), "交出的必须是精确解，不是模拟计划");
        assertEquals(8L, plan.finalOutput().amount(), "终产物量逐字等于请求量");
        assertTrue(FakePlanGrid.toPlain(plan.missingItems()).isEmpty(),
                () -> "一个键都不许缺: " + FakePlanGrid.toPlain(plan.missingItems()));
    }
}
