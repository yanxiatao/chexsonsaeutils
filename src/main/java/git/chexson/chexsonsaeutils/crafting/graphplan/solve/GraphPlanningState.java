package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.bigint.Ae2Amounts;
import git.chexson.chexsonsaeutils.bigint.ByteAccount;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;

/**
 * Level-1 planning ledger. Reproduces AE2's {@code CraftingSimulationState}
 * accounting with exact per-variant keys, mirroring the tree planner bit for
 * bit:
 *
 * <ul>
 * <li>{@code stock} is the immutable network snapshot; {@code unmodified} caches
 * what was lazily pulled from it per key (AE2's unmodifiedCache), while
 * {@code working} tracks the mutable sandbox (AE2's modifiableCache).</li>
 * <li>{@code cacheFuzzy} reproduces AE2's whole-class lazy pull: the first
 * touch of any key pulls every network variant of its fuzzy class (same
 * primary key) into the sandbox, in class order; later touches are no-ops.
 * Inserts participate too, so pattern outputs join the class exactly like
 * AE2's {@code insert} → {@code cacheFuzzy} path.</li>
 * <li>{@code used} reproduces {@code requiredExtract}: the peak of
 * {@code unmodified - working} per key, only updated on modular extracts.</li>
 * <li>{@code extractTemplates} reproduces AE2's whole-template rounding
 * (SIMULATE probe, floor to a whole number of templates, modular extract), and
 * {@code findFuzzyTemplates} returns the live sandbox members of a class in
 * class order for the solver's greedy template iteration.</li>
 * <li>The AEKey ledgers are {@link WideKeyCounter}s: long fast path, BigInteger
 * side table only for keys that truly overflow. Exits narrow through
 * {@link Ae2Amounts#toKeyCounter} (saturation + negative scan).</li>
 * <li>份数账（{@code crafts}）同样是宽域账本（{@link WideCraftCounter}）：一张样板
 * 跨站点累加或环宏发射向量超界时如实记账，饱和只代表值只出现在交回 AE2
 * {@code patternTimes} 的那份字典上。</li>
 * </ul>
 */
public final class GraphPlanningState {

