package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.List;
import java.util.Map;

public record RecipeSignature(
        MachineRecipeKind kind,
        List<RecipeSignatureInput> inputs,
        List<GenericStack> outputs
) {
    public RecipeSignature {
        Map<AEKey, Long> inputAmounts = DirectProcessingStackSupport.signatureInputAmounts(inputs);
        Map<AEKey, Long> outputAmounts = DirectProcessingStackSupport.stackAmounts(outputs);
        inputs = inputAmounts.entrySet().stream()
                .map(entry -> new RecipeSignatureInput(entry.getKey(), entry.getValue()))
                .toList();
        outputs = outputAmounts.entrySet().stream()
                .map(entry -> new GenericStack(entry.getKey(), entry.getValue()))
                .toList();
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
