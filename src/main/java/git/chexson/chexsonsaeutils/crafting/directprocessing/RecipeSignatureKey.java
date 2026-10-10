package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.List;
import java.util.Map;

public record RecipeSignatureKey(
        Map<AEKey, Long> inputs,
        Map<AEKey, Long> outputs
) {
    public RecipeSignatureKey {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public static RecipeSignatureKey of(
            AEKey input,
            long inputAmount,
            AEKey output,
            long outputAmount
    ) {
        if (input == null || output == null) {
            return null;
        }
        return of(
                List.of(new RecipeSignatureInput(input, inputAmount)),
                List.of(new GenericStack(output, outputAmount))
        );
    }

    public static RecipeSignatureKey of(
            List<RecipeSignatureInput> inputs,
            List<GenericStack> outputs
    ) {
        if (inputs == null || inputs.isEmpty() || outputs == null || outputs.isEmpty()) {
            return null;
        }
        Map<AEKey, Long> inputAmounts = DirectProcessingStackSupport.signatureInputAmounts(inputs);
        Map<AEKey, Long> outputAmounts = DirectProcessingStackSupport.stackAmounts(outputs);
        if (inputAmounts.isEmpty() || outputAmounts.isEmpty()) {
            return null;
        }
        return new RecipeSignatureKey(inputAmounts, outputAmounts);
    }

    public static RecipeSignatureKey of(RecipeSignature signature) {
        if (signature == null) {
            return null;
        }
        return of(signature.inputs(), signature.outputs());
    }
}
