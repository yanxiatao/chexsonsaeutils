package git.chexson.chexsonsaeutils.crafting.graphplan.convert;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveRingPlanner;
import git.chexson.chexsonsaeutils.crafting.recursive.RingScale;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;

/**
 * Level-1 post-processing replicating the tree planner's
 * {@code retainRecursiveCatalysts} on the solve ledger: every recursive ring
 * seed whose projected network amount after the plan falls below one copy is
 * replenished by firing its retaining ring in closed form.
 *
 * <p>The main solve has already proved a valid ring startup and recorded the
 * actual network seed set in {@code ringExtractions}. Supplemental cycles reuse
 * that set; other overlap keys may be produced by the ring itself and must not
 * be promoted to independent network seeds.
 *
 * <p>Ring net inputs pull from the sandbox and reuse already-firing plan
 * producers ({@code addCrafting + addBytes(times)}, input demands pushed as
 * pending requests); execution ratios and net outputs unpack with zero bytes.
 * After each supplemented catalyst the internal reserve
 * ({@code recursiveInternalItems}) is recorded with max-merge.
 */
public final class RetentionApplier {

    private static final long RETAINED_SEED_COUNT = 1L;

    private RetentionApplier() {
    }

    /**
     * Applies retention to a successful recursive solve ledger. Returns
     * {@code null} on success or a decline reason when a required expansion has
     * no close-form equivalent.
     */
    @Nullable
    public static String apply(GraphPlanningState state, CraftGraph graph, long finalAmount) {
        if (state.ringExtractions().isEmpty()) {
            return null;
        }

        Map<AEKey, CompressedRing> retainingRings = graph.retainingRingsByCatalyst();
        Set<AEKey> candidateKeys = collectCandidates(state, retainingRings.keySet());
        if (candidateKeys.isEmpty()) {
            return null;
        }
        Map<IPatternDetails, Long> crafts = state.crafts();
        // selectedInputs / 每键产出量只依赖 graph 的编译期数据，apply 期间不变；
        // 同一 producer 的重复 demand 之前是全额重算（新建 HashMap + 线性扫产出）。
        var selectedInputsMemo = new java.util.IdentityHashMap<IPatternDetails, Map<AEKey, Long>>();
        var outputAmountMemo = new java.util.IdentityHashMap<IPatternDetails, java.util.Map<AEKey, Long>>();
        Map<AEKey, IPatternDetails> plannedProducers = null;
        for (AEKey candidateKey : candidateKeys) {
            CompressedRing retainingRing = retainingRings.get(candidateKey);
            if (!RecursiveRingPlanner.isCompressedRingCalculable(retainingRing)) {
                continue;
            }
            long seedAmount = retainingRing.catalysts().get(candidateKey);
            if (seedAmount <= 0L) {
                return "retaining ring without a positive seed amount for " + candidateKey;
            }
            long retainedAmount = WideMath.wideMul(RETAINED_SEED_COUNT, seedAmount);
            long projectedAmount = PlanFlow.projectedNetworkAmount(crafts, graph,
                    graph.target(), finalAmount, candidateKey);
            long deficit = retainedAmount - projectedAmount;
            if (deficit <= 0L) {
                continue;
            }
            if (plannedProducers == null) {
                // 延迟构建：全部候选 deficit<=0 时（绝大多数请求）整段省掉两遍 crafts 扫描。
                // 首次使用点之前 crafts 未被本方法改动，构建结果与提前构建完全相同。
                plannedProducers = buildPlannedProducers(state.crafts(), graph);
            }
            String decline = applySupplementalRing(state, graph, retainingRing, candidateKey,
                    deficit, plannedProducers, selectedInputsMemo, outputAmountMemo);
            if (decline != null) {
                return decline;
            }
            // 补种已把新增 crafts 写进账本；下一候选的投影直接扫最新账本，
            // 无需重建（PlanAmount 加法精确可结合，逐 craft 直加与先聚合再查同值）。
            long internalReserve = retainedAmount - networkAmountNotExtractedByPlan(state, graph, candidateKey);
            if (internalReserve > 0L) {
                state.recordInternalItem(candidateKey, internalReserve);
            }
        }
        return null;
    }

