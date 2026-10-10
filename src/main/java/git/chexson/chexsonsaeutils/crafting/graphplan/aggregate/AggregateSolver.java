package git.chexson.chexsonsaeutils.crafting.graphplan.aggregate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import git.chexson.chexsonsaeutils.crafting.executionwitness.PlanningInputWitness;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;

/**
 * 聚合求解器（Phase 10）：把站点树的「每个树位置各请求一次」压成「每个键各请求一次」。
 *
 * <p>树路径为对齐 AE2 的节点数要把依赖 DAG 展开成树，实机是 42 万个站点、25.7 万次
 * request，而真正的配方只有 542 个——475 倍冗余。冗余本身不产生新信息：一个键的总需求
 * 就是各消费槽需求之和，聚合后一趟拓扑序即可求解。
 *
 * <p>字节口径三项都能聚合复现：
 * <ul>
 * <li>物料费 {@code Σ(amount × units)}：对同一个键，各位置的 units 之和乘以 amount
 * 与逐位置累加逐位相同；</li>
 * <li>点火费 {@code Σ times}：crafts 次数相同则相同；</li>
 * <li>节点费 {@code nodes × 8}：节点数 = 1 + Σ_k 位置数(k) × Σ_{P∈producers(k)}
 * 输入槽数(P)，而「位置数」与需求量同构，走同一趟拓扑传播
 * （{@code positions(输入槽键) += positions(键)}）。</li>
 * </ul>
 *
 * <p>多产出路线固定使用稳定注册顺序中的第一条。聚合求解器只保留为离线比较与
 * 测试实现；多路线图出现缺口时立即 DECLINE，由生产线性求解器处理路线拆分，
 * 不再用实验性的跨轮路线升级近似树侧语义。
 *
 * <p>唯一不逐位的情形：库存跨位置部分满足时，树里靠前的位置吃到库存会提前返回、
 * 不展开子树，而聚合只知道总量。该情形下节点数偏大，误差以 8 字节/节点计——实机
 * {@code nodes×8 = 3.4e6} 对总量 {@code 6.2e12} 占 5.5e-7。
 */
public final class AggregateSolver {

    private AggregateSolver() {
    }

    public static SolveResult solve(CraftGraph graph, CalculationStrategy strategy, long requestedAmount,
            @Nullable GraphPlanningBudget budget) {
        if (graph.hasDeclined()) {
            return SolveResult.declined(graph.declineReason());
        }
        if (requestedAmount <= 0) {
            return SolveResult.declined("non-positive request amount");
        }
        String unsupported = unsupportedReason(graph);
        if (unsupported != null) {
            return SolveResult.declined("aggregate: " + unsupported);
        }

        // 保留比较基准的确定性默认档，不接管语义可能偏离树规划器的图形。
        var plan = new Plan(graph, budget);
        String decline = plan.build(requestedAmount);
        if (decline != null) {
            return SolveResult.declined("aggregate: " + decline);
        }
        if (plan.hasMissing()) {
            plan.reconcileMissingWithStock();
            // 对账后重算：假缺口全部剔除时计划实际可执行，simulation 必须翻回
            // false——否则 AE2 UI 挂在模拟模式而 missing 列为空（用户实测的
            // 「说缺又不显示缺什么」正是这个状态）。
            plan.refreshMissingAfterReconciliation();
            // 计划有缺口时，树路径还能在多条产出选择之间**拆分**需求（15 个铁：
            // 10 金出 5 个、30 钻出 10 个）。这种拆分要求「先把路线 A 点到它失败为止」，
            // 而「A 失败」只有等 A 的整棵子树跑完才知道——一趟拓扑序里拿不到这个信息，
            // 任何按趟数近似的方案都会给出与树不同的 crafts 分布。
            // 所以多路线图一旦出现缺口就整盘交回树路径：宁可不快，也不给出不同的计划。
            // 单路线图的缺口计划与树侧模拟轮逐字段相同，可以直接用。
            if (hasMultiRouteKey(graph)) {
                return SolveResult.declined("aggregate: shortfall on a multi-route graph");
            }
            if (strategy != CalculationStrategy.REPORT_MISSING_ITEMS) {
                return SolveResult.declined("aggregate: incomplete under strict strategy");
            }
        }
        // 后置守恒闸门与树路径同一道：聚合核自己也是闭式捷径，符号错误必须在出门前
        // 被拦住，绝不让不可能的计划进 CraftingPlan。违例即回落树路径。
        if (git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig
                .graphPlanningClosureGateEnabled()) {
            long gateStart = GraphDiagnostics.active() ? System.nanoTime() : 0L;
            String violation = git.chexson.chexsonsaeutils.crafting.graphplan.solve.MaterialClosureGate
                    .validate(plan.state(), graph, requestedAmount);
            if (GraphDiagnostics.active()) {
                GraphDiagnostics.log("solve.closureGate took %d us decline=%s",
                        (System.nanoTime() - gateStart) / 1_000L, violation);
            }
            if (violation != null) {
                return SolveResult.declined("aggregate: closure gate — " + violation);
            }
        }
        if (GraphDiagnostics.active()) {
            GraphDiagnostics.log("aggregate.solve nodes=%d slots=%d crafts=%d missing=%s",
                    plan.nodes(), plan.visitedKeys(), plan.state().crafts().size(), plan.hasMissing());
        }
        return SolveResult.success(plan.state(), requestedAmount, plan.hasMissing(), plan.buildWitness());
    }

