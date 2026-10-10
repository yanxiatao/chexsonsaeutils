package git.chexson.chexsonsaeutils.crafting.graphplan.replay;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import appeng.menu.me.crafting.CraftingPlanSummary;
import com.mojang.serialization.JsonOps;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanPostProcessor;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.recursive.SelectedInputsPlan;
import net.minecraft.world.level.Level;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes capture replays through the calculation/future/summary boundary used at runtime. */
public final class RuntimeReplayRunner {

    public enum Planner {
        AE2_NATIVE("ae2-native", true),
        GRAPH("graph", true),
        LINEAR("linear", false),
        NEOECOAE("neoecoae", true);

        private final String commandName;
        private final boolean productionEntry;

        Planner(String commandName, boolean productionEntry) {
            this.commandName = commandName;
            this.productionEntry = productionEntry;
        }

        public String commandName() {
            return this.commandName;
        }

        public boolean productionEntry() {
            return this.productionEntry;
        }

        public static Planner parse(String value) {
            return Arrays.stream(values())
                    .filter(planner -> planner.commandName.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown planner '" + value + "'; expected " + commandNames() + ", all"));
        }

        public static String commandNames() {
            return String.join(", ", Arrays.stream(values()).map(Planner::commandName).toList());
        }
    }

    public record Result(
            Planner planner,
            ICraftingPlan plan,
            CraftingPlanSummary summary,
            long elapsedNanos,
            long queueNanos,
            long calculationNanos,
            long compileNanos,
            long solveNanos,
            long retentionNanos,
            long convertNanos,
            long summaryNanos,
            String detail
    ) {
    }

    private record CalculationResult(
            ICraftingPlan plan,
            long compileNanos,
            long solveNanos,
            long retentionNanos,
            long convertNanos
    ) {
        private CalculationResult(ICraftingPlan plan) {
            this(plan, -1L, -1L, -1L, -1L);
        }
    }

    public record Sample(
            Planner planner,
            String phase,
            int round,
            Result result,
            String error
    ) {
        public boolean success() {
            return this.result != null && this.error == null;
        }
    }

    public record PlannerStatistics(
            Planner planner,
            int samples,
            int failures,
            double failureRate,
            long coldNanos,
            long p50Nanos,
            long p95Nanos,
            long maxNanos,
            long calculationP50Nanos,
            long compileP50Nanos,
            long solveP50Nanos,
            long retentionP50Nanos,
            long convertP50Nanos,
            long summaryP50Nanos,
            boolean semanticConsistent,
            String semanticSummary,
            String error
    ) {
    }

    public record Experiment(
            int warmups,
            int samples,
            List<String> executionOrder,
            List<Sample> observations,
            List<PlannerStatistics> statistics
    ) {
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chexsonsaeutils-runtime-replay");
        thread.setDaemon(true);
        return thread;
    });
    private static final ICraftingSimulationRequester REQUESTER = IActionSource::empty;

    private RuntimeReplayRunner() {
    }

    public static CompletableFuture<Result> run(
            CapturedPlanReplay.Loaded fixture,
            Planner planner,
            Level level
    ) {
        return runSample(fixture, planner, level);
    }

    public static CompletableFuture<Experiment> runExperiment(
            CapturedPlanReplay.Loaded fixture,
            List<Planner> planners,
            Level level,
            int warmups,
            int samples
    ) {
        if (planners.isEmpty()) {
            throw new IllegalArgumentException("at least one replay planner is required");
        }
        if (warmups < 0 || samples < 1) {
            throw new IllegalArgumentException("warmups must be >= 0 and samples must be >= 1");
        }
        List<Planner> selected = List.copyOf(planners);
        List<Sample> observations = new ArrayList<>();
        List<String> executionOrder = new ArrayList<>();
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
        int rotationSeed = Math.floorMod(fixture.target().hashCode(), selected.size());

        sequence = appendRound(sequence, fixture, selected, level, "cold", 0,
                rotationSeed, observations, executionOrder);
        for (int round = 0; round < warmups; round++) {
            sequence = appendRound(sequence, fixture, selected, level, "warmup", round,
                    rotationSeed + 1 + round, observations, executionOrder);
        }
        for (int round = 0; round < samples; round++) {
            sequence = appendRound(sequence, fixture, selected, level, "sample", round,
                    rotationSeed + 1 + warmups + round, observations, executionOrder);
        }
        return sequence.thenApply(ignored -> {
            List<PlannerStatistics> measured = selected.stream()
                    .map(planner -> statistics(planner, observations, samples))
                    .toList();
            String referenceSummary = measured.stream()
                    .filter(result -> result.semanticSummary() != null)
                    .map(PlannerStatistics::semanticSummary)
                    .findFirst()
                    .orElse(null);
            List<PlannerStatistics> validated = measured.stream()
                    .map(result -> withCrossPlannerSemantics(result, referenceSummary))
                    .toList();
            return new Experiment(warmups, samples, List.copyOf(executionOrder),
                    List.copyOf(observations), validated);
        });
    }

