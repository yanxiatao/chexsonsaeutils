package git.chexson.chexsonsaeutils.pattern.replacement;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.InputTemplate;

import java.util.List;
import java.util.function.BiPredicate;

public final class ReplacementGroupTemplateSelector {
    private ReplacementGroupTemplateSelector() {
    }

    public static List<InputTemplate> selectTemplates(
            IPatternDetails.IInput input,
            long requestedAmount,
            BiPredicate<AEKey, Long> hasSufficientAmount
    ) {
        if (input == null || requestedAmount <= 0L) {
            return List.of();
        }

        GenericStack[] possibleInputs = input.getPossibleInputs();
        if (possibleInputs.length == 0) {
            return List.of();
        }

        long acceptableAmount = possibleInputs[0].amount();
        for (GenericStack possibleInput : possibleInputs) {
            if (possibleInput.amount() != acceptableAmount) {
                continue;
            }

            long requiredAmount = possibleInput.amount() * requestedAmount;
            if (hasSufficientAmount.test(possibleInput.what(), requiredAmount)) {
                return List.of(new InputTemplate(possibleInput.what(), possibleInput.amount()));
            }
        }

        return List.of();
    }
}
