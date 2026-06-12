package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.List;

public record RecipeSignature(
        MachineRecipeKind kind,
        List<RecipeSignatureInput> inputs,
        List<GenericStack> outputs
) {
    public RecipeSignature {
        inputs = DirectProcessingStackSupport.normalizeSignatureInputs(inputs);
        outputs = DirectProcessingStackSupport.normalizeStacks(outputs);
    }

    public RecipeSignature(
            MachineRecipeKind kind,
            AEKey input,
            long inputAmount,
            AEKey output,
            long outputAmount
    ) {
        this(
                kind,
                List.of(new RecipeSignatureInput(input, inputAmount)),
                List.of(new GenericStack(output, outputAmount))
        );
    }
}
