package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;

/**
 * Immutable level-1 planning snapshot compiled once per calculation and shared
 * by every probe (full solve, CRAFT_LESS binary search, simulate fallback).
 *
 * <p>{@code producersByOutput} holds every compiled pattern per key in the
 * crafting service's registration order; {@link
 * git.chexson.chexsonsaeutils.crafting.graphplan.solve.RouteOrdering} turns it
 * into the site's route sequence at solve time. {@code ringMacrosByEntry}
 * indexes compressed rings that can produce a key. {@code patternsBySource}
 * lets the converter map {@code patternTimes} back to
 * compiled inputs. {@code byproductSupply} indexes the extra supply a key
 * receives when other patterns fire and insert their byproducts; it is only
 * used for the optimistic upper bound, mirroring the tree planner where
 * byproducts are consumed opportunistically from the sandbox. {@code
 * classVariants} maps every participating key to the ordered member list of its
 * fuzzy class (same primary key, most damaged first): it supports lazy class
 * caching and ordered template enumeration only where an input's
 * {@code isValid} explicitly permits fuzzy extraction; it does not make class
 * members globally interchangeable.
 */
public final class CraftGraph {

    public enum CycleKind {
        NONE,
        COMPRESSIBLE,
        UNSUPPORTED_CYCLE
    }

    private final AEKey target;
    private final Map<AEKey, List<GraphPattern>> producersByOutput;
    private final Map<IPatternDetails, GraphPattern> patternsBySource;
    private final List<IPatternDetails> patternRegistrationOrder;
    private final Map<AEKey, List<RingMacro>> ringMacrosByEntry;
    private final CycleKind cycleKind;
    private final Map<AEKey, List<ByproductSupply>> byproductSupply;
    private final Map<AEKey, List<RemainderSupply>> remainderSupply;
    /** 非 final：编译缓存命中时按请求注入新鲜库存快照（求解单线程）。 */
    private Map<AEKey, Long> stock;
    private final Set<AEKey> emittable;
    private final Map<AEKey, List<AEKey>> classVariants;
    /**
     * 编译期预编号的键内化表：求解侧的账本 id 在建图时一次定死，
     * attempt/求解不再为同一键反复哈希。键集合编译期封闭（产出/输入/
     * 候选/余料/库存/宏净流量），迟到键按需追加，语义与各自编号一致。
     */
    private final git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex;
    /**
     * 图拓扑的稠密键视图。模糊类成员按 AE2 贪婪顺序预译为 int[]，
     * emittable 预译为位图；同一编译图的库存新鲜化只重建数量数组，
     * 拓扑视图由 {@link #withStock(Map)} 原样复用。
     */
    @Nullable
    private final DenseKeyView denseKeyView;
    /** 当前库存快照的 id → amount 视图；仅数量随 withStock 更新。 */
    @Nullable
    private final long[] denseStock;
    /**
     * 规划侧只读真值供给（与 {@link #stock} 同一请求生命周期；编译缓存命中时随请求注入）。
     *
     * <p>{@link #stock()} 的逐键 {@code long} 是 AE2 的口径事实：我们无限盘的溢出键在那里
     * 永远长成 {@code Long.MAX_VALUE}。供需判据要区分「真只有这么多」与「被 long 截断」，
     * 就读这里——{@link PlanningSupplyTruth#supplyOf} 未命中宽域键时逐字退回 {@code stock} 的读数。
     */
    private PlanningSupplyTruth supplyTruth = PlanningSupplyTruth.EMPTY;
    private final boolean multiplePaths;
    @Nullable
    private final String declineReason;
    /** Raw {@code getCraftingFor(target)} size at compile time — index-layer probe. */
    private final int craftingForTargetCount;
    /** Routes assembled for the root site — routing-layer probe. */
    private final int rootRoutesCount;
    /**
     * 惰性缓存的容量遍历环分析（B1/X2）：图不可变且求解单线程，
     * CRAFT_LESS 的每一轮 probe 共享一次 SCC 分析，不重跑 Tarjan。
     */
    @Nullable
    private ActiveRouteSelection.Selection routeSelection;

