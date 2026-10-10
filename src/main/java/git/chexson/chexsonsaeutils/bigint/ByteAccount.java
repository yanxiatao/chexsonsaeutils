package git.chexson.chexsonsaeutils.bigint;

import java.math.BigInteger;

import org.jetbrains.annotations.Nullable;
import appeng.api.stacks.AEKey;

/**
 * 精确字节账本：复刻 AE2 {@code addStackBytes} 的 double 累加器（位模式一致），
 * 同时按 {@code amountPerByte} 分组保留精确整数账。
 *
 * <p>读取规则：double 未离开精确域（|bytes| &lt; 2^53 且有限）时以 double 为准
 * （{@code (long) Math.ceil}），既有场景逐位不变；越界才切换到
 * 「Σ 桶和通分 lcm 后整除上取整」的精确值。
 *
 * <p>桶值走 long 快路径（{@code fastByApb}），只有真正溢出 long 域的桶才落宽表
 * （{@code wideByApb}），且宽值回落 long 域时自动降级——所以「加了再减回去」
 * 恒等，undo 日志只记增量即可，不必快照整桶。此前每次 {@code add} 都要构造
 * 3 个 {@link PlanAmount}，是 42 万站点规模下求解耗时的最大单项。
 */
public final class ByteAccount {
    /** double 整数精确上限：2^53。 */
    private static final double EXACT_DOUBLE_LIMIT = 9_007_199_254_740_992.0;

    private double ae2Bytes;
    /**
     * apb 桶用平铺小数组而不是哈希表：{@code amountPerByte} 全网只有物品与流体
     * 两三个取值，线性扫两格远快于哈希（{@code addStackBytes} 在求解热路径上
     * 每次 request 都要走一遍，此前每次要查两张 Long2* 表）。
     */
    private int bucketCount;
    private long[] bucketApb = new long[4];
    private long[] bucketFast = new long[4];
    @Nullable
    private BigInteger[] bucketWide;

    public void add(AEKey key, long amount, long multiplier) {
        // 位模式必须与 AE2 ICraftingSimulationState.addStackBytes 完全一致
        this.ae2Bytes += (double) amount * multiplier / key.getType().getAmountPerByte() * 8;
        addExact(key.getAmountPerByte(), amount, multiplier);
    }

    public void addBytes(double bytes) {
        this.ae2Bytes += bytes;
    }

    private void addExact(long apb, long amount, long multiplier) {
        long product = amount * multiplier;
        if (multiplier != 0L
                && (product / multiplier != amount || (amount == Long.MIN_VALUE && multiplier == -1L))) {
            addWide(apb, BigInteger.valueOf(amount).multiply(BigInteger.valueOf(multiplier)));
            return;
        }
        addBucket(apb, product);
    }

    /** 桶下标；不存在时按需追加。 */
    private int slotOf(long apb) {
        for (int i = 0; i < this.bucketCount; i++) {
            if (this.bucketApb[i] == apb) {
                return i;
            }
        }
        if (this.bucketCount == this.bucketApb.length) {
            this.bucketApb = java.util.Arrays.copyOf(this.bucketApb, this.bucketCount << 1);
            this.bucketFast = java.util.Arrays.copyOf(this.bucketFast, this.bucketCount << 1);
            if (this.bucketWide != null) {
                this.bucketWide = java.util.Arrays.copyOf(this.bucketWide, this.bucketCount << 1);
            }
        }
        int slot = this.bucketCount++;
        this.bucketApb[slot] = apb;
        this.bucketFast[slot] = 0L;
        if (this.bucketWide != null) {
            this.bucketWide[slot] = null;
        }
        return slot;
    }

    /** 桶增量（含负增量，即 undo 反向记账）；越界升宽域，回落自动降级。 */
    public void addBucket(long apb, long delta) {
        int slot = slotOf(apb);
        BigInteger wide = this.bucketWide == null ? null : this.bucketWide[slot];
        if (wide != null) {
            storeWide(slot, wide.add(BigInteger.valueOf(delta)));
            return;
        }
        long current = this.bucketFast[slot];
        long sum = current + delta;
        if (((current ^ sum) & (delta ^ sum)) < 0) {
            storeWide(slot, BigInteger.valueOf(current).add(BigInteger.valueOf(delta)));
            return;
        }
        this.bucketFast[slot] = sum;
    }