    /** 图里被任何样板输入槽消费的键全集。 */
    private static Set<AEKey> collectConsumedKeys(CraftGraph graph) {
        var keys = new java.util.HashSet<AEKey>();
        for (var producers : graph.producersByOutput().values()) {
            for (GraphPattern pattern : producers) {
                for (GraphInput input : pattern.inputs()) {
                    keys.add(input.key());
                    if (input.extractCandidates() != null) {
                        for (var candidate : input.extractCandidates()) {
                            keys.add(candidate.key());
                        }
                    }
                }
            }
        }
        return keys;
    }

    /**
     * 图上是否存在「一个键有多条产出选择」。有缺口时这类图必须交回树路径：树能在
     * 多条选择之间拆分需求，聚合的一趟拓扑只能整键选一条。环宏也算一条选择——
     * 树侧的沙箱回滚（环失败就当没试过、改走样板）同样是聚合表达不了的分支。
     */
    private static boolean hasMultiRouteKey(CraftGraph graph) {
        if (!graph.ringMacrosByEntry().isEmpty()) {
            return true;
        }
        for (var producers : graph.producersByOutput().values()) {
            if (producers.size() > 1) {
                return true;
            }
        }
        return false;
    }

    /** 本切片的受理条件；返回不受理原因或 {@code null}。 */
    @Nullable
    private static String unsupportedReason(CraftGraph graph) {
        // 副产物 / 余料只有在**图里有消费者**时才构成回流，而那种情形与树路径**不可能**
        // 等价——不是「暂时没做」：
        //   树侧 fire() 先把全部输入槽递归到底，**之后**才插入产出（含副产物），
        //   所以 A 的子树看不到 A 自己的副产物；只有 firePatternRoute 的第二批起才看得到。
        //   聚合侧一个键只处理一次，副产物在该节点处理时就插入，而它的后继节点都排在
        //   它之后——于是副产物对后继**总是**可见。两侧的可见窗口不同，crafts 分布必然分叉。
        // 结论：这类图整盘交回树路径。没有消费者的副产物（典型是 FINITE_USE 的损耗余料）
        // 只是插进库存无人取用，落账次序不影响账本，照常受理。
        var consumedKeys = collectConsumedKeys(graph);
        for (AEKey key : graph.byproductSupply().keySet()) {
            if (consumedKeys.contains(key)) {
                return "byproduct feedback into a consumed key";
            }
        }
        for (AEKey key : graph.remainderSupply().keySet()) {
            if (consumedKeys.contains(key)) {
                return "remainder feedback into a consumed key";
            }
        }
        if (!graph.routeSelection().acyclic()) {
            return "cyclic route selection";
        }
        return null;
    }

    /** 一个键的聚合节点：模板单位取自父输入槽（根为 1）。 */
    private static final class Node {
        private final AEKey key;
        private final long templateAmount;
        private final boolean isRoot;
        private final List<GraphPattern> routes;
        /** 本键的环宏（树侧 RouteOrdering 把它排在样板之前）。 */
        @Nullable
        private final git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro ringMacro;
        /** 环宏依赖子树内的节点：树侧是沙箱里的临时根节点，不计入节点数。 */
        private final boolean isRingDependency;
        /** 模糊提取候选（父输入槽给的），null 表示精确键快路径。 */
        @Nullable
        private final List<GraphInput.Candidate> templates;
        @Nullable
        private final Set<AEKey> validVariants;
        private long unitsRequested;
        private long positions;
        /**
         * 延迟归还：树侧的循环槽是「点火当中提取、随即归还」，提取严格早于归还。
         * 聚合把子节点的提取推迟到拓扑序里，所以归还也必须推迟到该节点处理完之后
         * 才落账，否则 {@code used} 峰值会因为归还先到而偏低（实测差一个桶）。
         */
        private long deferredReturn;
        /** 共享节点的消费者需求按拓扑传播顺序压缩保存，后续所有供应都按此顺序归属。 */
        private final List<DemandRun> demands = new ArrayList<>();
        private int demandCursor;
        private long demandOffset;
        /** 后继身份表，建图时算一次（DFS 与 Kahn 两趟都用）。 */
        @Nullable
        private NodeKey[] childIds;
        /**
         * 账本键 id，首次用到时解析一次。账本内部是 {@code long[]} 按 id 寻址，
         * AEKey 形态的入口每次都要先哈希一遍——本节点的键在一次处理里要被用到
         * 三到四次（模板提取、产出回收、延迟归还）。
         */
        private int keyId = -1;

        private Node(AEKey key, long templateAmount, boolean isRoot, List<GraphPattern> routes,
                @Nullable git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro ringMacro,
                boolean isRingDependency,
                @Nullable List<GraphInput.Candidate> templates, @Nullable Set<AEKey> validVariants) {
            this.key = key;
            this.templateAmount = templateAmount;
            this.isRoot = isRoot;
            this.routes = routes;
            this.ringMacro = ringMacro;
            this.isRingDependency = isRingDependency;
            this.templates = templates;
            this.validVariants = validVariants;
        }

        @Nullable
        private GraphPattern route() {
            return this.routes.isEmpty() ? null : this.routes.getFirst();
        }
    }

