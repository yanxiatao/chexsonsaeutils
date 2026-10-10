package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.Arrays;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.ByteAccount;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.WideCraftCounter;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideMath;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;

/**
 * Phase 2d B2 —— {@link GraphPlanningState} 的 undo 日志：所有账本写经本类
 * 代理（记录与写入同一调用，杜绝漏记），写入前把 {@code (账本, 键, 旧值或增量)}
 * 压进平铺日志，{@link #undoTo(int)} 逆序回放，快照成本从 O(状态规模) 降到 O(1)，
 * 回滚成本从 O(状态规模) 降到 O(该分支实际触碰的键数)。
 *
 * <p>条目两态：set 语义写（{@code cacheFuzzy} 的零标记、{@code used} 峰值、
 * {@code recursiveInternalItems}）记旧值——absent 与 0 值零标记必须可区分；
 * add 语义写（working/emitted/missing/ringExtractions/crafts 热路径）记带符号增量——
 * 省掉旧值快照的溢出侧表+主表双查，undo 反向 add 即精确恢复（含宽值；加到 0
 * 时键位自动移除与 absent 等价）。全量 {@code copy()}/{@code restore()} 已退役：
 * restore 会打破 delta 不变式（当前值 = mark + Σ增量），所有回滚点统一走日志。
 *
 * <p>存储是裸数组：主三列（{@code kinds} / {@code refs} / {@code deltas}）覆盖
 * 全部条目，只有字节条目与宽值 set 条目才额外占一格稀有列
 * （{@code doubleOlds} / {@code objOlds}）。稀有列不与主列对齐索引，而是各带
 * 一个游标——{@code undoTo} 严格逆序回放，遇到对应 kind 就回退一格，天然与追加
 * 顺序配对。实机夹具采样显示，改造前光是 fastutil 列表扩容就占求解自耗近两成，
 * 而热路径上占绝对多数的 delta 条目有两列是纯占位。
 */
public final class PlanJournal {

    static final int UNMODIFIED = 0;
    static final int WORKING = 1;
    static final int USED = 2;
    static final int EMITTED = 3;
    static final int MISSING = 4;
    static final int RING_EXTRACTIONS = 5;
    static final int RECURSIVE_INTERNAL = 6;
    /**
     * 份数条目：与 AEKey 账本同为 <b>add 语义</b>（带符号增量放 {@code deltas} 列，
     * 样板身份放 {@code refs} 列），undo 反向 add 即精确恢复——含宽域值落回 long 域
     * 的降级（{@code WideCraftCounter} 自己管侧表）。此前它记旧值，所以要区分
     * 「原本缺位」与「原本某个 long 值」两态；改宽域后旧值可能是 BigInteger，
     * 增量口径省掉那一整格稀有列，也与 working/emitted/missing 完全同构。
     */
    static final int CRAFTS = 7;
    /** 份数条目且增量本身超 {@code long}（环宏宽域发射向量）：增量放稀有列 {@code objOlds}。 */
    static final int CRAFTS_WIDE = 14;
    static final int RING_FAILED = 8;
    /** 留种还种轮数已按 {@code ringId} 记账（undo 时移除，环宏回滚后可重新还）。 */
    static final int RING_SEED_REPAID = 13;
    /** {@code addStackBytes} 的一体条目：double 旧读数 + apb 桶增量。 */
    static final int BYTE_STACK = 9;
    /** {@code addStackBytes} 的宽域桶增量条目（乘积本身溢出 long 的稀有路径）。 */
    static final int BYTE_STACK_WIDE = 10;
    /** {@code addBytes(double)} 的 double 读数恢复条目（无 apb 桶变化）。 */
    static final int BYTE_PLAIN = 11;

    /** delta 条目标志：{@code deltas} 存带符号增量，undo 时反向 add。 */
    static final int DELTA_FLAG = 64;
    /** set 条目标志：旧值在 long 域且键位存在，放 {@code deltas} 列免装箱。 */
    static final int FAST_OLD_FLAG = 128;
    static final int LEDGER_MASK = 63;

    private final WideKeyCounter[] counters;
    private final WideCraftCounter crafts;
    private final Set<RingId> failedRingMacros;
    private final Set<RingId> repaidRingSeeds;
    private final ByteAccount byteAccount;

    /**
     * 跨 attempt 复用的底层数组。一次 solve 里 attempt 是串行的，且 attempt
     * 返回后日志即废弃（{@code undoTo} 只在 attempt 内部调用），所以数组可以
     * 直接借用——此前每个 attempt 都要从小表一路翻倍到百万条目，实机采样里
     * 光是 grow / Arrays.copyOf 就占求解自耗四分之一（大数组分配还要清零）。
     */
    public static final class Arena {
        /**
         * 按计算线程复用，且用软引用持有——AE 的合成计算线程会连续处理多个请求，
         * 保住上一轮长到的容量就再也不用重新翻倍；内存压力上来时 GC 可直接回收。
         */
        private static final ThreadLocal<java.lang.ref.SoftReference<Arena>> PER_THREAD =
                new ThreadLocal<>();