    public CraftGraph(AEKey target,
            Map<AEKey, List<GraphPattern>> producersByOutput,
            Map<IPatternDetails, GraphPattern> patternsBySource,
            List<IPatternDetails> patternRegistrationOrder,
            Map<AEKey, List<RingMacro>> ringMacrosByEntry,
            Map<AEKey, List<ByproductSupply>> byproductSupply,
            Map<AEKey, List<RemainderSupply>> remainderSupply,
            Map<AEKey, Long> stock,
            Set<AEKey> emittable,
            Map<AEKey, List<AEKey>> classVariants,
            boolean multiplePaths,
            @Nullable String declineReason,
            int craftingForTargetCount,
            int rootRoutesCount) {
        this(target, producersByOutput, patternsBySource, patternRegistrationOrder, ringMacrosByEntry,
                inferCycleKind(ringMacrosByEntry), byproductSupply, remainderSupply, stock, emittable,
                classVariants, null, null, null, multiplePaths, declineReason, craftingForTargetCount,
                rootRoutesCount);
    }

    public CraftGraph(AEKey target,
            Map<AEKey, List<GraphPattern>> producersByOutput,
            Map<IPatternDetails, GraphPattern> patternsBySource,
            List<IPatternDetails> patternRegistrationOrder,
            Map<AEKey, List<RingMacro>> ringMacrosByEntry,
            Map<AEKey, List<ByproductSupply>> byproductSupply,
            Map<AEKey, List<RemainderSupply>> remainderSupply,
            Map<AEKey, Long> stock,
            Set<AEKey> emittable,
            Map<AEKey, List<AEKey>> classVariants,
            @Nullable git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
            boolean multiplePaths,
            @Nullable String declineReason,
            int craftingForTargetCount,
            int rootRoutesCount) {
        this(target, producersByOutput, patternsBySource, patternRegistrationOrder, ringMacrosByEntry,
                inferCycleKind(ringMacrosByEntry), byproductSupply, remainderSupply, stock, emittable,
                classVariants, keyIndex,
                keyIndex == null ? null : DenseKeyView.create(keyIndex, classVariants, emittable),
                keyIndex == null ? null : denseStock(keyIndex, stock), multiplePaths, declineReason,
                craftingForTargetCount, rootRoutesCount);
    }

    CraftGraph(AEKey target,
            Map<AEKey, List<GraphPattern>> producersByOutput,
            Map<IPatternDetails, GraphPattern> patternsBySource,
            List<IPatternDetails> patternRegistrationOrder,
            Map<AEKey, List<RingMacro>> ringMacrosByEntry,
            CycleKind cycleKind,
            Map<AEKey, List<ByproductSupply>> byproductSupply,
            Map<AEKey, List<RemainderSupply>> remainderSupply,
            Map<AEKey, Long> stock,
            Set<AEKey> emittable,
            Map<AEKey, List<AEKey>> classVariants,
            @Nullable git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
            boolean multiplePaths,
            @Nullable String declineReason,
            int craftingForTargetCount,
            int rootRoutesCount) {
        this(target, producersByOutput, patternsBySource, patternRegistrationOrder, ringMacrosByEntry, cycleKind,
                byproductSupply, remainderSupply, stock, emittable, classVariants, keyIndex,
                keyIndex == null ? null : DenseKeyView.create(keyIndex, classVariants, emittable),
                keyIndex == null ? null : denseStock(keyIndex, stock), multiplePaths, declineReason,
                craftingForTargetCount, rootRoutesCount);
    }

    private CraftGraph(AEKey target,
            Map<AEKey, List<GraphPattern>> producersByOutput,
            Map<IPatternDetails, GraphPattern> patternsBySource,
            List<IPatternDetails> patternRegistrationOrder,
            Map<AEKey, List<RingMacro>> ringMacrosByEntry,
            CycleKind cycleKind,
            Map<AEKey, List<ByproductSupply>> byproductSupply,
            Map<AEKey, List<RemainderSupply>> remainderSupply,
            Map<AEKey, Long> stock,
            Set<AEKey> emittable,
            Map<AEKey, List<AEKey>> classVariants,
            @Nullable git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
            @Nullable DenseKeyView denseKeyView,
            @Nullable long[] denseStock,
            boolean multiplePaths,
            @Nullable String declineReason,
            int craftingForTargetCount,
            int rootRoutesCount) {
        this.target = target;
        this.producersByOutput = producersByOutput;
        this.patternsBySource = patternsBySource;
        this.patternRegistrationOrder = List.copyOf(patternRegistrationOrder);
        this.ringMacrosByEntry = ringMacrosByEntry;
        this.cycleKind = cycleKind;
        this.byproductSupply = byproductSupply;
        this.remainderSupply = remainderSupply;
        this.stock = stock;
        this.emittable = emittable;
        this.classVariants = classVariants;
        this.keyIndex = keyIndex;
        this.denseKeyView = denseKeyView;
        this.denseStock = denseStock;
        this.multiplePaths = multiplePaths;
        this.declineReason = declineReason;
        this.craftingForTargetCount = craftingForTargetCount;
        this.rootRoutesCount = rootRoutesCount;
    }