    private final Map<AEKey, Long> stock;
    /** Every member of a multi-variant fuzzy class → the ordered member list. */
    private final Map<AEKey, List<AEKey>> classVariants;
    /** 编译图提供的模糊类 primitive 成员索引；手工图/旧构造器为空。 */
    @Nullable
    private final int[][] classMemberIdsByKeyId;
    /** 编译图提供的库存 id → amount 快照；手工图/旧构造器为空。 */
    @Nullable
    private final long[] stockByKeyId;
    /**
     * 本次请求的真值供给：只在<b>种子那一眼</b>起作用——沙盒账本本来就是宽域的，
     * 但起步量从 {@code long} 库存写进来就永远顶在 {@link Long#MAX_VALUE}。
     * {@code EMPTY} 即今日口径，逐字不变。
     */
    private final git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth supplyTruth;
    /**
     * 七张账本共用一张键内化表：id 一次解析后全程数组寻址。若每张表各自编号，
     * 一次 extract 就要为同一个键做七次哈希，比原先更糟。
     */
    private final git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex;
    private final WideKeyCounter unmodified;
    private final WideKeyCounter working;
    private final WideKeyCounter used;
    private final WideKeyCounter emitted;
    private final WideKeyCounter missing;
    /**
     * 样板份数账本（宽域）：{@code long} 快路径 + BigInteger 溢出侧表，范式与七张 AEKey 账本
     * 一致。旧实现是裸 {@code Object2LongOpenHashMap}，越界只能抛
     * {@code PlanningOverflowException} 把整条规划打死；现在同一张样板跨站点累加、或环宏
     * 发射向量本身超界时，份数如实记账。
     * {@link #crafts()} 交回的仍是该账本的饱和 {@code long} 视图（AE2 {@code patternTimes}
     * 那份代表值），判据侧必须读 {@link #craftsWide()}。
     */
    private final WideCraftCounter crafts = new WideCraftCounter();
    private final WideKeyCounter ringExtractions;
    /** Retention reserve — the tree planner's recursiveInternalItems ledger. */
    private final WideKeyCounter recursiveInternalItems;
    private final Set<RingId> failedRingMacros = new HashSet<>();
    /** 留种还种已记账的环（按 ringId，同一环整趟只还一次；随 journal 回滚）。 */
    private final Set<RingId> repaidRingSeeds = new HashSet<>();
    /** 已筹齐起步池的成员环，跨需求轮次及不同净输出入口复用。 */
    private final Set<RingId> startedRings = new HashSet<>();
    private final ByteAccount byteAccount = new ByteAccount();
    /** bytes 构成分诊断：节点费与物料费分账（attempt 末尾探针用）。 */
    private double nodeBytesAccum;
    private double stackBytesAccum;
    private final PlanJournal journal;

    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants) {
        this(stock, classVariants, new PlanJournal.Arena(),
                new git.chexson.chexsonsaeutils.bigint.KeyIndex());
    }

    /** 带真值供给的最简口：非稠密图（含离线用例）也从这里吃无限磁盘的 BigInteger 读数。 */
    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth supplyTruth) {
        this(stock, classVariants, new PlanJournal.Arena(),
                new git.chexson.chexsonsaeutils.bigint.KeyIndex(),
                null, null, supplyTruth);
    }

    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena) {
        this(stock, classVariants, journalArena,
                new git.chexson.chexsonsaeutils.bigint.KeyIndex());
    }

    /**
     * @param journalArena 跨 attempt 复用的 undo 日志底层数组（attempt 串行且返回后
     *                     日志即废弃），省掉每轮从小表翻倍到百万条目的重分配与清零。
     */
    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena,
            git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex) {
        this(stock, classVariants, journalArena, keyIndex, null, null);
    }

    /** 带共享键表与稠密视图、不带真值供给的口：起步量就是网络的 {@code long} 读数。 */
    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena,
            git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
            @Nullable int[][] classMemberIdsByKeyId,
            @Nullable long[] stockByKeyId) {
        this(stock, classVariants, journalArena, keyIndex, classMemberIdsByKeyId, stockByKeyId,
                git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.EMPTY);
    }

    /**
     * @param supplyTruth 本次请求的<b>真值供给</b>（无限磁盘溢出键的 BigInteger 读数）；
     *                    {@link git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth#EMPTY}
     *                    即今日口径。沙盒账本本来就是宽域的，缺的只是<b>起步那一眼</b>：
     *                    种子从 {@code long} 库存写进来，这一格最高就只有 {@code Long.MAX_VALUE}，
     *                    计划于是永远不敢申报 &gt; long 的用量。这里把它接上。
     */
    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena,
            git.chexson.chexsonsaeutils.bigint.KeyIndex keyIndex,
            @Nullable int[][] classMemberIdsByKeyId,
            @Nullable long[] stockByKeyId,
            git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth supplyTruth) {
        this.stock = stock;
        this.classVariants = classVariants;
        this.classMemberIdsByKeyId = classMemberIdsByKeyId;
        this.stockByKeyId = stockByKeyId;
        this.supplyTruth = supplyTruth;
        this.keyIndex = keyIndex;
        this.unmodified = new WideKeyCounter(keyIndex);
        this.working = new WideKeyCounter(keyIndex);
        this.used = new WideKeyCounter(keyIndex);
        this.emitted = new WideKeyCounter(keyIndex);
        this.missing = new WideKeyCounter(keyIndex);
        this.ringExtractions = new WideKeyCounter(keyIndex);
        this.recursiveInternalItems = new WideKeyCounter(keyIndex);
        this.journal = new PlanJournal(
                new WideKeyCounter[] { unmodified, working, used, emitted, missing, ringExtractions,
                        recursiveInternalItems },
                crafts, failedRingMacros, repaidRingSeeds, startedRings, byteAccount, journalArena);
    }

    /** 键的稠密 id——热路径调用方（站点 / 样板产出）应在编译期解析一次后复用。 */
    public int idOf(AEKey key) {
        return this.keyIndex.idOf(key);
    }

    /** Journal mark for a candidate attempt — roll back with {@link #undoTo(int)}. */
    public int journal() {
        return journal.mark();
    }

    /** 在求解检查点检查堆余量，压力出现时无损迁移回滚历史。 */
    public void checkMemoryPressure() { journal.checkMemoryPressure(); }

    /** 求解尝试结束后不再需要回滚历史，立即释放日志旧值与临时文件。 */
    public void releaseRollback() { journal.releaseRollback(); }

    /** Undo every journaled write after {@code mark}, newest first, and truncate. */
    public void undoTo(int mark) {
        journal.undoTo(mark);
    }

    /** Reserved ring seed amount — the tree planner's ringExtractions ledger. */
    public WideKeyCounter ringExtractions() {
        return ringExtractions;
    }

    public void recordRingExtraction(AEKey key, long amount) {
        if (amount > 0) {
            journal.counterAddDelta(PlanJournal.RING_EXTRACTIONS, idOf(key), amount);
        }
    }

    /**
     * 宽域留种预留一次记账，不按 long 大小拆笔；旧值由 journal 精确保存供回滚。
     * 窄增量复用 long 日志列，只有宽增量保存旧值对象。
     */
    public void recordRingExtraction(AEKey key, PlanAmount amount) {
        if (amount != null && amount.signum() > 0) {
            journal.counterAddDelta(PlanJournal.RING_EXTRACTIONS, idOf(key), amount);
        }
    }

    /**
     * Retention reserve, max-merged exactly like the tree planner's
     * {@code Math.max} update of {@code recursiveInternalItems}.
     */
    public void recordInternalItem(AEKey key, long amount) {
        int id = idOf(key);
        if (amount > 0 && recursiveInternalItems.getLongById(id) < amount) {
            journal.counterSet(PlanJournal.RECURSIVE_INTERNAL, id, amount);
        }
    }

    public WideKeyCounter recursiveInternalItems() {
        return recursiveInternalItems;
    }

    /** The {@code requiredExtract} peak for one key — {@code usedItems().get(key)}. */
    public long usedAmount(AEKey key) {
        return used.getLong(key);
    }

    public boolean hasRingMacroFailed(RingId ringId) {
        return this.failedRingMacros.contains(ringId);
    }

    public void markRingMacroFailed(RingId ringId) {
        journal.ringFailed(ringId);
    }

    /** 这一环的留种轮数是否已经记进发射向量（整趟只还一次）。 */
    public boolean hasRingSeedRepaid(RingId ringId) {
        return this.repaidRingSeeds.contains(ringId);
    }

    /** 起步完成状态随分支回滚，不能在试探失败后继续借用不存在的种子。 */
    public boolean hasRingStarted(RingId ringId) { return startedRings.contains(ringId.startupIdentity()); }

    public void markRingStarted(RingId ringId) { journal.ringStarted(ringId.startupIdentity()); }

    public void markRingSeedRepaid(RingId ringId) {
        journal.ringSeedRepaid(ringId);
    }

    /** Legacy AE2 double accumulator view — bit-identical to the tree planner. */
    public double bytes() {
        return this.byteAccount.ae2Bytes();
    }

    /** Exact byte ledger; precise value wins once the double leaves its exact domain. */
    public ByteAccount byteAccount() {
        return this.byteAccount;
    }

    /**
     * 份数的饱和 {@code long} 视图——交回 AE2 {@code patternTimes} 与按 long 计数的场合用。
     *
     * <p><b>判据侧禁止读这里</b>：超界样板在本视图上读回的是 {@code Long.MAX_VALUE} 代表值，
     * 拿它算供需会把 1e19 发截成 9.2e18 发，闸门就此假通过。供需判定一律走
     * {@link #craftsWide()}。
     */
    public Map<IPatternDetails, Long> crafts() {
        return crafts.longView();
    }

    /** 份数宽账（精确值）：守恒闸门、毛账与出口旁路账的唯一取数点。 */
    public WideCraftCounter craftsWide() {
        return crafts;
    }

    /** 内部宽账本直读（convert 的 id 形态快遍历用；调用方不得写）。 */
    public WideKeyCounter used() {
        return used;
    }

    public WideKeyCounter emitted() {
        return emitted;
    }

    public WideKeyCounter missing() {
        return missing;
    }

    public KeyCounter usedItems() {
        return toCounter(used);
    }

    public KeyCounter emittedItems() {
        return toCounter(emitted);
    }

    public KeyCounter missingItems() {
        return toCounter(missing);
    }

    /**
     * Equivalent of {@code CraftingSimulationState.ignore}: the target's own
     * stock must not satisfy the request (and must not be pulled again later).
     * Like AE2, this pulls the whole fuzzy class first and then zeroes only the
     * exact key, leaving sibling variants available to later extractions.
     */
    public void ignore(AEKey what) {
        int id = idOf(what);
        cacheFuzzy(what, id);
        journal.counterSet(PlanJournal.UNMODIFIED, id, 0L);
        journal.counterSet(PlanJournal.WORKING, id, 0L);
    }

    /**
     * Equivalent of AE2's {@code cacheFuzzy}: the first touch of a fuzzy class
     * pulls every network variant of the class (in class order) into the
     * sandbox; any later touch of the same class is a no-op. A class with no
     * network stock gets a zero marker on the touched key.
     */
    private void cacheFuzzy(AEKey what, int id) {
        if (unmodified.containsKeyById(id)) {
            // 常态快路径：本键已在册即整类已拉取（整类拉取会给每个成员——含本键
            // ——写 UNMODIFIED），一次位图查询即可返回，零哈希。
            return;
        }
        int[] memberIds = classMemberIds(id);
        if (memberIds != null) {
            cacheFuzzyDense(id, memberIds);
            return;
        }
        var members = classVariants.get(what);
        if (members == null) {
            seedStock(id, what);
            return;
        }
        // 「整类已拉取」只探首成员：整类拉取会给每个成员（含首成员）写
        // UNMODIFIED，且 journal 回滚是连续后缀，所以首成员在册 ⟺ 任一成员
        // 在册。此前每次账本触碰都要遍历全类探测，modpack 里同类变体动辄
        // 十几个，等于每次 extract/insert 多做十几次查表。
        if (members.isEmpty() || unmodified.containsKey(members.get(0))) {
            return;
        }
        boolean pulledAny = false;
        for (var member : members) {
            if (seedStock(idOf(member), member)) {
                pulledAny = true;
            }
        }
        if (!pulledAny) {
            journal.counterSet(PlanJournal.UNMODIFIED, id, 0L);
        }
    }

    private void cacheFuzzyDense(int id, int[] memberIds) {
        if (memberIds.length == 0 || unmodified.containsKeyById(memberIds[0])) {
            return;
        }
        boolean pulledAny = false;
        for (int memberId : memberIds) {
            if (seedStock(memberId, null)) {
                pulledAny = true;
            }
        }
        if (!pulledAny) {
            journal.counterSet(PlanJournal.UNMODIFIED, id, 0L);
        }
    }

    private long stockAmount(@Nullable AEKey key, int id) {
        return stockByKeyId != null && id < stockByKeyId.length
                ? stockByKeyId[id]
                : stock.getOrDefault(key != null ? key : keyIndex.keyOf(id), 0L);
    }

    /**
     * 把一个键的网络库存<b>真值</b>种进沙盒：UNMODIFIED 记绝对值、WORKING 起步同值；
     * 返回是否种进了正数（调用方用它决定"整类有没有料"的 0 标记）。
     *
     * <p>窄值（装得进 {@code long}）逐字走原路——{@code set} + {@code delta} 两条日志的形状
     * 一字不变，非溢出键的内存与回放开销零变化。只有真值越出 {@code long} 才两张账都换成
     * {@code setExact}：这一格此刻必然不在册（整类只在首次被触碰时拉取），set 与 add 同解，
     * 而增量日志的 {@code deltas} 列是 {@code long}，装不下宽值。
     */
    private boolean seedStock(int id, @Nullable AEKey key) {
        var amount = stockAmountWide(key, id);
        if (amount.isFast()) {
            long narrow = amount.fastValue();
            journal.counterSet(PlanJournal.UNMODIFIED, id, narrow);
            if (narrow > 0L) {
                journal.counterAddDelta(PlanJournal.WORKING, id, narrow);
            }
            return narrow > 0L;
        }
        journal.counterSetExact(PlanJournal.UNMODIFIED, id, amount);
        journal.counterSetExact(PlanJournal.WORKING, id, amount);
        return true;
    }

    /** 种子读数：命中溢出键给 {@link PlanAmount} 真值，否则逐字是今天那份 {@code long} 库存。 */
    private PlanAmount stockAmountWide(@Nullable AEKey key, int id) {
        if (supplyTruth.isEmpty()) {
            return PlanAmount.of(stockAmount(key, id));
        }
        var resolved = key != null ? key : keyIndex.keyOf(id);
        return supplyTruth.supplyOf(resolved, stockAmount(resolved, id));
    }

    /**
     * 精确键的当前可用量只读视图，供终端输入的批量剪枝使用。
     * 已拉取的键或族读取 working，未拉取则计入库存真值；不触发缓存、不写日志。
     * 与提取入口相同，已忽略的目标和已预留种子不能再次算成可用库存。
     * 调用方必须使用本状态内化表中的 keyId，不得把该读数推广为模糊槽总供给。
     */
    public PlanAmount availableExactByIdWide(AEKey key, int keyId) {
        var available = working.getAmountById(keyId);
        if (unmodified.containsKeyById(keyId)) {
            return available.max(PlanAmount.ZERO);
        }
        int[] memberIds = classMemberIds(keyId);
        if (memberIds != null) {
            if (memberIds.length == 0 || unmodified.containsKeyById(memberIds[0])) {
                return available.max(PlanAmount.ZERO);
            }
        } else {
            var members = classVariants.get(key);
            if (members != null && (members.isEmpty() || unmodified.containsKey(members.getFirst()))) {
                return available.max(PlanAmount.ZERO);
            }
        }
        return available.add(stockAmountWide(key, keyId)).max(PlanAmount.ZERO);
    }

    @Nullable
    private int[] classMemberIds(int id) {
        return classMemberIdsByKeyId != null && id < classMemberIdsByKeyId.length
                ? classMemberIdsByKeyId[id]
                : null;
    }

    /**
     * 把留种那一份从<b>可花库存</b>里扣住：只减 {@code working}，{@code unmodified} 不动。
     *
     * <p>消费者于是拿不到它，只能去打火环宏补一颗；而环起步那份由
     * {@code reservableStock}（读网络库存快照）照常 reserve 得到。扣下去的这一份同时
     * 记进 {@code used} 峰值——它会被物理提进 CPU，作业跑完由 {@code storeItems} 倒回网络，
     * 这才是"提取进 CPU 用完还得还种"。
     */
    public void holdBackRetainedSeeds(Map<AEKey, Long> retained) {
        for (var entry : retained.entrySet()) {
            holdBackRetainedSeed(entry.getKey(), entry.getValue());
        }
    }

    /** 宽域版留种账（{@code CraftGraph.retainedSeedsWide()}）：真值扣真值，不读饱和视图。 */
    public void holdBackRetainedSeedsWide(Map<AEKey, PlanAmount> retained) {
        for (var entry : retained.entrySet()) {
            holdBackRetainedSeed(entry.getKey(), entry.getValue());
        }
    }

    /** 单键版：把 {@code amount} 枚留种从可花账里扣回去（{@code working} 不足时按现有量夹住）。 */
    public void holdBackRetainedSeed(AEKey key, long amount) {
        if (key == null || amount <= 0L) {
            return;
        }
        int id = idOf(key);
        cacheFuzzy(key, id);
        long held = Math.min(amount, working.getLongById(id));
        if (held > 0L) {
            journal.counterAddDelta(PlanJournal.WORKING, id, -held);
            updateUsed(id);
        }
    }

    /**
     * 宽域留种按 min(需求, 可花库存) 一次扣回，供给与扣回量均不窄化。
     * 提取峰值只在扣回后更新；窄值仍走原有 long 入口。
     */
    public void holdBackRetainedSeed(AEKey key, PlanAmount amount) {
        if (key == null || amount == null || amount.signum() <= 0L) {
            return;
        }
        if (amount.isFast()) {
            holdBackRetainedSeed(key, amount.fastValue());
            return;
        }
        int id = idOf(key);
        cacheFuzzy(key, id);
        var held = amount.min(working.getAmountById(id));
        if (held.signum() > 0L) {
            journal.counterAddDelta(PlanJournal.WORKING, id, held.negate());
            updateUsed(id);
        }
    }

    public long extract(AEKey what, long amount, boolean simulate) {
        return extractById(what, idOf(what), amount, simulate);
    }

    /** id 形态热路径：调用方已持有键 id（站点在建站时解析一次）。 */
    public long extractById(AEKey what, int id, long amount, boolean simulate) {
        cacheFuzzy(what, id);
        long cached = working.getLongById(id);
        long extracted = Math.min(cached, amount);
        if (!simulate) {
            if (extracted > 0) {
                journal.counterAddDelta(PlanJournal.WORKING, id, -extracted);
            }
            updateUsed(id);
        }
        return extracted;
    }

    /**
     * 宽域精确取料：按 min(库存, 请求) 一次扣回，更新 requiredExtract 峰值。
     * 窄值仍走 long 入口；宽值不拆轮，避免数量增大导致日志和循环线性膨胀。
     */
    public PlanAmount extractByIdWide(AEKey what, int id, PlanAmount amount) {
        if (amount.signum() <= 0) {
            return PlanAmount.ZERO;
        }
        if (amount.isFast()) {
            return PlanAmount.of(extractById(what, id, amount.fastValue(), false));
        }
        cacheFuzzy(what, id);
        var extracted = working.getAmountById(id).min(amount).max(PlanAmount.ZERO);
        if (extracted.signum() > 0) {
            journal.counterAddDelta(PlanJournal.WORKING, id, extracted.negate());
        }
        updateUsed(id);
        return extracted;
    }

    public void insert(AEKey what, long amount) {
        insertById(what, idOf(what), amount);
    }

    /** 宽域版 {@link #insert}：一笔产出可以 {@code > long}，窄值一次退回 long 版。 */
    public void insert(AEKey what, PlanAmount amount) {
        insertByIdWide(what, idOf(what), amount);
    }

    public void insertById(AEKey what, int id, long amount) {
        cacheFuzzy(what, id);
        if (amount != 0) {
            journal.counterAddDelta(PlanJournal.WORKING, id, amount);
        }
    }

    /**
     * 宽域产出一次进入 working 账；同族库存仍在入账前按既有顺序拉取。
     * 窄增量复用 long 日志列，宽增量保存旧值供精确回滚。
     */
    public void insertByIdWide(AEKey what, int id, PlanAmount amount) {
        if (amount.signum() == 0) {
            return;
        }
        cacheFuzzy(what, id);
        journal.counterAddDelta(PlanJournal.WORKING, id, amount);
    }

    /**
     * Live sandbox members of the touched key's fuzzy class in class order —
     * the solver's greedy template iteration order. Keys with a zero sandbox
     * amount are skipped (AE2 would return them, but its own whole-template
     * probe immediately extracts 0 from them).
     */
    public List<AEKey> findFuzzyTemplates(AEKey what) {
        int id = idOf(what);
        cacheFuzzy(what, id);
        int[] memberIds = classMemberIds(id);
        if (memberIds != null) {
            var result = new java.util.ArrayList<AEKey>(memberIds.length);
            for (int memberId : memberIds) {
                if (working.getLongById(memberId) != 0) {
                    result.add(keyIndex.keyOf(memberId));
                }
            }
            return result;
        }
        var members = classVariants.get(what);
        if (members == null) {
            return working.getLongById(id) != 0 ? List.of(what) : List.of();
        }
        var result = new java.util.ArrayList<AEKey>(members.size());
        for (var member : members) {
            if (working.getLongById(idOf(member)) != 0) {
                result.add(member);
            }
        }
        return result;
    }

    /**
     * 找「该键的模糊类在沙盒里的第一个活成员」（类序，与 AE2 的贪婪模板迭代同序），
     * 找不到返回 null。求解侧的模板提取每次只需要**一个**可提取来源：贪婪迭代
     * 「取到整模板就停」，第一个活成员就是它，后面的成员从不被消费——所以无需
     * 构建整个活变体列表（每键一次 ArrayList + 全类扫描，实机类有十几个变体）。
     *
     * <p>与 {@link #findFuzzyTemplates} 的等价性：两版本按同一类序扫同一沙盒位图，
     * findFuzzyTemplates 的首元素 == 本方法的返回值；提取后成员数量只会减少，
     * 所以下一次调用自然推进到下一个活成员，行为与「逐个消费列表」完全一致。
     */
    @Nullable
    public AEKey firstLiveTemplate(AEKey what) {
        return firstLiveTemplate(what, java.util.Set.of());
    }

    /** 同上，但跳过 {@code rejected} 里的成员（isValid 不接受的变体）。 */
    @Nullable
    public AEKey firstLiveTemplate(AEKey what, java.util.Set<AEKey> rejected) {
        int id = idOf(what);
        cacheFuzzy(what, id);
        int[] memberIds = classMemberIds(id);
        if (memberIds != null) {
            for (int memberId : memberIds) {
                AEKey member = keyIndex.keyOf(memberId);
                if (!rejected.contains(member) && working.getLongById(memberId) != 0) {
                    return member;
                }
            }
            return null;
        }
        var members = classVariants.get(what);
        if (members == null) {
            return rejected.isEmpty() && working.getLongById(id) != 0 ? what : null;
        }
        for (var member : members) {
            if (rejected.contains(member)) {
                continue;
            }
            if (working.getLongById(idOf(member)) != 0) {
                return member;
            }
        }
        return null;
    }

    public void emitItems(AEKey what, long amount) {
        if (amount != 0) {
            journal.counterAddDelta(PlanJournal.EMITTED, idOf(what), amount);
        }
    }

    /**
     * 宽域版 {@link #emitItems}：可发射（emittable）那一腿的需求可以越出 {@code long}。
     * 这本账是守恒闸门的供给侧（{@code addToSupply(supply, state.emitted())}），
     * 记成天花板就是把哨兵当量——闸门另一侧的需求是精确宽值，两侧读出两个数即假拒。
     * 窄值一次退回 long 版。
     */
    public void emitItemsWide(AEKey what, PlanAmount amount) {
        if (amount.signum() != 0) {
            journal.counterAddDelta(PlanJournal.EMITTED, idOf(what), amount);
        }
    }

    public void addMissing(AEKey what, long amount) {
        if (amount != 0) {
            journal.counterAddDelta(PlanJournal.MISSING, idOf(what), amount);
        }
    }

    /**
     * 宽域版 {@link #addMissing}：缺口同样记进守恒闸门的供给侧平衡项，
     * 一笔 {@code > long} 的需求截成天花板就把"还差多少"报小了。窄值一次退回 long 版。
     */
    public void addMissingWide(AEKey what, PlanAmount amount) {
        if (amount.signum() != 0) {
            journal.counterAddDelta(PlanJournal.MISSING, idOf(what), amount);
        }
    }

    /**
     * 出口对账专用：把某键的 missing 剔除指定量（假缺口剔除）。
     * 只在出口、无回滚语义的阶段调用。
     */
    public void reduceMissing(AEKey what, long amount) {
        reduceMissingWide(what, PlanAmount.of(amount));
    }

    /**
     * 宽域出口对账：缺口一次减去精确数量，清零时移除键，保留原有空账判据。
     */
    public void reduceMissingWide(AEKey what, PlanAmount amount) {
        if (amount.signum() != 0) {
            journal.counterAddDelta(PlanJournal.MISSING, idOf(what), amount.negate());
        }
    }

    /**
     * 出口对账专用：假缺口剔除后，把对应量按 requiredExtract 口径补进 used
     * （树侧这些量本该作为整模板提取出现在 used 里）。同样只在出口调用。
     */
    public void addUsedForReconciliation(AEKey what, long amount) {
        addUsedForReconciliationWide(what, PlanAmount.of(amount));
    }

    /**
     * 宽域版：used 的峰值口径是 {@code unmodified − working}，这两个读数在无限盘溢出键上
     * 都是宽域真值。<b>先读饱和窄视图再相减</b>的两个方向都会错（{@code MAX − 5.78e18} 与
     * 再叠一笔 {@code long} 补偿会直接回绕成负数），而 {@code counterSet} 是绝对赋值——
     * 于是这本账里已有的 {@code > long} 真值被压回天花板，闸门供给侧凭空少一截即假拒。
     * 全程走 {@link PlanAmount}，落账用 {@code counterSetExact}。
     */
    public void addUsedForReconciliationWide(AEKey what, PlanAmount amount) {
        int id = idOf(what);
        var current = unmodified.getAmountById(id).subtract(working.getAmountById(id));
        if (current.signum() < 0) {
            current = PlanAmount.ZERO;
        }
        var target = current.add(amount);
        journal.counterSetExact(PlanJournal.USED, id, target.max(used.getAmountById(id)));
    }

    /**
     * 记一张样板执行 {@code times} 发。越界不再打死规划：份数账是宽域的，
     * 超 {@code long} 的量如实入账（饱和只发生在 {@link #crafts()} 那份交回 AE2 的代表值上）。
     */
    public void addCrafting(IPatternDetails details, long times) {
        journal.craftsMerge(details, times);
    }

    /** 宽域发数版：环宏发射向量本身超界时走这条，增量口径与 long 版逐字相同。 */
    public void addCrafting(IPatternDetails details, PlanAmount times) {
        journal.craftsMerge(details, times);
    }

    /** 宽域节点费使用原始发数，不通过 double 反推数量。 */
    public void addBytes(PlanAmount bytes) {
        this.nodeBytesAccum += bytes.exactValue().doubleValue();
        journal.plainBytes(bytes);
    }

    public void addBytes(double bytes) {
        this.nodeBytesAccum += bytes;
        journal.plainBytes(bytes);
    }

    public void addStackBytes(AEKey key, long amount, long multiplier) {
        // Crafting storage is 8 times bigger than normal storage, this is intentional.
        this.stackBytesAccum += (double) amount * multiplier / key.getType().getAmountPerByte() * 8;
        journal.stackBytes(key, amount, multiplier);
    }

    /**
     * 宽份数的物料费一次写入精确整数桶，double 仅作诊断读数。
     * 窄份数仍复用原浮点累加顺序；宽份数撤销时恢复旧 double 与精确桶增量。
     */
    public void addStackBytesWide(AEKey key, long amount, PlanAmount multiplier) {
        if (multiplier.signum() == 0) {
            return;
        }
        if (multiplier.isFast()) {
            addStackBytes(key, amount, multiplier.fastValue());
            return;
        }
        this.stackBytesAccum += (double) amount * multiplier.exactValue().doubleValue()
                / key.getType().getAmountPerByte() * 8;
        journal.stackBytes(key, amount, multiplier);
    }

    /** bytes 构成分诊断：节点费（addBytes）与物料费（addStackBytes）。 */
    public double nodeBytesAccum() {
        return nodeBytesAccum;
    }

    public double stackBytesAccum() {
        return stackBytesAccum;
    }

    /**
     * Whole-template exact-key extract, byte-for-byte reproduction of one
     * {@code CraftingCpuHelper.extractTemplates} candidate. Fuzzy inputs must
     * enumerate only variants accepted by {@code IInput.isValid()} through
     * {@link #extractTemplatesFromIds}; membership in the same primary-key
     * class alone never makes two component-bearing stacks interchangeable.
     */
    public long extractTemplates(AEKey key, long templateAmount, long requestedUnits) {
        return extractTemplatesById(key, idOf(key), templateAmount, requestedUnits);
    }

    /**
     * 整模板提取：件数落在 long 域时复用原单轮抽取。
     * 乘积越域时以宽库存除以模板量，一次提取整份数；余料保留，返回份数不超过请求。
     */
    public long extractTemplatesById(AEKey key, int keyId, long templateAmount, long requestedUnits) {
        if (requestedUnits <= 0L) {
            return 0L;
        }
        if (templateAmount <= 1L || requestedUnits <= Long.MAX_VALUE / templateAmount) {
            return extractTemplatesOnce(key, keyId, templateAmount, requestedUnits);
        }
        return extractTemplatesWide(key, keyId, templateAmount,
                PlanAmount.of(requestedUnits)).fastValue();
    }

    /**
     * 宽份数整模板提取：库存除以模板量后向下取整，取请求与可用份数的较小值。
     * 一次扣回精确件数并更新用量峰值，避免嵌套的份数分轮与件数分轮。
     */
    public PlanAmount extractTemplatesByIdWide(AEKey key, int keyId, long templateAmount,
            PlanAmount requestedUnits) {
        if (requestedUnits.signum() <= 0L) {
            return PlanAmount.ZERO;
        }
        if (requestedUnits.isFast()) {
            return PlanAmount.of(extractTemplatesById(key, keyId, templateAmount,
                    requestedUnits.fastValue()));
        }
        return extractTemplatesWide(key, keyId, templateAmount, requestedUnits);
    }

    /** 宽域库存按整模板一次提取；份数向下取整，余料留在同一沙盒中。 */
    private PlanAmount extractTemplatesWide(AEKey key, int keyId, long templateAmount,
            PlanAmount requestedUnits) {
        if (templateAmount <= 0L) {
            throw new IllegalArgumentException("template amount must be positive");
        }
        cacheFuzzy(key, keyId);
        var template = PlanAmount.of(templateAmount);
        var units = working.getAmountById(keyId).max(PlanAmount.ZERO)
                .divide(template).min(requestedUnits);
        if (units.signum() > 0) {
            var total = template.multiply(units);
            journal.counterAddDelta(PlanJournal.WORKING, keyId, total.negate());
            updateUsed(keyId);
        }
        return units;
    }

    /** 单轮整模板提取（旧口径原样）：模拟抽 → 折算份数 → 真抽，份数不整除就退回。 */
    private long extractTemplatesOnce(AEKey key, int keyId, long templateAmount, long requestedUnits) {
        long maxTotal = WideMath.wideMul(templateAmount, requestedUnits);
        long extracted = extractById(key, keyId, maxTotal, true);
        long units = extracted / templateAmount;
        maxTotal = WideMath.wideMul(templateAmount, units);
        if (maxTotal == 0) {
            return 0L;
        }
        long really = extractById(key, keyId, maxTotal, false);
        if (really != maxTotal) {
            throw new IllegalStateException("Failed to correctly extract whole number. Invalid simulation!");
        }
        return units;
    }

    /**
     * 已预译模糊输入的热路径：类拉取一次，随后按 AE2 类序直接扫描合法 source id。
     * 不构造活成员列表，也不在每次 request 中查询 classVariants / validVariants。
     */
    public long extractTemplatesFromIds(AEKey classKey, int classKeyId, int[] sourceIds,
            long templateAmount, long requestedUnits) {
        cacheFuzzy(classKey, classKeyId);
        long extractedUnits = 0L;
        for (int sourceId : sourceIds) {
            long remaining = requestedUnits - extractedUnits;
            if (remaining <= 0) {
                break;
            }
            long units = extractTemplatesById(keyIndex.keyOf(sourceId), sourceId,
                    templateAmount, remaining);
            extractedUnits += units;
        }
        return extractedUnits;
    }

    private void updateUsed(int id) {
        if (unmodified.overflowOfById(id) == null && working.overflowOfById(id) == null
                && used.overflowOfById(id) == null) {
            long original = unmodified.rawFastById(id);
            long current = working.rawFastById(id);
            long extracted = original - current;
            // 两个窄操作数相减仍可溢出；只有精确落在 long 时才走原始列。
            if (((original ^ current) & (original ^ extracted)) >= 0) {
                if (extracted > 0L && extracted > used.rawFastById(id)) {
                    journal.counterSet(PlanJournal.USED, id, extracted);
                }
                return;
            }
        }
        // 相减必须走宽域真值。旧口径读两个【饱和主表】再相减，两个方向都会记丢：
        // working 还在溢出区（主表恒为 Long.MAX_VALUE）时差值恒报 0，抽走多少全隐身；
        // 一旦 working 跌破天花板，又开始拿 Long.MAX_VALUE 反推，于是从真值 1.5e19 枚里
        // 抽走 Long.MAX_VALUE 枚只记上 3.45e18 枚——少掉的 5.78e18 枚不是没抽，是记丢了。
        // 这本账是守恒闸门与配给表的供给侧（addToSupply(supply, state.used())），
        // 少记一截就把配得平的图判成"材料不闭合"整条拒掉。
        var delta = unmodified.getAmountById(id).subtract(working.getAmountById(id));
        if (delta.signum() > 0 && delta.compareTo(used.getAmountById(id)) > 0) {
            journal.counterSetExact(PlanJournal.USED, id, delta);
        }
    }

    private static KeyCounter toCounter(WideKeyCounter source) {
        var counter = new KeyCounter();
        Ae2Amounts.toKeyCounter(source, counter);
        return counter;
    }

    /**
     * N4 出口体检：任一账本出现负值即 AE2 裸 {@code +=} 回绕的唯一可观测症状，
     * 调用方必须 DECLINE 回落，绝不让负数进 {@code CraftingPlan}。
     *
     * <p>份数账（{@code crafts}）也在检查域内：实机 2026-09-24 13:50 的取证行里躺着
     * {@code times=-13216328854969394}，而这一本当时<b>不在</b>体检名单上，负发数于是
     * 一路出了门（{@code Ae2Amounts.toLong} 只饱和不回绕，负值原样带下去）。
     */
    public boolean hasNegativeExitValues() {
        return hasNegative(unmodified) || hasNegative(working) || hasNegative(used)
                || hasNegative(emitted) || hasNegative(missing) || hasNegative(ringExtractions)
                || hasNegative(recursiveInternalItems) || crafts.hasNegative();
    }

    /** 负发数的点名串（样板 + 份数）；无负发数返回 {@code null}。只给出口的判决文本点名来源。 */
    public @Nullable String negativeCraftDetails() {
        return crafts.describeNegative();
    }

    private static boolean hasNegative(WideKeyCounter counter) {
        // 溢出侧表为空时（常态），直接按 id 扫在册位图：省掉每键一次
        // KeyIndex 哈希反查 + 一次 PlanAmount 分配。getLongById 的窄视图
        // 与原口径完全一致。
        if (!counter.hasOverflow()) {
            return counter.hasAnyNegativeFast();
        }
        for (AEKey key : counter.keys()) {
            if (counter.getAmount(key).signum() < 0) {
                return true;
            }
        }
        return false;
    }
}
