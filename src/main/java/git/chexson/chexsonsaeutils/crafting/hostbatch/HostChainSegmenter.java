package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.pattern.AECraftingPattern;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.crafting.solve.DependencyGraphs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 链识别/分段/边界输入净额化：从原生计划的 patternTimes 里挑出同一宿主独占的样板链，
 * 折叠成宿主批次候选并给出净外部输入。只做纯计算，不写回计划。
 */
final class HostChainSegmenter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 段构建失败归因（单次重写内累计，写回后由 Planner 统一输出一行）。 */
    static final class SegmentFailureStats {
        int topoNull;
        int defEmpty;
        int remainderNull;
        int primaryNonPositive;
        int boundaryInputsNull;
        int boundaryOutputsEmpty;
        int splitOutputsEmpty;
        int basePatternNull;
        /** grouped 过滤：unwrap 失败 / host 非独占 / 环成员。 */
        int groupedUnwrapNull;
        int groupedHostNull;
        int groupedRingMember;
        /** 非合成样板：宿主只执行 IMolecularAssemblerSupportedPattern，处理样板聚了也推不动。 */
        int groupedUnsupported;
        /** 段级拦截：段产出键是环催化物。 */
        int blockedSegments;
        int blockedSegmentNodes;
        /** 现料策略：上游产出键现料不足全量（f&lt;1）而放弃聚合的段数（改走原生+执行期多推）。 */
        int stockSplits;
        /** 兼容归因行保留（原「拆批回退整批」计数，现路径已移除，恒 0）。 */
        int stockSplitFallback;
        /** 库存切环（neoecoae 式）：催化物种子被库存覆盖、整段聚合的段数。 */
        int stockCutRings;
        int stockCutRingNodes;
        /** 库存切环回落：催化物种子库存不足。 */
        int stockCutShort;
        /** stockCutShort 首例的催化物键与缺口量。 */
        @Nullable AEKey stockCutShortKey;
        /** hostNull 首例的样板类名与 definition，用于定位独占判定失败。 */
        @Nullable String hostNullExample;
        @Nullable AEKey hostNullKeyExample;
        @Nullable AEKey topoExample;
        @Nullable AEKey remainderExample;
        @Nullable AEKey boundaryExample;
    }

    private static final ThreadLocal<SegmentFailureStats> FAILURE_STATS =
            ThreadLocal.withInitial(SegmentFailureStats::new);

    /** 取出并清空本次累计（Planner 写回后调用）。 */
    static SegmentFailureStats drainFailureStats() {
        SegmentFailureStats stats = FAILURE_STATS.get();
        FAILURE_STATS.remove();
        return stats;
    }

    private static SegmentFailureStats failures() {
        return FAILURE_STATS.get();
    }

    private HostChainSegmenter() {
    }

    static @Nullable SelectedPlanGraph buildSelectedPlanGraph(
            HostBatchPlanningContext rewriteContext,
            AEKey rootOutput,
            Map<IPatternDetails, Long> selectedPatternTimes
    ) {
        Map<IPatternDetails, Long> aggregatablePatternTimes = selectedPatternTimes;
        if (aggregatablePatternTimes == null || aggregatablePatternTimes.isEmpty()) {
            return null;
        }

        Map<AEKey, List<SelectedGraphNode>> nodesByOutput = new LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Long> entry : aggregatablePatternTimes.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            long craftCount = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
            if (pattern == null || craftCount <= 0L) {
                continue;
            }

            PatternDefinitionKey definitionKey = rewriteContext.patternDefinitionKey(pattern);
            if (definitionKey == null) {
                return null;
            }

            HostBatchProvider host = rewriteContext.exclusiveHostBatchProvider(pattern);

            Map<AEKey, Long> inputs = rewriteContext.describeAggregationInputs(pattern);
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

    /**
     * 批次化候选：单元批按 {@code pushCount} 份写入 crafts（执行期现料
     * 多推：CPU 有多少料就推多少个单元，与原生多推同构）。
     */
    record BatchedCandidate(HostAggregationCandidate candidate, long pushCount) {
    }

    static List<BatchedCandidate> buildHostAggregationCandidates(
            Level level,
            HostBatchPlanningContext rewriteContext,
            SelectedPlanGraph selectedGraph
    ) {
        var stats = failures();
        Map<HostBatchProvider, Map<AEKey, SelectedGraphNode>> grouped = new LinkedHashMap<>();
        for (Map.Entry<AEKey, SelectedGraphNode> entry : selectedGraph.nodes().entrySet()) {
            SelectedGraphNode node = entry.getValue();
            if (rewriteContext.unwrapBasePattern(node.pattern()) == null) {
                stats.groupedUnwrapNull++;
                continue;
            }
            if (node.host() == null) {
                stats.groupedHostNull++;
                if (stats.hostNullExample == null && node.pattern() != null) {
                    stats.hostNullExample = node.pattern().getClass().getName();
                    stats.hostNullKeyExample = entry.getKey();
                }
                continue;
            }
            // 宿主只执行合成样板（IMolecularAssemblerSupportedPattern）：
            // 处理样板聚成批次也推不动（pushPattern 直接拒绝），不过滤
            // 会占着链分段与余料仿真白白浪费 200ms+ 重写耗时。
            if (!(node.pattern() instanceof IMolecularAssemblerSupportedPattern)) {
                stats.groupedUnsupported++;
                continue;
            }
            grouped.computeIfAbsent(node.host(), ignored -> new LinkedHashMap<>()).put(entry.getKey(), node);
        }

        List<BatchedCandidate> candidates = new ArrayList<>();
        // 现料拆批的工作库存：跨段共享、逐段扣减（上游产出键的现成部分
        // 是各段竞争的有限资源）。无 stock 快照（原生路径/测试）不拆批。
        Map<AEKey, Long> workingUpstreamStock = rewriteContext.networkStock() == null
                ? null
                : new LinkedHashMap<>(rewriteContext.networkStock());
        for (Map.Entry<HostBatchProvider, Map<AEKey, SelectedGraphNode>> entry : grouped.entrySet()) {
            Map<AEKey, SelectedGraphNode> hostNodes = entry.getValue();

            List<Set<AEKey>> segments = splitPerPatternFormalAggregationSegments(formalInputsByOutput(hostNodes));

            for (Set<AEKey> segment : segments) {
                Map<AEKey, SelectedGraphNode> segmentNodes = selectSegmentNodes(hostNodes, segment);
                candidates.addAll(splitSegmentIntoBatches(
                        level,
                        rewriteContext,
                        entry.getKey(),
                        segmentNodes,
                        workingUpstreamStock
                ));
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 整段一批：一段即一次 push，执行数 = 计划给出的全量 craftCount。
     * 不设上限、不拆批——材料是规划时按库存与需求算好的全量，执行侧一次提走、
     * 一次推送；链内中间产物在段内闭环，无需等待。
     *
     * <p>现料拆批（neoecoae 式）：边界输入里依赖上游产出的键（尚未在网络里）
     * 按库存快照算可跑比例 f，f&lt;1 时拆「现料批 + 等料批」——现料批立即可推，
     * 等料批等上游供料后再推。叶料（纯网络提取）在 CPU 提交时已全量提取进
     * 库存，不构成等待，不参与 f。
     */
    private static List<BatchedCandidate> splitSegmentIntoBatches(
            Level level,
            HostBatchPlanningContext rewriteContext,
            HostBatchProvider host,
            Map<AEKey, SelectedGraphNode> segmentNodes,
            @Nullable Map<AEKey, Long> workingUpstreamStock
    ) {
        if (segmentNodes == null || segmentNodes.isEmpty()) {
            return List.of();
        }
        HostAggregationCandidate whole =
                buildHostAggregationCandidate(level, rewriteContext, host, segmentNodes, null);
        if (whole == null) {
            return List.of();
        }
        if (workingUpstreamStock == null) {
            return List.of(new BatchedCandidate(whole, 1L));
        }
        Set<AEKey> upstreamProducedKeys = rewriteContext.upstreamProducedKeys();
        // f = min(1, min over 上游键 stock/need)，有理数交叉比较防溢出
        long ratioNum = 1L;
        long ratioDen = 1L;
        boolean anyUpstreamNeed = false;
        for (GenericStack input : whole.boundaryInputs()) {
            if (input == null || input.what() == null || input.amount() <= 0L
                    || upstreamProducedKeys == null || !upstreamProducedKeys.contains(input.what())) {
                continue;
            }
            anyUpstreamNeed = true;
            long available = Math.max(0L, workingUpstreamStock.getOrDefault(input.what(), 0L));
            if (isSmallerRatio(available, input.amount(), ratioNum, ratioDen)) {
                ratioNum = available;
                ratioDen = input.amount();
            }
        }
        if (!anyUpstreamNeed || ratioNum >= ratioDen) {
            deductUpstreamBoundary(workingUpstreamStock, upstreamProducedKeys, whole.boundaryInputs());
            return List.of(unitScale(whole));
        }
        // f&lt;1：不拆批、不放弃聚合——单元化后整段仍是聚合批（单元 × 份数），
        // 材料是否齐全由执行期现料多推自然处理：CPU 有多少料推多少个单元，
        // 到货即推，无需等待全量。f 只用于工作库存扣减记账。
        deductUpstreamBoundary(workingUpstreamStock, upstreamProducedKeys, whole.boundaryInputs());
        return List.of(unitScale(whole));
    }

    /**
     * 单元化（neoecoae maximumSafeBatch 同构）：取全段各步 executionCount
     * 的 gcd 作为「可重复单元」数——steps/boundary/产出全部按 gcd 缩成单元
     * 量，任务表份数 = gcd。执行期宿主多推按现料推 k 个单元（k ≤ gcd），
     * 即「CPU 有多少就推多少做多少」。任一量不能整除或 gcd=1 时保持全量
     * 批（份数 1，执行期等待全量，降级机制兜底）。
     */
    private static BatchedCandidate unitScale(HostAggregationCandidate whole) {
        long gcd = 0L;
        for (HostBatchStep step : whole.steps()) {
            gcd = gcd(gcd, step.executionCount());
        }
        if (gcd <= 1L) {
            return new BatchedCandidate(whole, 1L);
        }
        List<HostBatchStep> unitSteps = new ArrayList<>(whole.steps().size());
        for (HostBatchStep step : whole.steps()) {
            Map<AEKey, Long> unitInputs = downscaleStacks(step.stepInputs(), gcd);
            Map<AEKey, Long> unitRemainders = downscaleStacks(step.stepRemainders(), gcd);
            long unitPrimary = step.stepPrimaryOutput() == null
                    ? 0L
                    : downscaleAmount(step.stepPrimaryOutput().amount(), gcd);
            if (unitInputs == null || unitRemainders == null || unitPrimary <= 0L) {
                return new BatchedCandidate(whole, 1L);
            }
            unitSteps.add(new HostBatchStep(
                    step.patternDefinition(),
                    step.executionCount() / gcd,
                    toGenericStacks(unitInputs),
                    new GenericStack(step.stepPrimaryOutput().what(), unitPrimary),
                    toGenericStacks(unitRemainders)
            ));
        }
        Map<AEKey, Long> unitBoundary = downscaleStacks(whole.boundaryInputs(), gcd);
        Map<AEKey, Long> unitOutputs = downscaleStacks(whole.aggregatedOutputs(), gcd);
        Map<AEKey, Long> unitRemainders = downscaleStacks(whole.aggregatedRemainders(), gcd);
        if (unitBoundary == null || unitOutputs == null || unitRemainders == null
                || unitOutputs.isEmpty()) {
            return new BatchedCandidate(whole, 1L);
        }
        return new BatchedCandidate(
                new HostAggregationCandidate(
                        whole.host(),
                        whole.basePattern(),
                        toGenericStacks(unitBoundary),
                        whole.externalMissingInputs(),
                        toGenericStacks(unitOutputs),
                        toGenericStacks(unitRemainders),
                        List.copyOf(unitSteps),
                        whole.totalTicks(),
                        whole.originalPatterns()
                ),
                gcd
        );
    }

    private static long gcd(long left, long right) {
        long a = Math.abs(left);
        long b = Math.abs(right);
        while (b != 0L) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** a/b &lt; num/den 的精确比较（宽域乘积，无溢出无精度损失）。 */
    private static boolean isSmallerRatio(long a, long b, long num, long den) {
        if (b <= 0L || den <= 0L) {
            return false;
        }
        return WideMath.mul(a, den).compareTo(WideMath.mul(num, b)) < 0;
    }

    /** 从工作库存扣减边界输入里的上游键（现成部分已被本段占用）。 */
    private static void deductUpstreamBoundary(
            Map<AEKey, Long> workingUpstreamStock,
            @Nullable Set<AEKey> upstreamProducedKeys,
            List<GenericStack> boundaryInputs
    ) {
        if (upstreamProducedKeys == null || boundaryInputs == null) {
            return;
        }
        for (GenericStack input : boundaryInputs) {
            if (input == null || input.what() == null || input.amount() <= 0L
                    || !upstreamProducedKeys.contains(input.what())) {
                continue;
            }
            long remaining = workingUpstreamStock.getOrDefault(input.what(), 0L) - input.amount();
            workingUpstreamStock.put(input.what(), Math.max(0L, remaining));
        }
    }

    /**
     * 单批余料仿真（X5 缓存点）：{@code getRemainingItems} 是链聚合最贵的
     * 单次操作，按样板身份挂 calculation 生命周期共享。
     */
    private static @Nullable Map<AEItemKey, Long> computeRemaindersCached(
            Level level,
            HostBatchPlanningContext rewriteContext,
            IPatternDetails pattern
    ) {
        var cache = rewriteContext.planningCache();
        if (cache == null) {
            return HostBatchRemainders.computeSingleRunRemainders(level, pattern);
        }
        return cache.remainders(pattern, key -> {
            var remainders = HostBatchRemainders.computeSingleRunRemainders(level, key);
            return remainders == null ? Map.<AEItemKey, Long>of() : remainders;
        });
    }

    /**
     * 按依赖弱连通分量分段：同一宿主下有输入输出关联的节点并成一段，一次聚合成一批；
     * 无关联的孤立节点各自成段。链式合成的中间产物在段内闭环，执行时无需跨段等待。
     *
     * <p>此前是每输出键一单段——链被切碎成小批量串行，是"只在小批量跑"的根因。
     * 环形等待的段由下游 {@code topoSortHostNodes} 返回 null 自然落选（不聚合、
     * 不卡住），满足"只要不会等待就一次性聚合"。
     */
    static List<Set<AEKey>> splitPerPatternFormalAggregationSegments(
            Map<AEKey, ? extends Collection<AEKey>> formalInputsByOutput
    ) {
        if (formalInputsByOutput == null || formalInputsByOutput.isEmpty()) {
            return List.of();
        }
        // 并查集：输入必须是另一已选样板的完整输出键，才构成段内依赖。
        Map<AEKey, AEKey> parent = new LinkedHashMap<>();
        for (AEKey output : formalInputsByOutput.keySet()) {
            if (output != null) {
                parent.putIfAbsent(output, output);
            }
        }
        for (Map.Entry<AEKey, ? extends Collection<AEKey>> entry : formalInputsByOutput.entrySet()) {
            AEKey output = entry.getKey();
            if (output == null) {
                continue;
            }
            Collection<AEKey> inputs = entry.getValue();
            if (inputs == null) {
                continue;
            }
            for (AEKey input : inputs) {
                if (input != null && parent.containsKey(input)) {
                    union(parent, output, input);
                }
            }
        }
        Map<AEKey, Set<AEKey>> segmentsByRoot = new LinkedHashMap<>();
        for (AEKey output : formalInputsByOutput.keySet()) {
            if (output == null) {
                continue;
            }
            segmentsByRoot.computeIfAbsent(find(parent, output), ignored -> new LinkedHashSet<>()).add(output);
        }
        List<Set<AEKey>> segments = new ArrayList<>(segmentsByRoot.size());
        for (Set<AEKey> segment : segmentsByRoot.values()) {
            segments.add(Set.copyOf(segment));
        }
        return List.copyOf(segments);
    }

    private static AEKey find(Map<AEKey, AEKey> parent, AEKey key) {
        AEKey root = key;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        // 路径压缩
        while (!parent.get(key).equals(root)) {
            AEKey next = parent.get(key);
            parent.put(key, root);
            key = next;
        }
        return root;
    }

    private static void union(Map<AEKey, AEKey> parent, AEKey left, AEKey right) {
        AEKey leftRoot = find(parent, left);
        AEKey rightRoot = find(parent, right);
        if (!leftRoot.equals(rightRoot)) {
            parent.put(leftRoot, rightRoot);
        }
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
            HostBatchPlanningContext rewriteContext,
            HostBatchProvider host,
            Map<AEKey, SelectedGraphNode> hostNodes,
            @Nullable Set<AEKey> stockSuppliedKeys
    ) {
        List<SelectedGraphNode> executionOrder = topoSortHostNodes(hostNodes, stockSuppliedKeys);
        if (executionOrder == null || executionOrder.isEmpty()) {
            var stats = failures();
            stats.topoNull++;
            if (stats.topoExample == null && !hostNodes.isEmpty()) {
                stats.topoExample = hostNodes.keySet().iterator().next();
            }
            LOGGER.warn("buildHostAggregationCandidate: executionOrder is null/empty");
            return null;
        }

        Map<AEKey, Long> totalInputs = new LinkedHashMap<>();
        Map<AEKey, Long> totalPrimaryOutputs = new LinkedHashMap<>();
        Map<AEKey, Long> totalRemainders = new LinkedHashMap<>();
        LinkedHashSet<IPatternDetails> originalPatterns = new LinkedHashSet<>();
        List<HostBatchStep> steps = new ArrayList<>(executionOrder.size());

        for (SelectedGraphNode node : executionOrder) {
            ItemStack patternDefinition = node.pattern().getDefinition().toStack();
            if (patternDefinition.isEmpty()) {
                failures().defEmpty++;
                LOGGER.warn("buildHostAggregationCandidate: pattern definition is empty for ", node.outputKey());
                return null;
            }

            Map<AEItemKey, Long> singleRunRemainders = computeRemaindersCached(
                    level, rewriteContext, node.pattern());
            if (singleRunRemainders == null) {
                var stats = failures();
                stats.remainderNull++;
                if (stats.remainderExample == null) {
                    stats.remainderExample = node.outputKey();
                }
                LOGGER.warn("buildHostAggregationCandidate: remainder computation failed for {}", node.outputKey());
                return null;
            }

            List<GenericStack> stepInputs = toScaledStacks(node.inputs(), node.craftCount());
            long primaryAmount = multiply(node.outputAmount(), node.craftCount());
            if (primaryAmount <= 0L) {
                failures().primaryNonPositive++;
                LOGGER.warn("buildHostAggregationCandidate: primaryAmount <= 0 for {}", node.outputKey());
                return null;
            }
            List<GenericStack> stepRemainders = toScaledStacks(singleRunRemainders, node.craftCount());

            steps.add(new HostBatchStep(
                    patternDefinition,
                    node.craftCount(),
                    stepInputs,
                    new GenericStack(node.outputKey(), primaryAmount),
                    stepRemainders
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

        KeyCounter recursiveInitialItems = filterCandidateRecursiveInitialItems(
                rewriteContext.recursiveInitialItems(), totalProduced);

        Map<AEKey, Long> boundaryInputs = describeAggregatedBoundaryInputs(steps);
        if (boundaryInputs == null) {
            failures().boundaryInputsNull++;
            LOGGER.warn("buildHostAggregationCandidate: boundaryInputs is null");
            return null;
        }
        // crafts 层替换（Phase 4）：missing 全部取 AE2 原值（模拟账本对聚合
        // 前后的净输入天然一致），不再做候选级 missing 调整。
        List<GenericStack> externalMissingInputs = List.of();
        Map<AEKey, Long> boundaryOutputs = subtractPositive(totalProduced, totalInputs);
        restoreRecursiveInitialBoundaryOutputs(boundaryOutputs, recursiveInitialItems);
        if (boundaryOutputs.isEmpty()) {
            var stats = failures();
            stats.boundaryOutputsEmpty++;
            if (stats.boundaryExample == null && !hostNodes.isEmpty()) {
                stats.boundaryExample = hostNodes.keySet().iterator().next();
            }
            LOGGER.warn("buildHostAggregationCandidate: boundaryOutputs is empty");
            return null;
        }

        SplitBoundaryOutputs splitOutputs = splitBoundaryOutputs(
                rewriteContext.finalOutput(),
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
            failures().splitOutputsEmpty++;
            LOGGER.warn("buildHostAggregationCandidate: splitOutputs.outputs() is empty");
            return null;
        }

        IPatternDetails basePattern = chooseBasePattern(rewriteContext.finalOutput(), hostNodes, executionOrder);
        if (basePattern == null) {
            failures().basePatternNull++;
            LOGGER.warn("buildHostAggregationCandidate: basePattern is null");
            return null;
        }

        return new HostAggregationCandidate(
                host,
                basePattern,
                toGenericStacks(boundaryInputs),
                List.copyOf(externalMissingInputs),
                splitOutputs.outputs(),
                splitOutputs.remainders(),
                List.copyOf(steps),
                saturatingIntMultiply(host.getCurrentOperationTicks(), Math.max(1, steps.size())),
                List.copyOf(originalPatterns.stream().toList())
        );
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

    private static @Nullable IPatternDetails chooseBasePattern(
            @Nullable GenericStack finalOutput,
            Map<AEKey, SelectedGraphNode> hostNodes,
            List<SelectedGraphNode> executionOrder
    ) {
        if (finalOutput != null && finalOutput.what() != null) {
            SelectedGraphNode finalNode = hostNodes.get(finalOutput.what());
            IPatternDetails pattern = finalNode == null ? null : unwrapBasePattern(finalNode.pattern());
            if (pattern != null) {
                return pattern;
            }
        }

        // Reachable for cyclic dependency segments too; the pick is arbitrary but deterministic.
        List<SelectedGraphNode> reversedOrder = new ArrayList<>(executionOrder);
        Collections.reverse(reversedOrder);
        for (SelectedGraphNode node : reversedOrder) {
            IPatternDetails pattern = unwrapBasePattern(node.pattern());
            if (pattern != null) {
                return pattern;
            }
        }
        return null;
    }

    static List<AEKey> topoSortDependencyOutputs(
            Map<AEKey, ? extends Collection<AEKey>> inputsByOutput
    ) {
        if (inputsByOutput == null || inputsByOutput.isEmpty()) {
            return List.of();
        }
        return DependencyGraphs.orderDependencyFirst(inputsByOutput.keySet(), inputsByOutput::get);
    }

    private static @Nullable List<SelectedGraphNode> topoSortHostNodes(
            Map<AEKey, SelectedGraphNode> hostNodes,
            @Nullable Set<AEKey> stockSuppliedKeys
    ) {
        Map<AEKey, ? extends Collection<AEKey>> inputsByOutput = formalInputsByOutput(hostNodes);
        if (stockSuppliedKeys != null && !stockSuppliedKeys.isEmpty()) {
            // 库存切环：催化物键视作外部供给，砍掉它承载的依赖边——
            // 环在拓扑上被库存打断，消费端按种子记账（boundary 输入）。
            Map<AEKey, Collection<AEKey>> cut = new LinkedHashMap<>();
            for (Map.Entry<AEKey, ? extends Collection<AEKey>> entry : inputsByOutput.entrySet()) {
                List<AEKey> kept = new ArrayList<>();
                for (AEKey input : entry.getValue()) {
                    if (input == null || !stockSuppliedKeys.contains(input)) {
                        kept.add(input);
                    }
                }
                cut.put(entry.getKey(), kept);
            }
            inputsByOutput = cut;
        }
        List<AEKey> orderedOutputs = topoSortDependencyOutputs(inputsByOutput);
        if (orderedOutputs.size() != hostNodes.size()) {
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

    static @Nullable Map<AEKey, Long> describeAggregatedBoundaryInputs(
            @Nullable List<HostBatchStep> steps
    ) {
        if (steps == null || steps.isEmpty()) {
            return Map.of();
        }

        Map<AEKey, Long> requiredExternalInputs = new LinkedHashMap<>();
        Map<AEKey, Long> availableInternalInputs = new LinkedHashMap<>();
        for (HostBatchStep step : steps) {
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
            @Nullable Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            @Nullable IPatternDetails pattern
    ) {
        Map<AEKey, Long> selectedInputs = describeSelectedPatternInputs(selectedPatternInputs, pattern);
        if (selectedInputs != null) {
            return selectedInputs;
        }
        return describePatternInputs(pattern);
    }

    private static @Nullable Map<AEKey, Long> describeSelectedPatternInputs(
            @Nullable Map<AEItemKey, Map<AEKey, Long>> selectedPatternInputs,
            @Nullable IPatternDetails pattern
    ) {
        if (selectedPatternInputs == null
                || selectedPatternInputs.isEmpty()
                || pattern == null
                || pattern.getDefinition() == null
                || !(pattern.getDefinition() instanceof AEItemKey definition)) {
            return null;
        }
        Map<AEKey, Long> selected = selectedPatternInputs.get(definition);
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        return Map.copyOf(new LinkedHashMap<>(selected));
    }

    static @Nullable IPatternDetails unwrapBasePattern(@Nullable IPatternDetails pattern) {
        return unwrapDelegatingPattern(pattern);
    }

    static @Nullable AECraftingPattern unwrapBaseCraftingPattern(@Nullable IPatternDetails pattern) {
        IPatternDetails unwrapped = unwrapDelegatingPattern(pattern);
        return unwrapped instanceof AECraftingPattern craftingPattern ? craftingPattern : null;
    }

    private static @Nullable IPatternDetails unwrapDelegatingPattern(@Nullable IPatternDetails pattern) {
        if (pattern instanceof DelegatingPatternDetails delegatingPattern) {
            return delegatingPattern.basePattern();
        }
        return pattern;
    }

    private static @Nullable Map<AEKey, Long> describePatternInputs(IPatternDetails pattern) {
        if (pattern instanceof HostBatchPattern aggregatedPattern) {
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
            HostBatchPattern pattern
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

    static void mergeStack(Map<AEKey, Long> target, AEKey key, long amount) {
        if (target == null || key == null || amount <= 0L) {
            return;
        }
        target.merge(key, amount, HostChainSegmenter::saturatingAdd);
    }

    private static void mergeStackMaps(Map<AEKey, Long> target, Map<AEKey, Long> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<AEKey, Long> entry : source.entrySet()) {
            mergeStack(target, entry.getKey(), Math.max(0L, entry.getValue() == null ? 0L : entry.getValue()));
        }
    }

    static Map<AEKey, Long> subtractPositive(Map<AEKey, Long> left, Map<AEKey, Long> right) {
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

    private static KeyCounter filterCandidateRecursiveInitialItems(
            @Nullable KeyCounter recursiveInitialItemsSource,
            Map<AEKey, Long> candidateProducedKeys
    ) {
        KeyCounter filtered = new KeyCounter();
        if (candidateProducedKeys == null || candidateProducedKeys.isEmpty()) {
            return filtered;
        }
        KeyCounter recursiveInitialItems = recursiveInitialItemsSource == null
                ? new KeyCounter()
                : recursiveInitialItemsSource;
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

    private static void restoreRecursiveInitialBoundaryOutputs(
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


    static long multiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        return WideMath.wideMul(left, right);
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
        PlanAmount laterRuns = WideMath.mul(executionCount - 1L, consumePerRun - producePerRun);
        return Ae2Amounts.toLong(laterRuns.add(PlanAmount.of(consumePerRun)));
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
            PlanAmount produced = WideMath.mul(executionCount, producePerRun - consumePerRun);
            return Ae2Amounts.toLong(produced.add(PlanAmount.of(startingAmount)));
        }
        PlanAmount consumed = WideMath.mul(executionCount, consumePerRun - producePerRun);
        return Ae2Amounts.toLong(
                PlanAmount.of(startingAmount).subtract(consumed).max(PlanAmount.ZERO)
        );
    }

    static long saturatingAdd(long left, long right) {
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

    record HostAggregationCandidate(
            HostBatchProvider host,
            IPatternDetails basePattern,
            List<GenericStack> boundaryInputs,
            List<GenericStack> externalMissingInputs,
            List<GenericStack> aggregatedOutputs,
            List<GenericStack> aggregatedRemainders,
            List<HostBatchStep> steps,
            int totalTicks,
            List<IPatternDetails> originalPatterns
    ) {
    }

    record SelectedPlanGraph(Map<AEKey, SelectedGraphNode> nodes) {
    }

    record SelectedGraphNode(
            PatternDefinitionKey definitionKey,
            IPatternDetails pattern,
            long craftCount,
            AEKey outputKey,
            long outputAmount,
            Map<AEKey, Long> inputs,
            @Nullable HostBatchProvider host
    ) {
    }

    private record EquivalentSelectedNodeKey(
            @Nullable HostBatchProvider host,
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

    record PatternDefinitionKey(AEItemKey definition) {
        static @Nullable PatternDefinitionKey of(@Nullable IPatternDetails pattern) {
            if (pattern == null || pattern.getDefinition() == null) {
                return null;
            }
            return new PatternDefinitionKey(pattern.getDefinition());
        }
    }
}