    private static long[] denseStock(
            git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
            Map<AEKey, Long> stock) {
        var amounts = new long[keyIndex.size()];
        // 键索引只含求解工作集：按索引取库存，避免整网库存再做一轮哈希查找。
        for (int id = 0; id < amounts.length; id++) {
            amounts[id] = stock.getOrDefault(keyIndex.keyOf(id), 0L);
        }
        return amounts;
    }

    public AEKey target() {
        return target;
    }

    public Map<AEKey, List<GraphPattern>> producersByOutput() {
        return producersByOutput;
    }

    public Map<IPatternDetails, GraphPattern> patternsBySource() {
        return patternsBySource;
    }

    public List<IPatternDetails> patternRegistrationOrder() {
        return patternRegistrationOrder;
    }

    public Map<AEKey, List<RingMacro>> ringMacrosByEntry() {
        return ringMacrosByEntry;
    }

    public CycleKind cycleKind() {
        return cycleKind;
    }

    private static CycleKind inferCycleKind(Map<AEKey, List<RingMacro>> macrosByEntry) {
        return macrosByEntry == null || macrosByEntry.isEmpty()
                ? CycleKind.NONE
                : CycleKind.COMPRESSIBLE;
    }

    /** 留种量只依赖环定义，编译期就定了：算一次存着。 */
    private Map<AEKey, Long> retainedSeedsCache;
    /** 留种宽域真值账（与饱和 {@code long} 视图并行，见 {@link #retainedSeedsWide()}）。 */
    private Map<AEKey, PlanAmount> retainedSeedsWideCache;

    /**
     * 留种唯一的一条规则：<b>网络里要留下来的那一份，不是可花库存</b>。
     *
     * <p>求解侧据此把该键的<b>可花量</b>从沙盒里扣住（{@code working}），而
     * {@code stock()}/{@code reservableStock} 照旧看得见它——环还得拿它起步。
     * 于是"把这颗种补回来"成了规划自己的事：消费端拿不到那份库存，就去打火环宏，
     * 环宏的原料（钻石那一类）在同一次闭式解里跟主链争同一份料。
     *
     * <p>事后补种不可能成立：解完的账本已经把每一枚料排死了，补的那一发只能靠
     * "剩下的"，而剩下的恰好是零。
     *
     * <p><b>本视图是饱和 {@code long} 读数</b>：同族催化物累加越出 {@code long} 时顶在
     * {@code Long.MAX_VALUE}——那是哨兵不是量。判据侧要真值读 {@link #retainedSeedsWide()}。
     */
    public Map<AEKey, Long> retainedSeeds() {
        if (retainedSeedsCache != null) {
            return retainedSeedsCache;
        }
        var retained = new java.util.LinkedHashMap<AEKey, Long>();
        for (var entry : retainingRingsByCatalyst().entrySet()) {
            AEKey catalyst = entry.getKey();
            var ring = entry.getValue();
            if (catalyst == null || ring == null || ring.catalysts() == null) {
                continue;
            }
            long seedAmount = ring.catalysts().get(catalyst);
            if (seedAmount <= 0L) {
                continue;
            }
            long held = retained.getOrDefault(catalyst, 0L);
            retained.put(catalyst, seedAmount > Long.MAX_VALUE - held
                    ? Long.MAX_VALUE : held + seedAmount);
        }
        return retainedSeedsCache = Map.copyOf(retained);
    }

