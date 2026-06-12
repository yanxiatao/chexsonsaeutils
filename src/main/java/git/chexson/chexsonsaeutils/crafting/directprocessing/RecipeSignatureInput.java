package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.stacks.AEKey;

public record RecipeSignatureInput(
        AEKey input,
        long amount
) implements Comparable<RecipeSignatureInput> {

    public RecipeSignatureInput {
        amount = Math.max(1L, amount);
    }

    @Override
    public int compareTo(RecipeSignatureInput other) {
        if (other == null) {
            return 1;
        }
        int keyCompare = input.toString().compareTo(other.input.toString());
        if (keyCompare != 0) {
            return keyCompare;
        }
        return Long.compare(amount, other.amount);
    }
}
