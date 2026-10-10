package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.networking.crafting.CalculationStrategy;
import git.chexson.chexsonsaeutils.crafting.graphplan.aggregate.AggregateSolver;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 回放实机捕获的规划请求，量出真实 modpack 规模下的编译 + 求解耗时。
 *
 * <p>用法：游戏侧把 {@code graphPlanCapture} 打开跑一次目标请求，把生成的
 * {@code logs/chexsonsaeutils-plan-capture-*.json} 放进
 * {@code src/test/resources/plan-captures/}，本测试即自动回放。目录为空时跳过，
 * 所以夹具不入库也不会让测试套变红。
 */
final class CapturedPlanBenchmarkTest {

    private static final Path CAPTURE_DIR = Path.of("src", "test", "resources", "plan-captures");
    private static final int ROUNDS = 6;
    private static final int WARMUP_ROUNDS = 2;
    private static final int PROFILE_ROUNDS = 8;

    private static List<Path> captures() throws IOException {
        if (!Files.isDirectory(CAPTURE_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(CAPTURE_DIR)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    @Test
    void replayCapturedPlans() throws IOException {
        var captures = captures();
        Assumptions.assumeFalse(captures.isEmpty(),
                () -> "no capture fixtures in " + CAPTURE_DIR.toAbsolutePath()
                        + " (enable graphPlanCapture in game, then drop the JSON here)");

        for (Path capture : captures) {
            var loaded = CapturedPlanFixture.load(capture);
            // 全会话分段必须是本 JVM 的第一次调用——它一次给出
            // compile / solve / convert / retention 四段的**冷**读数，与实机
            // `Graph planning handled` 那行同口径，所以放在最前面。
            sessionPhases(loaded, capture.getFileName().toString());

            var compileTimes = new ArrayList<Long>();
            var solveTimes = new ArrayList<Long>();
            String declineReason = null;
            boolean simulated = false;
            int crafts = 0;
            double bytes = 0;

            for (int round = 0; round < ROUNDS; round++) {
                System.gc();
                long compileStart = System.nanoTime();
                var graph = GraphCompiler.compile(
                        loaded.craftingService(), loaded.stock(), loaded.target(), null);
                long compileNanos = System.nanoTime() - compileStart;
                assertFalse(graph.hasDeclined(),
                        () -> capture.getFileName() + ": compile declined: " + graph.declineReason());

                long solveStart = System.nanoTime();
                var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, loaded.amount());
                long solveNanos = System.nanoTime() - solveStart;

                if (round >= WARMUP_ROUNDS) {
                    compileTimes.add(compileNanos);
                    solveTimes.add(solveNanos);
                }
                declineReason = result.declineReason();
                if (declineReason == null) {
                    simulated = result.simulation();
                    crafts = result.state().crafts().size();
                    bytes = result.state().bytes();
                }
            }

            System.out.printf(
                    "[replay] %s keys=%d patterns=%d amount=%d"
                            + " warm(compileMedian=%.1f solveMedian=%.1f solveBest=%.1f) decline=%s"
                            + " simulate=%s crafts=%d bytes=%.4g%n",
                    capture.getFileName(), loaded.keyCount(), loaded.patternCount(),
                    loaded.amount(),
                    median(compileTimes) / 1e6, median(solveTimes) / 1e6,
                    min(solveTimes) / 1e6, declineReason, simulated, crafts, bytes);

            // 聚合路径量级 + 不受理原因（指出下一个该放宽的门）。冷读数是本 JVM 里
            // 聚合核的首次调用。
            var aggGraph = GraphCompiler.compile(
                    loaded.craftingService(), loaded.stock(), loaded.target(), null);
            long aggColdStart = System.nanoTime();
            String aggDecline = AggregateSolver.solve(aggGraph, CalculationStrategy.REPORT_MISSING_ITEMS,
                    loaded.amount(), null).declineReason();
            long aggCold = System.nanoTime() - aggColdStart;
            long aggBest = Long.MAX_VALUE;
            for (int round = 0; round < ROUNDS; round++) {
                System.gc();
                long start = System.nanoTime();
                var aggResult = AggregateSolver.solve(aggGraph, CalculationStrategy.REPORT_MISSING_ITEMS,
                        loaded.amount(), null);
                long nanos = System.nanoTime() - start;
                aggDecline = aggResult.declineReason();
                if (round >= WARMUP_ROUNDS) {
                    aggBest = Math.min(aggBest, nanos);
                }
            }
            System.out.printf("[aggregate] %s cold=%.2f ms warmBest=%.2f ms decline=%s%n",
                    capture.getFileName(), aggCold / 1e6, aggBest / 1e6, aggDecline);

            profile(loaded, capture.getFileName().toString());
        }
    }

    /**
     * 走完整 {@link GraphPlanningEngine#createSession} → {@code solve} 路径，把
     * 预算的四段（compile / solve / convert / retention）与总耗时报出来——与实机
     * {@code Graph planning handled} 那行同口径。
     *
     * <p>此前基准只直接调 {@code LinearPlanner} / {@code AggregateSolver}，
     * retention 与 convert 结构上测不到，所以那两段的改动一直只能靠实机验。
     * 会话基准直接运行 graph-owned retention 阶段。
     *
     * <p>冷读数是本方法第一次调用（会话层的类此前未加载过）；随后取暖机最优。
     */
    private static void sessionPhases(CapturedPlanFixture.Loaded loaded, String label) {
        long[] cold = runSession(loaded, true);
        long[] best = null;
        for (int round = 0; round < ROUNDS; round++) {
            System.gc();
            long[] phases = runSession(loaded);
            if (round >= WARMUP_ROUNDS && (best == null || phases[4] < best[4])) {
                best = phases;
            }
        }
        System.out.printf("[session] %s cold(total=%.1f compile=%.1f solve=%.1f convert=%.2f retention=%.2f)"
                        + " warm(total=%.1f compile=%.1f solve=%.1f convert=%.2f retention=%.2f)%n",
                label, cold[4] / 1e6, cold[0] / 1e6, cold[1] / 1e6, cold[2] / 1e6, cold[3] / 1e6,
                best[4] / 1e6, best[0] / 1e6, best[1] / 1e6, best[2] / 1e6, best[3] / 1e6);
    }

    /** @return {compileNanos, solveNanos, convertNanos, retentionNanos, totalNanos} */
    private static long[] runSession(CapturedPlanFixture.Loaded loaded) {
        return runSession(loaded, false);
    }

    private static long[] runSession(CapturedPlanFixture.Loaded loaded, boolean dumpDiagnostics) {
        if (dumpDiagnostics) {
            GraphDiagnostics.begin(false);
        }
        try {
            return runSessionInner(loaded);
        } finally {
            if (dumpDiagnostics) {
                GraphDiagnostics.dumpAndClear("[coldSession]");
                GraphDiagnostics.end();
            }
        }
    }

    private static long[] runSessionInner(CapturedPlanFixture.Loaded loaded) {
        var budget = new GraphPlanningBudget(0L);
        long start = System.nanoTime();
        var session = GraphPlanningSession.create(loaded.craftingService(), loaded.stock(), loaded.target(),
                CalculationStrategy.REPORT_MISSING_ITEMS, budget, null, true);
        session.solve(loaded.amount());
        long total = System.nanoTime() - start;
        return new long[] {
                budget.phaseNanos(GraphPlanningBudget.Phase.COMPILE),
                budget.phaseNanos(GraphPlanningBudget.Phase.SOLVE),
                budget.phaseNanos(GraphPlanningBudget.Phase.CONVERT)
                        + budget.currentPhaseNanos(GraphPlanningBudget.Phase.CONVERT),
                budget.phaseNanos(GraphPlanningBudget.Phase.RETENTION),
                total };
    }

    /** 再跑一轮，用采样剖析器指出热点方法（读数用于定位，不作断言）。 */
    private static void profile(CapturedPlanFixture.Loaded loaded, String label) {
        var graph = GraphCompiler.compile(
                loaded.craftingService(), loaded.stock(), loaded.target(), null);
        try (var sampler = new StackSampler(Thread.currentThread(), 1L)) {
            for (int round = 0; round < PROFILE_ROUNDS; round++) {
                LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, loaded.amount());
            }
            sampler.report("[profile] " + label, 14);
        }
    }

    private static long median(List<Long> samples) {
        var sorted = new ArrayList<>(samples);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static long min(List<Long> samples) {
        long best = Long.MAX_VALUE;
        for (long sample : samples) {
            best = Math.min(best, sample);
        }
        return best;
    }
}
