package git.chexson.chexsonsaeutils.bigint;

import java.math.BigInteger;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphDiagnostics;

/**
 * 唯一的 AE2 边界窄化出口。
 *
 * <p>宽域只活在 {@code PlanAmount}/{@code WideKeyCounter} 里；交回 AE2 前统一窄化。
 * 窄化一律向上偏置（宁高估存储与需求，不放装不下的作业进 CPU），饱和伴随诊断。
 */
public final class Ae2Amounts {
    private Ae2Amounts() {
    }

    /** 宽值 → AE2 long：超界饱和 + 诊断一行。数量语义下向上饱和（宁高估）。 */
    public static long toLong(PlanAmount amount) {
        if (amount.isFast()) {
            return amount.fastValue();
        }
        if (GraphDiagnostics.traceActive()) {
            // 窄化钳制发生在 toLong 上，而 toLong 挂在求解热路径（每次相乘都过）：
            // 大量级计划下逐次记行会把诊断缓冲直接打满，只在 trace 档记。
            GraphDiagnostics.trace("numeric.wide clamp value=%s", amount);
        }
        return amount.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    /**
     * 宽账本 → AE2 {@link KeyCounter}：逐键饱和写入。
     *
     * @return 是否发现负值（AE2 裸 {@code +=} 回绕的唯一可观测症状）；
     *         负值键不写入，调用方应 DECLINE 回落，绝不让负数进 {@code CraftingPlan}。
     */
    public static boolean toKeyCounter(WideKeyCounter source, KeyCounter target) {
        // 溢出侧表为空时（常态）走 id 形态快遍历：省掉每键一次 KeyIndex 哈希反查
        // 与一次 PlanAmount 分配；窄值就是精确值，toLong 对窄值恒等，所以逐位相同。
        if (!source.hasOverflow()) {
            source.forEachPresent((key, narrowed) -> {
                if (narrowed >= 0L) {
                    target.add(key, narrowed);
                } else if (GraphDiagnostics.active()) {
                    GraphDiagnostics.log("numeric.negative key=%s value=%d", key, narrowed);
                }
            });
            return source.hasAnyNegativeFast();
        }
        boolean negativeFound = false;
        for (AEKey key : source.keys()) {
            PlanAmount amount = source.getAmount(key);
            long narrowed = toLong(amount);
            if (narrowed < 0L) {
                negativeFound = true;
                if (GraphDiagnostics.active()) {
                    GraphDiagnostics.log("numeric.negative key=%s value=%s", key, amount);
                }
                continue;
            }
            target.add(key, narrowed);
        }
        return negativeFound;
    }

    /** 宽值是否可精确表示为 AE2 long（false 时调用方应回退，而非饱和）。 */
    public static boolean fitsLong(PlanAmount amount) {
        if (amount.isFast()) {
            return true;
        }
        BigInteger exact = amount.exactValue();
        return exact.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && exact.compareTo(WideCeilings.LONG_ACCOUNT) <= 0;
    }

    /** 诊断汇总：numeric.wide promotions/keys/maxBits（N4 接入点）。 */
    public static void logWideSummary() {
        GraphDiagnostics.log("numeric.wide promotions=%d keys=%d maxBits=%d",
                PlanAmount.promotions(), WideKeyCounter.overflowKeys(), PlanAmount.maxBits());
    }
}