    /**
     * 聚合节点身份 = 键 + 提取口径。同一个键被不同模板单位 / 不同候选集的槽消费时，
     * 整模板取整与贪婪分配的口径不同，必须分成两个节点各自结算——它们共享同一个
     * 库存池，逐节点依次抽干与树侧逐位置抽干的总量一致。
     */
    /**
     * 聚合节点身份 = 键 + 模板单位 + 提取形状 id + 是否环依赖。
     *
     * <p>形状（候选表 + 有效变体集）用 int id 而不是直接放集合：record 的自动哈希会
     * 递归哈希整份候选表，而实机一个槽最多 34 个候选、全图 1757 个槽，每次 map 操作
     * 都付一遍就是冷启动的主要开销。id 由 {@code ShapeRegistry} 按 {@code GraphInput}
     * 实例记忆化，深比较每个槽只做一次。
     */
    private record NodeKey(AEKey key, long templateAmount, int shapeId, boolean ringDependency) {
    }

    /** 提取形状去重表：把 (候选表, 有效变体集) 归一成 int id。 */
    private static final class ShapeRegistry {
        private record Shape(@Nullable List<GraphInput.Candidate> templates,
                @Nullable Set<AEKey> validVariants) {
        }

        private final java.util.IdentityHashMap<GraphInput, NodeKey> byInput =
                new java.util.IdentityHashMap<>();
        private final java.util.HashMap<Shape, Integer> shapeIds = new java.util.HashMap<>();

        /** 该槽的节点身份（按槽实例记忆化，热路径零分配零深哈希）。 */
        private NodeKey idOf(GraphInput input, boolean ringDependency) {
            NodeKey cached = this.byInput.get(input);
            if (cached != null && cached.ringDependency() == ringDependency) {
                return cached;
            }
            var shape = new Shape(input.extractCandidates(), input.validVariants());
            int shapeId = this.shapeIds.computeIfAbsent(shape, ignored -> this.shapeIds.size());
            var id = new NodeKey(input.key(), input.amountPerCraft(), shapeId, ringDependency);
            if (cached == null) {
                this.byInput.put(input, id);
            }
            return id;
        }

        /** 精确键形状（无候选表）的 id。 */
        private int exactShapeId() {
            return this.shapeIds.computeIfAbsent(new Shape(null, null), ignored -> this.shapeIds.size());
        }
    }

    private record DemandRun(GraphPattern consumer, int inputSlot, long units) {
        private DemandRun {
            Objects.requireNonNull(consumer, "consumer");
            if (inputSlot < 0 || units <= 0L) {
                throw new IllegalArgumentException("invalid consumer demand run");
            }
        }
    }

    private record AllocatedRun(AEItemKey patternDefinition, int inputSlot, AEKey exactKey,
            long templateAmount, long units) {
    }

    /** 一轮聚合求解的全部可变状态。 */
    private static final class Plan {
        private final CraftGraph graph;
        @Nullable
        private final GraphPlanningBudget budget;
        private final GraphPlanningState state;
        /** 按「键 + 提取口径」索引：口径不同的消费者各自结算，共享同一个库存池。 */
        private final HashMap<NodeKey, Node> nodes = new HashMap<>();
        @Nullable
        private NodeKey rootId;
        /** 自阻塞（树侧的 pattern-less 叶）槽位 id——建节点时不挂任何路线。 */
        private final java.util.HashSet<NodeKey> blockedIds = new java.util.HashSet<>();
        private final ShapeRegistry shapes = new ShapeRegistry();
        /** 槽位 → 提取形状（节点身份只带 id，形状本体在这里取回）。 */
        private final java.util.IdentityHashMap<GraphInput, GraphInput> shapeSource =
                new java.util.IdentityHashMap<>();
        private long nodeCount = 1;
        private boolean missing;
        private boolean witnessComplete = true;
        private final List<AllocatedRun> allocatedRuns = new ArrayList<>();

        private Plan(CraftGraph graph, @Nullable GraphPlanningBudget budget) {
            this.graph = graph;
            this.budget = budget;
            // 复用线程级 undo 日志底层数组：默认构造会新建一个约 1.5 MB 的 Arena
            // （64K ints + 64K refs + 64K longs + 32K doubles + 16K refs），
            // 而聚合核根本不回滚，每次求解白分配加清零一遍。
            // 键表复用图内预编号：账本 id 建图时已定死，本轮零哈希。
            var sharedKeys = graph.keyIndex();
            this.state = sharedKeys == null
                    ? new GraphPlanningState(graph.stock(), graph.classVariants(),
                            git.chexson.chexsonsaeutils.crafting.graphplan.solve.PlanJournal.Arena
                                    .forCurrentThread())
                    : new GraphPlanningState(graph.stock(), graph.classVariants(),
                            git.chexson.chexsonsaeutils.crafting.graphplan.solve.PlanJournal.Arena
                                    .forCurrentThread(),
                            sharedKeys);
        }

        private GraphPlanningState state() {
            return this.state;
        }

        private boolean hasMissing() {
            return this.missing;
        }

        private long nodes() {
            return this.nodeCount;
        }

        private int visitedKeys() {
            return this.nodes.size();
        }

        @Nullable
        private String build(long requestedAmount) {
            this.state.ignore(this.graph.target());
            List<Node> order = topologicalOrder();
            if (order == null) {
                return "extraction shape diverges across consumers";
            }
            Node root = this.nodes.get(this.rootId);
            root.unitsRequested = requestedAmount;
            root.positions = 1;

            for (Node node : order) {
                if (this.budget != null) {
                    this.budget.check();
                }
                String decline = process(node);
                if (decline != null) {
                    return decline;
                }
            }
            this.state.addBytes(this.nodeCount * 8L);
            return null;
        }

