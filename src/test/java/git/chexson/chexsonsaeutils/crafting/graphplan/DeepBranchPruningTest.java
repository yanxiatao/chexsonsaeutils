package git.chexson.chexsonsaeutils.crafting.graphplan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 深层确定链剪枝：逐层取整、中间库存及不适用结构保留原计划语义。 */
class DeepBranchPruningTest {
    private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey A = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey B = AEItemKey.of(Items.EMERALD);
    private static final AEItemKey RAW = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey ALTERNATIVE = AEItemKey.of(Items.DIAMOND);

    @Test
    void laterStockOnlyInputPrunesAfterExpensiveFirstInputAndKeepsSharedCompetition() {
        for (boolean shared : new boolean[] {false, true}) {
            var side = shared ? RAW : AEItemKey.of(Items.COAL);
            var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(TARGET, 1)), new FakeInput(A, 1),
                    FakeInput.stack(side, 3)).withPriority(10);
            var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(TARGET, 1)), new FakeInput(ALTERNATIVE, 1));
            for (long stock : new long[] {0, 7, 31}) {
                var storage = new java.util.HashMap<appeng.api.stacks.AEKey, Long>();
                storage.put(RAW, 31L);
                storage.put(side, stock);
                storage.put(ALTERNATIVE, 100L);
                var grid = FakeGrid.builder().storage(storage).pattern(TARGET, preferred, alternative)
                        .pattern(A, FakePattern.of(A, 1, new FakeInput(B, 1)))
                        .pattern(B, FakePattern.of(B, 1, new FakeInput(RAW, 1))).build();
                assertMatchesNative(grid, 97);
            }
        }
    }

    @Test
    void firstInputByproductCanFundLaterTerminal() {
        var side = AEItemKey.of(Items.COAL);
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1)), new FakeInput(A, 1),
                FakeInput.stack(side, 2)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1)), new FakeInput(ALTERNATIVE, 1));
        var producer = FakePattern.colored(0, AEItemKey.of(Items.MAP),
                List.of(new GenericStack(A, 1), new GenericStack(side, 2)), new FakeInput(RAW, 1));
        var grid = FakeGrid.builder().storage(Map.of(RAW, 7L, ALTERNATIVE, 100L))
                .pattern(TARGET, preferred, alternative).pattern(A, producer).build();
        assertMatchesNative(grid, 97);
    }

    @Test
    void multiInputPrefixUsesSideCapacityWithoutChangingRoundingOrSharedStock() {
        var side = AEItemKey.of(Items.COAL);
        for (long rawStock : new long[] {0, 7, 31}) {
            for (long sideStock : new long[] {0, 2, 19}) {
                var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                        List.of(new GenericStack(TARGET, 1)), FakeInput.stack(A, 2)).withPriority(10);
                var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                        List.of(new GenericStack(TARGET, 1)), new FakeInput(ALTERNATIVE, 1));
                var grid = FakeGrid.builder().storage(Map.of(RAW, rawStock, side, sideStock,
                                ALTERNATIVE, 100L))
                        .pattern(TARGET, preferred, alternative)
                        .pattern(A, FakePattern.of(A, 3, FakeInput.stack(B, 2), FakeInput.stack(side, 3)))
                        .pattern(B, FakePattern.of(B, 2, FakeInput.stack(RAW, 3), FakeInput.stack(side, 2)))
                        .build();
                assertMatchesNative(grid, 97);
            }
        }
    }

    @Test
    void laterInputByproductKeepsSupplyingOtherDependencies() {
        var side = AEItemKey.of(Items.COAL);
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1)), FakeInput.stack(A, 2)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1)), new FakeInput(ALTERNATIVE, 1));
        var sideProducer = FakePattern.colored(0, AEItemKey.of(Items.MAP),
                List.of(new GenericStack(side, 1), new GenericStack(B, 4)), new FakeInput(RAW, 1));
        var grid = FakeGrid.builder().storage(Map.of(RAW, 11L, B, 2L, ALTERNATIVE, 100L))
                .pattern(TARGET, preferred, alternative)
                .pattern(A, FakePattern.of(A, 3, FakeInput.stack(B, 2), new FakeInput(side, 1)))
                .pattern(B, FakePattern.of(B, 2, FakeInput.stack(RAW, 3)))
                .pattern(side, sideProducer).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 97);
        assertFalse(result.hasDeclined(), result::declineReason);
        var actual = Ae2PlanConverter.toPlan(result, graph);
        var expected = new Ae2TreePlanReference(grid, grid.requester,
                new GenericStack(TARGET, 97), CalculationStrategy.REPORT_MISSING_ITEMS).run();
        // 此交叉副产物夹具原有批次差异：原生树为 421，优化前图算法为 411。
        // 完整固定旧图的发数、用料和余料，避免把既有批次差异误记成本轮回归。
        assertEquals(421, expected.bytes());
        assertEquals(411, actual.bytes());
        assertEquals(expected.finalOutput(), actual.finalOutput());
        assertEquals(Map.of(preferred, 12L, alternative, 85L, sideProducer, 8L,
                graph.producersByOutput().get(A).getFirst().source(), 8L,
                graph.producersByOutput().get(B).getFirst().source(), 1L), actual.patternTimes());
        assertEquals(Map.of(RAW, 11L, B, 2L, ALTERNATIVE, 85L), FakePlanGrid.toPlain(actual.usedItems()));
        assertEquals(20L, result.state().extract(B, 100L, true));
        assertEquals(FakePlanGrid.toPlain(expected.missingItems()), FakePlanGrid.toPlain(actual.missingItems()));
        assertEquals(FakePlanGrid.toPlain(expected.emittedItems()), FakePlanGrid.toPlain(actual.emittedItems()));
        assertEquals(expected.simulation(), actual.simulation());
    }

    @Test
    void roundedChainAndIntermediateStockMatchNativeIncludingBytes() {
        for (long rawStock : new long[] {0L, 1L, 7L, 31L}) {
            for (long intermediateStock : new long[] {0L, 2L}) {
                var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                        List.of(new GenericStack(TARGET, 1L)), FakeInput.stack(A, 2L)).withPriority(10);
                var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                        List.of(new GenericStack(TARGET, 1L)), new FakeInput(ALTERNATIVE, 1L));
                var grid = FakeGrid.builder().storage(Map.of(RAW, rawStock, A, intermediateStock,
                                B, 1L, ALTERNATIVE, 100L))
                        .pattern(TARGET, preferred, alternative)
                        .pattern(A, FakePattern.of(A, 3L, FakeInput.stack(B, 2L)))
                        .pattern(B, FakePattern.of(B, 2L, FakeInput.stack(RAW, 3L)))
                        .build();
                assertMatchesNative(grid, 97L);
            }
        }
    }

    @Test
    void oddIntermediateStockKeepsExistingWholeTemplateAccounting() {
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1L)), FakeInput.stack(A, 2L)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(ALTERNATIVE, 1L));
        var aPattern = FakePattern.of(A, 3L, FakeInput.stack(B, 2L));
        var bPattern = FakePattern.of(B, 2L, FakeInput.stack(RAW, 3L));
        var grid = FakeGrid.builder().storage(Map.of(RAW, 7L, A, 9L, B, 1L, ALTERNATIVE, 100L))
                .pattern(TARGET, preferred, alternative).pattern(A, aPattern).pattern(B, bPattern).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 97L);
        assertFalse(result.hasDeclined(), result::declineReason);
        var actual = Ae2PlanConverter.toPlan(result, graph);
        var nativePlan = new Ae2TreePlanReference(grid, grid.requester,
                new GenericStack(TARGET, 97L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
        // 优化前已有差异：图只记实际抽走的整模板库存 8，原生树记 9。
        assertEquals(9L, nativePlan.usedItems().get(A));
        assertEquals(Map.of(A, 8L, RAW, 6L, ALTERNATIVE, 90L), FakePlanGrid.toPlain(actual.usedItems()));
        assertEquals(Map.of(preferred, 7L, alternative, 90L, aPattern, 2L, bPattern, 2L), actual.patternTimes());
        assertEquals(nativePlan.finalOutput(), actual.finalOutput());
        assertEquals(nativePlan.bytes(), actual.bytes());
        assertFalse(actual.simulation());
        assertEquals(Map.of(), FakePlanGrid.toPlain(actual.missingItems()));
    }

    @Test
    void branchingInsideDependencyChainKeepsBothRoutesAvailable() {
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(A, 1L)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(ALTERNATIVE, 1L));
        var aFromMissing = FakePattern.colored(0, AEItemKey.of(Items.MAP),
                List.of(new GenericStack(A, 1L)), new FakeInput(RAW, 1L)).withPriority(10);
        var aFromStock = FakePattern.colored(0, AEItemKey.of(Items.CLOCK),
                List.of(new GenericStack(A, 1L)), new FakeInput(B, 1L));
        var grid = FakeGrid.builder().storage(Map.of(B, 7L, ALTERNATIVE, 100L))
                .pattern(TARGET, preferred, alternative).pattern(A, aFromMissing, aFromStock).build();
        assertMatchesNative(grid, 97L);
    }

    @Test
    void wideLeafSupplyAndHugeTemplateKeepExactUsedQuantity() {
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(A, 1L)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new GenericStack(TARGET, 1L)), new FakeInput(ALTERNATIVE, 1L));
        var grid = FakeGrid.builder().storage(Map.of(RAW, Long.MAX_VALUE, ALTERNATIVE, 1_000_000L))
                .pattern(TARGET, preferred, alternative)
                .pattern(A, FakePattern.of(A, 1L, FakeInput.stack(B, Long.MAX_VALUE)))
                .pattern(B, FakePattern.of(B, 1L, new FakeInput(RAW, 2L))).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        graph.attachSupplyTruth(git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(Map.of(
                RAW, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(
                        new java.math.BigInteger("30000000000000000000")))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1_000_000L);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(1_000_000L, result.finalAmount());
        assertEquals(1L, (long) result.state().crafts().get(preferred));
        assertEquals(999_999L, (long) result.state().crafts().get(alternative));
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.TWO),
                result.state().used().getExact(RAW));
    }

    private static void assertMatchesNative(FakeGrid grid, long amount) {
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        assertEquals(2, graph.producersByOutput().get(TARGET).size());
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, amount);
        assertFalse(result.hasDeclined(), result::declineReason);
        var actual = Ae2PlanConverter.toPlan(result, graph);
        var expected = new Ae2TreePlanReference(grid, grid.requester,
                new GenericStack(TARGET, amount), CalculationStrategy.REPORT_MISSING_ITEMS).run();
        FakePlanGrid.assertPlansEqual(expected, actual);
    }
}