        public static Arena forCurrentThread() {
            var reference = PER_THREAD.get();
            Arena arena = reference == null ? null : reference.get();
            if (arena == null) {
                arena = new Arena();
                PER_THREAD.set(new java.lang.ref.SoftReference<>(arena));
            }
            return arena;
        }

        // 小计划按需扩容；大计划仍按线程复用已经增长的数组。
        private int[] kinds = new int[1 << 10];
        private int[] keyIds = new int[1 << 10];
        private Object[] refs = new Object[1 << 10];
        private long[] deltas = new long[1 << 10];
        private double[] doubleOlds = new double[1 << 9];
        private Object[] objOlds = new Object[1 << 8];
        /** 上一次用到的最高水位——复用时只清这段前缀，不清整表。 */
        private int refsHighWater;
        private int objHighWater;
    }

    private final Arena arena;
    private int size;
    private int[] kinds;
    /** 账本条目的键 id（内化后账本按 id 数组寻址，undo 无需再解析 AEKey）。 */
    private int[] keyIds;
    private Object[] refs;
    private long[] deltas;
    private int doubleTop;
    private double[] doubleOlds;
    private int objTop;
    private Object[] objOlds;

    PlanJournal(WideKeyCounter[] counters,
            WideCraftCounter crafts,
            Set<RingId> failedRingMacros, Set<RingId> repaidRingSeeds,
            ByteAccount byteAccount, Arena arena) {
        this.counters = counters;
        this.crafts = crafts;
        this.failedRingMacros = failedRingMacros;
        this.repaidRingSeeds = repaidRingSeeds;
        this.byteAccount = byteAccount;
        this.arena = arena;
        this.kinds = arena.kinds;
        this.keyIds = arena.keyIds;
        this.refs = arena.refs;
        this.deltas = arena.deltas;
        this.doubleOlds = arena.doubleOlds;
        this.objOlds = arena.objOlds;
        // 只清上一次真正用到的引用前缀。size 从 0 起算，undo 只读 size 以下，
        // 水位之上的残值读不到，
        // 清理的唯一目的是释放 GC 引用。
        java.util.Arrays.fill(this.refs, 0, Math.min(arena.refsHighWater, this.refs.length), null);
        java.util.Arrays.fill(this.objOlds, 0, Math.min(arena.objHighWater, this.objOlds.length), null);
        arena.refsHighWater = 0;
        arena.objHighWater = 0;
    }

    /** 当前日志长度——{@link #undoTo(int)} 的回滚目标。 */
    int mark() {
        return this.size;
    }

    /** 逆序回放到 {@code mark} 并截断，之后日志可复用。 */
    void undoTo(int mark) {
        for (int i = this.size - 1; i >= mark; i--) {
            int kind = this.kinds[i];
            Object key = this.refs[i];
            this.refs[i] = null;
            if ((kind & DELTA_FLAG) != 0) {
                // add 语义条目：反向 add 即精确恢复（含宽值与 absent——
                // 加到 0 时 WideKeyCounter 自动移除键位，与 absent 等价）
                this.counters[kind & LEDGER_MASK].addById(this.keyIds[i], -this.deltas[i]);
                continue;
            }
            if ((kind & FAST_OLD_FLAG) != 0) {
                // set 语义 + long 域旧值：直接写回，旧值无装箱
                this.counters[kind & LEDGER_MASK].setById(this.keyIds[i], this.deltas[i]);
                continue;
            }
            undoRare(kind, key, i);
        }
        this.size = mark;
    }

    private void undoRare(int kind, @Nullable Object key, int index) {
        // 账本条目的键走 keyIds 列，样板 / 环 id / 字节键走 refs 列
        switch (kind) {
            case UNMODIFIED, WORKING, USED, EMITTED, MISSING, RING_EXTRACTIONS, RECURSIVE_INTERNAL ->
                undoCounter(kind, this.keyIds[index], popObj());
            case CRAFTS -> this.crafts.add((IPatternDetails) key, -this.deltas[index]);
            // 宽域发射向量的回滚：增量在稀有列，反向相加后账本自己决定要不要降回窄域
            case CRAFTS_WIDE -> this.crafts.add((IPatternDetails) key,
                    PlanAmount.of(((java.math.BigInteger) popObj()).negate()));
            case RING_FAILED -> this.failedRingMacros.remove((RingId) key);
            case RING_SEED_REPAID -> this.repaidRingSeeds.remove((RingId) key);
            case BYTE_PLAIN -> this.byteAccount.setAe2Bytes(popDouble());
            case BYTE_STACK -> {
                this.byteAccount.setAe2Bytes(popDouble());
                this.byteAccount.addBucket(((AEKey) key).getAmountPerByte(), -this.deltas[index]);
            }
            case BYTE_STACK_WIDE -> {
                var wide = (java.math.BigInteger) popObj();
                this.byteAccount.setAe2Bytes(popDouble());
                this.byteAccount.addBucketWide(((AEKey) key).getAmountPerByte(), wide.negate());
            }
            default -> throw new IllegalStateException("unknown journal entry kind " + kind);
        }
    }