        /**
         * 需求方向的拓扑序（目标在前）。Kahn 保证一个节点被处理时全部上游贡献都已
         * 累加完毕；节点身份含提取口径，所以口径分叉不再是不受理原因。
         *
         * <p>返回 null 表示图在需求方向上仍有回边（环收缩后本不该出现），交回树路径。
         */
        @Nullable
        private List<Node> topologicalOrder() {
            var indegree = new Object2IntOpenHashMap<NodeKey>();
            indegree.defaultReturnValue(0);
            AEKey target = this.graph.target();
            this.rootId = new NodeKey(target, 1L, this.shapes.exactShapeId(), false);
            Node root = newNode(this.rootId, null, true, false);
            this.nodes.put(this.rootId, root);
            // 栈里直接放节点对象：此前放 id 再逐次 nodes.get，等于每条边多一次查表
            var stack = new ArrayDeque<Node>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Node current = stack.pop();
                GraphPattern currentPattern = current.route();
                int patternInputs = currentPattern == null ? 0 : currentPattern.inputs().size();
                NodeKey[] childIds = childIdsOf(current);
                for (int i = 0; i < childIds.length; i++) {
                    NodeKey childId = childIds[i];
                    indegree.addTo(childId, 1);
                    if (!this.nodes.containsKey(childId)) {
                        GraphInput sourceInput = i < patternInputs ? currentPattern.inputs().get(i) : null;
                        Node child = newNode(childId, sourceInput, false,
                                this.blockedIds.contains(childId));
                        this.nodes.put(childId, child);
                        stack.push(child);
                    }
                }
            }

            var order = new ArrayList<Node>(this.nodes.size());
            var ready = new ArrayDeque<Node>();
            ready.add(root);
            while (!ready.isEmpty()) {
                Node current = ready.poll();
                order.add(current);
                for (NodeKey childId : childIdsOf(current)) {
                    if (indegree.addTo(childId, -1) == 1) {
                        ready.add(this.nodes.get(childId));
                    }
                }
            }
            return order.size() == this.nodes.size() ? order : null;
        }

        private NodeKey idOf(GraphInput input, boolean ringDependency) {
            return this.shapes.idOf(input, ringDependency);
        }

        /** 环依赖键：树侧当作全新的根节点请求（模板单位 1、无候选、不计节点数）。 */
        private NodeKey ringSlotId(AEKey key) {
            return new NodeKey(key, 1L, this.shapes.exactShapeId(), true);
        }

        /**
         * 一个节点的全部后继：样板路线的输入槽，加上环宏的净输入与催化物缺口槽。
         * 环宏与样板可能都被用到（树侧 {@code totalRequested -= fireRingMacro(...)}
         * 之后会继续走样板路线补余量），所以两侧的后继都要进拓扑图。
         */
        private NodeKey[] childIdsOf(Node node) {
            if (node.childIds != null) {
                return node.childIds;
            }
            GraphPattern pattern = node.route();
            int patternSlots = pattern == null ? 0 : pattern.inputs().size();
            int ringSlots = 0;
            if (node.ringMacro != null) {
                ringSlots = node.ringMacro.netInputs().size() + node.ringMacro.startupSeeds().size();
            }
            var ids = new NodeKey[patternSlots + ringSlots];
            int at = 0;
            for (int x = 0; x < patternSlots; x++) {
                GraphInput input = pattern.inputs().get(x);
                NodeKey childId = idOf(input, node.isRingDependency);
                if (input.selfBlocked()) {
                    this.blockedIds.add(childId);
                }
                ids[at++] = childId;
            }
            if (node.ringMacro != null) {
                for (var stack : node.ringMacro.netInputs()) {
                    ids[at++] = ringSlotId(stack.getKey());
                }
                for (var key : node.ringMacro.startupSeeds().keySet()) {
                    ids[at++] = ringSlotId(key);
                }
            }
            node.childIds = ids;
            return ids;
        }

        private Node newNode(NodeKey id, @Nullable GraphInput sourceInput, boolean isRoot,
                boolean selfBlocked) {
            List<GraphPattern> routes = selfBlocked
                    ? List.of()
                    : this.graph.producersByOutput().getOrDefault(id.key(), List.of());
            // 环依赖子树内不再挂环宏：树侧靠 activeRingIds 互斥禁止环自嵌套，
            // 聚合无递归栈，一律不挂即等价且更保守。
            var macro = id.ringDependency() ? null : macroFor(id.key(), isRoot);
            return new Node(id.key(), id.templateAmount(), isRoot, routes, macro,
                    id.ringDependency(),
                    sourceInput == null ? null : sourceInput.extractCandidates(),
                    sourceInput == null ? null : sourceInput.validVariants());
        }

        /** 该键的环宏；{@code rootOnly} 宏只挂根，与 {@code RouteOrdering} 一致。 */
        @Nullable
        private git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro macroFor(AEKey key,
                boolean isRoot) {
            var macros = this.graph.ringMacrosByEntry().get(key);
            if (macros == null) {
                return null;
            }
            for (var macro : macros) {
                if (macro.rootOnly() == isRoot && macro.entryOutputPerCycle() > 0) {
                    return macro;
                }
            }
            for (var macro : macros) {
                if (!macro.rootOnly() && macro.entryOutputPerCycle() > 0) {
                    return macro;
                }
            }
            return null;
        }

        @Nullable
        private String process(Node node) {
            try {
                return processNode(node);
            } finally {
                if (node.deferredReturn > 0) {
                    this.state.insertById(node.key, keyIdOf(node), node.deferredReturn);
                }
            }
        }