    /**
     * 实际从网络提取或作为环种子预留、且属于 {@code allowed} 的键。
     * 环内样板输入若由同一计划产出，不属于网络催化物，不能因出现在 crafts 中
     * 就被当成新的初始种子；否则可由 B 启动的 A/B 环会错误地同时要求库存 A。
     */
    private static Set<AEKey> collectCandidates(GraphPlanningState state, @Nullable Set<AEKey> allowed) {
        if (allowed != null && allowed.isEmpty()) {
            return Set.of();
        }
        Set<AEKey> keys = new it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<>();
        for (AEKey key : state.ringExtractions().keys()) {
            if (allowed == null || allowed.contains(key)) {
                keys.add(key);
            }
        }
        state.used().forEachPresent((key, narrowed) -> {
            if (narrowed > 0L && (allowed == null || allowed.contains(key))) {
                keys.add(key);
            }
        });
        return keys;
    }

    /**
     * Main-plan producer mapping (main-output first), verbatim from the tree's
     * {@code buildPlannedProducers}.
     */
    private static Map<AEKey, IPatternDetails> buildPlannedProducers(Map<IPatternDetails, Long> crafts,
            CraftGraph graph) {
        Map<AEKey, IPatternDetails> producers = new HashMap<>();
        var patterns = new java.util.ArrayList<IPatternDetails>();
        Set<IPatternDetails> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (IPatternDetails pattern : graph.patternRegistrationOrder()) {
            Long times = crafts.get(pattern);
            if (times != null && times > 0L && seen.add(pattern)) {
                patterns.add(pattern);
            }
        }
        for (var entry : crafts.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            Long times = entry.getValue();
            if (pattern != null && times != null && times > 0L && !seen.contains(pattern)) {
                throw new IllegalStateException("planned pattern missing from registration order: "
                        + pattern.getDefinition());
            }
        }
        // 两趟都读编译期固化的产出表（图上没有的样板才回落 raw getOutputs）：
        // 真实实现的 getOutputs 可能每次都新建列表，而这里要走两趟。
        for (IPatternDetails pattern : patterns) {
            var outputs = outputsOf(pattern, graph);
            if (!outputs.isEmpty() && outputs.get(0) != null && outputs.get(0).what() != null
                    && outputs.get(0).amount() > 0L) {
                producers.putIfAbsent(outputs.get(0).what(), pattern);
            }
        }
        for (IPatternDetails pattern : patterns) {
            for (GenericStack output : outputsOf(pattern, graph)) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    producers.putIfAbsent(output.what(), pattern);
                }
            }
        }
        return producers;
    }

    /** 样板产出表：编译期固化的优先，图上没有该样板时回落 raw 调用。 */
    private static java.util.List<GenericStack> outputsOf(IPatternDetails pattern, CraftGraph graph) {
        var compiled = graph.patternsBySource().get(pattern);
        if (compiled != null) {
            return compiled.allOutputs();
        }
        var outputs = pattern.getOutputs();
        return outputs == null ? java.util.List.of() : outputs;
    }

    private static long networkAmountNotExtractedByPlan(GraphPlanningState state, CraftGraph graph, AEKey key) {
        long available = graph.stock().getOrDefault(key, 0L);
        return Math.max(0L, available - state.usedAmount(key));
    }

    private static String applySupplementalRing(GraphPlanningState state, CraftGraph graph,
            CompressedRing ring, AEKey retainedKey, long deficit,
            Map<AEKey, IPatternDetails> plannedProducers,
            Map<IPatternDetails, Map<AEKey, Long>> selectedInputsMemo,
            Map<IPatternDetails, Map<AEKey, Long>> outputAmountMemo) {
        long netOutput = ring.netOutputs().get(retainedKey);
        if (netOutput <= 0L) {
            return null;
        }

        RingScale scale = RingScale.of(deficit, netOutput);
        if (!hasExecutableStartup(state, graph, ring)) {
            return "retention ring has no executable startup seed set for " + retainedKey;
        }
        String decline = expandRingNetInputs(state, graph, ring, scale, plannedProducers,
                selectedInputsMemo, outputAmountMemo);
        if (decline != null) {
            return decline;
        }
        unpackRingOperations(state, ring, scale);
        return null;
    }

    private static boolean hasExecutableStartup(
            GraphPlanningState state,
            CraftGraph graph,
            CompressedRing ring
    ) {
        Map<AEKey, BigInteger> available = new HashMap<>();
        for (AEKey key : state.ringExtractions().keys()) {
            PlanAmount amount = state.ringExtractions().getAmount(key);
            if (amount.signum() > 0) {
                available.put(key, amount.exactValue());
            }
        }
        for (var input : ring.netInputs()) {
            if (input.getKey() != null && input.getLongValue() > 0L) {
                available.merge(input.getKey(), BigInteger.valueOf(input.getLongValue()), BigInteger::add);
            }
        }
        if (available.isEmpty()) {
            return false;
        }

        Map<IPatternDetails, Long> remaining = new IdentityHashMap<>();
        ring.executionRatio().forEach((pattern, times) -> {
            if (pattern != null && times != null && times > 0L) {
                remaining.put(pattern, times);
            }
        });
        var ordered = new java.util.ArrayList<>(ring.executionRatio().keySet());

        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (IPatternDetails pattern : ordered) {
                long remainingTimes = remaining.getOrDefault(pattern, 0L);
                if (remainingTimes <= 0L) {
                    continue;
                }
                Map<AEKey, BigInteger> required = ringInputsOf(pattern, ring);
                long firingTimes = fireableTimes(available, required, remainingTimes);
                if (firingTimes <= 0L) {
                    continue;
                }
                BigInteger firingMultiplier = BigInteger.valueOf(firingTimes);
                required.forEach((key, amount) -> available.merge(
                        key, amount.multiply(firingMultiplier).negate(), BigInteger::add));
                for (GenericStack output : outputsOf(pattern, graph)) {
                    if (output != null && output.what() != null && output.amount() > 0L) {
                        BigInteger produced = BigInteger.valueOf(output.amount()).multiply(firingMultiplier);
                        available.merge(output.what(), produced, BigInteger::add);
                    }
                }
                if (firingTimes == remainingTimes) {
                    remaining.remove(pattern);
                } else {
                    remaining.put(pattern, remainingTimes - firingTimes);
                }
                progressed = true;
                break;
            }
            if (!progressed) {
                return false;
            }
        }
        return true;
    }

    private static long fireableTimes(
            Map<AEKey, BigInteger> available,
            Map<AEKey, BigInteger> required,
            long remainingTimes
    ) {
        long result = remainingTimes;
        for (var entry : required.entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            BigInteger possible = available.getOrDefault(entry.getKey(), BigInteger.ZERO)
                    .divide(entry.getValue());
            if (possible.signum() <= 0) {
                return 0L;
            }
            if (possible.bitLength() < 63) {
                result = Math.min(result, possible.longValue());
            }
        }
        return result;
    }

    private static Map<AEKey, BigInteger> ringInputsOf(IPatternDetails pattern, CompressedRing ring) {
        Map<AEKey, BigInteger> required = new HashMap<>();
        var memberOutputs = new HashSet<AEKey>();
        for (IPatternDetails member : ring.executionRatio().keySet()) {
            for (GenericStack output : member.getOutputs()) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    memberOutputs.add(output.what());
                }
            }
        }
        for (var input : pattern.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            GenericStack selected = null;
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null && candidate.amount() > 0L
                        && memberOutputs.contains(candidate.what())) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                for (GenericStack candidate : input.getPossibleInputs()) {
                    if (candidate != null && candidate.what() != null && candidate.amount() > 0L) {
                        selected = candidate;
                        break;
                    }
                }
            }
            if (selected == null || input.getRemainingKey(selected.what()) != null) {
                continue;
            }
            BigInteger amount = BigInteger.valueOf(selected.amount())
                    .multiply(BigInteger.valueOf(input.getMultiplier()));
            if (amount.signum() > 0) {
                required.merge(selected.what(), amount, BigInteger::add);
            }
        }
        return required;
    }

    private static String expandRingNetInputs(GraphPlanningState state, CraftGraph graph,
            CompressedRing ring, RingScale scale, Map<AEKey, IPatternDetails> plannedProducers,
            Map<IPatternDetails, Map<AEKey, Long>> selectedInputsMemo,
            Map<IPatternDetails, Map<AEKey, Long>> outputAmountMemo) {
        Deque<PendingDemand> pending = new ArrayDeque<>();
        for (var stack : ring.netInputs()) {
            if (stack.getKey() == null) {
                continue;
            }
            long required = scale.ceilScaled(stack.getLongValue());
            if (required > 0L) {
                pending.add(new PendingDemand(stack.getKey(), required));
            }
        }

        while (!pending.isEmpty()) {
            PendingDemand demand = pending.poll();
            AEKey key = demand.key();
            long amount = demand.amount();
            long fulfilled = state.extract(key, amount, false);
            long remaining = amount - fulfilled;
            if (remaining <= 0L) {
                continue;
            }

            IPatternDetails producer = plannedProducers.get(key);
            var selectedInputs = selectedInputsFor(graph, producer, selectedInputsMemo);
            long outputPerCraft = producer == null ? 0L
                    : plannedOutputAmount(producer, graph, key, outputAmountMemo);
            if (producer == null
                    || ring.netOutputs().get(key) > 0L
                    || outputPerCraft <= 0L
                    || selectedInputs == null
                    || selectedInputs.isEmpty()) {
                return "retention net input " + key + " has no reusable plan path";
            }

            long times = remaining / outputPerCraft + (remaining % outputPerCraft == 0L ? 0L : 1L);
            long produced = WideMath.wideMul(outputPerCraft, times);
            state.addCrafting(producer, times);
            state.addBytes(times);
            state.insert(key, produced);
            long paid = state.extract(key, remaining, false);
            if (paid < remaining) {
                return "retention reuse path under-produced for " + key;
            }
            for (var entry : selectedInputs.entrySet()) {
                long inputAmount = WideMath.wideMul(entry.getValue(), times);
                pending.add(new PendingDemand(entry.getKey(), inputAmount));
            }
        }
        return null;
    }

    /**
     * Per-craft input selection of an already-firing plan pattern, computed
     * from the compiled graph sites exactly like the plan converter's
     * {@code buildSelectedPatternInputs} (the tree planner's
     * {@code selectedPatternInputs} lookup). Ring-macro execution-ratio
     * patterns have no compiled sites — the tree planner doesn't record them
     * either, so the reuse path declines to a fresh subtree there.
     */
    @Nullable
    private static Map<AEKey, Long> selectedInputsFor(CraftGraph graph, @Nullable IPatternDetails producer,
            Map<IPatternDetails, Map<AEKey, Long>> memo) {
        var cached = memo.get(producer);
        if (cached != null || (producer != null && memo.containsKey(producer))) {
            return cached;
        }
        var computed = selectedInputsForInner(graph, producer);
        if (producer != null) {
            memo.put(producer, computed);
        }
        return computed;
    }

    private static Map<AEKey, Long> selectedInputsForInner(CraftGraph graph,
            @Nullable IPatternDetails producer) {
        if (producer == null || producer.getDefinition() == null
                || !(producer.getDefinition() instanceof appeng.api.stacks.AEItemKey definition)) {
            return null;
        }
        var compiled = graph.patternsBySource().get(producer);
        if (compiled == null) {
            return null;
        }
        Map<AEKey, Long> selected = new HashMap<>();
        for (var input : compiled.inputs()) {
            long amount = WideMath.wideMul(input.amountPerCraft(), input.multiplier());
            if (input.key() != null && amount > 0) {
                selected.merge(input.key(), amount, Long::sum);
            }
        }
        return selected;
    }

    private static long plannedOutputAmount(IPatternDetails producer, CraftGraph graph, AEKey key,
            Map<IPatternDetails, Map<AEKey, Long>> memo) {
        var cached = memo.get(producer);
        if (cached == null) {
            cached = new HashMap<>();
            for (GenericStack output : outputsOf(producer, graph)) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    cached.putIfAbsent(output.what(), output.amount());
                }
            }
            memo.put(producer, cached);
        }
        Long amount = cached.get(key);
        return amount == null ? 0L : amount;
    }

    private static void unpackRingOperations(GraphPlanningState state, CompressedRing ring,
            RingScale scale) {
        for (var entry : ring.executionRatio().entrySet()) {
            long times = scale.ceilScaled(entry.getValue());
            if (times > 0L) {
                state.addCrafting(entry.getKey(), times);
            }
        }

        for (var stack : ring.netOutputs()) {
            long produced = scale.floorScaled(stack.getLongValue());
            if (produced > 0L) {
                state.insert(stack.getKey(), produced);
            }
        }
    }

    private record PendingDemand(AEKey key, long amount) {
    }

    /**
     * 计划流量的按需单键投影。原实现先对全部 crafts 聚合出 (inputsByKey,
     * outputsByKey) 两张 map，每个实际补种的候选后还要整份重建一次——每次约
     * 1500-2500 次 merge、5-10k 个临时对象。而查询端只读「某一个键」的净供给，
     * 所以直接对 crafts 扫一遍只累加该键的贡献：候选数个位数，每次投影一趟
     * crafts、零中间 map。
     *
     * <p>口径与原聚合分支逐位相同：编译期固化的输入/产出表优先；多候选槽按
     * 去重后的候选各记一份需求；finalOutput 从净供给中扣除；PlanAmount 加法
     * 精确可结合，逐 craft 直加与先聚合再查数值相同。
     */
    private static final class PlanFlow {

        private PlanFlow() {
        }

        static long projectedNetworkAmount(Map<IPatternDetails, Long> crafts, CraftGraph graph,
                AEKey target, long finalAmount, AEKey key) {
            // long 快路径：单键流量在 long 域内精确时零分配；溢出回落宽路径。
            // 饱和只在单键流量超 9e18 时触发（实机总量 1e12 级），常态逐位一致。
            long projected = graph.stock().getOrDefault(key, 0L);
            boolean wide = false;
            PlanAmount wideProjected = null;
            for (var entry : crafts.entrySet()) {
                IPatternDetails details = entry.getKey();
                long times = entry.getValue();
                if (details == null || times <= 0L) {
                    continue;
                }
                var compiled = graph.patternsBySource().get(details);
                if (wide) {
                    wideProjected = compiled != null
                            ? accumulateKeyCompiled(compiled, times, key, wideProjected)
                            : accumulateKeyRaw(details, times, key, wideProjected);
                    continue;
                }
                long next = compiled != null
                        ? accumulateKeyCompiledLong(compiled, times, key, projected)
                        : accumulateKeyRawLong(details, times, key, projected);
                if (next == Long.MIN_VALUE) {
                    wide = true;
                    wideProjected = compiled != null
                            ? accumulateKeyCompiled(compiled, times, key, PlanAmount.of(projected))
                            : accumulateKeyRaw(details, times, key, PlanAmount.of(projected));
                } else {
                    projected = next;
                }
            }
            if (wide) {
                if (key.equals(target)) {
                    wideProjected = wideProjected.subtract(PlanAmount.of(finalAmount));
                }
                return Math.max(0L, Ae2Amounts.toLong(wideProjected));
            }
            if (key.equals(target)) {
                projected -= finalAmount;
            }
            return Math.max(0L, projected);
        }

        /** long 快路径累加；溢出返回 {@link Long#MIN_VALUE} 哨兵。 */
        private static long accumulateKeyCompiledLong(
                git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern compiled, long times,
                AEKey key, long projected) {
            for (var input : compiled.inputs()) {
                if (input.extractCandidates() == null) {
                    if (input.key().equals(key)) {
                        long delta = mul3(input.amountPerCraft(), input.multiplier(), times);
                        if (delta == Long.MIN_VALUE && !(input.amountPerCraft() == 0
                                || input.multiplier() == 0 || times == 0)) {
                            return Long.MIN_VALUE;
                        }
                        projected = subSaturating(projected, delta);
                        if (projected == Long.MIN_VALUE) {
                            return Long.MIN_VALUE;
                        }
                    }
                    continue;
                }
                boolean found = false;
                for (var candidate : input.extractCandidates()) {
                    if (!found && candidate.key().equals(key)) {
                        found = true;
                        long delta = mul3(candidate.amount(), input.multiplier(), times);
                        if (delta == Long.MIN_VALUE && !(candidate.amount() == 0
                                || input.multiplier() == 0 || times == 0)) {
                            return Long.MIN_VALUE;
                        }
                        projected = subSaturating(projected, delta);
                        if (projected == Long.MIN_VALUE) {
                            return Long.MIN_VALUE;
                        }
                    }
                }
            }
            for (var out : compiled.allOutputs()) {
                if (out.what().equals(key)) {
                    long delta = WideMath.wideMul(out.amount(), times);
                    if ((delta == Long.MAX_VALUE || delta == Long.MIN_VALUE) && out.amount() != 0 && times != 0) {
                        return Long.MIN_VALUE;
                    }
                    projected = addSaturating(projected, delta);
                    if (projected == Long.MIN_VALUE) {
                        return Long.MIN_VALUE;
                    }
                }
            }
            return projected;
        }

        /** long 快路径累加（raw 样板路径）；溢出返回 {@link Long#MIN_VALUE} 哨兵。 */
        private static long accumulateKeyRawLong(IPatternDetails details, long times,
                AEKey key, long projected) {
            for (var input : details.getInputs()) {
                if (input == null || input.getPossibleInputs() == null) {
                    continue;
                }
                boolean found = false;
                for (GenericStack possibleInput : input.getPossibleInputs()) {
                    if (possibleInput == null || possibleInput.what() == null) {
                        continue;
                    }
                    if (!found && possibleInput.what().equals(key)) {
                        found = true;
                        long delta = mul3(possibleInput.amount(), input.getMultiplier(), times);
                        if (delta == Long.MIN_VALUE
                                && !(possibleInput.amount() == 0 || input.getMultiplier() == 0 || times == 0)) {
                            return Long.MIN_VALUE;
                        }
                        projected = subSaturating(projected, delta);
                        if (projected == Long.MIN_VALUE) {
                            return Long.MIN_VALUE;
                        }
                    }
                }
            }
            for (GenericStack outputStack : details.getOutputs()) {
                if (outputStack != null && outputStack.what() != null && outputStack.what().equals(key)) {
                    long delta = WideMath.wideMul(outputStack.amount(), times);
                    if ((delta == Long.MAX_VALUE || delta == Long.MIN_VALUE)
                            && outputStack.amount() != 0 && times != 0) {
                        return Long.MIN_VALUE;
                    }
                    projected = addSaturating(projected, delta);
                    if (projected == Long.MIN_VALUE) {
                        return Long.MIN_VALUE;
                    }
                }
            }
            return projected;
        }

        private static long mul3(long a, long b, long c) {
            long ab = WideMath.wideMul(a, b);
            if ((ab == Long.MAX_VALUE || ab == Long.MIN_VALUE) && a != 0 && b != 0) {
                return Long.MIN_VALUE;
            }
            return WideMath.wideMul(ab, c);
        }

        private static long addSaturating(long a, long b) {
            long sum = a + b;
            // 哨兵透传：任一端已是哨兵则整体转宽路径重算
            if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MIN_VALUE : sum;
        }

        private static long subSaturating(long a, long b) {
            if (a == Long.MIN_VALUE || b == Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            long diff = a - b;
            return ((a ^ b) & (a ^ diff)) < 0 ? Long.MIN_VALUE : diff;
        }

        private static PlanAmount accumulateKeyCompiled(
                git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern compiled, long times,
                AEKey key, PlanAmount projected) {
            for (var input : compiled.inputs()) {
                if (input.extractCandidates() == null) {
                    if (input.key().equals(key)) {
                        projected = projected.subtract(WideMath.mul(input.amountPerCraft(), input.multiplier())
                                .multiply(PlanAmount.of(times)));
                    }
                    continue;
                }
                Set<AEKey> seenKeys = new HashSet<>();
                for (var candidate : input.extractCandidates()) {
                    if (candidate.key().equals(key) && seenKeys.add(candidate.key())) {
                        projected = projected.subtract(WideMath.mul(candidate.amount(), input.multiplier())
                                .multiply(PlanAmount.of(times)));
                    }
                }
            }
            for (var out : compiled.allOutputs()) {
                if (out.what().equals(key)) {
                    projected = projected.add(WideMath.mul(out.amount(), times));
                }
            }
            return projected;
        }

        private static PlanAmount accumulateKeyRaw(IPatternDetails details, long times,
                AEKey key, PlanAmount projected) {
            for (var input : details.getInputs()) {
                if (input == null || input.getPossibleInputs() == null) {
                    continue;
                }
                Set<AEKey> seenKeys = new HashSet<>();
                for (GenericStack possibleInput : input.getPossibleInputs()) {
                    if (possibleInput == null || possibleInput.what() == null) {
                        continue;
                    }
                    if (possibleInput.what().equals(key) && seenKeys.add(possibleInput.what())) {
                        projected = projected.subtract(WideMath.mul(possibleInput.amount(), input.getMultiplier())
                                .multiply(PlanAmount.of(times)));
                    }
                }
            }
            for (GenericStack outputStack : details.getOutputs()) {
                if (outputStack != null && outputStack.what() != null && outputStack.what().equals(key)) {
                    projected = projected.add(WideMath.mul(outputStack.amount(), times));
                }
            }
            return projected;
        }
    }
}
