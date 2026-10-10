package git.chexson.chexsonsaeutils.crafting.numeric;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphDiagnostics;

/**
 * 不可变数量标量：long 快路径 + BigInteger 宽路径。
 *
 * <p>规范化不变式：{@code wide == null} 当且仅当值落在 long 域（含运算结果回落）。
 * 溢出时捕获 {@link ArithmeticException} 提升到 BigInteger 重算一次，
 * 绝不抛异常、绝不饱和、绝不静默归零——饱和只允许出现在 {@link Ae2Amounts} 边界。
 */
public final class PlanAmount implements Comparable<PlanAmount> {
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
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
        if (value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0) {
            return of(value.longValueExact());
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
        if (this.wide == null && other.wide == null) {
            try {
                return of(Math.addExact(this.fast, other.fast));
            } catch (ArithmeticException overflow) {
                return of(BigInteger.valueOf(this.fast).add(BigInteger.valueOf(other.fast)));
            }
        }
        return of(toBig(this).add(toBig(other)));
    }

    public PlanAmount subtract(PlanAmount other) {
        if (this.wide == null && other.wide == null) {
            try {
                return of(Math.subtractExact(this.fast, other.fast));
            } catch (ArithmeticException overflow) {
                return of(BigInteger.valueOf(this.fast).subtract(BigInteger.valueOf(other.fast)));
            }
        }
        return of(toBig(this).subtract(toBig(other)));
    }

    public PlanAmount multiply(PlanAmount other) {
        if (this.wide == null && other.wide == null) {
            try {
                return of(Math.multiplyExact(this.fast, other.fast));
            } catch (ArithmeticException overflow) {
                return of(BigInteger.valueOf(this.fast).multiply(BigInteger.valueOf(other.fast)));
            }
        }
        return of(toBig(this).multiply(toBig(other)));
    }

    /** Java 整除语义（截断向零）；除数为零由调用方保证。 */
    public PlanAmount divide(PlanAmount other) {
        if (this.wide == null && other.wide == null) {
            return of(this.fast / other.fast);
        }
        return of(toBig(this).divide(toBig(other)));
    }

    /** 数学上取整 {@code ceil(a/b)}；除数为零由调用方保证。 */
    public PlanAmount ceilDiv(PlanAmount other) {
        if (this.wide == null && other.wide == null) {
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
        if (this.wide == null && other.wide == null) {
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
        if (this.wide == null && other.wide == null) {
            return Long.compare(this.fast, other.fast);
        }
        return toBig(this).compareTo(toBig(other));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PlanAmount other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        // 与 BigInteger 的散列兼容不必要；宽窄等值的实例需同散列。
        BigInteger exact = exactValue();
        // 小值走缓存实例的高频路径：散列取值域内一致。
        return exact.compareTo(LONG_MIN) >= 0 && exact.compareTo(LONG_MAX) <= 0
                ? Long.hashCode(exact.longValue())
                : exact.hashCode();
    }

    @Override
    public String toString() {
        return this.wide != null ? this.wide.toString() : Long.toString(this.fast);
    }
}