        @Nullable
        private String processNode(Node node) {
            long unitsRequested = node.unitsRequested;
            if (unitsRequested <= 0) {
                return null;
            }
            // 物料费：Σ(amount × units) 与逐位置累加逐位相同
            this.state.addStackBytes(node.key, node.templateAmount, unitsRequested);

            int nodeKeyId = keyIdOf(node);
            long remaining = unitsRequested - extractStock(node, unitsRequested);
            if (remaining <= 0) {
                return null;
            }
            if (this.graph.emittable().contains(node.key)) {
                this.state.emitItems(node.key, multiplyForWitness(node.templateAmount, remaining));
                allocateDemand(node, node.key, node.templateAmount, remaining);
                return null;
            }
            long totalNeeded = multiplyForWitness(remaining, node.templateAmount);
            if (node.ringMacro != null) {
                this.witnessComplete = false;
                long fulfilled = fireRingMacro(node, node.ringMacro, totalNeeded);
                allocateItems(node, node.key, node.templateAmount, fulfilled);
                totalNeeded -= fulfilled;
                if (totalNeeded <= 0) {
                    return null;
                }
            }
            GraphPattern pattern = node.route();
            if (pattern == null) {
                this.missing = true;
                this.state.addMissing(node.key, totalNeeded);
                return null;
            }
            if (!(pattern.source().getDefinition() instanceof AEItemKey)) {
                this.witnessComplete = false;
            }

            // 该键真被展开：本键的全部位置都会把**每条**路线的槽位建出来。
            // 环依赖子树在树侧是沙箱临时根节点，不计入节点数。
            if (!node.isRingDependency) {
                long slotsPerPosition = 0;
                for (GraphPattern route : node.routes) {
                    slotsPerPosition += route.inputs().size();
                }
                this.nodeCount += node.positions * slotsPerPosition;
            }

            long totalRequested = totalNeeded;
            long perCraft = Math.max(1L, pattern.outputPerCraft());
            long times = (totalRequested + perCraft - 1L) / perCraft;

            // 点火站点数：树侧每个被展开的位置各点一次火。positions 是「被请求的位置数」，
            // 它 ≥「被展开的位置数」（库存全额满足的位置会提前返回不点火），所以下面
            // 用它算出的循环输入请求量只会偏保守（多留催化物），不会偏少。
            long firings = Math.max(1L, node.positions);
            for (int inputSlot = 0; inputSlot < pattern.inputs().size(); inputSlot++) {
                GraphInput input = pattern.inputs().get(inputSlot);
                Node child = this.nodes.get(idOf(input, node.isRingDependency));
                if (child == null) {
                    return "input key missing from order: " + input.key();
                }
                long units = switch (input.mode()) {
                    case NORMAL, FINITE_USE -> multiplyForWitness(input.multiplier(), times);
                    // 循环槽只有每次点火的首发射真实提取，其余由闭式补偿覆盖
                    case RETURNED, CONSUMED_RETURNING -> multiplyForWitness(input.multiplier(), firings);
                };
                child.unitsRequested = addForWitness(child.unitsRequested, units);
                child.positions += node.positions;
                if (input.mode() == GraphInput.Mode.NORMAL) {
                    child.demands.add(new DemandRun(pattern, inputSlot, units));
                } else {
                    this.witnessComplete = false;
                }
                if (input.mode() == GraphInput.Mode.RETURNED
                        || input.mode() == GraphInput.Mode.CONSUMED_RETURNING) {
                    this.state.addStackBytes(input.key(), input.amountPerCraft(),
                            WideMath.wideMul(input.multiplier(), times - firings));
                }
                if (input.mode() == GraphInput.Mode.RETURNED) {
                    // 归还的是同一个键（remainderKey == key），必须等子节点提取完再落账
                    child.deferredReturn = addSaturating(child.deferredReturn,
                            WideMath.wideMul(input.multiplier(), firings));
                    this.state.addStackBytes(input.remainderKey(), input.multiplier(), times);
                }
            }
            // FINITE_USE 的余料是另一个键（损耗后的工具），图里没有消费者
            // （byproduct/remainderSupply 为空已在门口校验），落账次序不影响账本。
            for (GraphInput input : pattern.inputs()) {
                if (input.mode() == GraphInput.Mode.FINITE_USE) {
                    long units = WideMath.wideMul(input.multiplier(), times);
                    this.state.insert(input.remainderKey(), units);
                    this.state.addStackBytes(input.remainderKey(), units, 1);
                }
            }
            // 产出：自喂催化键按闭式修正（produced×Σtimes − consumed×(Σtimes − firings)），
            // 同键多输出栈只修正第一个，与树侧 fire 的口径一致
            java.util.HashSet<AEKey> corrected = null;
            boolean hasSelfFeeding = pattern.hasSelfFeedingInput();
            for (var out : pattern.allOutputs()) {
                if (!hasSelfFeeding) {
                    this.state.insert(out.what(), WideMath.wideMul(out.amount(), times));
                    continue;
                }
                long consumedPerCraft = selfConsumedPerCraft(pattern, out.what());
                if (consumedPerCraft <= 0) {
                    this.state.insert(out.what(), WideMath.wideMul(out.amount(), times));
                    continue;
                }
                if (corrected == null) {
                    corrected = new java.util.HashSet<>();
                }
                if (!corrected.add(out.what())) {
                    continue;
                }
                long produced = WideMath.wideMul(outputPerCraftOf(pattern, out.what()), times);
                long consumed = WideMath.wideMul(consumedPerCraft, times - firings);
                this.state.insert(out.what(), produced - consumed);
            }
            this.state.addCrafting(pattern.source(), times);
            this.state.addBytes(times);

            long available = this.state.extractById(node.key, nodeKeyId, totalRequested, false);
            allocateItems(node, node.key, node.templateAmount, available);
            if (available < totalRequested) {
                this.missing = true;
                this.state.addMissing(node.key, totalRequested - available);
            }
            return null;
        }

