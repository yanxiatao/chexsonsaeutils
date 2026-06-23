package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 同色样板压缩环视图。
 *
 * 只描述 planning 需要的净输入、净输出、催化物和入口点。
 */
public record DyeablePatternCompressedRing(
        KeyCounter netInputs,
        KeyCounter netOutputs,
        KeyCounter catalysts,
        Map<IPatternDetails, Integer> executionRatio,
        Set<AEKey> entryPoints,
        boolean calculable
) {

    @Nullable
    public static DyeablePatternCompressedRing calculate(@Nullable Collection<? extends IPatternDetails> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return null;
        }

        KeyCounter totalOutputs = new KeyCounter();
        Map<AEKey, Set<IPatternDetails>> inputPatternsByKey = new HashMap<>();
        Map<AEKey, Set<IPatternDetails>> outputPatternsByKey = new HashMap<>();
        for (var pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            for (GenericStack output : pattern.getOutputs()) {
                if (output != null && output.what() != null && output.amount() > 0L) {
                    totalOutputs.add(output.what(), output.amount());
                    outputPatternsByKey.computeIfAbsent(output.what(), ignored -> new HashSet<>()).add(pattern);
                }
            }
        }

        KeyCounter totalInputs = new KeyCounter();
        Map<IPatternDetails, Integer> executionRatio = new HashMap<>();

        for (var pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            executionRatio.put(pattern, 1);

            for (var input : pattern.getInputs()) {
                GenericStack baseStack = selectRingInput(input, totalOutputs);
                if (baseStack == null || baseStack.what() == null || baseStack.amount() <= 0L) {
                    continue;
                }
                totalInputs.add(baseStack.what(), baseStack.amount() * input.getMultiplier());
                inputPatternsByKey.computeIfAbsent(baseStack.what(), ignored -> new HashSet<>()).add(pattern);
            }
        }

        if (executionRatio.isEmpty()) {
            return null;
        }

        KeyCounter netInputs = new KeyCounter();
        KeyCounter netOutputs = new KeyCounter();
        KeyCounter catalysts = new KeyCounter();

        Set<AEKey> allKeys = new HashSet<>(totalInputs.keySet());
        allKeys.addAll(totalOutputs.keySet());
        for (AEKey key : allKeys) {
            long in = totalInputs.get(key);
            long out = totalOutputs.get(key);
            if (in > 0L && out > 0L) {
                if (out > in) {
                    netOutputs.add(key, out - in);
                    catalysts.add(key, in);
                } else if (in > out) {
                    netInputs.add(key, in - out);
                } else {
                    if (hasSelfContainedInputAndOutput(key, inputPatternsByKey, outputPatternsByKey)) {
                        catalysts.add(key, in);
                    }
                }
            } else if (in > 0L) {
                netInputs.add(key, in);
            } else if (out > 0L) {
                netOutputs.add(key, out);
            }
        }

        boolean calculable = !netOutputs.isEmpty() || !netInputs.isEmpty();

        return new DyeablePatternCompressedRing(
                netInputs,
                netOutputs,
                catalysts,
                Map.copyOf(executionRatio),
                Set.copyOf(netOutputs.keySet()),
                calculable
        );
    }

    private static boolean hasSelfContainedInputAndOutput(
            AEKey key,
            Map<AEKey, Set<IPatternDetails>> inputPatternsByKey,
            Map<AEKey, Set<IPatternDetails>> outputPatternsByKey
    ) {
        Set<IPatternDetails> inputPatterns = inputPatternsByKey.get(key);
        Set<IPatternDetails> outputPatterns = outputPatternsByKey.get(key);
        if (inputPatterns == null || outputPatterns == null) {
            return false;
        }
        for (IPatternDetails inputPattern : inputPatterns) {
            if (outputPatterns.contains(inputPattern)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static GenericStack selectRingInput(
            IPatternDetails.IInput input,
            KeyCounter totalOutputs
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
            if (totalOutputs.get(possibleInput.what()) > 0L) {
                return possibleInput;
            }
        }
        return primary;
    }
}
