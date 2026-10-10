package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.bigint.ClampedMath;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DirectProcessingStackSupport {

    private static final org.slf4j.Logger LOG = LogUtils.getLogger();

    private DirectProcessingStackSupport() {
    }

    public static List<GenericStack> normalizeStacks(List<GenericStack> rawStacks) {
        if (rawStacks == null || rawStacks.isEmpty()) {
            return List.of();
        }
        Map<AEKey, Long> merged = new LinkedHashMap<>();
        for (GenericStack stack : rawStacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            merged.merge(stack.what(), stack.amount(), ClampedMath::add);
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        List<GenericStack> normalized = new ArrayList<>(merged.size());
        for (Map.Entry<AEKey, Long> entry : merged.entrySet()) {
            normalized.add(new GenericStack(entry.getKey(), Math.max(1L, entry.getValue())));
        }
        return List.copyOf(normalized);
    }

    static Map<AEKey, Long> stackAmounts(List<GenericStack> stacks) {
        Map<AEKey, Long> amounts = new LinkedHashMap<>();
        if (stacks == null) {
            return Map.of();
        }
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            amounts.merge(stack.what(), stack.amount(), ClampedMath::add);
        }
        return amounts.isEmpty() ? Map.of() : Map.copyOf(amounts);
    }

    static boolean stacksEqual(List<GenericStack> left, List<GenericStack> right) {
        return stackAmounts(left).equals(stackAmounts(right));
    }

    static List<RecipeSignatureInput> normalizeSignatureInputs(List<RecipeSignatureInput> rawInputs) {
        if (rawInputs == null || rawInputs.isEmpty()) {
            return List.of();
        }
        Map<AEKey, Long> merged = new LinkedHashMap<>();
        for (RecipeSignatureInput input : rawInputs) {
            if (input == null || input.input() == null || input.amount() <= 0L) {
                continue;
            }
            merged.merge(input.input(), input.amount(), ClampedMath::add);
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        List<RecipeSignatureInput> normalized = new ArrayList<>(merged.size());
        for (Map.Entry<AEKey, Long> entry : merged.entrySet()) {
            normalized.add(new RecipeSignatureInput(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(normalized);
    }

    static Map<AEKey, Long> signatureInputAmounts(List<RecipeSignatureInput> inputs) {
        Map<AEKey, Long> amounts = new LinkedHashMap<>();
        if (inputs == null) {
            return Map.of();
        }
        for (RecipeSignatureInput input : inputs) {
            if (input == null || input.input() == null || input.amount() <= 0L) {
                continue;
            }
            amounts.merge(input.input(), input.amount(), ClampedMath::add);
        }
        return amounts.isEmpty() ? Map.of() : Map.copyOf(amounts);
    }

    static boolean signatureInputsEqual(List<RecipeSignatureInput> left, List<RecipeSignatureInput> right) {
        return signatureInputAmounts(left).equals(signatureInputAmounts(right));
    }

    static List<RecipeSignatureInput> toSignatureInputs(List<GenericStack> rawStacks) {
        if (rawStacks == null || rawStacks.isEmpty()) {
            return List.of();
        }
        List<RecipeSignatureInput> inputs = new ArrayList<>(rawStacks.size());
        for (GenericStack stack : rawStacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            inputs.add(new RecipeSignatureInput(stack.what(), stack.amount()));
        }
        return normalizeSignatureInputs(inputs);
    }

    static List<GenericStack> toGenericStacks(List<RecipeSignatureInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<GenericStack> stacks = new ArrayList<>(inputs.size());
        for (RecipeSignatureInput input : inputs) {
            if (input != null && input.input() != null && input.amount() > 0L) {
                stacks.add(new GenericStack(input.input(), input.amount()));
            }
        }
        return normalizeStacks(stacks);
    }

    /** 样板自身的一份确定输入；用于区分样板倍率与 CPU 一次投入的批量份数。 */
    static List<RecipeSignatureInput> patternInputs(appeng.api.crafting.IPatternDetails pattern) {
        List<GenericStack> inputs = new ArrayList<>();
        for (var input : pattern.getInputs()) {
            var options = input.getPossibleInputs();
            if (options.length != 1 || options[0] == null || options[0].what() == null) return List.of();
            long amount = multiplyOrZero(options[0].amount(), input.getMultiplier());
            if (amount <= 0) return List.of();
            inputs.add(new GenericStack(options[0].what(), amount));
        }
        return toSignatureInputs(inputs);
    }

    /** 用输出倍率约束替换输入，只接受机器索引中存在的完整配方。 */
    static List<RecipeSignatureInput> patternInputs(appeng.api.crafting.IPatternDetails pattern,
                                                   RecipeSignature signature) {
        return patternInputs(pattern, signature, -1, null);
    }

    static List<RecipeSignatureInput> patternInputs(appeng.api.crafting.IPatternDetails pattern,
                                                   RecipeSignature signature, int forcedSlot, AEKey forcedKey) {
        Long scale = deriveExecutionCountFromStacks(pattern.getOutputs(), signature.outputs());
        if (scale == null) return List.of();
        var required = scaleStacks(toGenericStacks(signature.inputs()), scale);
        if (required.isEmpty()) return List.of();
        var deterministic = patternInputs(pattern);
        if (!deterministic.isEmpty()) {
            if (forcedSlot >= 0 && !pattern.getInputs()[forcedSlot].getPossibleInputs()[0].what().equals(forcedKey)) {
                return List.of();
            }
            return signatureInputsEqual(deterministic, toSignatureInputs(required)) ? deterministic : List.of();
        }
        var slots = pattern.getInputs();
        var remaining = new LinkedHashMap<>(stackAmounts(required));
        // 逐槽选择完整样板量；不再把一个替代槽拆成数个物品流量。
        int[] order = java.util.stream.IntStream.range(0, slots.length).boxed()
                .sorted(java.util.Comparator.comparingInt(slot -> slots[slot].getPossibleInputs().length))
                .mapToInt(Integer::intValue).toArray();
        return matchWholeInputs(slots, order, 0, remaining, forcedSlot, forcedKey, new java.util.HashSet<>())
                ? toSignatureInputs(required) : List.of();
    }

    private static boolean matchWholeInputs(appeng.api.crafting.IPatternDetails.IInput[] inputs,
                                            int[] order, int cursor, Map<AEKey, Long> remaining,
                                            int forcedSlot, AEKey forcedKey,
                                            java.util.Set<InputMatchState> declined) {
        if (cursor == order.length) return remaining.values().stream().allMatch(amount -> amount == 0);
        var state = new InputMatchState(cursor, List.copyOf(remaining.values()));
        if (declined.contains(state)) return false;
        int slot = order[cursor];
        var input = inputs[slot];
        for (var candidate : input.getPossibleInputs()) {
            if (slot == forcedSlot && !candidate.what().equals(forcedKey)) continue;
            long amount = multiplyOrZero(candidate.amount(), input.getMultiplier());
            long available = remaining.getOrDefault(candidate.what(), 0L);
            if (amount <= 0 || available < amount) continue;
            remaining.put(candidate.what(), available - amount);
            if (matchWholeInputs(inputs, order, cursor + 1, remaining, forcedSlot, forcedKey, declined)) return true;
            remaining.put(candidate.what(), available);
        }
        declined.add(state);
        return false;
    }

    /** 相同剩余材料与待选槽只核验一次，避免重复候选产生指数级重试。 */
    private record InputMatchState(int cursor, List<Long> remaining) { }

    static List<GenericStack> scaleStacks(List<GenericStack> stacks, long scale) {
        if (stacks == null || stacks.isEmpty() || scale <= 0L) {
            return List.of();
        }
        List<GenericStack> scaled = new ArrayList<>(stacks.size());
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            long scaledAmount = multiplyOrZero(stack.amount(), scale);
            if (scaledAmount <= 0L) {
                return List.of();
            }
            scaled.add(new GenericStack(stack.what(), scaledAmount));
        }
        return normalizeStacks(scaled);
    }

    /**
     * 由 holder 实收量反推执行份数。份数口径与 {@link ProcessingTask} 的执行计数同为
     * long——这里曾夹在 {@code Integer.MAX_VALUE}，把直连机整批压在 2.1e9 份上。
     */
    @Nullable
    static Long deriveExecutionCount(
            List<RecipeSignatureInput> selectedInputs,
            List<RecipeSignatureInput> inputsPerExecution
    ) {
        Map<AEKey, Long> selectedAmounts = signatureInputAmounts(selectedInputs);
        Map<AEKey, Long> requiredAmounts = signatureInputAmounts(inputsPerExecution);
        if (selectedAmounts.isEmpty() || selectedAmounts.size() != requiredAmounts.size()
                || !selectedAmounts.keySet().equals(requiredAmounts.keySet())) {
            return null;
        }
        Long ratio = null;
        for (Map.Entry<AEKey, Long> entry : requiredAmounts.entrySet()) {
            long selectedAmount = selectedAmounts.getOrDefault(entry.getKey(), 0L);
            long requiredAmount = entry.getValue();
            if (requiredAmount <= 0L || selectedAmount < requiredAmount || selectedAmount % requiredAmount != 0L) {
                return null;
            }
            long candidateRatio = selectedAmount / requiredAmount;
            if (candidateRatio <= 0L) {
                return null;
            }
            if (ratio == null) {
                ratio = candidateRatio;
                continue;
            }
            if (ratio.longValue() != candidateRatio) {
                return null;
            }
        }
        return ratio;
    }

    @Nullable
    static Long deriveExecutionCountFromStacks(
            List<GenericStack> selectedStacks,
            List<GenericStack> stacksPerExecution
    ) {
        return deriveExecutionCount(toSignatureInputs(selectedStacks), toSignatureInputs(stacksPerExecution));
    }

    static long multiplyOrZero(long amount, long count) {
        if (amount <= 0L || count <= 0L) {
            return 0L;
        }
        if (amount > Long.MAX_VALUE / count) {
            LOG.warn("Direct processing stack amount {} x {} overflows long: stack marked unusable", amount, count);
            return 0L;
        }
        return amount * count;
    }
}