    private static PlannerStatistics withCrossPlannerSemantics(
            PlannerStatistics result,
            String referenceSummary
    ) {
        boolean semanticConsistent = result.semanticConsistent()
                && referenceSummary != null
                && referenceSummary.equals(result.semanticSummary());
        return new PlannerStatistics(
                result.planner(), result.samples(), result.failures(), result.failureRate(),
                result.coldNanos(), result.p50Nanos(), result.p95Nanos(), result.maxNanos(),
                result.calculationP50Nanos(), result.compileP50Nanos(), result.solveP50Nanos(),
                result.retentionP50Nanos(), result.convertP50Nanos(), result.summaryP50Nanos(),
                semanticConsistent, result.semanticSummary(), result.error());
    }

    private static CompletableFuture<Void> appendRound(
            CompletableFuture<Void> sequence,
            CapturedPlanReplay.Loaded fixture,
            List<Planner> planners,
            Level level,
            String phase,
            int round,
            int rotation,
            List<Sample> observations,
            List<String> executionOrder
    ) {
        int start = Math.floorMod(rotation, planners.size());
        CompletableFuture<Void> result = sequence;
        for (int offset = 0; offset < planners.size(); offset++) {
            Planner planner = planners.get((start + offset) % planners.size());
            result = result.thenCompose(ignored -> {
                executionOrder.add(phase + ":" + round + ":" + planner.commandName());
                return runSample(fixture, planner, level)
                        .handle((sample, failure) -> {
                            observations.add(failure == null
                                    ? new Sample(planner, phase, round, sample, null)
                                    : new Sample(planner, phase, round, null, rootMessage(failure)));
                            return null;
                        });
            });
        }
        return result;
    }

