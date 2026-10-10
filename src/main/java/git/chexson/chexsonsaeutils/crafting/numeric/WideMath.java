package git.chexson.chexsonsaeutils.crafting.numeric;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

/**
 * 环 scale 的双轨实现：宽域整数闭式与 legacy double 链。
 *
 * <p>整数闭式 {@code ceil(a·b/c) = (a·b + c − 1) / c}、{@code floor(a·b/c) = (a·b) / c}
 * （正数域），分子走 {@link PlanAmount#multiply}，任意量级零精度损失。
 * legacy 分支必须逐位复刻原 double 运算顺序（先 {@code scale = (double) b / c}
 * 再 {@code a × scale}），乘除不满足结合律，任何重排都会破坏逐位回归。
 */
public final class WideMath {

    private WideMath() {
    }

    public static boolean wideEnabled() {
        return ChexsonsaeutilsCompatibilityConfig.wideAmountSolverEnabled();
    }

    /** ceil(amount × total / divisor)，divisor > 0。ceilDiv 自带进位，分子不再预加。 */
    public static long ceilMulDiv(long amount, long total, long divisor) {
        if (wideEnabled()) {
            long numerator = amount * total;
            if (noOverflow(amount, total, numerator)) {
                return Math.ceilDiv(numerator, divisor);
            }
            var wide = PlanAmount.of(amount).multiply(PlanAmount.of(total));
            return Ae2Amounts.toLong(wide.ceilDiv(PlanAmount.of(divisor)));
        }
        return (long) Math.ceil((double) amount * ((double) total / (double) divisor));
    }

    /** floor(amount × total / divisor)，divisor > 0。 */
    public static long floorMulDiv(long amount, long total, long divisor) {
        if (wideEnabled()) {
            long numerator = amount * total;
            if (noOverflow(amount, total, numerator)) {
                return Math.floorDiv(numerator, divisor);
            }
            var wide = PlanAmount.of(amount).multiply(PlanAmount.of(total));
            return Ae2Amounts.toLong(wide.divide(PlanAmount.of(divisor)));
        }
        return (long) Math.floor((double) amount * ((double) total / (double) divisor));
    }

    /** a × b 宽域精确乘，不窄化。 */
    public static PlanAmount mul(long a, long b) {
        return PlanAmount.of(a).multiply(PlanAmount.of(b));
    }

    /**
     * a × b 宽域精确乘，交回 long 接口时经 {@link Ae2Amounts#toLong} 向上饱和窄化
     * （伴随诊断一行）——取代旧的「中间乘积溢出即 DECLINE」闸门。
     *
     * <p>乘积不溢出 long 时直接返回：{@code toLong} 对窄值恒等，所以快路径与
     * 宽路径逐位相同。求解热路径每次 request / 每个输入槽都要走这里，此前每次
     * 都要构造 2-3 个 {@link PlanAmount}。
     */
    public static long wideMul(long a, long b) {
        long product = a * b;
        if (noOverflow(a, b, product)) {
            return product;
        }
        return Ae2Amounts.toLong(mul(a, b));
    }

    /** {@code a * b == product} 是否在 long 域内精确（0 与 MIN_VALUE 边界已覆盖）。 */
    private static boolean noOverflow(long a, long b, long product) {
        if (a == 0L) {
            return true;
        }
        return product / a == b && !(a == -1L && b == Long.MIN_VALUE);
    }
}