        /**
         * 环宏点火，逐项对应树侧 {@code fireRingMacro}：催化物按「一次计算一份种子」
         * 从原始库存按精确键预留、缺口推给环依赖槽；净输入按 ceil 缩放推给环依赖槽；
         * {@code executionRatio × ceil} 入 crafts、{@code netOutputs × floor} 入库存，
         * 两者都零字节。
         *
         * <p>树侧的沙箱回滚（环失败就当没试过）在聚合里没有对应物，改由出口的
         * 「有缺口 + 多路线图 ⇒ DECLINE」兜住：无缺口时树侧的环尝试也必然成功，
         * 两侧账本一致；有缺口时整个计划交回树路径。
         *
         * @return 环宏实际满足的数量
         */
        private long fireRingMacro(Node node,
                git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro macro, long totalRequested) {
            long entryOutput = macro.entryOutputPerCycle();
            if (entryOutput <= 0 || totalRequested <= 0) {
                return 0;
            }
            for (var seed : macro.startupSeeds().entrySet()) {
                AEKey key = seed.getKey();
                long amountNeeded = seed.getValue();
                long already = this.state.ringExtractions().getLong(key);
                long requiredAmount = Math.max(0L, amountNeeded - already);
                if (requiredAmount <= 0) {
                    continue;
                }
                long reservable = Math.max(0L, reservableStock(key) - already);
                long extracted = Math.min(requiredAmount, reservable);
                if (extracted > 0L) {
                    this.state.recordRingExtraction(key, extracted);
                    this.state.insert(key, extracted);
                    // 预留在提交时进 CPU 库存并驻留整个作业，与其他提取同样计费
                    this.state.addStackBytes(key, extracted, 1);
                }
                if (extracted < requiredAmount) {
                    addRingDemand(key, requiredAmount - extracted, node.positions);
                }
            }
            for (var stack : macro.netInputs()) {
                long required = WideMath.ceilMulDiv(stack.getLongValue(), totalRequested, entryOutput);
                if (required > 0) {
                    addRingDemand(stack.getKey(), required, node.positions);
                }
            }
            for (var entry : macro.executionRatio().entrySet()) {
                long times = WideMath.ceilMulDiv(entry.getValue(), totalRequested, entryOutput);
                if (times > 0) {
                    this.state.addCrafting(entry.getKey(), times);
                }
            }
            for (var stack : macro.netOutputs()) {
                long produced = WideMath.floorMulDiv(stack.getLongValue(), totalRequested, entryOutput);
                if (produced > 0) {
                    this.state.insert(stack.getKey(), produced);
                }
            }
            return this.state.extractById(node.key, keyIdOf(node), totalRequested, false);
        }

        private void addRingDemand(AEKey key, long units, long positions) {
            Node child = this.nodes.get(ringSlotId(key));
            if (child == null) {
                // 拓扑建图阶段已把环宏的净输入与催化物键全部登记，缺失说明宏在
                // 建图后变了——保守记为缺口，由出口的多路线 DECLINE 兜住。
                this.missing = true;
                this.state.addMissing(key, units);
                return;
            }
            child.unitsRequested = addSaturating(child.unitsRequested, units);
            child.positions += positions;
        }

        private long reservableStock(AEKey key) {
            return this.graph.stock().getOrDefault(key, 0L);
        }

        /**
         * 库存提取，口径与树侧 {@code request} 第一步逐位一致：精确键走整模板快路径；
         * 多候选槽按「候选 × 活变体」贪婪迭代。
         *
         * <p>聚合安全性：贪婪迭代对每个位置都是「当前候选取不出整模板才换下一个」，
         * 而候选池共享，所以逐位置依次抽干与一次性按总需求抽干的结果相同。
         */
        private long extractStock(Node node, long unitsRequested) {
            if (node.templates == null) {
                var extracted = this.state.extractTemplatesWithSourceById(node.key, keyIdOf(node),
                        node.templateAmount, unitsRequested);
                if (extracted.units() > 0L && extracted.sourceKey() != null) {
                    allocateDemand(node, extracted.sourceKey(), node.templateAmount, extracted.units());
                }
                return extracted.units();
            }
            long remaining = unitsRequested;
            var rejected = new java.util.HashSet<AEKey>();
            for (var candidate : node.templates) {
                // 树侧的贪婪迭代「取到整模板就停」，每个候选至多消费一个活变体——
                // 用 firstLiveTemplate 逐次推进，不再构建整张活变体列表
                // （实机一个模糊类十几个变体，540 个 craft 各一次就是数千次扫描分配）。
                while (remaining > 0) {
                    var variant = this.state.firstLiveTemplate(candidate.key());
                    if (variant == null) {
                        break;
                    }
                    if (node.validVariants != null && !node.validVariants.contains(variant)) {
                        // 该变体不被 isValid 接受：树侧「跳过列表元素继续找下一个」。
                        // 在拒绝集上推进，保持同一类序与同一消费结果。
                        rejected.add(variant);
                        var next = this.state.firstLiveTemplate(candidate.key(), rejected);
                        if (next == null) {
                            break;
                        }
                        if (node.validVariants.contains(next)) {
                            var extracted = this.state.extractTemplatesWithSourceById(next,
                                    this.state.idOf(next), candidate.amount(), remaining);
                            if (extracted.units() > 0) {
                                allocateDemand(node, next, candidate.amount(), extracted.units());
                                remaining -= extracted.units();
                                if (remaining == 0) {
                                    return unitsRequested;
                                }
                            }
                        }
                        rejected.add(next);
                        continue;
                    }
                    var extracted = this.state.extractTemplatesWithSourceById(variant,
                            this.state.idOf(variant), candidate.amount(), remaining);
                    if (extracted.units() > 0) {
                        allocateDemand(node, variant, candidate.amount(), extracted.units());
                        remaining -= extracted.units();
                        if (remaining == 0) {
                            return unitsRequested;
                        }
                    }
                    break;
                }
            }
            return unitsRequested - remaining;
        }

