package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.List;
import java.util.Set;

record RecipeSignatureShapeKey(
        Set<AEKey> inputs,
        Set<AEKey> outputs
) {
    RecipeSignatureShapeKey {
        inputs = inputs == null ? Set.of() : Set.copyOf(inputs);
        outputs = outputs == null ? Set.of() : Set.copyOf(outputs);
    }

    static RecipeSignatureShapeKey of(
            List<RecipeSignatureInput> inputs,
            List<GenericStack> outputs
    ) {
        Set<AEKey> normalizedInputs = DirectProcessingStackSupport.signatureInputAmounts(inputs).keySet();
        Set<AEKey> normalizedOutputs = DirectProcessingStackSupport.stackAmounts(outputs).keySet();
        if (normalizedInputs.isEmpty() || normalizedOutputs.isEmpty()) {
            return null;
        }
        return new RecipeSignatureShapeKey(normalizedInputs, normalizedOutputs);
    }

    static RecipeSignatureShapeKey of(RecipeSignature signature) {
        if (signature == null) {
            return null;
        }
        return of(signature.inputs(), signature.outputs());
    }
}
