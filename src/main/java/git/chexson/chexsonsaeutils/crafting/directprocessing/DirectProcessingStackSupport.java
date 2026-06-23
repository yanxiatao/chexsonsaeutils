package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DirectProcessingStackSupport {

    private static final Comparator<GenericStack> STACK_COMPARATOR = Comparator
            .comparing((GenericStack stack) -> stack.what().toString())
            .thenComparingLong(GenericStack::amount);

    private static final Comparator<RecipeSignatureInput> INPUT_COMPARATOR = Comparator
            .comparing((RecipeSignatureInput input) -> input.input().toString())
            .thenComparingLong(RecipeSignatureInput::amount);

    private DirectProcessingStackSupport() {
    }

    public static List<GenericStack> normalizeStacks(GenericStack[] rawStacks) {
        if (rawStacks == null) {
            return List.of();
        }
        return normalizeStacks(List.of(rawStacks));
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
        normalized.sort(STACK_COMPARATOR);
        return List.copyOf(normalized);
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
        normalized.sort(INPUT_COMPARATOR);
        return List.copyOf(normalized);
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
        if (selectedInputs == null || inputsPerExecution == null
                || selectedInputs.isEmpty() || inputsPerExecution.isEmpty()
                || selectedInputs.size() != inputsPerExecution.size()) {
            return null;
        }
        Integer ratio = null;
        for (int index = 0; index < inputsPerExecution.size(); index++) {
            RecipeSignatureInput selected = selectedInputs.get(index);
            RecipeSignatureInput required = inputsPerExecution.get(index);
            if (selected == null || required == null || selected.input() == null || required.input() == null) {
                return null;
            }
            if (!selected.input().equals(required.input()) || selected.amount() < required.amount()) {
                return null;
            }
            if (selected.amount() % required.amount() != 0L) {
                return null;
            }
            long candidateRatio = selected.amount() / required.amount();
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
