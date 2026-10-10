package git.chexson.chexsonsaeutils.bigint;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphDiagnostics;

/**
 * 不可变数量标量：long 快路径 + BigInteger 宽路径。
 *
 * <p>规范化不变式：{@code wide == null} 当且仅当值落在 long 域（含运算结果回落）。
 * 通过整数判据检测溢出后提升到 BigInteger，热路径不创建异常对象，
 * 绝不抛异常、绝不饱和、绝不静默归零——饱和只允许出现在 {@link Ae2Amounts} 边界。
 */
public final class PlanAmount implements Comparable<PlanAmount> {
    private static final int SMALL_CACHE_RADIUS = 16;
    private static final PlanAmount[] SMALL_CACHE = new PlanAmount[SMALL_CACHE_RADIUS * 2 + 1];

    static {
        for (int value = -SMALL_CACHE_RADIUS; value <= SMALL_CACHE_RADIUS; value++) {
            SMALL_CACHE[value + SMALL_CACHE_RADIUS] = new PlanAmount(value, null);
        }
    }

    public static final PlanAmount ZERO = of(0L);
    public static final PlanAmount ONE = of(1L);

    private static final AtomicLong PROMOTIONS = new AtomicLong();
    private static final AtomicLong MAX_BITS = new AtomicLong();

    private final long fast;
    @Nullable
    private final BigInteger wide;

    private PlanAmount(long fast, @Nullable BigInteger wide) {
        this.fast = fast;
        this.wide = wide;
    }

    public static PlanAmount of(long value) {
        if (value >= -SMALL_CACHE_RADIUS && value <= SMALL_CACHE_RADIUS) {
            return SMALL_CACHE[(int) value + SMALL_CACHE_RADIUS];
        }
        return new PlanAmount(value, null);
    }

    public static PlanAmount of(BigInteger value) {
        if (value.bitLength() < Long.SIZE) {
            return of(value.longValue());
        }
        recordPromotion(value);
        return new PlanAmount(0L, value);
    }

    private static void recordPromotion(BigInteger wide) {
        if (GraphDiagnostics.active()) {
            PROMOTIONS.incrementAndGet();
            MAX_BITS.accumulateAndGet(wide.bitLength(), Math::max);
        }
    }

    /** 诊断读取：提升到宽域的次数（仅 diagnostics 开启时累加）。 */
    static long promotions() {
        return PROMOTIONS.get();
    }

    /** 诊断读取：历史宽域值的最大位宽。 */
    static int maxBits() {
        return (int) MAX_BITS.get();
    }

    public boolean isFast() {
        return this.wide == null;
    }

    /** 窄域原始值；仅当 {@code isFast()} 时有意义。 */
    public long fastValue() {
        return this.fast;
    }

    @Nullable
    public BigInteger wideValue() {
        return this.wide;
    }

    /** 精确值；宽域或转 {@link BigInteger} 用，热路径避免调用。 */
    public BigInteger exactValue() {
        return this.wide != null ? this.wide : BigInteger.valueOf(this.fast);
    }

    public int signum() {
        return this.wide != null ? this.wide.signum() : Long.signum(this.fast);
    }

    public PlanAmount add(PlanAmount other) {
        if (other == ZERO) {
            return this;
        }
        if (this == ZERO) {
            return other;
        }
        if (this.wide == null && other.wide == null) {
            long sum = this.fast + other.fast;
            if (((this.fast ^ sum) & (other.fast ^ sum)) >= 0) {
                return of(sum);
            }
        }
        return of(toBig(this).add(toBig(other)));
    }

    public PlanAmount subtract(PlanAmount other) {
        if (other == ZERO) {
            return this;
        }
        if (this == other) {
            return ZERO;
        }
        if (this.wide == null && other.wide == null) {
            long difference = this.fast - other.fast;
            if (((this.fast ^ other.fast) & (this.fast ^ difference)) >= 0) {
                return of(difference);
            }
        }
        return of(toBig(this).subtract(toBig(other)));
    }

