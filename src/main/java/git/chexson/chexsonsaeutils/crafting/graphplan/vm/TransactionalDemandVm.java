package git.chexson.chexsonsaeutils.crafting.graphplan.vm;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPatternFlows;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.PlanningOverflowException;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.RouteOrdering;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;

/**
 * 独立的事务式需求 VM。
 *
 * <p>编译阶段把根站点和每个样板输入槽冻结成不可变站点程序；执行阶段以需求站点为
 * 指令单元，在 {@link GraphPlanningState} 上直接解释。每次 producer 点火均由
 * {@link GraphPlanningState#journal()} / {@link GraphPlanningState#undoTo(int)} 包围，
 * 因此失败路线不会把库存、模糊类缓存、bytes、副产物或 crafts 泄漏给 fallback。
 *
 * <p>普通 producer 和压缩递归分量都编译为 VM 自身的不可变路线；执行器直接解释
 * {@link RingMacro}，不委托其他规划器。
 */
public final class TransactionalDemandVm {

    private static final long MAX_LEGACY_AMOUNT = 1L << 52;
    private static final int BUDGET_CHECK_STRIDE = 1024;

    private TransactionalDemandVm() {
    }

    /** 冻结图的站点与路线，后续每次 {@link Program#solve} 使用全新的事务台账。 */
    public static Program compile(CraftGraph graph) {
        return new Program(graph);
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount) {
        return compile(graph).solve(strategy, requestedAmount, null);
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount,
            @Nullable GraphPlanningBudget budget) {
        return compile(graph).solve(strategy, requestedAmount, budget);
    }

    /** 不可变 VM 程序；可串行或并发重复执行。 */
    public static final class Program {
        private final CraftGraph graph;
        private final Site root;
        private final Map<GraphPattern, Site[]> slotsByPattern;
        private final Map<AEKey, Site> ringSites;
        @Nullable
        private final String declineReason;

        private Program(CraftGraph graph) {
            this.graph = graph;
            this.declineReason = validate(graph);
            this.slotsByPattern = new IdentityHashMap<>();
            this.root = new Site(graph.target(), 1L, true, false, null, null,
                    RouteOrdering.routesFor(graph, graph.target(), true, false));

            Set<GraphPattern> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (GraphPattern pattern : graph.patternsBySource().values()) {
                if (!seen.add(pattern)) {
                    continue;
                }
                var inputs = pattern.inputs();
                var slots = new Site[inputs.size()];
                for (int index = 0; index < inputs.size(); index++) {
                    GraphInput input = inputs.get(index);
                    slots[index] = new Site(input.key(), input.amountPerCraft(), false, false,
                            input.extractCandidates(), input.validVariants(),
                            RouteOrdering.routesFor(graph, input.key(), false, input.selfBlocked()));
                }
                this.slotsByPattern.put(pattern, slots);
            }

            Set<AEKey> ringDependencyKeys = new HashSet<>();
            for (var entry : graph.ringMacrosByEntry().entrySet()) {
                ringDependencyKeys.add(entry.getKey());
                for (RingMacro macro : entry.getValue()) {
                    ringDependencyKeys.addAll(macro.startupSeeds().keySet());
                    for (var input : macro.netInputs()) {
                        ringDependencyKeys.add(input.getKey());
                    }
                }
            }
            Map<AEKey, Site> compiledRingSites = new HashMap<>();
            for (AEKey key : ringDependencyKeys) {
                compiledRingSites.put(key, new Site(key, 1L, false, true, null, null,
                        RouteOrdering.routesFor(graph, key, false, false)));
            }
            this.ringSites = Map.copyOf(compiledRingSites);
        }

        public SolveResult solve(CalculationStrategy strategy, long requestedAmount) {
            return solve(strategy, requestedAmount, null);
        }

        public CraftGraph graph() {
            return this.graph;
        }

        public SolveResult solve(CalculationStrategy strategy, long requestedAmount,
                @Nullable GraphPlanningBudget budget) {
            if (declineReason != null) {
                return SolveResult.declined(declineReason);
            }
            if (requestedAmount <= 0L) {
                return SolveResult.declined("transactional demand VM: non-positive request amount");
            }
            if (!WideMath.wideEnabled() && requestedAmount > MAX_LEGACY_AMOUNT) {
                return SolveResult.declined("transactional demand VM: request amount above planner cap");
            }

            try {
                return new Machine(this, budget).solve(strategy, requestedAmount);
            } catch (VmDecline declined) {
                return SolveResult.declined(declined.getMessage());
            } catch (PlanningOverflowException overflow) {
                return SolveResult.declined(overflow.getMessage());
            }
        }

        private static @Nullable String validate(CraftGraph graph) {
            if (graph.hasDeclined()) {
                return graph.declineReason();
            }
            if (graph.cycleKind() == CraftGraph.CycleKind.UNSUPPORTED_CYCLE) {
                return "transactional demand VM: unsupported recursive cycle";
            }
            return null;
        }
    }

