package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.config.Actionable;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.me.service.CraftingService;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskCompletionRoute;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternSelectedInputsPlan;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.IFormalMachineAggregatedPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.IFormalMachineDelegatingPattern;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceAccessor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FormalMachinePlanningAggregationService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FormalMachinePlanningAggregationService() {
    }

    public static Future<ICraftingPlan> tryBeginCraftingCalculation(
            @Nullable CraftingService craftingService,
            @Nullable Level level,
            @Nullable AEKey what,
            long amount,
            @Nullable CalculationStrategy strategy,
            @Nullable Future<ICraftingPlan> nativeFuture
    ) {
        if (nativeFuture == null
                || craftingService == null
                || level == null
                || what == null
                || amount <= 0L
                || !supportsStrategy(strategy)) {
            return nativeFuture;
        }
        return wrapNativeFuture(craftingService, level, what, amount, nativeFuture);
    }

    static Future<ICraftingPlan> wrapNativeFuture(
            CraftingService craftingService,
            Level level,
            AEKey what,
            long amount,
            Future<ICraftingPlan> nativeFuture
    ) {
        return new AggregatingPlanningFuture(craftingService, level, what, amount, nativeFuture);
    }

    private static boolean supportsStrategy(@Nullable CalculationStrategy strategy) {
        return strategy == CalculationStrategy.REPORT_MISSING_ITEMS
                || strategy == CalculationStrategy.CRAFT_LESS;
    }

    private static ICraftingPlan rewriteNativePlan(
            CraftingService craftingService,
            Level level,
            AEKey requestedOutput,
            long requestedAmount,
            ICraftingPlan nativePlan
    ) {
        if (nativePlan == null
                || nativePlan.finalOutput() == null
                || nativePlan.finalOutput().what() == null
                || nativePlan.patternTimes().isEmpty()) {
            return nativePlan;
        }

        SelectedPlanGraph selectedGraph = buildSelectedPlanGraph(
                craftingService,
                nativePlan,
                nativePlan.finalOutput().what(),
                nativePlan.patternTimes()
        );
        if (selectedGraph == null || selectedGraph.nodes().isEmpty()) {
            return nativePlan;
        }

        List<HostAggregationCandidate> candidates = buildHostAggregationCandidates(
                level,
                nativePlan,
                selectedGraph
        );
        if (candidates.isEmpty()) {
            return nativePlan;
        }

        Map<IPatternDetails, Long> rewrittenPatternTimes = new LinkedHashMap<>(nativePlan.patternTimes());
        long startedAt = System.nanoTime();
        boolean changed = false;
        List<HostAggregationCandidate> appliedCandidates = new ArrayList<>();
        for (HostAggregationCandidate candidate : candidates) {
            candidate.host().recordPlanningLiveSnapshotRequestForTest();
        }
        KeyCounter liveVisibleStacks = snapshotLiveVisibleStacks(craftingService);

        for (HostAggregationCandidate candidate : candidates) {
            FormalMachineAggregatedPattern aggregatedPattern = FormalMachineAggregatedPattern.create(
                    level.registryAccess(),
                    candidate.basePattern(),
                    FormalMachineHostLocator.fromHost(candidate.host()),
                    candidate.boundaryInputs(),
                    candidate.aggregatedOutputs(),
                    candidate.aggregatedRemainders(),
                    candidate.steps(),
                    candidate.totalTicks()
            );
            if (aggregatedPattern == null) {
                LOGGER.warn(
                        "Failed to build aggregated formal-machine pattern for host {} and output {}",
                        candidate.host().getBlockPos(),
                        nativePlan.finalOutput()
                );
                continue;
            }

            boolean removedAny = false;
            for (IPatternDetails originalPattern : candidate.originalPatterns()) {
                removedAny |= rewrittenPatternTimes.remove(originalPattern) != null;
            }
            if (!removedAny) {
                continue;
            }

            rewrittenPatternTimes.put(aggregatedPattern, 1L);
            changed = true;
            appliedCandidates.add(candidate);
        }

        if (!changed) {
            return nativePlan;
        }

        Map<IPatternDetails, Long> dependencyOrderedPatternTimes = orderPatternTimesByDependencies(
                rewrittenPatternTimes,
                nativePlan
        );
        if (dependencyOrderedPatternTimes == null) {
            LOGGER.warn(
                    "Failed to topologically order rewritten formal-machine crafting plan for output {} amount {}",
                    requestedOutput,
                    requestedAmount
            );
            return nativePlan;
        }
        rewrittenPatternTimes = dependencyOrderedPatternTimes;

        long wallClockNanos = Math.max(0L, System.nanoTime() - startedAt);
        long rewrittenBytes = computeRewrittenBytes(appliedCandidates);
        for (HostAggregationCandidate candidate : appliedCandidates) {
            candidate.host().recordPlanningAggregationRequestForTest(requestedAmount);
            candidate.host().recordPlanningWorkEstimateForTest(candidate.estimatedWork(), true);
            candidate.host().recordDeterministicPlanningHitForTest(wallClockNanos);
        }

        KeyCounter rewrittenMissingItems = mergeMissingItems(
                nativePlan.missingItems(),
                appliedCandidates,
                liveVisibleStacks
        );

        return new AggregatedCraftingPlan(
                nativePlan.finalOutput(),
                rewrittenBytes,
                !rewrittenMissingItems.isEmpty(),
                nativePlan.multiplePaths(),
                computeRewrittenUsedItems(rewrittenPatternTimes, rewrittenMissingItems, nativePlan),
                copyCounter(nativePlan.emittedItems()),
                rewrittenMissingItems,
                immutableOrderedPatternTimes(rewrittenPatternTimes),
                usesDyeableRecursivePlanning(nativePlan),
                copyCounter(dyeableRecursiveInitialItems(nativePlan)),
                copyCounter(dyeableRecursiveInternalItems(nativePlan)),
                dyeableRecursiveFinalOutputAmount(nativePlan)
        );
    }

    private static @Nullable SelectedPlanGraph buildSelectedPlanGraph(
            CraftingService craftingService,
            ICraftingPlan nativePlan,
            AEKey rootOutput,
            Map<IPatternDetails, Long> selectedPatternTimes
    ) {
        if (selectedPatternTimes == null || selectedPatternTimes.isEmpty()) {
            return null;
        }

        Map<AEKey, List<SelectedGraphNode>> nodesByOutput = new LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Long> entry : selectedPatternTimes.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            long craftCount = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
            if (pattern == null || craftCount <= 0L) {
                continue;
            }

            PatternDefinitionKey definitionKey = PatternDefinitionKey.of(pattern);
            if (definitionKey == null) {
                return null;
            }

            AbstractHighCapacityCraftingHostBlockEntity host = exclusiveFormalMachineProvider(craftingService, pattern);
            recordPlanningHelperUsage(host, host == null);

            Map<AEKey, Long> inputs = describeAggregationInputs(nativePlan, pattern);
            recordPlanningHelperUsage(host, inputs == null);
            if (inputs == null) {
                LOGGER.warn("Selected crafting pattern {} has non-deterministic inputs", pattern.getDefinition());
                return null;
            }
            for (GenericStack output : pattern.getOutputs()) {
                if (output == null || output.what() == null || output.amount() <= 0L) {
                    continue;
                }
                nodesByOutput.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(
                        new SelectedGraphNode(
                                definitionKey,
                                pattern,
                                craftCount,
                                output.what(),
                                output.amount(),
                                Map.copyOf(inputs),
                                host
                        )
                );
            }
        }

        ArrayDeque<AEKey> pending = new ArrayDeque<>();
        pending.addLast(rootOutput);

        Map<AEKey, SelectedGraphNode> nodes = new LinkedHashMap<>();

        while (!pending.isEmpty()) {
            AEKey current = pending.removeFirst();
            if (current == null || nodes.containsKey(current)) {
                continue;
            }

            List<SelectedGraphNode> candidates = nodesByOutput.getOrDefault(current, List.of());
            if (candidates.isEmpty()) {
                if (Objects.equals(current, rootOutput)) {
                    return null;
                }
                continue;
            }
            SelectedGraphNode resolved = collapseEquivalentCandidates(current, candidates);
            if (resolved == null) {
                LOGGER.warn("Output {} resolves to multiple non-equivalent selected native planning patterns", current);
                return null;
            }
            nodes.put(current, resolved);
            for (AEKey input : resolved.inputs().keySet()) {
                if (input != null && !nodes.containsKey(input) && nodesByOutput.containsKey(input)) {
                    pending.addLast(input);
                }
            }
        }

        return new SelectedPlanGraph(Map.copyOf(nodes));
    }

    private static @Nullable SelectedGraphNode collapseEquivalentCandidates(
            AEKey output,
            List<SelectedGraphNode> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }

        SelectedGraphNode base = candidates.getFirst();
        EquivalentSelectedNodeKey baseKey = EquivalentSelectedNodeKey.of(base);
        if (base == null || baseKey == null) {
            return null;
        }

        long aggregatedCraftCount = 0L;
        IPatternDetails selectedPattern = base.pattern();
        for (SelectedGraphNode candidate : candidates) {
            EquivalentSelectedNodeKey candidateKey = EquivalentSelectedNodeKey.of(candidate);
            if (candidate == null || candidateKey == null || !baseKey.equals(candidateKey)) {
                return null;
            }
            if (selectedPattern != candidate.pattern()
                    && comparePatternDefinitions(selectedPattern, candidate.pattern()) > 0) {
                selectedPattern = candidate.pattern();
            }
            aggregatedCraftCount = saturatingAdd(aggregatedCraftCount, candidate.craftCount());
        }
        return new SelectedGraphNode(
                base.definitionKey(),
                selectedPattern,
                aggregatedCraftCount,
                output,
                base.outputAmount(),
                base.inputs(),
                base.host()
        );
    }

    private static List<HostAggregationCandidate> buildHostAggregationCandidates(
            Level level,
            ICraftingPlan nativePlan,
            SelectedPlanGraph selectedGraph
    ) {
        Map<AbstractHighCapacityCraftingHostBlockEntity, Map<AEKey, SelectedGraphNode>> grouped = new LinkedHashMap<>();
        for (Map.Entry<AEKey, SelectedGraphNode> entry : selectedGraph.nodes().entrySet()) {
            SelectedGraphNode node = entry.getValue();
            if (unwrapBaseCraftingPattern(node.pattern()) == null || node.host() == null) {
                continue;
            }
            grouped.computeIfAbsent(node.host(), ignored -> new LinkedHashMap<>()).put(entry.getKey(), node);
        }

        List<HostAggregationCandidate> candidates = new ArrayList<>();
        for (Map.Entry<AbstractHighCapacityCraftingHostBlockEntity, Map<AEKey, SelectedGraphNode>> entry : grouped.entrySet()) {
            Map<AEKey, SelectedGraphNode> hostNodes = entry.getValue();
            for (Set<AEKey> segment : splitPerPatternFormalAggregationSegments(formalInputsByOutput(hostNodes))) {
                Map<AEKey, SelectedGraphNode> segmentNodes = selectSegmentNodes(hostNodes, segment);
                HostAggregationCandidate candidate = buildHostAggregationCandidate(
                        level,
                        nativePlan,
                        entry.getKey(),
                        segmentNodes
                );
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return List.copyOf(candidates);
    }

    static List<Set<AEKey>> splitPerPatternFormalAggregationSegments(
            Map<AEKey, ? extends Collection<AEKey>> formalInputsByOutput
    ) {
        // ponytail: semantics hook only, still delegates to the single dependency splitter for now.
        return splitFormalDependencySegments(formalInputsByOutput);
    }

    static List<Set<AEKey>> splitFormalDependencySegments(
            Map<AEKey, ? extends Collection<AEKey>> formalInputsByOutput
    ) {
        return splitFormalDependencySegments(formalInputsByOutput, Set.of());
    }

    static List<Set<AEKey>> splitFormalDependencySegments(
            Map<AEKey, ? extends Collection<AEKey>> formalInputsByOutput,
            Set<AEKey> segmentStartOutputs
    ) {
        if (formalInputsByOutput == null || formalInputsByOutput.isEmpty()) {
            return List.of();
        }
        Set<AEKey> forcedSegmentStarts = segmentStartOutputs == null ? Set.of() : segmentStartOutputs;

        Map<AEKey, LinkedHashSet<AEKey>> adjacency = new LinkedHashMap<>();
        for (AEKey output : formalInputsByOutput.keySet()) {
            if (output != null) {
                adjacency.putIfAbsent(output, new LinkedHashSet<>());
            }
        }

        for (Map.Entry<AEKey, ? extends Collection<AEKey>> entry : formalInputsByOutput.entrySet()) {
            AEKey consumerOutput = entry.getKey();
            if (consumerOutput == null || !adjacency.containsKey(consumerOutput) || entry.getValue() == null) {
                continue;
            }
            for (AEKey inputKey : entry.getValue()) {
                if (inputKey == null || !adjacency.containsKey(inputKey)) {
                    continue;
                }
                if (forcedSegmentStarts.contains(consumerOutput)) {
                    continue;
                }
                adjacency.get(inputKey).add(consumerOutput);
                adjacency.get(consumerOutput).add(inputKey);
            }
        }

        List<Set<AEKey>> segments = new ArrayList<>();
        Set<AEKey> visited = new LinkedHashSet<>();
        for (AEKey start : adjacency.keySet()) {
            if (!visited.add(start)) {
                continue;
            }

            LinkedHashSet<AEKey> segment = new LinkedHashSet<>();
            ArrayDeque<AEKey> pending = new ArrayDeque<>();
            pending.addLast(start);
            while (!pending.isEmpty()) {
                AEKey current = pending.removeFirst();
                segment.add(current);
                for (AEKey next : adjacency.getOrDefault(current, new LinkedHashSet<>())) {
                    if (visited.add(next)) {
                        pending.addLast(next);
                    }
                }
            }
            segments.add(Set.copyOf(segment));
        }
        return List.copyOf(segments);
    }

    private static Set<AEKey> externalProducedInputConsumers(
            Map<AEKey, SelectedGraphNode> hostNodes,
            Map<AEKey, SelectedGraphNode> selectedNodes,
            AbstractHighCapacityCraftingHostBlockEntity host
    ) {
        if (hostNodes == null || hostNodes.isEmpty() || selectedNodes == null || selectedNodes.isEmpty()) {
            return Set.of();
        }
        Set<AEKey> consumers = new LinkedHashSet<>();
        for (Map.Entry<AEKey, SelectedGraphNode> entry : hostNodes.entrySet()) {
            SelectedGraphNode consumer = entry.getValue();
            if (entry.getKey() == null || consumer == null || consumer.inputs() == null) {
                continue;
            }
            for (AEKey inputKey : consumer.inputs().keySet()) {
                SelectedGraphNode producer = selectedNodes.get(inputKey);
                if (producer != null && producer.host() != host) {
                    consumers.add(entry.getKey());
                    break;
                }
            }
        }
        return Set.copyOf(consumers);
    }

    private static Map<AEKey, Collection<AEKey>> formalInputsByOutput(Map<AEKey, SelectedGraphNode> hostNodes) {
        Map<AEKey, Collection<AEKey>> inputsByOutput = new LinkedHashMap<>();
        if (hostNodes == null || hostNodes.isEmpty()) {
            return inputsByOutput;
        }
        for (Map.Entry<AEKey, SelectedGraphNode> entry : hostNodes.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                inputsByOutput.put(entry.getKey(), entry.getValue().inputs().keySet());
            }
        }
        return inputsByOutput;
    }

    private static Map<AEKey, SelectedGraphNode> selectSegmentNodes(
            Map<AEKey, SelectedGraphNode> hostNodes,
            Set<AEKey> segment
    ) {
        Map<AEKey, SelectedGraphNode> selected = new LinkedHashMap<>();
        if (hostNodes == null || hostNodes.isEmpty() || segment == null || segment.isEmpty()) {
            return selected;
        }
        for (Map.Entry<AEKey, SelectedGraphNode> entry : hostNodes.entrySet()) {
            if (segment.contains(entry.getKey())) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    private static @Nullable HostAggregationCandidate buildHostAggregationCandidate(
            Level level,
            ICraftingPlan nativePlan,
            AbstractHighCapacityCraftingHostBlockEntity host,
            Map<AEKey, SelectedGraphNode> hostNodes
    ) {
        List<SelectedGraphNode> executionOrder = topoSortHostNodes(hostNodes);
        if (executionOrder == null || executionOrder.isEmpty()) {
            return null;
        }

        Map<AEKey, Long> totalInputs = new LinkedHashMap<>();
        Map<AEKey, Long> totalPrimaryOutputs = new LinkedHashMap<>();
        Map<AEKey, Long> totalRemainders = new LinkedHashMap<>();
        LinkedHashSet<IPatternDetails> originalPatterns = new LinkedHashSet<>();
        List<FormalMachineAggregationStep> steps = new ArrayList<>(executionOrder.size());

        for (SelectedGraphNode node : executionOrder) {
            ItemStack patternDefinition = node.pattern().getDefinition().toStack();
            if (patternDefinition.isEmpty()) {
                return null;
            }

            Map<AEItemKey, Long> singleRunRemainders =
                    FormalMachineAggregationRemainderHelper.computeSingleRunRemainders(level, node.pattern());
            if (singleRunRemainders == null) {
                return null;
            }

            List<GenericStack> stepInputs = toScaledStacks(node.inputs(), node.craftCount());
            long primaryAmount = multiply(node.outputAmount(), node.craftCount());
            if (primaryAmount <= 0L) {
                return null;
            }
            List<GenericStack> stepRemainders = toScaledStacks(singleRunRemainders, node.craftCount());

            steps.add(new FormalMachineAggregationStep(
                    patternDefinition,
                    node.craftCount(),
                    stepInputs,
                    new GenericStack(node.outputKey(), primaryAmount),
                    stepRemainders,
                    TaskCompletionRoute.AE_STORAGE
            ));

            mergeStacks(totalInputs, stepInputs);
            mergeStack(totalPrimaryOutputs, node.outputKey(), primaryAmount);
            mergeStacks(totalRemainders, stepRemainders);
            originalPatterns.add(node.pattern());
        }

        if (steps.isEmpty() || originalPatterns.isEmpty()) {
            return null;
        }

        Map<AEKey, Long> totalProduced = new LinkedHashMap<>(totalPrimaryOutputs);
        mergeStackMaps(totalProduced, totalRemainders);

        KeyCounter recursiveInitialItems = filterCandidateRecursiveInitialItems(nativePlan, totalProduced);

        Map<AEKey, Long> boundaryInputs = describeAggregatedBoundaryInputs(steps);
        if (boundaryInputs == null) {
            return null;
        }
        Map<AEKey, Long> externalMissingInputs = extractExternalMissingInputs(
                nativePlan.missingItems(),
                totalProduced,
                recursiveInitialItems
        );
        Map<AEKey, Long> boundaryOutputs = subtractPositive(totalProduced, totalInputs);
        restoreRecursiveInitialBoundaryOutputs(boundaryOutputs, recursiveInitialItems);
        if (boundaryOutputs.isEmpty()) {
            return null;
        }

        SplitBoundaryOutputs splitOutputs = splitBoundaryOutputs(
                nativePlan.finalOutput(),
                boundaryOutputs,
                totalPrimaryOutputs
        );
        if (splitOutputs.outputs().isEmpty() && !splitOutputs.remainders().isEmpty()) {
            List<GenericStack> outputs = new ArrayList<>();
            outputs.add(splitOutputs.remainders().getFirst());
            List<GenericStack> remainders = new ArrayList<>(splitOutputs.remainders());
            remainders.removeFirst();
            splitOutputs = new SplitBoundaryOutputs(List.copyOf(outputs), List.copyOf(remainders));
        }
        if (splitOutputs.outputs().isEmpty()) {
            return null;
        }

        AECraftingPattern basePattern = chooseBasePattern(nativePlan.finalOutput(), hostNodes, executionOrder);
        if (basePattern == null) {
            return null;
        }

        return new HostAggregationCandidate(
                host,
                basePattern,
                toGenericStacks(boundaryInputs),
                toGenericStacks(externalMissingInputs),
                splitOutputs.outputs(),
                splitOutputs.remainders(),
                List.copyOf(steps),
                saturatingIntMultiply(host.getCurrentOperationTicks(), Math.max(1, steps.size())),
                List.copyOf(originalPatterns.stream().toList()),
                estimateAggregatedWork(steps)
        );
    }

    static @Nullable List<AEKey> topoSortDependencyOutputs(
            Map<AEKey, ? extends Collection<AEKey>> inputsByOutput
    ) {
        if (inputsByOutput == null || inputsByOutput.isEmpty()) {
            return List.of();
        }

        Map<AEKey, Integer> indegree = new LinkedHashMap<>();
        Map<AEKey, List<AEKey>> adjacency = new LinkedHashMap<>();
        for (AEKey output : inputsByOutput.keySet()) {
            if (output == null) {
                continue;
            }
            indegree.putIfAbsent(output, 0);
            adjacency.putIfAbsent(output, new ArrayList<>());
        }

        for (Map.Entry<AEKey, ? extends Collection<AEKey>> entry : inputsByOutput.entrySet()) {
            AEKey consumerOutput = entry.getKey();
            if (consumerOutput == null || !adjacency.containsKey(consumerOutput) || entry.getValue() == null) {
                continue;
            }
            for (AEKey inputKey : entry.getValue()) {
                if (inputKey == null || inputKey.equals(consumerOutput) || !adjacency.containsKey(inputKey)) {
                    continue;
                }
                adjacency.computeIfAbsent(inputKey, ignored -> new ArrayList<>()).add(consumerOutput);
                indegree.merge(consumerOutput, 1, Integer::sum);
            }
        }

        ArrayDeque<AEKey> ready = new ArrayDeque<>();
        for (Map.Entry<AEKey, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }

        List<AEKey> ordered = new ArrayList<>(indegree.size());
        while (!ready.isEmpty()) {
            AEKey current = ready.removeFirst();
            ordered.add(current);
            for (AEKey consumer : adjacency.getOrDefault(current, List.of())) {
                int next = indegree.getOrDefault(consumer, 0) - 1;
                indegree.put(consumer, next);
                if (next == 0) {
                    ready.addLast(consumer);
                }
            }
        }

        return ordered.size() == indegree.size() ? List.copyOf(ordered) : null;
    }

    private static @Nullable List<SelectedGraphNode> topoSortHostNodes(Map<AEKey, SelectedGraphNode> hostNodes) {
        List<AEKey> orderedOutputs = topoSortDependencyOutputs(formalInputsByOutput(hostNodes));
        if (orderedOutputs == null || orderedOutputs.size() != hostNodes.size()) {
            return null;
        }

        List<SelectedGraphNode> ordered = new ArrayList<>(orderedOutputs.size());
        for (AEKey output : orderedOutputs) {
            SelectedGraphNode node = hostNodes.get(output);
            if (node == null) {
                return null;
            }
            ordered.add(node);
        }
        return List.copyOf(ordered);
    }

    private static @Nullable Map<IPatternDetails, Long> orderPatternTimesByDependencies(
            Map<IPatternDetails, Long> patternTimes,
            @Nullable ICraftingPlan nativePlan
    ) {
        if (patternTimes == null || patternTimes.size() <= 1) {
            return patternTimes;
        }

        Map<IPatternDetails, Integer> indegree = new LinkedHashMap<>();
        Map<IPatternDetails, LinkedHashSet<IPatternDetails>> adjacency = new LinkedHashMap<>();
        Map<IPatternDetails, Map<AEKey, Long>> inputsByPattern = new LinkedHashMap<>();
        Map<AEKey, List<IPatternDetails>> producersByOutput = new LinkedHashMap<>();

        for (Map.Entry<IPatternDetails, Long> entry : patternTimes.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            if (pattern == null) {
                return null;
            }

            Map<AEKey, Long> inputs = describeAggregationInputs(nativePlan, pattern);
            if (inputs == null) {
                LOGGER.warn("Rewritten crafting pattern {} has non-deterministic inputs", pattern.getDefinition());
                return null;
            }

            indegree.putIfAbsent(pattern, 0);
            adjacency.putIfAbsent(pattern, new LinkedHashSet<>());
            inputsByPattern.put(pattern, inputs);
            for (GenericStack output : pattern.getOutputs()) {
                if (output == null || output.what() == null || output.amount() <= 0L) {
                    continue;
                }
                producersByOutput.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(pattern);
            }
        }

        for (Map.Entry<IPatternDetails, Map<AEKey, Long>> entry : inputsByPattern.entrySet()) {
            IPatternDetails consumer = entry.getKey();
            for (AEKey inputKey : entry.getValue().keySet()) {
                for (IPatternDetails producer : producersByOutput.getOrDefault(inputKey, List.of())) {
                    if (producer == consumer) {
                        continue;
                    }
                    if (adjacency.get(producer).add(consumer)) {
                        indegree.merge(consumer, 1, Integer::sum);
                    }
                }
            }
        }

        ArrayDeque<IPatternDetails> ready = new ArrayDeque<>();
        for (Map.Entry<IPatternDetails, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }

        Map<IPatternDetails, Long> ordered = new LinkedHashMap<>();
        while (!ready.isEmpty()) {
            IPatternDetails current = ready.removeFirst();
            ordered.put(current, patternTimes.get(current));
            for (IPatternDetails consumer : adjacency.getOrDefault(current, new LinkedHashSet<>())) {
                int next = indegree.getOrDefault(consumer, 0) - 1;
                indegree.put(consumer, next);
                if (next == 0) {
                    ready.addLast(consumer);
                }
            }
        }

        return ordered.size() == patternTimes.size() ? ordered : null;
    }

    private static Map<IPatternDetails, Long> immutableOrderedPatternTimes(
            Map<IPatternDetails, Long> patternTimes
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(patternTimes));
    }

    private static SplitBoundaryOutputs splitBoundaryOutputs(
            @Nullable GenericStack finalOutput,
            Map<AEKey, Long> boundaryOutputs,
            Map<AEKey, Long> totalPrimaryOutputs
    ) {
        List<GenericStack> outputs = new ArrayList<>();
        List<GenericStack> remainders = new ArrayList<>();
        Set<AEKey> emittedAsOutput = new HashSet<>();

        if (finalOutput != null && finalOutput.what() != null) {
            Long amount = boundaryOutputs.get(finalOutput.what());
            if (amount != null && amount > 0L) {
                outputs.add(new GenericStack(finalOutput.what(), amount));
                emittedAsOutput.add(finalOutput.what());
            }
        }

        for (AEKey key : totalPrimaryOutputs.keySet()) {
            Long amount = boundaryOutputs.get(key);
            if (amount != null && amount > 0L && emittedAsOutput.add(key)) {
                outputs.add(new GenericStack(key, amount));
            }
        }

        for (Map.Entry<AEKey, Long> entry : boundaryOutputs.entrySet()) {
            if (entry.getKey() != null
                    && entry.getValue() > 0L
                    && !emittedAsOutput.contains(entry.getKey())) {
                remainders.add(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }

        return new SplitBoundaryOutputs(List.copyOf(outputs), List.copyOf(remainders));
    }

    private static @Nullable AECraftingPattern chooseBasePattern(
            @Nullable GenericStack finalOutput,
            Map<AEKey, SelectedGraphNode> hostNodes,
            List<SelectedGraphNode> executionOrder
    ) {
        if (finalOutput != null && finalOutput.what() != null) {
            SelectedGraphNode finalNode = hostNodes.get(finalOutput.what());
            AECraftingPattern craftingPattern = finalNode == null ? null : unwrapBaseCraftingPattern(finalNode.pattern());
            if (craftingPattern != null) {
                return craftingPattern;
            }
        }

        List<SelectedGraphNode> reversedOrder = new ArrayList<>(executionOrder);
        Collections.reverse(reversedOrder);
        for (SelectedGraphNode node : reversedOrder) {
            AECraftingPattern craftingPattern = unwrapBaseCraftingPattern(node.pattern());
            if (craftingPattern != null) {
                return craftingPattern;
            }
        }
        return null;
    }

    private static @Nullable AbstractHighCapacityCraftingHostBlockEntity exclusiveFormalMachineProvider(
            CraftingService craftingService,
            IPatternDetails pattern
    ) {
        IPatternDetails providerPattern = unwrapDelegatingPattern(pattern);
        if (!(providerPattern instanceof AECraftingPattern)) {
            return null;
        }

        AbstractHighCapacityCraftingHostBlockEntity matchedHost = null;
        boolean sawFormalProvider = false;
        for (ICraftingProvider provider : craftingService.getProviders(providerPattern)) {
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

    private static void recordPlanningHelperUsage(
            @Nullable AbstractHighCapacityCraftingHostBlockEntity host,
            boolean nullResult
    ) {
        if (host != null) {
            host.recordPlanningHelperUsageForTest(nullResult);
        }
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

    private static @Nullable Map<AEKey, Long> describePatternInputs(IPatternDetails pattern) {
        if (pattern instanceof IFormalMachineAggregatedPattern aggregatedPattern) {
            return describeAggregatedPatternInputs(aggregatedPattern);
        }
        AECraftingPattern craftingPattern = unwrapBaseCraftingPattern(pattern);
        if (craftingPattern != null) {
            return describeCraftingPatternInputs(craftingPattern);
        }
        Map<AEKey, Long> mergedInputs = new LinkedHashMap<>();
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack resolvedInput = resolveDeterministicInput(input);
            if (resolvedInput == null || resolvedInput.what() == null || resolvedInput.amount() <= 0L) {
                return null;
            }
            long multiplier = input.getMultiplier();
            if (multiplier <= 0L) {
                return null;
            }
            long scaledAmount = multiply(resolvedInput.amount(), multiplier);
            if (scaledAmount <= 0L) {
                return null;
            }
            mergeStack(mergedInputs, resolvedInput.what(), scaledAmount);
        }
        return Map.copyOf(mergedInputs);
    }

    private static @Nullable Map<AEKey, Long> describeAggregatedPatternInputs(
            IFormalMachineAggregatedPattern pattern
    ) {
        Map<AEKey, Long> mergedInputs = new LinkedHashMap<>();
        for (GenericStack input : pattern.aggregatedInputs()) {
            if (input == null) {
                continue;
            }
            if (input.what() == null || input.amount() <= 0L) {
                return null;
            }
            mergeStack(mergedInputs, input.what(), input.amount());
        }
        return Map.copyOf(mergedInputs);
    }

    static @Nullable Map<AEKey, Long> describeAggregatedBoundaryInputs(
            @Nullable List<FormalMachineAggregationStep> steps
    ) {
        if (steps == null || steps.isEmpty()) {
            return Map.of();
        }

        Map<AEKey, Long> requiredExternalInputs = new LinkedHashMap<>();
        Map<AEKey, Long> availableInternalInputs = new LinkedHashMap<>();
        for (FormalMachineAggregationStep step : steps) {
            if (step == null || step.executionCount() <= 0L) {
                return null;
            }

            Map<AEKey, Long> perRunInputs = downscaleStacks(step.stepInputs(), step.executionCount());
            Map<AEKey, Long> perRunRemainders = downscaleStacks(step.stepRemainders(), step.executionCount());
            if (perRunInputs == null || perRunRemainders == null) {
                return null;
            }

            Map<AEKey, Long> perRunOutputs = new LinkedHashMap<>(perRunRemainders);
            if (step.stepPrimaryOutput() != null) {
                if (step.stepPrimaryOutput().what() == null) {
                    return null;
                }
                long perRunPrimaryAmount = downscaleAmount(step.stepPrimaryOutput().amount(), step.executionCount());
                if (perRunPrimaryAmount <= 0L) {
                    return null;
                }
                mergeStack(perRunOutputs, step.stepPrimaryOutput().what(), perRunPrimaryAmount);
            }

            Set<AEKey> affectedKeys = new LinkedHashSet<>();
            affectedKeys.addAll(perRunInputs.keySet());
            affectedKeys.addAll(perRunOutputs.keySet());
            for (AEKey key : affectedKeys) {
                if (key == null) {
                    continue;
                }

                long startingAvailable = Math.max(0L, availableInternalInputs.getOrDefault(key, 0L));
                long consumePerRun = Math.max(0L, perRunInputs.getOrDefault(key, 0L));
                long producePerRun = Math.max(0L, perRunOutputs.getOrDefault(key, 0L));
                long requiredStartup = minimalExecutionStartupAmount(
                        consumePerRun,
                        producePerRun,
                        step.executionCount()
                );
                if (startingAvailable < requiredStartup) {
                    mergeStack(requiredExternalInputs, key, requiredStartup - startingAvailable);
                    startingAvailable = requiredStartup;
                }

                long endingAvailable = advanceExecutionBalance(
                        startingAvailable,
                        consumePerRun,
                        producePerRun,
                        step.executionCount()
                );
                if (endingAvailable > 0L) {
                    availableInternalInputs.put(key, endingAvailable);
                } else {
                    availableInternalInputs.remove(key);
                }
            }
        }
        return Map.copyOf(requiredExternalInputs);
    }

    static @Nullable Map<AEKey, Long> describeAggregationInputs(
            @Nullable ICraftingPlan nativePlan,
            @Nullable IPatternDetails pattern
    ) {
        Map<AEKey, Long> selectedInputs = describeSelectedPatternInputs(nativePlan, pattern);
        if (selectedInputs != null) {
            return selectedInputs;
        }
        return describePatternInputs(pattern);
    }

    private static @Nullable Map<AEKey, Long> describeSelectedPatternInputs(
            @Nullable ICraftingPlan nativePlan,
            @Nullable IPatternDetails pattern
    ) {
        if (!(nativePlan instanceof DyeablePatternSelectedInputsPlan selectedInputsPlan)
                || pattern == null
                || pattern.getDefinition() == null
                || !(pattern.getDefinition() instanceof AEItemKey definition)) {
            return null;
        }
        Map<AEItemKey, Map<AEKey, Long>> selectedInputs =
                selectedInputsPlan.chexsonsaeutils$dyeableSelectedPatternInputs();
        if (selectedInputs == null || selectedInputs.isEmpty()) {
            return null;
        }
        Map<AEKey, Long> selected = selectedInputs.get(definition);
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        return Map.copyOf(new LinkedHashMap<>(selected));
    }

    private static @Nullable AECraftingPattern unwrapBaseCraftingPattern(@Nullable IPatternDetails pattern) {
        IPatternDetails unwrapped = unwrapDelegatingPattern(pattern);
        return unwrapped instanceof AECraftingPattern craftingPattern ? craftingPattern : null;
    }

    private static @Nullable IPatternDetails unwrapDelegatingPattern(@Nullable IPatternDetails pattern) {
        if (pattern instanceof IFormalMachineDelegatingPattern delegatingPattern) {
            return delegatingPattern.basePattern();
        }
        return pattern;
    }

    private static @Nullable Map<AEKey, Long> describeCraftingPatternInputs(AECraftingPattern pattern) {
        if (pattern == null) {
            return null;
        }
        Map<AEKey, Long> mergedInputs = new LinkedHashMap<>();
        for (GenericStack input : pattern.getSparseInputs()) {
            if (input == null) {
                continue;
            }
            if (input.what() == null || input.amount() <= 0L) {
                return null;
            }
            mergeStack(mergedInputs, input.what(), input.amount());
        }
        return Map.copyOf(mergedInputs);
    }

    private static @Nullable GenericStack resolveDeterministicInput(IPatternDetails.IInput input) {
        if (input == null) {
            return null;
        }
        GenericStack matched = null;
        for (GenericStack possibleInput : input.getPossibleInputs()) {
            if (possibleInput == null || possibleInput.what() == null || possibleInput.amount() <= 0L) {
                continue;
            }
            if (matched == null) {
                matched = possibleInput;
                continue;
            }
            if (!matched.what().equals(possibleInput.what()) || matched.amount() != possibleInput.amount()) {
                return null;
            }
        }
        return matched == null ? null : new GenericStack(matched.what(), matched.amount());
    }

    private static int comparePatternDefinitions(@Nullable IPatternDetails left, @Nullable IPatternDetails right) {
        ItemStack leftStack = left == null || left.getDefinition() == null ? ItemStack.EMPTY : left.getDefinition().toStack();
        ItemStack rightStack = right == null || right.getDefinition() == null ? ItemStack.EMPTY : right.getDefinition().toStack();
        return compareItemStacks(leftStack, rightStack);
    }

    private static int compareItemStacks(@Nullable ItemStack left, @Nullable ItemStack right) {
        if (left == right) {
            return 0;
        }
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty() ? 0 : -1;
        }
        if (right == null || right.isEmpty()) {
            return 1;
        }
        int idCompare = left.getItem().toString().compareTo(right.getItem().toString());
        if (idCompare != 0) {
            return idCompare;
        }
        int countCompare = Integer.compare(left.getCount(), right.getCount());
        if (countCompare != 0) {
            return countCompare;
        }
        return left.getComponentsPatch().toString().compareTo(right.getComponentsPatch().toString());
    }

    private static List<GenericStack> toScaledStacks(Map<? extends AEKey, Long> amounts, long multiplier) {
        if (amounts == null || amounts.isEmpty() || multiplier <= 0L) {
            return List.of();
        }
        List<GenericStack> stacks = new ArrayList<>(amounts.size());
        for (Map.Entry<? extends AEKey, Long> entry : amounts.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            long scaledAmount = multiply(Math.max(0L, entry.getValue() == null ? 0L : entry.getValue()), multiplier);
            if (scaledAmount > 0L) {
                stacks.add(new GenericStack(entry.getKey(), scaledAmount));
            }
        }
        return List.copyOf(stacks);
    }

    private static List<GenericStack> toGenericStacks(Map<AEKey, Long> amounts) {
        if (amounts == null || amounts.isEmpty()) {
            return List.of();
        }
        List<GenericStack> stacks = new ArrayList<>(amounts.size());
        for (Map.Entry<AEKey, Long> entry : amounts.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                stacks.add(new GenericStack(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(stacks);
    }

    private static void mergeStacks(Map<AEKey, Long> target, List<GenericStack> stacks) {
        if (target == null || stacks == null) {
            return;
        }
        for (GenericStack stack : stacks) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                mergeStack(target, stack.what(), stack.amount());
            }
        }
    }

    private static void mergeStack(Map<AEKey, Long> target, AEKey key, long amount) {
        if (target == null || key == null || amount <= 0L) {
            return;
        }
        target.merge(key, amount, FormalMachinePlanningAggregationService::saturatingAdd);
    }

    private static void mergeStackMaps(Map<AEKey, Long> target, Map<AEKey, Long> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<AEKey, Long> entry : source.entrySet()) {
            mergeStack(target, entry.getKey(), Math.max(0L, entry.getValue() == null ? 0L : entry.getValue()));
        }
    }

    private static Map<AEKey, Long> subtractPositive(Map<AEKey, Long> left, Map<AEKey, Long> right) {
        Map<AEKey, Long> difference = new LinkedHashMap<>();
        if (left == null || left.isEmpty()) {
            return difference;
        }
        for (Map.Entry<AEKey, Long> entry : left.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            long remaining = Math.max(0L, entry.getValue() - Math.max(0L, right.getOrDefault(entry.getKey(), 0L)));
            if (remaining > 0L) {
                difference.put(entry.getKey(), remaining);
            }
        }
        return difference;
    }

    private static void subtractInPlace(Map<AEKey, Long> target, Map<AEKey, Long> consumed) {
        if (target == null || target.isEmpty() || consumed == null || consumed.isEmpty()) {
            return;
        }
        for (Map.Entry<AEKey, Long> entry : consumed.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            long remaining = Math.max(0L, target.getOrDefault(entry.getKey(), 0L) - entry.getValue());
            if (remaining > 0L) {
                target.put(entry.getKey(), remaining);
            } else {
                target.remove(entry.getKey());
            }
        }
    }

    private static Map<AEKey, Long> extractMissingAmounts(@Nullable KeyCounter missingItems) {
        return extractCounterAmounts(missingItems);
    }

    static Map<AEKey, Long> extractExternalMissingInputs(
            @Nullable KeyCounter missingItems,
            Map<AEKey, Long> internallyCraftedOutputs,
            @Nullable KeyCounter recursiveInitialItems
    ) {
        Map<AEKey, Long> missing = extractCounterAmounts(missingItems);
        if (missing.isEmpty() || internallyCraftedOutputs == null || internallyCraftedOutputs.isEmpty()) {
            return missing;
        }
        Set<AEKey> startupKeys = extractCounterAmounts(recursiveInitialItems).keySet();
        for (AEKey craftedOutput : internallyCraftedOutputs.keySet()) {
            if (craftedOutput != null && !startupKeys.contains(craftedOutput)) {
                missing.remove(craftedOutput);
            }
        }
        return missing;
    }

    private static KeyCounter filterCandidateRecursiveInitialItems(
            @Nullable ICraftingPlan nativePlan,
            Map<AEKey, Long> candidateProducedKeys
    ) {
        KeyCounter filtered = new KeyCounter();
        if (candidateProducedKeys == null || candidateProducedKeys.isEmpty()) {
            return filtered;
        }
        KeyCounter recursiveInitialItems = dyeableRecursiveInitialItems(nativePlan);
        if (recursiveInitialItems.isEmpty()) {
            return filtered;
        }
        for (var entry : recursiveInitialItems) {
            if (entry.getKey() == null
                    || entry.getLongValue() <= 0L
                    || !candidateProducedKeys.containsKey(entry.getKey())) {
                continue;
            }
            filtered.set(entry.getKey(), entry.getLongValue());
        }
        return filtered;
    }

    static void restoreRecursiveInitialBoundaryOutputs(
            Map<AEKey, Long> boundaryOutputs,
            @Nullable KeyCounter recursiveInitialItems
    ) {
        if (boundaryOutputs == null || recursiveInitialItems == null || recursiveInitialItems.isEmpty()) {
            return;
        }
        for (var entry : recursiveInitialItems) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                mergeStack(boundaryOutputs, entry.getKey(), entry.getLongValue());
            }
        }
    }

    private static KeyCounter snapshotLiveVisibleStacks(CraftingService craftingService) {
        KeyCounter liveVisible = new KeyCounter();
        if (craftingService == null
                || ((CraftingServiceAccessor) craftingService).chexsonsaeutils$getGrid() == null
                || ((CraftingServiceAccessor) craftingService).chexsonsaeutils$getGrid().getStorageService() == null) {
            return liveVisible;
        }
        var inventory = ((CraftingServiceAccessor) craftingService)
                .chexsonsaeutils$getGrid()
                .getStorageService()
                .getInventory();
        KeyCounter visibleStacks = inventory.getAvailableStacks();
        liveVisible.addAll(visibleStacks);
        for (var entry : visibleStacks) {
            if (entry.getKey() == null || entry.getLongValue() < Integer.MAX_VALUE) {
                continue;
            }
            long liveAmount = inventory.extract(
                    entry.getKey(),
                    Long.MAX_VALUE,
                    Actionable.SIMULATE,
                    IActionSource.empty()
            );
            if (liveAmount > entry.getLongValue()) {
                liveVisible.set(entry.getKey(), liveAmount);
            }
        }
        return liveVisible;
    }

    private static Map<AEKey, Long> extractCounterAmounts(@Nullable KeyCounter counter) {
        Map<AEKey, Long> amounts = new LinkedHashMap<>();
        if (counter == null) {
            return amounts;
        }
        for (var entry : counter) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                amounts.put(entry.getKey(), entry.getLongValue());
            }
        }
        return amounts;
    }

    private static long estimateAggregatedWork(List<FormalMachineAggregationStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0L;
        }
        long estimatedWork = 0L;
        for (FormalMachineAggregationStep step : steps) {
            if (step == null) {
                continue;
            }
            estimatedWork = saturatingAdd(estimatedWork, Math.max(1L, step.executionCount()));
        }
        return estimatedWork;
    }

    private static long computeRewrittenBytes(List<HostAggregationCandidate> candidates) {
        long rewritten = 0L;
        if (candidates == null || candidates.isEmpty()) {
            return 1L;
        }

        for (HostAggregationCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            rewritten = saturatingAdd(rewritten, estimateAggregatedReplacementBytes(candidate));
        }

        return Math.max(1L, rewritten);
    }

    private static long estimateAggregatedReplacementBytes(HostAggregationCandidate candidate) {
        if (candidate == null) {
            return 1L;
        }

        long replacementBytes = 0L;
        replacementBytes = saturatingAdd(replacementBytes, 1L);
        replacementBytes = saturatingAdd(replacementBytes, multiply(estimateAggregatedReplacementNodeCount(candidate), 8L));
        replacementBytes = saturatingAdd(replacementBytes, estimateAggregatedDescriptorBytes(candidate));
        return Math.max(1L, replacementBytes);
    }

    private static long estimateAggregatedReplacementNodeCount(HostAggregationCandidate candidate) {
        long outputNodes = countPayloadEntries(candidate == null ? null : candidate.aggregatedOutputs());
        long inputNodes = countPayloadEntries(candidate == null ? null : candidate.boundaryInputs());
        return Math.max(1L, saturatingAdd(outputNodes, inputNodes));
    }

    private static long estimateAggregatedDescriptorBytes(HostAggregationCandidate candidate) {
        if (candidate == null) {
            return 0L;
        }

        long descriptorBytes = 0L;
        descriptorBytes = saturatingAdd(descriptorBytes, countPayloadEntries(candidate.boundaryInputs()));
        descriptorBytes = saturatingAdd(descriptorBytes, countPayloadEntries(candidate.aggregatedOutputs()));
        descriptorBytes = saturatingAdd(descriptorBytes, countPayloadEntries(candidate.aggregatedRemainders()));

        if (candidate.steps() == null) {
            return descriptorBytes;
        }

        for (FormalMachineAggregationStep step : candidate.steps()) {
            if (step == null) {
                continue;
            }
            descriptorBytes = saturatingAdd(descriptorBytes, 1L);
            descriptorBytes = saturatingAdd(descriptorBytes, countPayloadEntries(step.stepInputs()));
            descriptorBytes = saturatingAdd(descriptorBytes, step.stepPrimaryOutput() == null ? 0L : 1L);
            descriptorBytes = saturatingAdd(descriptorBytes, countPayloadEntries(step.stepRemainders()));
        }
        return descriptorBytes;
    }


    private static long countPayloadEntries(@Nullable List<GenericStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return 0L;
        }

        long entries = 0L;
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            entries++;
        }
        return entries;
    }

    private static KeyCounter copyCounter(@Nullable KeyCounter original) {
        KeyCounter copy = new KeyCounter();
        if (original != null) {
            copy.addAll(original);
        }
        return copy;
    }

    static KeyCounter computeRewrittenUsedItems(
            Map<IPatternDetails, Long> patternTimes,
            KeyCounter rewrittenMissingItems,
            @Nullable ICraftingPlan nativePlan
    ) {
        Map<AEKey, Long> totalInputs = new LinkedHashMap<>();
        Map<AEKey, Long> totalOutputs = new LinkedHashMap<>();
        if (patternTimes != null) {
            for (Map.Entry<IPatternDetails, Long> entry : patternTimes.entrySet()) {
                IPatternDetails pattern = entry.getKey();
                long count = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
                if (pattern == null || count <= 0L) {
                    continue;
                }
                Map<AEKey, Long> inputs = describePatternInputs(pattern);
                if (inputs == null) {
                    continue;
                }
                mergeScaledMap(totalInputs, inputs, count);
                for (GenericStack output : pattern.getOutputs()) {
                    if (output != null && output.what() != null && output.amount() > 0L) {
                        mergeStack(totalOutputs, output.what(), multiply(output.amount(), count));
                    }
                }
            }
        }
        Map<AEKey, Long> requiredExternalInputs = subtractPositive(totalInputs, totalOutputs);
        KeyCounter usedItems;
        if (rewrittenMissingItems == null || rewrittenMissingItems.isEmpty()) {
            usedItems = toCounter(requiredExternalInputs);
            mergeRecursiveInitialUsedItems(usedItems, dyeableRecursiveInitialItems(nativePlan));
            return usedItems;
        }

        Map<AEKey, Long> availableInputs = new LinkedHashMap<>();
        for (Map.Entry<AEKey, Long> entry : requiredExternalInputs.entrySet()) {
            AEKey key = entry.getKey();
            if (key == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            long missingAmount = Math.max(0L, rewrittenMissingItems.get(key));
            long usedAmount = Math.max(0L, entry.getValue() - missingAmount);
            if (usedAmount > 0L) {
                availableInputs.put(key, usedAmount);
            }
        }
        usedItems = toCounter(availableInputs);
        mergeRecursiveInitialUsedItems(usedItems, dyeableRecursiveInitialItems(nativePlan));
        return usedItems;
    }

    private static boolean usesDyeableRecursivePlanning(@Nullable ICraftingPlan plan) {
        return plan instanceof DyeablePatternRecursivePlan recursivePlan
                && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning();
    }

    private static KeyCounter dyeableRecursiveInitialItems(@Nullable ICraftingPlan plan) {
        if (plan instanceof DyeablePatternRecursivePlan recursivePlan
                && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
            return recursivePlan.chexsonsaeutils$dyeableRecursiveInitialItems();
        }
        return new KeyCounter();
    }

    private static KeyCounter dyeableRecursiveInternalItems(@Nullable ICraftingPlan plan) {
        if (plan instanceof DyeablePatternRecursivePlan recursivePlan
                && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
            return recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems();
        }
        return new KeyCounter();
    }

    private static long dyeableRecursiveFinalOutputAmount(@Nullable ICraftingPlan plan) {
        if (plan instanceof DyeablePatternRecursivePlan recursivePlan
                && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
            return recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount();
        }
        return -1L;
    }

    private static void addUsedItems(KeyCounter target, @Nullable KeyCounter source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (var entry : source) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                target.set(entry.getKey(), saturatingAdd(target.get(entry.getKey()), entry.getLongValue()));
            }
        }
    }

    private static void mergeRecursiveInitialUsedItems(
            KeyCounter target,
            @Nullable KeyCounter recursiveInitialItems
    ) {
        if (target == null || recursiveInitialItems == null || recursiveInitialItems.isEmpty()) {
            return;
        }
        for (var entry : recursiveInitialItems) {
            if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                continue;
            }
            long existing = Math.max(0L, target.get(entry.getKey()));
            if (entry.getLongValue() > existing) {
                target.set(entry.getKey(), entry.getLongValue());
            }
        }
    }

    private static void mergeScaledMap(Map<AEKey, Long> target, Map<AEKey, Long> source, long multiplier) {
        if (target == null || source == null || source.isEmpty() || multiplier <= 0L) {
            return;
        }
        for (Map.Entry<AEKey, Long> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                mergeStack(target, entry.getKey(), multiply(entry.getValue(), multiplier));
            }
        }
    }

    private static KeyCounter toCounter(Map<AEKey, Long> amounts) {
        KeyCounter counter = new KeyCounter();
        if (amounts == null || amounts.isEmpty()) {
            return counter;
        }
        for (Map.Entry<AEKey, Long> entry : amounts.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                counter.add(entry.getKey(), entry.getValue());
            }
        }
        return counter;
    }

    private static KeyCounter mergeMissingItems(
            @Nullable KeyCounter nativeMissingItems,
            List<HostAggregationCandidate> candidates,
            @Nullable KeyCounter liveVisibleStacks
    ) {
        KeyCounter merged = copyCounter(nativeMissingItems);
        if (candidates == null || candidates.isEmpty()) {
            return merged;
        }

        for (HostAggregationCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            pruneInternalCraftableMissing(merged, candidate.originalPatterns());
            mergePositiveStacksByMax(merged, candidate.externalMissingInputs());
        }
        pruneLiveVisibleMissing(merged, liveVisibleStacks);
        return merged;
    }

    private static void pruneLiveVisibleMissing(KeyCounter missingItems, @Nullable KeyCounter liveVisibleStacks) {
        if (missingItems == null || missingItems.isEmpty() || liveVisibleStacks == null || liveVisibleStacks.isEmpty()) {
            return;
        }
        for (var entry : liveVisibleStacks) {
            if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                continue;
            }
            if (missingItems.get(entry.getKey()) <= entry.getLongValue()) {
                missingItems.remove(entry.getKey());
            }
        }
        missingItems.removeZeros();
    }

    private static void pruneInternalCraftableMissing(
            KeyCounter missingItems,
            @Nullable List<IPatternDetails> originalPatterns
    ) {
        if (missingItems == null || missingItems.isEmpty() || originalPatterns == null || originalPatterns.isEmpty()) {
            return;
        }
        for (IPatternDetails pattern : originalPatterns) {
            if (pattern == null) {
                continue;
            }
            for (GenericStack output : pattern.getOutputs()) {
                if (output == null || output.what() == null) {
                    continue;
                }
                missingItems.remove(output.what());
            }
        }
        missingItems.removeZeros();
    }

    private static void mergePositiveStacksByMax(KeyCounter counter, @Nullable List<GenericStack> stacks) {
        if (counter == null || stacks == null || stacks.isEmpty()) {
            return;
        }
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            counter.set(stack.what(), Math.max(counter.get(stack.what()), stack.amount()));
        }
    }

    private static long multiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long downscaleAmount(long scaledAmount, long divisor) {
        if (scaledAmount <= 0L || divisor <= 0L || scaledAmount % divisor != 0L) {
            return 0L;
        }
        return scaledAmount / divisor;
    }

    private static @Nullable Map<AEKey, Long> downscaleStacks(
            @Nullable List<GenericStack> scaledStacks,
            long divisor
    ) {
        if (divisor <= 0L) {
            return null;
        }
        if (scaledStacks == null || scaledStacks.isEmpty()) {
            return Map.of();
        }

        Map<AEKey, Long> downscaled = new LinkedHashMap<>();
        for (GenericStack stack : scaledStacks) {
            if (stack == null) {
                continue;
            }
            if (stack.what() == null || stack.amount() <= 0L) {
                return null;
            }
            long downscaledAmount = downscaleAmount(stack.amount(), divisor);
            if (downscaledAmount <= 0L) {
                return null;
            }
            mergeStack(downscaled, stack.what(), downscaledAmount);
        }
        return Map.copyOf(downscaled);
    }

    private static long minimalExecutionStartupAmount(
            long consumePerRun,
            long producePerRun,
            long executionCount
    ) {
        if (consumePerRun <= 0L || executionCount <= 0L) {
            return 0L;
        }
        if (executionCount == 1L || producePerRun >= consumePerRun) {
            return consumePerRun;
        }
        return saturatingAdd(
                consumePerRun,
                multiply(executionCount - 1L, consumePerRun - producePerRun)
        );
    }

    private static long advanceExecutionBalance(
            long startingAmount,
            long consumePerRun,
            long producePerRun,
            long executionCount
    ) {
        if (executionCount <= 0L) {
            return Math.max(0L, startingAmount);
        }
        if (producePerRun >= consumePerRun) {
            return saturatingAdd(startingAmount, multiply(executionCount, producePerRun - consumePerRun));
        }
        return Math.max(
                0L,
                startingAmount - multiply(executionCount, consumePerRun - producePerRun)
        );
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

    private static int saturatingIntMultiply(int left, int right) {
        if (left <= 0 || right <= 0) {
            return 1;
        }
        if (left > Integer.MAX_VALUE / right) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, left * right);
    }

    private record HostAggregationCandidate(
            AbstractHighCapacityCraftingHostBlockEntity host,
            AECraftingPattern basePattern,
            List<GenericStack> boundaryInputs,
            List<GenericStack> externalMissingInputs,
            List<GenericStack> aggregatedOutputs,
            List<GenericStack> aggregatedRemainders,
            List<FormalMachineAggregationStep> steps,
            int totalTicks,
            List<IPatternDetails> originalPatterns,
            long estimatedWork
    ) {
    }

    private record SelectedPlanGraph(Map<AEKey, SelectedGraphNode> nodes) {
    }

    private record SelectedGraphNode(
            PatternDefinitionKey definitionKey,
            IPatternDetails pattern,
            long craftCount,
            AEKey outputKey,
            long outputAmount,
            Map<AEKey, Long> inputs,
            @Nullable AbstractHighCapacityCraftingHostBlockEntity host
    ) {
    }

    private record EquivalentSelectedNodeKey(
            @Nullable AbstractHighCapacityCraftingHostBlockEntity host,
            AEKey outputKey,
            long outputAmount,
            Map<AEKey, Long> inputs
    ) {
        private static @Nullable EquivalentSelectedNodeKey of(@Nullable SelectedGraphNode node) {
            if (node == null || node.outputKey() == null || node.inputs() == null) {
                return null;
            }
            return new EquivalentSelectedNodeKey(
                    node.host(),
                    node.outputKey(),
                    node.outputAmount(),
                    node.inputs()
            );
        }
    }

    private record SplitBoundaryOutputs(List<GenericStack> outputs, List<GenericStack> remainders) {
    }

    private record PatternDefinitionKey(AEItemKey definition) {
        private static @Nullable PatternDefinitionKey of(@Nullable IPatternDetails pattern) {
            if (pattern == null || pattern.getDefinition() == null) {
                return null;
            }
            return new PatternDefinitionKey(pattern.getDefinition());
        }
    }

    private static final class AggregatingPlanningFuture implements Future<ICraftingPlan> {

        private final CraftingService craftingService;
        private final Level level;
        private final AEKey requestedOutput;
        private final long requestedAmount;
        private final Future<ICraftingPlan> delegate;

        private AggregatingPlanningFuture(
                CraftingService craftingService,
                Level level,
                AEKey requestedOutput,
                long requestedAmount,
                Future<ICraftingPlan> delegate
        ) {
            this.craftingService = craftingService;
            this.level = level;
            this.requestedOutput = requestedOutput;
            this.requestedAmount = requestedAmount;
            this.delegate = delegate;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public ICraftingPlan get() throws InterruptedException, ExecutionException {
            try {
                return awaitTransformedPlan(null, null);
            } catch (TimeoutException exception) {
                throw new IllegalStateException("Unbounded native crafting plan wait timed out unexpectedly", exception);
            }
        }

        @Override
        public ICraftingPlan get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return awaitTransformedPlan(timeout, unit == null ? TimeUnit.MILLISECONDS : unit);
        }

        private ICraftingPlan awaitTransformedPlan(@Nullable Long timeout, @Nullable TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            ICraftingPlan nativePlan = timeout == null
                    ? delegate.get()
                    : delegate.get(timeout, unit);

            try {
                return rewriteNativePlan(
                        craftingService,
                        level,
                        requestedOutput,
                        requestedAmount,
                        nativePlan
                );
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Failed to rewrite native AE2 crafting plan for output {} amount {}",
                        requestedOutput,
                        requestedAmount,
                        exception
                );
                return nativePlan;
            }
        }
    }
}
