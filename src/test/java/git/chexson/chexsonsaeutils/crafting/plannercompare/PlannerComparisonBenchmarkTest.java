package git.chexson.chexsonsaeutils.crafting.plannercompare;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import git.chexson.chexsonsaeutils.crafting.graphplan.CapturedPlanFixture;
import git.chexson.chexsonsaeutils.crafting.graphplan.aggregate.AggregateSolver;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.RetentionApplier;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.search.ResidualBestFirstPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.graphplan.vm.TransactionalDemandVm;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.SelectedInputsPlan;
import git.chexson.chexsonsaeutils.crafting.solve.LegacyFrozenRouteReplay;
import git.chexson.chexsonsaeutils.crafting.solve.OrderedPlanningReplay;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningAlgorithmId;
import git.chexson.chexsonsaeutils.crafting.speedplan.FrozenRoutePlanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对同一份 CapturedPlanFixture 统一回放所有规划算法。
 *
 * <p>每个算法都执行一次冷编译/冷求解，再共享其编译产物执行相同数量的 warmup 和 sample。
 * 输出以 {@code PLANNER_COMPARE_CSV} / {@code PLANNER_COMPARE_JSON} 开头，便于日志采集器逐行读取。
 */
final class PlannerComparisonBenchmarkTest {
    private static final Path CAPTURE_DIR = Path.of("src", "test", "resources", "plan-captures");
    private static final String RECURSIVE_CAPTURE = "chexsonsaeutils-plan-capture-recursive-ring.json";
    private static final String REFERENCE_PLANNER = "LinearPlanner";
    private static final String BASELINE_CURRENT_PLANNER = "AggregateSolver";
    private static final String OPTIMIZED_CURRENT_PLANNER = REFERENCE_PLANNER;
    private static final String NEW_RESIDUAL_PLANNER = "ResidualBestFirstPlanner";
    private static final String NEW_VM_PLANNER = "TransactionalDemandVm";
    private static final int WARMUP = positiveProperty("plannerCompare.warmup", 2);
    private static final int SAMPLES = positiveProperty("plannerCompare.samples", 8);
    private static final boolean RETENTION_EVIDENCE = Boolean.getBoolean("plannerCompare.retentionEvidence");
    private static final String CSV_HEADER = "capture,planner,measurement_scope,available,complete,semantic_correct,eligible,"
            + "correctness,cold_compile_ns,cold_solve_ns,cold_total_ns,warm_best_ns,warm_p50_ns,"
            + "warm_p95_ns,warm_max_ns,completed_samples,failed_samples,failure_rate,decline,digest,"
            + "reference_digest,ring_sccs,colored_patterns,remainder_inputs,multi_route_keys,fuzzy_inputs,error";
    private static volatile int blackhole;

    @Test
    void failedSamplesDoNotContributeToPercentiles() {
        long[] timings = { 11L, 0L, 31L, 0L, 21L };

        TimingStatistics statistics = timingStatistics(timings);

        assertEquals(11L, statistics.best());
        assertEquals(21L, statistics.p50());
        assertEquals(31L, statistics.p95());
        assertEquals(31L, statistics.max());
    }

    @Test
    void compareCapturedPlans() throws Exception {
        List<Path> captures = captures();
        Assumptions.assumeFalse(captures.isEmpty(), "no JSON captures in " + CAPTURE_DIR.toAbsolutePath());
        System.out.println("PLANNER_COMPARE_CSV " + CSV_HEADER);

        for (Path capture : captures) {
            CapturedPlanFixture.Loaded fixture = CapturedPlanFixture.load(capture);
            CaptureFeatures features = CaptureFeatures.read(capture);
            features.verify();
            if (capture.getFileName().toString().equals(RECURSIVE_CAPTURE)) {
                verifyIndependentRecursiveReplay(fixture);
            }
            SemanticIndex semantics = SemanticIndex.build(fixture);
            CraftGraph validationGraph = GraphCompiler.compile(
                    fixture.craftingService(), fixture.stock(), fixture.target(), null);
            printFeatureLine(capture, fixture, features);

            List<PlannerAdapter> adapters = adapters();
            List<NamedResult> results = benchmarkInterleaved(
                    adapters, fixture, semantics, validationGraph, capture.getFileName().toString().hashCode());
            BenchmarkResult reference = results.stream()
                    .filter(result -> result.name().equals(REFERENCE_PLANNER))
                    .map(NamedResult::result)
                    .findFirst()
                    .orElseThrow();
            assertTrue(reference.complete(), () -> "reference plan is incomplete: " + reference.error());
            assertTrue(reference.decline() == null, () -> "reference planner declined: " + reference.decline());
            assertTrue(reference.validationValid(), () -> "reference validation failed: "
                    + reference.validationReason());
            assertNotNull(reference.digest(), "reference digest");
            assertNotNull(reference.portableDigest(), "reference portable digest");

            String referenceDigest = reference.digest();
            String referencePortableDigest = reference.portableDigest();
            String referenceCanonical = reference.canonical();
            String captureName = capture.getFileName().toString();
            for (NamedResult named : results) {
                named.adapter().printEvidence(captureName);
                printResult(captureName, named.name(), named.result(), referenceDigest,
                        referencePortableDigest, features);
                if (named.result().complete()
                        && named.result().digest() != null
                        && !referenceDigest.equals(named.result().digest())) {
                    System.out.printf(Locale.ROOT,
                            "PLANNER_RAW_DIFF_JSON {\"capture\":\"%s\",\"planner\":\"%s\","
                                    + "\"differences\":\"%s\"}%n",
                            json(captureName), json(named.name()),
                            json(differences(referenceCanonical, named.result().canonical())));
                }
                assertTrue(!named.result().available() || named.result().error() == null,
                        () -> named.name() + " failed: " + named.result().error());
            }
            List<NamedResult> preparedEligible = results.stream()
                    .filter(result -> result.adapter().scope() == MeasurementScope.PREPARED_SOLVE)
                    .filter(result -> result.result().eligible(referencePortableDigest))
                    .sorted(Comparator.comparingLong(result -> result.result().warmP50Ns()))
                    .toList();
            assertTrue(!preparedEligible.isEmpty(),
                    "no semantically correct prepared planner is eligible for ranking");
            printRanking(captureName, preparedEligible, referenceDigest, referencePortableDigest);
            printCurrentOptimization(captureName, results, referencePortableDigest);
            verifyNeoEcoPerformance(captureName, preparedEligible);
            List<NamedResult> entryResults = results.stream()
                    .filter(result -> result.adapter().scope() == MeasurementScope.END_TO_END_ENTRY)
                    .toList();
            printEntryMetrics(captureName, entryResults, referencePortableDigest);
            verifyReplacementSelection(captureName, entryResults, referencePortableDigest);
        }
        assertTrue(blackhole != Integer.MIN_VALUE);
    }

    private static List<PlannerAdapter> adapters() {
        return List.of(
                new GraphAdapter(REFERENCE_PLANNER, graph -> LinearPlanner.solve(
                        graph.graph(), CalculationStrategy.REPORT_MISSING_ITEMS, graph.requestedAmount())),
                new GraphAdapter("AggregateSolver", graph -> AggregateSolver.solve(
                        graph.graph(), CalculationStrategy.REPORT_MISSING_ITEMS, graph.requestedAmount(), null)),
                new FrozenAdapter(),
                new VmAdapter(),
                new ResidualAdapter(),
                new NeoEcoAdapter(),
                new LegacyFrozenRouteAdapter(),
                new OrderedAdapter(PlanningAlgorithmId.FROZEN_ROUTE),
                new OrderedAdapter(PlanningAlgorithmId.GRAPH),
                new OrderedAdapter(PlanningAlgorithmId.FAST),
                new OrderedAdapter(PlanningAlgorithmId.NATIVE));
    }

    private static void verifyIndependentRecursiveReplay(CapturedPlanFixture.Loaded fixture) {
        CraftGraph graph = GraphCompiler.compile(
                fixture.craftingService(), fixture.stock(), fixture.target(), null);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind(),
                "recursive capture cycle kind");
        assertTrue(graph.ringMacrosByEntry().values().stream().anyMatch(macros -> !macros.isEmpty()),
                "recursive capture must compile at least one RingMacro");

