package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
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
     * product stays far below the 2^53 exact-double range. Only enforced when
     * the wide amount solver is off — the wide path tracks amounts exactly
     * beyond this range.
     */
    public static final long MAX_AMOUNT = 1L << 52;

    /*
     * #211：乐观上界域今天没有天花板了。
     *
     * 旧口径在这里放一个 SATURATION = Long.MAX_VALUE，供 satMul／satAdd／dividedCap／
     * firingBound 把"还能产多少"一路夹回 long。两处致命，这一刀都拆掉：
     *
     * 1. 夹出来的数被当成量。CapacityUpperBound#dividedCap 交回的 Long.MAX_VALUE 既可能是
     *    "这一键夹不住搜索"（emittable／每发量非正），也可能是"真值 4.33e20 被 toLong 截到
     *    天花板"。同一句 == Long.MAX_VALUE 判据（改前 :1473／:1482／:1488／:1520）只会读成
     *    前者，于是"真无限"哨兵与"夹到顶"共用同一个值，夹住被系统性地读成够。
     * 2. 夹在比较之前。上界一旦进了 long 域，"需求 vs 产能"就只能拿饱和读数比，
     *    需求 4e20 那一档永远比不出方向。
     *
     * 现在：产能上界这条链（firingBound／dividedCap／supplyWide／routeCapacity）全程
     * PlanAmount，一次都不窄化；"真无限"只剩 CapacityUpperBound#UNBOUNDED 一个哨兵值，
     * 读它只走 isUnbounded()（不低于哨兵位即为"夹不住"），它不参与任何一次量的比较。
     * 请求域那条 2^62 旧天花板把 6e18 静默排成 4.61e18 的反面教材仍由
     * CapacityBoundCeilingTest 盯着，宽域化不许把它放松。
     */

    /** Planning recursion ceiling — configurable fallback for very deep chains. */
    private static final int MAX_DEPTH = ChexsonsaeutilsCompatibilityConfig.graphPlanningMaxDepth();

    private LinearPlanner() {
    }

    /** 宽域根请求沿用同一站点、材料账和守恒闸门；窄量保持原有搜索路径。 */
    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, PlanAmount amount,
            boolean lexicographicRoutes, @Nullable GraphPlanningBudget budget) {
        if (amount.isFast()) return solve(graph, strategy, amount.fastValue(), lexicographicRoutes, budget);
        if (graph.hasDeclined()) return SolveResult.declined(graph.declineReason());
        if (amount.signum() <= 0 || !WideMath.wideEnabled()) return SolveResult.declined("wide request disabled or non-positive");
        var sites = new SiteTree(graph, lexicographicRoutes, budget, new CapacityUpperBound(graph));
        try {
            var full = sites.attempt(false, amount);
            if (full != null) return finishWide(graph, full, amount, false);
            if (strategy == CalculationStrategy.CRAFT_LESS) {
                var success = PlanAmount.ZERO;
                GraphPlanningState best = null;
                for (var increment = java.math.BigInteger.ONE.shiftLeft(amount.exactValue().bitLength() - 1);
                        increment.signum() > 0; increment = increment.shiftRight(1)) {
                    var probe = success.add(PlanAmount.of(increment));
                    if (probe.compareTo(amount) >= 0) continue;
                    var candidate = sites.attempt(false, probe);
                    if (candidate != null) { success = probe; best = candidate; }
                }
                if (best != null) return finishWide(graph, best, success, false);
            }
            return finishWide(graph, sites.attempt(true, amount), amount, true);
        } catch (PlanningOverflowException failure) {
            return SolveResult.declined(failure.getMessage());
        } catch (RecursionTooDeepException failure) {
            return SolveResult.declined("planning recursion deeper than " + MAX_DEPTH);
        }
    }

    private static SolveResult finishWide(CraftGraph graph, GraphPlanningState state, PlanAmount amount,
            boolean simulation) {
        if (state == null) return SolveResult.declined("wide request produced no ledger");
        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        String violation = ChexsonsaeutilsCompatibilityConfig.graphPlanningClosureGateEnabled()
                ? MaterialClosureGate.validate(state, graph, amount, shortage) : null;
        if (violation != null) return new SolveResult(state,
                git.chexson.chexsonsaeutils.bigint.Ae2Amounts.toLong(amount), true,
                violation, Map.copyOf(shortage), amount);
        return SolveResult.success(state, amount, simulation);
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
        // 产能上界读宽域真值，一次都不窄化：旧口径在这一步 toLong，把 4.33e20 夹成
        // Long.MAX_VALUE，后面"够不够"那一眼就永远比不出方向（#211）。
        // 普通无环单路线没有竞争分支，成功求解本身已证明供给足够。
        // 容量遍历延至失败后，避免冷启动成功订单先后扫描同一依赖链两遍。
        // null 只表示尚未计算；进入部分合成搜索前必须补齐精确的乐观上界。
        boolean fullFirst = !graph.multiplePaths() && graph.cycleKind() == CraftGraph.CycleKind.NONE;
        PlanAmount rootCap = fullFirst ? null : capacityBound.supplyWide(graph.target());
        // "真无限"走 isUnbounded 这一句标志判定、不进量的比较：UNBOUNDED 不是"有 2^256 枚"，
        // 是"这一键夹不住搜索"，拿它当量参加比较就是本仓铁律禁的"把哨兵当量"。
        boolean rootClampless = rootCap == null || CapacityUpperBound.isUnbounded(rootCap);
        if (rootCap != null && GraphDiagnostics.active()) {
            GraphDiagnostics.log("solve.upperBound target=%s rootCap=%s took %d us",
                    graph.target(), rootCap, (System.nanoTime() - upperBoundStart) / 1_000L);
        }
        var sites = new SiteTree(graph, lexicographicRoutes, budget, capacityBound);
        // 守恒闸门判缺料时逐键缺口的去向：判决照旧 DECLINE，这份数只在末级算法也没辙时
        // 用来把"材料不足"如实报给玩家（见 SolveResult#declinedShortage）。
        var shortage = new LinkedHashMap<AEKey, PlanAmount>();
        try {
            if (GraphDiagnostics.traceActive()) {
                // routeSelection 是全图 Tarjan SCC，只为这一行诊断服务（求解本身
                // 不消费它）。实机实测这一行要 40 ms——诊断关着也照样付费，
                // 因为调用点在 active() 判定之外。降到 trace 档按需计算。
                GraphDiagnostics.trace("solve.routeSelection acyclic=%s cumulative %d us",
                        graph.routeSelection().acyclic(), (System.nanoTime() - upperBoundStart) / 1_000L);
            }

            if (rootClampless || rootCap.compareTo(PlanAmount.of(requestedAmount)) >= 0) {
                long fullStart = System.nanoTime();
                var full = sites.attempt(false, requestedAmount);
                if (GraphDiagnostics.active()) {
                    GraphDiagnostics.log("solve.full %s took %d ms",
                            full != null ? "success" : "branch-failed",
                            (System.nanoTime() - fullStart) / 1_000_000L);
                }
                if (full != null) {
                    String violation = closureViolation(graph, full, requestedAmount, shortage);
                    if (violation != null) {
                        return closureDecline(violation, shortage, full, requestedAmount);
                    }
                    return SolveResult.success(full, requestedAmount, false);
                }
            }

            if (rootCap == null) {
                long delayedBoundStart = System.nanoTime();
                rootCap = capacityBound.supplyWide(graph.target());
                rootClampless = CapacityUpperBound.isUnbounded(rootCap);
                if (GraphDiagnostics.active()) {
                    GraphDiagnostics.log("solve.upperBound target=%s rootCap=%s took %d us after full failure",
                            graph.target(), rootCap, (System.nanoTime() - delayedBoundStart) / 1_000L);
                }
            }

            if (strategy == CalculationStrategy.CRAFT_LESS) {
                long successfulAmount = 0;
                GraphPlanningState successfulPlan = null;
                // 有限上界低于请求时先验证上界；若可行，它已经证明是最大产量，
                // 无需从请求的最高位反复建立账本与重走整条依赖链。
                // 上界可能因共享原料而过松，失败时仍由下方逐位搜索求精确解。
                if (!rootClampless && rootCap.signum() > 0
                        && rootCap.compareTo(PlanAmount.of(requestedAmount)) < 0) {
                    // 0 < rootCap < 正 long 请求，规范化不变式保证这里可精确读窄值。
                    long boundedAmount = rootCap.fastValue();
                    var boundedPlan = sites.attempt(false, boundedAmount);
                    if (boundedPlan != null) {
                        String violation = closureViolation(graph, boundedPlan, boundedAmount, shortage);
                        if (violation != null) {
                            return closureDecline(violation, shortage, boundedPlan, boundedAmount);
                        }
                        return SolveResult.success(boundedPlan, boundedAmount, false);
                    }
                }
                // 二分的上界比较也读宽域上界：这里以前是 long testAmount <= long rootCap，
                // 而 rootCap 是被 toLong 夹过的那一眼——4e20 的真产能读成 9.22e18，
                // 二分就永远只试到天花板以下那一截（CapacityBoundCeilingTest 钉的反面形状）。
                for (long increment = Long.highestOneBit(requestedAmount); increment > 0; increment /= 2) {
                    long testAmount = successfulAmount + increment;
                    if (testAmount < requestedAmount
                            && (rootClampless || rootCap.compareTo(PlanAmount.of(testAmount)) >= 0)) {
                        var attempt = sites.attempt(false, testAmount);
                        if (attempt != null) {
                            successfulAmount = testAmount;
                            successfulPlan = attempt;
                        }
                    }
                }
                if (successfulPlan != null) {
                    String violation = closureViolation(graph, successfulPlan, successfulAmount, shortage);
                    if (violation != null) {
                        return closureDecline(violation, shortage, successfulPlan, successfulAmount);
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
            String violation = closureViolation(graph, simulated, requestedAmount, shortage);
            if (violation != null) {
                return closureDecline(violation, shortage, simulated, requestedAmount);
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
    private static @Nullable String closureViolation(CraftGraph graph, GraphPlanningState state,
            long finalAmount, @Nullable Map<AEKey, PlanAmount> shortageSink) {
        if (!ChexsonsaeutilsCompatibilityConfig.graphPlanningClosureGateEnabled()) {
            return null;
        }
        long start = GraphDiagnostics.active() ? System.nanoTime() : 0L;
        String violation = MaterialClosureGate.validate(state, graph, finalAmount, shortageSink);
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("solve.closureGate took %d us decline=%s",
                    (System.nanoTime() - start) / 1_000L, violation);
        }
        return violation;
    }

    /**
     * 闸门判决的出口：逐键缺口拿到了就把那份账本一起带回去（判决仍是 DECLINE，
     * 只是不再把"还差多少"丢进异常），拿不到就照旧交回一条纯判决。
     */
    private static SolveResult closureDecline(String violation, Map<AEKey, PlanAmount> shortage,
            GraphPlanningState ledger, long finalAmount) {
        return shortage.isEmpty() ? SolveResult.declined(violation)
                : SolveResult.declinedShortage(violation, shortage, ledger, finalAmount);
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
        /** 首槽精确终端证明会失败、从而省去实际试探的批次数。 */
        private long prunedBatchCount;
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
        private final git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex;
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
        /** 失败后才建立的结构索引：全图能产出或返回的键不能被当作纯库存瓶颈。 */
        @Nullable
        private Set<AEKey> replenishableKeys;

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
                    ? new git.chexson.chexsonsaeutils.bigint.KeyIndex(256)
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
            return attempt(simulate, PlanAmount.of(amount));
        }

        @Nullable
        private boolean wideRootRequest;

        private GraphPlanningState attempt(boolean simulate, PlanAmount amount) {
            this.wideRootRequest = !amount.isFast();
            this.state = new GraphPlanningState(root.graph.stock(), root.graph.classVariants(),
                    this.journalArena, this.keyIndex,
                    this.denseKeyView == null ? null : this.denseKeyView.classMemberIdsByKeyId(),
                    root.graph.denseStock(), root.graph.supplyTruth());
            // 留种那条规则落在这里：要留在网络里的那一份不是可花库存（宽域真值账，#218）。
            state.holdBackRetainedSeedsWide(root.graph.retainedSeedsWide());
            this.simulate = simulate;
            this.traceSites = GraphDiagnostics.traceActive();
            this.budgetCheckCountdown = 1;
            this.requestCount = 0;
            this.branchFailureCount = 0;
            this.prunedBatchCount = 0;
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
                        "solve.attempt nodes=%d requests=%d branchFails=%d prunedBatches=%d missingSites=%d ringMacroFails=%d"
                                + " ringProbes=%d ringFires=%d ringMs=%d nodeBytes=%s stackBytes=%s",
                        materializedNodes, requestCount, branchFailureCount, prunedBatchCount, missingSiteCount,
                        ringMacroFailureCount, ringMacroProbeCount, ringMacroFireCount,
                        ringMacroNanos / 1_000_000L, this.state.nodeBytesAccum(), this.state.stackBytesAccum());
            }
            return this.state;
        }

        /**
         * 宽域需求口：终端、足量库存及已证明对齐的确定链一次结算。
         * 其余区域按件数轮长送入 long 内核，保留模糊竞争、副产物及环的跨轮供料顺序。
         * 每种路径均使用现有宽账和回滚日志，同一样板的总发数可以越出 long。
         *
         * <p><b>轮长由 {@link WideMath#roundUnits} 这一把尺决定，不再直接取
         * {@code Long.MAX_VALUE}</b>（#211）。旧口径切"一个 long 的单位数"，而下面那两路
         * 乘的都是<b>件数</b>：{@code :465/:528} 先做 {@code 单位数 × 每件数}。每发吃 100 枚金
         * 那一张样板，{@code 4e18} 个单位数装得进 {@code long}、走的是窄值那一条单轮快路，
         * 可 {@code 4e18 × 100 = 4e20} 枚金早就越出 {@code long} 了——一轮内部第一句
         * {@code wideMul} 就把它夹成 {@code Long.MAX_VALUE}，站点照这个夹过的读数排产，
         * 尾段 {@code 4e20 − 9.22e18} 无声消失且不报缺料。轮长必须让"轮内那一次乘算"
         * 落在 long 域内，饱和才不可能发生在轮内。
         *
         * <p>窄值（{@code 单位数 × 每件数} 本来就在 long 域内）逐字退回今天的那一条单轮
         * {@code long} 路：42 万站点规模下这是硬要求，常态一次分配都不多（用例
         * {@code WidePerSiteDemandTest#narrowSiteDemandStillTakesExactlyOneRound} 用 journal
         * 水位量这一条）。
         */
        private void request(Site site, PlanAmount requestedAmount) {
            long roundUnits = WideMath.roundUnits(site.amount);
            if (requestedAmount.isFast() && requestedAmount.fastValue() <= roundUnits) {
                if (requestedAmount.signum() > 0) {
                    request(site, requestedAmount.fastValue());
                }
                return;
            }
            // 精确终端或足量库存不需要生产试探，一次记入宽账。
            if (site.templates == null && (site.routes.isEmpty() || site.isEmittable()
                    || state.availableExactByIdWide(site.what, site.keyId())
                            .compareTo(WideMath.mul(site.amount, requestedAmount)) >= 0)) {
                requestTerminalWide(site, requestedAmount);
                return;
            }
            if (canBatchWide(site)) {
                requestLinearWide(site, requestedAmount);
                return;
            }
            var remaining = requestedAmount;
            while (true) {
                long chunk = remaining.isFast()
                        ? Math.min(remaining.fastValue(), roundUnits) : roundUnits;
                if (chunk <= 0L) {
                    return;
                }
                request(site, chunk);
                remaining = remaining.subtract(PlanAmount.of(chunk));
                if (remaining.signum() <= 0L) {
                    return;
                }
            }
        }

        /**
         * 仅合并无环、唯一生产路线、无副产或退料的确定链。
         * 宽根的余量写入原库存账；窄根保持原有分轮及共享输入竞争语义。
         * 只检查结构，不建立槽位节点，库存满足的后续节点仍不收节点费。
         */
        private boolean canBatchWide(Site site) {
            if (site.wideBatchable != null) {
                return site.wideBatchable;
            }
            if (site.templates != null || site.graph.cycleKind() != CraftGraph.CycleKind.NONE) {
                site.wideBatchable = false;
                return false;
            }
            if (site == root && wideRootRequest) {
                site.wideBatchable = batchableRootRoutes(site.what, site.amount, site.routes, 0);
                return site.wideBatchable;
            }
            var routes = site.routes;
            AEKey key = site.what;
            long amount = site.amount;
            for (int steps = 0; steps < MAX_DEPTH; steps++) {
                checkBudget();
                if (routes.isEmpty() || site.graph.emittable().contains(key)) {
                    site.wideBatchable = true;
                    return true;
                }
                if (routes.size() != 1 || !(routes.getFirst() instanceof RouteOrdering.Route.Pattern route)) {
                    break;
                }
                var pattern = route.pattern();
                if (pattern.inputs().size() != 1 || pattern.allOutputs().size() != 1
                        || !pattern.allOutputs().getFirst().what().equals(key)
                        || pattern.outputPerCraft() <= 0 || amount % pattern.outputPerCraft() != 0) {
                    break;
                }
                var input = pattern.inputs().getFirst();
                if (input.mode() != GraphInput.Mode.NORMAL || input.extractCandidates() != null
                        || input.remainderKey() != null || input.selfBlocked()
                        || input.amountPerCraft() <= 0 || input.multiplier() <= 0) {
                    break;
                }
                key = input.key();
                amount = input.amountPerCraft();
                routes = slotBundlesFor(pattern)[0].routes();
            }
            site.wideBatchable = false;
            return false;
        }

        /** 仅宽根请求允许整体向上取整；子站点保留既有整模板与单输入批量约束。 */
        private boolean batchableRootRoutes(AEKey key, long template, List<RouteOrdering.Route> routes, int depth) {
            checkBudget();
            if (depth >= MAX_DEPTH) return false;
            if (routes.isEmpty() || root.graph.emittable().contains(key)) return true;
            if (routes.size() != 1 || !(routes.getFirst() instanceof RouteOrdering.Route.Pattern route)) return false;
            var pattern = route.pattern();
            if (pattern.allOutputs().size() != 1 || !pattern.allOutputs().getFirst().what().equals(key)
                    || pattern.outputPerCraft() <= 0) return false;
            if (depth > 0 && (pattern.inputs().size() != 1 || template % pattern.outputPerCraft() != 0)) return false;
            var bundles = slotBundlesFor(pattern);
            for (int index = 0; index < pattern.inputs().size(); index++) {
                var input = pattern.inputs().get(index);
                if (input.mode() != GraphInput.Mode.NORMAL || input.extractCandidates() != null
                        || input.remainderKey() != null || input.selfBlocked()
                        || input.amountPerCraft() <= 0 || input.multiplier() <= 0
                        || !batchableRootRoutes(input.key(), input.amountPerCraft(), bundles[index].routes(), depth + 1))
                    return false;
            }
            return true;
        }

        /** 已证明无跨轮竞争的宽需求一次结算；仍使用原有宽账和回滚日志。 */
        private void requestLinearWide(Site site, PlanAmount requestedAmount) {
            if (++depth > MAX_DEPTH) {
                throw new RecursionTooDeepException();
            }
            try {
                checkBudget();
                state.addStackBytesWide(site.what, site.amount, requestedAmount);
                var remaining = requestedAmount.subtract(state.extractTemplatesByIdWide(
                        site.what, site.keyId(), site.amount, requestedAmount));
                if (remaining.signum() <= 0) {
                    return;
                }
                if (!site.slotsBuilt) {
                    site.countAllSlots();
                }
                requestCount++;
                var route = (RouteOrdering.Route.Pattern) site.routes.getFirst();
                var pattern = route.pattern();
                var demand = WideMath.mul(site.amount, remaining);
                var times = demand.ceilDiv(PlanAmount.of(pattern.outputPerCraft()));
                int mark = state.journal();
                try {
                    var slots = site.slotsFor(route);
                    for (int index = 0; index < pattern.inputs().size(); index++) {
                        var input = pattern.inputs().get(index);
                        request(slots[index], WideMath.mul(input.multiplier(), times));
                    }
                    state.insertByIdWide(site.what, site.keyId(), WideMath.mul(pattern.outputPerCraft(), times));
                    state.addCrafting(pattern.source(), times);
                    state.addBytes(times.exactValue().doubleValue());
                } catch (BranchFailure failure) {
                    branchFailureCount++;
                    state.undoTo(mark);
                    throw failure;
                }
                if (!state.extractByIdWide(site.what, site.keyId(), demand).equals(demand)) {
                    throw new IllegalStateException("Wide linear production did not fulfill its exact demand");
                }
            } finally {
                depth--;
            }
        }

        /** 终端与足量库存一次结算，不执行生产路线。 */
        private void requestTerminalWide(Site site, PlanAmount requestedAmount) {
            if (requestedAmount.signum() <= 0) {
                return;
            }
            if (++depth > MAX_DEPTH) {
                throw new RecursionTooDeepException();
            }
            try {
                checkBudget();
                state.addStackBytesWide(site.what, site.amount, requestedAmount);
                var extracted = state.extractTemplatesByIdWide(site.what, site.keyId(),
                        site.amount, requestedAmount);
                var remaining = requestedAmount.subtract(extracted);
                if (remaining.signum() <= 0) {
                    return;
                }
                var missing = WideMath.mul(site.amount, remaining);
                if (site.isEmittable()) {
                    state.emitItemsWide(site.what, missing);
                } else {
                    requestCount++;
                    if (traceSites) {
                        GraphDiagnostics.trace("request.wideTerminal site=%s remaining=%s simulate=%s",
                                site.what, missing, simulate);
                    }
                    if (simulate) {
                        missingSiteCount++;
                        state.addMissingWide(site.what, missing);
                    } else {
                        throw new BranchFailure();
                    }
                }
            } finally {
                depth--;
            }
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
                    requestedAmount -= extracted;
                    if (requestedAmount == 0) {
                        return;
                    }
                } else {
                    var templates = site.fuzzyTemplates();
                    // 整槽由首选候选的本体族承担：它能自产（或存量就够整槽）时不许跨族改道。
                    // 跨族拆份在整除处必掉余量，掉的份没人补产——实机 share=1199245817
                    // 对实耗 1199245818（3×399748606）少 1 枚玻璃，尾段 5 张任务僵死。
                    Object payerFamily = templates.length > 0
                            ? templates[0].classKey().getPrimaryKey() : null;
                    // "整族存量够不够这一槽"必须在宽域里比：familyStock 的读数会把同族
                    // 多张盘累过 long 的那一截夹成天花板，需求一侧又是精确值，
                    // 两个不同尺的数一比就把"配得平"读成"配不平"（#211 同族改动）。
                    // 注意这里比较的是累加域（PlanAmount）而非加数域：加数仍是 graph.stock()
                    // 那份网络 long 读数，与供给侧 supplyWide／沙盒种料读的 graph.supplyAmount
                    // 逐键宽域真值<b>不是同一把尺</b>——这一分歧已登记（见 familyStock javadoc，
                    // #219 K1 条目④判为未证 ⇒ 加数来源退回窄读数，只留口径不改代码）。
                    boolean payerOwnsSlot = payerFamily != null
                            && (site.familyProducible(payerFamily)
                            || site.familyStock(payerFamily)
                            .compareTo(WideMath.mul(site.amount, requestedAmount)) >= 0);
                    for (var template : templates) {
                        if (payerOwnsSlot
                                && !payerFamily.equals(template.classKey().getPrimaryKey())) {
                            continue;
                        }
                        for (int sourceId : template.sourceIds()) {
                            if (requestedAmount <= 0L) {
                                break;
                            }
                            AEKey source = keyIndex.keyOf(sourceId);
                            long units = state.extractTemplatesById(
                                    source, sourceId, template.amount(), requestedAmount);
                            if (units > 0L) {
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
                    // 供给侧那一腿记的是精确值：emitted 账直接进守恒闸门的 supply 侧，
                    // 拿饱和读数入账就是把 1e19 枚记成 9.22e18 枚，另一侧的需求是宽值，
                    // 两侧两个数即假缺口。窄值在 emitItemsWide 里逐字退回 long 版。
                    state.emitItemsWide(site.what, WideMath.mul(site.amount, requestedAmount));
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
                // ceil(需求 / 每发产量)：老算式 (a + b - 1) / b 在 a 顶到 Long.MAX_VALUE 时
                // 先回绕成负数，整张样板的发数就带着负号进了 crafts（实机 times=-1.3e16）。
                long times = WideMath.ceilDivExact(totalRequested, craftedPerPattern);
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
                PlanAmount planned = PlanAmount.of(totalRequested);
                double scale = (double) totalRequested / entryOutput;

                // requestRingDependencies: reserve the deterministic minimal
                // startup seed set proved by an executionRatio firing
                // schedule. Seeds are not scaled and are deduplicated through
                // the calculation-wide ringExtractions ledger.
                // #218：起步预留量全程 PlanAmount——预留账读宽域真值、可留量读供给真值，
                // 减法与比较都不许先把操作数窄化。旧写法拿回绕/饱和的 long 读数相减，
                // 顶格那一族"既不留种也不报缺"：预留 0、缺口被沙盒就地吸收。
                for (var seed : macro.startupSeeds().entrySet()) {
                    AEKey key = seed.getKey();
                    PlanAmount amountNeeded = seed.getValue();
                    PlanAmount already = state.ringExtractions().getAmount(key);
                    PlanAmount requiredAmount = amountNeeded.subtract(already).max(PlanAmount.ZERO);
                    if (requiredAmount.signum() <= 0) {
                        continue;
                    }
                    PlanAmount reservable = reservableStock(site.graph, key)
                            .subtract(already).max(PlanAmount.ZERO);
                    PlanAmount extracted = requiredAmount.min(reservable);
                    if (extracted.signum() > 0L) {
                        state.recordRingExtraction(key, extracted);
                        state.insert(key, extracted);
                        // The reservation enters the CPU inventory at submit
                        // time and stays there for the whole job — charge its
                        // bytes like any other extraction.
                        state.addStackBytesWide(key, 1L, extracted);
                        // 起步预留只是把这颗种提进 CPU（提交时按 usedItems 物理提取），
                        // 它不能变成主链可花的料：留种那份立刻扣回可花账，
                        // 否则后面任何同键需求都会把它吃掉，还种的那一发被聚合需求吸收。
                        state.holdBackRetainedSeed(key, extracted);
                    }
                    if (extracted.compareTo(requiredAmount) < 0) {
                        request(site.ringSlot(key), requiredAmount.subtract(extracted));
                    }
                }
                // 留种那一发：起步预留之后才算发射向量——判据是这一趟从网络起步预留里
                // 实际借走了几枚留种催化物（ringExtractions 随 journal 回滚），去重按 ringId
                // 记"这一次加成是否真记进发射向量"，不再拿入口键在预留账上有没有值当判据。
                // 父请求的满足量仍按 totalRequested 结算，不会多报完成。
                if (!state.hasRingSeedRepaid(macro.ringId())) {
                    PlanAmount repayment = macro.seedRepaymentCycles(site.graph,
                            state.ringExtractions().getAmount(macro.entryKey()));
                    if (repayment.signum() > 0L) {
                        state.markRingSeedRepaid(macro.ringId());
                        planned = macro.plannedWithSeedReturn(totalRequested, repayment);
                        // legacy double 链只在窄域等价：饱和读数与旧版逐位相同
                        scale = (double) Ae2Amounts.toLong(planned) / entryOutput;
                    }
                }
                // Ship the firing vector first: the per-member ceil rounding is
                // what the executor will actually run, so every material write
                // below has to be derived from it and not from a re-scaled
                // per-cycle aggregate.
                Map<IPatternDetails, PlanAmount> firings = new LinkedHashMap<>();
                for (var entry : macro.executionRatio().entrySet()) {
                    PlanAmount times = wide
                            ? WideMath.wideCeilMulDiv(entry.getValue(), planned, entryOutput)
                            : PlanAmount.of((long) Math.ceil(entry.getValue() * scale));
                    if (times.signum() > 0) {
                        firings.put(entry.getKey(), times);
                        state.addCrafting(entry.getKey(), times);
                    }
                }
                // unpackRingOperations, on the shipped vector: the ring's true
                // net per key is what the sandbox must absorb or feed. Booking
                // netInputs × ceil / netOutputs × floor instead leaves the
                // rounding gap unfunded — no usedItems entry, no missingItems
                // entry, and a key the tail of the job can never extract.
                for (var flow : macro.netFlowOf(firings).entrySet()) {
                    AEKey key = flow.getKey();
                    BigInteger net = flow.getValue();
                    int sign = net.signum();
                    if (sign < 0) {
                        // 净输入这一腿交宽域需求口：环宏的净流量是 BigInteger，交回天花板
                        // 就是把"还差 1e19 枚"排成"最多排 9.22e18 枚"，尾段静默少产。
                        // 判据范围：这一段 wide 走的是同一条 request(Site, PlanAmount) 分轮
                        // 语义，见 WideLegsSolverAlignmentTest#wideRequestFeedsBeyondLongSlotUnitsInsteadOfDeclining
                        // 与 LinearPlanner#request(Site, PlanAmount) :432——本轮判据覆盖 API 端点，
                        // 具体这条腿的环宏形状（BigInteger net.negate() > long）在测试域内无实机同型的环宏夹具。
                        var required = PlanAmount.of(net.negate());
                        if (required.signum() > 0) {
                            request(site.ringSlot(key), required);
                        }
                    } else if (sign > 0) {
                        state.insert(key, PlanAmount.of(net));
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
         * fewest network items, then fewest emitted, then fewest crafts, then
         * fewest bytes; the route index breaks ties deterministically) is
         * replayed deterministically onto the rolled-back ledger. Recursion
         * inside a candidate stays greedy, so the cost is
         * O(routes × subtree) per competition point, not exponential.
         *
         * <h3>度量整条进 {@code PlanAmount} 域（#219 K1 条目③）</h3>
         * 改前三本账取的是 {@code state.missingItems()}/{@code usedItems()}/
         * {@code emittedItems()}——那是宽账本交回 AE2 边界的<b>逐键饱和视图</b>
         * （{@code Ae2Amounts.toKeyCounter} 把 {@code 1.5e19} 那一键写成
         * {@code Long.MAX_VALUE}），{@link #totalOf} 再把这个读数累加、交出时第二次饱和。
         * 于是「这一条比那一条多用多少料」这一眼在两个数域里长得不一样：
         * 两条候选路真用量 {@code 2.1e19} 对 {@code 1.8e19}，夹完都是
         * {@code Long.MAX_VALUE} ⇒ 并列 ⇒ 判决顺延给后面的份数档 ⇒
         * <b>被顺延选中的那一条真被重放进计划</b>（{@code :878} 那句 replay），
         * 不是读数错、是解错。基线那一眼（{@code base*}）同一条夹子，
         * 所以「差值」还会把真增量读成 0。
         *
         * <p>现在三本账直接读 {@code WideKeyCounter#total()}（精确和，逐键不过天花板）、
         * 份数读 {@code WideCraftCounter} 的宽账、{@code base} 与候选值同一条算式相减。
         * 定序兜底仍是最后一档的 {@code index}，语义没动。
         *
         * <p>第一档（剩余需求）与最后一档（下标）本来就是 {@code long} 精确量，
         * 装箱进同一只数组只为让一次比较走一把尺。bytes 那一档保持 AE2 的 double
         * 累加器读数（{@code bytes()}）不动：它没有天花板，也不是本条的判据；
         * 换算法会在窄计划上改路由 tie-break，那是另一件事。
         */
        private long fireRoutesLexicographic(Site site, List<RouteOrdering.Route> routes,
                long totalRequested, boolean multiBranch) {
            GraphPlanningState saved = this.state;
            int mark = saved.journal();
            PlanAmount baseMissing = totalOf(saved.missing());
            PlanAmount baseUsed = totalOf(saved.used());
            PlanAmount baseEmitted = totalOf(saved.emitted());
            PlanAmount baseCrafts = totalCrafts(saved);
            double baseBytes = saved.bytes();
            long bestFulfilled = 0;
            PlanAmount[] bestMetric = null;
            long bestLeftover = 0L;
            RouteOrdering.Route bestRoute = null;
            for (int index = 0; index < routes.size(); index++) {
                var route = routes.get(index);
                if (bestMetric != null) {
                    // X1a — optimistic capacity pruning: even firing the route
                    // to its memoized upper bound cannot beat the incumbent on
                    // the leading metric (leftover), so the lexically later
                    // components could never come into play. Strictly greater
                    // keeps ties alive for the remaining metric components.
                    // 比较在宽域里做，且真无限那一档先看标志再决定剪不剪：
                    // "夹不住"不是"有 2^256 枚"，拿哨兵参加量的比较就是本仓铁律禁的那一手。
                    PlanAmount routeCap = routeCapacity(route);
                    if (!CapacityUpperBound.isUnbounded(routeCap)
                            && routeCap.compareTo(PlanAmount.of(totalRequested - bestLeftover)) < 0) {
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
                PlanAmount[] metric = {
                        PlanAmount.of(totalRequested - fulfilled),
                        totalOf(saved.missing()).subtract(baseMissing),
                        totalOf(saved.used()).subtract(baseUsed),
                        totalOf(saved.emitted()).subtract(baseEmitted),
                        totalCrafts(saved).subtract(baseCrafts),
                        // bytes 那一档：AE2 double 累加器的读数，见方法抬头最后一段
                        PlanAmount.of((long) Math.ceil(saved.bytes() - baseBytes)),
                        PlanAmount.of(index)
                };
                if (bestMetric == null || lexicographicCompare(metric, bestMetric) < 0) {
                    bestMetric = metric;
                    bestRoute = route;
                    bestFulfilled = fulfilled;
                    bestLeftover = totalRequested - fulfilled;
                }
                saved.undoTo(mark);
            }
            if (bestRoute == null) {
                return 0;
            }
            // The firing is deterministic on the rolled-back ledger, so replaying
            // the winning trial reproduces its writes. A replay failure (budget
            // only — the trial itself succeeded) reports nothing fulfilled
            // instead of taking a phantom credit.
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
         *
         * <p>交回<b>宽域</b>量（#211）：此前这里是 {@code satMul(firingBound, 每发产出)}，
         * 两个因子都被夹在 {@code long} 天花板内，乘完再夹一次——真产能 {@code 4e20} 枚读成
         * {@code 9.22e18} 枚，剪枝方向就从"剪不掉"变成"错剪"。上界是过近似，宽了只少剪枝、
         * 不会误杀可行解；窄了就是把夹住的读数当量。
         */
        private PlanAmount routeCapacity(RouteOrdering.Route route) {
            if (route instanceof RouteOrdering.Route.Ring ring) {
                var macro = ring.macro();
                return capacityBound.ringMacroFiringBound(macro)
                        .multiply(PlanAmount.of(macro.entryOutputPerCycle()));
            }
            var pattern = ((RouteOrdering.Route.Pattern) route).pattern();
            return capacityBound.firingBound(pattern)
                    .multiply(PlanAmount.of(pattern.outputPerCraft()));
        }

        /**
         * 一本 {@code WideKeyCounter} 的精确总和（#219 K1 条目③）。
         *
         * <p>改前签名收的是 {@code KeyCounter}——即 {@code Ae2Amounts.toKeyCounter} 交出的
         * 那份<b>逐键饱和视图</b>，把 {@code 1.5e19} 那一枚键写成天花板，再在交出总和时
         * 饱和第二次。字典序度量的差值因此读不到真增量（顶格时读成 0）、也分不出两条候选路
         * 谁用得少。这里改读账本自己的 {@link WideKeyCounter#total()}：逐键过 BigInteger
         * 侧表、总和永不回绕，也不再有天花板。
         */
        private static PlanAmount totalOf(WideKeyCounter counter) {
            return counter.total();
        }

        private static PlanAmount totalCrafts(GraphPlanningState state) {
            // 份数读宽账：单张样板跨站点累加可以超 long，总和在 PlanAmount 里精确累加。
            // 改前只在交出字典序度量那一句 toLong 饱和（宁多算不发少算），于是两笔都
            // 越界的候选被夹成同一个读数、当场并列——本条把那一次窄化也去掉了。
            var crafts = state.craftsWide();
            PlanAmount total = PlanAmount.ZERO;
            for (var source : crafts.keys()) {
                total = total.add(crafts.getAmount(source));
            }
            return total;
        }

        private static int lexicographicCompare(PlanAmount[] left, PlanAmount[] right) {
            for (int i = 0; i < left.length; i++) {
                int comparison = left[i].compareTo(right[i]);
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
            // 同 firePatternRoute：ceil 的那一眼不许先回绕成负批次
            long batch = WideMath.ceilDivExact(totalRequested, perCraft);
            Site terminal = null;
            PlanAmount terminalCost = null;
            FirstInputPrefix prefix = null;
            List<PrefixLimit> terminalLimits = List.of();
            boolean terminalChecked = false;
            while (totalRequested > 0) {
                checkBudget();
                if (terminal != null || prefix != null || !terminalLimits.isEmpty()) {
                    var cap = terminal != null
                            ? state.availableExactByIdWide(terminal.what, terminal.keyId()).divide(terminalCost)
                            : prefix != null ? prefix.firingCapacity(state) : PlanAmount.of(batch);
                    for (var limit : terminalLimits) {
                        cap = cap.min(state.availableExactByIdWide(limit.site().what, limit.site().keyId())
                                .divide(limit.cost()));
                    }
                    // 首槽界来自已证明的加工前缀；后续库存槽排除了全图产出和留物补料。
                    // 只跳过原减半序列中必定在该槽失败的批次，成功批量、路线和节点费均不变。
                    // 每次成功后重新读 working，避免把已消耗库存或后来新增的副产物记错。
                    // cap 非负且规范化；宽值必大于任意正 long 批量，直接保留。
                    // 窄值比较不为每次减半构造数量对象。
                    while (batch > 0 && cap.isFast() && batch > cap.fastValue()) {
                        batch /= 2L;
                        prunedBatchCount++;
                    }
                    if (batch == 0) {
                        break;
                    }
                }
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
                    // 只在实际失败后准备剪枝；一次成功的普通路线不额外读取供给。
                    if (!terminalChecked) {
                        terminalChecked = true;
                        if (!simulate && !pattern.inputs().isEmpty()) {
                            terminalLimits = stockOnlyLimits(site, route);
                            var first = pattern.inputs().getFirst();
                            if (first.mode() == GraphInput.Mode.NORMAL && first.extractCandidates() == null
                                    && first.amountPerCraft() > 0L && first.multiplier() > 0L) {
                                var slot = site.slotsFor(route)[0];
                                if (slot.routes.isEmpty() && !slot.isEmittable()) {
                                    terminal = slot;
                                    terminalCost = WideMath.mul(first.amountPerCraft(), first.multiplier());
                                } else {
                                    prefix = firstInputPrefix(slot,
                                            WideMath.mul(first.amountPerCraft(), first.multiplier()));
                                }
                            }
                        }
                    }
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
                    long maxBatch = WideMath.ceilDivExact(totalRequested, perCraft);
                    // 加倍只许加到"这一轮真需要几发"为止：旧写法先把 batch*2 夹进
                    // Long.MAX_VALUE 再 min 回 maxBatch，等于绕着天花板算了一圈——
                    // 结果今天虽同，但那个天花板读起来像个量。按 maxBatch 自身判够不够，
                    // 一次乘法都不许多做，也就没有回绕。
                    long doubled = batch > maxBatch / 2L ? maxBatch : batch * 2L;
                    batch = doubled;
                } else {
                    state.undoTo(mark);
                    break;
                }
            }
            return produced;
        }

        /**
         * 多输入失败后检查后续纯库存槽，避免先重算昂贵子树再在同一终端失败。
         * 全图产出、留物或发射能补充的键均排除，实际请求顺序不变。
         */
        private List<PrefixLimit> stockOnlyLimits(Site site, RouteOrdering.Route.Pattern route) {
            var inputs = route.pattern().inputs();
            if (inputs.size() < 2 || site.graph.cycleKind() != CraftGraph.CycleKind.NONE) {
                return List.of();
            }
            if (replenishableKeys == null) {
                var keys = new HashSet<AEKey>();
                for (var pattern : site.graph.patternsBySource().values()) {
                    checkBudget();
                    for (var output : pattern.allOutputs()) {
                        keys.add(output.what());
                    }
                    for (var input : pattern.inputs()) {
                        if (input.remainderKey() != null) {
                            keys.add(input.remainderKey());
                        }
                    }
                }
                replenishableKeys = keys;
            }
            var limits = new ArrayList<PrefixLimit>();
            var slots = site.slotsFor(route);
            for (int x = 1; x < inputs.size(); x++) {
                var input = inputs.get(x);
                var slot = slots[x];
                if (input.mode() == GraphInput.Mode.NORMAL && input.extractCandidates() == null
                        && input.remainderKey() == null && input.amountPerCraft() > 0
                        && input.multiplier() > 0 && slot.routes.isEmpty() && !slot.isEmittable()
                        && !replenishableKeys.contains(input.key())) {
                    limits.add(new PrefixLimit(slot, WideMath.mul(input.amountPerCraft(), input.multiplier())));
                }
            }
            return List.copyOf(limits);
        }

        /**
         * 失败后识别已访问的确定加工前缀：首槽继续向下，其他槽只允许普通终端或发射源。
         * 只读已建槽位，避免为剪枝展开新节点而改变失败路径的字节数计费。
         * 多路线、模糊输入、环或不完整的已访问前缀不能证明容量，交回 null。
         */
        private @Nullable FirstInputPrefix firstInputPrefix(Site first, PlanAmount firstCost) {
            if (first.graph.cycleKind() != CraftGraph.CycleKind.NONE) {
                return null;
            }
            var steps = new ArrayList<PrefixStep>();
            Site current = first;
            while (steps.size() < MAX_DEPTH) {
                checkBudget();
                if (current.templates != null || current.isEmittable()) {
                    return null;
                }
                if (current.routes.isEmpty()) {
                    return new FirstInputPrefix(List.copyOf(steps), current, firstCost);
                }
                if (current.routes.size() != 1
                        || !(current.routes.getFirst() instanceof RouteOrdering.Route.Pattern route)) {
                    return null;
                }
                var pattern = route.pattern();
                if (pattern.inputs().isEmpty() || pattern.allOutputs().size() != 1
                        || pattern.outputPerCraft() <= 0L
                        || !pattern.allOutputs().getFirst().what().equals(current.what)) {
                    return null;
                }
                var input = pattern.inputs().getFirst();
                if (input.mode() != GraphInput.Mode.NORMAL || input.extractCandidates() != null
                        || input.selfBlocked() || input.remainderKey() != null || input.amountPerCraft() <= 0L
                        || input.multiplier() <= 0L) {
                    return null;
                }
                if (current.patternSlots == null || current.patternSlots[route.slotIndex()] == null) {
                    return null;
                }
                var slots = current.patternSlots[route.slotIndex()];
                var limits = new ArrayList<PrefixLimit>();
                for (int x = 1; x < pattern.inputs().size(); x++) {
                    var side = pattern.inputs().get(x);
                    var slot = slots[x];
                    // 后续槽不能生产或返回物料，否则宽需求的后续轮次会改变首槽供给。
                    if (side.mode() != GraphInput.Mode.NORMAL || side.extractCandidates() != null
                            || side.selfBlocked() || side.remainderKey() != null || side.amountPerCraft() <= 0
                            || side.multiplier() <= 0
                            || (!slot.routes.isEmpty() && !slot.isEmittable())) {
                        return null;
                    }
                    if (!slot.isEmittable()) {
                        limits.add(new PrefixLimit(slot,
                                WideMath.mul(side.amountPerCraft(), side.multiplier())));
                    }
                }
                steps.add(new PrefixStep(current, WideMath.mul(input.amountPerCraft(), input.multiplier()),
                        PlanAmount.of(pattern.outputPerCraft()), List.copyOf(limits)));
                current = slots[0];
            }
            return null;
        }

        /** 一层确定生产依赖：站点用于读取中间库存，用料及产出用于向上折算供给。 */
        private record PrefixStep(Site site, PlanAmount inputCost, PlanAmount outputPerCraft,
                List<PrefixLimit> limits) {
        }

        /** 额外普通终端槽的容量约束；只读当前库存，忽略共享竞争得到乐观上界。 */
        private record PrefixLimit(Site site, PlanAmount cost) {
        }

        /** 已证实的确定加工链，数量不缓存；每个试探都重新读取当前库存。 */
        private record FirstInputPrefix(List<PrefixStep> steps, Site terminal, PlanAmount firstCost) {
            /**
             * 从叶子向上逐层整除，再加当前中间库存，最后除以外层路线每发用料。
             * 保留每层取整，不能把链压成总倍率；宽值亦不经过 long 窄化。
             * 整模板库存的零头可令此界偏松，不会错误地剪掉可行批量。
             */
            private PlanAmount firingCapacity(GraphPlanningState state) {
                var supply = state.availableExactByIdWide(terminal.what, terminal.keyId());
                for (int index = steps.size() - 1; index >= 0; index--) {
                    var step = steps.get(index);
                    var firings = supply.divide(step.inputCost());
                    for (var limit : step.limits()) {
                        firings = firings.min(state.availableExactByIdWide(
                                limit.site().what, limit.site().keyId()).divide(limit.cost()));
                    }
                    var produced = firings.multiply(step.outputPerCraft());
                    supply = state.availableExactByIdWide(step.site().what, step.site().keyId()).add(produced);
                }
                return supply.divide(firstCost);
            }
        }

        /**
         * 起步预留可留的<b>供给真值</b>（宽域）：无限盘溢出键在 {@code stock} 的逐键
         * {@code long} 读数里永远顶格或回绕，留种判据读 {@code supplyAmount} 才分得开
         * "真只有这么多"与"被 long 截断"（#218，与 {@code supplyAmount} 同一把尺）。
         */
        private static PlanAmount reservableStock(CraftGraph graph, AEKey key) {
            return graph.supplyAmount(key);
        }

        /**
         * 单站点用料 = 槽倍率 × 发数，这一乘连同轮内那句「单位数 × 每件数」都可能越出
         * {@code long}。
         *
         * <p>口径各只有一处：越不越域问 {@link WideMath#mulFitsLong}，轮长问
         * {@link WideMath#roundUnits}，两条算式都在 {@code crafting/numeric}，这里不另立判据。
         * <b>不许</b>再拿 {@code wideMul} 交回的 {@code Long.MAX_VALUE} 当"装不下"的判别哨
         * （改前 {@code :1010}/{@code :1037} 正是这一型）：把哨兵当量，真值恰好等于天花板
         * 的那一乘会被误送进宽口，而越出去的那一乘又会被下游读成"有那么多"。
         *
         * <p>窄值（单位数与件数都还在 {@code long} 域内）逐字退回今天那一条单轮 {@code long}
         * 路、零额外分配；只要件数会越域就交宽域需求口按轮长分轮，让每一轮的轮内乘算都落在
         * {@code long} 域内——饱和没有发生的地方，也就没有静默少产。
         */
        private void requestSlotUnits(Site slot, long multiplier, long times) {
            if (WideMath.mulFitsLong(multiplier, times)) {
                long units = multiplier * times;
                if (units <= WideMath.roundUnits(slot.amount)) {
                    request(slot, units);
                    return;
                }
            }
            request(slot, WideMath.mul(multiplier, times));
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
                    case NORMAL, FINITE_USE -> {
                        requestSlotUnits(slots[x], input.multiplier(), times);
                    }
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
                        // 留物入账走 WideMath.mul 精确积，判"要不要换宽口"用 mulFitsLong，
                        // 不许拿 wideMul 的饱和读数当判别哨：9223372036854775807 一旦进了
                        // 入账口就是把 1e19 枚留物记成 9.22e18 枚（本仓铁律：哨兵不是量）。
                        if (WideMath.mulFitsLong(input.multiplier(), times)) {
                            state.insert(input.remainderKey(), input.multiplier() * times);
                        } else {
                            state.insert(input.remainderKey(), WideMath.mul(input.multiplier(), times));
                        }
                        state.addStackBytes(input.remainderKey(),
                                WideMath.wideMul(input.multiplier(), times), 1);
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
                    // 规模下是百万级分配）。溢出才走宽域入账口。
                    // 溢出<b>不许</b>交回天花板：working 账是守恒闸门的供给侧，也是父站点
                    // 伸手要料的地方——1e19 枚记成 9.22e18 枚，父站点当场少一发（实机
                    // 形状：一发产 10 枚、发 1e18 次的那一张样板）。
                    long fast = fastMul(out.amount(), times);
                    if (fast != Long.MIN_VALUE) {
                        state.insertById(out.what(), outId, fast);
                    } else {
                        state.insertByIdWide(out.what(), outId, WideMath.mul(out.amount(), times));
                    }
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
                state.insertByIdWide(out.what(), outId, amount);
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
        /** 宽需求才计算的结构证明；库存数量不缓存，跨 attempt 仅复用不可变结构。 */
        @Nullable
        private Boolean wideBatchable;
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

        private record FuzzyTemplate(AEKey classKey, int classKeyId, int[] sourceIds, long amount) {
        }

        private Site(@Nullable SiteTree tree, AEKey what, long amount, CraftGraph graph,
                boolean isRoot, boolean isRingDependency, boolean patternLeaf,
                @Nullable List<GraphInput.Candidate> templates, @Nullable Set<AEKey> validVariants,
                RouteOrdering.RouteBundle bundle) {
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

        /** 本体族级缓存：这份图里哪些物品本体族能自产（有生产者或可发射）、族内存量合计。 */
        @Nullable
        private java.util.Set<Object> producibleFamiliesCache;
        @Nullable
        private java.util.Map<Object, PlanAmount> stockByFamilyCache;

        private boolean familyProducible(Object family) {
            var cached = producibleFamiliesCache;
            if (cached == null) {
                cached = new java.util.HashSet<>();
                for (var entry : graph.producersByOutput().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        cached.add(entry.getKey().getPrimaryKey());
                    }
                }
                for (var key : graph.emittable()) {
                    if (key != null) {
                        cached.add(key.getPrimaryKey());
                    }
                }
                producibleFamiliesCache = cached;
            }
            return cached.contains(family);
        }

        /**
         * 族内存量合计（<b>累加域为宽</b>，#211）：逐键加数取 {@code graph.stock()} 那份网络
         * {@code long} 读数，累加走 {@code PlanAmount}。
         *
         * <h3>两把尺的分歧（已登记事实，#219 K1 条目④判为未证）</h3>
         * 本方法的<b>加数</b>读的是网络聚合 {@code long} 视图：同一本体族里那一枚被 AE2 普通
         * {@code long} 加法<b>回绕成负</b>的键（{@code <= 0L}）被整枚跳过、顶在
         * {@code Long.MAX_VALUE} 的那一枚交回的是<b>哨兵</b>而非量。而供给侧
         * （{@code supplyWide}／{@code reservableStock}）与沙盒种料读的是
         * {@link CraftGraph#supplyAmount}（逐键<b>宽域真值</b>）。同一族、同一个
         * {@code payerOwnsSlot} 判据，两侧取数<b>不在同一数域</b>——只有当族里存在
         * "聚合回绕成负／顶格、而逐存储扫描能捞回更大真值"的那一枚键时才会分歧；
         * 普通键（聚合读数可信）{@code supplyOf} 逐字退回那份 {@code long} 读数，两把尺同值。
         *
         * <p>#219 K1 曾把加数换成 {@code supplyAmount} 求"同尺对齐"，但<b>没有任何用例打得到它</b>：
         * {@code familyStock} 只喂给 {@code payerOwnsSlot} 这一个"要不要整槽由首选族承担"的
         * 收紧开关，而 {@code templates[0]} 正是首选族、恒在排水循环里<b>最先</b>被
         * {@code extractTemplatesById} 触碰——该提取读的是从 {@code supplyAmount} 种下的沙盒
         * 真值。于是"族真值够付整槽"（新尺 {@code payerOwnsSlot=真}）时首选族当场付满、
         * 与旧尺（放行跨族改道、但首选族仍先付满）产出<b>同一份计划</b>；"族真值不够"
         * （因真值恒 ≥ 窄读数，旧尺也判不够）两尺同为假、照旧改道。<b>不存在</b>旧尺红／
         * 新尺绿的排产差分夹具——已用回绕成负的付款族现场探针实测：加数换窄／换宽两版
         * {@code usedX=5e18、usedY=0、final=5e18} 逐字相同。按作者裁决规则②：判为未证 ⇒
         * <b>撤回加数来源改动</b>、退回 {@code f0c30b6} 那份实现，只把上面这把分歧留成口径。
         */
        private PlanAmount familyStock(Object family) {
            var cached = stockByFamilyCache;
            if (cached == null) {
                cached = new java.util.HashMap<>();
                for (var entry : graph.stock().entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                        continue;
                    }
                    var primary = entry.getKey().getPrimaryKey();
                    PlanAmount held = cached.getOrDefault(primary, PlanAmount.ZERO);
                    cached.put(primary, held.add(PlanAmount.of(entry.getValue())));
                }
                stockByFamilyCache = cached;
            }
            return cached.getOrDefault(family, PlanAmount.ZERO);
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
                        input.validVariants(), bundles[x]);
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
        /**
         * "这一键夹不住搜索"的<b>真无限</b>哨兵：emittable、每发量非正、回环 in-flight
         * 的乐观标记三处共用这一个值。
         *
         * <p>它必须与"夹到顶"是<b>两个值两句话</b>（#211）。改前它是
         * {@code PlanAmount.of(Long.MAX_VALUE)}，而同一个 {@code Long.MAX_VALUE} 正是
         * {@code satMul}/{@code satAdd}/{@code Ae2Amounts.toLong} 把真值夹出来的那个天花板——
         * 于是一条 {@code dividedCap} 交回的 {@code 9.22e18} 有两种毫不相干的读法：
         * <ul>
         * <li>真无限（emittable／每发量非正）——{@code :1488} 那一支；</li>
         * <li>真值被截断（{@code Ae2Amounts.toLong} 把 {@code 4.33e20} 夹到顶）——同一个
         * {@code :1491} 那一支。</li>
         * </ul>
         * 而读它的四句判据（改前 {@code :1473}/{@code :1482}/{@code :1488}/{@code :1520}
         * 的 {@code == Long.MAX_VALUE}）只认第一种，所以"夹住"被系统性地读成"够"，
         * 上界凭空缩回 {@code long} 域，尾段静默少产。
         *
         * <p>现在：这条链全程宽域、不再有任何一次 {@code toLong}，所以"夹到顶"这一档在产能
         * 上界域里<b>根本不存在了</b>；剩下的唯一含义是真无限。取 {@code 2^256} 是故意的——
         * 它比网络里任何一次真实乘算（库存 ≤ {@code ~1e38} 量级、再乘每发产出）都高若干数量级，
         * 因此不会被一份真值撞上；读它只用 {@link #isUnbounded}，不许拿它当量参加"够不够"的比较。
         */
        private static final PlanAmount UNBOUNDED = PlanAmount.of(BigInteger.ONE.shiftLeft(256));

        private final CraftGraph graph;
        private final Map<AEKey, PlanAmount> keyCaps = new HashMap<>();
        // 按身份查表：GraphPattern / RingMacro 的 record 深哈希会走完整个输入
        // 与 KeyCounter 结构，而这里查的永远是编译期建好的同一实例。
        private final Map<GraphPattern, PlanAmount> patternFirings = new java.util.IdentityHashMap<>();
        private final Map<RingMacro, PlanAmount> macroFirings = new java.util.IdentityHashMap<>();

        private CapacityUpperBound(CraftGraph graph) {
            this.graph = graph;
        }

        /**
         * 这个上界表示"夹不住搜索"（真无限，或乘算已越过哨兵位）。它是<b>标志</b>不是量：
         * 调用方读到真，只能"跳过这次剪枝"，绝不许再拿它参加供需比较。
         */
        static boolean isUnbounded(PlanAmount cap) {
            return cap.compareTo(UNBOUNDED) >= 0;
        }

        /**
         * 发数上界 × 每发产出，交回件数上界。真无限那一档乘完<b>还是真无限</b>——
         * 不许把哨兵乘成一个"看着像量"的新值（那等于给"夹不住"造一份可比较的假库存）。
         */
        static PlanAmount scaleByOutput(PlanAmount firingCap, long outputPerCraft) {
            if (isUnbounded(firingCap)) {
                return UNBOUNDED;
            }
            return firingCap.multiply(PlanAmount.of(outputPerCraft));
        }

        /**
         * 该键的<b>宽域</b>供给上界：真值库存 + 各生产途径（每发产出 × 发数上界）。
         *
         * <p>库存那一眼必须读 {@link CraftGraph#supplyAmount}：网络那份 {@code long} 读数在
         * 无限盘溢出键上恒等于 {@code Long.MAX_VALUE}，而真值可以更大——放大出来的用量
         * （每发 10 枚 × 1e18 发 = 1e19 枚）就是靠这一截才配得平。
         *
         * <p>过近似性质不变：{@code emittable} 与回环中的 in-flight 标记都取
         * {@link #UNBOUNDED}（宁大不小），所以"上界低于请求即证明不可行"这条剪枝方向
         * 永远不会反。交回值<b>不再有 long 天花板</b>：唯一的窄化点在调用方交出 AE2 边界处。
         */
        private PlanAmount supplyWide(AEKey key) {
            if (graph.emittable().contains(key)) {
                return UNBOUNDED;
            }
            var memo = keyCaps.get(key);
            if (memo != null) {
                return memo;
            }
            // Optimistic in-flight marker: re-entering a key mid-computation
            // returns UNBOUNDED so the bound can only over-approximate —
            // "below request proves failure" stays valid on cyclic walks.
            keyCaps.put(key, UNBOUNDED);

            var cap = graph.supplyAmount(key);
            var producers = graph.producersByOutput().get(key);
            if (producers != null) {
                for (var producer : producers) {
                    cap = cap.add(scaleByOutput(firingBound(producer), producer.outputPerCraft()));
                }
            }
            for (var macro : graph.ringMacrosByEntry().getOrDefault(key, List.of())) {
                cap = cap.add(scaleByOutput(ringMacroFiringBound(macro), macro.entryOutputPerCycle()));
            }
            List<CraftGraph.ByproductSupply> supplies = graph.byproductSupply().get(key);
            if (supplies != null) {
                for (var supply : supplies) {
                    cap = cap.add(scaleByOutput(firingBound(supply.pattern()), supply.amountPerCraft()));
                }
            }
            List<CraftGraph.RemainderSupply> remainders = graph.remainderSupply().get(key);
            if (remainders != null) {
                for (var supply : remainders) {
                    cap = cap.add(scaleByOutput(firingBound(supply.pattern()), supply.amountPerCraft()));
                }
            }
            cap = cap.max(PlanAmount.ZERO);
            keyCaps.put(key, cap);
            return cap;
        }

        /**
         * 只算库存（不含任何生产途径）的宽域上界：selfBlocked 叶子与模糊槽的
         * {@code stockOnly} 一路按这一眼取料。
         */
        private PlanAmount stockOnlyWide(AEKey key) {
            return graph.emittable().contains(key) ? UNBOUNDED : graph.supplyAmount(key);
        }

        /**
         * 供给上界除以"每发要吃几枚"得到的发数上界：<b>除法在宽域里做，商也不窄化</b>。
         *
         * <p>两道旧病都在这一句里（改前 {@code :1439}/{@code :1487}）：
         * <ul>
         * <li>先 {@code toLong} 再整除，等于把 {@code 1.5e19} 枚真值截成
         * {@code Long.MAX_VALUE} 之后再除以 10——放大出来的那一截直接没了；</li>
         * <li>交回 {@code long} 就必须给"夹不住"和"夹到顶"共用同一个 {@code Long.MAX_VALUE}，
         * 调用方分不清（见 {@link #UNBOUNDED} 抬头）。改宽域后后一档不存在了。</li>
         * </ul>
         *
         * <p>每发量非正或上界本身真无限 ⇒ 交回 {@link #UNBOUNDED} 标志，调用方用
         * {@link #isUnbounded} 读，据此跳过这一眼剪枝。
         */
        private static PlanAmount dividedCap(PlanAmount supply, PlanAmount unitCost) {
            if (unitCost.signum() <= 0 || isUnbounded(supply)) {
                return UNBOUNDED;
            }
            return supply.divide(unitCost);
        }

        private PlanAmount dividedCap(PlanAmount supply, long unitCost) {
            return dividedCap(supply, PlanAmount.of(unitCost));
        }

        /**
         * 一张样板的发数上界（宽域）。每格输入给一个"这一格最多付得起几发"的上界，
         * 取最小；夹不住的那一格（真无限）不参与最小化。
         */
        private PlanAmount firingBound(GraphPattern pattern) {
            var memo = patternFirings.get(pattern);
            if (memo != null) {
                return memo;
            }
            patternFirings.put(pattern, UNBOUNDED); // cycle guard

            PlanAmount bound = UNBOUNDED;
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
                    PlanAmount inputCap;
                    if (input.extractCandidates() == null) {
                        inputCap = dividedCap(stockOnlyWide(input.key()),
                                unitCost(input.amountPerCraft(), input.multiplier()));
                    } else {
                        inputCap = fuzzyFiringBound(input, true);
                    }
                    if (isUnbounded(inputCap)) {
                        continue;
                    }
                    bound = bound.min(inputCap);
                    continue;
                }
                PlanAmount inputCap;
                if (input.extractCandidates() != null) {
                    inputCap = fuzzyFiringBound(input, false);
                    if (isUnbounded(inputCap)) {
                        continue;
                    }
                } else {
                    inputCap = dividedCap(supplyWide(input.key()),
                            unitCost(input.amountPerCraft(), input.multiplier()));
                    if (isUnbounded(inputCap)) {
                        continue;
                    }
                }
                bound = bound.min(inputCap);
            }
            patternFirings.put(pattern, bound);
            return bound;
        }

        /**
         * 每发折成的单位数：{@code 每发件数 × 槽倍率}，宽域精确积。
         *
         * <p>改前是 {@code satMul}——recipe 里的这两个数确实小，但"饱和后交回天花板"这一手
         * 让它和"真无限"共用一个读数，而下游 {@code unitCost <= 0} 那一句判据读的正是这个
         * 天花板。精确乘没有这个歧义，也不比它贵。
         */
        private static PlanAmount unitCost(long amountPerCraft, long multiplier) {
            return WideMath.mul(amountPerCraft, multiplier);
        }

        /**
         * Firing capacity of an explicit fuzzy input. Each accepted exact
         * variant contributes once at its best candidate template ratio, so
         * overlapping candidates cannot double-count or underestimate supply.
         */
        private PlanAmount fuzzyFiringBound(GraphInput input, boolean stockOnly) {
            if (graph.emittable().contains(input.key())) {
                return UNBOUNDED;
            }
            var variantFirings = new HashMap<AEKey, PlanAmount>();
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
            PlanAmount cap = PlanAmount.ZERO;
            for (PlanAmount variantCap : variantFirings.values()) {
                if (isUnbounded(variantCap)) {
                    return UNBOUNDED;
                }
                cap = cap.add(variantCap);
            }
            return cap;
        }

        private void addFuzzyVariantCapacity(GraphInput input, GraphInput.Candidate candidate,
                AEKey variant, boolean stockOnly, Map<AEKey, PlanAmount> variantFirings) {
            if (!input.validVariants().contains(variant)) {
                return;
            }
            PlanAmount cost = unitCost(candidate.amount(), input.multiplier());
            if (cost.signum() <= 0) {
                return;
            }
            PlanAmount firings = dividedCap(stockOnly ? stockOnlyWide(variant) : supplyWide(variant),
                    cost);
            variantFirings.merge(variant, firings, (best, next) -> best.max(next));
        }

        /**
         * Optimistic cycle count of one ring macro: bounded only by its net
         * inputs (seed catalysts are self-replenishing inside the ring and
         * never bound the optimistic count).
         */
        private PlanAmount ringMacroFiringBound(RingMacro macro) {
            var memo = macroFirings.get(macro);
            if (memo != null) {
                return memo;
            }
            macroFirings.put(macro, UNBOUNDED); // cycle guard

            PlanAmount bound = UNBOUNDED;
            for (var stack : macro.netInputs()) {
                bound = bound.min(dividedCap(supplyWide(stack.getKey()), stack.getLongValue()));
            }
            macroFirings.put(macro, bound);
            return bound;
        }
    }
}
