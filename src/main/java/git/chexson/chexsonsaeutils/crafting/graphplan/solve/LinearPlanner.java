package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPatternFlows;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

/**
 * Level-1 linear solver for a compiled {@link CraftGraph}.
 *
 * <p>The request recursion reproduces AE2's tree planner site by site (slot
 * order, template extraction, emit short-circuit, ceil batch firing, byproduct
 * insert, missing accounting) but on plain exact-key maps instead of the fuzzy
 * simulation state, and without any per-tick machinery. Routes are tried in
 * the tree planner's deterministic order; every ring-macro expansion runs
 * behind a journal
 * mark that is rolled back on failure, mirroring the tree planner's sandboxed
 * ring replacement. {@code CRAFT_LESS} reuses the same site tree
 * across binary search attempts exactly like AE2 reuses its node cache, and an
 * optimistic capacity bound (stock + route upper bounds including byproduct
 * supply) skips attempts that can't succeed.
 */
public final class LinearPlanner {

    /**
     * Requests above this cap decline instead of attempting; every accounting
     * product stays far below the 2^53 exact-double range and the optimistic
     * bound's saturation ceiling. Only enforced when the wide amount solver is
     * off — the wide path tracks amounts exactly beyond this range.
     */
    public static final long MAX_AMOUNT = 1L << 52;

    private static final long SATURATION = 1L << 62;

    /** Planning recursion ceiling — configurable fallback for very deep chains. */
    private static final int MAX_DEPTH = ChexsonsaeutilsCompatibilityConfig.graphPlanningMaxDepth();

