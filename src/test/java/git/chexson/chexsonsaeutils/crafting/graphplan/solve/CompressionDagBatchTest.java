package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.networking.crafting.CalculationStrategy;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 连续九倍压缩接多输入、共享原料子图；核对真实图编译入口和精确账本。 */
class CompressionDagBatchTest {
    private static final AEKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey COPPER = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEKey RAW_IRON = AEItemKey.of(Items.IRON_NUGGET);
    private static final AEKey RAW_COPPER = AEItemKey.of(Items.GOLD_NUGGET);
    private static final AEKey ROD = AEItemKey.of(Items.STICK);
    private static final AEKey BLOCK = AEItemKey.of(Items.COPPER_BLOCK);
    private static final List<AEKey> MATERIALS = List.of(IRON, COPPER, RAW_IRON, RAW_COPPER, ROD, BLOCK);

    private record Fixture(FakeGrid grid, List<AEItemKey> chain, BigInteger alloyAmount) { }

    private static Fixture fixture(int depth) {
        return fixture(depth, false, false);
    }

    private static FakeInput input(AEKey key, long multiplier, boolean duplicate) {
        return duplicate ? FakeInput.multi(multiplier, key, key) : new FakeInput(key, multiplier);
    }

    private static Fixture fixture(int depth, boolean duplicate, boolean bulk) {
        var chain = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
                .map(AEItemKey::of).filter(key -> !MATERIALS.contains(key)).limit(depth + 1L).toList();
        assertEquals(depth + 1, chain.size());
        var builder = FakeGrid.builder().storage(Map.of(RAW_IRON, Long.MAX_VALUE, RAW_COPPER, Long.MAX_VALUE,
                chain.get(depth), bulk ? 1L : 0L));
        for (int index = 0; index < depth; index++) {
            builder.pattern(chain.get(index), FakePattern.of(chain.get(index), 1,
                    input(chain.get(index + 1), 9, duplicate)));
        }
        builder.pattern(chain.get(depth), FakePattern.of(chain.get(depth), 1,
                input(ROD, 4, duplicate), input(BLOCK, 1, duplicate)));
        builder.pattern(ROD, FakePattern.of(ROD, 1, input(IRON, 2, duplicate), input(COPPER, 1, duplicate)));
        builder.pattern(BLOCK, FakePattern.of(BLOCK, 1, input(COPPER, 9, duplicate)));
        builder.pattern(IRON, FakePattern.of(IRON, bulk ? 3 : 1, input(RAW_IRON, bulk ? 8 : 3, duplicate)));
        builder.pattern(COPPER, FakePattern.of(COPPER, bulk ? 3 : 1, input(RAW_COPPER, bulk ? 8 : 3, duplicate)));
        return new Fixture(builder.build(), chain, BigInteger.valueOf(9).pow(depth).subtract(bulk ? BigInteger.ONE : BigInteger.ZERO));
    }

    @Test
    void duplicatedCandidatesAndBulkSharedProductsKeepReusableRoundingRemainders() {
        var fixture = fixture(22, true, true);
        var ironTimes = fixture.alloyAmount.multiply(BigInteger.valueOf(8)).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
        var copperTimes = fixture.alloyAmount.multiply(BigInteger.valueOf(13)).add(BigInteger.TWO).divide(BigInteger.valueOf(3));
        var result = solve(fixture, ironTimes.multiply(BigInteger.valueOf(8)), copperTimes.multiply(BigInteger.valueOf(8)));
        assertFalse(result.simulation());
        assertEquals(ironTimes.multiply(BigInteger.valueOf(8)), result.state().used().getExact(RAW_IRON));
        assertEquals(copperTimes.multiply(BigInteger.valueOf(8)), result.state().used().getExact(RAW_COPPER));
        assertEquals(2L, result.state().extract(IRON, 100, true));
        assertEquals(1L, result.state().extract(COPPER, 100, true));
        assertTrue(result.state().journal() < 500, "重复候选及可重用余料不应引起宽需求分轮");
    }

