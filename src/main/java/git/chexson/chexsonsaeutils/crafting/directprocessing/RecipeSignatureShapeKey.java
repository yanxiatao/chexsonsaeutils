package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.util.List;

record RecipeSignatureShapeKey(
        List<AEKey> inputs,
        List<AEKey> outputs
) {
    RecipeSignatureShapeKey {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }

    static RecipeSignatureShapeKey of(
            List<RecipeSignatureInput> inputs,
            List<GenericStack> outputs
    ) {
        List<AEKey> normalizedInputs = DirectProcessingStackSupport.normalizeSignatureInputs(inputs).stream()
                .map(RecipeSignatureInput::input)
                .toList();
        List<AEKey> normalizedOutputs = DirectProcessingStackSupport.normalizeStacks(outputs).stream()
                .map(GenericStack::what)
                .toList();
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
