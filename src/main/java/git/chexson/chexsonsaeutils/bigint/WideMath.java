package git.chexson.chexsonsaeutils.bigint;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

/**
 * 环 scale 的双轨实现：宽域整数闭式与 legacy double 链。
 *
 * <p>整数闭式 {@code ceil(a·b/c) = (a·b + c − 1) / c}、{@code floor(a·b/c) = (a·b) / c}
 * （正数域），分子走 {@link PlanAmount#multiply}，任意量级零精度损失。
 * legacy 分支必须逐位复刻原 double 运算顺序（先 {@code scale = (double) b / c}
 * 再 {@code a × scale}），乘除不满足结合律，任何重排都会破坏逐位回归。
 *
 * <p>包边界冻结登记：{@link #wideEnabled()} 读 config 的兼容性开关，这是 bigint 包唯一一条
 * 向外依赖，K1 搬包不切断。切断要把 {@code wideEnabled()} 的判断推到全部调用面，
 * 那是行为变更，不归搬包这一刀。三条现量读数登记在 K1 第5笔的验收板面，不写在这里以免腐烂。
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
     * {@code long} 读数 × 宽域量（每发量 × 发数、槽倍率 × 份数这一类乘算的共用口径）。
     *
     * <p>与 {@link #mul(long, long)} 同一把尺子：两数都还在 long 域时逐字走
     * {@link PlanAmount} 的 long 快路径（高半乘积判溢出，越界才整程升宽表），
     * 不做任何一次窄化。求解侧的供需乘算只有这一个入口——求解器与闸门各写一份同形乘算，
     * 一份改了另一份没改就是"两侧不同尺"的起点。
     */
    public static PlanAmount mul(long a, PlanAmount b) {
        return PlanAmount.of(a).multiply(b);
    }

    /**
     * {@code ceil(amount × total / divisor)}（divisor &gt; 0），但 {@code total} 允许超
     * {@code long}，且交回<b>精确宽值</b>——环宏的发射向量用它，不再把发数截到天花板：
     * 一份超界的还种加成被饱和成 {@code Long.MAX_VALUE} 之后，成员发数、守恒闸门与任务表
     * 会各自按不同的截断值算账。
     *
     * <p>{@code total} 在 long 域内且中间乘积不溢出时，结果与 {@link #ceilMulDiv} 的宽域分支
     * 逐位相同（同一条 {@code ceilDiv}），所以窄计划零行为变化。关掉宽域开关时逐位复刻
     * legacy double 链（先 {@code scale = total / divisor} 再乘 amount，乘除不满足结合律）。
     */
    public static PlanAmount wideCeilMulDiv(long amount, PlanAmount total, long divisor) {
        if (!wideEnabled()) {
            double legacyTotal = (double) (total.isFast() ? total.fastValue() : Ae2Amounts.toLong(total));
            return PlanAmount.of((long) Math.ceil((double) amount * (legacyTotal / (double) divisor)));
        }
        if (total.isFast()) {
            long planned = total.fastValue();
            long numerator = amount * planned;
            if (noOverflow(amount, planned, numerator)) {
                return PlanAmount.of(Math.ceilDiv(numerator, divisor));
            }
        }
        return total.multiply(PlanAmount.of(amount)).ceilDiv(PlanAmount.of(divisor));
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

    /**
     * {@code ceil(value / divisor)}（{@code divisor > 0}）在 long 域内的<b>精确</b>算式。
     *
     * <p>写成惯常的 {@code (value + divisor - 1) / divisor} 在 {@code value} 顶到
     * {@code Long.MAX_VALUE} 时先回绕成负数：{@code > long} 的订单正是按
     * {@code Long.MAX_VALUE} 一轮一轮喂进求解器的，于是"这一批要发射几发"带着负号进了份数账
     * （实机 2026-09-24 13:50 取证行里的 {@code times=-13216328854969394}）。
     * 商加余数的写法不做任何一次加法，值域与 {@code Math.ceilDiv} 一致而不会中途回绕。
     *
     * <p>与旧算式在 {@code value + divisor - 1} 不溢出的全部场合逐位相同，窄计划零行为变化。
     */
    public static long ceilDivExact(long value, long divisor) {
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    /**
     * {@code a × b} 是否精确落在 {@code long} 域内——<b>本仓唯一一处"越不越域"判据</b>。
     *
     * <p>调用方问的是"这一乘还表不表示得出来"，不是"读数是不是天花板"。拿
     * {@link #wideMul} 交回的 {@code Long.MAX_VALUE} 反过来当判别哨就是<b>把哨兵当量</b>：
     * 真值恰好等于 {@code 9223372036854775807} 的那一乘会被误判成"越域"，而越域的那一乘
     * 又会被误读成"有一天花板那么多"。判域用这一句，取值用 {@link #mul}，两条各走各的。
     */
    public static boolean mulFitsLong(long a, long b) {
        if (a == 0L) {
            return true;
        }
        long product = a * b;
        return noOverflow(a, b, product);
    }

    /**
     * 一轮最多能伸出去多少<b>单位数</b>：切到"单位数 × 每件数"仍装得进 {@code long} 为止。
     *
     * <p>分轮轮长的唯一口径，求解侧两条需求口共用（{@code LinearPlanner#request(Site, PlanAmount)}
     * 与 {@code LinearPlanner#requestSlotUnits}）。轮长不许直接取
     * {@code Long.MAX_VALUE}：那是"单位数"的天花板，不是"件数"的——站点第一句就要把单位数
     * 乘成件数，按 {@code Long.MAX} 切轮等于让那一句在轮内饱和，尾段静默少产
     * （#211，用例 {@code WidePerSiteDemandTest} 排够臂钉的正是这一型）。
     */
    public static long roundUnits(long amountPerUnit) {
        return Long.MAX_VALUE / Math.max(1L, amountPerUnit);
    }

    /** {@code a * b == product} 是否在 long 域内精确（0 与 MIN_VALUE 边界已覆盖）。 */
    private static boolean noOverflow(long a, long b, long product) {
        // 128 位有符号乘积的高半部必须等于低半部的符号扩展，免除整数除法。
        return Math.multiplyHigh(a, b) == (product >> 63);
    }
}
