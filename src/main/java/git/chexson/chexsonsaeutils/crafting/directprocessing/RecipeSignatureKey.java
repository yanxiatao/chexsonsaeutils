package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.List;

public record RecipeSignatureKey(
        List<RecipeSignatureInput> inputs,
        List<GenericStack> outputs
) {
    public RecipeSignatureKey {
        inputs = DirectProcessingStackSupport.normalizeSignatureInputs(inputs);
        outputs = DirectProcessingStackSupport.normalizeStacks(outputs);
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
        return new RecipeSignatureKey(inputs, outputs);
    }

    public static RecipeSignatureKey of(RecipeSignature signature) {
        if (signature == null) {
            return null;
        }
        return of(signature.inputs(), signature.outputs());
    }
}
