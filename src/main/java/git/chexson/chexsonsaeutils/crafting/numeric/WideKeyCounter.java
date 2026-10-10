package git.chexson.chexsonsaeutils.crafting.numeric;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import appeng.api.stacks.AEKey;

/**
 * AEKey → 数量账本：{@link KeyIndex} 编号 + 原始 {@code long[]} 主表 +
 * BigInteger 溢出侧表。
 *
 * <p>范式照 {@code cell/InfinityCellStore}：常态路径零分配、纯原始 long 读写；
 * 只有真溢出的键进侧表。不变式：侧表 id ⊆ 在册 id（提升时主表写窄视图哨兵值），
 * 迭代与 containsKey 只看在册位图即可覆盖全部键。
 *
 * <p>热路径请用 {@code *ById} 系列：id 由调用方一次解析后复用，账本操作退化成
 * 数组下标读写。实机剖析显示求解自耗有一半以上花在账本哈希查表上，而 25.7 万次
 * request 每次要做八次左右查询——键集合编译期已封闭，编号后这些全变成数组访问。
 * AEKey 形态的方法保留给出口与冷路径，内部只是先解析一次 id。
 */
public final class WideKeyCounter {

    private static final AtomicLong OVERFLOW_KEYS = new AtomicLong();

    private final KeyIndex index;
    /** id → 数量（窄视图；溢出 id 存哨兵值）。 */
    private long[] amounts;
    /** id → 是否在册（0 值零标记必须与 absent 可区分）。 */
    private long[] present;
    private int presentCount;
    @Nullable
    private Int2ObjectOpenHashMap<BigInteger> overflow;

    public WideKeyCounter() {
        this(new KeyIndex());
    }

    public WideKeyCounter(KeyIndex index) {
        this.index = index;
        int capacity = Math.max(64, index.size() + 16);
        this.amounts = new long[capacity];
        this.present = new long[(capacity + 63) >>> 6];
    }

    public KeyIndex index() {
        return this.index;
    }

    public int idOf(AEKey key) {
        return this.index.idOf(key);
    }

    private void ensure(int id) {
        if (id < this.amounts.length) {
            return;
        }
        int capacity = Math.max(id + 1, this.amounts.length << 1);
        this.amounts = java.util.Arrays.copyOf(this.amounts, capacity);
        this.present = java.util.Arrays.copyOf(this.present, (capacity + 63) >>> 6);
    }

    private boolean isPresent(int id) {
        return id < this.amounts.length && (this.present[id >>> 6] & (1L << (id & 63))) != 0L;
    }

    private void markPresent(int id) {
        if (!isPresent(id)) {
            this.present[id >>> 6] |= 1L << (id & 63);
            this.presentCount++;
        }
    }

    private void clearPresent(int id) {
        if (isPresent(id)) {
            this.present[id >>> 6] &= ~(1L << (id & 63));
            this.presentCount--;
        }
    }

    // ---- id 形态热路径 ----

    public void addById(int id, long delta) {
        ensure(id);
        if (this.overflow != null) {
            var existing = this.overflow.get(id);
            if (existing != null) {
                promoteById(id, existing.add(BigInteger.valueOf(delta)));
                return;
            }
        }
        long current = isPresent(id) ? this.amounts[id] : 0L;
        long updated = current + delta;
        if (((current ^ updated) & (delta ^ updated)) < 0) {
            promoteById(id, BigInteger.valueOf(current).add(BigInteger.valueOf(delta)));
            return;
        }
        if (updated == 0L) {
            this.amounts[id] = 0L;
            clearPresent(id);
        } else {
            this.amounts[id] = updated;
            markPresent(id);
        }
    }

    public long getLongById(int id) {
        return isPresent(id) ? this.amounts[id] : 0L;
    }

    public boolean containsKeyById(int id) {
        return isPresent(id);
    }

    /** 绝对赋值，{@code HashMap.put} 语义：0 值条目保留键位，溢出侧表条目一并清除。 */
    public void setById(int id, long value) {
        ensure(id);
        if (this.overflow != null) {
            this.overflow.remove(id);
        }
        this.amounts[id] = value;
        markPresent(id);
    }

