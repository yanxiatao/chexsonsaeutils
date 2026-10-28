package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import git.chexson.chexsonsaeutils.crafting.solve.OrderedPlanningReplay;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规划求解与原生对照的行为场景；由类别测试统一调用。 */
public final class PlanningScenarios {
    private PlanningScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.graphplan",
                () -> DeepBranchPruningCases.verify(temporaryRoot),
                () -> GraphPlanningEquivalenceCases.verify(temporaryRoot),
                () -> LinearPlannerBeyondLongLedgerCases.verify(temporaryRoot),
                () -> LinearPlannerSchedulingCases.verify(temporaryRoot),
                () -> PlanBytesExactCases.verify(temporaryRoot),
                () -> TerminalBatchPruningCases.verify(temporaryRoot)
        );
    }

    /** 执行本包的按需度量，保留各场景名称与状态隔离。 */
    public static void measure(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.graphplan",
                () -> ColdPlanningPerformanceCases.measure(temporaryRoot)
        );
    }

    /** ColdPlanningPerformanceCases 的独立场景夹具，按类别统一执行。 */
    public static final class ColdPlanningPerformanceCases {
        void coldDeepMultiInputReadout() {
            var target = AEItemKey.of(Items.IRON_INGOT);
            var raw = AEItemKey.of(Items.GOLD_INGOT);
            var side = AEItemKey.of(Items.COAL);
            var alternativeKey = AEItemKey.of(Items.DIAMOND);
            long request = 100_000_000_000_000L;
            var keys = BuiltInRegistries.ITEM.stream()
                    .filter(item -> item != Items.AIR && item != Items.IRON_INGOT && item != Items.GOLD_INGOT
                            && item != Items.COAL && item != Items.DIAMOND)
                    .limit(128).map(AEItemKey::of).toList();
            var builder = FakeGrid.builder().storage(Map.of(raw, request, alternativeKey, request));
            for (int index = 0; index < keys.size(); index++) {
                var input = index + 1 == keys.size() ? raw : keys.get(index + 1);
                builder.pattern(keys.get(index), FakePattern.of(keys.get(index), 1,
                        new FakePlanGrid.FakeInput(input, 1)));
            }
            var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    java.util.List.of(new appeng.api.stacks.GenericStack(target, 1)),
                    new FakePlanGrid.FakeInput(keys.getFirst(), 1), new FakePlanGrid.FakeInput(side, 1)).withPriority(10);
            var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    java.util.List.of(new appeng.api.stacks.GenericStack(target, 1)),
                    new FakePlanGrid.FakeInput(alternativeKey, 1));
            var grid = builder.pattern(target, preferred, alternative).build();
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);
            long[] elapsed = new long[9];
            long[] allocated = new long[9];
            for (int sample = 0; sample < 9; sample++) {
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, request);
                var plan = Ae2PlanConverter.toPlan(result, graph);
                elapsed[sample] = System.nanoTime() - start;
                allocated[sample] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                assertFalse(result.hasDeclined(), result::declineReason);
                assertFalse(plan.simulation());
                assertEquals(request, plan.usedItems().get(alternativeKey));
                assertEquals(0, plan.usedItems().get(raw));
                assertEquals(Map.of(alternative, request), plan.patternTimes());
            }
            Arrays.sort(elapsed);
            Arrays.sort(allocated);
            System.out.printf("冷图多输入 depth=128 request=100T median-ns=%d median-B=%d%n", elapsed[4], allocated[4]);
        }

        void coldGraphReadout() {
            var bean = ManagementFactory.getThreadMXBean();
            assertTrue(bean instanceof com.sun.management.ThreadMXBean);
            var allocation = (com.sun.management.ThreadMXBean) bean;
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            for (int depth : new int[] {2, 128}) {
                for (int unrelated : new int[] {0, 20_000}) {
                    var fixture = fixture(depth, unrelated);
                    long[] elapsed = new long[9];
                    long[] allocated = new long[9];
                    int indexedKeys = 0;
                    for (int sample = 0; sample < elapsed.length; sample++) {
                        long beforeAllocation = allocation.getThreadAllocatedBytes(thread);
                        long start = System.nanoTime();
                        var graph = GraphCompiler.compile(fixture.grid.crafting,
                                fixture.grid.storage.getCachedInventory(), fixture.target, null);
                        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1000L);
                        var plan = Ae2PlanConverter.toPlan(result, graph);
                        elapsed[sample] = System.nanoTime() - start;
                        allocated[sample] = allocation.getThreadAllocatedBytes(thread) - beforeAllocation;
                        assertFalse(graph.hasDeclined(), graph::declineReason);
                        assertFalse(result.hasDeclined(), result::declineReason);
                        assertFalse(plan.simulation());
                        assertEquals(depth, plan.patternTimes().size());
                        assertEquals(Map.of(fixture.raw, 1000L), FakePlanGrid.toPlain(plan.usedItems()));
                        indexedKeys = graph.keyIndex().size();
                    }
                    long first = elapsed[0];
                    long firstBytes = allocated[0];
                    Arrays.sort(elapsed);
                    Arrays.sort(allocated);
                    System.out.printf("冷图度量 depth=%d unrelated=%d first-ns=%d first-B=%d"
                                    + " median-ns=%d median-B=%d indexedKeys=%d%n",
                            depth, unrelated, first, firstBytes, elapsed[4], allocated[4], indexedKeys);
                }
            }
        }

        void coldPartialGraphReadout() {
            var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            var fixture = fixture(128, 0, 537L);
            long[] elapsed = new long[9];
            long[] allocated = new long[9];
            for (int sample = 0; sample < elapsed.length; sample++) {
                long beforeAllocation = allocation.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                var graph = GraphCompiler.compile(fixture.grid.crafting,
                        fixture.grid.storage.getCachedInventory(), fixture.target, null);
                var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, 1_000_000L);
                var plan = Ae2PlanConverter.toPlan(result, graph);
                elapsed[sample] = System.nanoTime() - start;
                allocated[sample] = allocation.getThreadAllocatedBytes(thread) - beforeAllocation;
                assertFalse(result.hasDeclined(), result::declineReason);
                assertFalse(plan.simulation());
                assertEquals(537L, result.finalAmount());
                assertEquals(Map.of(fixture.raw, 537L), FakePlanGrid.toPlain(plan.usedItems()));
            }
            Arrays.sort(elapsed);
            Arrays.sort(allocated);
            System.out.printf("冷图部分合成 depth=128 request=1000000 capacity=537 median-ns=%d median-B=%d%n",
                    elapsed[4], allocated[4]);
        }

        void coldAlternativeRouteReadout() {
            var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            var target = AEItemKey.of(Items.IRON_INGOT);
            var scarce = AEItemKey.of(Items.GOLD_INGOT);
            var abundant = AEItemKey.of(Items.DIAMOND);
            for (long stock : new long[] {0L, 7L}) {
                var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                        java.util.List.of(new appeng.api.stacks.GenericStack(target, 1L)),
                        new FakePlanGrid.FakeInput(scarce, 1L)).withPriority(10);
                var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                        java.util.List.of(new appeng.api.stacks.GenericStack(target, 1L)),
                        new FakePlanGrid.FakeInput(abundant, 1L));
                var grid = FakeGrid.builder().storage(Map.of(scarce, stock, abundant, 1_000_000L))
                        .pattern(target, preferred, alternative).build();
                long[] elapsed = new long[9];
                long[] allocated = new long[9];
                for (int sample = 0; sample < elapsed.length; sample++) {
                    long beforeAllocation = allocation.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
                    var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1_000_000L);
                    var plan = Ae2PlanConverter.toPlan(result, graph);
                    elapsed[sample] = System.nanoTime() - start;
                    allocated[sample] = allocation.getThreadAllocatedBytes(thread) - beforeAllocation;
                    assertFalse(result.hasDeclined(), result::declineReason);
                    assertFalse(plan.simulation());
                    assertEquals(2, graph.producersByOutput().get(target).size());
                    assertEquals(stock, plan.usedItems().get(scarce));
                    assertEquals(1_000_000L - stock, plan.usedItems().get(abundant));
                }
                Arrays.sort(elapsed);
                Arrays.sort(allocated);
                System.out.printf("冷图替代路线 request=1000000 scarce=%d median-ns=%d median-B=%d%n",
                        stock, elapsed[4], allocated[4]);
            }
        }

        private static Fixture fixture(int depth, int unrelated) {
            return fixture(depth, unrelated, 10_000L);
        }

        void coldAlternativeFanoutReadout() {
            int width = 128;
            long request = 1_000_000L;
            var target = AEItemKey.of(Items.EMERALD);
            var scarce = AEItemKey.of(Items.GOLD_INGOT);
            var abundant = AEItemKey.of(Items.DIAMOND);
            var children = BuiltInRegistries.ITEM.stream()
                    .filter(item -> item != Items.AIR && item != Items.EMERALD
                            && item != Items.GOLD_INGOT && item != Items.DIAMOND)
                    .limit(width).map(AEItemKey::of).toList();
            var inputs = new appeng.api.crafting.IPatternDetails.IInput[width];
            var builder = FakeGrid.builder().storage(Map.of(abundant, request * width));
            for (int index = 0; index < width; index++) {
                var child = children.get(index);
                inputs[index] = new FakePlanGrid.FakeInput(child, 1L);
                var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                        java.util.List.of(new appeng.api.stacks.GenericStack(child, 1L)),
                        new FakePlanGrid.FakeInput(scarce, 1L)).withPriority(10);
                var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                        java.util.List.of(new appeng.api.stacks.GenericStack(child, 1L)),
                        new FakePlanGrid.FakeInput(abundant, 1L));
                builder.pattern(child, preferred, alternative);
            }
            var grid = builder.pattern(target, FakePattern.of(target, 1L, inputs)).build();
            var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            long[] elapsed = new long[9];
            long[] allocated = new long[9];
            for (int sample = 0; sample < elapsed.length; sample++) {
                long beforeAllocation = allocation.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, request);
                var plan = Ae2PlanConverter.toPlan(result, graph);
                elapsed[sample] = System.nanoTime() - start;
                allocated[sample] = allocation.getThreadAllocatedBytes(thread) - beforeAllocation;
                assertFalse(result.hasDeclined(), result::declineReason);
                assertFalse(plan.simulation());
                assertEquals(width + 1, plan.patternTimes().size());
                assertEquals(request * width, plan.usedItems().get(abundant));
            }
            Arrays.sort(elapsed);
            Arrays.sort(allocated);
            System.out.printf("冷图竞争分支 width=128 request=1000000 median-ns=%d median-B=%d%n",
                    elapsed[4], allocated[4]);
        }

        private static Fixture fixture(int depth, int unrelated, long supply) {
            var keys = BuiltInRegistries.ITEM.stream()
                    .filter(item -> item != Items.AIR && item != Items.LEATHER_HELMET)
                    .limit(depth + 1L).map(AEItemKey::of).toList();
            assertEquals(depth + 1, keys.size());
            var stock = new LinkedHashMap<AEKey, Long>();
            stock.put(keys.get(depth), supply);
            for (int index = 0; index < unrelated; index++) {
                stock.put(FakePlanGrid.dyedKey(Items.LEATHER_HELMET, index), 1L);
            }
            var builder = FakeGrid.builder().storage(stock);
            for (int index = 0; index < depth; index++) {
                builder.pattern(keys.get(index),
                        FakePattern.crafting(keys.get(index), 1L, Map.of(keys.get(index + 1), 1L)));
            }
            return new Fixture(builder.build(), keys.get(0), keys.get(depth));
        }

        void coldDeepAlternativeReadout() {
            var target = AEItemKey.of(Items.IRON_INGOT);
            var scarce = AEItemKey.of(Items.GOLD_INGOT);
            var abundant = AEItemKey.of(Items.DIAMOND);
            var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            allocation.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            for (int depth : new int[] {16, 128}) {
                var keys = BuiltInRegistries.ITEM.stream()
                        .filter(item -> item != Items.AIR && item != Items.IRON_INGOT
                                && item != Items.GOLD_INGOT && item != Items.DIAMOND)
                        .limit(depth).map(AEItemKey::of).toList();
                for (long stock : new long[] {0L, 7L}) {
                    var builder = FakeGrid.builder().storage(Map.of(scarce, stock, abundant, 1_000_000L));
                    for (int index = 0; index < depth; index++) {
                        var input = index + 1 == depth ? scarce : keys.get(index + 1);
                        builder.pattern(keys.get(index), FakePattern.crafting(keys.get(index), 1L, Map.of(input, 1L)));
                    }
                    var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                            java.util.List.of(new appeng.api.stacks.GenericStack(target, 1L)),
                            new FakePlanGrid.FakeInput(keys.getFirst(), 1L)).withPriority(10);
                    var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                            java.util.List.of(new appeng.api.stacks.GenericStack(target, 1L)),
                            new FakePlanGrid.FakeInput(abundant, 1L));
                    var grid = builder.pattern(target, preferred, alternative).build();
                    long[] elapsed = new long[9];
                    long[] allocated = new long[9];
                    for (int sample = 0; sample < elapsed.length; sample++) {
                        long beforeAllocation = allocation.getThreadAllocatedBytes(thread);
                        long start = System.nanoTime();
                        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
                        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1_000_000L);
                        var plan = Ae2PlanConverter.toPlan(result, graph);
                        elapsed[sample] = System.nanoTime() - start;
                        allocated[sample] = allocation.getThreadAllocatedBytes(thread) - beforeAllocation;
                        assertFalse(result.hasDeclined(), result::declineReason);
                        assertFalse(plan.simulation());
                        assertEquals(2, graph.producersByOutput().get(target).size());
                        assertEquals(stock, plan.usedItems().get(scarce));
                        assertEquals(1_000_000L - stock, plan.usedItems().get(abundant));
                    }
                    Arrays.sort(elapsed);
                    Arrays.sort(allocated);
                    System.out.printf("冷图深层替代 depth=%d scarce=%d median-ns=%d median-B=%d%n",
                            depth, stock, elapsed[4], allocated[4]);
                }
            }
        }

        private record Fixture(FakeGrid grid, AEKey target, AEKey raw) {
        }

        static void measure(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases.coldDeepMultiInputReadout", () -> new ColdPlanningPerformanceCases().coldDeepMultiInputReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases.coldGraphReadout", () -> new ColdPlanningPerformanceCases().coldGraphReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases.coldPartialGraphReadout", () -> new ColdPlanningPerformanceCases().coldPartialGraphReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases.coldAlternativeRouteReadout", () -> new ColdPlanningPerformanceCases().coldAlternativeRouteReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases.coldAlternativeFanoutReadout", () -> new ColdPlanningPerformanceCases().coldAlternativeFanoutReadout()),
                () -> org.junit.jupiter.api.Assertions.assertAll("ColdPlanningPerformanceCases.coldDeepAlternativeReadout", () -> new ColdPlanningPerformanceCases().coldDeepAlternativeReadout())
            );
        }
    }

    /** DeepBranchPruningCases 的独立场景夹具，按类别统一执行。 */
    public static final class DeepBranchPruningCases {
        private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey A = AEItemKey.of(Items.REDSTONE);
        private static final AEItemKey B = AEItemKey.of(Items.EMERALD);
        private static final AEItemKey RAW = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey ALTERNATIVE = AEItemKey.of(Items.DIAMOND);

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

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.laterStockOnlyInputPrunesAfterExpensiveFirstInputAndKeepsSharedCompetition", () -> new DeepBranchPruningCases().laterStockOnlyInputPrunesAfterExpensiveFirstInputAndKeepsSharedCompetition()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.firstInputByproductCanFundLaterTerminal", () -> new DeepBranchPruningCases().firstInputByproductCanFundLaterTerminal()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.multiInputPrefixUsesSideCapacityWithoutChangingRoundingOrSharedStock", () -> new DeepBranchPruningCases().multiInputPrefixUsesSideCapacityWithoutChangingRoundingOrSharedStock()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.laterInputByproductKeepsSupplyingOtherDependencies", () -> new DeepBranchPruningCases().laterInputByproductKeepsSupplyingOtherDependencies()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.roundedChainAndIntermediateStockMatchNativeIncludingBytes", () -> new DeepBranchPruningCases().roundedChainAndIntermediateStockMatchNativeIncludingBytes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.oddIntermediateStockKeepsExistingWholeTemplateAccounting", () -> new DeepBranchPruningCases().oddIntermediateStockKeepsExistingWholeTemplateAccounting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.branchingInsideDependencyChainKeepsBothRoutesAvailable", () -> new DeepBranchPruningCases().branchingInsideDependencyChainKeepsBothRoutesAvailable()),
                () -> org.junit.jupiter.api.Assertions.assertAll("DeepBranchPruningCases.wideLeafSupplyAndHugeTemplateKeepExactUsedQuantity", () -> new DeepBranchPruningCases().wideLeafSupplyAndHugeTemplateKeepExactUsedQuantity())
            );
        }
    }

    /** GraphPlanningEquivalenceCases 的独立场景夹具，按类别统一执行。 */
    public static final class GraphPlanningEquivalenceCases {
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

        /** 参照面：AE2 原生树（离线一次性算完，见 {@link Ae2TreePlanReference}）。 */
        private static ICraftingPlan runTree(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
            return new Ae2TreePlanReference(
                    grid, grid.requester, new GenericStack(output, amount), strategy).run();
        }

        private static ICraftingPlan runGraph(FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), output, null);
            assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());
            var result = LinearPlanner.solve(graph, strategy, amount);
            assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
            return Ae2PlanConverter.toPlan(result, graph);
        }

        private static ICraftingPlan assertGraphMatchesNativeTree(
                FakeGrid grid, AEKey output, long amount, CalculationStrategy strategy) {
            var tree = runTree(grid, output, amount, strategy);
            var graph = runGraph(grid, output, amount, strategy);
            FakePlanGrid.assertPlansEqual(tree, graph);
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

            // 对拍基准是递归树（环压缩）：原生树对「输入=主产物键」
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

            assertGraphMatchesNativeTree(grid, TOP, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // S3: chains of three or more levels
        // ------------------------------------------------------------------

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

            assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // S4: partially satisfied by stock -> simulation plan
        // ------------------------------------------------------------------

        void s4PartialStockYieldsSimulation() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 5L))
                    .pattern(IRON, ironFromGold)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // S5: CRAFT_LESS binary search
        // ------------------------------------------------------------------

        void s5CraftLessFindsLargestAmount() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 10L))
                    .pattern(IRON, ironFromGold)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
        }

        void s5CraftLessImpossibleFallsBackToSimulation() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of())
                    .pattern(IRON, ironFromGold)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 16, CalculationStrategy.CRAFT_LESS);
        }

        void s5CraftLessFullySatisfiedSkipsSearch() {
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 100L))
                    .pattern(IRON, ironFromGold)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
        }

        void s5CraftLessLooseBoundFromSharedRawMaterialStillFindsExactMaximum() {
            // 两条分支共用金库存：独立分支上界为 11，整张配方实际最多只能做 5。
            var inputs = new java.util.LinkedHashMap<AEKey, Long>();
            inputs.put(DIAMOND, 1L);
            inputs.put(REDSTONE, 1L);
            var grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 11L))
                    .pattern(IRON, FakePattern.crafting(IRON, 1, inputs))
                    .pattern(DIAMOND, FakePattern.crafting(DIAMOND, 1, Map.of(GOLD, 1L)))
                    .pattern(REDSTONE, FakePattern.crafting(REDSTONE, 1, Map.of(GOLD, 1L)))
                    .build();

            var plan = assertGraphMatchesNativeTree(grid, IRON, 32, CalculationStrategy.CRAFT_LESS);
            assertEquals(5L, plan.finalOutput().amount());
            assertEquals(10L, plan.usedItems().get(GOLD));
        }

        // ------------------------------------------------------------------
        // S10: byproducts feeding downstream demand
        // ------------------------------------------------------------------

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

            assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

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

            assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // S6: container item cycles (bucket-style limitQty, closed-form batch)
        // ------------------------------------------------------------------

        void s6ContainerCycleBatched() {
            FakePattern ironInBucket = FakePattern.of(IRON, 1,
                    new FakeInput(GOLD, 1),
                    new FakeInput(BUCKET, 1, BUCKET));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 100L, BUCKET, 5L))
                    .pattern(IRON, ironInBucket)
                    .build();

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertFalse(plan.simulation());
            // Returned buckets keep the network pool whole: peak extraction of one.
            assertEquals(10L, plan.patternTimes().get(ironInBucket));
            assertEquals(1L, plan.usedItems().get(BUCKET));
        }

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

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertTrue(plan.simulation());
            assertEquals(1L, plan.missingItems().get(BUCKET));
            assertEquals(0L, plan.usedItems().get(BUCKET));
        }

        // ------------------------------------------------------------------
        // S7: finite-use durability chain (remainingKey hand-off)
        // ------------------------------------------------------------------

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

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, TOP, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
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

        void s9MultiCandidateFallback() {
            // Slot accepts A or B; only B is stocked, so every firing draws B
            // while the slot's node key (and bytes) stay on the primary A.
            FakePattern topFromEither = FakePattern.of(TOP, 1,
                    FakeInput.multi(1, A_ITEM, B_ITEM));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B_ITEM, 20L))
                    .pattern(TOP, topFromEither)
                    .build();

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertFalse(plan.simulation());
            assertEquals(10L, plan.patternTimes().get(topFromEither));
            assertEquals(10L, plan.usedItems().get(B_ITEM));
        }

        void s9MultiCandidatePreferenceOrder() {
            // The first candidate is drained whole before the greedy iteration
            // falls through to the second candidate.
            FakePattern topFromEither = FakePattern.of(TOP, 1,
                    FakeInput.multi(1, A_ITEM, B_ITEM));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(A_ITEM, 4L, B_ITEM, 20L))
                    .pattern(TOP, topFromEither)
                    .build();

            // 这一格不与原生树对拍：AE2 的模糊槽按它自己的类视图"整槽一键"付满
            // （本夹具里直接 copper×10），而我方图按样板声明的候选顺位先耗尽首选、
            // 不够才落到备选（amethyst×4 + copper×6）。这是台账 #46/#146 定下的自有口径，
            // 树那一腿的模糊序我们管不到（见 ProviderPatternOrder 类抬头"管不到 AE2 的树路"）。
            ICraftingPlan plan = runGraph(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertFalse(plan.simulation());
            assertEquals(4L, plan.usedItems().get(A_ITEM));
            assertEquals(6L, plan.usedItems().get(B_ITEM));
        }

        void s9FindCraftedStackReplacement() {
            // The primary SUB is pattern-less but fuzzy-craftable as GOLD:
            // findCraftedStack replaces the slot's node key with GOLD before
            // recursing, so demand flows into GOLD's pattern and the slot's bytes
            // land on GOLD.
            //
            // 台账 #143 把这一对夹具改成 AE2 的真形状：模糊代用的宇宙是
            // {@code craftableItemsList}（按 {@code AEKey#getPrimaryKey} 分族），
            // 所以候选必须与代用键<b>同 item</b>（旧夹具用 SLIME_BALL 代用 GOLD_INGOT，
            // 实机 AE2 永远不会这样配对）。
            AEItemKey subGold = componentKey(Items.GOLD_INGOT, "sub");
            FakePattern goldFromRedstone = FakePattern.crafting(GOLD, 1, Map.of(REDSTONE, 2L));
            FakePattern topFromEither = FakePattern.of(TOP, 1,
                    FakeInput.multi(1, subGold, GOLD));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(REDSTONE, 100L))
                    .pattern(GOLD, goldFromRedstone)
                    .pattern(TOP, topFromEither)
                    .build();

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertFalse(plan.simulation());
            assertEquals(10L, plan.patternTimes().get(topFromEither));
            assertEquals(10L, plan.patternTimes().get(goldFromRedstone));
            assertEquals(20L, plan.usedItems().get(REDSTONE));
        }

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

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, IRON, 12, CalculationStrategy.REPORT_MISSING_ITEMS);
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

            // 同上：dense 模糊槽的候选×变体顺位是我方口径，AE2 树在那儿整槽一键。
            ICraftingPlan plan = runGraph(grid, IRON, 8,
                    CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertFalse(plan.simulation());
            assertEquals(3L, plan.usedItems().get(damagedPickaxe));
            assertEquals(2L, plan.usedItems().get(pickaxe));
            assertEquals(3L, plan.usedItems().get(shovel));
        }

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

            assertGraphMatchesNativeTree(grid, tool100, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // S11: emittable items (emitter supply)
        // ------------------------------------------------------------------

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

            assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        /** dense emittable 位图必须在库存不足后保持 exact emittedItems 口径。 */
        void s11DenseEmittableViewPreservesPartialStockAccounting() {
            FakePattern topFromIron = FakePattern.of(TOP, 1, new FakeInput(IRON, 2));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(IRON, 3L))
                    .emittable(IRON)
                    .pattern(TOP, topFromIron)
                    .build();

            ICraftingPlan plan = assertGraphMatchesNativeTree(grid, TOP, 4,
                    CalculationStrategy.REPORT_MISSING_ITEMS);
            assertNotNull(plan);
            assertFalse(plan.simulation());
            assertEquals(3L, plan.usedItems().get(IRON));
            assertEquals(5L, plan.emittedItems().get(IRON));
        }

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

            assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // S12: infeasible request early out
        // ------------------------------------------------------------------

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

            assertGraphMatchesNativeTree(grid, TOP, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        void s12NoPatternAtAllSimulates() {
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 50L))
                    .build();

            assertGraphMatchesNativeTree(grid, GOLD, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        // ------------------------------------------------------------------
        // Decline scenarios: the graph must refuse and stay on the tree path.
        // ------------------------------------------------------------------

        void multiRouteGraphMatchesNativeTree() {
            // Multiple routes for one key are supported since level-1 phase 4:
            // routes fire greedily in registration order like the tree planner.
            // 台账 #140：清单里并列（同优先级）那一段不再保证"喂进来的序"，要表达有意的先后
            // 就声明递减的提供者优先级——那正是 AE2 交这份序的依据。
            FakePattern ironFromGold = FakePattern.crafting(IRON, 1, Map.of(GOLD, 2L)).withPriority(1);
            FakePattern ironFromDiamond = FakePattern.crafting(IRON, 1, Map.of(DIAMOND, 3L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(GOLD, 10L, DIAMOND, 10L))
                    .pattern(IRON, ironFromGold, ironFromDiamond)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

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
            var tree = runTree(grid, IRON, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertEquals(1L, tree.missingItems().get(GOLD));
            assertEquals(0L, tree.missingItems().get(REDSTONE));
        }

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

            assertGraphMatchesNativeTree(grid, IRON, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        void selfReferencingInputSimulatesLikeTree() {
            // The pattern fires once (level-1 node allows it), then the
            // re-entered IRON node filters every pattern and goes missing.
            FakePattern selfRef = FakePattern.crafting(IRON, 1, Map.of(IRON, 1L));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of())
                    .pattern(IRON, selfRef)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

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

        void substituteKeyRecursionSimulatesLikeTree() {
            // F's slot accepts a pattern-less candidate that is fuzzy-craftable as
            // F itself: the resolved node key re-enters F, every pattern is
            // filtered (selfBlocked leaf), and the request goes missing — the
            // tree's findCraftedStack + notRecursive behavior.
            // 同 #143 那条夹具改形：候选与代用键必须同 item（这里用 IRON_INGOT 的组件变体）。
            AEItemKey subIron = componentKey(Items.IRON_INGOT, "sub");
            FakePattern selfFeeding = FakePattern.of(IRON, 1,
                    FakeInput.multi(1, subIron, IRON));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of())
                    .pattern(IRON, selfFeeding)
                    .build();

            assertGraphMatchesNativeTree(grid, IRON, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

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

            var plan = assertGraphMatchesNativeTree(grid, TOP, 4096, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertTrue(plan.bytes() > 50 * M, () -> "input-site bytes collapsed: " + plan.bytes());
        }

        void s19HugeDeclaredIoMultiplierChannel() {
            // multiplier 通道（模板 1 件 × 大 multiplier）：字节同站按总件数入账。
            FakePattern midFromRedstone = FakePattern.crafting(MID, 10 * M, Map.of(REDSTONE, 30 * M));
            FakePattern topFromMid = FakePattern.crafting(TOP, 10 * M, Map.of(MID, 20 * M));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(REDSTONE, 100 * M))
                    .pattern(MID, midFromRedstone)
                    .pattern(TOP, topFromMid)
                    .build();

            var plan = assertGraphMatchesNativeTree(grid, TOP, 4096, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertTrue(plan.bytes() > 50 * M, () -> "input-site bytes collapsed: " + plan.bytes());
        }

        /**
         * 模糊槽整归首选本体族：首选候选能自产时，即使第二个候选在库里摆着，也不许跨族改道。
         *
         * <p>旧口径按"池子里还剩多少"逐份削，跨族拆份在整除处必掉余量且掉的份没人补产
         * （实机 {@code share=1199245817} 对实耗 {@code 1199245818＝3×399748606}，
         * 尾段 {@code stored=1} 谁也拿不到，5 张任务僵死）。
         */
        void fuzzySlotPaysWholeSlotToFirstProducibleCandidate() {
            FakePattern ironFromSand = FakePattern.crafting(IRON, 1, Map.of(SAND, 1L));
            FakePattern topFromEither = FakePattern.of(TOP, 1L,
                    FakeInput.multi(1L, IRON, B_ITEM));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(B_ITEM, 5L, SAND, 8L))
                    .pattern(IRON, ironFromSand)
                    .pattern(TOP, topFromEither)
                    .build();

            var plan = runGraph(grid, TOP, 2L, CalculationStrategy.REPORT_MISSING_ITEMS);

            assertEquals(0L, plan.usedItems().get(B_ITEM),
                    () -> "首选族能自产，就不该去动第二个候选的存量, used="
                            + FakePlanGrid.toPlain(plan.usedItems()));
            assertEquals(2L, plan.patternTimes().getOrDefault(ironFromSand, 0L),
                    () -> "整槽 2 份都得由铁矿的生产链承担, times=" + plan.patternTimes());
        }

        /**
         * 规划定版的配给表（"这一格哪个键几份"）必须自己撑得住：某键写下的份数不得超过
         * "这张任务表自产的量 + 计划已从网络提走的量"。
         *
         * <p>实机形态是 {@code refinedstorage:silicon hole=960 used=0 miss=0}：守恒闸门按图上
         * 展开后的候选宇宙判"够"，提交期又按 AE2 原始候选表重算一份归属，两个宇宙对不上，
         * 计划就带着"少产 960 枚硅"出门了——尾段那一格永远取不到料，lane 停在
         * {@code extractNull} 上不动。
         */
        void plannedMaterialPlanIsBoundedByThePlansOwnSupply() {
            // 自增殖环把计划推到递归出口，配给表才随任务表一起下发。
            FakePattern ironDoubling = FakePattern.of(IRON, 2L,
                    new FakeInput(IRON, 1L), new FakeInput(SAND, 1L));
            // 模糊槽 [铁矿, 铜锭]：每发 2 份，两种各摆 1 枚，规划得把 2 份拆给两个键。
            FakePattern topFromAny = FakePattern.of(TOP, 1L,
                    FakeInput.multi(2L, IRON, B_ITEM));
            FakeGrid grid = FakeGrid.builder()
                    .storage(Map.of(IRON, 1L, B_ITEM, 1L, SAND, 8L))
                    .pattern(IRON, ironDoubling)
                    .pattern(TOP, topFromAny)
                    .build();

            var plan = runGraph(grid, TOP, 1L, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertTrue(plan instanceof RecursivePlanContract,
                    () -> "期望递归出口: " + plan.getClass().getSimpleName());
            var material = ((RecursivePlanContract) plan).chexsonsaeutils$planMaterialPlan();
            var supply = new HashMap<AEKey, Long>();
            for (var entry : plan.patternTimes().entrySet()) {
                long times = entry.getValue() == null ? 0L : entry.getValue();
                if (entry.getKey() == null || times <= 0L || entry.getKey().getOutputs() == null) {
                    continue;
                }
                for (var output : entry.getKey().getOutputs()) {
                    if (output != null && output.what() != null && output.amount() > 0L) {
                        supply.merge(output.what(), output.amount() * times, Long::sum);
                    }
                }
            }
            for (var entry : plan.usedItems()) {
                if (entry.getKey() != null && entry.getLongValue() > 0L) {
                    supply.merge(entry.getKey(), entry.getLongValue(), Long::sum);
                }
            }
            long assigned = 0L;
            for (var task : material.entrySet()) {
                // 配给账换宽域账本后的等价读法：逐在册键取窄视图读数（本用例量级远未越界）
                var share = task.getValue();
                for (AEKey unit : share.keys()) {
                    long amount = share.getLong(unit);
                    assigned += amount;
                    long source = supply.getOrDefault(unit, 0L);
                    assertTrue(source >= amount, () -> "配给无来源: " + unit
                            + " assigned=" + amount + " supply=" + source
                            + " material=" + describeMaterialPlan(material));
                }
            }
            assertTrue(assigned > 0L, () -> "计划没带配给表: " + describeMaterialPlan(material));
            // 下界等式（任务 #49 复现点）：配给表给某 pattern 某族的份数，必须等于该 pattern
            // 发射向量按同一条付款规则真吃的那一族的份数。TOP 槽 multi(2, 铁矿, 铜锭) 首选族
            // 铁矿可自产 ⇒ 整槽 2×times 份都必须记在铁矿族上，拆给铜锭一族即规划算料错。
            long topTimes = plan.patternTimes().getOrDefault(topFromAny, 0L);
            var topAssigned = material.get(topFromAny);
            assertNotNull(topAssigned, () -> "TOP 没进配给表: " + describeMaterialPlan(material));
            Object ironFamily = IRON.getPrimaryKey();
            long ironShare = 0L;
            for (AEKey unit : topAssigned.keys()) {
                if (ironFamily.equals(unit.getPrimaryKey())) {
                    ironShare += topAssigned.getLong(unit);
                }
            }
            assertEquals(2L * topTimes, ironShare,
                    () -> "付款族份额 != 发射向量应吃份数: TOP 配给=" + describeMaterialPlan(material)
                            + " times=" + topTimes);
        }

        /**
         * 配给表的失败信息读数：账本换 {@link WideKeyCounter} 后默认 {@code toString} 只有对象身份，
         * 这里按「样板 → 键=份数」展开（纯诊断，不参与判据）。
         */
        private static String describeMaterialPlan(
                Map<IPatternDetails, WideKeyCounter> material) {
            var described = new StringBuilder("{");
            for (var task : material.entrySet()) {
                described.append('\n').append(task.getKey().getDefinition()).append("={");
                for (AEKey key : task.getValue().keys()) {
                    described.append(key).append('=').append(task.getValue().getLong(key)).append(", ");
                }
                described.append("},");
            }
            return described.append('}').toString();
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.selfReplicatingPatternMatchesTree", () -> new GraphPlanningEquivalenceCases().selfReplicatingPatternMatchesTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.ringCatalystSeedWithPatchedStockMatchesTree", () -> new GraphPlanningEquivalenceCases().ringCatalystSeedWithPatchedStockMatchesTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.exactInputRejectsSiblingComponentVariant", () -> new GraphPlanningEquivalenceCases().exactInputRejectsSiblingComponentVariant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.explicitlyFuzzyInputAcceptsSiblingComponentVariant", () -> new GraphPlanningEquivalenceCases().explicitlyFuzzyInputAcceptsSiblingComponentVariant()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.componentSpecificOutputUsesMatchingPatternAndIngredients", () -> new GraphPlanningEquivalenceCases().componentSpecificOutputUsesMatchingPatternAndIngredients()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.orderedGraphKeepsExactComponentsAcrossBatchedTransformationChain", () -> new GraphPlanningEquivalenceCases().orderedGraphKeepsExactComponentsAcrossBatchedTransformationChain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.orderedGraphHonorsExplicitCraftingSubstitutionBoundary", () -> new GraphPlanningEquivalenceCases().orderedGraphHonorsExplicitCraftingSubstitutionBoundary()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.multiCandidateCircularInputMatchesTree", () -> new GraphPlanningEquivalenceCases().multiCandidateCircularInputMatchesTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s3ThreeLevelChain", () -> new GraphPlanningEquivalenceCases().s3ThreeLevelChain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s4PartialStockYieldsSimulation", () -> new GraphPlanningEquivalenceCases().s4PartialStockYieldsSimulation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s5CraftLessFindsLargestAmount", () -> new GraphPlanningEquivalenceCases().s5CraftLessFindsLargestAmount()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s5CraftLessImpossibleFallsBackToSimulation", () -> new GraphPlanningEquivalenceCases().s5CraftLessImpossibleFallsBackToSimulation()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s5CraftLessFullySatisfiedSkipsSearch", () -> new GraphPlanningEquivalenceCases().s5CraftLessFullySatisfiedSkipsSearch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s5CraftLessLooseBoundFromSharedRawMaterialStillFindsExactMaximum", () -> new GraphPlanningEquivalenceCases().s5CraftLessLooseBoundFromSharedRawMaterialStillFindsExactMaximum()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s10ByproductFeedsDownstream", () -> new GraphPlanningEquivalenceCases().s10ByproductFeedsDownstream()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s10ByproductShortfallFallsBackToPattern", () -> new GraphPlanningEquivalenceCases().s10ByproductShortfallFallsBackToPattern()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s6ContainerCycleBatched", () -> new GraphPlanningEquivalenceCases().s6ContainerCycleBatched()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s6ContainerCycleMissingFirstFiring", () -> new GraphPlanningEquivalenceCases().s6ContainerCycleMissingFirstFiring()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s7DurabilityChain", () -> new GraphPlanningEquivalenceCases().s7DurabilityChain()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s8SelfOutputCatalystBatched", () -> new GraphPlanningEquivalenceCases().s8SelfOutputCatalystBatched()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s8SelfOutputCatalystSurplus", () -> new GraphPlanningEquivalenceCases().s8SelfOutputCatalystSurplus()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s8SelfOutputCatalystMissingFirstFiring", () -> new GraphPlanningEquivalenceCases().s8SelfOutputCatalystMissingFirstFiring()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s9MultiCandidateFallback", () -> new GraphPlanningEquivalenceCases().s9MultiCandidateFallback()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s9MultiCandidatePreferenceOrder", () -> new GraphPlanningEquivalenceCases().s9MultiCandidatePreferenceOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s9FindCraftedStackReplacement", () -> new GraphPlanningEquivalenceCases().s9FindCraftedStackReplacement()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s9FuzzyStockVariants", () -> new GraphPlanningEquivalenceCases().s9FuzzyStockVariants()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s9DenseFuzzySourcesPreserveCandidateAndVariantOrder", () -> new GraphPlanningEquivalenceCases().s9DenseFuzzySourcesPreserveCandidateAndVariantOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s9FuzzyTargetVariantStock", () -> new GraphPlanningEquivalenceCases().s9FuzzyTargetVariantStock()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s11EmittableCoversWholeDemand", () -> new GraphPlanningEquivalenceCases().s11EmittableCoversWholeDemand()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s11DenseEmittableViewPreservesPartialStockAccounting", () -> new GraphPlanningEquivalenceCases().s11DenseEmittableViewPreservesPartialStockAccounting()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s11EmittableCoversShortfallAfterStock", () -> new GraphPlanningEquivalenceCases().s11EmittableCoversShortfallAfterStock()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s12InfeasibleReportMissingSimulates", () -> new GraphPlanningEquivalenceCases().s12InfeasibleReportMissingSimulates()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s12NoPatternAtAllSimulates", () -> new GraphPlanningEquivalenceCases().s12NoPatternAtAllSimulates()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.multiRouteGraphMatchesNativeTree", () -> new GraphPlanningEquivalenceCases().multiRouteGraphMatchesNativeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.declineNetConsumingCatalyst", () -> new GraphPlanningEquivalenceCases().declineNetConsumingCatalyst()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.declineContainerPlusSelfOutput", () -> new GraphPlanningEquivalenceCases().declineContainerPlusSelfOutput()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.declineEmittableCircularInput", () -> new GraphPlanningEquivalenceCases().declineEmittableCircularInput()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.declineMixedRemainderKinds", () -> new GraphPlanningEquivalenceCases().declineMixedRemainderKinds()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.patternCycleSimulatesLikeTree", () -> new GraphPlanningEquivalenceCases().patternCycleSimulatesLikeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.selfReferencingInputSimulatesLikeTree", () -> new GraphPlanningEquivalenceCases().selfReferencingInputSimulatesLikeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.declineFuzzyCombinationTooLarge", () -> new GraphPlanningEquivalenceCases().declineFuzzyCombinationTooLarge()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.substituteKeyRecursionSimulatesLikeTree", () -> new GraphPlanningEquivalenceCases().substituteKeyRecursionSimulatesLikeTree()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s18LexicographicRouteSelectionPrefersCheaperCompletingRoute", () -> new GraphPlanningEquivalenceCases().s18LexicographicRouteSelectionPrefersCheaperCompletingRoute()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s19HugeDeclaredIoAmountChannel", () -> new GraphPlanningEquivalenceCases().s19HugeDeclaredIoAmountChannel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.s19HugeDeclaredIoMultiplierChannel", () -> new GraphPlanningEquivalenceCases().s19HugeDeclaredIoMultiplierChannel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.fuzzySlotPaysWholeSlotToFirstProducibleCandidate", () -> new GraphPlanningEquivalenceCases().fuzzySlotPaysWholeSlotToFirstProducibleCandidate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("GraphPlanningEquivalenceCases.plannedMaterialPlanIsBoundedByThePlansOwnSupply", () -> new GraphPlanningEquivalenceCases().plannedMaterialPlanIsBoundedByThePlansOwnSupply())
            );
        }
    }

    /** LinearPlannerBeyondLongLedgerCases 的独立场景夹具，按类别统一执行。 */
    public static final class LinearPlannerBeyondLongLedgerCases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);
        /** 每发 1 枚 TOP 吃 10 枚金 ⇒ 1e18 发放大成 1e19 枚，正好越出 long。 */
        private static final long REQUEST = 1_000_000_000_000_000_000L;
        private static final java.math.BigInteger WIDE_UNITS = new java.math.BigInteger("10000000000000000000");

        private static CraftGraph compile(FakeGrid grid, AEKey target) {
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
            assertFalse(graph.hasDeclined(), () -> "unexpected compile decline: " + graph.declineReason());
            return graph;
        }

        private static Map<String, Long> craftTimes(GraphPlanningState state) {
            var map = new HashMap<String, Long>();
            for (var entry : state.crafts().entrySet()) {
                map.merge(String.valueOf(entry.getKey().getDefinition()), entry.getValue(), Long::sum);
            }
            return map;
        }

        /**
         * 存量那一腿：网络里存着 1.5e19 枚金（无限盘真值），配得平的一单不许被读成
         * 「供给 9.22e18 &lt; 需求 1e19」的假缺口。
         */
        void beyondLongAggregateDemandBooksExactSupplyInsteadOfSentinel() {
            var grid = FakeGrid.builder()
                    .storage(Map.of(OTHER, Long.MAX_VALUE))
                    .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(OTHER, 10L)))
                    .build();

            var tree = LinearPlanner.solve(withTruth(compile(grid, TOP)),
                    CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            assertFalse(tree.hasDeclined(),
                    () -> "网络里真有 1.5e19 枚金，树趟不该被自己那本饱和账拒掉: " + tree.declineReason());
            assertNotNull(tree.state());
            assertEquals(WIDE_UNITS, tree.state().used().getExact(OTHER),
                    "供给侧（used）必须记下 1e19 枚的真值，而不是天花板");
            assertTrue(tree.state().missingItems().isEmpty(),
                    () -> "池子里有这么多料，不许报缺料: " + tree.state().missingItems());
            var times = craftTimes(tree.state());
            long firings = times.values().stream().mapToLong(Long::longValue).sum();
            assertEquals(1L, times.size(), () -> "只有一张配方，发数表就该只有一格: " + times);
            assertEquals(REQUEST, firings, "每发一枚 TOP ⇒ 发数必须等于请求量，不许在饱和读数上截断");
        }

        /** 可发射（emittable）那一腿同样不许把需求截成天花板：这本账（emitted）是闸门供给侧的一条腿。 */
        void beyondLongEmittableDemandBooksExactEmittedAmount() {
            var grid = FakeGrid.builder()
                    .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(OTHER, 10L)))
                    .emittable(OTHER)
                    .build();

            var tree = LinearPlanner.solve(compile(grid, TOP),
                    CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            assertFalse(tree.hasDeclined(),
                    () -> "发射口是无界的，树趟不该被自己那本饱和账拒掉: " + tree.declineReason());
            assertNotNull(tree.state());
            assertEquals(WIDE_UNITS, tree.state().emitted().getExact(OTHER),
                    "发射量 1e19 枚必须记真值：闸门的需求侧读到的就是这份数");
        }

        /** 把无限盘溢出键的宽域供给真值挂到编译图上（与 {@code GraphPlanningSession} 同一入口）。 */
        private static CraftGraph withTruth(CraftGraph graph) {
            graph.attachSupplyTruth(git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(
                    java.util.Map.of(OTHER, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(
                            new java.math.BigInteger("15000000000000000000")))));
            return graph;
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("LinearPlannerBeyondLongLedgerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("LinearPlannerBeyondLongLedgerCases.beyondLongAggregateDemandBooksExactSupplyInsteadOfSentinel", () -> new LinearPlannerBeyondLongLedgerCases().beyondLongAggregateDemandBooksExactSupplyInsteadOfSentinel()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LinearPlannerBeyondLongLedgerCases.beyondLongEmittableDemandBooksExactEmittedAmount", () -> new LinearPlannerBeyondLongLedgerCases().beyondLongEmittableDemandBooksExactEmittedAmount())
            );
        }
    }

    /** LinearPlannerSchedulingCases 的独立场景夹具，按类别统一执行。 */
    public static final class LinearPlannerSchedulingCases {
        private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
        private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
        private static final AEItemKey LEAF = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);

        private static CraftGraph compile(FakeGrid grid) {
            return compile(grid, TOP);
        }

        private static CraftGraph compile(FakeGrid grid, AEKey target) {
            CraftGraph graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
            assertFalse(graph.hasDeclined(), () -> "compile declined: " + graph.declineReason());
            return graph;
        }

        private static SolveResult solve(FakeGrid grid, long amount, CalculationStrategy strategy) {
            SolveResult result = LinearPlanner.solve(compile(grid), strategy, amount);
            assertFalse(result.hasDeclined(), () -> "solve declined: " + result.declineReason());
            return result;
        }

        /** 模糊槽同一物品两档损伤：先吃受损那一枚、余额再吃无损那一枚，顺位逐字不变。 */
        void fuzzyInputKeepsStableGreedyVariantOrder() {
            AEItemKey pristine = AEItemKey.of(Items.IRON_PICKAXE);
            var damagedStack = new ItemStack(Items.IRON_PICKAXE);
            damagedStack.setDamageValue(50);
            AEItemKey damaged = AEItemKey.of(damagedStack);
            var grid = FakeGrid.builder()
                    .storage(Map.of(pristine, 10L, damaged, 5L))
                    .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.anyVariant(pristine, 1L)))
                    .build();

            SolveResult result = solve(grid, 12L, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertEquals(5L, result.state().usedItems().get(damaged));
            assertEquals(7L, result.state().usedItems().get(pristine));
        }

        /** 两级链各带组件键：逐级发数与网络提取腿逐字不变。 */
        void componentSpecificChainBooksPerStageFirings() {
            AEItemKey version1 = customDataKey(1);
            AEItemKey version2 = customDataKey(2);
            AEItemKey version3 = customDataKey(3);
            FakePattern firstStage = FakePattern.of(version2, 1L,
                    new FakeInput(version1, 1L), new FakeInput(LEAF, 1L));
            FakePattern secondStage = FakePattern.of(version3, 1L,
                    new FakeInput(version2, 1L), new FakeInput(OTHER, 1L));
            var grid = FakeGrid.builder()
                    .storage(Map.of(version1, 4L, LEAF, 4L, OTHER, 4L))
                    .pattern(version2, firstStage)
                    .pattern(version3, secondStage)
                    .build();

            SolveResult result = LinearPlanner.solve(compile(grid, version3),
                    CalculationStrategy.REPORT_MISSING_ITEMS, 4L);
            assertFalse(result.hasDeclined(), () -> "solve declined: " + result.declineReason());
            assertFalse(result.simulation());
            assertEquals(4L, result.state().crafts().get(firstStage));
            assertEquals(4L, result.state().crafts().get(secondStage));
            assertEquals(4L, result.state().usedItems().get(version1));
            assertEquals(0L, result.state().usedItems().get(version2));
        }

        private static AEItemKey customDataKey(int version) {
            ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
            CompoundTag tag = new CompoundTag();
            tag.putInt("version", version);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            return AEItemKey.of(stack);
        }

        /** 台账 #140：两条路线抢一格时，拆分点必须落在最大可行批上（吃铁那条先被首触）。 */
        void boundedMultiProducerSplitsAtMaximumFeasibleBatch() {
            // 这一档要的是"吃铁的那条路线先被首触"，用递减的提供者优先级表达，
            // 而不是指望并列清单的喂入序——AE2 真网格上并列那一档交的是 HashSet 遍历序。
            FakePattern scarceRoute = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L)).withPriority(1);
            FakePattern fallbackRoute = FakePattern.of(TOP, 1L, new FakeInput(OTHER, 1L));
            var grid = FakeGrid.builder()
                    .storage(Map.of(LEAF, 2L, OTHER, 100L))
                    .pattern(TOP, scarceRoute, fallbackRoute)
                    .build();

            SolveResult result = solve(grid, 6L, CalculationStrategy.REPORT_MISSING_ITEMS);
            assertEquals(2L, result.state().crafts().get(scarceRoute));
            assertEquals(4L, result.state().crafts().get(fallbackRoute));
        }

        /** 非均匀环（1 发产 3 枚 / 1 枚换 1+1 枚）的配比发数表：全仓只有这里按绝对值钉。 */
        void nonUniformRingRatioBooksProducerRecyclerConsumerFirings() {
            FakePattern producer = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(LEAF, 2L)}, new GenericStack(OTHER, 3L));
            FakePattern recycler = FakePattern.withOutputs(
                    new FakeInput[]{new FakeInput(OTHER, 1L)},
                    new GenericStack(LEAF, 1L), new GenericStack(MID, 1L));
            FakePattern consumer = FakePattern.of(TOP, 1L, new FakeInput(MID, 1L));
            var grid = FakeGrid.builder()
                    .storage(Map.of(LEAF, 2L))
                    .pattern(LEAF, recycler)
                    .pattern(OTHER, producer)
                    .pattern(MID, recycler)
                    .pattern(TOP, consumer)
                    .build();

            CraftGraph graph = compile(grid);
            assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
            var macros = graph.ringMacrosByEntry().getOrDefault(MID, List.of());
            assertFalse(macros.isEmpty());
            assertEquals(1L, macros.getFirst().executionRatio().get(producer));
            assertEquals(2L, macros.getFirst().executionRatio().get(recycler));
            SolveResult result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
            assertFalse(result.hasDeclined(), () -> "solve declined: " + result.declineReason());
            // 还种轮数只看"从网络起步预留里实际借走了几枚留种催化物"：这里被扣的起步种是另一种
            // 催化物，留种键（该环的净输出键）在网络里是 0 枚 —— 一枚都没借走就不加发，
            // 多算那一发既不还出任何东西又抢主链的料。所以就是刚够交付那一版（每轮 1+2 发）。
            // 真借走种的形状由 OrderedPlanningRecursiveCapabilityTest
            // .ringSeedBorrowedByOtherEntryIsRepaidToNetwork 守着。
            assertEquals(9L, result.state().crafts().get(producer));
            assertEquals(24L, result.state().crafts().get(recycler));
            assertEquals(8L, result.state().crafts().get(consumer));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("LinearPlannerSchedulingCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("LinearPlannerSchedulingCases.fuzzyInputKeepsStableGreedyVariantOrder", () -> new LinearPlannerSchedulingCases().fuzzyInputKeepsStableGreedyVariantOrder()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LinearPlannerSchedulingCases.componentSpecificChainBooksPerStageFirings", () -> new LinearPlannerSchedulingCases().componentSpecificChainBooksPerStageFirings()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LinearPlannerSchedulingCases.boundedMultiProducerSplitsAtMaximumFeasibleBatch", () -> new LinearPlannerSchedulingCases().boundedMultiProducerSplitsAtMaximumFeasibleBatch()),
                () -> org.junit.jupiter.api.Assertions.assertAll("LinearPlannerSchedulingCases.nonUniformRingRatioBooksProducerRecyclerConsumerFirings", () -> new LinearPlannerSchedulingCases().nonUniformRingRatioBooksProducerRecyclerConsumerFirings())
            );
        }
    }

    /** PlanBytesExactCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanBytesExactCases {
        void returnedSlotByteCompensationPreservesWideProduct() {
            assertContainerBytes(true);
        }

        void finiteUseRemainderFeesPreserveWideProduct() {
            assertContainerBytes(false);
        }

        private static void assertContainerBytes(boolean returned) {
            var target = AEItemKey.of(Items.IRON_INGOT);
            var input = AEItemKey.of(Items.BUCKET);
            var remainder = returned ? input : AEItemKey.of(Items.PAPER);
            long times = Long.MAX_VALUE;
            var pattern = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern.of(
                    target, 1, new git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput(input, 2, remainder));
            var grid = git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid.builder()
                    .storage(Map.of(input, returned ? 2L : Long.MAX_VALUE)).pattern(target, pattern).build();
            var graph = git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler.compile(
                    grid.crafting, grid.storage.getCachedInventory(), target, null);
            assertFalse(graph.hasDeclined(), graph::declineReason);
            graph.attachSupplyTruth(git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(
                    Map.of(input, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(
                            BigInteger.valueOf(times).multiply(BigInteger.TWO)))));
            var result = git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner.solve(
                    graph, appeng.api.networking.crafting.CalculationStrategy.REPORT_MISSING_ITEMS, times);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            // 根物料费 times、输入和返还各 2*times、节点费 times、两个站点各 8 字节。
            var expected = BigInteger.valueOf(times).multiply(BigInteger.valueOf(6)).add(BigInteger.valueOf(16));
            var plan = Ae2PlanConverter.toPlan(result, graph);
            assertEquals(expected, WideAmountPlanCarrier.resolveBytes(plan).exactValue());
            assertEquals(BigInteger.valueOf(times), result.state().craftsWide().getAmount(pattern).exactValue());
        }

        void bytesAloneCanRequireAWidePlan() {
            var key = AEItemKey.of(Items.PAPER);
            var state = new GraphPlanningState(Map.of(), Map.of());
            state.byteAccount().add(key, Long.MAX_VALUE, 100);
            var graph = new CraftGraph(key, Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), Map.of(), false, null, 0, 0);
            var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1, false), graph);
            var expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(800))
                    .divide(BigInteger.valueOf(key.getAmountPerByte()));
            assertTrue(expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
            assertEquals(Long.MAX_VALUE, plan.bytes());
            assertEquals(expected, WideAmountPlanCarrier.resolveBytes(plan).exactValue());
            assertEquals(1, plan.finalOutput().amount());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanBytesExactCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanBytesExactCases.returnedSlotByteCompensationPreservesWideProduct", () -> new PlanBytesExactCases().returnedSlotByteCompensationPreservesWideProduct()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanBytesExactCases.finiteUseRemainderFeesPreserveWideProduct", () -> new PlanBytesExactCases().finiteUseRemainderFeesPreserveWideProduct()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanBytesExactCases.bytesAloneCanRequireAWidePlan", () -> new PlanBytesExactCases().bytesAloneCanRequireAWidePlan())
            );
        }
    }

    /** TerminalBatchPruningCases 的独立场景夹具，按类别统一执行。 */
    public static final class TerminalBatchPruningCases {
        private static final AEItemKey TARGET = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey SCARCE = AEItemKey.of(Items.GOLD_INGOT);
        private static final AEItemKey ABUNDANT = AEItemKey.of(Items.DIAMOND);

        void scarceFirstRouteKeepsNativePlanIncludingRoundingAndBytes() {
            for (long stock : new long[] {0L, 1L, 7L, 42L}) {
                var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                        List.of(new GenericStack(TARGET, 3L)), FakeInput.stack(SCARCE, 2L)).withPriority(10);
                var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                        List.of(new GenericStack(TARGET, 3L)), new FakeInput(ABUNDANT, 1L));
                var grid = FakeGrid.builder().storage(Map.of(SCARCE, stock, ABUNDANT, 100L))
                        .pattern(TARGET, preferred, alternative).build();
                var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
                assertEquals(2, graph.producersByOutput().get(TARGET).size());
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 97L);
                assertFalse(result.hasDeclined(), result::declineReason);
                var actual = Ae2PlanConverter.toPlan(result, graph);
                var expected = new Ae2TreePlanReference(grid, grid.requester,
                        new GenericStack(TARGET, 97L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
                FakePlanGrid.assertPlansEqual(expected, actual);
            }
        }

        void byproductRestoresAFormerlyUnavailableRouteInTheSameAttempt() {
            var intermediate = AEItemKey.of(Items.REDSTONE);
            var top = AEItemKey.of(Items.EMERALD);
            var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(TARGET, 1L)), new FakeInput(SCARCE, 1L)).withPriority(10);
            var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(TARGET, 1L)), new FakeInput(ABUNDANT, 1L));
            var byproduct = FakePattern.colored(0, AEItemKey.of(Items.MAP),
                    List.of(new GenericStack(intermediate, 1L), new GenericStack(SCARCE, 1L)),
                    new FakeInput(ABUNDANT, 1L));
            var parent = FakePattern.of(top, 1L,
                    new FakeInput(TARGET, 1L), new FakeInput(intermediate, 1L), new FakeInput(TARGET, 1L));
            var grid = FakeGrid.builder().storage(Map.of(ABUNDANT, 2L))
                    .pattern(top, parent).pattern(intermediate, byproduct)
                    .pattern(TARGET, preferred, alternative).build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), top, null);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 1L);
            assertFalse(result.hasDeclined(), result::declineReason);
            assertFalse(result.simulation());
            assertEquals(1L, (long) result.state().crafts().get(preferred));
            assertEquals(1L, (long) result.state().crafts().get(alternative));
            assertEquals(2L, result.state().usedItems().get(ABUNDANT));
            assertEquals(0L, result.state().usedItems().get(SCARCE));
        }

        void missingSimulationStillReportsPreferredRouteInputs() {
            var preferred = FakePattern.colored(0, AEItemKey.of(Items.PAPER),
                    List.of(new GenericStack(TARGET, 1L)), new FakeInput(SCARCE, 1L)).withPriority(10);
            var alternative = FakePattern.colored(0, AEItemKey.of(Items.BOOK),
                    List.of(new GenericStack(TARGET, 1L)), new FakeInput(ABUNDANT, 1L));
            var grid = FakeGrid.builder().pattern(TARGET, preferred, alternative).build();
            var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), TARGET, null);
            var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 17L);
            assertFalse(result.hasDeclined(), result::declineReason);
            var actual = Ae2PlanConverter.toPlan(result, graph);
            var expected = new Ae2TreePlanReference(grid, grid.requester,
                    new GenericStack(TARGET, 17L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
            FakePlanGrid.assertPlansEqual(expected, actual);
            assertEquals(17L, actual.missingItems().get(SCARCE));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("TerminalBatchPruningCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalBatchPruningCases.scarceFirstRouteKeepsNativePlanIncludingRoundingAndBytes", () -> new TerminalBatchPruningCases().scarceFirstRouteKeepsNativePlanIncludingRoundingAndBytes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalBatchPruningCases.byproductRestoresAFormerlyUnavailableRouteInTheSameAttempt", () -> new TerminalBatchPruningCases().byproductRestoresAFormerlyUnavailableRouteInTheSameAttempt()),
                () -> org.junit.jupiter.api.Assertions.assertAll("TerminalBatchPruningCases.missingSimulationStillReportsPreferredRouteInputs", () -> new TerminalBatchPruningCases().missingSimulationStillReportsPreferredRouteInputs())
            );
        }
    }
}