    private Object popObj() {
        Object value = this.objOlds[--this.objTop];
        this.objOlds[this.objTop] = null;
        return value;
    }

    private double popDouble() {
        return this.doubleOlds[--this.doubleTop];
    }

    /** 主三列追加。 */
    private void append(int kind, @Nullable Object ref, long delta) {
        int index = this.size;
        if (index == this.kinds.length) {
            grow();
        }
        this.kinds[index] = kind;
        this.refs[index] = ref;
        this.deltas[index] = delta;
        this.size = index + 1;
        if (index >= this.arena.refsHighWater) {
            this.arena.refsHighWater = index + 1;
        }
    }

    /** 账本条目追加：键只记 id，不写 refs 列。 */
    private void appendKey(int kind, int keyId, long delta) {
        int index = this.size;
        if (index == this.kinds.length) {
            grow();
        }
        this.kinds[index] = kind;
        this.keyIds[index] = keyId;
        this.refs[index] = null;
        this.deltas[index] = delta;
        this.size = index + 1;
    }

    private void grow() {
        int capacity = this.kinds.length << 1;
        if (capacity < 0) {
            throw new PlanningOverflowException("planning journal exceeded 2^31 entries");
        }
        this.kinds = Arrays.copyOf(this.kinds, capacity);
        this.keyIds = Arrays.copyOf(this.keyIds, capacity);
        this.refs = Arrays.copyOf(this.refs, capacity);
        this.deltas = Arrays.copyOf(this.deltas, capacity);
        this.arena.kinds = this.kinds;
        this.arena.keyIds = this.keyIds;
        this.arena.refs = this.refs;
        this.arena.deltas = this.deltas;
    }

    private void pushObj(@Nullable Object value) {
        if (this.objTop == this.objOlds.length) {
            this.objOlds = Arrays.copyOf(this.objOlds, this.objOlds.length << 1);
            this.arena.objOlds = this.objOlds;
        }
        this.objOlds[this.objTop++] = value;
        if (this.objTop > this.arena.objHighWater) {
            this.arena.objHighWater = this.objTop;
        }
    }

    private void pushDouble(double value) {
        if (this.doubleTop == this.doubleOlds.length) {
            this.doubleOlds = Arrays.copyOf(this.doubleOlds, this.doubleOlds.length << 1);
            this.arena.doubleOlds = this.doubleOlds;
        }
        this.doubleOlds[this.doubleTop++] = value;
    }

    private void undoCounter(int ledger, int keyId, @Nullable Object oldValue) {
        var counter = this.counters[ledger];
        if (oldValue == null) {
            counter.removeById(keyId);
        } else {
            counter.setExactById(keyId, (java.math.BigInteger) oldValue);
        }
    }

    // ---- 写代理：先记录，再落账本 ----

    /**
     * set 语义写入（absent/0 标记/宽值三态都必须忠实恢复）——记旧值。
     * long 域旧值走 {@code FAST_OLD_FLAG} 的原始列（免包装对象分配），
     * 只有真溢出的键才占一格 {@code objOlds}。
     */
    void counterSet(int ledger, int keyId, long value) {
        var counter = this.counters[ledger];
        var wide = counter.overflowOfById(keyId);
        if (wide != null) {
            appendKey(ledger, keyId, 0L);
            pushObj(wide);
        } else if (counter.containsKeyById(keyId)) {
            appendKey(ledger | FAST_OLD_FLAG, keyId, counter.rawFastById(keyId));
        } else {
            appendKey(ledger, keyId, 0L);
            pushObj(null);
        }
        counter.setById(keyId, value);
    }