    private static CompletableFuture<Result> runSample(
            CapturedPlanReplay.Loaded fixture,
            Planner planner,
            Level level
    ) {
        long submittedNanos = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> {
            long calculationStarted = System.nanoTime();
            CalculationResult calculation = calculate(fixture, planner, level);
            long calculationCompleted = System.nanoTime();
            ICraftingPlan plan = calculation.plan();
            if (plan == null) {
                throw new CompletionException(new IllegalStateException(planner.commandName + " returned null"));
            }
            CraftingPlanSummary summary = CraftingPlanSummary.fromJob(
                    fixture.grid(), IActionSource.empty(), plan);
            long completedNanos = System.nanoTime();
            return new Result(
                    planner,
                    plan,
                    summary,
                    completedNanos - submittedNanos,
                    calculationStarted - submittedNanos,
                    calculationCompleted - calculationStarted,
                    calculation.compileNanos(),
                    calculation.solveNanos(),
                    calculation.retentionNanos(),
                    calculation.convertNanos(),
                    completedNanos - calculationCompleted,
                    describe(fixture, plan, summary));
        }, EXECUTOR);
    }

    private static PlannerStatistics statistics(Planner planner, List<Sample> observations, int expectedSamples) {
        List<Sample> measured = observations.stream()
                .filter(sample -> sample.planner() == planner && sample.phase().equals("sample"))
                .toList();
        List<Result> successful = measured.stream()
                .filter(Sample::success)
                .map(Sample::result)
                .toList();
        int failures = expectedSamples - successful.size();
        long[] elapsed = successful.stream().mapToLong(Result::elapsedNanos).sorted().toArray();
        long[] calculation = successful.stream().mapToLong(Result::calculationNanos).sorted().toArray();
        long[] compile = successful.stream().mapToLong(Result::compileNanos).filter(value -> value >= 0L)
                .sorted().toArray();
        long[] solve = successful.stream().mapToLong(Result::solveNanos).filter(value -> value >= 0L)
                .sorted().toArray();
        long[] retention = successful.stream().mapToLong(Result::retentionNanos).filter(value -> value >= 0L)
                .sorted().toArray();
        long[] convert = successful.stream().mapToLong(Result::convertNanos).filter(value -> value >= 0L)
                .sorted().toArray();
        long[] summary = successful.stream().mapToLong(Result::summaryNanos).sorted().toArray();
        Sample cold = observations.stream()
                .filter(sample -> sample.planner() == planner && sample.phase().equals("cold"))
                .findFirst().orElse(null);
        String semanticSummary = successful.isEmpty() ? null : successful.get(0).detail();
        boolean semanticConsistent = semanticSummary != null
                && successful.stream().allMatch(result -> semanticSummary.equals(result.detail()));
        String error = measured.stream().filter(sample -> !sample.success())
                .map(Sample::error).findFirst().orElse(null);
        if (error == null && cold != null && !cold.success()) {
            error = cold.error();
        }
        return new PlannerStatistics(
                planner,
                expectedSamples,
                failures,
                failures / (double) expectedSamples,
                cold != null && cold.success() ? cold.result().elapsedNanos() : -1L,
                percentile(elapsed, 50),
                percentile(elapsed, 95),
                elapsed.length == 0 ? -1L : elapsed[elapsed.length - 1],
                percentile(calculation, 50),
                percentile(compile, 50),
                percentile(solve, 50),
                percentile(retention, 50),
                percentile(convert, 50),
                percentile(summary, 50),
                semanticConsistent,
                semanticSummary,
                error);
    }

    private static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return -1L;
        }
        int index = (int) Math.ceil(percentile / 100.0D * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static CalculationResult calculate(
            CapturedPlanReplay.Loaded fixture,
            Planner planner,
            Level level
    ) {
        return switch (planner) {
            case AE2_NATIVE -> new CalculationResult(new CraftingCalculation(
                    level,
                    fixture.grid(),
                    REQUESTER,
                    new GenericStack(fixture.target(), fixture.amount()),
                    CalculationStrategy.REPORT_MISSING_ITEMS).run());
            case GRAPH -> productionGraphPlan(fixture, level);
            case LINEAR -> independentGraphPlan(fixture, graph -> LinearPlanner.solve(
                    graph, CalculationStrategy.REPORT_MISSING_ITEMS, fixture.amount()));
            case NEOECOAE -> new CalculationResult(NeoEcoAeRuntimeAdapter.calculate(fixture));
        };
    }

    private static CalculationResult productionGraphPlan(
            CapturedPlanReplay.Loaded fixture,
            Level level
    ) {
        long started = System.nanoTime();
        GraphPlanningBudget budget = new GraphPlanningBudget(0L);
        // 生产 OrderedPlanningCalculation 的递归 kernel 恒定启用 retention
        GraphPlanningSession session = GraphPlanningSession.create(
                fixture.craftingService(),
                fixture.stock(),
                fixture.target(),
                CalculationStrategy.REPORT_MISSING_ITEMS,
                budget,
                level,
                true,
                fixture.storage(),
                fixture.grid());
        long compileCompleted = System.nanoTime();

        var attempt = session.solve(fixture.amount(), GraphPlanningSession.BudgetPolicy.ENFORCED);
        // 免预算再解的触发判定与生产 kernel 共用会话层那一处，不再各写一遍条件。
        // recursiveRequired 这里恒 false：回放腿按捕获夹具解一次图规划，没有"非交递归计划不可"
        // 的那一型入口，因此只有夹具真成环时才拿得到豁免趟。
        if (session.shouldRetryWithoutBudget(false, attempt)) {
            attempt = session.solve(fixture.amount(), GraphPlanningSession.BudgetPolicy.EXEMPT);
        }
        if (!attempt.handled() || attempt.plan() == null) {
            throw new IllegalStateException(
                    "production graph entry declined: " + attempt.declineReason());
        }
        return new CalculationResult(
                attempt.plan(),
                compileCompleted - started,
                budget.phaseNanos(GraphPlanningBudget.Phase.SOLVE),
                budget.phaseNanos(GraphPlanningBudget.Phase.RETENTION),
                budget.phaseNanos(GraphPlanningBudget.Phase.CONVERT)
                        + budget.currentPhaseNanos(GraphPlanningBudget.Phase.CONVERT));
    }

    @FunctionalInterface
    private interface GraphCalculation {
        SolveResult solve(CraftGraph graph);
    }

    private static CalculationResult independentGraphPlan(
            CapturedPlanReplay.Loaded fixture,
            GraphCalculation calculation
    ) {
        long compileStarted = System.nanoTime();
        CraftGraph graph = GraphCompiler.compile(
                fixture.craftingService(), fixture.stock(), fixture.target(), null);
        long compileCompleted = System.nanoTime();
        if (graph.hasDeclined()) {
            throw new IllegalStateException("graph compile declined: " + graph.declineReason());
        }
        long solveStarted = System.nanoTime();
        SolveResult result = calculation.solve(graph);
        long solveCompleted = System.nanoTime();
        if (result.hasDeclined()) {
            throw new IllegalStateException("planner declined: " + result.declineReason());
        }
        result = GraphPlanPostProcessor.reconcileMissingWithStorage(
                result, fixture.storage(), fixture.craftingService());

        long retentionStarted = System.nanoTime();
        String retentionDecline = GraphPlanPostProcessor.applyRetention(
                result, graph, fixture.amount());
        long retentionCompleted = System.nanoTime();
        if (retentionDecline != null) {
            throw new IllegalStateException("planner declined: " + retentionDecline);
        }
        long convertStarted = System.nanoTime();
        String conversionDecline = GraphPlanPostProcessor.conversionDecline(result);
        if (conversionDecline != null) {
            throw new IllegalStateException("planner declined: " + conversionDecline);
        }
        ICraftingPlan plan = GraphPlanPostProcessor.convert(result, graph);
        long convertCompleted = System.nanoTime();
        return new CalculationResult(
                plan,
                compileCompleted - compileStarted,
                solveCompleted - solveStarted,
                retentionCompleted - retentionStarted,
                convertCompleted - convertStarted);
    }

    private static String describe(
            CapturedPlanReplay.Loaded fixture,
            ICraftingPlan plan,
            CraftingPlanSummary summary
    ) {
        String canonical = canonicalPlan(fixture, plan);
        return "sha256=" + sha256(canonical)
                + ", bytes=" + plan.bytes()
                + ", simulation=" + plan.simulation()
                + ", multiplePaths=" + plan.multiplePaths()
                + ", finalOutput=" + stack(fixture, plan.finalOutput())
                + ", used=" + entries(fixture, plan.usedItems())
                + ", emitted=" + entries(fixture, plan.emittedItems())
                + ", missing=" + entries(fixture, plan.missingItems())
                + ", patterns=" + patternEntries(fixture, plan.patternTimes())
                + ", summaryEntries=" + summary.getEntries().size();
    }

    private static String canonicalPlan(CapturedPlanReplay.Loaded fixture, ICraftingPlan plan) {
        StringBuilder canonical = new StringBuilder(16_384);
        canonical.append("final=").append(stack(fixture, plan.finalOutput())).append('\n');
        canonical.append("bytes=").append(plan.bytes()).append('\n');
        canonical.append("simulation=").append(plan.simulation()).append('\n');
        canonical.append("multiplePaths=").append(plan.multiplePaths()).append('\n');
        canonical.append("used=").append(entries(fixture, plan.usedItems())).append('\n');
        canonical.append("emitted=").append(entries(fixture, plan.emittedItems())).append('\n');
        canonical.append("missing=").append(entries(fixture, plan.missingItems())).append('\n');
        canonical.append("patterns=").append(patternEntries(fixture, plan.patternTimes())).append('\n');
        if (plan instanceof RecursivePlanContract recursive) {
            canonical.append("recursive=true\n");
            canonical.append("recursiveFinal=")
                    .append(recursive.chexsonsaeutils$dyeableRecursiveFinalOutputAmount()).append('\n');
            canonical.append("recursiveInitial=")
                    .append(entries(fixture, recursive.chexsonsaeutils$dyeableRecursiveInitialItems())).append('\n');
            canonical.append("recursiveInternal=")
                    .append(entries(fixture, recursive.chexsonsaeutils$dyeableRecursiveInternalItems())).append('\n');
        } else {
            canonical.append("recursive=false\n");
        }
        if (plan instanceof SelectedInputsPlan selected) {
            canonical.append("selectedInputs=")
                    .append(selectedEntries(fixture, selected.chexsonsaeutils$dyeableSelectedPatternInputs()))
                    .append('\n');
        } else {
            canonical.append("selectedInputs=\n");
        }
        return canonical.toString();
    }

    private static String entries(CapturedPlanReplay.Loaded fixture, KeyCounter counter) {
        List<String> entries = new ArrayList<>();
        for (var entry : counter) {
            entries.add(key(fixture, entry.getKey()) + ':' + entry.getLongValue());
        }
        entries.sort(null);
        return String.join(";", entries);
    }

    private static String patternEntries(
            CapturedPlanReplay.Loaded fixture,
            Map<IPatternDetails, Long> patterns
    ) {
        List<String> entries = new ArrayList<>();
        for (var entry : patterns.entrySet()) {
            entries.add(pattern(fixture, entry.getKey()) + ':' + entry.getValue());
        }
        entries.sort(null);
        return String.join(";", entries);
    }

    private static String selectedEntries(
            CapturedPlanReplay.Loaded fixture,
            Map<AEItemKey, Map<AEKey, Long>> selectedInputs
    ) {
        List<String> entries = new ArrayList<>();
        for (var patternEntry : selectedInputs.entrySet()) {
            List<String> inputs = new ArrayList<>();
            for (var input : patternEntry.getValue().entrySet()) {
                inputs.add(key(fixture, input.getKey()) + ':' + input.getValue());
            }
            inputs.sort(null);
            entries.add(key(fixture, patternEntry.getKey()) + '[' + String.join(";", inputs) + ']');
        }
        entries.sort(null);
        return String.join(";", entries);
    }

    private static String stack(CapturedPlanReplay.Loaded fixture, GenericStack stack) {
        return stack == null ? "null" : key(fixture, stack.what()) + ':' + stack.amount();
    }

    private static String pattern(CapturedPlanReplay.Loaded fixture, IPatternDetails pattern) {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append(key(fixture, pattern.getDefinition())).append("|outputs=");
        List<String> outputs = pattern.getOutputs().stream()
                .map(output -> stack(fixture, output)).sorted().toList();
        fingerprint.append(String.join(";", outputs)).append("|inputs=");
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            List<String> candidates = Arrays.stream(input.getPossibleInputs())
                    .map(candidate -> stack(fixture, candidate)).sorted().toList();
            fingerprint.append(input.getMultiplier()).append('[')
                    .append(String.join(";", candidates)).append("];");
        }
        return fingerprint.toString();
    }

    private static String key(CapturedPlanReplay.Loaded fixture, AEKey key) {
        return AEKey.CODEC.encodeStart(
                        fixture.registries().createSerializationContext(JsonOps.INSTANCE), key)
                .getOrThrow()
                .toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                result.append(String.format(Locale.ROOT, "%02x", part & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Soft integration: no NeoECOAE class is linked unless that optional mod is installed. */
    private static final class NeoEcoAeRuntimeAdapter {
        private static final String SERVICE =
                "cn.dancingsnow.neoecoae.impl.crafting.planner.ECOCraftingPlannerService";
        private static final String CANCELLATION =
                "cn.dancingsnow.neoecoae.impl.crafting.planner.ECOCancellation";

        private NeoEcoAeRuntimeAdapter() {
        }

        private static ICraftingPlan calculate(CapturedPlanReplay.Loaded fixture) {
            try {
                ClassLoader loader = RuntimeReplayRunner.class.getClassLoader();
                Class<?> serviceClass = Class.forName(SERVICE, true, loader);
                Class<?> cancellationClass = Class.forName(CANCELLATION, true, loader);
                Object cancellation = cancellationClass.getField("NONE").get(null);
                Object service = serviceClass.getDeclaredConstructor().newInstance();
                Method createSession = serviceClass.getMethod(
                        "createSession",
                        appeng.api.networking.crafting.ICraftingService.class,
                        appeng.api.stacks.AEKey.class,
                        KeyCounter.class,
                        boolean.class);
                Object session = invoke(createSession, service,
                        fixture.craftingService(), fixture.target(), fixture.stock(), true);
                Method ensureCompiled = session.getClass().getDeclaredMethod("ensureCompiled", cancellationClass);
                ensureCompiled.setAccessible(true);
                invoke(ensureCompiled, session, cancellation);
                Method plan = session.getClass().getMethod("plan", long.class, boolean.class, cancellationClass);
                Object result = invoke(plan, session, fixture.amount(), false, cancellation);
                String status = String.valueOf(invokeRequired(result, "status"));
                if (!"SUCCESS".equals(status.toUpperCase(Locale.ROOT))) {
                    throw new IllegalStateException("NeoECOAE status " + status);
                }
                Object rawPlan = invokeRequired(result, "plan");
                if (!(rawPlan instanceof ICraftingPlan craftingPlan)) {
                    throw new IllegalStateException("NeoECOAE SUCCESS returned no ICraftingPlan");
                }
                Object executionError = invokeRequired(result, "executionPlanError");
                if (executionError != null) {
                    throw new IllegalStateException("NeoECOAE execution plan error: " + executionError);
                }
                return craftingPlan;
            } catch (ClassNotFoundException | NoClassDefFoundError unavailable) {
                throw new IllegalStateException(
                        "NeoECOAE unavailable: optional mod/classes are not installed", unavailable);
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IllegalStateException("NeoECOAE unavailable: incompatible reflective API", failure);
            }
        }

        private static Object invokeRequired(Object target, String method) throws ReflectiveOperationException {
            if (target == null) {
                throw new IllegalStateException("NeoECOAE returned null");
            }
            return invoke(target.getClass().getMethod(method), target);
        }

        private static Object invoke(Method method, Object target, Object... arguments)
                throws ReflectiveOperationException {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw failure;
            }
        }
    }
}