    /**
     * 一次 solve 的 VM 运行时。站点激活位图跨 full/CRAFT_LESS/simulation attempt
     * 保留，复刻树规划器节点只物化一次、最终 attempt 统一收节点费的语义。
     */
    private static final class Machine {
        private final Program program;
        @Nullable
        private final GraphPlanningBudget budget;
        private final Set<Site> materialized = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<RingId> activeRingIds = new HashSet<>();
        private GraphPlanningState state;
        private boolean simulate;
        private int materializedNodes = 1;
        private int budgetCountdown = 1;

        private Machine(Program program, @Nullable GraphPlanningBudget budget) {
            this.program = program;
            this.budget = budget;
        }

        private SolveResult solve(CalculationStrategy strategy, long requestedAmount) {
            GraphPlanningState full = attempt(false, requestedAmount);
            if (full != null) {
                return SolveResult.success(full, requestedAmount, false);
            }

            if (strategy == CalculationStrategy.CRAFT_LESS) {
                long successfulAmount = 0L;
                GraphPlanningState successfulPlan = null;
                for (long increment = Long.highestOneBit(requestedAmount); increment > 0L; increment >>>= 1) {
                    long testAmount = successfulAmount + increment;
                    if (testAmount >= requestedAmount) {
                        continue;
                    }
                    GraphPlanningState candidate = attempt(false, testAmount);
                    if (candidate != null) {
                        successfulAmount = testAmount;
                        successfulPlan = candidate;
                    }
                }
                if (successfulPlan != null) {
                    return SolveResult.success(successfulPlan, successfulAmount, false);
                }
            }

            GraphPlanningState simulated = attempt(true, requestedAmount);
            if (simulated == null) {
                throw new VmDecline("transactional demand VM: simulation did not produce a ledger");
            }
            return SolveResult.success(simulated, requestedAmount, true);
        }

        private @Nullable GraphPlanningState attempt(boolean simulation, long amount) {
            this.state = new GraphPlanningState(program.graph.stock(), program.graph.classVariants());
            this.simulate = simulation;
            this.budgetCountdown = 1;
            this.activeRingIds.clear();
            this.state.ignore(program.graph.target());
            try {
                request(program.root, amount);
            } catch (BranchFailure failure) {
                return null;
            }
            this.state.addBytes(materializedNodes * 8L);
            if (this.state.hasNegativeExitValues()) {
                throw new VmDecline("transactional demand VM: negative exit ledger");
            }
            String violation = MaterialClosureGate.validate(this.state, program.graph, amount);
            if (violation != null) {
                throw new VmDecline("transactional demand VM: closure gate — " + violation);
            }
            return this.state;
        }

        /** 解释一个需求站点：库存 → emitter → 有序 producer → missing/failure。 */
        private void request(Site site, long requestedUnits) {
            checkBudget();
            try {
                state.addStackBytes(site.key, site.templateAmount, requestedUnits);
                requestedUnits -= extractTemplates(site, requestedUnits);
                if (requestedUnits == 0L) {
                    return;
                }

                if (program.graph.emittable().contains(site.key)) {
                    state.emitItems(site.key, WideMath.wideMul(site.templateAmount, requestedUnits));
                    return;
                }

                long totalRequested = WideMath.wideMul(site.templateAmount, requestedUnits);
                RouteOrdering.RouteBundle routes = site.routes;
                if (totalRequested > 0L && materialized.add(site) && !site.ringDependency) {
                    for (RouteOrdering.Route route : routes.routes()) {
                        if (route instanceof RouteOrdering.Route.Pattern patternRoute) {
                            materializedNodes = Math.addExact(materializedNodes,
                                    patternRoute.pattern().inputs().size());
                        }
                    }
                }

                boolean multiplePatterns = routes.patternRouteCount() > 1;
                List<RouteOrdering.Route> ordered = !simulate && !site.root
                        ? routes.patternFirst()
                        : routes.routes();
                for (RouteOrdering.Route route : ordered) {
                    if (totalRequested <= 0L) {
                        return;
                    }
                    long fulfilled;
                    if (route instanceof RouteOrdering.Route.Ring ringRoute) {
                        fulfilled = fireRing(site, ringRoute.macro(), totalRequested);
                    } else {
                        GraphPattern pattern = ((RouteOrdering.Route.Pattern) route).pattern();
                        fulfilled = multiplePatterns
                                ? fireFallbackRoute(site, pattern, totalRequested)
                                : fireSingleRoute(site, pattern, totalRequested);
                    }
                    totalRequested -= fulfilled;
                }

                if (totalRequested > 0L) {
                    if (simulate) {
                        state.addMissing(site.key, totalRequested);
                    } else {
                        throw BranchFailure.INSTANCE;
                    }
                }
            } catch (ArithmeticException overflow) {
                throw new PlanningOverflowException("transactional demand VM arithmetic overflow at " + site.key);
            }
        }