    /**
     * set 语义写入<b>宽域</b>值：旧值仍按 {@link #counterSet} 那三态（absent / 0 标记 / 宽值）
     * 忠实记录，撤销即原样恢复。快路径（值在 {@code long} 域）逐字退回 {@code counterSet}——
     * 非溢出键的日志形状一字不变，只有真越界那一格才占稀有列。
     */
    void counterSetExact(int ledger, int keyId, PlanAmount value) {
        if (value.isFast()) {
            counterSet(ledger, keyId, value.fastValue());
            return;
        }
        var counter = this.counters[ledger];
        var wide = counter.overflowOfById(keyId);
        if (wide != null) {
            appendKey(ledger, keyId, 0L);
            pushObj(wide);
        } else if (counter.containsKeyById(keyId)) {
            appendKey(ledger | FAST_OLD_FLAG, keyId, counter.rawFastById(keyId));
        } else {
            appendKey(ledger, keyId, 0L);
            pushObj(null);
        }
        counter.setExactById(keyId, value.exactValue());
    }

    /**
     * add 语义写入（working/emitted/missing/ringExtractions 热路径）——
     * 记带符号增量而非旧值，省掉旧值快照的溢出侧表+主表双查；
     * undo 反向 add 即精确恢复。
     */
    void counterAddDelta(int ledger, int keyId, long delta) {
        if (delta == Long.MIN_VALUE) {
            // MIN 的反向增量不能留在 long 列，否则撤销时负号回绕。
            counterAddExact(ledger, keyId, PlanAmount.of(delta));
            return;
        }
        appendKey(ledger | DELTA_FLAG, keyId, delta);
        this.counters[ledger].addById(keyId, delta);
    }

    /** 宽增量一次记账；保存旧值以复用既有精确回滚语义，不按 long 大小拆笔。 */
    void counterAddDelta(int ledger, int keyId, PlanAmount delta) {
        if (delta.isFast()) {
            counterAddDelta(ledger, keyId, delta.fastValue());
            return;
        }
        counterAddExact(ledger, keyId, delta);
    }

    /** 保存旧值的增量写入；与 addById 一致，合计为零时恢复为缺位。 */
    private void counterAddExact(int ledger, int keyId, PlanAmount delta) {
        var value = this.counters[ledger].getAmountById(keyId).add(delta);
        counterSetExact(ledger, keyId, value);
        if (value.signum() == 0) {
            // add 到零必须移除键；set 的零标记仅供库存缓存语义使用。
            this.counters[ledger].removeById(keyId);
        }
    }

    /** 宽份数物料费：整数桶一笔入账，double 旧值仍逐位保存供撤销。 */
    void stackBytes(AEKey key, long amount, PlanAmount multiplier) {
        var product = WideMath.mul(amount, multiplier);
        append(BYTE_STACK_WIDE, key, 0L);
        pushDouble(this.byteAccount.ae2Bytes());
        pushObj(product.exactValue());
        this.byteAccount.addBytes((double) amount * multiplier.exactValue().doubleValue()
                / key.getType().getAmountPerByte() * 8);
        this.byteAccount.addBucketWide(key.getAmountPerByte(), product.exactValue());
    }

    void craftsMerge(IPatternDetails details, long times) {
        // 增量条目：样板身份走 refs 列（AEKey 账本走 keyIds 列），带符号发数走 deltas 列。
        // 越界由 WideCraftCounter 自己提升到侧表，日志侧不需要分支——回滚反向 add 即精确恢复。
        append(CRAFTS, details, times);
        this.crafts.add(details, times);
    }

    /** 宽域发数版：增量本身超 {@code long}（环宏发射向量）时把真值压进稀有列。 */
    void craftsMerge(IPatternDetails details, PlanAmount times) {
        if (times.isFast()) {
            craftsMerge(details, times.fastValue());
            return;
        }
        append(CRAFTS_WIDE, details, 0L);
        pushObj(times.exactValue());
        this.crafts.add(details, times);
    }

    void ringFailed(RingId ringId) {
        append(RING_FAILED, ringId, 0L);
        this.failedRingMacros.add(ringId);
    }

    void ringSeedRepaid(RingId ringId) {
        append(RING_SEED_REPAID, ringId, 0L);
        this.repaidRingSeeds.add(ringId);
    }

    void stackBytes(AEKey key, long amount, long multiplier) {
        double oldBytes = this.byteAccount.ae2Bytes();
        long product = amount * multiplier;
        if (multiplier != 0L
                && (product / multiplier != amount || (amount == Long.MIN_VALUE && multiplier == -1L))) {
            var wide = java.math.BigInteger.valueOf(amount).multiply(java.math.BigInteger.valueOf(multiplier));
            append(BYTE_STACK_WIDE, key, 0L);
            pushDouble(oldBytes);
            pushObj(wide);
        } else {
            append(BYTE_STACK, key, product);
            pushDouble(oldBytes);
        }
        this.byteAccount.add(key, amount, multiplier);
    }

    void plainBytes(double bytes) {
        append(BYTE_PLAIN, null, 0L);
        pushDouble(this.byteAccount.ae2Bytes());
        this.byteAccount.addBytes(bytes);
    }
}