        private void allocateItems(Node node, AEKey exactKey, long templateAmount, long itemAmount) {
            if (itemAmount <= 0L) {
                return;
            }
            if (templateAmount <= 0L || itemAmount % templateAmount != 0L) {
                this.witnessComplete = false;
                return;
            }
            allocateDemand(node, exactKey, templateAmount, itemAmount / templateAmount);
        }

        /** 把供应按共享节点的消费者 demand run 顺序归属，不按 firing 展开。 */
        private void allocateDemand(Node node, AEKey exactKey, long templateAmount, long units) {
            if (node.isRoot || units <= 0L) {
                return;
            }
            long remaining = units;
            while (remaining > 0L && node.demandCursor < node.demands.size()) {
                DemandRun demand = node.demands.get(node.demandCursor);
                long available = demand.units() - node.demandOffset;
                long assigned = Math.min(remaining, available);
                if (!(demand.consumer().source().getDefinition() instanceof AEItemKey definition)) {
                    this.witnessComplete = false;
                    return;
                }
                appendAllocated(new AllocatedRun(definition, demand.inputSlot(), exactKey,
                        templateAmount, assigned));
                remaining -= assigned;
                node.demandOffset += assigned;
                if (node.demandOffset == demand.units()) {
                    node.demandCursor++;
                    node.demandOffset = 0L;
                }
            }
            if (remaining > 0L) {
                this.witnessComplete = false;
            }
        }

        private void appendAllocated(AllocatedRun run) {
            if (run.units() <= 0L) {
                return;
            }
            int lastIndex = this.allocatedRuns.size() - 1;
            if (lastIndex >= 0) {
                AllocatedRun last = this.allocatedRuns.get(lastIndex);
                if (last.patternDefinition().equals(run.patternDefinition())
                        && last.inputSlot() == run.inputSlot()
                        && last.exactKey().equals(run.exactKey())
                        && last.templateAmount() == run.templateAmount()) {
                    long merged = addForWitness(last.units(), run.units());
                    this.allocatedRuns.set(lastIndex, new AllocatedRun(last.patternDefinition(),
                            last.inputSlot(), last.exactKey(), last.templateAmount(), merged));
                    return;
                }
            }
            this.allocatedRuns.add(run);
        }

        private long multiplyForWitness(long left, long right) {
            try {
                return Math.multiplyExact(left, right);
            } catch (ArithmeticException overflowed) {
                this.witnessComplete = false;
                return WideMath.wideMul(left, right);
            }
        }

