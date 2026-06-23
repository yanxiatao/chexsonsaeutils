package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import net.minecraft.world.item.ItemStack;

public record BuildingGadgets2PatternEncodingResult(
        ItemStack stack,
        int totalInputTypes,
        int encodedInputTypes,
        int skippedStates
) {
    public boolean encoded() { return !stack.isEmpty(); }
    public boolean truncated() { return totalInputTypes > encodedInputTypes; }
}
