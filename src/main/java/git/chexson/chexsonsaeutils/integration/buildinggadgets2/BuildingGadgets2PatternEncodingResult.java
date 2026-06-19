package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import net.minecraft.world.item.ItemStack;

/**
 * BG2 copy-paste material encoding result.
 *
 * @param stack encoded AE2 processing pattern, or empty when no material could be encoded
 * @param totalInputTypes material key count before the AE2 processing input limit is applied
 * @param encodedInputTypes material key count written into the pattern
 * @param skippedStates source states skipped because they were air or produced no encodable material
 */
public record BuildingGadgets2PatternEncodingResult(
        ItemStack stack,
        int totalInputTypes,
        int encodedInputTypes,
        int skippedStates
) {

    public boolean encoded() {
        return !stack.isEmpty();
    }

    public boolean truncated() {
        return totalInputTypes > encodedInputTypes;
    }
}
