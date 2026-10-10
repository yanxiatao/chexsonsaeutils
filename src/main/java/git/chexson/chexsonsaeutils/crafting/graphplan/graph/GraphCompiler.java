package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import net.minecraft.world.level.Level;

/**
 * Compiles the reachable planning neighbourhood of a request into a
 * {@link CraftGraph}. Every key keeps all of its non-recursive patterns in
 * registration order (the solver tries them in the tree planner's route
 * order); compressed rings join as virtual ring-macro routes.
 * Circular (limitQty) inputs are classified into closed-form modes that mirror
 * {@code FastLimitQtyBatcher} and declined if the batcher would reject them.
 * Fuzzy inputs (multi-candidate slots, fuzzy stock variants,
 * {@code findCraftedStack} substitutes) are compiled into ordered extraction
 * template lists; anything the greedy template model cannot express exactly
 * declines with a reason so the caller falls back to the optimized tree path.
 */
public final class GraphCompiler {

    private GraphCompiler() {
    }

    public static CraftGraph compile(ICraftingService craftingService, KeyCounter networkStock, AEKey target,
            @Nullable Level level) {
        var stockSnapshot = new LinkedHashMap<AEKey, Long>(Math.max(16, networkStock.size()));
        for (var entry : networkStock) {
            stockSnapshot.put(entry.getKey(), entry.getLongValue());
        }
        var cacheKey = cacheKey(craftingService, stockSnapshot.keySet(), target);
        if (cacheKey != null) {
            synchronized (GRAPH_CACHE) {
                var cached = GRAPH_CACHE.get(cacheKey);
                if (cached != null) {
                    // 拓扑与库存键集合未变——只注入新鲜库存数量快照
                    return cached.withStock(stockSnapshot);
                }
            }
        }
        CraftGraph graph = compileUncached(craftingService, stockSnapshot, target, level);
        if (cacheKey != null) {
            synchronized (GRAPH_CACHE) {
                GRAPH_CACHE.put(cacheKey, graph);
            }
        }
        return graph;
    }

