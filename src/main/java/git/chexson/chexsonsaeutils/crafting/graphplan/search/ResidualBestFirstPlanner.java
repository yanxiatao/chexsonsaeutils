package git.chexson.chexsonsaeutils.crafting.graphplan.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPatternFlows;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.PlanningOverflowException;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.RouteOrdering;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;

/**
 * 独立的稀疏残量最佳优先规划器。
 *
 * <p>它不构造规划树，也不调用其他求解器。普通 producer 以残量批次传播，多 producer
 * 用有界最佳优先队列搜索最大可执行批次；压缩递归分量则由本规划器直接解释
 * {@link RingMacro}。候选试跑只写 {@link GraphPlanningState} journal，失败后精确回滚。
 */
public final class ResidualBestFirstPlanner {

    public static final SearchLimits DEFAULT_LIMITS = new SearchLimits(131_072, 4_096);

    private ResidualBestFirstPlanner() {
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount) {
        return solve(graph, strategy, requestedAmount, DEFAULT_LIMITS);
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount,
            SearchLimits limits) {
        if (graph.hasDeclined()) {
            return SolveResult.declined(graph.declineReason());
        }
        if (requestedAmount <= 0) {
            return SolveResult.declined("residual-search: non-positive request amount");
        }
        if (!WideMath.wideEnabled() && requestedAmount > LinearPlanner.MAX_AMOUNT) {
            return SolveResult.declined("residual-search: request amount above planner cap");
        }
        if (graph.cycleKind() == CraftGraph.CycleKind.UNSUPPORTED_CYCLE) {
            return SolveResult.declined("residual-search: unsupported recursive cycle");
        }
        String unsupported = unsupportedReason(graph);
        if (unsupported != null) {
            return SolveResult.declined("residual-search: " + unsupported);
        }

        try {
            var search = new Search(graph, limits);
            GraphPlanningState full = search.attempt(requestedAmount, false);
            if (full != null) {
                return checkedSuccess(graph, full, requestedAmount, false);
            }

            if (strategy == CalculationStrategy.CRAFT_LESS) {
                long successfulAmount = 0L;
                GraphPlanningState successfulState = null;
                for (long increment = Long.highestOneBit(requestedAmount); increment > 0; increment >>>= 1) {
                    long candidate = Math.addExact(successfulAmount, increment);
                    if (candidate >= requestedAmount) {
                        continue;
                    }
                    GraphPlanningState state = search.attempt(candidate, false);
                    if (state != null) {
                        successfulAmount = candidate;
                        successfulState = state;
                    }
                }
                if (successfulState != null) {
                    return checkedSuccess(graph, successfulState, successfulAmount, false);
                }
            }

            GraphPlanningState simulated = search.attempt(requestedAmount, true);
            if (simulated == null) {
                return SolveResult.declined("residual-search: simulation attempt failed");
            }
            return checkedSuccess(graph, simulated, requestedAmount, true);
        } catch (SearchLimitExceeded exceeded) {
            return SolveResult.declined("residual-search: " + exceeded.getMessage());
        } catch (PlanningOverflowException | ArithmeticException overflow) {
            return SolveResult.declined("residual-search: numeric overflow");
        }
    }

    private static SolveResult checkedSuccess(CraftGraph graph, GraphPlanningState state,
            long finalAmount, boolean simulation) {
        if (state.hasNegativeExitValues()) {
            return SolveResult.declined("residual-search: negative exit ledger");
        }
        String violation = MaterialClosureGate.validate(state, graph, finalAmount);
        if (violation != null) {
            return SolveResult.declined("residual-search: closure gate — " + violation);
        }
        return SolveResult.success(state, finalAmount, simulation);
    }

    @Nullable
    private static String unsupportedReason(CraftGraph graph) {
        if (graph.cycleKind() == CraftGraph.CycleKind.NONE && !graph.routeSelection().acyclic()) {
            return "cyclic producer graph";
        }

        Set<AEKey> consumed = new HashSet<>();
        for (var pattern : uniquePatterns(graph)) {
            for (var input : pattern.inputs()) {
                if (input.mode() == GraphInput.Mode.RETURNED
                        || input.mode() == GraphInput.Mode.FINITE_USE) {
                    return "unsupported input mode " + input.mode();
                }
                if (graph.cycleKind() == CraftGraph.CycleKind.NONE && input.selfBlocked()) {
                    return "recursive self-blocked input";
                }
                if (input.amountPerCraft() <= 0 || input.multiplier() <= 0) {
                    return "non-positive input amount";
                }
                consumed.add(input.key());
                if (input.extractCandidates() != null) {
                    for (var candidate : input.extractCandidates()) {
                        if (candidate.amount() <= 0) {
                            return "non-positive fuzzy template amount";
                        }
                        consumed.add(candidate.key());
                    }
                }
            }
            if (pattern.outputPerCraft() <= 0) {
                return "non-positive producer output";
            }
        }

        if (graph.cycleKind() == CraftGraph.CycleKind.NONE) {
            for (var pattern : uniquePatterns(graph)) {
                for (var output : pattern.allOutputs()) {
                    if (!output.what().equals(pattern.outputKey()) && consumed.contains(output.what())) {
                        return "byproduct feedback into consumed key " + output.what();
                    }
                }
            }
        }
        return null;
    }