    private LinearPlanner() {
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount) {
        return solve(graph, strategy, requestedAmount, false, null);
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount,
            boolean lexicographicRoutes) {
        return solve(graph, strategy, requestedAmount, lexicographicRoutes, null);
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount,
            boolean lexicographicRoutes, @Nullable GraphPlanningBudget budget) {
        if (graph.hasDeclined()) {
            return SolveResult.declined(graph.declineReason());
        }
        if (requestedAmount <= 0) {
            return SolveResult.declined("non-positive request amount");
        }
        if (!WideMath.wideEnabled() && requestedAmount > MAX_AMOUNT) {
            return SolveResult.declined("request amount above planner cap");
        }

        var capacityBound = new CapacityUpperBound(graph);
        long upperBoundStart = System.nanoTime();
        long rootCap = capacityBound.upperBound(graph.target());
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("solve.upperBound target=%s rootCap=%d took %d us",
                    graph.target(), rootCap, (System.nanoTime() - upperBoundStart) / 1_000L);
        }
        var sites = new SiteTree(graph, lexicographicRoutes, budget, capacityBound);
        try {
            if (GraphDiagnostics.traceActive()) {
                // routeSelection 是全图 Tarjan SCC，只为这一行诊断服务（求解本身
                // 不消费它）。实机实测这一行要 40 ms——诊断关着也照样付费，
                // 因为调用点在 active() 判定之外。降到 trace 档按需计算。
                GraphDiagnostics.trace("solve.routeSelection acyclic=%s cumulative %d us",
                        graph.routeSelection().acyclic(), (System.nanoTime() - upperBoundStart) / 1_000L);
            }

            if (rootCap >= requestedAmount) {
                long fullStart = System.nanoTime();
                var full = sites.attempt(false, requestedAmount);
                if (GraphDiagnostics.active()) {
                    GraphDiagnostics.log("solve.full %s took %d ms",
                            full != null ? "success" : "branch-failed",
                            (System.nanoTime() - fullStart) / 1_000_000L);
                }
                if (full != null) {
                    String violation = closureViolation(graph, full, requestedAmount);
                    if (violation != null) {
                        return SolveResult.declined(violation);
                    }
                    return SolveResult.success(full, requestedAmount, false);
                }
            }

            if (strategy == CalculationStrategy.CRAFT_LESS) {
                long successfulAmount = 0;
                GraphPlanningState successfulPlan = null;
                for (long increment = Long.highestOneBit(requestedAmount); increment > 0; increment /= 2) {
                    long testAmount = successfulAmount + increment;
                    if (testAmount < requestedAmount && testAmount <= rootCap) {
                        var attempt = sites.attempt(false, testAmount);
                        if (attempt != null) {
                            successfulAmount = testAmount;
                            successfulPlan = attempt;
                        }
                    }
                }
                if (successfulPlan != null) {
                    String violation = closureViolation(graph, successfulPlan, successfulAmount);
                    if (violation != null) {
                        return SolveResult.declined(violation);
                    }
                    return SolveResult.success(successfulPlan, successfulAmount, false);
                }
            }

            long simulateStart = System.nanoTime();
            var simulated = sites.attempt(true, requestedAmount);
            if (GraphDiagnostics.active()) {
                GraphDiagnostics.log("solve.simulate %s took %d ms",
                        simulated != null ? "ok" : "null",
                        (System.nanoTime() - simulateStart) / 1_000_000L);
            }
            String violation = closureViolation(graph, simulated, requestedAmount);
            if (violation != null) {
                return SolveResult.declined(violation);
            }
            return SolveResult.success(simulated, requestedAmount, true);
        } catch (PlanningOverflowException e) {
            return SolveResult.declined(e.getMessage());
        } catch (RecursionTooDeepException e) {
            return SolveResult.declined("planning recursion deeper than " + MAX_DEPTH);
        }
    }

    /**
     * Phase 2d B4 — post-solve conservation gate; only consulted on SUCCESS
     * so it never participates in the search. Returns the violation text or
     * null when the gate is disabled / the ledger closes.
     */
    private static @Nullable String closureViolation(CraftGraph graph, GraphPlanningState state, long finalAmount) {
        if (!ChexsonsaeutilsCompatibilityConfig.graphPlanningClosureGateEnabled()) {
            return null;
        }
        long start = GraphDiagnostics.active() ? System.nanoTime() : 0L;
        String violation = MaterialClosureGate.validate(state, graph, finalAmount);
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("solve.closureGate took %d us decline=%s",
                    (System.nanoTime() - start) / 1_000L, violation);
        }
        return violation;
    }

    private static final class RecursionTooDeepException extends RuntimeException {
        private RecursionTooDeepException() {
            super(null, null, false, false);
        }
    }

    private static final class BranchFailure extends RuntimeException {
        private BranchFailure() {
            super(null, null, false, false);
        }
    }

    /**
     * The reusable site tree: one site per tree-planner node (root or pattern
     * input slot). Slots are built lazily on first route use, exactly like AE2
     * builds child nodes in {@code buildChildPatterns}, so the node count —
     * and therefore the trailing {@code bytes} charge — matches the tree
     * planner for every attempt. Ring-macro dependency sites are transient in
     * the tree planner (fresh root nodes inside the sandbox), so they are
     * excluded from the node count.
     */
    private static final class SiteTree {
        private final Site root;
        private final boolean lexicographicRoutes;
        @Nullable
        private final GraphPlanningBudget budget;
        /** Shared memoized capacity bound — root cap plus per-route pruning. */
        private final CapacityUpperBound capacityBound;
        @Nullable
        private final CraftGraph.DenseKeyView denseKeyView;
        private GraphPlanningState state;
        private boolean simulate;
        private int depth;
        /**
         * K1 — materialized site count, maintained incrementally as slots are
         * lazily built (ring dependency sites excluded, exactly like the
         * recursive {@code nodeCount} they replace).
         */
        private int materializedNodes;
        /**
         * 逐站点量级的诊断改为按 attempt 聚合计数（此前每站点/每点火各写一行，
         * modpack 实测单次计算攒出 39 MB 文本，格式化本身就是整秒级开销）。
         */
        private long requestCount;
        private long branchFailureCount;
        private long missingSiteCount;
        private long ringMacroFailureCount;
        /** 本次 attempt 是否记逐站点 trace（ThreadLocal 查询不进逐 request 路径）。 */
        private boolean traceSites;
        private long ringMacroProbeCount;
        private long ringMacroFireCount;
        private long ringMacroNanos;
        /** 跨 attempt（并跨同线程的后续请求）复用的 undo 日志底层数组。 */
        private final PlanJournal.Arena journalArena = PlanJournal.Arena.forCurrentThread();
        /**
         * 键内化表：优先复用图内编译期预编号（建图时一次定死），
         * 手工图/测试图无预编号时回落自建。站点树跨 attempt 复用，
         * 所以 id 在整次求解内稳定，站点与样板产出可以在建站时
         * 把 id 定下来，热路径完全不再哈希键。
         */
        private final git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex keyIndex;
        /** Active ring identities — the tree planner's ringsBeingReplaced guard. */
        private final Set<RingId> activeRingIds = new HashSet<>();
        /**
         * 槽位路线视图按「产出样板」整排缓存：一排里第 x 个元素就是该样板第 x
         * 个输入槽的路线视图（键取自输入，且与树位置无关）。
         * 站点树里同一样板会在上万个位置重复展开，逐槽重装配要 3 个 ArrayList
         * + 1 个 HashMap + 一次稳定排序，是 42 万站点建树的主要开销。
         */
        private final Map<GraphPattern, RouteOrdering.RouteBundle[]> slotBundles = new java.util.IdentityHashMap<>();
        /**
         * 非环依赖子树的槽站点按样板整排共享：子站点的全部字段除
         * {@code isRingDependency} 外都只由 (样板, 槽序) 决定（键/模板量/
         * 叶子标记/候选集/路线 bundle 全取自该槽的输入），而站点对象
         * 本身无状态（求解状态全在台账里）。同一样板在树里展开上万次，
         * 共享后每样板只建一排站点，42 万站点对象降到槽总数规模。
         * 环依赖子树（isRingDependency=true）的子站点不共享——它们的
         * 标记要继承父站点，且不计入节点数，数量本就极少。
         */
        private final Map<GraphPattern, Site[]> sharedSlots = new java.util.IdentityHashMap<>();

        private final Map<GraphPattern, int[]> patternOutputIdCache = new java.util.IdentityHashMap<>();

        private int[] outputIdsOf(GraphPattern pattern) {
            var cached = patternOutputIdCache.get(pattern);
            if (cached == null) {
                var outputs = pattern.allOutputs();
                cached = new int[outputs.size()];
                for (int x = 0; x < cached.length; x++) {
                    cached[x] = keyIndex.idOf(outputs.get(x).what());
                }
                patternOutputIdCache.put(pattern, cached);
            }
            return cached;
        }

        private RouteOrdering.RouteBundle[] slotBundlesFor(GraphPattern pattern) {
            var cached = slotBundles.get(pattern);
            if (cached == null) {
                cached = assembleSlotBundles(root.graph, pattern);
                slotBundles.put(pattern, cached);
            }
            return cached;
        }

        private static RouteOrdering.RouteBundle[] assembleSlotBundles(CraftGraph graph, GraphPattern pattern) {
            var inputs = pattern.inputs();
            var bundles = new RouteOrdering.RouteBundle[inputs.size()];
            for (int x = 0; x < bundles.length; x++) {
                var input = inputs.get(x);
                bundles[x] = input.selfBlocked()
                        ? RouteOrdering.RouteBundle.EMPTY
                        : assembleRoutes(graph, input.key(), false);
            }
            return bundles;
        }

        /**
         * 一个键的路线视图。{@code isRoot} 时额外挂 rootOnly 宏
         * （{@code tryRootRingReplacement} 只在根跑）。
         */
        private static RouteOrdering.RouteBundle assembleRoutes(CraftGraph graph, AEKey what, boolean isRoot) {
            var patterns = graph.producersByOutput().getOrDefault(what, List.of());
            var graphMacros = graph.ringMacrosByEntry().getOrDefault(what, List.of());
            if (patterns.isEmpty() && graphMacros.isEmpty()) {
                return RouteOrdering.RouteBundle.EMPTY;
            }
            var macros = new ArrayList<RingMacro>(graphMacros.size());
            RingMacro rootMacro = null;
            for (var macro : graphMacros) {
                if (macro.rootOnly()) {
                    if (isRoot && rootMacro == null) {
                        rootMacro = macro;
                    }
                } else {
                    macros.add(macro);
                }
            }
            return RouteOrdering.assembleRoutes(patterns, macros, rootMacro);
        }

        private SiteTree(CraftGraph graph, boolean lexicographicRoutes, @Nullable GraphPlanningBudget budget,
                CapacityUpperBound capacityBound) {
            this.lexicographicRoutes = lexicographicRoutes;
            this.budget = budget;
            this.capacityBound = capacityBound;
            this.denseKeyView = graph.denseKeyView();
            var sharedKeys = graph.keyIndex();
            this.keyIndex = sharedKeys == null
                    ? new git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex(256)
                    : sharedKeys;
            this.root = new Site(this, graph.target(), 1, graph, true, false, false, null, null,
                    assembleRoutes(graph, graph.target(), true));
            this.materializedNodes = 1;
        }

        /**
         * 预算检查按批摊销：{@code System.nanoTime()} 在 Windows 上是不可优化掉的
         * 序列化调用，而这里原先每次 request、每次点火各调一次——一次 attempt 就是
         * 三四十万次。1024 次 request 的粒度对上百毫秒的预算足够精细。
         *
         * <p>回放基准 {@code budget == null} 走空判定，所以这笔开销只在实机出现，
         * 是回放与实机读数差距的一部分。
         */
        private static final int BUDGET_CHECK_STRIDE = 1024;
        private int budgetCheckCountdown;

        private void checkBudget() {
            if (this.budget == null) {
                return;
            }
            if (--this.budgetCheckCountdown > 0) {
                return;
            }
            this.budgetCheckCountdown = BUDGET_CHECK_STRIDE;
            this.budget.check();
        }

        @Nullable
        private GraphPlanningState attempt(boolean simulate, long amount) {
            this.state = new GraphPlanningState(root.graph.stock(), root.graph.classVariants(),
                    this.journalArena, this.keyIndex,
                    this.denseKeyView == null ? null : this.denseKeyView.classMemberIdsByKeyId(),
                    root.graph.denseStock());
            this.simulate = simulate;
            this.traceSites = GraphDiagnostics.traceActive();
            this.budgetCheckCountdown = 1;
            this.requestCount = 0;
            this.branchFailureCount = 0;
            this.missingSiteCount = 0;
            this.ringMacroFailureCount = 0;
            this.ringMacroProbeCount = 0;
            this.ringMacroFireCount = 0;
            this.ringMacroNanos = 0;
            this.state.ignore(root.what);
            try {
                request(root, amount);
            } catch (BranchFailure failure) {
                return null;
            }
            // Add bytes for the tree size.
            this.state.addBytes(materializedNodes * 8L);
            if (GraphDiagnostics.active()) {
                GraphDiagnostics.log(
                        "solve.attempt nodes=%d requests=%d branchFails=%d missingSites=%d ringMacroFails=%d"
                                + " ringProbes=%d ringFires=%d ringMs=%d nodeBytes=%s stackBytes=%s",
                        materializedNodes, requestCount, branchFailureCount, missingSiteCount,
                        ringMacroFailureCount, ringMacroProbeCount, ringMacroFireCount,
                        ringMacroNanos / 1_000_000L, this.state.nodeBytesAccum(), this.state.stackBytesAccum());
            }
            return this.state;
        }

        private void request(Site site, long requestedAmount) {
            if (++depth > MAX_DEPTH) {
                throw new RecursionTooDeepException();
            }
            try {
                checkBudget();
                state.addStackBytes(site.what, site.amount, requestedAmount);

                // 1) Collect items from the inventory (whole templates), in the
                // tree planner's greedy order: candidates × live class variants,
                // filtered by the pattern's isValid (precomputed as
                // validVariants). Exact-key fast path when the node key is the
                // only valid template.
                if (site.templates == null) {
                    long extracted = state.extractTemplatesById(site.what, site.keyId(), site.amount,
                            requestedAmount);
                    if (extracted > 0L && site.consumer != null) {
                        state.recordInputAllocation(
                                site.consumer, site.inputSlot, site.what, site.amount, extracted);
                    }
                    requestedAmount -= extracted;
                    if (requestedAmount == 0) {
                        return;
                    }
                } else {
                    for (var template : site.fuzzyTemplates()) {
                        for (int sourceId : template.sourceIds()) {
                            if (requestedAmount <= 0L) {
                                break;
                            }
                            AEKey source = keyIndex.keyOf(sourceId);
                            long units = state.extractTemplatesById(
                                    source, sourceId, template.amount(), requestedAmount);
                            if (units > 0L) {
                                if (site.consumer != null) {
                                    state.recordInputAllocation(site.consumer, site.inputSlot,
                                            source, template.amount(), units);
                                }
                                requestedAmount -= units;
                            }
                        }
                        if (requestedAmount == 0L) {
                            return;
                        }
                    }
                }

                // 2) Emittable items.
                if (site.isEmittable()) {
                    if (site.consumer != null && requestedAmount > 0L) {
                        state.recordInputAllocation(
                                site.consumer, site.inputSlot, site.what, site.amount, requestedAmount);
                    }
                    state.emitItems(site.what, WideMath.wideMul(site.amount, requestedAmount));
                    return;
                }

                // 3) Routes keep the graph compiler's deterministic order.
                // patterns first (RouteOrdering). Every route fires greedily
                // until its inputs run out, then the next route takes over —
                // the tree planner's process loop. Mirroring
                // buildChildPatterns, consulting routes materializes every
                // pattern route's input slots so the node count matches the
                // tree's eager child construction.
                long totalRequested = WideMath.wideMul(requestedAmount, site.amount);
                if (totalRequested > 0 && !site.slotsBuilt) {
                    site.countAllSlots();
                }
                requestCount++;
                if (traceSites) {
                    GraphDiagnostics.trace(
                            "request site=%s isRoot=%s amount=%d remaining=%d totalRequested=%d routes=%d emittable=%s",
                            site.what, site.isRoot, site.amount, requestedAmount, totalRequested, site.routes.size(),
                            site.isEmittable());
                }
                boolean multiBranch = site.patternRouteCount > 1;
                // 非模拟子站点先尝试普通样板，再用环宏处理剩余需求；
                // 模拟和根站点保持编译后的宏优先顺序。
                var orderedRoutes = site.routes;
                if (!simulate && !site.isRoot && site.routes.size() > 1) {
                    orderedRoutes = site.routesPatternFirst;
                }
                if (this.lexicographicRoutes && orderedRoutes.size() > 1) {
                    totalRequested -= fireRoutesLexicographic(site, orderedRoutes, totalRequested,
                            multiBranch);
                } else {
                    for (var route : orderedRoutes) {
                        if (totalRequested <= 0) {
                            if (site.consumer != null && requestedAmount > 0L) {
                                state.recordInputAllocation(site.consumer, site.inputSlot,
                                        site.what, site.amount, requestedAmount);
                            }
                            return;
                        }
                        if (route instanceof RouteOrdering.Route.Ring ring) {
                            totalRequested -= fireRingMacro(site, ring.macro(), totalRequested);
                        } else {
                            var patternRoute = (RouteOrdering.Route.Pattern) route;
                            totalRequested -= multiBranch
                                    ? firePerFiring(site, patternRoute, totalRequested)
                                    : firePatternRoute(site, patternRoute, totalRequested);
                        }
                    }
                }

                if (totalRequested > 0) {
                    if (simulate) {
                        missingSiteCount++;
                        if (traceSites) {
                            GraphDiagnostics.trace("solve.addMissing site=%s isRoot=%s unfulfilled=%d routes=%d",
                                    site.what, site.isRoot, totalRequested, site.routes.size());
                        }
                        state.addMissing(site.what, totalRequested);
                    } else {
                        if (traceSites) {
                            GraphDiagnostics.trace("solve.branchFail site=%s unfulfilled=%d routes=%d",
                                    site.what, totalRequested, site.routes.size());
                        }
                        throw new BranchFailure();
                    }
                } else if (site.consumer != null && requestedAmount > 0L) {
                    state.recordInputAllocation(site.consumer, site.inputSlot,
                            site.what, site.amount, requestedAmount);
                }
            } finally {
                depth--;
            }
        }

        /**
         * Fires one pattern route greedily: every attempt is journaled so a
         * failure rolls back only the keys it touched (the tree planner
         * discards its attempt sandbox on {@code CraftBranchFailure}); a
         * failed attempt ends the route and the next one takes over the
         * remainder.
         */
        private long firePatternRoute(Site site, RouteOrdering.Route.Pattern route, long totalRequested) {
            GraphPattern pattern = route.pattern();
            long craftedPerPattern = pattern.outputPerCraft();
            long produced = 0;
            while (totalRequested > 0) {
                checkBudget();
                int mark = state.journal();
                long times = (totalRequested + craftedPerPattern - 1) / craftedPerPattern;
                try {
                    fire(site, route, times);
                } catch (BranchFailure failure) {
                    branchFailureCount++;
                    if (traceSites) {
                        GraphDiagnostics.trace("BRANCH_FAIL pattern=%s site=%s times=%d",
                                pattern.source().getDefinition(), site.what, times);
                    }
                    state.undoTo(mark);
                    break;
                }
                long available = state.extractById(site.what, site.keyId(), totalRequested, false);
                if (available != 0) {
                    totalRequested -= available;
                    produced += available;
                } else {
                    // Mirrors the tree planner's "can't find created items" guard.
                    throw new IllegalStateException(
                            "Unexpected error in the crafting calculation: can't find created items.");
                }
            }
            return produced;
        }

        /**
         * One ring-macro expansion, mirroring the tree planner's
         * {@code tryRingReplacement}: catalysts are reserved from stock (the
         * {@code trackRingUsage} ledger), shortfalls and net inputs are
         * requested like fresh root nodes, {@code executionRatio} lands in
         * {@code patternTimes} and {@code netOutputs} in the sandbox — both
         * with zero bytes — and the fulfilled amount is extracted from stock.
         * Any failure rolls the journal back to the entry mark and marks the
         * ring failed for the rest of the attempt. Every ledger write inside
         * goes through the journal proxy, so the rollback is O(touched keys)
         * and the delta-entry invariant (current = mark + Σdeltas) holds.
         */
        private long fireRingMacro(Site site, RingMacro macro, long totalRequested) {
            ringMacroProbeCount++;
            if (activeRingIds.contains(macro.ringId())) {
                return 0;
            }
            if (state.hasRingMacroFailed(macro.ringId())) {
                return 0;
            }
            long entryOutput = macro.entryOutputPerCycle();
            if (entryOutput <= 0 || totalRequested <= 0) {
                return 0;
            }
            ringMacroFireCount++;
            long ringStart = GraphDiagnostics.active() ? System.nanoTime() : 0L;
            try {
                return fireRingMacroBody(site, macro, totalRequested, entryOutput);
            } finally {
                if (GraphDiagnostics.active()) {
                    ringMacroNanos += System.nanoTime() - ringStart;
                }
            }
        }

        private long fireRingMacroBody(Site site, RingMacro macro, long totalRequested, long entryOutput) {

            if (GraphDiagnostics.traceActive()) {
                GraphDiagnostics.trace("solve.ringMacro entry=%s requested=%d scaleBase=%d",
                        macro.entryKey(), totalRequested, macro.entryOutputPerCycle());
            }
            int mark = state.journal();
            boolean applied = false;
            activeRingIds.add(macro.ringId());
            boolean wide = WideMath.wideEnabled();
            try {
                double scale = (double) totalRequested / entryOutput;

                // requestRingDependencies: reserve the deterministic minimal
                // startup seed set proved by an executionRatio firing
                // schedule. Seeds are not scaled and are deduplicated through
                // the calculation-wide ringExtractions ledger.
                for (var seed : macro.startupSeeds().entrySet()) {
                    AEKey key = seed.getKey();
                    long amountNeeded = seed.getValue();
                    long already = state.ringExtractions().getLong(key);
                    long requiredAmount = Math.max(0L, amountNeeded - already);
                    if (requiredAmount <= 0) {
                        continue;
                    }
                    long reservable = Math.max(0L,
                            reservableStock(site.graph, key) - already);
                    long extracted = Math.min(requiredAmount, reservable);
                    if (extracted > 0L) {
                        state.recordRingExtraction(key, extracted);
                        state.insert(key, extracted);
                        // The reservation enters the CPU inventory at submit
                        // time and stays there for the whole job — charge its
                        // bytes like any other extraction.
                        state.addStackBytes(key, extracted, 1);
                    }
                    if (extracted < requiredAmount) {
                        request(site.ringSlot(key), requiredAmount - extracted);
                    }
                }
                for (var stack : macro.netInputs()) {
                    long required = wide
                            ? WideMath.ceilMulDiv(stack.getLongValue(), totalRequested, entryOutput)
                            : (long) Math.ceil(stack.getLongValue() * scale);
                    if (required > 0) {
                        request(site.ringSlot(stack.getKey()), required);
                    }
                }

                for (var entry : macro.executionRatio().entrySet()) {
                    long times = wide
                            ? WideMath.ceilMulDiv(entry.getValue(), totalRequested, entryOutput)
                            : (long) Math.ceil(entry.getValue() * scale);
                    if (times > 0) {
                        state.addCrafting(entry.getKey(), times);
                    }
                }
                for (var stack : macro.netOutputs()) {
                    long produced = wide
                            ? WideMath.floorMulDiv(stack.getLongValue(), totalRequested, entryOutput)
                            : (long) Math.floor(stack.getLongValue() * scale);
                    if (produced > 0) {
                        state.insert(stack.getKey(), produced);
                    }
                }

                long fulfilled = state.extractById(site.what, site.keyId(), totalRequested, false);
                if (fulfilled > 0) {
                    applied = true;
                    return fulfilled;
                }
                state.undoTo(mark);
                // 失败标记必须落在回滚之后——journal 回滚会抹掉 RING_FAILED
                // 条目，标记若被回滚，确定性求解下失败的环宏会在同一 attempt
                // 内被反复重试（每次都完整展开 netInputs 子树，指数浪费）。
                // 与树路径 failedRingReplacements 的持久语义对齐。
                state.markRingMacroFailed(macro.ringId());
                return 0;
            } catch (BranchFailure | PlanningOverflowException | RecursionTooDeepException failure) {
                ringMacroFailureCount++;
                if (GraphDiagnostics.traceActive()) {
                    GraphDiagnostics.trace("RING_MACRO_FAIL ring=%s entry=%s", macro.ringId(), macro.entryKey());
                }
                state.undoTo(mark);
                state.markRingMacroFailed(macro.ringId());
                return 0;
            } finally {
                if (!applied) {
                    state.undoTo(mark);
                }
                activeRingIds.remove(macro.ringId());
            }
        }

        /**
         * Experimental lexicographic route selection: every candidate route is
         * fired directly on the main ledger behind a journal mark and rolled
         * back before the next candidate runs, so trial cost is O(touched
         * keys) instead of a full ledger copy. The plan comparing best in
         * lexicographic order (fewest leftovers, then fewest missing, then
         * fewest network items, then fewest crafts, then fewest bytes; the
         * route index breaks ties deterministically) is replayed
         * deterministically onto the rolled-back ledger. Recursion inside a
         * candidate stays greedy, so the cost is O(routes × subtree) per
         * competition point, not exponential.
         */
        private long fireRoutesLexicographic(Site site, List<RouteOrdering.Route> routes,
                long totalRequested, boolean multiBranch) {
            GraphPlanningState saved = this.state;
            int mark = saved.journal();
            long baseMissing = totalOf(saved.missingItems());
            long baseUsed = totalOf(saved.usedItems());
            long baseEmitted = totalOf(saved.emittedItems());
            long baseCrafts = totalCrafts(saved);
            double baseBytes = saved.bytes();
            long bestFulfilled = 0;
            long[] bestMetric = null;
            RouteOrdering.Route bestRoute = null;
            for (int index = 0; index < routes.size(); index++) {
                var route = routes.get(index);
                if (bestMetric != null) {
                    // X1a — optimistic capacity pruning: even firing the route
                    // to its memoized upper bound cannot beat the incumbent on
                    // the leading metric (leftover), so the lexically later
                    // components could never come into play. Strictly greater
                    // keeps ties alive for the remaining metric components.
                    long routeCap = routeCapacity(route);
                    if (routeCap < totalRequested - bestMetric[0]) {
                        continue;
                    }
                }
                long fulfilled;
                try {
                    if (route instanceof RouteOrdering.Route.Ring ring) {
                        fulfilled = fireRingMacro(site, ring.macro(), totalRequested);
                    } else {
                        var patternRoute = (RouteOrdering.Route.Pattern) route;
                        fulfilled = multiBranch
                                ? firePerFiring(site, patternRoute, totalRequested)
                                : firePatternRoute(site, patternRoute, totalRequested);
                    }
                } catch (BranchFailure failure) {
                    saved.undoTo(mark);
                    continue;
                }
                long[] metric = {
                        totalRequested - fulfilled,
                        totalOf(saved.missingItems()) - baseMissing,
                        totalOf(saved.usedItems()) - baseUsed,
                        totalOf(saved.emittedItems()) - baseEmitted,
                        totalCrafts(saved) - baseCrafts,
                        (long) Math.ceil(saved.bytes() - baseBytes),
                        index
                };
                if (bestMetric == null || lexicographicCompare(metric, bestMetric) < 0) {
                    bestMetric = metric;
                    bestRoute = route;
                    bestFulfilled = fulfilled;
                }
                saved.undoTo(mark);
            }
            if (bestRoute == null) {
                return 0;
            }            // Witness replay: the firing is deterministic on the rolled-back
            // ledger, so the replay reproduces the winning trial's writes. A
            // replay failure (budget only — the trial itself succeeded) falls
            // back to reporting nothing fulfilled instead of a phantom credit.
            long replayed;
            try {
                if (bestRoute instanceof RouteOrdering.Route.Ring ring) {
                    replayed = fireRingMacro(site, ring.macro(), totalRequested);
                } else {
                    var patternRoute = (RouteOrdering.Route.Pattern) bestRoute;
                    replayed = multiBranch
                            ? firePerFiring(site, patternRoute, totalRequested)
                            : firePatternRoute(site, patternRoute, totalRequested);
                }
            } catch (BranchFailure failure) {
                replayed = 0;
            }
            return replayed;
        }

        /**
         * Optimistic fulfillment ceiling of one route on this site: the
         * memoized capacity bound is strictly optimistic, so a route whose
         * ceiling cannot beat the incumbent's leftover is pruned soundly.
         */
        private long routeCapacity(RouteOrdering.Route route) {
            if (route instanceof RouteOrdering.Route.Ring ring) {
                var macro = ring.macro();
                return CapacityUpperBound.satMul(capacityBound.ringMacroFiringBound(macro), macro.entryOutputPerCycle());
            }
            var pattern = ((RouteOrdering.Route.Pattern) route).pattern();
            return CapacityUpperBound.satMul(capacityBound.firingBound(pattern), pattern.outputPerCraft());
        }

        private static long totalOf(KeyCounter counter) {
            PlanAmount total = PlanAmount.ZERO;
            for (var entry : counter) {
                total = total.add(PlanAmount.of(entry.getLongValue()));
            }
            return Ae2Amounts.toLong(total);
        }

        private static long totalCrafts(GraphPlanningState state) {
            PlanAmount total = PlanAmount.ZERO;
            for (var entry : state.crafts().entrySet()) {
                total = total.add(PlanAmount.of(entry.getValue()));
            }
            return Ae2Amounts.toLong(total);
        }

        private static int lexicographicCompare(long[] left, long[] right) {
            for (int i = 0; i < left.length; i++) {
                int comparison = Long.compare(left[i], right[i]);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        /**
         * Multi-branch route firing, mirroring CraftingTreeNode.request's
         * multiple-branches loop (CraftingTreeNode.java:250-277): each firing
         * is journaled so a firing that fails or produces nothing extractable
         * rolls back only its own writes; the next route then takes over.
         *
         * <p>Batches start at the exact ceil batch and only halve on failure:
         * {@code fire} is linear in {@code times}, so a batch is
         * ledger-equivalent to that many single firings — the only difference
         * is failure granularity, and halving walks straight back down to
         * single-firing granularity when a batch cannot be satisfied. Growing
         * from 1 instead re-walked the whole input subtree {@code log2(times)}
         * times per site, which multiplies level by level on deep chains.
         */
        private long firePerFiring(Site site, RouteOrdering.Route.Pattern route, long totalRequested) {
            GraphPattern pattern = route.pattern();
            long produced = 0;
            long perCraft = Math.max(1L, pattern.outputPerCraft());
            long batch = (totalRequested + perCraft - 1L) / perCraft;
            while (totalRequested > 0) {
                checkBudget();
                int mark = state.journal();
                try {
                    fire(site, route, batch);
                } catch (BranchFailure failure) {
                    branchFailureCount++;
                    if (traceSites) {
                        GraphDiagnostics.trace("BRANCH_FAIL batched pattern=%s site=%s batch=%d producedSoFar=%d",
                                pattern.source().getDefinition(), site.what, batch, produced);
                    }
                    state.undoTo(mark);
                    batch /= 2L;
                    if (batch == 0L) {
                        break;
                    }
                    continue;
                }
                long available = state.extractById(site.what, site.keyId(), totalRequested, false);
                if (available != 0) {
                    totalRequested -= available;
                    produced += available;
                    // ceil 钳制：批次不得越过精确需求（越界 fire 即超产）
                    long maxBatch = (totalRequested + perCraft - 1L) / perCraft;
                    batch = Math.min(batch * 2L, maxBatch);
                } else {
                    state.undoTo(mark);
                    break;
                }
            }
            return produced;
        }

        /** Returns exact stock available for a compiled ring startup seed. */
        private static long reservableStock(CraftGraph graph, AEKey key) {
            return graph.stock().getOrDefault(key, 0L);
        }

        private void fire(Site site, RouteOrdering.Route.Pattern route, long times) {
            var slots = site.slotsFor(route);
            var pattern = route.pattern();
            var outputIds = outputIdsOf(pattern);
            var inputs = pattern.inputs();
            // Request and remove inputs, slot by slot in order. Circular
            // (limitQty) inputs use the closed-form batch of
            // FastLimitQtyBatcher: only the first firing is requested in full,
            // the remaining (times-1) firings are bytes-only compensation —
            // RETURNED cycles net to zero and CONSUMED_RETURNING is covered by
            // the output correction below.
            for (int x = 0; x < inputs.size(); x++) {
                var input = inputs.get(x);
                switch (input.mode()) {
                    case NORMAL, FINITE_USE ->
                        request(slots[x], WideMath.wideMul(input.multiplier(), times));
                    case RETURNED, CONSUMED_RETURNING -> {
                        request(slots[x], input.multiplier());
                        state.addStackBytes(input.key(), input.amountPerCraft(),
                                WideMath.wideMul(input.multiplier(), times - 1));
                    }
                }
            }
            // Container returns: RETURNED restores the first firing's units
            // once (net zero over the batch), FINITE_USE hands back the
            // remainder key once per firing.
            for (int x = 0; x < inputs.size(); x++) {
                var input = inputs.get(x);
                switch (input.mode()) {
                    case RETURNED -> {
                        state.insert(input.remainderKey(), input.multiplier());
                        state.addStackBytes(input.remainderKey(), input.multiplier(), times);
                    }
                    case FINITE_USE -> {
                        long units = WideMath.wideMul(input.multiplier(), times);
                        state.insert(input.remainderKey(), units);
                        state.addStackBytes(input.remainderKey(), units, 1);
                    }
                    default -> {
                    }
                }
            }
            // Add crafting results, including byproducts. Self-feeding
            // catalysts carry the batched correction on the first matching
            // output stack: produced*times - consumed*(times-1), so the
            // catalyst only enters the pool once instead of times times.
            Set<AEKey> corrected = null;
            boolean hasSelfFeeding = pattern.hasSelfFeedingInput();
            var outputs = pattern.allOutputs();
            for (int x = 0; x < outputs.size(); x++) {
                var out = outputs.get(x);
                int outId = outputIds[x];
                if (!hasSelfFeeding) {
                    // 无自喂输入的常态路径：产出量 = amount×times，long 域内
                    // 直接算（此前每个产出栈都要构造 PlanAmount，42 万站点
                    // 规模下是百万级分配）。溢出才走宽域。
                    long fast = fastMul(out.amount(), times);
                    state.insertById(out.what(), outId, fast != Long.MIN_VALUE
                            ? fast
                            : Ae2Amounts.toLong(WideMath.mul(out.amount(), times)));
                    continue;
                }
                PlanAmount amount = WideMath.mul(out.amount(), times);
                PlanAmount consumedPerCraft = GraphPatternFlows.selfConsumedPerCraft(pattern, out.what());
                if (consumedPerCraft.signum() > 0) {
                    if (corrected == null) {
                        corrected = new HashSet<>();
                    }
                    if (corrected.add(out.what())) {
                        PlanAmount produced = GraphPatternFlows.outputPerCraftOf(pattern, out.what()).multiply(PlanAmount.of(times));
                        PlanAmount consumed = consumedPerCraft.multiply(PlanAmount.of(times - 1));
                        amount = produced.subtract(consumed);
                    } else {
                        amount = PlanAmount.ZERO;
                    }
                }
                state.insertById(out.what(), outId, Ae2Amounts.toLong(amount));
            }
            state.addCrafting(pattern.source(), times);
            state.addBytes(times);
        }

        /** long 域乘法；溢出返回 {@link Long#MIN_VALUE} 作哨兵。 */
        private static long fastMul(long a, long b) {
            long r = a * b;
            if (a != 0 && (r / a != b || (a == -1 && b == Long.MIN_VALUE))) {
                return Long.MIN_VALUE;
            }
            return r == Long.MIN_VALUE ? Long.MIN_VALUE : r;
        }

    }

    private static final class Site {
        @Nullable
        private final SiteTree tree;
        private final AEKey what;
        private final long amount;
        private final CraftGraph graph;
        private final boolean isRoot;
        /**
         * Fuzzy extraction template sources from the parent pattern input
         * (candidate × template amount); null for the root and for inputs on
         * the exact-key fast path.
         */
        @Nullable
        private final List<GraphInput.Candidate> templates;
        @Nullable
        private final Set<AEKey> validVariants;
        /** True inside a ring macro's dependency subtree (excluded from nodeCount). */
        private final boolean isRingDependency;
        /**
         * The tree planner's second-level notRecursive termination: every
         * pattern for this key is filtered, so the site is a pattern-less leaf
         * (stock and emit only).
         */
        private final boolean patternLeaf;
        /**
         * 键 id 惰性解析：站点树为对齐 AE2 节点数会把每条样板路线的槽位全部建出来
         * （42 万个），而真正被 request 的只有 25.7 万个——放进构造器就是净亏。
         */
        private int whatId = -1;
        private final List<RouteOrdering.Route> routes;
        /** K2 — pattern route count, precomputed once per site. */
        private final int patternRouteCount;
        /** K2 — the pattern-first variant used by non-simulate requests. */
        private final List<RouteOrdering.Route> routesPatternFirst;
        /**
         * 槽站点按样板路线的稠密下标索引（{@code Route.Pattern.slotIndex}）：
         * 42 万站点规模下每站点一个 HashMap + 条目对象是主要分配来源。
         */
        @Nullable
        private Site[][] patternSlots;

        /** 全部样板路线的输入槽是否已建（每次 request 重扫是纯浪费）。 */
        private boolean slotsBuilt;
        @Nullable
        private Map<AEKey, Site> ringSlots;
        /** 候选键与合法 exact source id 的预译结果；首次 request 后跨 attempt 复用。 */
        @Nullable
        private FuzzyTemplate[] fuzzyTemplates;
        @Nullable
        private final IPatternDetails consumer;
        private final int inputSlot;

        private record FuzzyTemplate(AEKey classKey, int classKeyId, int[] sourceIds, long amount) {
        }

        private Site(@Nullable SiteTree tree, AEKey what, long amount, CraftGraph graph,
                boolean isRoot, boolean isRingDependency, boolean patternLeaf,
                @Nullable List<GraphInput.Candidate> templates, @Nullable Set<AEKey> validVariants,
                RouteOrdering.RouteBundle bundle) {
            this(tree, what, amount, graph, isRoot, isRingDependency, patternLeaf,
                    templates, validVariants, bundle, null, -1);
        }

        private Site(@Nullable SiteTree tree, AEKey what, long amount, CraftGraph graph,
                boolean isRoot, boolean isRingDependency, boolean patternLeaf,
                @Nullable List<GraphInput.Candidate> templates, @Nullable Set<AEKey> validVariants,
                RouteOrdering.RouteBundle bundle, @Nullable IPatternDetails consumer, int inputSlot) {
            this.tree = tree;
            this.what = what;
            this.amount = amount;
            this.graph = graph;
            this.isRoot = isRoot;
            this.isRingDependency = isRingDependency;
            this.patternLeaf = patternLeaf;
            this.templates = templates;
            this.validVariants = validVariants;
            this.routes = bundle.routes();
            this.patternRouteCount = bundle.patternRouteCount();
            this.routesPatternFirst = bundle.patternFirst();
            this.slotsBuilt = this.patternRouteCount == 0;
            this.consumer = consumer;
            this.inputSlot = inputSlot;
        }

        /**
         * 记入每条样板路线的槽位节点数，但**不建对象**。
         *
         * <p>AE2 的节点费只看数量（{@code materializedNodes × 8}），不看对象本身；
         * 而站点树为对齐这个数量要把每条路线的槽位都算上，实机 42 万个，其中真正
         * 被 request 的只有 25.7 万个。所以这里只计数，站点对象留给
         * {@link #slotsFor} 在该路线真被点火时才建——从未点火的路线一个对象都不建。
         */
        private void countAllSlots() {
            this.patternSlots = new Site[patternRouteCount][];
            if (!this.isRingDependency && tree != null) {
                for (var route : routes) {
                    if (route instanceof RouteOrdering.Route.Pattern patternRoute) {
                        tree.materializedNodes += patternRoute.pattern().inputs().size();
                    }
                }
            }
            this.slotsBuilt = true;
        }

        /** 首次请求时才解析键 id（构造期解析会为未被请求的站点白付）。 */
        private int keyId() {
            if (this.whatId < 0) {
                this.whatId = tree != null ? tree.keyIndex.idOf(this.what) : 0;
            }
            return this.whatId;
        }

        private boolean isEmittable() {
            int id = keyId();
            return tree != null && tree.denseKeyView != null
                    ? tree.denseKeyView.isEmittable(id)
                    : graph.emittable().contains(what);
        }

        private FuzzyTemplate[] fuzzyTemplates() {
            var cached = fuzzyTemplates;
            if (cached != null) {
                return cached;
            }
            cached = new FuzzyTemplate[templates.size()];
            for (int candidateIndex = 0; candidateIndex < cached.length; candidateIndex++) {
                var candidate = templates.get(candidateIndex);
                int candidateId = tree.keyIndex.idOf(candidate.key());
                int[] memberIds = tree.denseKeyView == null
                        ? null
                        : tree.denseKeyView.classMemberIds(candidateId);
                if (memberIds == null) {
                    var members = graph.classVariants().get(candidate.key());
                    if (members == null) {
                        memberIds = new int[] { candidateId };
                    } else {
                        memberIds = new int[members.size()];
                        for (int x = 0; x < memberIds.length; x++) {
                            memberIds[x] = tree.keyIndex.idOf(members.get(x));
                        }
                    }
                }
                int validCount = 0;
                for (int memberId : memberIds) {
                    if (validVariants.contains(tree.keyIndex.keyOf(memberId))) {
                        validCount++;
                    }
                }
                var sourceIds = new int[validCount];
                int target = 0;
                for (int memberId : memberIds) {
                    if (validVariants.contains(tree.keyIndex.keyOf(memberId))) {
                        sourceIds[target++] = memberId;
                    }
                }
                cached[candidateIndex] = new FuzzyTemplate(candidate.key(), candidateId, sourceIds,
                        candidate.amount());
            }
            fuzzyTemplates = cached;
            return cached;
        }

        private Site[] buildSlots(GraphPattern pattern) {
            // 非环依赖子树走整排共享：同一样板在全树只建一排站点对象。
            if (!this.isRingDependency && tree != null) {
                var shared = tree.sharedSlots.get(pattern);
                if (shared == null) {
                    shared = buildSlotsInner(pattern);
                    tree.sharedSlots.put(pattern, shared);
                }
                return shared;
            }
            return buildSlotsInner(pattern);
        }

        private Site[] buildSlotsInner(GraphPattern pattern) {
            var inputs = pattern.inputs();
            var slots = new Site[inputs.size()];
            // 节点数已在 countAllSlots 记过，这里只建对象。
            // 槽位路线视图只由 (样板, 槽序) 决定，所以按样板缓存整排路线。
            var bundles = tree != null
                    ? tree.slotBundlesFor(pattern)
                    : SiteTree.assembleSlotBundles(graph, pattern);
            for (int x = 0; x < slots.length; x++) {
                var input = inputs.get(x);
                slots[x] = new Site(tree, input.key(), input.amountPerCraft(), graph, false,
                        this.isRingDependency, input.selfBlocked(), input.extractCandidates(),
                        input.validVariants(), bundles[x], pattern.source(), x);
            }
            return slots;
        }

        private Site[] slotsFor(RouteOrdering.Route.Pattern route) {
            if (!slotsBuilt) {
                countAllSlots();
            }
            int slotIndex = route.slotIndex();
            var slots = patternSlots[slotIndex];
            if (slots == null) {
                slots = buildSlots(route.pattern());
                patternSlots[slotIndex] = slots;
            }
            return slots;
        }



        /** Ring dependency key — requested like a fresh root node. */
        private Site ringSlot(AEKey key) {
            if (ringSlots == null) {
                ringSlots = new HashMap<>();
            }
            return ringSlots.computeIfAbsent(key,
                    k -> new Site(tree, k, 1, graph, false, true, false, null, null,
                            SiteTree.assembleRoutes(graph, k, false)));
        }
    }

    /**
     * Optimistic per-key supply bound: exact stock + (route firing bounds ×
     * output), including byproduct supply from other patterns and ring macros.
     * Never underestimates, so a bound below the request proves failure and
     * skips the attempt. Fuzzy inputs aggregate only the class members accepted
     * by that input's {@code isValid} contract.
     */
    private static final class CapacityUpperBound {
        private final CraftGraph graph;
        private final Map<AEKey, Long> keyCaps = new HashMap<>();
        // 按身份查表：GraphPattern / RingMacro 的 record 深哈希会走完整个输入
        // 与 KeyCounter 结构，而这里查的永远是编译期建好的同一实例。
        private final Map<GraphPattern, Long> patternFirings = new java.util.IdentityHashMap<>();
        private final Map<RingMacro, Long> macroFirings = new java.util.IdentityHashMap<>();

        private CapacityUpperBound(CraftGraph graph) {
            this.graph = graph;
        }

        private long upperBound(AEKey key) {
            if (graph.emittable().contains(key)) {
                return Long.MAX_VALUE;
            }
            var memo = keyCaps.get(key);
            if (memo != null) {
                return memo;
            }
            // Optimistic in-flight marker: re-entering a key mid-computation
            // returns SATURATION so the bound can only over-approximate —
            // "below request proves failure" stays valid on cyclic walks.
            keyCaps.put(key, SATURATION);

            long cap = graph.stock().getOrDefault(key, 0L);
            var producers = graph.producersByOutput().get(key);
            if (producers != null) {
                for (var producer : producers) {
                    cap = satAdd(cap, satMul(firingBound(producer), producer.outputPerCraft()));
                }
            }
            for (var macro : graph.ringMacrosByEntry().getOrDefault(key, List.of())) {
                cap = satAdd(cap, satMul(ringMacroFiringBound(macro), macro.entryOutputPerCycle()));
            }
            List<CraftGraph.ByproductSupply> supplies = graph.byproductSupply().get(key);
            if (supplies != null) {
                for (var supply : supplies) {
                    cap = satAdd(cap, satMul(firingBound(supply.pattern()), supply.amountPerCraft()));
                }
            }
            List<CraftGraph.RemainderSupply> remainders = graph.remainderSupply().get(key);
            if (remainders != null) {
                for (var supply : remainders) {
                    cap = satAdd(cap, satMul(firingBound(supply.pattern()), supply.amountPerCraft()));
                }
            }
            cap = Math.min(cap, SATURATION);
            keyCaps.put(key, cap);
            return cap;
        }

        private long firingBound(GraphPattern pattern) {
            var memo = patternFirings.get(pattern);
            if (memo != null) {
                return memo;
            }
            patternFirings.put(pattern, SATURATION); // cycle guard

            long bound = SATURATION;
            for (var input : pattern.inputs()) {
                if (input.mode() == GraphInput.Mode.RETURNED
                        || input.mode() == GraphInput.Mode.CONSUMED_RETURNING) {
                    // Self-replenishing cycles: no net consumption per firing,
                    // so they never bound the optimistic firing count.
                    continue;
                }
                if (input.selfBlocked()) {
                    // The tree recursion materializes a selfBlocked slot as a
                    // pattern-less leaf. Exact slots can draw only their exact
                    // key; explicit fuzzy slots can draw only accepted class
                    // members. Producer routes stay excluded.
                    long inputCap = input.extractCandidates() == null
                            ? stockOnlyCap(input.key())
                            : fuzzyFiringBound(input, true);
                    if (inputCap == Long.MAX_VALUE) {
                        continue;
                    }
                    if (input.extractCandidates() == null) {
                        long unitCost = satMul(input.amountPerCraft(), input.multiplier());
                        if (unitCost <= 0) {
                            continue;
                        }
                        inputCap /= unitCost;
                    }
                    bound = Math.min(bound, inputCap);
                    continue;
                }
                long inputCap;
                if (input.extractCandidates() != null) {
                    inputCap = fuzzyFiringBound(input, false);
                    if (inputCap == Long.MAX_VALUE) {
                        continue;
                    }
                } else {
                    inputCap = upperBound(input.key());
                    if (inputCap == Long.MAX_VALUE) {
                        continue;
                    }
                    long unitCost = satMul(input.amountPerCraft(), input.multiplier());
                    if (unitCost <= 0) {
                        continue;
                    }
                    inputCap = inputCap / unitCost;
                }
                bound = Math.min(bound, inputCap);
            }
            patternFirings.put(pattern, bound);
            return bound;
        }

        /** Exact stock cap for a pattern-less exact input. */
        private long stockOnlyCap(AEKey key) {
            if (graph.emittable().contains(key)) {
                return Long.MAX_VALUE;
            }
            return graph.stock().getOrDefault(key, 0L);
        }

        /**
         * Firing capacity of an explicit fuzzy input. Each accepted exact
         * variant contributes once at its best candidate template ratio, so
         * overlapping candidates cannot double-count or underestimate supply.
         */
        private long fuzzyFiringBound(GraphInput input, boolean stockOnly) {
            if (graph.emittable().contains(input.key())) {
                return Long.MAX_VALUE;
            }
            var variantFirings = new HashMap<AEKey, Long>();
            for (var candidate : input.extractCandidates()) {
                var members = graph.classVariants().get(candidate.key());
                if (members == null) {
                    addFuzzyVariantCapacity(input, candidate, candidate.key(), stockOnly, variantFirings);
                } else {
                    for (var member : members) {
                        addFuzzyVariantCapacity(input, candidate, member, stockOnly, variantFirings);
                    }
                }
            }
            long cap = 0;
            for (long variantCap : variantFirings.values()) {
                if (variantCap == Long.MAX_VALUE) {
                    return Long.MAX_VALUE;
                }
                cap = satAdd(cap, variantCap);
            }
            return cap;
        }

        private void addFuzzyVariantCapacity(GraphInput input, GraphInput.Candidate candidate,
                AEKey variant, boolean stockOnly, Map<AEKey, Long> variantFirings) {
            if (!input.validVariants().contains(variant)) {
                return;
            }
            long unitCost = satMul(candidate.amount(), input.multiplier());
            if (unitCost <= 0) {
                return;
            }
            long variantCap = stockOnly
                    ? graph.stock().getOrDefault(variant, 0L)
                    : upperBound(variant);
            long firings = variantCap == Long.MAX_VALUE ? Long.MAX_VALUE : variantCap / unitCost;
            variantFirings.merge(variant, firings, Math::max);
        }

        /**
         * Optimistic cycle count of one ring macro: bounded only by its net
         * inputs (seed catalysts are self-replenishing inside the ring and
         * never bound the optimistic count).
         */
        private long ringMacroFiringBound(RingMacro macro) {
            var memo = macroFirings.get(macro);
            if (memo != null) {
                return memo;
            }
            macroFirings.put(macro, SATURATION); // cycle guard

            long bound = SATURATION;
            for (var stack : macro.netInputs()) {
                long inputCap = upperBound(stack.getKey());
                if (inputCap == Long.MAX_VALUE) {
                    continue;
                }
                long unitCost = stack.getLongValue();
                if (unitCost <= 0) {
                    continue;
                }
                bound = Math.min(bound, inputCap / unitCost);
            }
            macroFirings.put(macro, bound);
            return bound;
        }

        private static long satAdd(long a, long b) {
            long result = a + b;
            if (((a ^ result) & (b ^ result)) < 0) {
                return SATURATION;
            }
            return Math.min(result, SATURATION);
        }

        private static long satMul(long a, long b) {
            if (a == 0 || b == 0) {
                return 0;
            }
            long result = a * b;
            if (result / b != a) {
                return SATURATION;
            }
            return Math.min(result, SATURATION);
        }
    }
}