    @Test
    void differentCandidateAmountsAndAlternativeKeysRetainExtractionOrder() {
        var target = AEItemKey.of(Items.CLOCK);
        var cases = List.of(
                FakeInput.ordered(1, List.of(new appeng.api.stacks.GenericStack(RAW_IRON, 3),
                        new appeng.api.stacks.GenericStack(RAW_IRON, 2)), List.of(RAW_IRON)),
                FakeInput.multi(1, RAW_IRON, RAW_COPPER));
        for (int index = 0; index < cases.size(); index++) {
            var stock = index == 0 ? Map.of(RAW_IRON, 5L) : Map.of(RAW_IRON, 1L, RAW_COPPER, 1L);
            var grid = FakeGrid.builder().storage(stock).pattern(target, FakePattern.of(target, 1, cases.get(index))).build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            assertEquals(BigInteger.valueOf(index == 0 ? 5 : 1), result.state().used().getExact(RAW_IRON));
            if (index == 1) assertEquals(BigInteger.ONE, result.state().used().getExact(RAW_COPPER));
        }
    }

    private static SolveResult solve(Fixture fixture, BigInteger ironStock, BigInteger copperStock) {
        var graph = GraphCompiler.compile(fixture.grid.crafting, fixture.grid.storage.getCachedInventory(),
                fixture.chain.getFirst(), null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(
                RAW_IRON, PlanAmount.of(ironStock), RAW_COPPER, PlanAmount.of(copperStock))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertNull(MaterialClosureGate.validate(result.state(), graph, 1, null));
        return result;
    }

    @Test
    void exponentialCompressionWithSharedMaterialUsesBoundedJournalAndExactFirings() {
        for (int depth : new int[] {24, 25, 64}) {
            assertCompressionAccounting(depth);
        }
    }

    private static void assertCompressionAccounting(int depth) {
        var fixture = fixture(depth);
        var iron = fixture.alloyAmount.multiply(BigInteger.valueOf(24));
        var copper = fixture.alloyAmount.multiply(BigInteger.valueOf(39));
        var result = solve(fixture, iron, copper);
        assertFalse(result.simulation());
        assertEquals(iron, result.state().used().getExact(RAW_IRON));
        assertEquals(copper, result.state().used().getExact(RAW_COPPER));
        var firings = new LinkedHashMap<AEKey, BigInteger>();
        for (var entry : result.state().craftsWide().keys()) {
            firings.put(entry.getOutputs().getFirst().what(), result.state().craftsWide().getAmount(entry).exactValue());
        }
        for (int index = 0; index < fixture.chain.size(); index++) {
            assertEquals(BigInteger.valueOf(9).pow(index), firings.get(fixture.chain.get(index)));
        }
        assertEquals(fixture.alloyAmount.multiply(BigInteger.valueOf(13)), firings.get(COPPER));
        assertTrue(result.state().journal() < 500, "日志应随图节点增长，实际=" + result.state().journal());
    }

    @Test
    void failedBatchRollbackKeepsExactMissingMaterials() {
        var fixture = fixture(24);
        var iron = fixture.alloyAmount.multiply(BigInteger.valueOf(24));
        var copper = fixture.alloyAmount.multiply(BigInteger.valueOf(39));
        var result = solve(fixture, iron, BigInteger.valueOf(7));
        assertTrue(result.simulation());
        assertEquals(iron, result.state().used().getExact(RAW_IRON));
        assertEquals(BigInteger.valueOf(7), result.state().used().getExact(RAW_COPPER));
        assertEquals(copper.subtract(BigInteger.valueOf(7)), result.state().missing().getExact(RAW_COPPER));
        assertTrue(result.state().journal() < 500);
    }

    @Test
    void wideSharedInputsMatchExactAccountingAcrossStockAndShortageCombinations() {
        var target = AEItemKey.of(Items.CLOCK);
        var intermediate = AEItemKey.of(Items.EMERALD);
        var random = new java.util.Random(0x9DA6L);
        var demand = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
        for (int sample = 0; sample < 48; sample++) {
            long intermediateStock = random.nextInt(100);
            var firings = demand.subtract(BigInteger.valueOf(intermediateStock));
            var rawDemand = firings.multiply(BigInteger.valueOf(8));
            var copperDemand = firings;
            var rawStock = rawDemand.add(BigInteger.valueOf(random.nextInt(41) - 20));
            var copperStock = copperDemand.add(BigInteger.valueOf(random.nextInt(41) - 20));
            var grid = FakeGrid.builder().storage(Map.of(intermediate, intermediateStock,
                    RAW_IRON, Long.MAX_VALUE, RAW_COPPER, Long.MAX_VALUE))
                    .pattern(target, FakePattern.of(target, 1, new FakeInput(intermediate, Long.MAX_VALUE)))
                    .pattern(intermediate, FakePattern.of(intermediate, 1,
                            new FakeInput(RAW_IRON, 3), new FakeInput(RAW_IRON, 5), new FakeInput(RAW_COPPER, 1)))
                    .build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
            graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(
                    RAW_IRON, PlanAmount.of(rawStock), RAW_COPPER, PlanAmount.of(copperStock))));
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertEquals(rawStock.compareTo(rawDemand) < 0 || copperStock.compareTo(copperDemand) < 0,
                    result.simulation());
            assertEquals(BigInteger.valueOf(intermediateStock), result.state().used().getExact(intermediate));
            assertEquals(firings, result.state().craftsWide().getAmount(
                    graph.producersByOutput().get(intermediate).getFirst().source()).exactValue());
            assertEquals(rawDemand.min(rawStock), result.state().used().getExact(RAW_IRON));
            assertEquals(copperDemand.min(copperStock), result.state().used().getExact(RAW_COPPER));
            assertEquals(rawDemand.subtract(rawStock).max(BigInteger.ZERO), result.state().missing().getExact(RAW_IRON));
            assertEquals(copperDemand.subtract(copperStock).max(BigInteger.ZERO), result.state().missing().getExact(RAW_COPPER));
            assertNull(MaterialClosureGate.validate(result.state(), graph, 2, null));
        }
    }

    @Test
    void failedSharedBatchRestoresStockBeforePartialAndAlternativeRoutes() {
        var target = AEItemKey.of(Items.CLOCK);
        var intermediate = AEItemKey.of(Items.EMERALD);
        var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                List.of(new appeng.api.stacks.GenericStack(target, 1)),
                new FakeInput(intermediate, Long.MAX_VALUE)).withPriority(10);
        var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                List.of(new appeng.api.stacks.GenericStack(target, 1)), new FakeInput(RAW_IRON, 1));
        var grid = FakeGrid.builder().storage(Map.of(RAW_IRON, Long.MAX_VALUE))
                .pattern(target, preferred, alternative)
                .pattern(intermediate, FakePattern.of(intermediate, 1,
                        new FakeInput(RAW_IRON, 1), new FakeInput(RAW_IRON, 1)))
                .build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        graph.attachSupplyTruth(PlanningSupplyTruth.of(Map.of(RAW_IRON,
                PlanAmount.of(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3))))));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 2);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        // 完整首选批次失败并回滚；既有顺序随后先完成首选一发，再由替代路线完成一发。
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.ONE),
                result.state().used().getExact(RAW_IRON));
        assertEquals(BigInteger.ONE, result.state().craftsWide().getAmount(preferred).exactValue());
        assertEquals(BigInteger.ONE, result.state().craftsWide().getAmount(alternative).exactValue());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), result.state().craftsWide().getAmount(
                graph.producersByOutput().get(intermediate).getFirst().source()).exactValue());
        assertEquals(3, result.state().craftsWide().size());
        assertNull(MaterialClosureGate.validate(result.state(), graph, 2, null));
    }

    @Test
    @Tag("footprint")
    void planningPerformanceReadout() {
        var allocation = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        for (int depth : new int[] {18, 22, 24, 25, 64}) {
            var fixture = fixture(depth);
            var iron = fixture.alloyAmount.multiply(BigInteger.valueOf(24));
            var copper = fixture.alloyAmount.multiply(BigInteger.valueOf(39));
            var elapsed = new ArrayList<Long>();
            var allocated = new ArrayList<Long>();
            int journal = 0;
            for (int sample = 0; sample < 3; sample++) {
                long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                var result = solve(fixture, iron, copper);
                elapsed.add(System.nanoTime() - start);
                allocated.add(allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before);
                assertFalse(result.simulation());
                assertEquals(iron, result.state().used().getExact(RAW_IRON));
                assertEquals(copper, result.state().used().getExact(RAW_COPPER));
                journal = result.state().journal();
            }
            elapsed.sort(Long::compare);
            allocated.sort(Long::compare);
            System.out.printf("压缩共享图 depth=%d median-ns=%d median-B=%d journal=%d%n",
                    depth, elapsed.get(1), allocated.get(1), journal);
        }
    }
}
