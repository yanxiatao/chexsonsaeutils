package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.Ae2Amounts;
import git.chexson.chexsonsaeutils.crafting.numeric.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.numeric.WideKeyCounter;
import git.chexson.chexsonsaeutils.crafting.numeric.WideMath;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 递归样板强连通分量的压缩视图。
 *
 * <p>只描述 planning 需要的净输入、净输出、催化物、成员整数执行比和入口点。
 */
public record CompressedRing(
        KeyCounter netInputs,
        KeyCounter netOutputs,
        KeyCounter catalysts,
        Map<IPatternDetails, Long> executionRatio,
        Set<AEKey> entryPoints,
        boolean calculable
) {

    private static final int MIN_RATIO_PASSES = 64;
    private static final int MAX_RATIO_PASSES = 4096;

    @Nullable
    public static CompressedRing calculate(@Nullable Collection<? extends IPatternDetails> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return null;
        }
        if (patterns.size() > ChexsonsaeutilsCompatibilityConfig.recursiveRingMaxMembers()) {
            return notCalculable();
        }

        WideKeyCounter unscaledOutputs = new WideKeyCounter();
        Map<AEKey, Set<IPatternDetails>> inputPatternsByKey = new HashMap<>();
        Map<AEKey, Set<IPatternDetails>> outputPatternsByKey = new HashMap<>();
        Map<IPatternDetails, PatternFlow> flows = new LinkedHashMap<>();
        for (var pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            PatternFlow flow = flows.computeIfAbsent(pattern, ignored -> new PatternFlow());
            for (GenericStack output : pattern.getOutputs()) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    unscaledOutputs.add(output.what(), output.amount());
                    flow.addOutput(output.what(), output.amount());
                    outputPatternsByKey.computeIfAbsent(output.what(), ignored -> new HashSet<>()).add(pattern);
                }
            }
        }

        for (var entry : flows.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            PatternFlow flow = entry.getValue();
            for (var input : pattern.getInputs()) {
                GenericStack baseStack = selectRingInput(input, unscaledOutputs);
                if (baseStack == null || baseStack.what() == null || baseStack.amount() <= 0L) {
                    continue;
                }
                long amount = WideMath.wideMul(baseStack.amount(), input.getMultiplier());
                flow.addInput(baseStack.what(), amount);
                inputPatternsByKey.computeIfAbsent(baseStack.what(), ignored -> new HashSet<>()).add(pattern);
            }
        }

        Map<IPatternDetails, Long> executionRatio = calculateExecutionRatio(flows);
        if (executionRatio == null || executionRatio.isEmpty()) {
            return notCalculable();
        }

        WideKeyCounter totalInputs = new WideKeyCounter();
        WideKeyCounter totalOutputs = new WideKeyCounter();
        for (var entry : flows.entrySet()) {
            long ratio = executionRatio.get(entry.getKey());
            entry.getValue().addScaledTo(totalInputs, totalOutputs, ratio);
        }

        KeyCounter netInputs = new KeyCounter();
        KeyCounter netOutputs = new KeyCounter();
        KeyCounter catalysts = new KeyCounter();
        Set<AEKey> allKeys = new LinkedHashSet<>();
        totalInputs.keys().forEach(allKeys::add);
        totalOutputs.keys().forEach(allKeys::add);
        for (AEKey key : allKeys) {
            PlanAmount in = totalInputs.getAmount(key);
            PlanAmount out = totalOutputs.getAmount(key);
            if (in.signum() > 0 && out.signum() > 0) {
                int comparison = out.compareTo(in);
                PlanAmount overlap = in.compareTo(out) <= 0 ? in : out;
                if (comparison > 0) {
                    netOutputs.add(key, Ae2Amounts.toLong(out.subtract(in)));
                } else if (comparison < 0) {
                    netInputs.add(key, Ae2Amounts.toLong(in.subtract(out)));
                }
                if (participatesInRingFlow(key, inputPatternsByKey, outputPatternsByKey)) {
                    catalysts.add(key, Ae2Amounts.toLong(overlap));
                }
            } else if (in.signum() > 0) {
                netInputs.add(key, Ae2Amounts.toLong(in));
            } else if (out.signum() > 0) {
                netOutputs.add(key, Ae2Amounts.toLong(out));
            }
        }

        boolean calculable = !netOutputs.isEmpty() || !netInputs.isEmpty();
        return new CompressedRing(
                netInputs,
                netOutputs,
                catalysts,
                executionRatio,
                Collections.unmodifiableSet(new LinkedHashSet<>(netOutputs.keySet())),
                calculable
        );
    }

    @Nullable
    private static Map<IPatternDetails, Long> calculateExecutionRatio(
            Map<IPatternDetails, PatternFlow> flows
    ) {
        if (flows.isEmpty()) {
            return null;
        }
        Set<AEKey> produced = new LinkedHashSet<>();
        Set<AEKey> consumed = new LinkedHashSet<>();
        for (PatternFlow flow : flows.values()) {
            produced.addAll(flow.outputs.keySet());
            consumed.addAll(flow.inputs.keySet());
        }
        produced.retainAll(consumed);

        Map<IPatternDetails, BigInteger> ratios = new LinkedHashMap<>();
        flows.keySet().forEach(pattern -> ratios.put(pattern, BigInteger.ONE));
        int passLimit = Math.min(MAX_RATIO_PASSES,
                Math.max(MIN_RATIO_PASSES, flows.size() * Math.max(1, produced.size()) * 16));
        for (int pass = 0; pass < passLimit; pass++) {
            boolean changed = false;
            for (AEKey key : produced) {
                BigInteger balance = BigInteger.ZERO;
                for (var entry : flows.entrySet()) {
                    balance = balance.add(entry.getValue().net(key).multiply(ratios.get(entry.getKey())));
                }
                if (balance.signum() >= 0) {
                    continue;
                }

                IPatternDetails selected = null;
                BigInteger selectedContribution = BigInteger.ZERO;
                for (var entry : flows.entrySet()) {
                    BigInteger contribution = entry.getValue().net(key);
                    if (contribution.compareTo(selectedContribution) > 0) {
                        selected = entry.getKey();
                        selectedContribution = contribution;
                    }
                }
                if (selected == null) {
                    return null;
                }
                BigInteger deficit = balance.negate();
                BigInteger increment = deficit.add(selectedContribution).subtract(BigInteger.ONE)
                        .divide(selectedContribution);
                BigInteger updated = ratios.get(selected).add(increment);
                if (updated.bitLength() > 63) {
                    return null;
                }
                ratios.put(selected, updated);
                changed = true;
            }
            if (!changed) {
                Map<IPatternDetails, Long> result = new LinkedHashMap<>();
                ratios.forEach((pattern, ratio) -> result.put(pattern, ratio.longValueExact()));
                return java.util.Collections.unmodifiableMap(result);
            }
        }
        return null;
    }

    private static CompressedRing notCalculable() {
        return new CompressedRing(
                new KeyCounter(),
                new KeyCounter(),
                new KeyCounter(),
                Map.of(),
                Set.of(),
                false
        );
    }

    private static boolean participatesInRingFlow(
            AEKey key,
            Map<AEKey, Set<IPatternDetails>> inputPatternsByKey,
            Map<AEKey, Set<IPatternDetails>> outputPatternsByKey
    ) {
        Set<IPatternDetails> inputPatterns = inputPatternsByKey.get(key);
        Set<IPatternDetails> outputPatterns = outputPatternsByKey.get(key);
        return inputPatterns != null && !inputPatterns.isEmpty()
                && outputPatterns != null && !outputPatterns.isEmpty();
    }

    @Nullable
    private static GenericStack selectRingInput(
            IPatternDetails.IInput input,
            WideKeyCounter totalOutputs
    ) {
        GenericStack[] possibleInputs = input.getPossibleInputs();
        if (possibleInputs == null || possibleInputs.length == 0) {
            return null;
        }

        GenericStack primary = possibleInputs[0];
        if (totalOutputs == null || totalOutputs.isEmpty()) {
            return primary;
        }

        for (GenericStack possibleInput : possibleInputs) {
            if (possibleInput == null || possibleInput.what() == null || possibleInput.amount() <= 0L) {
                continue;
            }
            if (totalOutputs.getLong(possibleInput.what()) > 0L) {
                return possibleInput;
            }
        }
        return primary;
    }

    private static final class PatternFlow {
        private final Map<AEKey, BigInteger> inputs = new LinkedHashMap<>();
        private final Map<AEKey, BigInteger> outputs = new LinkedHashMap<>();

        private void addInput(AEKey key, long amount) {
            this.inputs.merge(key, BigInteger.valueOf(amount), BigInteger::add);
        }

        private void addOutput(AEKey key, long amount) {
            this.outputs.merge(key, BigInteger.valueOf(amount), BigInteger::add);
        }

        private BigInteger net(AEKey key) {
            return this.outputs.getOrDefault(key, BigInteger.ZERO)
                    .subtract(this.inputs.getOrDefault(key, BigInteger.ZERO));
        }

        private void addScaledTo(WideKeyCounter totalInputs, WideKeyCounter totalOutputs, long ratio) {
            BigInteger multiplier = BigInteger.valueOf(ratio);
            this.inputs.forEach((key, amount) -> totalInputs.add(key, amount.multiply(multiplier)));
            this.outputs.forEach((key, amount) -> totalOutputs.add(key, amount.multiply(multiplier)));
        }
    }
}