    /**
     * 留种<b>宽域真值</b>账（#218）：与 {@link #retainedSeeds()} 同一规则同一来源，
     * 累加走 {@code PlanAmount}，越出 {@code long} 的那一截如实留着——判据（起步预留、
     * 还种轮数）必须在真值域里比，不许先读饱和视图再相减。
     */
    public Map<AEKey, PlanAmount> retainedSeedsWide() {
        if (retainedSeedsWideCache != null) {
            return retainedSeedsWideCache;
        }
        var retained = new java.util.LinkedHashMap<AEKey, PlanAmount>();
        for (var entry : retainingRingsByCatalyst().entrySet()) {
            AEKey catalyst = entry.getKey();
            var ring = entry.getValue();
            if (catalyst == null || ring == null || ring.catalysts() == null) {
                continue;
            }
            long seedAmount = ring.catalysts().get(catalyst);
            if (seedAmount <= 0L) {
                continue;
            }
            var held = retained.getOrDefault(catalyst, PlanAmount.ZERO);
            retained.put(catalyst, held.add(PlanAmount.of(seedAmount)));
        }
        return retainedSeedsWideCache = Map.copyOf(retained);
    }

    /** 单键版宽域留种读数（无留种记录即零）。 */
    public PlanAmount retainedSeedAmount(AEKey catalyst) {
        return catalyst == null
                ? PlanAmount.ZERO
                : retainedSeedsWide().getOrDefault(catalyst, PlanAmount.ZERO);
    }

    /**
     * Graph-owned retaining rings indexed by catalyst key. Each SCC macro
     * already carries the complete compressed-ring flow needed by retention.
     */
    public Map<AEKey, CompressedRing> retainingRingsByCatalyst() {
        Map<AEKey, CompressedRing> retaining = new java.util.LinkedHashMap<>();
        Set<git.chexson.chexsonsaeutils.crafting.recursive.RingId> seen = new java.util.HashSet<>();
        for (var macros : ringMacrosByEntry.values()) {
            for (RingMacro macro : macros) {
                if (!seen.add(macro.ringId())) {
                    continue;
                }
                var ring = new CompressedRing(
                        macro.netInputs(), macro.netOutputs(), macro.catalysts(), macro.executionRatio(),
                        Collections.unmodifiableSet(new LinkedHashSet<>(macro.netOutputs().keySet())), true,
                        macro.memberFlows());
                for (var seed : macro.startupSeeds().entrySet()) {
                    if (macro.netOutputs().get(seed.getKey()) > 0L) {
                        retaining.putIfAbsent(seed.getKey(), ring);
                    }
                }
            }
        }
        return retaining.isEmpty() ? Map.of() : Collections.unmodifiableMap(retaining);
    }

    public Map<AEKey, List<ByproductSupply>> byproductSupply() {
        return byproductSupply;
    }

    public Map<AEKey, List<RemainderSupply>> remainderSupply() {
        return remainderSupply;
    }

    public Map<AEKey, Long> stock() {
        return stock;
    }

    /** 本次请求的真值供给（只读；从未注入即 {@link PlanningSupplyTruth#EMPTY}，等价于纯 {@code long} 口径）。 */
    public PlanningSupplyTruth supplyTruth() {
        return this.supplyTruth;
    }

    /**
     * 按请求注入真值供给：与 {@link #withStock(Map)} 的库存新鲜化同一时机、同一线程假设
     * （编译图可跨请求共享，注入的是一请求一值的只读快照）。
     */
    public void attachSupplyTruth(PlanningSupplyTruth supplyTruth) {
        this.supplyTruth = supplyTruth;
    }

    /**
     * 该键的<b>供给真值</b>（宽域）：命中无限盘溢出键给 {@link PlanAmount} 的 BigInteger，
     * 否则逐字退回 {@link #stock()} 那份 {@code long} 读数。
     *
     * <p>任何拿它当"这一键最多能提供多少"的判据都必须<b>先在这个数域里算完除法</b>再窄化：
     * 先 {@code toLong} 再 {@code / 每发用量}，等于把真值截成天花板之后还嫌它太大——
     * 放大出来的那份用量就这么没了。
     */
    public PlanAmount supplyAmount(AEKey key) {
        return supplyTruth.supplyOf(key, stock.getOrDefault(key, 0L));
    }

    /**
     * 编译缓存命中时的库存新鲜化：共享全部不可变拓扑字段，仅替换库存快照。
     * 求解单线程，安全。
     */
    public CraftGraph withStock(Map<AEKey, Long> freshStock) {
        if (freshStock == this.stock) {
            return this;
        }
        CraftGraph fresh = new CraftGraph(target, producersByOutput, patternsBySource,
                patternRegistrationOrder, ringMacrosByEntry, cycleKind, byproductSupply, remainderSupply,
                freshStock, emittable, classVariants, keyIndex, denseKeyView,
                keyIndex == null ? null : denseStock(keyIndex, freshStock),
                multiplePaths, declineReason, craftingForTargetCount, rootRoutesCount);
        fresh.routeSelection = this.routeSelection;
        fresh.supplyTruth = this.supplyTruth;
        return fresh;
    }

