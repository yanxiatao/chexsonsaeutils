package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;

/** 大倍率确定链的宽批量结算：核对精确发数、整模板库存、缺料及回滚。 */
class WideLinearBatchTest {
    private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey INTERMEDIATE = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey RAW = AEItemKey.of(Items.REDSTONE);
    private static final AEItemKey ALTERNATIVE = AEItemKey.of(Items.DIAMOND);
    private static final long COST = 1_000_000_000_000L;
    private static final long ORDER = 100_000_000_000_000L;

    @Test
    void nonAlignedOutputRetainsPerRoundRemainders() {
        var target = pattern(TARGET, 1, input(INTERMEDIATE, Long.MAX_VALUE));
        var intermediate = pattern(INTERMEDIATE, 2, input(RAW, 1));
        var graph = customGraph(List.of(target, intermediate), Map.of(RAW, Long.MAX_VALUE));
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW, PlanAmount.of(BigInteger.TEN.pow(30)))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 5);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        var times = BigInteger.valueOf(Long.MAX_VALUE / 2 + 1).multiply(BigInteger.valueOf(5));
        assertEquals(times, result.state().craftsWide().getAmount(intermediate.source()).exactValue());
        assertEquals(times, result.state().used().getExact(RAW));
        assertEquals(5, result.state().extract(INTERMEDIATE, 100, true));
    }

    @Test
    void repeatedInputWithDifferentTemplatesRetainsChunkCompetition() {
        var target = pattern(TARGET, 1, input(INTERMEDIATE, Long.MAX_VALUE));
        var intermediate = pattern(INTERMEDIATE, Long.MAX_VALUE, input(RAW, 2), input(RAW, 3));
        var raw = pattern(RAW, 1, input(ALTERNATIVE, 1));
        var graph = customGraph(List.of(target, intermediate, raw), Map.of(RAW, 6L, ALTERNATIVE, 100L));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        // 合并两轮会改成库存用量 4、补产 6；保留原顺序应为库存用量 5、补产 5。
        assertEquals(BigInteger.valueOf(5), result.state().used().getExact(RAW));
        assertEquals(BigInteger.valueOf(5), result.state().craftsWide().getAmount(raw.source()).exactValue());
        assertEquals(1, result.state().extract(RAW, 100, true));
    }

    @Test
    void hundredTrillionConsumesExactIntermediateTemplatesAndPreservesRemainder() {
        var graph = graph(PlanAmount.of(BigInteger.TEN.pow(30)), PlanAmount.of(3 * COST + 17), false);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        var demand = BigInteger.valueOf(ORDER - 3).multiply(BigInteger.valueOf(COST));
        assertEquals(demand, result.state().craftsWide().getAmount(
                graph.producersByOutput().get(INTERMEDIATE).getFirst().source()).exactValue());
        assertEquals(demand.multiply(BigInteger.TWO), result.state().used().getExact(RAW));
        assertEquals(BigInteger.valueOf(3 * COST), result.state().used().getExact(INTERMEDIATE));
        assertEquals(17L, result.state().extract(INTERMEDIATE, 100, true));
        assertEquals(ORDER, result.finalAmount());
        assertTrue(result.state().journal() < 100, "日志条目不得随宽值分轮增长");
    }

    @Test
    void stockedProducerDoesNotWalkItsProductionRoute() {
        var supply = BigInteger.valueOf(ORDER).multiply(BigInteger.valueOf(COST));
        var graph = graph(PlanAmount.ZERO, PlanAmount.of(supply.add(BigInteger.valueOf(17))), false);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(supply, result.state().used().getExact(INTERMEDIATE));
        assertEquals(0, result.state().craftsWide().getAmount(
                graph.producersByOutput().get(INTERMEDIATE).getFirst().source()).signum());
        assertEquals(17L, result.state().extract(INTERMEDIATE, 100, true));
        assertTrue(result.state().journal() < 100);
    }

    @Test
    void missingLeafAndFailedWideRouteKeepExactLedgers() {
        var demand = BigInteger.valueOf(ORDER).multiply(BigInteger.valueOf(COST));
        var graph = graph(PlanAmount.ZERO, PlanAmount.ZERO, false);
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertTrue(result.simulation());
        assertEquals(demand.multiply(BigInteger.TWO), result.state().missing().getExact(RAW));
        graph = graph(PlanAmount.ZERO, PlanAmount.of(17), true);
        result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, ORDER);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(BigInteger.valueOf(ORDER), result.state().used().getExact(ALTERNATIVE));
        assertEquals(0, result.state().used().getAmount(RAW).signum());
        assertEquals(0, result.state().used().getAmount(INTERMEDIATE).signum());
        assertEquals(0, result.state().craftsWide().getAmount(
                graph.producersByOutput().get(INTERMEDIATE).getFirst().source()).signum());
        assertEquals(17L, result.state().extract(INTERMEDIATE, 100, true));
    }

    @Test
    @Tag("footprint")
    void largeQuantityPerformanceReadout() {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (long requested : new long[] {10_000_000_000L, 10_000_000_000_000L, ORDER}) {
            var graph = graph(PlanAmount.of(BigInteger.TEN.pow(30)), PlanAmount.ZERO, false);
            long[] elapsed = new long[9];
            long[] allocated = new long[9];
            int journal = 0;
            for (int sample = 0; sample < 9; sample++) {
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, requested);
                elapsed[sample] = System.nanoTime() - start;
                allocated[sample] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                assertFalse(result.hasDeclined(), result::declineReason);
                assertFalse(result.simulation());
                assertEquals(BigInteger.valueOf(requested).multiply(BigInteger.valueOf(COST * 2)),
                        result.state().used().getExact(RAW));
                journal = result.state().journal();
            }
            java.util.Arrays.sort(elapsed);
            java.util.Arrays.sort(allocated);
            System.out.printf("宽生产链 request=%d median-ns=%d median-B=%d journal=%d%n",
                    requested, elapsed[4], allocated[4], journal);
        }
    }

    private static CraftGraph graph(PlanAmount rawSupply, PlanAmount intermediateSupply, boolean alternative) {
        var source = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(TARGET, 1)), FakeInput.stack(INTERMEDIATE, COST)).withPriority(10);
        var target = new GraphPattern(source, TARGET, 1, List.of(new GenericStack(TARGET, 1)),
                List.of(input(INTERMEDIATE, COST)));
        var intermediate = new GraphPattern(FakePattern.of(INTERMEDIATE, 1, FakeInput.stack(RAW, 2)),
                INTERMEDIATE, 1, List.of(new GenericStack(INTERMEDIATE, 1)), List.of(input(RAW, 2)));
        var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
        producers.put(TARGET, List.of(target));
        producers.put(INTERMEDIATE, List.of(intermediate));
        var patterns = new java.util.LinkedHashMap<appeng.api.crafting.IPatternDetails, GraphPattern>();
        patterns.put(target.source(), target);
        patterns.put(intermediate.source(), intermediate);
        if (alternative) {
            var alt = new GraphPattern(FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(TARGET, 1)), new FakeInput(ALTERNATIVE, 1)),
                    TARGET, 1, List.of(new GenericStack(TARGET, 1)), List.of(input(ALTERNATIVE, 1)));
            producers.put(TARGET, List.of(target, alt));
            patterns.put(alt.source(), alt);
        }
        var graph = new CraftGraph(TARGET, producers, patterns, List.copyOf(patterns.keySet()),
                Map.of(), Map.of(), Map.of(), Map.of(RAW, Long.MAX_VALUE, INTERMEDIATE, Long.MAX_VALUE,
                        ALTERNATIVE, ORDER), Set.of(), Map.of(), alternative, null, alternative ? 2 : 1,
                alternative ? 2 : 1);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW, rawSupply, INTERMEDIATE, intermediateSupply)));
        return graph;
    }

    private static GraphInput input(AEKey key, long amount) {
        return new GraphInput(key, amount, 1, GraphInput.Mode.NORMAL, null, null, null, false);
    }

    private static GraphPattern pattern(AEKey key, long output, GraphInput... inputs) {
        var sourceInputs = java.util.Arrays.stream(inputs)
                .map(input -> FakeInput.stack(input.key(), input.amountPerCraft())).toArray(FakeInput[]::new);
        var source = FakePattern.of(key, output, sourceInputs);
        return new GraphPattern(source, key, output, List.of(new GenericStack(key, output)), List.of(inputs));
    }

    private static CraftGraph customGraph(List<GraphPattern> patterns, Map<AEKey, Long> stock) {
        var producers = new java.util.HashMap<AEKey, List<GraphPattern>>();
        var sources = new java.util.LinkedHashMap<appeng.api.crafting.IPatternDetails, GraphPattern>();
        for (var pattern : patterns) {
            producers.put(pattern.allOutputs().getFirst().what(), List.of(pattern));
            sources.put(pattern.source(), pattern);
        }
        return new CraftGraph(TARGET, producers, sources, List.copyOf(sources.keySet()),
                Map.of(), Map.of(), Map.of(), stock, Set.of(), Map.of(), false, null, 1, 1);
    }
}
