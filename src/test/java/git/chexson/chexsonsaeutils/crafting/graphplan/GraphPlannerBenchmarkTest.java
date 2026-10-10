package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宽深链压力基准：复现 modpack 里 42 万站点规模的站点树爆炸，用来量化求解器
 * 的绝对耗时（modpack 实测 solve 939-1385 ms，目标 &lt; 80 ms）。
 *
 * <p>拓扑：每层 2 个键，每个键 2 个产出样板，每个样板消耗下一层的两个键
 * （所以被点火的样板必然向下分叉 ×2，站点树 ≈ 2^depth）加一个叶子键；
 * 每个键还多挂一个备用样板让站点进入 multiBranch 逐批路径。底层键与全部叶子
 * 键库存海量，求解必然成功、必然走满整棵树。
 */
final class GraphPlannerBenchmarkTest {

    /** 被点火站点数 ≈ 2^(DEPTH+1)，materialized 站点再乘每站点路线槽数。 */
    private static final int DEPTH = 15;
    private static final long REQUEST = 64L;
    private static final long STOCK = 1L << 55;
    private static final int WIDTH = 2;
    private static final int ROUNDS = 10;
    private static final int WARMUP_ROUNDS = 4;
    /** 宽松上限：只为让「满批点火退化」这类量级回归响亮失败，不是性能指标。 */
    private static final long CEILING_MILLIS = 1_500L;

    private static List<AEItemKey> distinctKeys(int count) {
        var keys = new ArrayList<AEItemKey>(count);
        for (Item item : BuiltInRegistries.ITEM) {
            var key = AEItemKey.of(item);
            if (key != null) {
                keys.add(key);
                if (keys.size() == count) {
                    return keys;
                }
            }
        }
        throw new IllegalStateException("not enough distinct items: " + keys.size());
    }

    private record Chain(FakeGrid grid, AEKey target) {
    }

    private static Chain buildChain(int depth) {
        int levels = depth + 1;
        var keys = distinctKeys(levels * WIDTH + depth);
        // levels × WIDTH 层键 + 每层一个叶子键
        var levelKeys = new ArrayList<List<AEItemKey>>(levels);
        for (int level = 0; level < levels; level++) {
            levelKeys.add(keys.subList(level * WIDTH, (level + 1) * WIDTH));
        }
        var leaves = keys.subList(levels * WIDTH, levels * WIDTH + depth);

        var builder = FakeGrid.builder();
        for (int level = 0; level < depth; level++) {
            var children = levelKeys.get(level + 1);
            AEItemKey leaf = leaves.get(level);
            for (AEItemKey output : levelKeys.get(level)) {
                builder.pattern(output,
                        FakePattern.of(output, 1L,
                                new FakeInput(children.get(0), 2L),
                                new FakeInput(children.get(1), 1L),
                                new FakeInput(leaf, 1L)),
                        FakePattern.of(output, 1L,
                                new FakeInput(children.get(0), 1L),
                                new FakeInput(children.get(1), 3L),
                                new FakeInput(leaf, 2L)));
            }
        }
        Map<AEKey, Long> stock = new HashMap<>();
        for (AEItemKey bottom : levelKeys.get(depth)) {
            stock.put(bottom, STOCK);
        }
        for (AEItemKey leaf : leaves) {
            stock.put(leaf, STOCK);
        }
        builder.storage(stock);
        return new Chain(builder.build(), levelKeys.get(0).get(0));
    }

    @Test
    void wideDeepChainSolvesFast() {
        var chain = buildChain(DEPTH);
        var grid = chain.grid();
        AEKey target = chain.target();

        long compileStart = System.nanoTime();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        long compileNanos = System.nanoTime() - compileStart;
        assertFalse(graph.hasDeclined(), () -> "unexpected decline: " + graph.declineReason());

        // 站点树按 attempt 复用，所以每轮都重新编图 + 重新求解，量的是完整一次冷求解；
        // 前若干轮只为 JIT 预热，正式读数取多轮最小值。
        long best = Long.MAX_VALUE;
        var measured = new java.util.ArrayList<Long>();
        double bytes = 0;
        int crafts = 0;
        for (int round = 0; round < ROUNDS; round++) {
            var roundGraph = GraphCompiler.compile(
                    grid.crafting, grid.storage.getCachedInventory(), target, null);
            // 每轮先回收上一轮的站点树垃圾，否则 GC 落在计时窗口里读数会飘
            System.gc();
            long solveStart = System.nanoTime();
            var result = LinearPlanner.solve(roundGraph, CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
            long solveNanos = System.nanoTime() - solveStart;
            assertFalse(result.hasDeclined(), () -> "unexpected decline: " + result.declineReason());
            if (round >= WARMUP_ROUNDS) {
                measured.add(solveNanos);
                if (solveNanos < best) {
                    best = solveNanos;
                    bytes = result.state().bytes();
                    crafts = result.state().crafts().size();
                }
            }
        }
        measured.sort(null);
        long median = measured.get(measured.size() / 2);

        System.out.printf(
                "[bench] depth=%d request=%d compile=%.1f ms solveBest=%.1f ms solveMedian=%.1f ms crafts=%d bytes=%.4g%n",
                DEPTH, REQUEST, compileNanos / 1e6, best / 1e6, median / 1e6, crafts, bytes);
        // 回归护栏：满批点火退化回「batch=1 几何增长」时同一图会把子树重走
        // log2(需求量) 遍，本规模下直接把堆吃穿；宽松上限只为让退化响亮失败。
        assertTrue(median < CEILING_MILLIS * 1_000_000L,
                () -> "graph solve regressed: median " + (median / 1e6) + " ms");
    }
}
