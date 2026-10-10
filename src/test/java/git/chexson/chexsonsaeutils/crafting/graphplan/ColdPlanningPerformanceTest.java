package git.chexson.chexsonsaeutils.crafting.graphplan;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冷图度量：每次完整编译新图并求解、转换，不预热、不复用图编译缓存。 */
@Tag("footprint")
class ColdPlanningPerformanceTest {
    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