    public void removeById(int id) {
        if (id >= this.amounts.length) {
            return;
        }
        this.amounts[id] = 0L;
        clearPresent(id);
        if (this.overflow != null) {
            this.overflow.remove(id);
        }
    }

    /** undo 日志专用：在册且窄域时的原始值。 */
    public long rawFastById(int id) {
        return id < this.amounts.length ? this.amounts[id] : 0L;
    }

    /** undo 日志专用：溢出侧表条目；侧表恒空时零成本返回 {@code null}。 */
    @Nullable
    public BigInteger overflowOfById(int id) {
        return this.overflow == null ? null : this.overflow.get(id);
    }

    /** 精确赋值（undo 恢复专用）：忠实保留「0 值且在册」的零标记语义。 */
    public void setExactById(int id, BigInteger value) {
        ensure(id);
        if (this.overflow != null) {
            this.overflow.remove(id);
        }
        if (value.bitLength() <= 63) {
            this.amounts[id] = value.longValueExact();
        } else {
            overflowTable().put(id, value);
            this.amounts[id] = value.signum() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        markPresent(id);
    }

    private Int2ObjectOpenHashMap<BigInteger> overflowTable() {
        if (this.overflow == null) {
            this.overflow = new Int2ObjectOpenHashMap<>();
        }
        return this.overflow;
    }

    private void promoteById(int id, BigInteger value) {
        ensure(id);
        if (value.bitLength() <= 63) {
            if (this.overflow != null) {
                this.overflow.remove(id);
            }
            long narrow = value.longValueExact();
            if (narrow == 0L) {
                this.amounts[id] = 0L;
                clearPresent(id);
            } else {
                this.amounts[id] = narrow;
                markPresent(id);
            }
            return;
        }
        if (this.overflow == null || !this.overflow.containsKey(id)) {
            recordOverflowKey();
        }
        overflowTable().put(id, value);
        this.amounts[id] = value.signum() >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        markPresent(id);
    }

    private static void recordOverflowKey() {
        if (GraphDiagnostics.active()) {
            OVERFLOW_KEYS.incrementAndGet();
        }
    }

    /** 在册条目里是否存在负值（溢出侧表为空时是精确判定；有溢出时只看窄值，调用方须先判空）。 */
    public boolean hasAnyNegativeFast() {
        for (int id = 0; id < this.amounts.length; id++) {
            if (isPresent(id) && this.amounts[id] < 0L) {
                return true;
            }
        }
        return false;
    }

    /** 在册条目的 id 与窄值遍历（无溢出侧表时值即精确值）。调用方不得写账本。 */
    public void forEachPresent(java.util.function.ObjLongConsumer<AEKey> action) {
        for (int id = 0; id < this.amounts.length; id++) {
            if (isPresent(id)) {
                action.accept(this.index.keyOf(id), this.amounts[id]);
            }
        }
    }

    /** 在册条目的 id 与窄值遍历，可跳过某个 id（convert 的 used − finalOutput 用）。 */
    public void forEachPresentExcept(int exceptId, java.util.function.ObjLongConsumer<AEKey> action) {
        for (int id = 0; id < this.amounts.length; id++) {
            if (id != exceptId && isPresent(id)) {
                action.accept(this.index.keyOf(id), this.amounts[id]);
            }
        }
    }

    /** 诊断读取：历史进入溢出侧表的键次数。 */
    public static long overflowKeys() {
        return OVERFLOW_KEYS.get();
    }

    // ---- AEKey 形态（出口与冷路径）----

    public void add(AEKey key, long delta) {
        addById(this.index.idOf(key), delta);
    }

    public void add(AEKey key, BigInteger delta) {
        if (delta.signum() == 0) {
            return;
        }
        int id = this.index.idOf(key);
        ensure(id);
        var existing = this.overflow == null ? null : this.overflow.get(id);
        BigInteger base = existing != null ? existing : BigInteger.valueOf(getLongById(id));
        promoteById(id, base.add(delta));
    }

    public void add(AEKey key, PlanAmount delta) {
        if (delta.isFast()) {
            add(key, delta.fastValue());
        } else {
            add(key, delta.wideValue());
        }
    }

    public void subtract(AEKey key, long delta) {
        add(key, -delta);
    }

    public void set(AEKey key, long value) {
        setById(this.index.idOf(key), value);
    }

    public long getLong(AEKey key) {
        int id = this.index.lookup(key);
        return id < 0 ? 0L : getLongById(id);
    }

    public BigInteger getExact(AEKey key) {
        int id = this.index.lookup(key);
        return id < 0 ? BigInteger.ZERO : getExactById(id);
    }

    public BigInteger getExactById(int id) {
        var existing = this.overflow == null ? null : this.overflow.get(id);
        return existing != null ? existing : BigInteger.valueOf(getLongById(id));
    }

    public PlanAmount getAmount(AEKey key) {
        int id = this.index.lookup(key);
        return id < 0 ? PlanAmount.ZERO : getAmountById(id);
    }

    public PlanAmount getAmountById(int id) {
        var existing = this.overflow == null ? null : this.overflow.get(id);
        return existing != null ? PlanAmount.of(existing) : PlanAmount.of(getLongById(id));
    }

    /** 一次查表读出「键位存在时的精确值」；键不存在返回 {@code null}。 */
    @Nullable
    public PlanAmount getIfPresent(AEKey key) {
        int id = this.index.lookup(key);
        if (id < 0 || !isPresent(id)) {
            return null;
        }
        return getAmountById(id);
    }

    public void setExact(AEKey key, BigInteger value) {
        setExactById(this.index.idOf(key), value);
    }

    public boolean containsKey(AEKey key) {
        int id = this.index.lookup(key);
        return id >= 0 && isPresent(id);
    }

    public void remove(AEKey key) {
        int id = this.index.lookup(key);
        if (id >= 0) {
            removeById(id);
        }
    }

    public void clear() {
        java.util.Arrays.fill(this.amounts, 0L);
        java.util.Arrays.fill(this.present, 0L);
        this.presentCount = 0;
        if (this.overflow != null) {
            this.overflow.clear();
        }
    }

    public void putAll(WideKeyCounter other) {
        // 主表窄视图是侧表的哨兵投影，逐键 getExact 才是精确值，直接累加会双计溢出键
        for (var key : other.keys()) {
            add(key, other.getExact(key));
        }
    }

    public int size() {
        return this.presentCount;
    }

    public boolean isEmpty() {
        return this.presentCount == 0;
    }

    public boolean hasOverflow() {
        return this.overflow != null && !this.overflow.isEmpty();
    }

    /** 在册键全集（侧表 id 已随提升置位，无需合并）。 */
    public Iterable<AEKey> keys() {
        return () -> new java.util.Iterator<AEKey>() {
            private int next = advance(0);

            private int advance(int from) {
                for (int id = from; id < amounts.length; id++) {
                    if (isPresent(id)) {
                        return id;
                    }
                }
                return -1;
            }

            @Override
            public boolean hasNext() {
                return this.next >= 0;
            }

            @Override
            public AEKey next() {
                if (this.next < 0) {
                    throw new java.util.NoSuchElementException();
                }
                AEKey key = index.keyOf(this.next);
                this.next = advance(this.next + 1);
                return key;
            }
        };
    }

    /** 全账本精确总和（宽域精确，绝不回绕）。 */
    public PlanAmount total() {
        PlanAmount sum = PlanAmount.ZERO;
        for (int id = 0; id < this.amounts.length; id++) {
            if (isPresent(id)) {
                sum = sum.add(getAmountById(id));
            }
        }
        return sum;
    }

    public WideKeyCounter copy() {
        var copy = new WideKeyCounter(this.index);
        copy.restore(this);
        return copy;
    }

    public void restore(WideKeyCounter snapshot) {
        this.amounts = snapshot.amounts.clone();
        this.present = snapshot.present.clone();
        this.presentCount = snapshot.presentCount;
        this.overflow = snapshot.overflow == null ? null : new Int2ObjectOpenHashMap<>(snapshot.overflow);
    }
}