        private long addForWitness(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException overflowed) {
                this.witnessComplete = false;
                return addSaturating(left, right);
            }
        }

        private PlanningInputWitness buildWitness() {
            boolean hasFuzzyInput = false;
            for (var entry : this.state.crafts().entrySet()) {
                GraphPattern compiled = this.graph.patternsBySource().get(entry.getKey());
                if (entry.getValue() != null && entry.getValue() > 0L && compiled != null
                        && compiled.inputs().stream().anyMatch(input ->
                        input.extractCandidates() != null || input.validVariants() != null)) {
                    hasFuzzyInput = true;
                    break;
                }
            }
            if (!hasFuzzyInput) {
                return null;
            }

            var expectedCrafts = new java.util.LinkedHashMap<AEItemKey, Long>();
            var slotCounts = new java.util.LinkedHashMap<AEItemKey, Integer>();
            var expectedUnits = new java.util.HashMap<String, Long>();
            for (var entry : this.state.crafts().entrySet()) {
                IPatternDetails source = entry.getKey();
                long times = entry.getValue();
                if (!(source.getDefinition() instanceof AEItemKey definition) || times <= 0L) {
                    this.witnessComplete = false;
                    continue;
                }
                try {
                    expectedCrafts.merge(definition, times, Math::addExact);
                } catch (ArithmeticException overflowed) {
                    this.witnessComplete = false;
                }
                GraphPattern compiled = this.graph.patternsBySource().get(source);
                if (compiled == null) {
                    this.witnessComplete = false;
                    continue;
                }
                slotCounts.merge(definition, compiled.inputs().size(), Math::max);
                for (int slot = 0; slot < compiled.inputs().size(); slot++) {
                    GraphInput input = compiled.inputs().get(slot);
                    if (input.mode() != GraphInput.Mode.NORMAL) {
                        this.witnessComplete = false;
                        continue;
                    }
                    try {
                        expectedUnits.merge(definition + "\u0000" + slot,
                                Math.multiplyExact(input.multiplier(), times), Math::addExact);
                    } catch (ArithmeticException overflowed) {
                        this.witnessComplete = false;
                    }
                }
            }

            var byPattern = new java.util.LinkedHashMap<AEItemKey,
                    List<PlanningInputWitness.InputRun>>();
            var ownership = new java.util.LinkedHashMap<AEKey,
                    List<PlanningInputWitness.OwnershipRun>>();
            var actualUnits = new java.util.HashMap<String, Long>();
            for (AllocatedRun run : this.allocatedRuns) {
                byPattern.computeIfAbsent(run.patternDefinition(), ignored -> new ArrayList<>())
                        .add(new PlanningInputWitness.InputRun(run.inputSlot(), run.exactKey(),
                                run.templateAmount(), run.units()));
                ownership.computeIfAbsent(run.exactKey(), ignored -> new ArrayList<>())
                        .add(new PlanningInputWitness.OwnershipRun(run.patternDefinition(), run.inputSlot(),
                                run.templateAmount(), run.units()));
                try {
                    actualUnits.merge(run.patternDefinition() + "\u0000" + run.inputSlot(),
                            run.units(), Math::addExact);
                } catch (ArithmeticException overflowed) {
                    this.witnessComplete = false;
                }
            }
            if (!expectedUnits.equals(actualUnits)) {
                this.witnessComplete = false;
            }

            var patternRuns = new ArrayList<PlanningInputWitness.PatternInputRuns>();
            for (var entry : byPattern.entrySet()) {
                int slots = slotCounts.getOrDefault(entry.getKey(), 0);
                if (slots <= 0) {
                    this.witnessComplete = false;
                    continue;
                }
                patternRuns.add(new PlanningInputWitness.PatternInputRuns(entry.getKey(), slots,
                        entry.getValue()));
            }
            var ownershipRuns = new ArrayList<PlanningInputWitness.ExactKeyOwnership>();
            ownership.forEach((key, runs) -> ownershipRuns.add(
                    new PlanningInputWitness.ExactKeyOwnership(key, runs)));
            return new PlanningInputWitness(this.witnessComplete, expectedCrafts, patternRuns,
                    ownershipRuns);
        }

        /** 一个自喂键每次发射的催化消耗（口径同树侧 fire）。 */
        private static long selfConsumedPerCraft(GraphPattern pattern, AEKey key) {
            long total = 0;
            for (GraphInput input : pattern.inputs()) {
                if (input.mode() == GraphInput.Mode.CONSUMED_RETURNING && input.key().equals(key)) {
                    total = addSaturating(total, WideMath.wideMul(input.amountPerCraft(), input.multiplier()));
                }
            }
            return total;
        }

        /** 一个键在全部产出栈上的每次发射产量。 */
        private static long outputPerCraftOf(GraphPattern pattern, AEKey key) {
            long total = 0;
            for (var out : pattern.allOutputs()) {
                if (out.what().equals(key)) {
                    total = addSaturating(total, out.amount());
                }
            }
            return total;
        }

        /** 节点键的账本 id（首次用到时解析，随后复用）。 */
        private int keyIdOf(Node node) {
            if (node.keyId < 0) {
                node.keyId = this.state.idOf(node.key);
            }
            return node.keyId;
        }

        /**
         * 把本轮仍有缺口且还有未尝试路线的键各换下一条路线，下一轮整图重跑。
         * 路线用尽的键保持缺口——最终计划的 missing 会如实反映。
         *
         * @return 是否发生了任何升级（false = 已收敛或无可升级）
         */
        /**
         * 缺口对账：把「库存实际足够却仍记缺口」的条目（类变体解析、流体的
         * static/flowing 键差异、InfinityCellStore 的 Long.MAX_VALUE 口径等造成
         * 的假缺口）从 missing 剔除，并按 used 口径补记真实提取量。
         *
         * <p>用户实测：x100 计划 missing 含 minecraft:lava x 2e15（网络里有无限
         * 熔岩单元），但 AE2 确认界面的 missing 列是 summary 从库存 SIMULATE
         * 重算的——重算发现不缺，UI 就什么都不显示；而计划仍是 simulate=true，
         * 玩家看到的是「说缺又缺不出的残缺计划」。
         *
         * <p>对账口径：missing 量 ≤ graph.stock 该键的类总量（含 InfinityCell 的
         * MAX_VALUE）即视为假缺口；rebuild 时把 missing 量按整模板折算成 used
         * 增加。真缺口（库存不足）原样保留。
         */
        /** 对账后同步内存标记与账本实际内容。 */
        private void refreshMissingAfterReconciliation() {
            boolean anyNegative = false;
            for (AEKey key : this.state.missing().keys()) {
                if (this.state.missing().getLong(key) > 0L) {
                    anyNegative = true;
                    break;
                }
            }
            this.missing = anyNegative;
        }

        private void reconcileMissingWithStock() {
            var missingItems = this.state.missing();
            var usedItems = this.state.used();
            var fixed = new java.util.LinkedHashMap<AEKey, Long>();
            for (AEKey key : missingItems.keys()) {
                long missingAmount = missingItems.getLong(key);
                if (missingAmount <= 0L) {
                    continue;
                }
                long available = reservableStock(key);
                if (available >= missingAmount) {
                    fixed.put(key, missingAmount);
                }
            }
            for (var entry : fixed.entrySet()) {
                // 从 missing 剔除：直接负增量（undo 日志不涉及——出口阶段无回滚）
                this.state.reduceMissing(entry.getKey(), entry.getValue());
                // 把这部分补回 used：树侧这些量本来就会走 requiredExtract 口径
                this.state.addUsedForReconciliation(entry.getKey(), entry.getValue());
            }
        }

        private static long addSaturating(long a, long b) {
            long sum = a + b;
            return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
        }
    }
}