        verifyIndependentRecursiveResult("ResidualBestFirstPlanner", graph,
                ResidualBestFirstPlanner.solve(
                        graph, CalculationStrategy.REPORT_MISSING_ITEMS, fixture.amount()));
        verifyIndependentRecursiveResult("TransactionalDemandVm", graph,
                TransactionalDemandVm.compile(graph).solve(
                        CalculationStrategy.REPORT_MISSING_ITEMS, fixture.amount()));
    }

    private static void verifyIndependentRecursiveResult(String planner, CraftGraph graph, SolveResult result) {
        assertTrue(!result.hasDeclined(), () -> planner + " declined: " + result.declineReason());
        assertTrue(!result.simulation(), planner + " returned simulation");
        assertTrue(result.state().missingItems().isEmpty(), planner + " returned missing items");
        assertTrue(!result.state().ringExtractions().isEmpty(),
                planner + " did not execute its RingMacro path");
        ICraftingPlan plan = Ae2PlanConverter.toPlan(result, graph);
        assertTrue(plan instanceof RecursivePlanContract,
                planner + " did not preserve recursive plan metadata");
    }

    private static List<NamedResult> benchmarkInterleaved(List<PlannerAdapter> adapters,
            CapturedPlanFixture.Loaded fixture, SemanticIndex semantics, CraftGraph validationGraph,
            int rotationSeed) {
        List<BenchmarkRun> runs = new ArrayList<>(adapters.size());
        for (PlannerAdapter adapter : adapters) {
            adapter.prepareValidation(validationGraph);
            runs.add(new BenchmarkRun(adapter, fixture, semantics));
        }
        runRotated(runs, rotationSeed, run -> run.prepare(validationGraph));

        for (int round = 0; round < WARMUP; round++) {
            runRotated(runs, rotationSeed + 1 + round, BenchmarkRun::warmup);
        }
        for (int sample = 0; sample < SAMPLES; sample++) {
            int sampleIndex = sample;
            runRotated(runs, rotationSeed + 1 + WARMUP + sample,
                    run -> run.sample(sampleIndex));
        }

        List<NamedResult> results = new ArrayList<>(runs.size());
        for (BenchmarkRun run : runs) {
            results.add(new NamedResult(run.adapter, run.result()));
        }
        return List.copyOf(results);
    }

    private static void runRotated(List<BenchmarkRun> runs, int rotation,
            java.util.function.Consumer<BenchmarkRun> operation) {
        if (runs.isEmpty()) {
            return;
        }
        int start = Math.floorMod(rotation, runs.size());
        for (int offset = 0; offset < runs.size(); offset++) {
            BenchmarkRun run = runs.get((start + offset) % runs.size());
            if (run.active()) {
                operation.accept(run);
            }
        }
    }

    private static final class BenchmarkRun {
        private final PlannerAdapter adapter;
        private final CapturedPlanFixture.Loaded fixture;
        private final SemanticIndex semantics;
        private final long[] timings = new long[SAMPLES];
        private Object prepared;
        private long coldCompile;
        private long coldSolve;
        private Outcome cold;
        private String stableCanonical;
        private String stableDigest;
        private String portableCanonical;
        private String portableDigest;
        private PatternTimesExecutionValidator.Validation validation;
        private boolean complete;
        private int completedSamples;
        private int failedSamples;
        private String sampleError;
        private BenchmarkResult terminal;

        private BenchmarkRun(PlannerAdapter adapter, CapturedPlanFixture.Loaded fixture,
                SemanticIndex semantics) {
            this.adapter = adapter;
            this.fixture = fixture;
            this.semantics = semantics;
        }

        private void prepare(CraftGraph validationGraph) {
            if (!this.adapter.available()) {
                this.terminal = BenchmarkResult.unavailable(this.adapter.unavailableReason());
                return;
            }
            try {
                long started = System.nanoTime();
                this.prepared = this.adapter.compile(this.fixture);
                this.coldCompile = System.nanoTime() - started;

                started = System.nanoTime();
                this.cold = this.adapter.solve(this.prepared, this.fixture);
                this.coldSolve = System.nanoTime() - started;
                this.stableCanonical = this.semantics.canonical(this.cold);
                this.stableDigest = sha256(this.stableCanonical);
                this.validation = PatternTimesExecutionValidator.validate(
                        this.fixture, this.cold.plan(), validationGraph);
                this.portableCanonical = this.semantics.portableCanonical(
                        this.cold, this.validation, validationGraph);
                this.portableDigest = this.validation.valid() ? sha256(this.portableCanonical) : null;
                this.complete = isComplete(this.cold, this.fixture);
                consume(this.stableDigest);

                Object recompiled = this.adapter.compile(this.fixture);
                Outcome recompiledOutcome = this.adapter.solve(recompiled, this.fixture);
                assertEquals(this.stableCanonical, this.semantics.canonical(recompiledOutcome),
                        this.adapter.name() + " recompiled canonical");
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private boolean active() {
            return this.terminal == null;
        }

        private void warmup() {
            try {
                Outcome outcome = this.adapter.solve(this.prepared, this.fixture);
                assertEquals(this.stableDigest, this.semantics.digest(outcome),
                        this.adapter.name() + " warmup digest");
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void sample(int index) {
            try {
                long started = System.nanoTime();
                Outcome outcome = this.adapter.solve(this.prepared, this.fixture);
                long elapsed = System.nanoTime() - started;
                assertEquals(this.stableDigest, this.semantics.digest(outcome),
                        this.adapter.name() + " sample digest");
                this.timings[index] = elapsed;
                this.completedSamples++;
                consume(this.stableDigest);
            } catch (Throwable failure) {
                this.failedSamples++;
                Throwable root = unwrap(failure);
                if (this.sampleError == null) {
                    this.sampleError = root.getClass().getName() + ": " + String.valueOf(root.getMessage());
                }
            }
        }

        private void fail(Throwable failure) {
            Throwable root = unwrap(failure);
            this.terminal = BenchmarkResult.failed(
                    root.getClass().getName() + ": " + String.valueOf(root.getMessage()));
        }

        private BenchmarkResult result() {
            if (this.terminal != null) {
                return this.terminal;
            }
            TimingStatistics statistics = timingStatistics(this.timings);
            return BenchmarkResult.success(
                    this.coldCompile,
                    this.coldSolve,
                    Math.addExact(this.coldCompile, this.coldSolve),
                    statistics.best(),
                    statistics.p50(),
                    statistics.p95(),
                    statistics.max(),
                    this.cold.decline(),
                    this.stableDigest,
                    this.stableCanonical,
                    this.portableDigest,
                    this.portableCanonical,
                    this.validation,
                    this.complete,
                    this.completedSamples,
                    this.failedSamples,
                    this.sampleError);
        }
    }

    private static TimingStatistics timingStatistics(long[] timings) {
        long[] sorted = Arrays.stream(timings)
                .filter(timing -> timing > 0L)
                .sorted()
                .toArray();
        if (sorted.length == 0) {
            return new TimingStatistics(-1L, -1L, -1L, -1L);
        }
        return new TimingStatistics(
                sorted[0],
                percentile(sorted, 50),
                percentile(sorted, 95),
                sorted[sorted.length - 1]);
    }

    private record TimingStatistics(long best, long p50, long p95, long max) {
    }

    private static boolean isComplete(Outcome outcome, CapturedPlanFixture.Loaded fixture) {
        if (outcome.decline() != null || outcome.plan() == null) {
            return false;
        }
        ICraftingPlan plan = outcome.plan();
        return !plan.simulation()
                && plan.missingItems().isEmpty()
                && fixture.target().equals(plan.finalOutput().what())
                && plan.finalOutput().amount() >= fixture.amount();
    }

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

    private static void printFeatureLine(Path capture, CapturedPlanFixture.Loaded fixture,
            CaptureFeatures features) {
        System.out.printf(Locale.ROOT,
                "PLANNER_CAPTURE_JSON {\"capture\":\"%s\",\"keys\":%d,\"patterns\":%d,"
                        + "\"amount\":%d,\"stock_dropped\":%d,\"reference_planner\":\"%s\","
                        + "\"features\":%s}%n",
                json(capture.getFileName().toString()), fixture.keyCount(), fixture.patternCount(),
                fixture.amount(), fixture.stockDropped(), json(REFERENCE_PLANNER), features.toJson());
    }

    private static void printResult(String capture, String planner, BenchmarkResult result,
            String referenceDigest, String referencePortableDigest, CaptureFeatures features) {
        boolean correct = result.semanticCorrect(referencePortableDigest);
        boolean eligible = result.eligible(referencePortableDigest);
        String correctness = result.correctness(referencePortableDigest);
        String csv = String.join(",",
                csv(capture), csv(planner), csv(scope(planner).jsonName()), Boolean.toString(result.available()),
                Boolean.toString(result.complete()), Boolean.toString(correct), Boolean.toString(eligible),
                csv(correctness), Long.toString(result.coldCompileNs()), Long.toString(result.coldSolveNs()),
                Long.toString(result.coldTotalNs()), Long.toString(result.warmBestNs()),
                Long.toString(result.warmP50Ns()), Long.toString(result.warmP95Ns()),
                Long.toString(result.warmMaxNs()), Integer.toString(result.completedSamples()),
                Integer.toString(result.failedSamples()), Double.toString(result.failureRate()),
                csv(result.decline()), csv(result.digest()), csv(referenceDigest),
                Integer.toString(features.ringSccs()), Integer.toString(features.coloredPatterns()),
                Integer.toString(features.remainderInputs()), Integer.toString(features.multiRouteKeys()),
                Integer.toString(features.fuzzyInputs()), csv(result.error()));
        System.out.println("PLANNER_COMPARE_CSV " + csv);
        System.out.printf(Locale.ROOT,
                "PLANNER_COMPARE_JSON {\"capture\":\"%s\",\"planner\":\"%s\","
                        + "\"measurement_scope\":\"%s\",\"available\":%s,\"complete\":%s,"
                        + "\"semantic_correct\":%s,"
                        + "\"eligible\":%s,\"correctness\":\"%s\",\"warmup\":%d,\"samples\":%d,"
                        + "\"cold_compile_ns\":%d,\"cold_solve_ns\":%d,\"cold_total_ns\":%d,"
                        + "\"warm_best_ns\":%d,\"warm_p50_ns\":%d,\"warm_p95_ns\":%d,"
                        + "\"warm_max_ns\":%d,\"completed_samples\":%d,\"failed_samples\":%d,"
                        + "\"failure_rate\":%s,"
                        + "\"decline\":%s,\"raw_digest\":%s,\"reference_raw_digest\":\"%s\","
                        + "\"validation_valid\":%s,\"validation_reason\":%s,"
                        + "\"portable_digest\":%s,\"reference_portable_digest\":\"%s\","
                        + "\"features\":%s,\"error\":%s}%n",
                json(capture), json(planner), scope(planner).jsonName(), result.available(), result.complete(),
                correct, eligible, json(correctness), WARMUP, SAMPLES, result.coldCompileNs(), result.coldSolveNs(),
                result.coldTotalNs(), result.warmBestNs(), result.warmP50Ns(), result.warmP95Ns(),
                result.warmMaxNs(), result.completedSamples(), result.failedSamples(), result.failureRate(),
                nullableJson(result.decline()), nullableJson(result.digest()), json(referenceDigest),
                result.validationValid(), nullableJson(result.validationReason()),
                nullableJson(result.portableDigest()), json(referencePortableDigest),
                features.toJson(), nullableJson(result.error()));
    }

    private static void printRanking(String capture, List<NamedResult> eligible, String referenceDigest,
            String referencePortableDigest) {
        StringBuilder planners = new StringBuilder();
        for (int index = 0; index < eligible.size(); index++) {
            if (index > 0) {
                planners.append(',');
            }
            NamedResult result = eligible.get(index);
            planners.append("{\"rank\":").append(index + 1)
                    .append(",\"planner\":\"").append(json(result.name())).append("\"")
                    .append(",\"warm_p50_ns\":").append(result.result().warmP50Ns()).append('}');
        }
        System.out.printf(Locale.ROOT,
                "PLANNER_RANKING_JSON {\"capture\":\"%s\",\"measurement_scope\":\"prepared_solve\","
                        + "\"reference_planner\":\"%s\",\"reference_raw_digest\":\"%s\","
                        + "\"reference_portable_digest\":\"%s\",\"eligible\":[%s]}%n",
                json(capture), json(REFERENCE_PLANNER), json(referenceDigest),
                json(referencePortableDigest), planners);
    }

    private static void printCurrentOptimization(String capture, List<NamedResult> results,
            String referenceDigest) {
        NamedResult baseline = namedResult(results, BASELINE_CURRENT_PLANNER);
        NamedResult optimized = namedResult(results, OPTIMIZED_CURRENT_PLANNER);
        NamedResult residual = namedResult(results, NEW_RESIDUAL_PLANNER);
        NamedResult vm = namedResult(results, NEW_VM_PLANNER);
        assertEquals(MeasurementScope.PREPARED_SOLVE, baseline.adapter().scope());
        assertEquals(MeasurementScope.PREPARED_SOLVE, optimized.adapter().scope());
        assertEquals(MeasurementScope.PREPARED_SOLVE, residual.adapter().scope());
        assertEquals(MeasurementScope.PREPARED_SOLVE, vm.adapter().scope());
        assertEquals(4L, Stream.of(baseline, optimized, residual, vm)
                .map(NamedResult::name).distinct().count(), "algorithm roles must be distinct");
        System.out.printf(Locale.ROOT,
                "PLANNER_OPTIMIZATION_JSON {\"capture\":\"%s\","
                        + "\"measurement_scope\":\"prepared_solve\","
                        + "\"before\":%s,\"after\":%s,"
                        + "\"independent_residual\":%s,\"independent_vm\":%s}%n",
                json(capture), optimizationResult(baseline, referenceDigest),
                optimizationResult(optimized, referenceDigest),
                optimizationResult(residual, referenceDigest),
                optimizationResult(vm, referenceDigest));
    }

    private static void verifyNeoEcoPerformance(String capture, List<NamedResult> preparedEligible) {
        NamedResult neoEco = preparedEligible.stream()
                .filter(result -> result.name().equals("NeoECOAE"))
                .findFirst()
                .orElse(null);
        if (neoEco == null) {
            System.out.printf(Locale.ROOT,
                    "PLANNER_NEOECO_COMPARISON_JSON {\"capture\":\"%s\","
                            + "\"measurement_scope\":\"prepared_solve\","
                            + "\"comparable\":false}%n",
                    json(capture));
            return;
        }
        NamedResult bestLocal = preparedEligible.stream()
                .filter(result -> !result.name().equals("NeoECOAE"))
                .min(Comparator.comparingLong(result -> result.result().warmP50Ns()))
                .orElseThrow();
        boolean localColdFaster = bestLocal.result().coldTotalNs() < neoEco.result().coldTotalNs();
        boolean localWarmFaster = bestLocal.result().warmP50Ns() < neoEco.result().warmP50Ns();
        System.out.printf(Locale.ROOT,
                "PLANNER_NEOECO_COMPARISON_JSON {\"capture\":\"%s\","
                        + "\"measurement_scope\":\"prepared_solve\","
                        + "\"cold_metric\":\"cold_total_ns\","
                        + "\"warm_metric\":\"warm_p50_ns\","
                        + "\"best_local\":{\"planner\":\"%s\","
                        + "\"cold_total_ns\":%d,\"warm_p50_ns\":%d},"
                        + "\"neoecoae\":{\"cold_total_ns\":%d,\"warm_p50_ns\":%d},"
                        + "\"local_cold_faster\":%s,\"local_warm_faster\":%s}%n",
                json(capture), json(bestLocal.name()), bestLocal.result().coldTotalNs(),
                bestLocal.result().warmP50Ns(), neoEco.result().coldTotalNs(),
                neoEco.result().warmP50Ns(), localColdFaster, localWarmFaster);
    }

    private static NamedResult namedResult(List<NamedResult> results, String name) {
        return results.stream().filter(result -> result.name().equals(name)).findFirst().orElseThrow();
    }

    private static String optimizationResult(NamedResult named, String referenceDigest) {
        return String.format(Locale.ROOT,
                "{\"planner\":\"%s\",\"eligible\":%s,\"correctness\":\"%s\",\"warm_p50_ns\":%d}",
                json(named.name()), named.result().eligible(referenceDigest),
                json(named.result().correctness(referenceDigest)), named.result().warmP50Ns());
    }

    private static void printEntryMetrics(String capture, List<NamedResult> entries, String referenceDigest) {
        StringBuilder metrics = new StringBuilder();
        for (NamedResult entry : entries) {
            if (metrics.length() > 0) {
                metrics.append(',');
            }
            metrics.append("{\"planner\":\"").append(json(entry.name())).append("\"")
                    .append(",\"eligible\":").append(entry.result().eligible(referenceDigest))
                    .append(",\"correctness\":\"")
                    .append(json(entry.result().correctness(referenceDigest))).append("\"")
                    .append(",\"warm_p50_ns\":").append(entry.result().warmP50Ns()).append('}');
        }
        System.out.printf(Locale.ROOT,
                "PLANNER_ENTRY_METRICS_JSON {\"capture\":\"%s\","
                        + "\"measurement_scope\":\"end_to_end_entry\",\"entries\":[%s]}%n",
                json(capture), metrics);
    }

    private static void verifyReplacementSelection(String capture, List<NamedResult> entries,
            String referenceDigest) {
        List<String> candidateNames = List.of(
                "LEGACY_FROZEN_ROUTE_ENTRY",
                PlanningAlgorithmId.GRAPH.name() + "_ENTRY",
                PlanningAlgorithmId.NATIVE.name() + "_ENTRY");
        List<NamedResult> candidates = candidateNames.stream()
                .map(name -> namedResult(entries, name))
                .toList();
        for (NamedResult candidate : candidates) {
            assertEquals(MeasurementScope.END_TO_END_ENTRY, candidate.adapter().scope());
            assertTrue(candidate.result().eligible(referenceDigest),
                    () -> "replacement candidate is not eligible: " + candidate.name());
        }
        NamedResult slowest = candidates.stream()
                .max(Comparator.comparingLong(result -> result.result().warmP50Ns()))
                .orElseThrow();

        StringBuilder metrics = new StringBuilder();
        for (NamedResult candidate : candidates) {
            if (metrics.length() > 0) {
                metrics.append(',');
            }
            metrics.append("{\"planner\":\"").append(json(candidate.name())).append("\"")
                    .append(",\"warm_p50_ns\":")
                    .append(candidate.result().warmP50Ns()).append('}');
        }
        System.out.printf(Locale.ROOT,
                "PLANNER_REPLACEMENT_JSON {\"capture\":\"%s\","
                        + "\"measurement_scope\":\"end_to_end_entry\","
                        + "\"selection_metric\":\"warm_p50_ns\","
                        + "\"excluded\":[\"FAST_ENTRY\"],\"candidates\":[%s],"
                        + "\"slowest_observed\":\"%s\"}%n",
                json(capture), metrics, json(slowest.name()));
    }

    private static MeasurementScope scope(String planner) {
        return planner.endsWith("_ENTRY")
                ? MeasurementScope.END_TO_END_ENTRY
                : MeasurementScope.PREPARED_SOLVE;
    }

    private enum MeasurementScope {
        PREPARED_SOLVE("prepared_solve"),
        END_TO_END_ENTRY("end_to_end_entry");

        private final String jsonName;

        MeasurementScope(String jsonName) {
            this.jsonName = jsonName;
        }

        private String jsonName() {
            return this.jsonName;
        }
    }

    private interface PlannerAdapter {
        String name();

        default MeasurementScope scope() {
            return MeasurementScope.PREPARED_SOLVE;
        }

        default boolean available() {
            return true;
        }

        default String unavailableReason() {
            return null;
        }

        default void printEvidence(String capture) {
        }

        default void prepareValidation(CraftGraph validationGraph) {
        }

        Object compile(CapturedPlanFixture.Loaded fixture) throws Exception;

        Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) throws Exception;
    }

    private record PreparedGraph(CraftGraph graph, long requestedAmount) {
    }

    @FunctionalInterface
    private interface GraphSolve {
        SolveResult solve(PreparedGraph graph);
    }

    private record GraphAdapter(String name, GraphSolve solver) implements PlannerAdapter {
        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) {
            CraftGraph graph = GraphCompiler.compile(
                    fixture.craftingService(), fixture.stock(), fixture.target(), null);
            return new PreparedGraph(graph, fixture.amount());
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) {
            PreparedGraph graph = (PreparedGraph) prepared;
            SolveResult result = solver.solve(graph);
            return graphOutcome(result, graph.graph());
        }
    }

    private record FrozenAdapter() implements PlannerAdapter {
        @Override
        public String name() {
            return "FrozenRoutePlanner";
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) {
            CraftGraph graph = GraphCompiler.compile(
                    fixture.craftingService(), fixture.stock(), fixture.target(), null);
            if (graph.cycleKind() != CraftGraph.CycleKind.NONE) {
                return new PreparedFrozen(null, true);
            }
            return new PreparedFrozen(
                    new FrozenRoutePlanner().compile(fixture.craftingService(), fixture.target(), null), false);
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) {
            PreparedFrozen frozen = (PreparedFrozen) prepared;
            if (frozen.recursive()) {
                return Outcome.declined(
                        "FrozenRoutePlanner prepared core does not interpret recursive graphs");
            }
            return Outcome.plan(new FrozenRoutePlanner()
                    .solve(frozen.program(), fixture.stock(), fixture.amount()).plan());
        }
    }

    private record PreparedFrozen(FrozenRoutePlanner.CompiledProgram program, boolean recursive) {
    }

    private record VmAdapter() implements PlannerAdapter {
        @Override
        public String name() {
            return NEW_VM_PLANNER;
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) {
            CraftGraph graph = GraphCompiler.compile(
                    fixture.craftingService(), fixture.stock(), fixture.target(), null);
            return TransactionalDemandVm.compile(graph);
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) {
            TransactionalDemandVm.Program program = (TransactionalDemandVm.Program) prepared;
            return graphOutcome(program.solve(
                    CalculationStrategy.REPORT_MISSING_ITEMS, fixture.amount()), program.graph());
        }
    }

    private record ResidualAdapter() implements PlannerAdapter {
        @Override
        public String name() {
            return NEW_RESIDUAL_PLANNER;
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) {
            return GraphCompiler.compile(
                    fixture.craftingService(), fixture.stock(), fixture.target(), null);
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) {
            CraftGraph graph = (CraftGraph) prepared;
            return graphOutcome(ResidualBestFirstPlanner.solve(
                    graph, CalculationStrategy.REPORT_MISSING_ITEMS, fixture.amount()), graph);
        }
    }

    private static final class NeoEcoAdapter implements PlannerAdapter {
        private static final Path JAR = Path.of(
                "reference-sources", "neoecoae-21.2.0-preview14.jar");
        private static final String SERVICE_CLASS =
                "cn.dancingsnow.neoecoae.impl.crafting.planner.ECOCraftingPlannerService";
        private static final String CANCELLATION_CLASS =
                "cn.dancingsnow.neoecoae.impl.crafting.planner.ECOCancellation";
        private final ClassLoader classLoader;
        private final Class<?> serviceClass;
        private final Class<?> cancellationClass;
        private final Object cancellation;
        private final String unavailable;
        private CraftGraph validationGraph;
        private String recursiveEvidence;

        private NeoEcoAdapter() {
            ClassLoader loadedClassLoader = null;
            Class<?> loadedService = null;
            Class<?> loadedCancellationClass = null;
            Object loadedCancellation = null;
            String reason = null;
            try {
                if (!Files.isRegularFile(JAR)) {
                    throw new IOException("missing " + JAR);
                }
                loadedClassLoader = new URLClassLoader(
                        new URL[] { JAR.toUri().toURL() }, getClass().getClassLoader());
                loadedService = Class.forName(SERVICE_CLASS, true, loadedClassLoader);
                loadedCancellationClass = Class.forName(
                        CANCELLATION_CLASS, true, loadedClassLoader);
                loadedCancellation = loadedCancellationClass.getField("NONE").get(null);
            } catch (ReflectiveOperationException | IOException | LinkageError failure) {
                reason = "NeoECOAE planner unavailable: " + failure;
            }
            this.classLoader = loadedClassLoader;
            this.serviceClass = loadedService;
            this.cancellationClass = loadedCancellationClass;
            this.cancellation = loadedCancellation;
            this.unavailable = reason;
        }

        @Override
        public String name() {
            return "NeoECOAE";
        }

        @Override
        public boolean available() {
            return serviceClass != null && cancellationClass != null && cancellation != null;
        }

        @Override
        public String unavailableReason() {
            return unavailable;
        }

        @Override
        public void prepareValidation(CraftGraph validationGraph) {
            this.validationGraph = validationGraph;
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) throws Exception {
            Object service = serviceClass.getDeclaredConstructor().newInstance();
            Method createSession = serviceClass.getMethod(
                    "createSession", ICraftingService.class, AEKey.class, KeyCounter.class, boolean.class);
            Object session = invoke(createSession, service,
                    new Object[] { fixture.craftingService(), fixture.target(), fixture.stock(), true });
            Method ensureCompiled = session.getClass().getDeclaredMethod(
                    "ensureCompiled", cancellationClass);
            ensureCompiled.setAccessible(true);
            invoke(ensureCompiled, session, new Object[] { cancellation });
            return new NeoEcoSession(session, session.getClass().getMethod(
                    "plan", long.class, boolean.class, cancellationClass));
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) throws Exception {
            NeoEcoSession session = (NeoEcoSession) prepared;
            Object result = invoke(session.plan(), session.session(),
                    new Object[] { fixture.amount(), false, cancellation });
            NeoEcoOutcome converted = neoEcoOutcome(result, fixture, validationGraph);
            if (converted.recursiveEvidence() != null) {
                recursiveEvidence = converted.recursiveEvidence();
            }
            return converted.outcome();
        }

        @Override
        public void printEvidence(String capture) {
            if (recursiveEvidence != null) {
                System.out.printf(Locale.ROOT,
                        "PLANNER_RECURSIVE_PATH_JSON {\"capture\":\"%s\",\"planner\":\"NeoECOAE\",%s}%n",
                        json(capture), recursiveEvidence);
            }
        }
    }

    private record NeoEcoSession(Object session, Method plan) {
    }

    private record NeoEcoOutcome(Outcome outcome, String recursiveEvidence) {
    }

    private static NeoEcoOutcome neoEcoOutcome(Object result, CapturedPlanFixture.Loaded fixture,
            CraftGraph graph) throws Exception {
        if (result == null) {
            throw new IllegalStateException("NeoECOAE returned null");
        }
        String status = enumName(invokeRequired(result, "status"));
        if (!"SUCCESS".equals(status)) {
            if (status.endsWith("UNSUPPORTED")) {
                return new NeoEcoOutcome(Outcome.declined("NeoECOAE status " + status), null);
            }
            throw new IllegalStateException("NeoECOAE status " + status);
        }
        Object rawPlan = invokeRequired(result, "plan");
        if (!(rawPlan instanceof CraftingPlan plan)) {
            throw new IllegalStateException("NeoECOAE SUCCESS returned no CraftingPlan");
        }
        Object executionPlanError = invokeRequired(result, "executionPlanError");
        if (executionPlanError != null) {
            throw new IllegalStateException("NeoECOAE execution plan error: " + executionPlanError);
        }
        validateCompletePlan("NeoECOAE", plan, fixture);

        String requirement = enumName(invokeRequired(result, "executionRequirement"));
        if ("BLOCKED".equals(requirement)) {
            throw new IllegalStateException("NeoECOAE returned BLOCKED execution requirement");
        }
        boolean recursive = graph.cycleKind() != CraftGraph.CycleKind.NONE;
        if (!recursive) {
            if (!"NONE".equals(requirement)) {
                throw new IllegalStateException(
                        "NeoECOAE non-recursive graph returned " + requirement);
            }
            return new NeoEcoOutcome(Outcome.plan(plan), null);
        }
        if (!"ORDERED".equals(requirement) && !"DYNAMIC".equals(requirement)) {
            throw new IllegalStateException(
                    "NeoECOAE recursive graph returned " + requirement);
        }

        Object executionPlan = invokeRequired(result, "executionPlan");
        KeyCounter recursiveInitial = neoEcoInitialSeed(executionPlan);
        if (recursiveInitial.isEmpty()) {
            throw new IllegalStateException("NeoECOAE recursive execution plan has no initial seed");
        }
        KeyCounter recursiveInternal = neoEcoInternalItems(result, recursiveInitial);
        Map<AEItemKey, Map<AEKey, Long>> selectedInputs =
                buildSelectedPatternInputs(plan.patternTimes(), graph);
        RecursiveCraftingPlan compatible = new RecursiveCraftingPlan(
                plan, recursiveInitial, recursiveInternal, plan.finalOutput().amount(), selectedInputs);
        SemanticIndex semantics = SemanticIndex.build(fixture);
        String evidence = String.format(Locale.ROOT,
                "\"execution_requirement\":\"%s\",\"initial_seed_keys\":%d,"
                        + "\"internal_keys\":%d,%s",
                json(requirement), recursiveInitial.size(), recursiveInternal.size(),
                neoEcoNativeEvidence(result, executionPlan, semantics));
        return new NeoEcoOutcome(Outcome.plan(compatible), evidence);
    }

    private static void validateCompletePlan(String planner, ICraftingPlan plan,
            CapturedPlanFixture.Loaded fixture) {
        if (plan.simulation()) {
            throw new IllegalStateException(planner + " returned simulation");
        }
        if (!plan.missingItems().isEmpty()) {
            throw new IllegalStateException(planner + " returned missing items: " + plan.missingItems());
        }
        if (!fixture.target().equals(plan.finalOutput().what())) {
            throw new IllegalStateException(planner + " returned wrong final output " + plan.finalOutput());
        }
        if (plan.finalOutput().amount() < fixture.amount()) {
            throw new IllegalStateException(planner + " returned insufficient final output "
                    + plan.finalOutput().amount() + " < " + fixture.amount());
        }
    }

    private static String neoEcoNativeEvidence(Object result, Object executionPlan,
            SemanticIndex semantics) throws Exception {
        Object rawTasks = invokeRequired(executionPlan, "tasks");
        if (!(rawTasks instanceof List<?> tasks)) {
            throw new IllegalStateException("NeoECOAE execution tasks are unavailable");
        }
        Map<Integer, String> taskPatterns = new HashMap<>();
        List<String> taskCounts = new ArrayList<>();
        for (Object task : tasks) {
            Object rawId = invokeRequired(task, "id");
            Object rawPattern = invokeRequired(task, "pattern");
            Object rawTotalCount = invokeRequired(task, "totalCount");
            if (!(rawId instanceof Number id)
                    || !(rawPattern instanceof IPatternDetails pattern)
                    || !(rawTotalCount instanceof Number totalCount)) {
                throw new IllegalStateException("NeoECOAE execution task is invalid");
            }
            String stablePattern = semantics.pattern(pattern);
            String previous = taskPatterns.put(id.intValue(), stablePattern);
            if (previous != null) {
                throw new IllegalStateException("NeoECOAE duplicate execution task " + id);
            }
            taskCounts.add(stablePattern + ':' + totalCount.longValue());
        }
        taskCounts.sort(null);

        Object rawPhases = invokeRequired(executionPlan, "phases");
        if (!(rawPhases instanceof List<?> phases)) {
            throw new IllegalStateException("NeoECOAE execution phases are unavailable");
        }
        List<String> dynamicFirings = new ArrayList<>();
        for (Object phase : phases) {
            Object rawIndex = invokeRequired(phase, "index");
            Object rawFirings = invokeRequired(phase, "dynamicFirings");
            if (!(rawIndex instanceof Number index) || !(rawFirings instanceof Map<?, ?> firings)) {
                throw new IllegalStateException("NeoECOAE execution phase is invalid");
            }
            List<String> entries = new ArrayList<>();
            for (var entry : firings.entrySet()) {
                if (!(entry.getKey() instanceof Number taskId)
                        || !(entry.getValue() instanceof Number count)) {
                    throw new IllegalStateException(
                            "NeoECOAE phase contains invalid dynamic firing " + entry);
                }
                String stablePattern = taskPatterns.get(taskId.intValue());
                if (stablePattern == null) {
                    throw new IllegalStateException(
                            "NeoECOAE phase references unknown task " + taskId);
                }
                entries.add(stablePattern + ":" + count.longValue());
            }
            entries.sort(null);
            dynamicFirings.add(index.intValue() + "[" + String.join(";", entries) + ']');
        }
        dynamicFirings.sort(null);

        List<String> cyclePatternTimes = new ArrayList<>();
        Object rawComponents = invokeRequired(result, "components");
        if (!(rawComponents instanceof List<?> components)) {
            throw new IllegalStateException("NeoECOAE components are unavailable");
        }
        for (Object component : components) {
            if (!"CYCLIC".equals(enumName(invokeRequired(component, "type")))) {
                continue;
            }
            Object cycleResult = invokeRequired(component, "cycleResult");
            if (cycleResult == null) {
                throw new IllegalStateException("NeoECOAE cyclic component has no cycle result");
            }
            Object rawPatternTimes = invokeRequired(cycleResult, "patternTimes");
            if (!(rawPatternTimes instanceof Map<?, ?> patternTimes)) {
                throw new IllegalStateException("NeoECOAE cycle patternTimes are unavailable");
            }
            for (var entry : patternTimes.entrySet()) {
                if (!(entry.getKey() instanceof IPatternDetails pattern)
                        || !(entry.getValue() instanceof Number count)) {
                    throw new IllegalStateException(
                            "NeoECOAE cycle contains invalid pattern count " + entry);
                }
                cyclePatternTimes.add(semantics.pattern(pattern) + ':' + count.longValue());
            }
        }
        cyclePatternTimes.sort(null);
        return String.format(Locale.ROOT,
                "\"task_total_counts\":\"%s\",\"phase_dynamic_firings\":\"%s\","
                        + "\"cycle_pattern_times\":\"%s\"",
                json(String.join(";", taskCounts)), json(String.join(";", dynamicFirings)),
                json(String.join(";", cyclePatternTimes)));
    }

    private static KeyCounter neoEcoInitialSeed(Object executionPlan) throws Exception {
        Object phases = invokeRequired(executionPlan, "phases");
        if (!(phases instanceof List<?> list)) {
            throw new IllegalStateException("NeoECOAE execution phases are unavailable");
        }
        KeyCounter initial = new KeyCounter();
        for (Object phase : list) {
            Object seed = invokeRequired(phase, "initialSeed");
            if (!(seed instanceof Map<?, ?> values)) {
                throw new IllegalStateException("NeoECOAE phase seed is not a map");
            }
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof AEKey key)
                        || !(entry.getValue() instanceof Long amount) || amount <= 0L) {
                    throw new IllegalStateException("NeoECOAE phase contains invalid seed " + entry);
                }
                initial.set(key, Math.max(initial.get(key), amount));
            }
        }
        return initial;
    }

    private static KeyCounter neoEcoInternalItems(Object result, KeyCounter initial) throws Exception {
        KeyCounter internal = copyCounter(initial);
        Object components = invokeRequired(result, "components");
        if (!(components instanceof List<?> list)) {
            throw new IllegalStateException("NeoECOAE components are unavailable");
        }
        for (Object component : list) {
            if (!"CYCLIC".equals(enumName(invokeRequired(component, "type")))) {
                continue;
            }
            Object cycleResult = invokeRequired(component, "cycleResult");
            if (cycleResult == null) {
                throw new IllegalStateException("NeoECOAE cyclic component has no cycle result");
            }
            if (!enumName(invokeRequired(cycleResult, "status")).equals("SUCCESS")) {
                throw new IllegalStateException("NeoECOAE cycle result is not successful");
            }
            Object shortfall = invokeRequired(cycleResult, "seedShortfall");
            if (!(shortfall instanceof Map<?, ?> missing) || !missing.isEmpty()) {
                throw new IllegalStateException("NeoECOAE cycle seed shortfall: " + shortfall);
            }
            mergePositiveMax(internal, invokeRequired(cycleResult, "requiredSeed"),
                    "requiredSeed");
        }
        return internal;
    }

    private static void mergePositiveMax(KeyCounter target, Object values, String label) {
        if (!(values instanceof Map<?, ?> map)) {
            throw new IllegalStateException("NeoECOAE " + label + " is not a map");
        }
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof AEKey key)
                    || !(entry.getValue() instanceof Long amount) || amount <= 0L) {
                continue;
            }
            target.set(key, Math.max(target.get(key), amount));
        }
    }

    private static Map<AEItemKey, Map<AEKey, Long>> buildSelectedPatternInputs(
            Map<IPatternDetails, Long> crafts, CraftGraph graph) {
        Map<AEItemKey, Map<AEKey, Long>> selected = new HashMap<>();
        for (var entry : crafts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L
                    || !(entry.getKey().getDefinition() instanceof AEItemKey definition)) {
                continue;
            }
            var compiled = graph.patternsBySource().get(entry.getKey());
            if (compiled == null) {
                continue;
            }
            Map<AEKey, Long> inputs = null;
            for (var input : compiled.inputs()) {
                long amount = Math.multiplyExact(input.amountPerCraft(), input.multiplier());
                if (amount <= 0L) {
                    continue;
                }
                if (inputs == null) {
                    inputs = selected.computeIfAbsent(definition, ignored -> new HashMap<>());
                }
                inputs.merge(input.key(), amount, Math::addExact);
            }
        }
        if (selected.isEmpty()) {
            return Map.of();
        }
        Map<AEItemKey, Map<AEKey, Long>> immutable = new HashMap<>();
        selected.forEach((key, value) -> immutable.put(key, Map.copyOf(value)));
        return Map.copyOf(immutable);
    }

    private static KeyCounter copyCounter(KeyCounter original) {
        KeyCounter copy = new KeyCounter();
        copy.addAll(original);
        return copy;
    }

    private static Object invokeRequired(Object owner, String methodName) throws Exception {
        Method method = owner.getClass().getMethod(methodName);
        return invoke(method, owner, new Object[0]);
    }

    private static String enumName(Object value) {
        if (!(value instanceof Enum<?> enumValue)) {
            throw new IllegalStateException("Expected enum but got " + value);
        }
        return enumValue.name();
    }

    private record LegacyFrozenRouteAdapter() implements PlannerAdapter {
        @Override
        public String name() {
            return "LEGACY_FROZEN_ROUTE_ENTRY";
        }

        @Override
        public MeasurementScope scope() {
            return MeasurementScope.END_TO_END_ENTRY;
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) {
            return fixture.replayGrid();
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) {
            var grid = (git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid) prepared;
            ICraftingPlan plan = LegacyFrozenRouteReplay.create(
                    grid,
                    grid.requester,
                    new GenericStack(fixture.target(), fixture.amount()),
                    CalculationStrategy.REPORT_MISSING_ITEMS).run();
            return Outcome.plan(plan);
        }
    }

    private record OrderedAdapter(PlanningAlgorithmId algorithm) implements PlannerAdapter {
        @Override
        public String name() {
            return algorithm.name() + "_ENTRY";
        }

        @Override
        public MeasurementScope scope() {
            return MeasurementScope.END_TO_END_ENTRY;
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) {
            return fixture.replayGrid();
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) {
            var grid = (git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid) prepared;
            ICraftingPlan plan = OrderedPlanningReplay.create(
                    grid,
                    grid.requester,
                    new GenericStack(fixture.target(), fixture.amount()),
                    CalculationStrategy.REPORT_MISSING_ITEMS,
                    algorithm).run();
            return Outcome.plan(plan);
        }
    }

    private static final class ReflectiveAdapter implements PlannerAdapter {
        private final String name;
        private final String className;
        private final Class<?> plannerClass;
        private final String unavailable;

        private ReflectiveAdapter(String name, String className) {
            this.name = name;
            this.className = className;
            Class<?> found = null;
            String reason = null;
            try {
                found = Class.forName(className);
            } catch (ClassNotFoundException absent) {
                reason = "class not present: " + className;
            } catch (LinkageError broken) {
                reason = "class linkage failed: " + broken;
            }
            this.plannerClass = found;
            this.unavailable = reason;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean available() {
            return plannerClass != null;
        }

        @Override
        public String unavailableReason() {
            return unavailable;
        }

        @Override
        public Object compile(CapturedPlanFixture.Loaded fixture) throws Exception {
            Method plan = findMethod(plannerClass, "plan", null, fixture);
            if (plan != null) {
                return new ReflectiveCall(instanceFor(plan), plan, null);
            }
            Method compile = findMethod(plannerClass, "compile", null, fixture);
            if (compile != null) {
                Object owner = instanceFor(compile);
                Object program = invoke(compile, owner, arguments(compile, null, fixture));
                Method solve = findMethod(plannerClass, "solve", program, fixture);
                if (solve == null) {
                    throw new NoSuchMethodException(className + " has compile but no supported solve method");
                }
                return new ReflectiveCall(instanceFor(solve), solve, program);
            }
            CraftGraph graph = GraphCompiler.compile(
                    fixture.craftingService(), fixture.stock(), fixture.target(), null);
            Method solve = findMethod(plannerClass, "solve", graph, fixture);
            if (solve == null) {
                throw new NoSuchMethodException(className + " has no supported plan/compile/solve signature");
            }
            return new ReflectiveCall(instanceFor(solve), solve, graph);
        }

        @Override
        public Outcome solve(Object prepared, CapturedPlanFixture.Loaded fixture) throws Exception {
            ReflectiveCall call = (ReflectiveCall) prepared;
            Object result = invoke(call.method(), call.owner(), arguments(call.method(), call.program(), fixture));
            return reflectiveOutcome(result, call.program());
        }

        private static Method findMethod(Class<?> type, String methodName, Object program,
                CapturedPlanFixture.Loaded fixture) {
            return Arrays.stream(type.getMethods())
                    .filter(method -> method.getName().equals(methodName))
                    .filter(method -> argumentsOrNull(method, program, fixture) != null)
                    .sorted(Comparator.comparingInt(Method::getParameterCount).reversed())
                    .findFirst()
                    .orElse(null);
        }

        private static Object instanceFor(Method method) throws Exception {
            if (Modifier.isStatic(method.getModifiers())) {
                return null;
            }
            Constructor<?> constructor = method.getDeclaringClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }
    }

    private record ReflectiveCall(Object owner, Method method, Object program) {
    }

    private static Object[] arguments(Method method, Object program, CapturedPlanFixture.Loaded fixture) {
        Object[] result = argumentsOrNull(method, program, fixture);
        if (result == null) {
            throw new IllegalArgumentException("unsupported reflective signature: " + method);
        }
        return result;
    }

    private static Object[] argumentsOrNull(Method method, Object program,
            CapturedPlanFixture.Loaded fixture) {
        Class<?>[] types = method.getParameterTypes();
        Object[] result = new Object[types.length];
        boolean programUsed = program == null;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (!programUsed && type.isInstance(program)) {
                result[index] = program;
                programUsed = true;
            } else if (type.isInstance(fixture.craftingService())) {
                result[index] = fixture.craftingService();
            } else if (type.isInstance(fixture.stock())) {
                result[index] = fixture.stock();
            } else if (type.isInstance(fixture.target())) {
                result[index] = fixture.target();
            } else if (type == long.class || type == Long.class) {
                result[index] = fixture.amount();
            } else if (type == int.class || type == Integer.class) {
                result[index] = fixture.requestedColor();
            } else if (type == boolean.class || type == Boolean.class) {
                result[index] = false;
            } else if (type == CalculationStrategy.class) {
                result[index] = CalculationStrategy.REPORT_MISSING_ITEMS;
            } else if (type.getName().equals("net.minecraft.world.level.Level")
                    || type.getName().endsWith("GraphPlanningBudget")) {
                result[index] = null;
            } else {
                return null;
            }
        }
        return programUsed ? result : null;
    }

    private static Outcome graphOutcome(SolveResult result, CraftGraph graph) {
        if (result.hasDeclined()) {
            return Outcome.declined(result.declineReason());
        }
        ICraftingPlan beforeRetention = null;
        if (RETENTION_EVIDENCE && graph.cycleKind() != CraftGraph.CycleKind.NONE) {
            beforeRetention = Ae2PlanConverter.toPlan(result, graph);
        }
        String retentionDecline = RetentionApplier.apply(
                result.state(), graph, result.finalAmount());
        if (retentionDecline != null) {
            return Outcome.declined(retentionDecline);
        }
        ICraftingPlan afterRetention = Ae2PlanConverter.toPlan(result, graph);
        if (beforeRetention != null) {
            SemanticIndex semantics = SemanticIndex.fromGraph(graph);
            System.out.printf(Locale.ROOT,
                    "PLANNER_RETENTION_JSON {\"before\":\"%s\",\"after\":\"%s\"}%n",
                    json(semantics.canonical(Outcome.plan(beforeRetention))),
                    json(semantics.canonical(Outcome.plan(afterRetention))));
        }
        return Outcome.plan(afterRetention);
    }

    private static Outcome reflectiveOutcome(Object result, Object program) throws Exception {
        if (result instanceof ICraftingPlan plan) {
            return Outcome.plan(plan);
        }
        CraftGraph graph = graphFromProgram(program);
        if (result instanceof SolveResult solveResult && graph != null) {
            return graphOutcome(solveResult, graph);
        }
        if (result == null) {
            throw new IllegalStateException("planner returned null");
        }
        Method declineMethod = noArgMethod(result.getClass(), "declineReason");
        if (declineMethod != null) {
            Object decline = invoke(declineMethod, result, new Object[0]);
            if (decline != null) {
                return Outcome.declined(String.valueOf(decline));
            }
        }
        Method planMethod = noArgMethod(result.getClass(), "plan");
        if (planMethod != null) {
            Object plan = invoke(planMethod, result, new Object[0]);
            if (plan instanceof ICraftingPlan craftingPlan) {
                return Outcome.plan(craftingPlan);
            }
        }
        throw new IllegalStateException("unsupported planner result type " + result.getClass().getName());
    }

    private static CraftGraph graphFromProgram(Object program) throws Exception {
        if (program instanceof CraftGraph graph) {
            return graph;
        }
        if (program == null) {
            return null;
        }
        try {
            var field = program.getClass().getDeclaredField("graph");
            field.setAccessible(true);
            Object value = field.get(program);
            return value instanceof CraftGraph graph ? graph : null;
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static Method noArgMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object invoke(Method method, Object owner, Object[] arguments) throws Exception {
        try {
            return method.invoke(owner, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }

    private record Outcome(ICraftingPlan plan, String decline) {
        private static Outcome plan(ICraftingPlan plan) {
            return new Outcome(assertPlan(plan), null);
        }

        private static Outcome declined(String reason) {
            return new Outcome(null, assertDecline(reason));
        }

        private static ICraftingPlan assertPlan(ICraftingPlan plan) {
            assertNotNull(plan);
            return plan;
        }

        private static String assertDecline(String reason) {
            assertNotNull(reason);
            return reason;
        }
    }

    private record NamedResult(PlannerAdapter adapter, BenchmarkResult result) {
        private String name() {
            return this.adapter.name();
        }
    }

    private record BenchmarkResult(boolean available, boolean complete, long coldCompileNs, long coldSolveNs,
            long coldTotalNs, long warmBestNs, long warmP50Ns, long warmP95Ns, long warmMaxNs,
            String decline, String digest, String canonical, String portableDigest, String portableCanonical,
            boolean validationValid, String validationReason, int completedSamples, int failedSamples,
            String error) {
        private static BenchmarkResult success(long coldCompileNs, long coldSolveNs, long coldTotalNs,
                long warmBestNs, long warmP50Ns, long warmP95Ns, long warmMaxNs,
                String decline, String digest, String canonical, String portableDigest, String portableCanonical,
                PatternTimesExecutionValidator.Validation validation, boolean complete,
                int completedSamples, int failedSamples, String error) {
            return new BenchmarkResult(true, complete, coldCompileNs, coldSolveNs, coldTotalNs,
                    warmBestNs, warmP50Ns, warmP95Ns, warmMaxNs, decline, digest, canonical,
                    portableDigest, portableCanonical, validation.valid(), validation.reason(),
                    completedSamples, failedSamples, error);
        }

        private static BenchmarkResult unavailable(String reason) {
            return new BenchmarkResult(false, false, -1, -1, -1, -1, -1, -1, -1,
                    null, null, null, null, null, false, null, 0, 0, reason);
        }

        private static BenchmarkResult failed(String error) {
            return new BenchmarkResult(true, false, -1, -1, -1, -1, -1, -1, -1,
                    null, null, null, null, null, false, null, 0, SAMPLES, error);
        }

        private double failureRate() {
            int attempts = this.completedSamples + this.failedSamples;
            return attempts == 0 ? 0.0D : this.failedSamples / (double) attempts;
        }

        private boolean semanticCorrect(String referencePortableDigest) {
            return complete && error == null && decline == null && validationValid
                    && referencePortableDigest.equals(portableDigest);
        }

        private boolean eligible(String referencePortableDigest) {
            return available && semanticCorrect(referencePortableDigest) && warmP50Ns >= 0L;
        }

        private String correctness(String referencePortableDigest) {
            if (!available) {
                return "unavailable";
            }
            if (error != null) {
                return "error";
            }
            if (decline != null) {
                return "declined";
            }
            if (!complete) {
                return "incomplete";
            }
            if (!validationValid) {
                return "execution_invalid:" + validationReason;
            }
            if (!referencePortableDigest.equals(portableDigest)) {
                return "portable_mismatch";
            }
            return "correct";
        }
    }

    /** 把夹具对象身份归一成捕获内稳定序号，再计算完整计划语义摘要。 */
    private static final class SemanticIndex {
        private final Map<AEKey, Integer> keyIds = new HashMap<>();
        private final IdentityHashMap<IPatternDetails, Integer> patternIds = new IdentityHashMap<>();

        private static SemanticIndex build(CapturedPlanFixture.Loaded fixture) {
            var index = new SemanticIndex();
            var queue = new ArrayDeque<AEKey>();
            index.addKey(fixture.target(), queue);
            while (!queue.isEmpty()) {
                AEKey key = queue.removeFirst();
                for (IPatternDetails pattern : fixture.craftingService().getCraftingFor(key)) {
                    if (index.patternIds.putIfAbsent(pattern, index.patternIds.size()) != null) {
                        continue;
                    }
                    index.addPatternKeys(pattern, queue);
                }
            }
            List<AEKey> stockOnly = new ArrayList<>();
            for (var entry : fixture.stock()) {
                if (!index.keyIds.containsKey(entry.getKey())) {
                    stockOnly.add(entry.getKey());
                }
            }
            stockOnly.sort(Comparator.comparing(SemanticIndex::fingerprint));
            stockOnly.forEach(key -> index.keyIds.put(key, index.keyIds.size()));
            return index;
        }

        private static SemanticIndex fromGraph(CraftGraph graph) {
            var index = new SemanticIndex();
            var queue = new ArrayDeque<AEKey>();
            index.addKey(graph.target(), queue);
            for (IPatternDetails pattern : graph.patternRegistrationOrder()) {
                index.patternIds.putIfAbsent(pattern, index.patternIds.size());
                index.addPatternKeys(pattern, queue);
            }
            return index;
        }

        private void addPatternKeys(IPatternDetails pattern, ArrayDeque<AEKey> queue) {
            for (var output : pattern.getOutputs()) {
                addKey(output.what(), queue);
            }
            for (var input : pattern.getInputs()) {
                for (var candidate : input.getPossibleInputs()) {
                    addKey(candidate.what(), queue);
                    AEKey remainder = input.getRemainingKey(candidate.what());
                    if (remainder != null) {
                        addKey(remainder, queue);
                    }
                }
            }
        }

        private void addKey(AEKey key, ArrayDeque<AEKey> queue) {
            if (keyIds.putIfAbsent(key, keyIds.size()) == null) {
                queue.addLast(key);
            }
        }

        private String digest(Outcome outcome) {
            return sha256(canonical(outcome));
        }

        private String portableCanonical(
                Outcome outcome,
                PatternTimesExecutionValidator.Validation validation,
                CraftGraph graph
        ) {
            if (outcome.decline() != null) {
                return "decline\n" + outcome.decline();
            }
            if (!validation.valid()) {
                return "invalid\n" + validation.reason();
            }
            ICraftingPlan plan = outcome.plan();
            boolean recursive = graph.cycleKind() != CraftGraph.CycleKind.NONE;
            return "valid=true\n"
                    + "final=" + key(plan.finalOutput().what()) + ':' + plan.finalOutput().amount() + '\n'
                    + "recursive=" + recursive + '\n';
        }

        private String canonical(Outcome outcome) {
            if (outcome.decline() != null) {
                return "decline\n" + outcome.decline();
            }
            ICraftingPlan plan = outcome.plan();
            StringBuilder canonical = new StringBuilder(16_384);
            canonical.append("final=").append(key(plan.finalOutput().what()))
                    .append(':').append(plan.finalOutput().amount()).append('\n');
            canonical.append("bytes=").append(plan.bytes()).append('\n');
            canonical.append("simulation=").append(plan.simulation()).append('\n');
            canonical.append("multiplePaths=").append(plan.multiplePaths()).append('\n');
            appendCounter(canonical, "used", plan.usedItems());
            appendCounter(canonical, "emitted", plan.emittedItems());
            appendCounter(canonical, "missing", plan.missingItems());
            appendPatterns(canonical, plan.patternTimes());
            if (plan instanceof RecursivePlanContract recursive) {
                canonical.append("recursive=true\n");
                canonical.append("recursiveFinal=")
                        .append(recursive.chexsonsaeutils$dyeableRecursiveFinalOutputAmount()).append('\n');
                appendCounter(canonical, "recursiveInitial",
                        recursive.chexsonsaeutils$dyeableRecursiveInitialItems());
                appendCounter(canonical, "recursiveInternal",
                        recursive.chexsonsaeutils$dyeableRecursiveInternalItems());
            } else {
                canonical.append("recursive=false\n");
            }
            if (plan instanceof SelectedInputsPlan selected) {
                appendSelectedInputs(canonical, selected.chexsonsaeutils$dyeableSelectedPatternInputs());
            } else {
                canonical.append("selectedInputs=\n");
            }
            return canonical.toString();
        }

        private void appendCounter(StringBuilder target, String label, KeyCounter counter) {
            List<String> entries = new ArrayList<>();
            for (var entry : counter) {
                entries.add(key(entry.getKey()) + ':' + entry.getLongValue());
            }
            entries.sort(null);
            target.append(label).append('=').append(String.join(";", entries)).append('\n');
        }

        private void appendPatterns(StringBuilder target, Map<IPatternDetails, Long> patterns) {
            List<String> entries = new ArrayList<>();
            for (var entry : patterns.entrySet()) {
                entries.add(pattern(entry.getKey()) + ':' + entry.getValue());
            }
            entries.sort(null);
            target.append("patterns=").append(String.join(";", entries)).append('\n');
        }

        private String pattern(IPatternDetails pattern) {
            Integer id = patternIds.get(pattern);
            return id == null ? "u:" + patternFingerprint(pattern) : "p:" + id;
        }

        private void appendSelectedInputs(StringBuilder target,
                Map<AEItemKey, Map<AEKey, Long>> selectedInputs) {
            List<String> entries = new ArrayList<>();
            for (var patternEntry : selectedInputs.entrySet()) {
                List<String> inputs = new ArrayList<>();
                for (var input : patternEntry.getValue().entrySet()) {
                    inputs.add(key(input.getKey()) + ':' + input.getValue());
                }
                inputs.sort(null);
                entries.add(key(patternEntry.getKey()) + '[' + String.join(";", inputs) + ']');
            }
            entries.sort(null);
            target.append("selectedInputs=").append(String.join(";", entries)).append('\n');
        }

        private String key(AEKey key) {
            Integer id = keyIds.get(key);
            return id == null ? "u:" + fingerprint(key) : "k:" + id;
        }

        private static String fingerprint(AEKey key) {
            if (key instanceof AEItemKey item) {
                return item.getId() + "|" + item.getReadOnlyStack().getComponentsPatch();
            }
            return key.getType() + "|" + key.getId() + "|" + key;
        }

        private static String patternFingerprint(IPatternDetails pattern) {
            StringBuilder value = new StringBuilder();
            value.append(fingerprint(pattern.getDefinition())).append('|');
            for (var output : pattern.getOutputs()) {
                value.append(fingerprint(output.what())).append(':').append(output.amount()).append(',');
            }
            value.append('|');
            for (var input : pattern.getInputs()) {
                value.append(input.getMultiplier()).append('[');
                for (var candidate : input.getPossibleInputs()) {
                    value.append(fingerprint(candidate.what())).append(':').append(candidate.amount()).append(',');
                }
                value.append(']');
            }
            return sha256(value.toString());
        }
    }

    /** 直接检查原始捕获结构，避免规划器能力差异掩盖 ring/color/remainder/多路线特征。 */
    private record CaptureFeatures(int coloredPatterns, int colorBuckets, int remainderInputs,
            int multiRouteKeys, int fuzzyInputs, int secondaryOutputPatterns, int cyclicSccs,
            int ringSccs) {
        private static CaptureFeatures read(Path capture) throws IOException {
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(capture, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonArray patterns = root.getAsJsonArray("patterns");
            int colored = 0;
            int remainders = 0;
            int fuzzy = 0;
            int secondary = 0;
            Set<Integer> colors = new HashSet<>();
            int[] producerColor = new int[patterns.size()];
            List<Set<Integer>> edges = new ArrayList<>(root.getAsJsonArray("keys").size());
            for (int key = 0; key < root.getAsJsonArray("keys").size(); key++) {
                edges.add(new HashSet<>());
            }
            for (int patternIndex = 0; patternIndex < patterns.size(); patternIndex++) {
                JsonObject pattern = patterns.get(patternIndex).getAsJsonObject();
                int color = pattern.get("color").getAsInt();
                producerColor[patternIndex] = color;
                if (color >= 0) {
                    colored++;
                    colors.add(color);
                }
                if (pattern.getAsJsonArray("outputs").size() > 1) {
                    secondary++;
                }
                for (var inputElement : pattern.getAsJsonArray("inputs")) {
                    JsonObject input = inputElement.getAsJsonObject();
                    if (input.get("remainder").getAsInt() >= 0) {
                        remainders++;
                    }
                    if (input.getAsJsonArray("accepts").size() > 1) {
                        fuzzy++;
                    }
                }
            }

            Map<Integer, List<Integer>> craftingFor = new HashMap<>();
            int multiRoute = 0;
            for (var entryElement : root.getAsJsonArray("craftingFor")) {
                JsonArray entry = entryElement.getAsJsonArray();
                int outputKey = entry.get(0).getAsInt();
                List<Integer> producers = new ArrayList<>();
                for (var producer : entry.get(1).getAsJsonArray()) {
                    producers.add(producer.getAsInt());
                }
                craftingFor.put(outputKey, producers);
                if (producers.size() > 1) {
                    multiRoute++;
                }
            }
            for (var entry : craftingFor.entrySet()) {
                int output = entry.getKey();
                for (int producer : entry.getValue()) {
                    JsonObject pattern = patterns.get(producer).getAsJsonObject();
                    for (var inputElement : pattern.getAsJsonArray("inputs")) {
                        for (var candidate : inputElement.getAsJsonObject().getAsJsonArray("candidates")) {
                            edges.get(output).add(candidate.getAsJsonArray().get(0).getAsInt());
                        }
                    }
                }
            }
            SccCounts scc = countCyclicSccs(edges, craftingFor, producerColor);
            return new CaptureFeatures(colored, colors.size(), remainders, multiRoute, fuzzy,
                    secondary, scc.cyclic(), scc.colored());
        }

        private void verify() {
            assertTrue(colorBuckets <= coloredPatterns, "color bucket count");
            assertTrue(ringSccs <= cyclicSccs, "ring SCCs must be cyclic SCCs");
            if (ringSccs > 0) {
                assertTrue(coloredPatterns > 0, "ring SCC requires colored patterns");
            }
        }

        private String toJson() {
            return String.format(Locale.ROOT,
                    "{\"ring_sccs\":%d,\"cyclic_sccs\":%d,\"colored_patterns\":%d,"
                            + "\"color_buckets\":%d,\"remainder_inputs\":%d,"
                            + "\"multi_route_keys\":%d,\"fuzzy_inputs\":%d,"
                            + "\"secondary_output_patterns\":%d}",
                    ringSccs, cyclicSccs, coloredPatterns, colorBuckets, remainderInputs,
                    multiRouteKeys, fuzzyInputs, secondaryOutputPatterns);
        }
    }

    private record SccCounts(int cyclic, int colored) {
    }

    private static SccCounts countCyclicSccs(List<Set<Integer>> edges,
            Map<Integer, List<Integer>> craftingFor, int[] producerColor) {
        int size = edges.size();
        int[] index = new int[size];
        int[] low = new int[size];
        Arrays.fill(index, -1);
        boolean[] onStack = new boolean[size];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        int[] nextIndex = {0};
        int[] counts = {0, 0};
        for (int node = 0; node < size; node++) {
            if (index[node] < 0) {
                tarjan(node, edges, craftingFor, producerColor, index, low, onStack, stack,
                        nextIndex, counts);
            }
        }
        return new SccCounts(counts[0], counts[1]);
    }

    private static void tarjan(int node, List<Set<Integer>> edges,
            Map<Integer, List<Integer>> craftingFor, int[] producerColor, int[] index, int[] low,
            boolean[] onStack, ArrayDeque<Integer> stack, int[] nextIndex, int[] counts) {
        index[node] = nextIndex[0];
        low[node] = nextIndex[0]++;
        stack.push(node);
        onStack[node] = true;
        for (int child : edges.get(node)) {
            if (index[child] < 0) {
                tarjan(child, edges, craftingFor, producerColor, index, low, onStack, stack,
                        nextIndex, counts);
                low[node] = Math.min(low[node], low[child]);
            } else if (onStack[child]) {
                low[node] = Math.min(low[node], index[child]);
            }
        }
        if (low[node] != index[node]) {
            return;
        }
        int members = 0;
        boolean selfLoop = edges.get(node).contains(node);
        boolean colored = false;
        int member;
        do {
            member = stack.pop();
            onStack[member] = false;
            members++;
            for (int producer : craftingFor.getOrDefault(member, List.of())) {
                colored |= producerColor[producer] >= 0;
            }
        } while (member != node);
        if (members > 1 || selfLoop) {
            counts[0]++;
            if (colored) {
                counts[1]++;
            }
        }
    }

    private static int positiveProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String differences(String reference, String actual) {
        if (actual == null) {
            return "actual canonical form unavailable";
        }
        Map<String, String> referenceLines = canonicalLines(reference);
        Map<String, String> actualLines = canonicalLines(actual);
        List<String> differences = new ArrayList<>();
        var labels = new java.util.LinkedHashSet<String>();
        labels.addAll(referenceLines.keySet());
        labels.addAll(actualLines.keySet());
        for (String label : labels) {
            if (differences.size() >= 8) {
                break;
            }
            String expected = referenceLines.get(label);
            String observed = actualLines.get(label);
            if (!java.util.Objects.equals(expected, observed)) {
                differences.add(label + ": expected=" + summarizeCanonicalValue(expected)
                        + ", actual=" + summarizeCanonicalValue(observed));
            }
        }
        return differences.isEmpty()
                ? "digest mismatch without canonical difference"
                : String.join(" | ", differences);
    }

    private static Map<String, String> canonicalLines(String canonical) {
        Map<String, String> lines = new java.util.LinkedHashMap<>();
        for (String line : canonical.lines().toList()) {
            int separator = line.indexOf('=');
            String label = separator < 0 ? line : line.substring(0, separator);
            lines.put(label, separator < 0 ? "" : line.substring(separator + 1));
        }
        return lines;
    }

    private static String summarizeCanonicalValue(String value) {
        if (value == null || value.length() <= 240) {
            return String.valueOf(value);
        }
        return value.substring(0, 160) + "...(" + value.length() + " chars, sha256="
                + sha256(value) + ')';
    }

    private static void consume(String value) {
        blackhole = 31 * blackhole + value.hashCode();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    private static String nullableJson(String value) {
        return value == null ? "null" : "\"" + json(value) + "\"";
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