    /** 宽域桶增量（乘积本身溢出 long 的稀有路径，含 undo 反向）。 */
    public void addBucketWide(long apb, BigInteger delta) {
        addWide(apb, delta);
    }

    private void addWide(long apb, BigInteger delta) {
        int slot = slotOf(apb);
        BigInteger wide = this.bucketWide == null ? null : this.bucketWide[slot];
        BigInteger base = wide != null ? wide : BigInteger.valueOf(this.bucketFast[slot]);
        storeWide(slot, base.add(delta));
    }

    private void storeWide(int slot, BigInteger value) {
        if (value.bitLength() < Long.SIZE) {
            if (this.bucketWide != null) {
                this.bucketWide[slot] = null;
            }
            this.bucketFast[slot] = value.longValueExact();
            return;
        }
        if (this.bucketWide == null) {
            this.bucketWide = new BigInteger[this.bucketApb.length];
        }
        this.bucketFast[slot] = 0L;
        this.bucketWide[slot] = value;
    }

    /** 位级恢复 double 读数（浮点减法不可逆，undo 必须整值写回）。 */
    public void setAe2Bytes(double value) {
        this.ae2Bytes = value;
    }

    public double ae2Bytes() {
        return this.ae2Bytes;
    }

    /** AE2 读法 {@code (long) Math.ceil(bytes)} 的精确对应值。 */
    public PlanAmount value() {
        double bytes = this.ae2Bytes;
        if (Double.isFinite(bytes) && Math.abs(bytes) < EXACT_DOUBLE_LIMIT) {
            return PlanAmount.of((long) Math.ceil(bytes));
        }
        return exactValue();
    }

    /** 精确值：Σ_b ceil 通分，全程整数。 */
    public PlanAmount exactValue() {
        boolean empty = true;
        for (int i = 0; i < this.bucketCount; i++) {
            if (this.bucketFast[i] != 0L || (this.bucketWide != null && this.bucketWide[i] != null)) {
                empty = false;
                break;
            }
        }
        if (empty) {
            return PlanAmount.of((long) Math.ceil(this.ae2Bytes));
        }
        long lcm = 1L;
        for (int i = 0; i < this.bucketCount; i++) {
            lcm = lcm / gcd(lcm, this.bucketApb[i]) * this.bucketApb[i];
        }
        PlanAmount numerator = PlanAmount.ZERO;
        for (int i = 0; i < this.bucketCount; i++) {
            BigInteger wide = this.bucketWide == null ? null : this.bucketWide[i];
            PlanAmount bucket = wide != null ? PlanAmount.of(wide) : PlanAmount.of(this.bucketFast[i]);
            numerator = numerator.add(bucket.multiply(PlanAmount.of(lcm / this.bucketApb[i])));
        }
        // AE2 公式末尾 ×8：分子统一带上 8
        return numerator.multiply(PlanAmount.of(8L)).ceilDiv(PlanAmount.of(lcm));
    }

    private static long gcd(long a, long b) {
        while (b != 0L) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    public ByteAccount copy() {
        var copy = new ByteAccount();
        copy.ae2Bytes = this.ae2Bytes;
        copy.bucketCount = this.bucketCount;
        copy.bucketApb = this.bucketApb.clone();
        copy.bucketFast = this.bucketFast.clone();
        copy.bucketWide = this.bucketWide == null ? null : this.bucketWide.clone();
        return copy;
    }

    public void restore(ByteAccount snapshot) {
        this.ae2Bytes = snapshot.ae2Bytes;
        this.bucketCount = snapshot.bucketCount;
        this.bucketApb = snapshot.bucketApb.clone();
        this.bucketFast = snapshot.bucketFast.clone();
        this.bucketWide = snapshot.bucketWide == null ? null : snapshot.bucketWide.clone();
    }

    /** 精确值与 AE2 double 读数之差（绝对值），供 N4 容差判定。 */
    public long exactMinusAe2Rounded() {
        BigInteger exact = value().exactValue();
        long ae2Rounded = (long) Math.ceil(this.ae2Bytes);
        return exact.subtract(BigInteger.valueOf(ae2Rounded)).signum() >= 0
                ? exact.subtract(BigInteger.valueOf(ae2Rounded)).longValueExact()
                : 0L;
    }
}