    private static List<GraphPattern> uniquePatterns(CraftGraph graph) {
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<GraphPattern, Boolean>());
        var result = new ArrayList<GraphPattern>();
        for (var routes : graph.producersByOutput().values()) {
            for (var pattern : routes) {
                if (seen.add(pattern)) {
                    result.add(pattern);
                }
            }
        }
        return result;
    }

    /** 硬预算；耗尽即 DECLINE，不把未搜索完解释成缺料。 */
    public record SearchLimits(int maxRouteProbes, int maxFrontierEntries) {
        public SearchLimits {
            if (maxRouteProbes <= 0 || maxFrontierEntries <= 0) {
                throw new IllegalArgumentException("search limits must be positive");
            }
        }
    }

    private static final class Search {
        private final CraftGraph graph;
        private final SearchLimits limits;
        private final SiteCatalog sites = new SiteCatalog();
        private final Set<RingId> activeRingIds = new HashSet<>();
        private final Map<AEKey, Object> ringSiteIdentities = new HashMap<>();
        private int routeProbes;
        private long queueSequence;
        private GraphPlanningState state;
        private boolean simulation;

        private Search(CraftGraph graph, SearchLimits limits) {
            this.graph = graph;
            this.limits = limits;
        }

        @Nullable
        private GraphPlanningState attempt(long amount, boolean simulation) {
            this.state = new GraphPlanningState(graph.stock(), graph.classVariants());
            this.simulation = simulation;
            this.routeProbes = 0;
            this.queueSequence = 0L;
            this.activeRingIds.clear();
            this.state.ignore(graph.target());
            try {
                request(new Site(graph.target(), 1L, true, false, null, null, RootSite.INSTANCE,
                        RouteOrdering.routesFor(graph, graph.target(), true, false), null, -1), amount);
            } catch (BranchFailure failure) {
                return null;
            }
            this.state.addBytes(Math.multiplyExact(sites.materializedNodes, 8L));
            return this.state;
        }

        private void request(Site site, long units) {
            state.addStackBytes(site.key, site.templateAmount, units);
            long remaining = extract(site, units);
            if (remaining == 0L) {
                return;
            }
            if (graph.emittable().contains(site.key)) {
                recordAllocation(site, site.key, site.templateAmount, remaining);
                state.emitItems(site.key, Math.multiplyExact(site.templateAmount, remaining));
                return;
            }

            RouteOrdering.RouteBundle bundle = site.routes;
            if (!site.ringDependency()) {
                sites.materialize(site.identity(), bundle.routes());
            }
            long residual = Math.multiplyExact(site.templateAmount(), remaining);
            List<RouteOrdering.Route> ordered = !simulation && !site.root() && bundle.routes().size() > 1
                    ? bundle.patternFirst()
                    : bundle.routes();
            boolean multiplePatterns = bundle.patternRouteCount() > 1;
            for (RouteOrdering.Route route : ordered) {
                if (residual <= 0L) {
                    recordAllocation(site, site.key, site.templateAmount, remaining);
                    return;
                }
                long produced;
                if (route instanceof RouteOrdering.Route.Ring ring) {
                    produced = fireRing(site, ring.macro(), residual);
                } else {
                    GraphPattern pattern = ((RouteOrdering.Route.Pattern) route).pattern();
                    produced = multiplePatterns
                            ? fireMultipleRoute(site, pattern, residual,
                                    ((RouteOrdering.Route.Pattern) route).slotIndex())
                            : fireSingle(site, pattern, residual);
                }
                residual -= produced;
            }

            if (residual > 0L) {
                if (!simulation) {
                    throw BranchFailure.INSTANCE;
                }
                state.addMissing(site.key, residual);
            } else {
                recordAllocation(site, site.key, site.templateAmount, remaining);
            }
        }

        private long extract(Site site, long units) {
            long remaining = units;
            if (site.candidates == null) {
                long extracted = state.extractTemplates(site.key, site.templateAmount, remaining);
                recordAllocation(site, site.key, site.templateAmount, extracted);
                return remaining - extracted;
            }
            for (var candidate : site.candidates) {
                for (var variant : state.findFuzzyTemplates(candidate.key())) {
                    if (site.validVariants == null || !site.validVariants.contains(variant)) {
                        continue;
                    }
                    long extracted = state.extractTemplates(variant, candidate.amount(), remaining);
                    recordAllocation(site, variant, candidate.amount(), extracted);
                    remaining -= extracted;
                    if (remaining == 0L) {
                        return 0L;
                    }
                }
            }
            return remaining;
        }

        private void recordAllocation(Site site, AEKey exactKey, long templateAmount, long units) {
            if (units > 0L && site.consumer() != null) {
                state.recordInputAllocation(
                        site.consumer(), site.inputSlot(), exactKey, templateAmount, units);
            }
        }

        private long fireSingle(Site site, GraphPattern pattern, long residual) {
            long times = ceilDiv(residual, pattern.outputPerCraft());
            int mark = state.journal();
            try {
                fire(pattern, times);
                return consumeProduced(site, residual);
            } catch (BranchFailure failure) {
                state.undoTo(mark);
                return 0L;
            }
        }

        private long fireMultipleRoute(Site site, GraphPattern pattern, long residual, int routeIndex) {
            long wanted = ceilDiv(residual, pattern.outputPerCraft());
            long feasible = simulation ? wanted : bestFeasibleBatch(pattern, wanted, residual, routeIndex);
            if (feasible <= 0L) {
                return 0L;
            }
            fire(pattern, feasible);
            return consumeProduced(site, residual);
        }

        private long bestFeasibleBatch(GraphPattern pattern, long wanted, long residual, int routeIndex) {
            var frontier = new PriorityQueue<SearchInterval>(Comparator
                    .comparingLong(SearchInterval::optimisticResidual)
                    .thenComparingInt(SearchInterval::routeIndex)
                    .thenComparingLong(SearchInterval::sequence));
            enqueue(frontier, new SearchInterval(1L, wanted, residual, pattern.outputPerCraft(),
                    routeIndex, queueSequence++));
            long best = 0L;
            while (!frontier.isEmpty()) {
                SearchInterval interval = frontier.remove();
                long probe = interval.midpoint();
                if (++routeProbes > limits.maxRouteProbes()) {
                    throw new SearchLimitExceeded("route probe budget exhausted");
                }
                int mark = state.journal();
                boolean feasible;
                try {
                    fire(pattern, probe);
                    feasible = true;
                } catch (BranchFailure failure) {
                    feasible = false;
                }
                state.undoTo(mark);

                if (feasible) {
                    best = Math.max(best, probe);
                    if (probe < interval.high) {
                        enqueue(frontier, new SearchInterval(probe + 1L, interval.high, residual,
                                pattern.outputPerCraft(), routeIndex, queueSequence++));
                    }
                } else if (interval.low < probe) {
                    enqueue(frontier, new SearchInterval(interval.low, probe - 1L, residual,
                            pattern.outputPerCraft(), routeIndex, queueSequence++));
                }
            }
            return best;
        }

        private void enqueue(PriorityQueue<SearchInterval> frontier, SearchInterval interval) {
            if (frontier.size() >= limits.maxFrontierEntries()) {
                throw new SearchLimitExceeded("frontier budget exhausted");
            }
            frontier.add(interval);
        }

        private void fire(GraphPattern pattern, long times) {
            for (int slot = 0; slot < pattern.inputs().size(); slot++) {
                var input = pattern.inputs().get(slot);
                long requestedUnits = input.mode() == GraphInput.Mode.CONSUMED_RETURNING
                        ? input.multiplier()
                        : Math.multiplyExact(input.multiplier(), times);
                request(new Site(input.key(), input.amountPerCraft(), false, false,
                        input.extractCandidates(), input.validVariants(), input,
                        RouteOrdering.routesFor(graph, input.key(), false, input.selfBlocked()),
                        pattern.source(), slot), requestedUnits);
                if (input.mode() == GraphInput.Mode.CONSUMED_RETURNING) {
                    state.addStackBytes(input.key(), input.amountPerCraft(),
                            Math.multiplyExact(input.multiplier(), times - 1L));
                }
            }
            java.util.Set<AEKey> corrected = new java.util.HashSet<>();
            for (var output : pattern.allOutputs()) {
                PlanAmount amount = WideMath.mul(output.amount(), times);
                PlanAmount consumedPerCraft = GraphPatternFlows.selfConsumedPerCraft(pattern, output.what());
                if (consumedPerCraft.signum() > 0) {
                    if (corrected.add(output.what())) {
                        amount = GraphPatternFlows.outputPerCraftOf(pattern, output.what())
                                .multiply(PlanAmount.of(times))
                                .subtract(consumedPerCraft.multiply(PlanAmount.of(times - 1L)));
                    } else {
                        amount = PlanAmount.ZERO;
                    }
                }
                state.insert(output.what(), Ae2Amounts.toLong(amount));
            }
            state.addCrafting(pattern.source(), times);
            state.addBytes(times);
        }

        private long fireRing(Site site, RingMacro macro, long totalRequested) {
            if (activeRingIds.contains(macro.ringId()) || state.hasRingMacroFailed(macro.ringId())) {
                return 0L;
            }
            long entryOutput = macro.entryOutputPerCycle();
            if (entryOutput <= 0L || totalRequested <= 0L) {
                return 0L;
            }
            int mark = state.journal();
            activeRingIds.add(macro.ringId());
            try {
                for (var seed : macro.startupSeeds().entrySet()) {
                    AEKey key = seed.getKey();
                    long already = state.ringExtractions().getLong(key);
                    long needed = Math.max(0L, seed.getValue() - already);
                    if (needed <= 0L) {
                        continue;
                    }
                    long reservable = Math.max(0L, reservableStock(key) - already);
                    long extracted = Math.min(needed, reservable);
                    if (extracted > 0L) {
                        state.recordRingExtraction(key, extracted);
                        state.insert(key, extracted);
                        state.addStackBytes(key, extracted, 1L);
                    }
                    if (extracted < needed) {
                        request(ringSite(key), needed - extracted);
                    }
                }
                for (var input : macro.netInputs()) {
                    long required = WideMath.ceilMulDiv(input.getLongValue(), totalRequested, entryOutput);
                    if (required > 0L) {
                        request(ringSite(input.getKey()), required);
                    }
                }
                for (var operation : macro.executionRatio().entrySet()) {
                    long times = WideMath.ceilMulDiv(operation.getValue(), totalRequested, entryOutput);
                    if (times > 0L) {
                        state.addCrafting(operation.getKey(), times);
                    }
                }
                for (var output : macro.netOutputs()) {
                    long produced = WideMath.floorMulDiv(output.getLongValue(), totalRequested, entryOutput);
                    if (produced > 0L) {
                        state.insert(output.getKey(), produced);
                    }
                }
                long fulfilled = state.extract(site.key, totalRequested, false);
                if (fulfilled > 0L) {
                    return fulfilled;
                }
            } catch (BranchFailure failure) {
                state.undoTo(mark);
                state.markRingMacroFailed(macro.ringId());
                return 0L;
            } finally {
                activeRingIds.remove(macro.ringId());
            }
            state.undoTo(mark);
            state.markRingMacroFailed(macro.ringId());
            return 0L;
        }

        private Site ringSite(AEKey key) {
            Object identity = ringSiteIdentities.computeIfAbsent(key, ignored -> new Object());
            return new Site(key, 1L, false, true, null, null, identity,
                    RouteOrdering.routesFor(graph, key, false, false), null, -1);
        }

        private long reservableStock(AEKey key) {
            return graph.stock().getOrDefault(key, 0L);
        }

        private long consumeProduced(Site site, long residual) {
            long extracted = state.extract(site.key, residual, false);
            if (extracted <= 0L) {
                throw BranchFailure.INSTANCE;
            }
            return extracted;
        }
    }

    private static long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    private record Site(AEKey key, long templateAmount, boolean root, boolean ringDependency,
            @Nullable List<GraphInput.Candidate> candidates,
            @Nullable Set<AEKey> validVariants, Object identity,
            RouteOrdering.RouteBundle routes, @Nullable IPatternDetails consumer, int inputSlot) {
    }

    private enum RootSite {
        INSTANCE
    }

    private static final class SiteCatalog {
        private final Set<Object> materialized = java.util.Collections.newSetFromMap(
                new IdentityHashMap<Object, Boolean>());
        private long materializedNodes = 1L;

        private void materialize(Object identity, List<RouteOrdering.Route> routes) {
            if (!materialized.add(identity)) {
                return;
            }
            for (RouteOrdering.Route route : routes) {
                if (route instanceof RouteOrdering.Route.Pattern pattern) {
                    materializedNodes = Math.addExact(materializedNodes,
                            pattern.pattern().inputs().size());
                }
            }
        }
    }

    private record SearchInterval(long low, long high, long residual, long outputPerCraft,
            int routeIndex, long sequence) {
        private long midpoint() {
            return low + ((high - low + 1L) >>> 1);
        }

        private long optimisticResidual() {
            long supply;
            try {
                supply = Math.multiplyExact(high, outputPerCraft);
            } catch (ArithmeticException overflow) {
                supply = Long.MAX_VALUE;
            }
            return Math.max(0L, residual - supply);
        }
    }

    private static final class BranchFailure extends RuntimeException {
        private static final BranchFailure INSTANCE = new BranchFailure();

        private BranchFailure() {
            super(null, null, false, false);
        }
    }

    private static final class SearchLimitExceeded extends RuntimeException {
        private SearchLimitExceeded(String message) {
            super(message, null, false, false);
        }
    }
}