        private long extractTemplates(Site site, long requestedUnits) {
            if (site.templates == null) {
                return state.extractTemplates(site.key, site.templateAmount, requestedUnits);
            }
            long remaining = requestedUnits;
            Set<AEKey> valid = site.validVariants == null ? Set.of() : site.validVariants;
            for (GraphInput.Candidate candidate : site.templates) {
                for (AEKey variant : state.findFuzzyTemplates(candidate.key())) {
                    if (!valid.contains(variant)) {
                        continue;
                    }
                    long extracted = state.extractTemplates(variant, candidate.amount(), remaining);
                    remaining -= extracted;
                    if (remaining == 0L) {
                        return requestedUnits;
                    }
                }
            }
            return requestedUnits - remaining;
        }

        /** 单 producer 走整批事务；失败即整条路线放弃。 */
        private long fireSingleRoute(Site site, GraphPattern pattern, long totalRequested) {
            long produced = 0L;
            while (totalRequested > 0L) {
                checkBudget();
                int mark = state.journal();
                long times = Math.ceilDiv(totalRequested, pattern.outputPerCraft());
                try {
                    fire(pattern, times);
                } catch (BranchFailure failure) {
                    state.undoTo(mark);
                    break;
                }
                long available = state.extract(site.key, totalRequested, false);
                if (available <= 0L) {
                    state.undoTo(mark);
                    throw new VmDecline("transactional demand VM: producer made no extractable output at "
                            + site.key);
                }
                totalRequested -= available;
                produced += available;
            }
            return produced;
        }

        /**
         * 多 producer 路线按整批开始、失败折半；成功后扩大批次。这样前一路线能消费
         * 可完成的最大整 firing，余量再交给注册顺序中的下一路线。
         */
        private long fireFallbackRoute(Site site, GraphPattern pattern, long totalRequested) {
            long perCraft = Math.max(1L, pattern.outputPerCraft());
            long batch = Math.ceilDiv(totalRequested, perCraft);
            long produced = 0L;
            while (totalRequested > 0L && batch > 0L) {
                checkBudget();
                int mark = state.journal();
                try {
                    fire(pattern, batch);
                } catch (BranchFailure failure) {
                    state.undoTo(mark);
                    batch >>>= 1;
                    continue;
                }
                long available = state.extract(site.key, totalRequested, false);
                if (available <= 0L) {
                    state.undoTo(mark);
                    break;
                }
                totalRequested -= available;
                produced += available;
                long ceiling = Math.ceilDiv(totalRequested, perCraft);
                batch = Math.min(saturatingDouble(batch), ceiling);
            }
            return produced;
        }

        /** 解释一次 producer 点火指令，所有写入都经过 GraphPlanningState journal。 */
        private void fire(GraphPattern pattern, long times) {
            Site[] slots = program.slotsByPattern.get(pattern);
            if (slots == null) {
                throw new VmDecline("transactional demand VM: missing compiled slots for pattern "
                        + pattern.source().getDefinition());
            }
            List<GraphInput> inputs = pattern.inputs();
            for (int index = 0; index < inputs.size(); index++) {
                GraphInput input = inputs.get(index);
                switch (input.mode()) {
                    case NORMAL, FINITE_USE ->
                        request(slots[index], WideMath.wideMul(input.multiplier(), times));
                    case RETURNED, CONSUMED_RETURNING -> {
                        request(slots[index], input.multiplier());
                        state.addStackBytes(input.key(), input.amountPerCraft(),
                                WideMath.wideMul(input.multiplier(), times - 1L));
                    }
                }
            }

            for (GraphInput input : inputs) {
                switch (input.mode()) {
                    case RETURNED -> {
                        state.insert(input.remainderKey(), input.multiplier());
                        state.addStackBytes(input.remainderKey(), input.multiplier(), times);
                    }
                    case FINITE_USE -> {
                        long units = WideMath.wideMul(input.multiplier(), times);
                        state.insert(input.remainderKey(), units);
                        state.addStackBytes(input.remainderKey(), units, 1L);
                    }
                    default -> {
                    }
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
            Site site = program.ringSites.get(key);
            if (site == null) {
                throw new VmDecline("transactional demand VM: missing compiled ring dependency site at " + key);
            }
            return site;
        }

        private long reservableStock(AEKey key) {
            return program.graph.stock().getOrDefault(key, 0L);
        }

        private void checkBudget() {
            if (budget == null || --budgetCountdown > 0) {
                return;
            }
            budgetCountdown = BUDGET_CHECK_STRIDE;
            budget.check();
        }
    }

    private static long saturatingDouble(long value) {
        return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
    }

    private record Site(AEKey key, long templateAmount, boolean root, boolean ringDependency,
            @Nullable List<GraphInput.Candidate> templates, @Nullable Set<AEKey> validVariants,
            RouteOrdering.RouteBundle routes) {
    }

    private static final class BranchFailure extends RuntimeException {
        private static final BranchFailure INSTANCE = new BranchFailure();

        private BranchFailure() {
            super(null, null, false, false);
        }
    }

    private static final class VmDecline extends RuntimeException {
        private VmDecline(String message) {
            super(message, null, false, false);
        }
    }
}
