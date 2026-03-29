package git.chexson.chexsonsaeutils.pattern.replacement;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class PlanningReplacementSelector {
    private PlanningReplacementSelector() {
    }

    public static @Nullable AEKey selectPlanningStack(
            IPatternDetails.IInput input,
            @Nullable Level level,
            BiPredicate<AEKey, Long> hasSufficientAmount,
            Predicate<AEKey> canEmitFor,
            Predicate<AEKey> hasExactCraftingPath,
            FuzzyCraftableResolver fuzzyCraftableResolver
    ) {
        GenericStack[] possibleInputs = input.getPossibleInputs();
        GenericStack primaryInput = possibleInputs[0];
        AEKey primaryKey = primaryInput.what();
        long multiplier = input.getMultiplier();
        if (hasSufficientAmount.test(primaryKey, primaryInput.amount() * multiplier)) {
            return primaryKey;
        }

        long acceptableAmount = primaryInput.amount();
        for (int index = 1; index < possibleInputs.length; index++) {
            GenericStack possibleInput = possibleInputs[index];
            if (possibleInput.amount() != acceptableAmount) {
                continue;
            }

            AEKey possibleKey = possibleInput.what();
            if (hasSufficientAmount.test(possibleKey, possibleInput.amount() * multiplier)) {
                return possibleKey;
            }
        }

        if (canEmitFor.test(primaryKey) || hasExactCraftingPath.test(primaryKey)) {
            return primaryKey;
        }

        for (int index = 1; index < possibleInputs.length; index++) {
            GenericStack possibleInput = possibleInputs[index];
            if (possibleInput.amount() != acceptableAmount) {
                continue;
            }

            AEKey possibleKey = possibleInput.what();
            if (canEmitFor.test(possibleKey)) {
                return possibleKey;
            }

            AEKey fuzzyCraftable = fuzzyCraftableResolver.getFuzzyCraftable(
                    possibleKey,
                    fuzzyCandidate -> input.isValid(fuzzyCandidate, level)
            );
            if (ReplacementAwareProcessingPattern.matchesAnyPossibleInput(input, fuzzyCraftable)) {
                return fuzzyCraftable;
            }
        }

        return null;
    }

    @FunctionalInterface
    public interface FuzzyCraftableResolver {
        @Nullable
        AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter);
    }
}