    /** LRU 编译缓存：modpack 巨图的 BFS 闭包是每次请求最贵的一段。 */
    private static final Map<CacheKey, CraftGraph> GRAPH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CraftGraph> eldest) {
            return size() > 4;
        }
    };

    record CacheKey(
            ICraftingService craftingService,
            long providersTick,
            AEKey target,
            Set<AEKey> stockKeys) {
        CacheKey {
            stockKeys = Set.copyOf(stockKeys);
        }
    }

    /**
     * 缓存键：providers tick（样板结构）+ target +
     * 完整库存键集合（不含数量——数量经 {@link CraftGraph#withStock}
     * 每请求注入）。
     */
    private static @Nullable CacheKey cacheKey(
            ICraftingService craftingService,
            Set<AEKey> stockKeys,
            AEKey target
    ) {
        if (!(craftingService instanceof CraftingService service)) {
            return null;
        }
        long providersTick;
        try {
            providersTick = ((git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceProvidersTickAccessor) service)
                    .chexsonsaeutils$getCraftingProviders()
                    .getLastModifiedOnTick();
        } catch (RuntimeException notAvailable) {
            return null;
        }
        return new CacheKey(service, providersTick, target, Set.copyOf(stockKeys));
    }

    private static CraftGraph compileUncached(ICraftingService craftingService,
            Map<AEKey, Long> networkStock, AEKey target,
            @Nullable Level level) {
        var compilation = new Compilation(craftingService, networkStock, level);
        long expandStart = System.nanoTime();
        String decline = compilation.expand(target, new LinkedHashSet<>(), true, null);
        compilation.expandNanos = System.nanoTime() - expandStart;
        return compilation.build(target, decline);
    }

    private static final class Compilation {
        private static final int MAX_KEYS = 65536;

        /**
         * Per-input fuzzy template options above this decline to the tree
         * planners (candidate × class variant pairs).
         */
        private static final int MAX_TEMPLATE_OPTIONS = 64;

        private final ICraftingService craftingService;
        /** 库存数量快照（未过滤 0 值——全部读取都走 getOrDefault，与过滤等价）。 */
        private final Map<AEKey, Long> networkStock;
        @Nullable
        private final Level level;
        private final Map<AEKey, List<GraphPattern>> producers = new LinkedHashMap<>();
        /** 未经祖先链过滤的可达样板，供 SCC 环识别使用。 */
        private final List<IPatternDetails> reachablePatterns = new ArrayList<>();
        private final Set<IPatternDetails> reachablePatternSet =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private CraftGraph.CycleKind cycleKind = CraftGraph.CycleKind.NONE;
        /** Raw pattern inputs aligned by index with each compiled pattern's inputs. */
        private final Map<GraphPattern, IPatternDetails.IInput[]> rawPatternInputs = new java.util.IdentityHashMap<>();
        private final Set<AEKey> visited = new HashSet<>();
        private final Set<AEKey> emittable = new LinkedHashSet<>();
        /** Raw {@code getCraftingFor(root)} size captured during expand (index-layer probe). */
        private int rootCraftingForCount = -1;
        /** 逐键 / 逐样板诊断改为聚合计数（trace 档才逐行记）。 */
        private int expandedKeyCount;
        private int skippedPatternCount;
        /** 编译分段耗时（纳秒）——摘要档一行报出，用于定位 compile 段的构成。 */
        private long expandNanos;
        private long classVariantsNanos;
        private long finalizeNanos;
        private long ringMacroNanos;
        private long stockNanos;
        private long assembleNanos;
        /**
         * 编译期 provider 查询记忆化：同一个键在 {@code resolveNodeKey}、
         * {@code expand}、输入槽发射判定里要被反复问「有没有样板 / 能不能发射」，
         * 而这些查询要穿透到网络索引。一次编译的 provider 视图是固定快照，
         * 记一次即可（实机上千键规模下这是编译耗时的主要来源之一）。
         */
        private final Map<AEKey, Collection<IPatternDetails>> craftingForMemo =
                new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
        private final it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap<AEKey> canEmitMemo =
                new it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap<>();

        private Collection<IPatternDetails> craftingFor(AEKey key) {
            var memo = craftingForMemo.get(key);
            if (memo == null) {
                memo = craftingService.getCraftingFor(key);
                craftingForMemo.put(key, memo);
            }
            return memo;
        }

        private boolean canEmitFor(AEKey key) {
            byte memo = canEmitMemo.getByte(key);
            if (memo == 0) {
                memo = craftingService.canEmitFor(key) ? (byte) 1 : (byte) -1;
                canEmitMemo.put(key, memo);
            }
            return memo > 0;
        }

        private Compilation(ICraftingService craftingService, Map<AEKey, Long> networkStock,
                @Nullable Level level) {
            this.craftingService = craftingService;
            this.networkStock = networkStock;
            this.level = level;
        }

        /**
         * The tree planner resolves every node key through
         * {@code CraftingTreeNode.findCraftedStack} at construction time:
         * emittable keys and keys with patterns stay, pattern-less keys are
         * replaced by the first fuzzy craftable substitute of any same-amount
         * candidate input that passes {@code isValid}. Everything downstream
         * (bytes, patterns, emit, missing) uses the resolved key.
         */
        private AEKey resolveNodeKey(AEKey wat, @Nullable IPatternDetails.IInput parentInput) {
            if (canEmitFor(wat)) {
                return wat;
            }
            var patterns = craftingFor(wat);
            if (!patterns.isEmpty() || parentInput == null) {
                return wat;
            }
            var possible = parentInput.getPossibleInputs();
            long acceptableAmount = possible[0].amount();
            for (var candidate : possible) {
                if (candidate.amount() != acceptableAmount) {
                    // Don't replace 1000 water by 1000 buckets for example.
                    continue;
                }
                var fuzzy = craftingService.getFuzzyCraftable(candidate.what(),
                        c -> parentInput.isValid(c, level));
                if (fuzzy != null) {
                    return fuzzy;
                }
            }
            return wat;
        }

        @Nullable
        private String expand(AEKey rawKey, LinkedHashSet<AEKey> pathKeys, boolean topLevel,
                @Nullable IPatternDetails.IInput parentInput) {
            AEKey key = topLevel ? rawKey : resolveNodeKey(rawKey, parentInput);
            if (!visited.add(key)) {
                return null;
            }
            if (visited.size() > MAX_KEYS) {
                return "graph too large (more than " + MAX_KEYS + " keys)";
            }
            if (canEmitFor(key)) {
                emittable.add(key);
                return null;
            }

            var patterns = craftingFor(key);
            if (topLevel) {
                rootCraftingForCount = patterns.size();
            }
            expandedKeyCount++;
            if (GraphDiagnostics.traceActive()) {
                // canEmitFor 只为凑诊断参数，不能无条件调用（编译要逐键走这里）
                GraphDiagnostics.trace("expand key=%s emittable=%s getCraftingFor=%d topLevel=%s",
                        key, canEmitFor(key), patterns.size(), topLevel);
            }
            if (patterns.isEmpty()) {
                return null;
            }
            for (var details : patterns) {
                if (this.reachablePatternSet.add(details)) {
                    this.reachablePatterns.add(details);
                }
            }

            List<GraphPattern> compiled = new ArrayList<>(patterns.size());
            // 单份可变路径集：进入本键时把它压进去，退出时弹出。此前每个键都要
            // 复制一份祖先链（658 个键 × 链深）——DFS 里 visited 保证每键只展开一次，
            // 压栈/弹栈与复制在语义上等价。祖先判定 = 在集合里且不是本键。
            pathKeys.add(key);
            try {
                for (var details : patterns) {
                    var decline = compilePattern(details, key, pathKeys, pathKeys, compiled);
                    if (decline != null) {
                        return decline;
                    }
                }
            } finally {
                pathKeys.remove(key);
            }
            if (!compiled.isEmpty()) {
                producers.put(key, compiled);
            }
            return null;
        }

        /** 祖先链包含 {@code candidate}（本键自身不算祖先）。 */
        private static boolean onAncestorPath(LinkedHashSet<AEKey> path, AEKey key, AEKey candidate) {
            return !candidate.equals(key) && path.contains(candidate);
        }

        /**
         * Compiles one pattern of {@code key}. Patterns whose outputs or
         * primary inputs land on the ancestor chain are skipped like the tree
         * planner's {@code notRecursive} filter
         * (CraftingTreeNode.java:138-156), which is evaluated on the parent
         * chain only — the child's own key is never part of the check, so a
         * pattern naturally producing its registration key stays usable.
         * Re-entry (own key or substitute resolving back onto the chain) is
         * blocked per-slot via {@link GraphInput#selfBlocked()}.
         */
        @Nullable
        private String compilePattern(IPatternDetails details, AEKey key, LinkedHashSet<AEKey> pathKeys,
                LinkedHashSet<AEKey> childPath, List<GraphPattern> compiled) {
            // 输入输出表只取一次：真实样板实现的 getInputs()/getOutputs()/
            // getPossibleInputs() 每次都新建数组/列表，编译期对同一
            // 样板反复查询是纯浪费；后续全部复用局部量。
            var patternOutputs = details.getOutputs();
            var patternInputs = details.getInputs();
            var outputAmounts = outputAmountsOf(patternOutputs);
            // possibles 按槽缓存：同一槽在祖先过滤、分类、候选三处都要读候选表，
            // 真实样板的 getPossibleInputs() 每次都新建数组。
            var possiblesBySlot = new appeng.api.stacks.GenericStack[patternInputs.length][];
            for (int slot = 0; slot < patternInputs.length; slot++) {
                possiblesBySlot[slot] = patternInputs[slot].getPossibleInputs();
            }
            for (var output : patternOutputs) {
                if (onAncestorPath(pathKeys, key, output.what())) {
                    skippedPatternCount++;
                    if (GraphDiagnostics.traceActive()) {
                        GraphDiagnostics.trace("compilePattern SKIP key=%s pattern=%s reason=output-on-path output=%s",
                                key, details.getDefinition(), output.what());
                    }
                    return null;
                }
            }
            for (int slot = 0; slot < patternInputs.length; slot++) {
                var possibles = possiblesBySlot[slot];
                var primary = possibles.length == 0 ? null : possibles[0];
                if (primary == null) {
                    skippedPatternCount++;
                    if (GraphDiagnostics.traceActive()) {
                        GraphDiagnostics.trace("compilePattern SKIP key=%s pattern=%s reason=null-primary-input",
                                key, details.getDefinition());
                    }
                    return null;
                }
                // 自喂键（输入 = 本样板自己的输出，如 1 出 2 的自增殖模板）豁免
                // 祖先链过滤：该槽走 CONSUMED_RETURNING 闭式（首发射提种子、
                // 产出修正滚雪球），A0 净增长门把守。真正的回环（自己产出
                // 不了的键回到祖先链）仍然拒绝——树语义下那是 pattern-less 叶。
                if (onAncestorPath(pathKeys, key, primary.what())
                        && outputAmounts.getOrDefault(primary.what(), 0L) <= 0) {
                    skippedPatternCount++;
                    if (GraphDiagnostics.traceActive()) {
                        GraphDiagnostics.trace("compilePattern SKIP key=%s pattern=%s reason=input-on-path input=%s",
                                key, details.getDefinition(), primary.what());
                    }
                    return null;
                }
            }

            // Circular (limitQty) inputs get closed-form batch accounting that
            // mirrors FastLimitQtyBatcher's classification; anything the
            // batcher would reject declines here so the tree planners keep
            // their exact per-item semantics.
            long outputPerCraft = outputAmounts.getOrDefault(key, 0L);
            if (outputPerCraft <= 0) {
                return "no output for " + key + " on pattern " + details.getDefinition();
            }

            var inputs = new ArrayList<GraphInput>(patternInputs.length);
            Map<AEKey, Long> selfConsumed = new HashMap<>();
            Map<AEKey, GraphInput.Mode> remainderModes = new HashMap<>();
            // 第一循环对每槽求出的 nodeKey / remainder 存下来给第二循环复用——
            // getRemainingKey 对 craft 样板是真实配方求值（AE2 源码自注释建议缓存），
            // resolveNodeKey 对 pattern-less 槽是完整模糊搜索，都禁不起跑两遍。
            var resolvedKeys = new ArrayList<AEKey>(patternInputs.length);
            for (int slot = 0; slot < patternInputs.length; slot++) {
                var input = patternInputs[slot];
                var possible = possiblesBySlot[slot];
                if (possible.length == 0 || possible[0] == null) {
                    return "empty input on pattern " + details.getDefinition();
                }
                var primary = possible[0];
                long mult = input.getMultiplier();
                long amount = primary.amount();
                if (mult <= 0 || amount <= 0) {
                    return "non-positive input size on pattern " + details.getDefinition();
                }
                AEKey inputKey = primary.what();
                GraphInput.Mode mode;
                AEKey remainder = null;
                var remaining = input.getRemainingKey(inputKey);
                if (remaining != null) {
                    // 多候选循环槽按 primary 候选走闭式——AE2 树的子节点键
                    // 就是 possibleInputs[0]，非首选候选在闭式槽上不参与
                    // （与树逐件语义一致的循环记账，不再整图拒绝）。
                    if (canEmitFor(inputKey)) {
                        return "emittable circular input " + inputKey + " on pattern " + details.getDefinition();
                    }
                    if (remaining.equals(inputKey)) {
                        // Container returned 1:1; combining with a self-output
                        // would make the extract/return cycle overlap the
                        // output insert.
                        if (outputAmounts.getOrDefault(inputKey, 0L) > 0) {
                            return "container and self-output on pattern " + details.getDefinition();
                        }
                        mode = GraphInput.Mode.RETURNED;
                        remainder = inputKey;
                    } else {
                        mode = GraphInput.Mode.FINITE_USE;
                        remainder = remaining;
                    }
                    // Two circular inputs sharing one remainder key can only
                    // share the single insert site when they use the same
                    // accounting kind.
                    var prevKind = remainderModes.putIfAbsent(remainder, mode);
                    if (prevKind != null && prevKind != mode) {
                        return "mixed remainder kinds for " + remainder + " on pattern " + details.getDefinition();
                    }
                } else if (outputAmounts.getOrDefault(inputKey, 0L) > 0) {
                    // Self-feeding catalyst. The pattern's own requested key is
                    // already skipped above (self-referencing input).
                    // 多候选自喂槽同上：闭式按 primary 候选，A0 净增长门兜底。
                    if (canEmitFor(inputKey)) {
                        return "emittable circular input " + inputKey + " on pattern " + details.getDefinition();
                    }
                    if (amount > Long.MAX_VALUE / mult) {
                        return "catalyst input overflow on pattern " + details.getDefinition();
                    }
                    long perCraft = amount * mult;
                    var consumed = selfConsumed.get(inputKey);
                    if (consumed != null && consumed > Long.MAX_VALUE - perCraft) {
                        return "catalyst input overflow on pattern " + details.getDefinition();
                    }
                    selfConsumed.merge(inputKey, perCraft, Long::sum);
                    mode = GraphInput.Mode.CONSUMED_RETURNING;
                } else {
                    mode = GraphInput.Mode.NORMAL;
                }
                // The node key after the tree planner's findCraftedStack
                // replacement drives bytes, pattern lookup, emit and missing
                // for this slot's site. Circular closed forms are derived from
                // the exact raw key, so a replaced circular input declines.
                AEKey nodeKey = resolveNodeKey(inputKey, input);
                if (mode != GraphInput.Mode.NORMAL && !nodeKey.equals(inputKey)) {
                    return "fuzzy circular input " + inputKey + " on pattern " + details.getDefinition();
                }
                // The slot's node key re-entering the expansion path is the
                // tree planner's second-level notRecursive termination: the
                // re-entered node behaves as a pattern-less leaf (stock only).
                // This stays on for CONSUMED_RETURNING slots too — their
                // closed form needs a seed-extraction leaf, and dropping it
                // makes the leaf site re-expand its own pattern (unbounded
                // recursion). RingContraction builds the self-loop edge
                // independently of this flag.
                boolean selfBlocked = childPath.contains(nodeKey);
                resolvedKeys.add(nodeKey);
                inputs.add(new GraphInput(nodeKey, amount, mult, mode, remainder, null, null, selfBlocked));
            }
            for (var entry : selfConsumed.entrySet()) {
                if (outputAmounts.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                    return "net-consuming catalyst " + entry.getKey() + " on pattern " + details.getDefinition();
                }
            }
            var pattern = new GraphPattern(details, key, outputPerCraft,
                    List.copyOf(patternOutputs), List.copyOf(inputs));
            compiled.add(pattern);
            rawPatternInputs.put(pattern, patternInputs);

            for (int x = 0; x < patternInputs.length; x++) {
                var raw = patternInputs[x];
                var childPossibles = possiblesBySlot[x];
                var childRaw = childPossibles[0].what();
                // The tree planner resolves child keys through findCraftedStack
                // before recursing; a resolved key landing back on the ancestor
                // chain (e.g. a substitute pointing at this pattern's own
                // output) makes the tree terminate as a pattern-less leaf — the
                // child simply compiles as a pattern-less key.
                var decline = expand(childRaw, childPath, false, raw);
                if (decline != null) {
                    return decline;
                }
            }
            return null;
        }

        /** 各输出键的每发产量（饱和于 {@link Long#MAX_VALUE}），一次建表复用。 */
        private static Map<AEKey, Long> outputAmountsOf(java.util.List<appeng.api.stacks.GenericStack> outputs) {
            var amounts = new HashMap<AEKey, Long>();
            for (var output : outputs) {
                if (output.amount() <= 0 || output.what() == null) {
                    continue;
                }
                long total = amounts.getOrDefault(output.what(), 0L);
                if (total > Long.MAX_VALUE - output.amount()) {
                    amounts.put(output.what(), Long.MAX_VALUE);
                } else {
                    amounts.put(output.what(), total + output.amount());
                }
            }
            return amounts;
        }

        /**
         * Ordered fuzzy-class members for every participating key: same primary
         * key, ordered exactly like AE2's {@code KeyComparator} (descending
         * fuzzy search value, ties by ascending hash code — most damaged first
         * for items). Covers network stock, pattern outputs, template
         * candidates and remainder keys. Drives the solver's whole-class stock
         * pulls (AE2's {@code cacheFuzzy}) and the ordered template
         * enumeration. Single member classes are omitted — the key itself is
         * the only variant.
         */
        private Map<AEKey, List<AEKey>> buildClassVariants() {
            Map<Object, Set<AEKey>> classes = new LinkedHashMap<>();
            // 库存侧只收「参与图的键所在的类」：networkStock 在大型整合包里有上万条，
            // 而求解只会碰到图里出现过的键的模糊类，其余全是白建。
            var relevantPrimaries = new java.util.HashSet<Object>();
            for (var patternList : producers.values()) {
                for (var pattern : patternList) {
                    for (var out : pattern.allOutputs()) {
                        relevantPrimaries.add(out.what().getPrimaryKey());
                    }
                }
            }
            for (var rawInputs : rawPatternInputs.values()) {
                for (var input : rawInputs) {
                    for (var candidate : input.getPossibleInputs()) {
                        if (candidate != null) {
                            relevantPrimaries.add(candidate.what().getPrimaryKey());
                        }
                    }
                }
            }
            for (var key : networkStock.keySet()) {
                if (!relevantPrimaries.contains(key.getPrimaryKey())) {
                    continue;
                }
                classes.computeIfAbsent(key.getPrimaryKey(), k -> new LinkedHashSet<>()).add(key);
            }
            for (var patternList : producers.values()) {
                for (var pattern : patternList) {
                    for (var out : pattern.allOutputs()) {
                        classes.computeIfAbsent(out.what().getPrimaryKey(), k -> new LinkedHashSet<>())
                                .add(out.what());
                    }
                }
            }
            // remainder 复用 compilePattern 第一循环求好的结果（getRemainingKey 对
            // craft 样板是真实配方求值，AE2 源码自注释建议缓存）；只有图上没有的
            // 样板（环宏 executionRatio 成员）才回落 raw 查询。同一 rawInputs 实例
            // 只会在两个循环之一出现，所以候选遍历仍在同一处完成。
            for (var rawEntry : rawPatternInputs.entrySet()) {
                // rawPatternInputs 的键就是 GraphPattern 实例本身
                var compiledPattern = rawEntry.getKey();
                var rawInputs = rawEntry.getValue();
                for (int inputIndex = 0; inputIndex < rawInputs.length; inputIndex++) {
                    var input = rawInputs[inputIndex];
                    for (var candidate : input.getPossibleInputs()) {
                        if (candidate != null) {
                            classes.computeIfAbsent(candidate.what().getPrimaryKey(), k -> new LinkedHashSet<>())
                                    .add(candidate.what());
                        }
                    }
                    var primary = input.getPossibleInputs()[0];
                    if (primary == null) {
                        continue;
                    }
                    AEKey remaining = null;
                    if (compiledPattern != null && inputIndex < compiledPattern.inputs().size()) {
                        remaining = compiledPattern.inputs().get(inputIndex).remainderKey();
                    } else {
                        remaining = input.getRemainingKey(primary.what());
                    }
                    if (remaining != null) {
                        classes.computeIfAbsent(remaining.getPrimaryKey(), k -> new LinkedHashSet<>())
                                .add(remaining);
                    }
                }
            }
            // Touched keys (target, resolved node keys) can belong to classes
            // with network variants they don't share a key with, e.g. a
            // pattern-less target whose sibling variant holds the stock.
            for (var key : visited) {
                classes.computeIfAbsent(key.getPrimaryKey(), k -> new LinkedHashSet<>()).add(key);
            }
            var result = new HashMap<AEKey, List<AEKey>>();
            for (var entry : classes.entrySet()) {
                if (entry.getValue().size() <= 1) {
                    continue;
                }
                var members = new ArrayList<>(entry.getValue());
                // Mirror AE2's FuzzySearch.KeyComparator: descending fuzzy
                // search value, ties by ascending hash code (damageable items
                // come back most damaged first).
                members.sort((a, b) -> {
                    int byValue = Integer.compare(b.getFuzzySearchValue(), a.getFuzzySearchValue());
                    if (byValue != 0) {
                        return byValue;
                    }
                    return Long.compare(a.hashCode(), b.hashCode());
                });
                for (var member : members) {
                    result.put(member, List.copyOf(members));
                }
            }
            return Map.copyOf(result);
        }

        /**
         * Finalizes the fuzzy extraction data of every compiled input against
         * the class registry: computes the valid template set (class members
         * and pattern outputs the pattern's {@code isValid} accepts), the
         * ordered candidate list, the circular-input exact-isolation check and
         * the per-input template option cap.
         */
        @Nullable
        private String finalizeFuzzyInputs(Map<AEKey, List<AEKey>> classVariants) {
            var oldRaws = new java.util.IdentityHashMap<>(rawPatternInputs);
            var newRaws = new java.util.IdentityHashMap<GraphPattern, IPatternDetails.IInput[]>();
            for (var entry : producers.entrySet()) {
                var patternList = entry.getValue();
                var finalizedList = new ArrayList<GraphPattern>(patternList.size());
                boolean listChanged = false;
                for (var pattern : patternList) {
                    var rawInputs = oldRaws.get(pattern);
                    var finalized = new ArrayList<GraphInput>(pattern.inputs().size());
                    boolean changed = false;
                    for (int x = 0; x < pattern.inputs().size(); x++) {
                        var input = pattern.inputs().get(x);
                        var raw = rawInputs[x];
                        // 单候选精确快路径：候选唯一、类表无该键（单成员类）、
                        // isValid 通过——与下面的逐候选枚举结果逐位相同
                        // （candidates=[key]、valid={key}、extract=null/valid=null），
                        // 但省掉 Candidate 分配与集合复制。逐键诊断行证实
                        // 实机 1757 槽里这类槽占绝大多数。
                        var possibles = raw.getPossibleInputs();
                        if (possibles.length == 1 && possibles[0] != null
                                && input.mode() == GraphInput.Mode.NORMAL
                                && classVariants.get(possibles[0].what()) == null
                                && possibles[0].what().equals(input.key())
                                && raw.isValid(input.key(), level)) {
                            finalized.add(input);
                            continue;
                        }
                        var candidates = new ArrayList<GraphInput.Candidate>();
                        Set<AEKey> valid = null;
                        int options = 0;
                        for (var stack : raw.getPossibleInputs()) {
                            if (stack == null) {
                                continue;
                            }
                            candidates.add(new GraphInput.Candidate(stack.what(), stack.amount()));
                            var members = classVariants.get(stack.what());
                            if (members == null) {
                                // Single-member class: the candidate itself is the
                                // only template option.
                                options++;
                                if (raw.isValid(stack.what(), level)) {
                                    valid = addTo(valid, stack.what());
                                }
                                continue;
                            }
                            for (var member : members) {
                                options++;
                                if (raw.isValid(member, level)) {
                                    valid = addTo(valid, member);
                                }
                            }
                        }
                        if (options > MAX_TEMPLATE_OPTIONS) {
                            return "fuzzy combination too large on pattern " + pattern.source().getDefinition();
                        }
                        if (input.mode() != GraphInput.Mode.NORMAL) {
                            // Closed-form circular accounting assumes the resolved
                            // key is the only extractable template.
                            if (valid == null || valid.size() != 1 || !valid.contains(input.key())) {
                                return "fuzzy circular input " + input.key() + " on pattern "
                                        + pattern.source().getDefinition();
                            }
                        }
                        List<GraphInput.Candidate> extractCandidates;
                        Set<AEKey> validVariants;
                        if (candidates.size() == 1 && valid != null && valid.size() == 1
                                && valid.contains(input.key())) {
                            // Exact-key fast path: the only valid template is the
                            // node key itself.
                            extractCandidates = null;
                            validVariants = null;
                        } else {
                            extractCandidates = List.copyOf(candidates);
                            validVariants = valid == null ? Set.of() : Set.copyOf(valid);
                        }
                        var rebuilt = new GraphInput(input.key(), input.amountPerCraft(), input.multiplier(),
                                input.mode(), input.remainderKey(), extractCandidates, validVariants,
                                input.selfBlocked());
                        changed |= !rebuilt.equals(input);
                        finalized.add(rebuilt);
                    }
                    var built = changed
                            ? new GraphPattern(pattern.source(), pattern.outputKey(), pattern.outputPerCraft(),
                                    pattern.allOutputs(), List.copyOf(finalized))
                            : pattern;
                    listChanged |= changed;
                    finalizedList.add(built);
                    newRaws.put(built, rawInputs);
                }
                if (listChanged) {
                    producers.put(entry.getKey(), finalizedList);
                }
            }
            rawPatternInputs.clear();
            rawPatternInputs.putAll(newRaws);
            return null;
        }

        /**
         * Compiles ring macros from the already-expanded producer graph.
         * {@link RingContraction} is the sole SCC authority; macro dependency
         * keys are expanded into the graph and failing macros are pruned.
         */
        private void compileRingMacros(
                Map<AEKey, List<RingMacro>> macrosByEntry) {
            RingContraction.Analysis analysis = RingContraction.analyzePatterns(this.reachablePatterns);
            this.cycleKind = analysis.cycleKind();
            for (RingMacro macro : analysis.macros()) {
                macrosByEntry.computeIfAbsent(macro.entryKey(), ignored -> new ArrayList<>()).add(macro);
            }

            // A macro's dependency keys (seed catalysts and net inputs) request
            // like fresh root nodes in the tree planner; expand them into the
            // graph. A key that fails to compile prunes the macro that needs it.
            var allMacros = new ArrayList<RingMacro>();
            macrosByEntry.values().forEach(allMacros::addAll);
            boolean prunedAny = false;
            for (var macro : allMacros) {
                boolean pruned = false;
                String pruneReason = null;
                for (var seed : macro.startupSeeds().entrySet()) {
                    var decline = expand(seed.getKey(), new LinkedHashSet<>(), true, null);
                    if (decline != null) {
                        pruned = true;
                        pruneReason = "startupSeed " + seed.getKey() + ": " + decline;
                        break;
                    }
                }
                if (!pruned) {
                    for (var stack : macro.netInputs()) {
                        var decline = expand(stack.getKey(), new LinkedHashSet<>(), true, null);
                        if (decline != null) {
                            pruned = true;
                            pruneReason = "netInput " + stack.getKey() + ": " + decline;
                            break;
                        }
                    }
                }
                if (pruned) {
                    prunedAny = true;
                    GraphDiagnostics.log("RING_MACRO pruned entry={} reason={}",
                            macro.entryKey(), pruneReason);
                    pruneMacro(macrosByEntry, macro);
                }
            }
            if (prunedAny) {
                this.cycleKind = CraftGraph.CycleKind.UNSUPPORTED_CYCLE;
            }
        }

        private static void pruneMacro(
                Map<AEKey, List<RingMacro>> macrosByEntry,
                RingMacro macro) {
            var list = macrosByEntry.get(macro.entryKey());
            if (list != null) {
                var filtered = list.stream().filter(m -> m != macro).toList();
                if (filtered.isEmpty()) {
                    macrosByEntry.remove(macro.entryKey());
                } else {
                    macrosByEntry.put(macro.entryKey(), filtered);
                }
            }
        }

        private static Set<AEKey> addTo(@Nullable Set<AEKey> set, AEKey key) {
            if (set == null) {
                set = new HashSet<>();
            }
            set.add(key);
            return set;
        }

        private CraftGraph build(AEKey target,
                @Nullable String decline) {
            // 快照直接当作图的库存：全部读取都是 getOrDefault，0 值项与缺项等价，
            // 所以不必再筛一遍建新表（此前是第三趟库存遍历）。
            var stock = networkStock;
            this.stockNanos = 0L;

            long classStart = System.nanoTime();
            var classVariants = buildClassVariants();
            this.classVariantsNanos = System.nanoTime() - classStart;
            if (decline == null) {
                long finalizeStart = System.nanoTime();
                decline = finalizeFuzzyInputs(classVariants);
                this.finalizeNanos = System.nanoTime() - finalizeStart;
            }

            var macrosByEntry = new LinkedHashMap<AEKey, List<RingMacro>>();
            if (decline == null) {
                long ringStart = System.nanoTime();
                compileRingMacros(macrosByEntry);
                this.ringMacroNanos = System.nanoTime() - ringStart;
            }

            long assembleStart = System.nanoTime();
            var patternsBySource = new HashMap<IPatternDetails, GraphPattern>();
            var byproductSupply = new HashMap<AEKey, List<CraftGraph.ByproductSupply>>();
            var remainderSupply = new HashMap<AEKey, List<CraftGraph.RemainderSupply>>();
            boolean multiplePaths = false;
            if (decline == null) {
                for (var patternList : producers.values()) {
                    if (patternList.size() > 1) {
                        multiplePaths = true;
                    }
                    for (var pattern : patternList) {
                        patternsBySource.put(pattern.source(), pattern);
                        for (var out : pattern.allOutputs()) {
                            if (out.what().equals(pattern.outputKey())) {
                                continue;
                            }
                            byproductSupply.computeIfAbsent(out.what(), k -> new ArrayList<>())
                                    .add(new CraftGraph.ByproductSupply(pattern, out.amount()));
                        }
                        for (var input : pattern.inputs()) {
                            if (input.mode() == GraphInput.Mode.FINITE_USE) {
                                remainderSupply.computeIfAbsent(input.remainderKey(), k -> new ArrayList<>())
                                        .add(new CraftGraph.RemainderSupply(pattern, input.multiplier()));
                            }
                        }
                    }
                }
            }

            int rootProducerCount = producers.getOrDefault(target, List.of()).size();
            int rootRoutesCount = rootProducerCount
                    + macrosByEntry.getOrDefault(target, List.of()).size();
            GraphDiagnostics.log(
                    "build target=%s decline=%s craftingForTarget=%d rootProducers=%d rootRoutes=%d"
                            + " totalProducerKeys=%d expandedKeys=%d skippedPatterns=%d"
                            + " expandMs=%d classVariantsMs=%d finalizeMs=%d ringMacroMs=%d"
                            + " stockMs=%d assembleMs=%d",
                    target, decline, rootCraftingForCount, rootProducerCount, rootRoutesCount, producers.size(),
                    expandedKeyCount, skippedPatternCount,
                    expandNanos / 1_000_000L, classVariantsNanos / 1_000_000L,
                    finalizeNanos / 1_000_000L, ringMacroNanos / 1_000_000L,
                    stockNanos / 1_000_000L,
                    (System.nanoTime() - assembleStart) / 1_000_000L);

            // 求解热路径每次 extract / insert / request 都要查 producersByOutput、
            // classVariants、emittable、stock，用 fastutil 开放寻址取代 Map.copyOf
            // 的 MapN（线性探测 + Objects.equals，实机采样里 MapN.probe 占自耗 16%）。
            // 键内化表编译期预编号：求解侧账本 id 在建图时一次定死，
            // attempt 内不再为同一键反复哈希（实机 25.7 万次 request 每次八次查询）。
            var keyIndex = decline == null
                    ? preassignKeyIndex(target, producers, classVariants, stock, macrosByEntry,
                            emittable)
                    : null;
            return new CraftGraph(target, fastMap(producers), fastMap(patternsBySource),
                    List.copyOf(reachablePatterns), fastMap(macrosByEntry), this.cycleKind,
                    fastMap(byproductSupply), fastMap(remainderSupply), fastMap(stock),
                    new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(emittable), fastMap(classVariants),
                    keyIndex, multiplePaths, decline, rootCraftingForCount, rootRoutesCount);
        }

        /**
         * 编译期键预编号：把求解会碰到的全部键一次编好。顺序按首次出现，
         * 与求解侧按需追加的编号值可能不同——但 id 只是账本数组下标，
         * 同一图内一致即等价，不要求与旧编号逐位相同。
         */
        private static git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex preassignKeyIndex(
                AEKey target,
                Map<AEKey, List<GraphPattern>> producers,
                Map<AEKey, List<AEKey>> classVariants,
                Map<AEKey, Long> stock,
                Map<AEKey, List<RingMacro>> macrosByEntry,
                Set<AEKey> emittable) {
            var keyIndex = new git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex(256);
            keyIndex.idOf(target);
            for (var entry : producers.entrySet()) {
                keyIndex.idOf(entry.getKey());
                for (var pattern : entry.getValue()) {
                    for (var out : pattern.allOutputs()) {
                        keyIndex.idOf(out.what());
                    }
                    for (var input : pattern.inputs()) {
                        keyIndex.idOf(input.key());
                        if (input.extractCandidates() != null) {
                            for (var candidate : input.extractCandidates()) {
                                keyIndex.idOf(candidate.key());
                            }
                        }
                        if (input.validVariants() != null) {
                            for (var variant : input.validVariants()) {
                                keyIndex.idOf(variant);
                            }
                        }
                        if (input.remainderKey() != null) {
                            keyIndex.idOf(input.remainderKey());
                        }
                    }
                }
            }
            for (var members : classVariants.values()) {
                for (var member : members) {
                    keyIndex.idOf(member);
                }
            }
            for (var key : stock.keySet()) {
                keyIndex.idOf(key);
            }
            for (var macros : macrosByEntry.values()) {
                for (var macro : macros) {
                    preassignMacroKeys(keyIndex, macro);
                }
            }
            for (var key : emittable) {
                keyIndex.idOf(key);
            }
            return keyIndex;
        }

        private static void preassignMacroKeys(
                git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex keyIndex, RingMacro macro) {
            for (var stack : macro.netInputs()) {
                keyIndex.idOf(stack.getKey());
            }
            for (var stack : macro.netOutputs()) {
                keyIndex.idOf(stack.getKey());
            }
            for (var stack : macro.catalysts()) {
                keyIndex.idOf(stack.getKey());
            }
            for (var key : macro.startupSeeds().keySet()) {
                keyIndex.idOf(key);
            }
        }

        private static <K, V> Map<K, V> fastMap(Map<K, V> source) {
            var map = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<K, V>(
                    Math.max(16, source.size() * 2));
            map.putAll(source);
            return map;
        }
    }
}
