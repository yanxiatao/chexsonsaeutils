package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import git.chexson.chexsonsaeutils.crafting.formalmachine.ScaledCraftingPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.ScaledCraftingPatternEligibilityService;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class FormalMachinePlanningAggregationService {

    private static final long MIN_AGGREGATION_THRESHOLD = 16_384L;
    private static final long WORK_AGGREGATION_THRESHOLD = 16_384L;
    private static final int MAX_ANALYSIS_DEPTH = 64;
    private static final int MAX_ANALYSIS_STEPS = 4_096;
    private static final int MAX_DETERMINISTIC_PLANNING_STEPS = 16_384;
    private static final long MAX_DETERMINISTIC_PLANNING_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final int PLANNING_POOL_THREADS = 2;
    private static final int PLANNING_QUEUE_CAPACITY = 8;
    private static final long PLANNING_FUTURE_TIMEOUT_SECONDS = 5L;
    private static final ExecutorService PLANNING_POOL;

    static {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Formal Machine Planning Aggregator");
            thread.setDaemon(true);
            return thread;
        };
        PLANNING_POOL = new ThreadPoolExecutor(
                PLANNING_POOL_THREADS,
                PLANNING_POOL_THREADS,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(PLANNING_QUEUE_CAPACITY),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private FormalMachinePlanningAggregationService() {
    }

    public static @Nullable Future<ICraftingPlan> tryBeginCraftingCalculation(
            CraftingService craftingService,
            IGrid realGrid,
            Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy
    ) {
        if (craftingService == null || realGrid == null || level == null || what == null || amount <= 0L) {
            return null;
        }

        PlanningPathAnalysis analysis = analyzePath(craftingService, what, amount);
        AbstractHighCapacityCraftingHostBlockEntity host = analysis.host();
        if (host == null) {
            return null;
        }

        host.recordPlanningAggregationRequestForTest(amount);
        boolean amountTriggered = amount >= analysis.threshold();
        boolean deterministicDepthTriggered = analysis.depth() > 1
                && analysis.estimatedWork() > 0L;
        boolean workTriggered = analysis.estimatedWork() >= WORK_AGGREGATION_THRESHOLD || deterministicDepthTriggered;
        boolean deterministicTriggered = amountTriggered || workTriggered;
        host.recordPlanningWorkEstimateForTest(analysis.estimatedWork(), workTriggered);
        if (!supportsStrategy(strategy)) {
            if (!analysis.supported() && deterministicTriggered) {
                host.recordPlanningAggregationFallbackForTest(analysis.replacementAware());
            }
            return null;
        }
        if (!analysis.supported()) {
            if (deterministicTriggered) {
                host.recordPlanningAggregationFallbackForTest(analysis.replacementAware());
                host.recordPlanningAggregationFailureForTest(0L, 0, 0L);
                return CompletableFuture.completedFuture(missingPlan(what, amount));
            }
            return null;
        }
        if (!deterministicTriggered) {
            return null;
        }

        KeyCounter initialSnapshot = copyCounter(snapshotVisibleInventory(realGrid.getStorageService()));

        try {
            CompletableFuture<ICraftingPlan> future = new CompletableFuture<>();
            future.completeOnTimeout(
                    missingPlan(what, amount),
                    PLANNING_FUTURE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
            PLANNING_POOL.execute(() -> runFormalPlanningAsync(
                    future,
                    level,
                    host,
                    what,
                    amount,
                    initialSnapshot,
                    analysis,
                    deterministicTriggered
            ));
            return future;
        } catch (RejectedExecutionException ignored) {
            host.recordPlanningAggregationFallbackForTest(false);
            host.recordPlanningAggregationFailureForTest(0L, 0, 0L);
            return CompletableFuture.completedFuture(missingPlan(what, amount));
        }
    }

    private static void runFormalPlanningAsync(
            CompletableFuture<ICraftingPlan> future,
            Level level,
            AbstractHighCapacityCraftingHostBlockEntity host,
            AEKey what,
            long amount,
            KeyCounter initialSnapshot,
            PlanningPathAnalysis analysis,
            boolean deterministicTriggered
    ) {
        PlanningComputationResult result;
        try {
            result = runFormalPlanning(level, what, amount, initialSnapshot, analysis, deterministicTriggered);
        } catch (RuntimeException exception) {
            result = PlanningComputationResult.failure(missingPlan(what, amount));
        }
        PlanningComputationResult finalResult = result;
        Runnable completion = () -> {
            applyPlanningTelemetry(host, finalResult.telemetry());
            future.complete(finalResult.plan());
        };
        if (level.getServer() == null || level.getServer().isSameThread()) {
            completion.run();
        } else {
            try {
                level.getServer().execute(completion);
            } catch (RejectedExecutionException exception) {
                future.complete(finalResult.plan());
            }
        }
    }

    private static PlanningComputationResult runFormalPlanning(
            Level level,
            AEKey what,
            long amount,
            KeyCounter initialSnapshot,
            PlanningPathAnalysis analysis,
            boolean deterministicTriggered
    ) {
        PlanningComputationResult deterministicPlan = tryBuildDeterministicPlan(
                level,
                what,
                amount,
                analysis,
                deterministicTriggered,
                initialSnapshot
        );
        if (deterministicPlan != null) {
            return deterministicPlan;
        }
        return PlanningComputationResult.failure(missingPlan(what, amount));
    }

    private static @Nullable PlanningComputationResult tryBuildDeterministicPlan(
            Level level,
            AEKey what,
            long amount,
            PlanningPathAnalysis analysis,
            boolean deterministicTriggered,
            KeyCounter initialSnapshot
    ) {
        long startedAt = System.nanoTime();
        if (!analysis.supported() || !deterministicTriggered) {
            return null;
        }
        DeterministicPlanAccumulator accumulator = new DeterministicPlanAccumulator(
                initialSnapshot
        );
        DeterministicPlanningBudget budget = new DeterministicPlanningBudget(startedAt);
        if (!collectDeterministicRequirements(
                analysis.frozenGraph(),
                what,
                amount,
                accumulator,
                true,
                1,
                new HashMap<>(),
                budget
        )) {
            return null;
        }
        if (accumulator.patternTimes.isEmpty()) {
            if (accumulator.missingItems.isEmpty()) {
                return null;
            }
            ICraftingPlan plan = new AggregatedCraftingPlan(
                    new GenericStack(what, amount),
                    0L,
                    true,
                    false,
                    accumulator.usedItems,
                    new KeyCounter(),
                    accumulator.missingItems,
                    Map.of()
            );
            return PlanningComputationResult.hit(plan, System.nanoTime() - startedAt);
        }
        boolean simulation = !accumulator.missingItems.isEmpty();
        long totalBytes = 0L;
        for (long patternTime : accumulator.patternTimes.values()) {
            totalBytes = saturatingAdd(totalBytes, patternTime);
        }
        ScalingTransformResult scalingResult = scalePatternTimes(level, accumulator.patternTimes);
        ICraftingPlan plan = new AggregatedCraftingPlan(
                new GenericStack(what, amount),
                Math.max(1L, totalBytes),
                simulation,
                false,
                accumulator.usedItems,
                new KeyCounter(),
                accumulator.missingItems,
                scalingResult.patternTimes()
        );
        return PlanningComputationResult.hit(plan, System.nanoTime() - startedAt, scalingResult);
    }

    private static void applyPlanningTelemetry(
            AbstractHighCapacityCraftingHostBlockEntity host,
            PlanningComputationTelemetry telemetry
    ) {
        if (telemetry.deterministicHit()) {
            host.recordDeterministicPlanningHitForTest(telemetry.wallClockNanos());
        }
        if (telemetry.deterministicFallback()) {
            host.recordDeterministicPlanningFallbackForTest();
        }
        host.recordVirtualScaledPatternStatsForTest(
                telemetry.virtualScaledPatternHitCount(),
                telemetry.virtualScaledPatternFallbackCount(),
                telemetry.largestVirtualPatternMultiplier(),
                telemetry.virtualScaledPatternLogicalExecutionsSaved()
        );
        if (telemetry.aggregationFallback()) {
            host.recordPlanningAggregationFallbackForTest(false);
        }
        if (telemetry.aggregationFailure()) {
            host.recordPlanningAggregationFailureForTest(0L, 0, 0L);
        }
    }

    private static ScalingTransformResult scalePatternTimes(
            Level level,
            Map<IPatternDetails, Long> originalPatternTimes
    ) {
        if (level == null || originalPatternTimes.isEmpty()) {
            return new ScalingTransformResult(Map.copyOf(originalPatternTimes), 0L, 0L, 0, 0L);
        }
        Map<IPatternDetails, Long> scaledPatternTimes = new LinkedHashMap<>();
        Map<IPatternDetails, ScaledCraftingPatternEligibilityService.Eligibility> eligibilityCache = new HashMap<>();
        long hitCount = 0L;
        long fallbackCount = 0L;
        int largestMultiplier = 0;
        long logicalExecutionsSaved = 0L;

        for (Map.Entry<IPatternDetails, Long> entry : originalPatternTimes.entrySet()) {
            IPatternDetails patternDetails = entry.getKey();
            long craftCount = entry.getValue() == null ? 0L : entry.getValue();
            if (craftCount <= 0L) {
                continue;
            }
            ScaledCraftingPatternEligibilityService.Eligibility eligibility = eligibilityCache.computeIfAbsent(
                    patternDetails,
                    ignored -> ScaledCraftingPatternEligibilityService.analyze(level, patternDetails)
            );
            if (eligibility == null || craftCount <= 1L) {
                scaledPatternTimes.merge(patternDetails, craftCount, FormalMachinePlanningAggregationService::saturatingAdd);
                if (craftCount > 1L && patternDetails instanceof AECraftingPattern) {
                    fallbackCount = saturatingAdd(fallbackCount, 1L);
                }
                continue;
            }

            long remainingCrafts = craftCount;
            long emittedTaskCount = 0L;
            boolean scaled = false;
            while (remainingCrafts > 0L) {
                int multiplier = ScaledCraftingPatternEligibilityService.capMultiplier(eligibility, remainingCrafts);
                if (multiplier <= 1) {
                    scaledPatternTimes.merge(eligibility.basePattern(), remainingCrafts, FormalMachinePlanningAggregationService::saturatingAdd);
                    emittedTaskCount = saturatingAdd(emittedTaskCount, remainingCrafts);
                    if (!scaled) {
                        fallbackCount = saturatingAdd(fallbackCount, 1L);
                    }
                    remainingCrafts = 0L;
                    continue;
                }
                ScaledCraftingPattern scaledPattern = ScaledCraftingPatternEligibilityService.createScaledPattern(
                        eligibility,
                        multiplier
                );
                long scaledRuns = remainingCrafts / multiplier;
                if (scaledRuns <= 0L) {
                    scaledRuns = 1L;
                }
                scaledPatternTimes.merge(scaledPattern, scaledRuns, FormalMachinePlanningAggregationService::saturatingAdd);
                remainingCrafts -= saturatingMultiply(scaledRuns, multiplier);
                emittedTaskCount = saturatingAdd(emittedTaskCount, scaledRuns);
                hitCount = saturatingAdd(hitCount, scaledRuns);
                largestMultiplier = Math.max(largestMultiplier, multiplier);
                scaled = true;
            }
            if (scaled) {
                long savedForPattern = Math.max(0L, craftCount - emittedTaskCount);
                logicalExecutionsSaved = saturatingAdd(logicalExecutionsSaved, savedForPattern);
            }
        }

        return new ScalingTransformResult(
                Map.copyOf(scaledPatternTimes),
                hitCount,
                fallbackCount,
                largestMultiplier,
                logicalExecutionsSaved
        );
    }

    private static boolean collectDeterministicRequirements(
            Map<AEKey, FrozenPatternNode> frozenGraph,
            AEKey output,
            long requiredAmount,
            DeterministicPlanAccumulator accumulator,
            boolean root,
            int depth,
            Map<AEKey, Boolean> recursionGuard,
            DeterministicPlanningBudget budget
    ) {
        if (!budget.tryClaim()) {
            return false;
        }
        if (requiredAmount <= 0L) {
            return true;
        }
        if (!root) {
            long consumed = accumulator.consumeAvailable(output, requiredAmount);
            if (consumed > 0L) {
                requiredAmount -= consumed;
                if (requiredAmount <= 0L) {
                    return true;
                }
            }
        }
        if (depth > MAX_ANALYSIS_DEPTH || Boolean.TRUE.equals(recursionGuard.put(output, true))) {
            return false;
        }
        FrozenPatternNode node = frozenGraph.get(output);
        if (node == null) {
            accumulator.missingItems.add(output, requiredAmount);
            recursionGuard.remove(output);
            return true;
        }
        long outputAmount = node.outputAmount();
        if (outputAmount <= 0L) {
            recursionGuard.remove(output);
            return false;
        }
        long craftCount = ceilDiv(requiredAmount, outputAmount);
        accumulator.patternTimes.merge(node.pattern(), craftCount, FormalMachinePlanningAggregationService::saturatingAdd);
        for (Map.Entry<AEKey, Long> entry : node.inputs().entrySet()) {
            long inputAmount = saturatingMultiply(craftCount, entry.getValue());
            if (inputAmount <= 0L) {
                recursionGuard.remove(output);
                return false;
            }
            if (!collectDeterministicRequirements(
                    frozenGraph,
                    entry.getKey(),
                    inputAmount,
                    accumulator,
                    false,
                    depth + 1,
                    recursionGuard,
                    budget
            )) {
                recursionGuard.remove(output);
                return false;
            }
        }
        recursionGuard.remove(output);
        return true;
    }

    private static KeyCounter snapshotVisibleInventory(IStorageService storageService) {
        if (storageService == null) {
            return new KeyCounter();
        }
        return storageService.getInventory().getAvailableStacks();
    }

    private static PlanningPathAnalysis analyzePath(
            CraftingService craftingService,
            AEKey rootOutput,
            long rootAmount
    ) {
        ArrayDeque<PatternPathNode> queue = new ArrayDeque<>();
        queue.addLast(new PatternPathNode(rootOutput, Math.max(1L, rootAmount), 1));
        Map<AEKey, java.util.Collection<IPatternDetails>> craftingForCache = new HashMap<>();
        Map<AEKey, FrozenPatternNode> frozenGraph = new LinkedHashMap<>();

        AbstractHighCapacityCraftingHostBlockEntity resolvedHost = null;
        int maxDepth = 1;
        int steps = 0;
        long estimatedWork = 0L;

        while (!queue.isEmpty()) {
            PatternPathNode current = queue.removeFirst();
            if (++steps > MAX_ANALYSIS_STEPS || current.depth() > MAX_ANALYSIS_DEPTH) {
                return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
            }
            maxDepth = Math.max(maxDepth, current.depth());

            var patterns = craftingFor(craftingService, craftingForCache, current.output());
            if (patterns.isEmpty()) {
                continue;
            }
            FormalProviderOwnership ownership = formalProviderOwnership(craftingService, patterns);
            if (!ownership.allFormal()) {
                return PlanningPathAnalysis.unsupported(
                        resolvedHost != null ? resolvedHost : ownership.host(),
                        maxDepth,
                        estimatedWork
                );
            }
            AbstractHighCapacityCraftingHostBlockEntity patternsHost = ownership.host();
            if (patternsHost == null) {
                return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
            }
            if (!ownership.exclusive()) {
                return PlanningPathAnalysis.unsupported(
                        resolvedHost != null ? resolvedHost : patternsHost,
                        maxDepth,
                        estimatedWork
                );
            }
            if (resolvedHost != null && patternsHost != resolvedHost) {
                return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
            }
            resolvedHost = patternsHost;
            if (patterns.size() != 1) {
                return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
            }

            IPatternDetails pattern = patterns.iterator().next();
            if (!(pattern instanceof AECraftingPattern craftingPattern)
                    || craftingPattern.canSubstitute
                    || craftingPattern.canSubstituteFluids) {
                return PlanningPathAnalysis.replacementAware(resolvedHost, maxDepth, estimatedWork);
            }

            long outputAmount = outputAmountFor(pattern, current.output());
            if (outputAmount <= 0L) {
                return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
            }
            long craftCount = ceilDiv(current.requiredAmount(), outputAmount);
            long inputWorkUnits = 0L;
            Map<AEKey, Long> mergedInputs = new LinkedHashMap<>();
            Map<AEKey, Long> mergedCraftableInputs = new LinkedHashMap<>();
            for (IPatternDetails.IInput input : craftingPattern.getInputs()) {
                GenericStack[] possibleInputs = input.getPossibleInputs();
                if (possibleInputs.length != 1 || possibleInputs[0] == null || possibleInputs[0].amount() <= 0L) {
                    return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
                }
                long inputMultiplier = input.getMultiplier();
                if (inputMultiplier <= 0L) {
                    return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
                }
                inputWorkUnits = saturatingAdd(inputWorkUnits, inputMultiplier);

                GenericStack possibleInput = possibleInputs[0];
                AEKey inputKey = possibleInput.what();
                long inputAmount = saturatingMultiply(possibleInput.amount(), inputMultiplier);
                if (inputAmount <= 0L) {
                    return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
                }
                mergedInputs.merge(
                        inputKey,
                        inputAmount,
                        FormalMachinePlanningAggregationService::saturatingAdd
                );
                var inputPatterns = craftingFor(craftingService, craftingForCache, inputKey);
                if (!inputPatterns.isEmpty()) {
                    if (inputPatterns.size() != 1) {
                        return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
                    }
                    mergedCraftableInputs.merge(
                            inputKey,
                            saturatingMultiply(craftCount, inputAmount),
                            FormalMachinePlanningAggregationService::saturatingAdd
                    );
                }
            }
            FrozenPatternNode existingNode = frozenGraph.putIfAbsent(
                    current.output(),
                    new FrozenPatternNode(pattern, outputAmount, Map.copyOf(mergedInputs))
            );
            if (existingNode != null && existingNode.pattern() != pattern) {
                return PlanningPathAnalysis.unsupported(resolvedHost, maxDepth, estimatedWork);
            }
            for (Map.Entry<AEKey, Long> entry : mergedCraftableInputs.entrySet()) {
                queue.addLast(new PatternPathNode(
                        entry.getKey(),
                        entry.getValue(),
                        current.depth() + 1
                ));
            }
            estimatedWork = saturatingAdd(
                    estimatedWork,
                    saturatingMultiply(craftCount, saturatingAdd(1L, inputWorkUnits))
            );
        }

        return resolvedHost == null
                ? PlanningPathAnalysis.unsupported(null, maxDepth, estimatedWork)
                : PlanningPathAnalysis.supported(resolvedHost, maxDepth, estimatedWork, Map.copyOf(frozenGraph));
    }

    private static java.util.Collection<IPatternDetails> craftingFor(
            CraftingService craftingService,
            Map<AEKey, java.util.Collection<IPatternDetails>> cache,
            AEKey output
    ) {
        return cache.computeIfAbsent(output, craftingService::getCraftingFor);
    }

    private static @Nullable AbstractHighCapacityCraftingHostBlockEntity exclusiveFormalMachineProvider(
            CraftingService craftingService,
            IPatternDetails pattern
    ) {
        AbstractHighCapacityCraftingHostBlockEntity matchedHost = null;
        boolean sawFormalProvider = false;
        for (ICraftingProvider provider : craftingService.getProviders(pattern)) {
            if (!(provider instanceof AbstractHighCapacityCraftingHostBlockEntity host)) {
                continue;
            }
            sawFormalProvider = true;
            if (matchedHost != null && matchedHost != host) {
                return null;
            }
            matchedHost = host;
        }
        return sawFormalProvider ? matchedHost : null;
    }

    private static FormalProviderOwnership formalProviderOwnership(
            CraftingService craftingService,
            java.util.Collection<IPatternDetails> patterns
    ) {
        AbstractHighCapacityCraftingHostBlockEntity matchedHost = null;
        boolean allFormal = true;
        boolean exclusive = true;
        for (IPatternDetails pattern : patterns) {
            AbstractHighCapacityCraftingHostBlockEntity patternHost = exclusiveFormalMachineProvider(
                    craftingService,
                    pattern
            );
            if (patternHost == null) {
                allFormal = false;
                continue;
            }
            if (matchedHost != null && patternHost != matchedHost) {
                exclusive = false;
                continue;
            }
            matchedHost = patternHost;
        }
        return new FormalProviderOwnership(allFormal, exclusive, matchedHost);
    }

    private static long outputAmountFor(IPatternDetails pattern, AEKey output) {
        long amount = 0L;
        for (GenericStack stack : pattern.getOutputs()) {
            if (stack != null && output.equals(stack.what())) {
                amount = saturatingAdd(amount, Math.max(0L, stack.amount()));
            }
        }
        return amount;
    }

    private static long ceilDiv(long value, long divisor) {
        if (divisor <= 0L) {
            return Long.MAX_VALUE;
        }
        if (value <= 0L) {
            return 0L;
        }
        return 1L + (value - 1L) / divisor;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static boolean supportsStrategy(@Nullable CalculationStrategy strategy) {
        return strategy == CalculationStrategy.REPORT_MISSING_ITEMS
                || strategy == CalculationStrategy.CRAFT_LESS;
    }

    private static KeyCounter copyCounter(KeyCounter original) {
        KeyCounter copy = new KeyCounter();
        if (original != null) {
            copy.addAll(original);
        }
        return copy;
    }

    private static final class DeterministicPlanAccumulator {

        private final KeyCounter availableInputs;
        private final KeyCounter usedItems = new KeyCounter();
        private final KeyCounter missingItems = new KeyCounter();
        private final Map<IPatternDetails, Long> patternTimes = new LinkedHashMap<>();

        private DeterministicPlanAccumulator(
                KeyCounter availableInputs
        ) {
            this.availableInputs = copyCounter(availableInputs);
        }

        private long consumeAvailable(AEKey key, long requestedAmount) {
            if (key == null || requestedAmount <= 0L) {
                return 0L;
            }
            long consumed = 0L;
            long snapshotted = availableInputs.get(key);
            long fromSnapshot = Math.min(snapshotted, requestedAmount);
            if (fromSnapshot > 0L) {
                availableInputs.set(key, snapshotted - fromSnapshot);
                consumed = FormalMachinePlanningAggregationService.saturatingAdd(consumed, fromSnapshot);
            }
            if (consumed > 0L) {
                usedItems.add(key, consumed);
            }
            return consumed;
        }
    }

    private static final class DeterministicPlanningBudget {

        private final long startedAtNanos;
        private int remainingSteps = MAX_DETERMINISTIC_PLANNING_STEPS;

        private DeterministicPlanningBudget(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }

        private boolean tryClaim() {
            if (remainingSteps-- <= 0) {
                return false;
            }
            return System.nanoTime() - startedAtNanos < MAX_DETERMINISTIC_PLANNING_NANOS;
        }
    }

    private static ICraftingPlan missingPlan(AEKey what, long amount) {
        KeyCounter missingItems = new KeyCounter();
        long requestedAmount = Math.max(1L, amount);
        missingItems.add(what, requestedAmount);
        return new AggregatedCraftingPlan(
                new GenericStack(what, requestedAmount),
                0L,
                true,
                false,
                new KeyCounter(),
                new KeyCounter(),
                missingItems,
                Map.of()
        );
    }

    private record PatternPathNode(AEKey output, long requiredAmount, int depth) {
    }

    private record FrozenPatternNode(IPatternDetails pattern, long outputAmount, Map<AEKey, Long> inputs) {
    }

    private record FormalProviderOwnership(
            boolean allFormal,
            boolean exclusive,
            @Nullable AbstractHighCapacityCraftingHostBlockEntity host
    ) {
    }

    private record PlanningComputationResult(ICraftingPlan plan, PlanningComputationTelemetry telemetry) {
        private static PlanningComputationResult hit(
                ICraftingPlan plan,
                long wallClockNanos
        ) {
            return hit(plan, wallClockNanos, new ScalingTransformResult(Map.of(), 0L, 0L, 0, 0L));
        }

        private static PlanningComputationResult hit(
                ICraftingPlan plan,
                long wallClockNanos,
                ScalingTransformResult scalingResult
        ) {
            return new PlanningComputationResult(
                    plan,
                    new PlanningComputationTelemetry(
                            true,
                            false,
                            false,
                            false,
                            Math.max(0L, wallClockNanos),
                            scalingResult.hitCount(),
                            scalingResult.fallbackCount(),
                            scalingResult.largestMultiplier(),
                            scalingResult.logicalExecutionsSaved()
                    )
            );
        }

        private static PlanningComputationResult failure(ICraftingPlan plan) {
            return new PlanningComputationResult(
                    plan,
                    new PlanningComputationTelemetry(false, true, true, true, 0L, 0L, 0L, 0, 0L)
            );
        }
    }

    private record PlanningComputationTelemetry(
            boolean deterministicHit,
            boolean deterministicFallback,
            boolean aggregationFallback,
            boolean aggregationFailure,
            long wallClockNanos,
            long virtualScaledPatternHitCount,
            long virtualScaledPatternFallbackCount,
            int largestVirtualPatternMultiplier,
            long virtualScaledPatternLogicalExecutionsSaved
    ) {
    }

    private record ScalingTransformResult(
            Map<IPatternDetails, Long> patternTimes,
            long hitCount,
            long fallbackCount,
            int largestMultiplier,
            long logicalExecutionsSaved
    ) {
    }

    private record PlanningPathAnalysis(
            boolean supported,
            boolean replacementAware,
            @Nullable AbstractHighCapacityCraftingHostBlockEntity host,
            int depth,
            long threshold,
            long estimatedWork,
            Map<AEKey, FrozenPatternNode> frozenGraph
    ) {
        private static PlanningPathAnalysis supported(
                AbstractHighCapacityCraftingHostBlockEntity host,
                int depth,
                long estimatedWork,
                Map<AEKey, FrozenPatternNode> frozenGraph
        ) {
            return new PlanningPathAnalysis(
                    true,
                    false,
                    host,
                    Math.max(1, depth),
                    MIN_AGGREGATION_THRESHOLD,
                    Math.max(0L, estimatedWork),
                    Map.copyOf(frozenGraph)
            );
        }

        private static PlanningPathAnalysis unsupported(
                @Nullable AbstractHighCapacityCraftingHostBlockEntity host,
                int depth,
                long estimatedWork
        ) {
            return new PlanningPathAnalysis(
                    false,
                    false,
                    host,
                    Math.max(1, depth),
                    MIN_AGGREGATION_THRESHOLD,
                    Math.max(0L, estimatedWork),
                    Map.of()
            );
        }

        private static PlanningPathAnalysis replacementAware(
                @Nullable AbstractHighCapacityCraftingHostBlockEntity host,
                int depth,
                long estimatedWork
        ) {
            return new PlanningPathAnalysis(
                    false,
                    true,
                    host,
                    Math.max(1, depth),
                    MIN_AGGREGATION_THRESHOLD,
                    Math.max(0L, estimatedWork),
                    Map.of()
            );
        }
    }
}
