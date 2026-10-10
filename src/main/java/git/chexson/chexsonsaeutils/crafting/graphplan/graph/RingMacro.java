package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;

/**
 * One compressed SCC ring compiled onto the graph as a virtual producer route
 * for its entry key. The ring's identity is the {@link RingId}.
 *
 * <p>One cycle consumes {@code netInputs} plus the {@code catalysts} seed pool
 * and produces {@code netOutputs}, mirroring the tree planner's
 * {@code requestRingDependencies} + {@code unpackRingOperations} pair:
 * catalyst seeds are reserved from stock (the tree planner's
 * {@code trackRingUsage}), the seed shortfall is requested recursively like a
 * fresh root node. The ledger then books the ring's <b>net flow of the firing
 * vector actually delivered</b> ({@link #netFlowOf}) — not {@code netInputs ×
 * ceil} / {@code netOutputs × floor}: the delivered per-member firings are
 * rounded up individually, so under a fractional scale the two disagree and the
 * difference is material the executor will consume but the plan never funded.
 * {@code executionRatio} lands in {@code patternTimes} and the positive net
 * flow in the sandbox, both with zero bytes. The solver scales a cycle by
 * {@code ceil(need / netOutputs(entryKey))}, matching the tree planner's
 * double division exactly.
 */
public record RingMacro(
        RingId ringId,
        KeyCounter netInputs,
        KeyCounter netOutputs,
        KeyCounter catalysts,
        java.util.Map<AEKey, PlanAmount> startupSeeds,
        java.util.Map<IPatternDetails, Long> executionRatio,
        java.util.Map<IPatternDetails, CompressedRing.PatternFlow> memberFlows,
        boolean rootOnly) {

    public RingMacro(
            RingId ringId,
            KeyCounter netInputs,
            KeyCounter netOutputs,
            KeyCounter catalysts,
            java.util.Map<IPatternDetails, Long> executionRatio,
            java.util.Map<IPatternDetails, CompressedRing.PatternFlow> memberFlows,
            boolean rootOnly) {
        this(ringId, netInputs, netOutputs, catalysts,
                startupSeedsOf(catalysts), executionRatio, memberFlows, rootOnly);
    }

    private static java.util.Map<AEKey, PlanAmount> startupSeedsOf(KeyCounter catalysts) {
        java.util.Map<AEKey, PlanAmount> result = new java.util.LinkedHashMap<>();
        for (var stack : catalysts) {
            result.put(stack.getKey(), PlanAmount.of(stack.getLongValue()));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public AEKey entryKey() {
        return ringId.entryPoint();
    }

    public long entryOutputPerCycle() {
        return netOutputs.get(ringId.entryPoint());
    }

    /**
     * 留种那一发的轮数：只看<b>这一趟环宏从网络起步预留里实际借走了几枚属于留种对象的催化物</b>。
     *
     * <p>判据落在入口键自身的净增益上：环入口键就是留种催化物时，{@code borrowedRetained}
     * 取自 {@code ringExtractions(入口键)}（起步预留记账，随 journal 回滚），
     * {@code cycles = ceil(min(borrowedRetained, retained) / 该键每轮净增益)}。
     * 没借走（网络里本就没有这颗种，预留提取量为 0）就不加——多算的轮数既不还出任何东西，
     * 又把主链的料抢走。净增益非正的环多跑也还不出来，返回 0。
     *
     * <p>去重不在这里做：调用方按 {@code ringId} 记"这一次加成是否真的记进发射向量"，见
     * {@code GraphPlanningState.hasRingSeedRepaid}。旧判据拿"入口键在起步预留账 {@code ringExtractions}
     * 上有没有值"当"已还"，而那本账按<b>催化物键</b>记：同一环常有两个入口共享同一份
     * {@code netInputs/netOutputs/catalysts}，先 fire 的入口（入口键 ≠ 催化物键）借走种就把账记在
     * 催化物键上，真正该还的那次（入口键 == 催化物键）一查账被误判成"已经还过"，加成整趟落空。
     *
     * <p>#218：留种读数、每轮净产出与除法全程 {@code PlanAmount}——旧写法
     * {@code netOutputs.get(entry) - netInputs.get(entry)} 在顶格的 long 读数上先回绕成负数，
     * {@code netPerCycle <= 0} 就把还种那一趟整趟判落空；减法、比较、ceil 除法都不许先把
     * 操作数窄化，饱和只允许出现在交回 AE2 边界的读数上。
     */
    public PlanAmount seedRepaymentCycles(CraftGraph graph, PlanAmount borrowedRetained) {
        if (graph == null || borrowedRetained == null || borrowedRetained.signum() <= 0L) {
            return PlanAmount.ZERO;
        }
        AEKey entry = entryKey();
        PlanAmount retained = graph.retainedSeedAmount(entry);
        PlanAmount netPerCycle = PlanAmount.of(netOutputs.get(entry))
                .subtract(PlanAmount.of(netInputs.get(entry)));
        if (retained.signum() <= 0L || netPerCycle.signum() <= 0L || entryOutputPerCycle() <= 0L) {
            return PlanAmount.ZERO;
        }
        PlanAmount borrowedOfSeed = borrowedRetained.min(retained);
        // ceil(借走的种 / 每轮净产出)：宽域 ceilDiv，商可以是真值，不做任何一次窄化。
        return borrowedOfSeed.ceilDiv(netPerCycle);
    }

    /**
     * 把还种轮数折进发射向量：只放大发射量（多接 {@code repaymentCycles ×} 入口每轮净产出），
     * 父请求的满足量仍按 {@code totalRequested} 结算，不会多报完成。
     *
     * <p>交回宽域值：还种加成 {@code repaymentCycles × perCycle} 可以超 {@code long}（超界计划的
     * 环宏就是要多跑几十倍轮数），旧版在这里饱和成 {@code Long.MAX_VALUE}，成员发数于是按
     * 截断后的轮数算——还种量算少，执行侧那颗种回不了网络。饱和只允许出现在交回 AE2
     * {@code patternTimes} 的那份代表值上（{@code Ae2Amounts.toLong}）。
     */
    public PlanAmount plannedWithSeedReturn(long totalRequested, long repaymentCycles) {
        return plannedWithSeedReturn(totalRequested, PlanAmount.of(repaymentCycles));
    }

    /** 宽域还种轮数版：轮数来自 {@link #seedRepaymentCycles} 的精确商，可以超 {@code long}。 */
    public PlanAmount plannedWithSeedReturn(long totalRequested, PlanAmount repaymentCycles) {
        long perCycle = entryOutputPerCycle();
        if (totalRequested <= 0L || repaymentCycles == null
                || repaymentCycles.signum() <= 0L || perCycle <= 0L) {
            return PlanAmount.of(totalRequested);
        }
        return PlanAmount.of(totalRequested)
                .add(repaymentCycles.multiply(PlanAmount.of(perCycle)));
    }

    /**
     * Net flow per key of the firing vector the solver ships for this macro:
     * positive = the ring hands that many units back to the sandbox, negative =
     * the ring eats that many units and the shortfall must be requested.
     *
     * <p>发射向量按<b>宽域发数</b>入账：环宏的成员份数可以超 {@code long}，净流量必须与
     * {@code crafts} 里记下的同一份数字，否则闸门按真值收费、沙盒按截断值进账。
     */
    public java.util.Map<AEKey, java.math.BigInteger> netFlowOf(
            java.util.Map<IPatternDetails, PlanAmount> firings) {
        java.util.Map<AEKey, java.math.BigInteger> net = new java.util.LinkedHashMap<>();
        for (var entry : memberFlows.entrySet()) {
            entry.getValue().addScaledNet(net,
                    firings.getOrDefault(entry.getKey(), PlanAmount.ZERO).exactValue());
        }
        net.values().removeIf(value -> value.signum() == 0);
        return net;
    }
}
