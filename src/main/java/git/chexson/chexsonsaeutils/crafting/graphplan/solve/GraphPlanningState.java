package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.ByteAccount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
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
     * 七张账本共用一张键内化表：id 一次解析后全程数组寻址。若每张表各自编号，
     * 一次 extract 就要为同一个键做七次哈希，比原先更糟。
     */
    private final git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex keyIndex;
    private final WideKeyCounter unmodified;
    private final WideKeyCounter working;
    private final WideKeyCounter used;
    private final WideKeyCounter emitted;
    private final WideKeyCounter missing;
    /**
     * 样板次数账本：fastutil 原语表，{@code addTo} 一次查表既加值又交回旧值
     * （此前 {@code get} + {@code merge} 查两次并装箱一个 Long，点火热路径每次都走）。
     * 仍实现 {@code Map<IPatternDetails, Long>}，出口侧口径不变。
     */
    private final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<IPatternDetails> crafts =
            new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
    private final WideKeyCounter ringExtractions;
    /** Retention reserve — the tree planner's recursiveInternalItems ledger. */
    private final WideKeyCounter recursiveInternalItems;
    private final Set<RingId> failedRingMacros = new HashSet<>();
    private final ByteAccount byteAccount = new ByteAccount();
    /** bytes 构成分诊断：节点费与物料费分账（attempt 末尾探针用）。 */
    private double nodeBytesAccum;
    private double stackBytesAccum;
    private final List<InputAllocation> inputAllocations = new java.util.ArrayList<>();
    private final PlanJournal journal;

    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants) {
        this(stock, classVariants, new PlanJournal.Arena(),
                new git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex());
    }

    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena) {
        this(stock, classVariants, journalArena,
                new git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex());
    }

    /**
     * @param journalArena 跨 attempt 复用的 undo 日志底层数组（attempt 串行且返回后
     *                     日志即废弃），省掉每轮从小表翻倍到百万条目的重分配与清零。
     */
    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena,
            git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex keyIndex) {
        this(stock, classVariants, journalArena, keyIndex, null, null);
    }

    public GraphPlanningState(Map<AEKey, Long> stock, Map<AEKey, List<AEKey>> classVariants,
            PlanJournal.Arena journalArena,
            git.chexson.chexsonsaeutils.crafting.numeric.KeyIndex keyIndex,
            @Nullable int[][] classMemberIdsByKeyId,
            @Nullable long[] stockByKeyId) {
        this.stock = stock;
        this.classVariants = classVariants;
        this.classMemberIdsByKeyId = classMemberIdsByKeyId;
        this.stockByKeyId = stockByKeyId;
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
                crafts, failedRingMacros, byteAccount, inputAllocations, journalArena);
    }

    /** 键的稠密 id——热路径调用方（站点 / 样板产出）应在编译期解析一次后复用。 */
    public int idOf(AEKey key) {
        return this.keyIndex.idOf(key);
    }

    /** Journal mark for a candidate attempt — roll back with {@link #undoTo(int)}. */
    public int journal() {
        return journal.mark();
    }

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

    /** Legacy AE2 double accumulator view — bit-identical to the tree planner. */
    public double bytes() {
        return this.byteAccount.ae2Bytes();
    }

    /** Exact byte ledger; precise value wins once the double leaves its exact domain. */
    public ByteAccount byteAccount() {
        return this.byteAccount;
    }

    public Map<IPatternDetails, Long> crafts() {
        return crafts;
    }

    public List<InputAllocation> inputAllocations() {
        return List.copyOf(inputAllocations);
    }

    public void recordInputAllocation(
            IPatternDetails consumer,
            int inputSlot,
            AEKey exactKey,
            long templateAmount,
            long units
    ) {
        if (consumer != null && inputSlot >= 0 && exactKey != null
                && templateAmount > 0L && units > 0L) {
            journal.inputAllocation(new InputAllocation(
                    consumer, inputSlot, exactKey, templateAmount, units));
        }
    }

    public record InputAllocation(
            IPatternDetails consumer,
            int inputSlot,
            AEKey exactKey,
            long templateAmount,
            long units
    ) {
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
            long amount = stockAmount(what, id);
            journal.counterSet(PlanJournal.UNMODIFIED, id, amount);
            if (amount > 0) {
                journal.counterAddDelta(PlanJournal.WORKING, id, amount);
            }
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
            int memberId = idOf(member);
            long amount = stock.getOrDefault(member, 0L);
            journal.counterSet(PlanJournal.UNMODIFIED, memberId, amount);
            if (amount > 0) {
                journal.counterAddDelta(PlanJournal.WORKING, memberId, amount);
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
            long amount = stockAmount(null, memberId);
            journal.counterSet(PlanJournal.UNMODIFIED, memberId, amount);
            if (amount > 0) {
                journal.counterAddDelta(PlanJournal.WORKING, memberId, amount);
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

    @Nullable
    private int[] classMemberIds(int id) {
        return classMemberIdsByKeyId != null && id < classMemberIdsByKeyId.length
                ? classMemberIdsByKeyId[id]
                : null;
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

    public void insert(AEKey what, long amount) {
        insertById(what, idOf(what), amount);
    }

    public void insertById(AEKey what, int id, long amount) {
        cacheFuzzy(what, id);
        if (amount != 0) {
            journal.counterAddDelta(PlanJournal.WORKING, id, amount);
        }
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
     * 找不到返回 null。聚合核的模板提取每次只需要**一个**可提取来源：贪婪迭代
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

    public void addMissing(AEKey what, long amount) {
        if (amount != 0) {
            journal.counterAddDelta(PlanJournal.MISSING, idOf(what), amount);
        }
    }

    /**
     * 出口对账专用：把某键的 missing 剔除指定量（假缺口剔除）。
     * 只在出口、无回滚语义的阶段调用。
     */
    public void reduceMissing(AEKey what, long amount) {
        if (amount != 0) {
            journal.counterAddDelta(PlanJournal.MISSING, idOf(what), -amount);
        }
    }

    /**
     * 出口对账专用：假缺口剔除后，把对应量按 requiredExtract 口径补进 used
     * （树侧这些量本该作为整模板提取出现在 used 里）。同样只在出口调用。
     */
    public void addUsedForReconciliation(AEKey what, long amount) {
        int id = idOf(what);
        long current = unmodified.getLongById(id) - working.getLongById(id);
        long target = Math.max(current, 0L) + amount;
        long unmod = unmodified.getLongById(id);
        // used 的峰值口径：delta = unmodified − working ≥ target 时直接设
        journal.counterSet(PlanJournal.USED, id, Math.max(used.getLongById(id), target));
    }

    public void addCrafting(IPatternDetails details, long times) {
        try {
            journal.craftsMerge(details, times);
        } catch (ArithmeticException overflowed) {
            throw new PlanningOverflowException("craft count overflow at " + details);
        }
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

    /** id 形态热路径：站点携带自身键 id，整条提取链零哈希。 */
    public long extractTemplatesById(AEKey key, int keyId, long templateAmount, long requestedUnits) {
        return extractTemplatesWithSourceById(key, keyId, templateAmount, requestedUnits).units();
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

    /**
     * 与 {@link #extractTemplatesById} 相同，但同时返回真正被扣减的 exact variant。
     * AggregateSolver 的输入见证必须记录这里选中的实体键，不能用编译期 GraphInput.key 代替。
     */
    public TemplateExtraction extractTemplatesWithSourceById(AEKey key, int keyId, long templateAmount,
            long requestedUnits) {
        long maxTotal = WideMath.wideMul(templateAmount, requestedUnits);
        long extracted = extractById(key, keyId, maxTotal, true);
        long units = extracted / templateAmount;
        maxTotal = WideMath.wideMul(templateAmount, units);
        if (maxTotal == 0) {
            return TemplateExtraction.none();
        }
        long really = extractById(key, keyId, maxTotal, false);
        if (really != maxTotal) {
            throw new IllegalStateException("Failed to correctly extract whole number. Invalid simulation!");
        }
        return new TemplateExtraction(key, units);
    }

    public record TemplateExtraction(@Nullable AEKey sourceKey, long units) {
        private static TemplateExtraction none() {
            return new TemplateExtraction(null, 0L);
        }
    }

    private void updateUsed(int id) {
        long delta = unmodified.getLongById(id) - working.getLongById(id);
        if (delta > 0 && delta > used.getLongById(id)) {
            journal.counterSet(PlanJournal.USED, id, delta);
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
     */
    public boolean hasNegativeExitValues() {
        return hasNegative(unmodified) || hasNegative(working) || hasNegative(used)
                || hasNegative(emitted) || hasNegative(missing) || hasNegative(ringExtractions)
                || hasNegative(recursiveInternalItems);
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
