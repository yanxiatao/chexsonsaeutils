package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.mojang.logging.LogUtils;
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
            merged.merge(stack.what(), stack.amount(), DirectProcessingStackSupport::saturatingAdd);
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
            amounts.merge(stack.what(), stack.amount(), DirectProcessingStackSupport::saturatingAdd);
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
            merged.merge(input.input(), input.amount(), DirectProcessingStackSupport::saturatingAdd);
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
            amounts.merge(input.input(), input.amount(), DirectProcessingStackSupport::saturatingAdd);
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

    static List<GenericStack> scaleStacks(List<GenericStack> stacks, int scale) {
        if (stacks == null || stacks.isEmpty() || scale <= 0) {
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

    @Nullable
    static Integer deriveExecutionCount(
            List<RecipeSignatureInput> selectedInputs,
            List<RecipeSignatureInput> inputsPerExecution
    ) {
        Map<AEKey, Long> selectedAmounts = signatureInputAmounts(selectedInputs);
        Map<AEKey, Long> requiredAmounts = signatureInputAmounts(inputsPerExecution);
        if (selectedAmounts.isEmpty() || selectedAmounts.size() != requiredAmounts.size()
                || !selectedAmounts.keySet().equals(requiredAmounts.keySet())) {
            return null;
        }
        Integer ratio = null;
        for (Map.Entry<AEKey, Long> entry : requiredAmounts.entrySet()) {
            long selectedAmount = selectedAmounts.getOrDefault(entry.getKey(), 0L);
            long requiredAmount = entry.getValue();
            if (requiredAmount <= 0L || selectedAmount < requiredAmount || selectedAmount % requiredAmount != 0L) {
                return null;
            }
            long candidateRatio = selectedAmount / requiredAmount;
            if (candidateRatio <= 0L || candidateRatio > Integer.MAX_VALUE) {
                return null;
            }
            if (ratio == null) {
                ratio = (int) candidateRatio;
                continue;
            }
            if (ratio.intValue() != (int) candidateRatio) {
                return null;
            }
        }
        return ratio;
    }

    @Nullable
    static Integer deriveExecutionCountFromStacks(
            List<GenericStack> selectedStacks,
            List<GenericStack> stacksPerExecution
    ) {
        return deriveExecutionCount(toSignatureInputs(selectedStacks), toSignatureInputs(stacksPerExecution));
    }

    static long multiplyOrZero(long amount, int count) {
        if (amount <= 0L || count <= 0) {
            return 0L;
        }
        if (amount > Long.MAX_VALUE / count) {
            LOG.warn("Direct processing stack amount {} x {} overflows long: stack marked unusable", amount, count);
            return 0L;
        }
        return amount * count;
    }

    static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }
}
