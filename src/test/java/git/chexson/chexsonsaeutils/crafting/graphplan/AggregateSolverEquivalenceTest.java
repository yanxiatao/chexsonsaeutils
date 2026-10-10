package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitnessPlans;
import git.chexson.chexsonsaeutils.crafting.executionwitness.WitnessedCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.graphplan.aggregate.AggregateSolver;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合求解器与树式 {@link LinearPlanner} 的逐字段对拍。
 *
 * <p>聚合路径把「每个树位置各请求一次」压成「每个键各请求一次」，所以它必须在
 * 受理范围内给出与树路径完全相同的账本（bytes 含节点费）。不受理的图必须
 * DECLINE，让调用方回落树路径——本测试两侧都断言。
 */
final class AggregateSolverEquivalenceTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
    private static final AEItemKey LEAF = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey EMIT = AEItemKey.of(Items.REDSTONE);

    private static CraftGraph compile(FakeGrid grid, AEKey target) {
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected compile decline: " + graph.declineReason());
        return graph;
    }

    private static void assertSameLedger(FakeGrid grid, AEKey target, long amount) {
        var treeResult = LinearPlanner.solve(compile(grid, target),
                CalculationStrategy.REPORT_MISSING_ITEMS, amount);
        assertFalse(treeResult.hasDeclined(), () -> "tree declined: " + treeResult.declineReason());

        var aggregateResult = AggregateSolver.solve(compile(grid, target),
                CalculationStrategy.REPORT_MISSING_ITEMS, amount, null);
        assertFalse(aggregateResult.hasDeclined(),
                () -> "aggregate declined: " + aggregateResult.declineReason());

        var tree = treeResult.state();
        var aggregate = aggregateResult.state();
        assertNotNull(tree);
        assertNotNull(aggregate);
        assertEquals(treeResult.simulation(), aggregateResult.simulation(), "simulation");
        assertEquals(plain(tree.usedItems()), plain(aggregate.usedItems()), "usedItems");
        assertEquals(plain(tree.missingItems()), plain(aggregate.missingItems()), "missingItems");
        assertEquals(plain(tree.emittedItems()), plain(aggregate.emittedItems()), "emittedItems");
        assertEquals(craftTimes(tree), craftTimes(aggregate), "patternTimes");
        assertEquals(tree.bytes(), aggregate.bytes(), "bytes");
    }

    private static Map<AEKey, Long> plain(KeyCounter counter) {
        var map = new HashMap<AEKey, Long>();
        for (var entry : counter) {
            map.put(entry.getKey(), entry.getLongValue());
        }
        return map;
    }

    private static Map<String, Long> craftTimes(GraphPlanningState state) {
        var map = new HashMap<String, Long>();
        for (var entry : state.crafts().entrySet()) {
            map.merge(String.valueOf(entry.getKey().getDefinition()), entry.getValue(), Long::sum);
        }
        return map;
    }

    @Test
    void exactOnlyAggregatePlanDoesNotCarryWitness() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 6L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L)))
                .build();
        CraftGraph graph = compile(grid, TOP);
        var result = AggregateSolver.solve(graph,
                CalculationStrategy.REPORT_MISSING_ITEMS, 6L, null);

        assertFalse(result.hasDeclined());
        assertEquals(null, result.witness());
        assertFalse(Ae2PlanConverter.toPlan(result, graph) instanceof WitnessedCraftingPlan);
    }

    @Test
    void aggregateWitnessRecordsExactSpillAllocation() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 100L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.multi(1L, LEAF, OTHER)))
                .build();
        CraftGraph graph = compile(grid, TOP);
        var result = AggregateSolver.solve(graph,
                CalculationStrategy.REPORT_MISSING_ITEMS, 6L, null);

        assertFalse(result.hasDeclined());
        PlanningInputWitness witness = result.witness();
        assertNotNull(witness);
        assertTrue(witness.complete());
        assertEquals(Map.of(AEItemKey.of(Items.PAPER), 6L), witness.expectedCrafts());
        var runs = witness.patternInputs().get(0).runs();
        assertEquals(2L, runs.stream()
                .filter(run -> run.exactKey().equals(LEAF))
                .mapToLong(PlanningInputWitness.InputRun::templateUnitCount).sum());
        assertEquals(4L, runs.stream()
                .filter(run -> run.exactKey().equals(OTHER))
                .mapToLong(PlanningInputWitness.InputRun::templateUnitCount).sum());

        var converted = Ae2PlanConverter.toPlan(result, graph);
        assertTrue(converted instanceof WitnessedCraftingPlan);
        assertEquals(witness, PlanningInputWitnessPlans.find(converted));
    }

    @Test
    void linearChainMatchesTree() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1000L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 2L)))
                .pattern(MID, FakePattern.of(MID, 3L, new FakeInput(LEAF, 4L)))
                .build();
        assertSameLedger(grid, TOP, 5L);
    }

    @Test
    void sharedDependencyMatchesTree() {
        // 同一个叶子被两个槽消费：树里是两个位置，聚合里是一个键
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 10_000L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 2L), new FakeInput(OTHER, 1L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 3L)))
                .pattern(OTHER, FakePattern.of(OTHER, 1L, new FakeInput(LEAF, 5L)))
                .build();
        assertSameLedger(grid, TOP, 7L);
    }

    @Test
    void emittableLeafMatchesTree() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 500L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 1L), new FakeInput(EMIT, 2L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 2L)))
                .emittable(EMIT)
                .build();
        assertSameLedger(grid, TOP, 4L);
    }

    @Test
    void missingLeafMatchesTree() {
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 5L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 1L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 4L)))
                .build();
        assertSameLedger(grid, TOP, 9L);
    }

    @Test
    void stockAtIntermediateKeyMatchesTree() {
        var grid = FakeGrid.builder()
                .storage(Map.of(MID, 6L, LEAF, 1000L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 2L)))
                .pattern(MID, FakePattern.of(MID, 1L, new FakeInput(LEAF, 3L)))
                .build();
        assertSameLedger(grid, TOP, 8L);
    }

    @Test
    void firstProducerRouteSufficesMatchesTree() {
        // 第一条路线够用时，两侧都只走第一条
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 100L, OTHER, 100L))
                .pattern(TOP,
                        FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L)),
                        FakePattern.of(TOP, 1L, new FakeInput(OTHER, 1L)))
                .build();
        assertSameLedger(grid, TOP, 3L);
    }

    @Test
    void shortfallOnMultiRouteGraphDeclines() {
        // 第一条路线不够时，树路径还能在两条路线之间拆分需求，聚合的一趟拓扑
        // 只能整键选一条——必须 DECLINE 交回树路径，绝不给出更差的计划
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 100L))
                .pattern(TOP,
                        FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L)),
                        FakePattern.of(TOP, 1L, new FakeInput(OTHER, 1L)))
                .build();
        var result = AggregateSolver.solve(compile(grid, TOP),
                CalculationStrategy.REPORT_MISSING_ITEMS, 6L, null);
        assertTrue(result.hasDeclined(), "shortfall on a multi-route graph must decline");
        assertTrue(result.declineReason().contains("multi-route"),
                () -> "unexpected reason: " + result.declineReason());
    }

    @Test
    void divergentExtractionShapesMatchTree() {
        // 同一个键被两个不同模板单位的槽消费：节点身份含提取口径，两侧应逐字段相同
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 1000L))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(MID, 1L), new FakeInput(OTHER, 1L)))
                .pattern(MID, FakePattern.of(MID, 1L, FakeInput.stack(LEAF, 3L)))
                .pattern(OTHER, FakePattern.of(OTHER, 1L, FakeInput.stack(LEAF, 5L)))
                .build();
        assertSameLedger(grid, TOP, 4L);
    }

    @Test
    void containerReturnMatchesTree() {
        // RETURNED：桶被借走又还回，净消耗为零。归还必须延迟到子节点提取之后落账，
        // 否则 used 峰值会因为归还先到而偏低。
        AEItemKey bucket = AEItemKey.of(Items.BUCKET);
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 100L, bucket, 4L))
                .pattern(TOP, FakePattern.of(TOP, 1L,
                        new FakeInput(LEAF, 2L),
                        new FakeInput(bucket, 1L, bucket)))
                .build();
        assertSameLedger(grid, TOP, 5L);
    }

    @Test
    void finiteUseRemainderMatchesTree() {
        // FINITE_USE：余料是另一个键，图里没有消费者
        AEItemKey tool = AEItemKey.of(Items.IRON_PICKAXE);
        AEItemKey worn = AEItemKey.of(Items.GOLDEN_PICKAXE);
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 100L, tool, 8L))
                .pattern(TOP, FakePattern.of(TOP, 1L,
                        new FakeInput(LEAF, 2L),
                        new FakeInput(tool, 1L, worn)))
                .build();
        assertSameLedger(grid, TOP, 4L);
    }

    @Test
    void multiCandidateInputMatchesTree() {
        // 候选 1 够用
        var plenty = FakeGrid.builder()
                .storage(Map.of(LEAF, 100L, OTHER, 100L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.multi(1L, LEAF, OTHER)))
                .build();
        assertSameLedger(plenty, TOP, 3L);

        // 候选 1 抽干后必须换到候选 2
        var spill = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 100L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.multi(1L, LEAF, OTHER)))
                .build();
        assertSameLedger(spill, TOP, 6L);

        // 两个候选都不够：缺口口径也要一致
        var starved = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 3L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.multi(1L, LEAF, OTHER)))
                .build();
        assertSameLedger(starved, TOP, 9L);
    }


}
