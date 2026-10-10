package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;

public record RecipeSignatureInput(
        AEKey input,
        long amount
) {

    public RecipeSignatureInput {
        amount = Math.max(1L, amount);
    }
}
