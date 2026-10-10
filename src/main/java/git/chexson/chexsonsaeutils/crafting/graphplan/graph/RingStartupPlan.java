package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class RingStartupPlan {

    private RingStartupPlan() {
    }

    /**
     * 起步种量：按 {@code executionRatio} 的发射日程模拟首圈，交回被阻塞输入的起步缺口。
     *
     * <p>#218：缺口全程 {@link java.math.BigInteger}，交回宽域真值。旧写法在出口做
     * {@code longValueExact()}——越界直接抛 {@link ArithmeticException}，
     * {@code RingContraction} 与 {@code planOnce} 都不捕，崩溃替 DECLINE/报缺开了口。
     * 现在不许夹 MAX、不许吞成 0、不许截断回 long：越界的量由起步预留的缺口腿走
     * 既有 {@code request(ringSlot)} 宽域需求口，付不出就如实进缺料账（DECLINE/报缺）。
     */
    @Nullable
    public static Map<AEKey, git.chexson.chexsonsaeutils.bigint.PlanAmount> minimumSeeds(
            Map<IPatternDetails, Long> executionRatio) {
        if (executionRatio == null || executionRatio.isEmpty()) {
            return null;
        }
        Map<AEKey, BigInteger> available = new LinkedHashMap<>();
        Map<AEKey, BigInteger> seeds = new LinkedHashMap<>();
        Map<IPatternDetails, Long> remaining = new IdentityHashMap<>();
        var ordered = new ArrayList<IPatternDetails>();
        for (var entry : executionRatio.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            Long firingTimes = entry.getValue();
            if (pattern == null || firingTimes == null || firingTimes <= 0L) {
                continue;
            }
            ordered.add(pattern);
            remaining.put(pattern, firingTimes);
        }
        if (ordered.isEmpty()) {
            return null;
        }

        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (IPatternDetails pattern : ordered) {
                long remainingTimes = remaining.getOrDefault(pattern, 0L);
                if (remainingTimes <= 0L) {
                    continue;
                }
                Map<AEKey, BigInteger> inputs = inputsOf(pattern, executionRatio);
                long firingTimes = fireableTimes(available, inputs, remainingTimes);
                if (firingTimes <= 0L) {
                    continue;
                }
                fire(pattern, inputs, firingTimes, available);
                if (firingTimes == remainingTimes) {
                    remaining.remove(pattern);
                } else {
                    remaining.put(pattern, remainingTimes - firingTimes);
                }
                progressed = true;
                break;
            }
            if (progressed) {
                continue;
            }

            IPatternDetails blocked = null;
            Map<AEKey, BigInteger> blockedInputs = null;
            int blockedShortfalls = 0;
            for (IPatternDetails pattern : ordered) {
                if (remaining.getOrDefault(pattern, 0L) <= 0L) {
                    continue;
                }
                Map<AEKey, BigInteger> inputs = inputsOf(pattern, executionRatio);
                int shortfalls = shortfallCount(available, inputs);
                if (shortfalls > blockedShortfalls) {
                    blocked = pattern;
                    blockedInputs = inputs;
                    blockedShortfalls = shortfalls;
                }
            }
            if (blocked == null || blockedInputs == null || blockedInputs.isEmpty()) {
                return null;
            }
            for (var input : blockedInputs.entrySet()) {
                BigInteger present = available.getOrDefault(input.getKey(), BigInteger.ZERO);
                if (present.compareTo(input.getValue()) < 0) {
                    BigInteger shortfall = input.getValue().subtract(present);
                    seeds.merge(input.getKey(), shortfall, BigInteger::add);
                    available.put(input.getKey(), input.getValue());
                }
            }
        }

        Map<AEKey, git.chexson.chexsonsaeutils.bigint.PlanAmount> result =
                new LinkedHashMap<>();
        for (var entry : seeds.entrySet()) {
            if (entry.getValue().signum() > 0) {
                result.put(entry.getKey(),
                        git.chexson.chexsonsaeutils.bigint.PlanAmount.of(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static int shortfallCount(
            Map<AEKey, BigInteger> available,
            Map<AEKey, BigInteger> required
    ) {
        int result = 0;
        for (var entry : required.entrySet()) {
            if (available.getOrDefault(entry.getKey(), BigInteger.ZERO).compareTo(entry.getValue()) < 0) {
                result++;
            }
        }
        return result;
    }

    /**
     * 这一轮最多能发几次的上界：逐条输入按 {@code 本数 / 每发用量} 取最小值。
     *
     * <p>窄化判据与全仓同一条尺——{@code bitLength() <= 63} 才装得进 {@code long}（{@code long} 是
     * 64 位有符号）。旧写法用 {@code < 63}，把 {@code 2^62..2^63-1} 整段白拒：那一档的商压根不参与
     * 最小化，发数顶到 {@code remainingTimes} 去——上限凭空放大一整截，扣料那一腿随后按超发的量走。
     * 越出 {@code long} 的商（{@code >= 2^63}）才是"这一条不夹"的那一档。
     *
     * <p>包内可见：越界与不越界两侧的分界只能直读这一句才钉得住（{@code minimumSeeds} 的公开面上
     * 两档交回同一个种量）。
     */
    static long fireableTimes(
            Map<AEKey, BigInteger> available,
            Map<AEKey, BigInteger> required,
            long remainingTimes
    ) {
        long result = remainingTimes;
        for (var entry : required.entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            BigInteger possible = available.getOrDefault(entry.getKey(), BigInteger.ZERO)
                    .divide(entry.getValue());
            if (possible.signum() <= 0) {
                return 0L;
            }
            if (possible.bitLength() <= 63) {
                result = Math.min(result, possible.longValue());
            }
        }
        return result;
    }

    private static void fire(
            IPatternDetails pattern,
            Map<AEKey, BigInteger> inputs,
            long firingTimes,
            Map<AEKey, BigInteger> available
    ) {
        BigInteger multiplier = BigInteger.valueOf(firingTimes);
        inputs.forEach((key, amount) ->
                available.merge(key, amount.multiply(multiplier).negate(), BigInteger::add));
        outputsOf(pattern).forEach((key, amount) ->
                available.merge(key, amount.multiply(multiplier), BigInteger::add));
    }

    private static Map<AEKey, BigInteger> outputsOf(IPatternDetails pattern) {
        Map<AEKey, BigInteger> outputs = new LinkedHashMap<>();
        for (GenericStack output : pattern.getOutputs()) {
            if (output != null && output.what() != null && output.amount() > 0L) {
                outputs.merge(output.what(), BigInteger.valueOf(output.amount()), BigInteger::add);
            }
        }
        return outputs;
    }

    private static Map<AEKey, BigInteger> inputsOf(
            IPatternDetails pattern,
            Map<IPatternDetails, Long> executionRatio
    ) {
        Map<AEKey, BigInteger> required = new LinkedHashMap<>();
        for (var input : pattern.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            GenericStack selected = selectRingInput(input, executionRatio);
            if (selected == null || selected.what() == null || selected.amount() <= 0L
                    || input.getRemainingKey(selected.what()) != null
                    || !isRingOutput(selected.what(), executionRatio)) {
                continue;
            }
            BigInteger amount = BigInteger.valueOf(selected.amount())
                    .multiply(BigInteger.valueOf(input.getMultiplier()));
            if (amount.signum() > 0) {
                required.merge(selected.what(), amount, BigInteger::add);
            }
        }
        return required;
    }

    private static boolean isRingOutput(AEKey key, Map<IPatternDetails, Long> executionRatio) {
        for (IPatternDetails member : executionRatio.keySet()) {
            for (GenericStack output : member.getOutputs()) {
                if (output != null && output.what() != null && output.amount() > 0L
                        && output.what().equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static GenericStack selectRingInput(
            IPatternDetails.IInput input,
            Map<IPatternDetails, Long> executionRatio
    ) {
        GenericStack[] possibleInputs = input.getPossibleInputs();
        if (possibleInputs == null || possibleInputs.length == 0) {
            return null;
        }
        for (GenericStack candidate : possibleInputs) {
            if (candidate != null && candidate.what() != null && candidate.amount() > 0L
                    && isRingOutput(candidate.what(), executionRatio)) {
                return candidate;
            }
        }
        return possibleInputs[0];
    }
}