    public Set<AEKey> emittable() {
        return emittable;
    }

    public Map<AEKey, List<AEKey>> classVariants() {
        return classVariants;
    }

    /**
     * 编译期预编号的键内化表（可空——手工图/测试图走老构造器时懒建）。
     * 求解侧复用它之后，账本 id 在建图时一次定死，attempt 内不再哈希。
     */
    @Nullable
    public git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex() {
        return keyIndex;
    }

    @Nullable
    public DenseKeyView denseKeyView() {
        return denseKeyView;
    }

    @Nullable
    public long[] denseStock() {
        return denseStock;
    }

    public boolean multiplePaths() {
        return multiplePaths;
    }

    @Nullable
    public String declineReason() {
        return declineReason;
    }

    /**
     * 容量遍历环分析（首次调用时计算并缓存）：图不可变、求解单线程，
     * CRAFT_LESS 的每一轮 probe 共享一次 SCC 分析。
     */
    public ActiveRouteSelection.Selection routeSelection() {
        var selection = this.routeSelection;
        if (selection == null) {
            selection = ActiveRouteSelection.analyze(this);
            this.routeSelection = selection;
        }
        return selection;
    }

    public boolean hasDeclined() {
        return declineReason != null;
    }

    /** Raw {@code getCraftingFor(target)} size seen at compile time (index-layer probe). */
    public int craftingForTargetCount() {
        return craftingForTargetCount;
    }

    /** Number of routes assembled for the root site (routing-layer probe). */
    public int rootRoutesCount() {
        return rootRoutesCount;
    }

    /**
     * 只含图拓扑的 primitive 键索引；数组内容不可修改。
     * {@code classMemberIdsByKeyId[id] == null} 表示该键没有多变体模糊类。
     */
    public record DenseKeyView(int[][] classMemberIdsByKeyId, long[] emittableBits) {

        private static DenseKeyView create(
                git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
                Map<AEKey, List<AEKey>> classVariants,
                Set<AEKey> emittable) {
            int size = keyIndex.size();
            var classIds = new int[size][];
            var sharedClassIds = new java.util.IdentityHashMap<List<AEKey>, int[]>();
            for (var entry : classVariants.entrySet()) {
                int keyId = keyIndex.lookup(entry.getKey());
                if (keyId < 0) {
                    continue;
                }
                var members = entry.getValue();
                var memberIds = sharedClassIds.get(members);
                if (memberIds == null) {
                    memberIds = new int[members.size()];
                    for (int x = 0; x < memberIds.length; x++) {
                        int memberId = keyIndex.lookup(members.get(x));
                        if (memberId < 0) {
                            throw new IllegalStateException("dense key view missing fuzzy member");
                        }
                        memberIds[x] = memberId;
                    }
                    sharedClassIds.put(members, memberIds);
                }
                classIds[keyId] = memberIds;
            }
            var emittableBits = new long[(size + 63) >>> 6];
            for (var key : emittable) {
                int id = keyIndex.lookup(key);
                if (id >= 0) {
                    emittableBits[id >>> 6] |= 1L << (id & 63);
                }
            }
            return new DenseKeyView(classIds, emittableBits);
        }

        public boolean isEmittable(int id) {
            return id >= 0 && id >>> 6 < emittableBits.length
                    && (emittableBits[id >>> 6] & (1L << (id & 63))) != 0L;
        }

        @Nullable
        public int[] classMemberIds(int id) {
            return id >= 0 && id < classMemberIdsByKeyId.length ? classMemberIdsByKeyId[id] : null;
        }
    }

    /**
     * Extra supply of {@code amountPerCraft} units of one key per firing of the
     * byproduct-producing pattern.
     */
    public record ByproductSupply(GraphPattern pattern, long amountPerCraft) {
    }

    /**
     * Supply of {@code amountPerCraft} units of a finite-use remainder key per
     * firing of the pattern (durability-style container returns). Used only for
     * the optimistic upper bound.
     */
    public record RemainderSupply(GraphPattern pattern, long amountPerCraft) {
    }
}