    public PlanAmount multiply(PlanAmount other) {
        if (this == ZERO || other == ZERO) {
            return ZERO;
        }
        if (other == ONE) {
            return this;
        }
        if (this == ONE) {
            return other;
        }
        if (this.wide == null && other.wide == null) {
            if (WideMath.mulFitsLong(this.fast, other.fast)) {
                return of(this.fast * other.fast);
            }
        }
        return of(toBig(this).multiply(toBig(other)));
    }

    /** Java 整除语义（截断向零）；除数为零由调用方保证。 */
    public PlanAmount divide(PlanAmount other) {
        if (other == ONE) {
            return this;
        }
        if (this.wide == null && other.wide == null) {
            if (this.fast == Long.MIN_VALUE && other.fast == -1L) {
                return negate();
            }
            return of(this.fast / other.fast);
        }
        return of(toBig(this).divide(toBig(other)));
    }

    /** 数学上取整 {@code ceil(a/b)}；除数为零由调用方保证。 */
    public PlanAmount ceilDiv(PlanAmount other) {
        if (other == ONE) {
            return this;
        }
        if (this.wide == null && other.wide == null) {
            if (this.fast == Long.MIN_VALUE && other.fast == -1L) {
                return negate();
            }
            return of(Math.ceilDiv(this.fast, other.fast));
        }
        BigInteger dividend = toBig(this);
        BigInteger divisor = toBig(other);
        BigInteger[] quotientRemainder = dividend.divideAndRemainder(divisor);
        BigInteger quotient = quotientRemainder[0];
        BigInteger remainder = quotientRemainder[1];
        if (remainder.signum() != 0 && remainder.signum() == divisor.signum()) {
            quotient = quotient.add(BigInteger.ONE);
        }
        return of(quotient);
    }

    /** 数学下取整 {@code floor(a/b)}；除数为零由调用方保证。 */
    public PlanAmount floorDiv(PlanAmount other) {
        if (other == ONE) {
            return this;
        }
        if (this.wide == null && other.wide == null) {
            if (this.fast == Long.MIN_VALUE && other.fast == -1L) {
                return negate();
            }
            return of(Math.floorDiv(this.fast, other.fast));
        }
        BigInteger dividend = toBig(this);
        BigInteger divisor = toBig(other);
        BigInteger[] quotientRemainder = dividend.divideAndRemainder(divisor);
        BigInteger quotient = quotientRemainder[0];
        BigInteger remainder = quotientRemainder[1];
        if (remainder.signum() != 0 && remainder.signum() != divisor.signum()) {
            quotient = quotient.subtract(BigInteger.ONE);
        }
        return of(quotient);
    }

    /** Java 取余语义（截断向零，与 {@code %} 一致）；除数为零由调用方保证。 */
    public PlanAmount remainder(PlanAmount other) {
        if (this.wide == null && other.wide == null) {
            return of(this.fast % other.fast);
        }
        return of(toBig(this).remainder(toBig(other)));
    }

    public PlanAmount negate() {
        if (this.wide != null) {
            return of(this.wide.negate());
        }
        if (this.fast == Long.MIN_VALUE) {
            return of(BigInteger.valueOf(this.fast).negate());
        }
        return of(-this.fast);
    }

    public PlanAmount min(PlanAmount other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public PlanAmount max(PlanAmount other) {
        return compareTo(other) >= 0 ? this : other;
    }

    private static BigInteger toBig(PlanAmount amount) {
        return amount.exactValue();
    }

    @Override
    public int compareTo(PlanAmount other) {
        if (this == other) {
            return 0;
        }
        if (this.wide == null && other.wide == null) {
            return Long.compare(this.fast, other.fast);
        }
        // 规范化保证宽值在整个 long 区间之外，混合比较只需读宽值符号。
        if (this.wide == null) {
            return -other.wide.signum();
        }
        return other.wide == null ? this.wide.signum() : this.wide.compareTo(other.wide);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PlanAmount other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return this.wide == null ? Long.hashCode(this.fast) : this.wide.hashCode();
    }

    @Override
    public String toString() {
        return this.wide != null ? this.wide.toString() : Long.toString(this.fast);
    }
}
