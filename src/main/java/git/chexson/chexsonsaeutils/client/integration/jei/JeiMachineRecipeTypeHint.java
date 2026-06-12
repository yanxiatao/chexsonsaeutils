package git.chexson.chexsonsaeutils.client.integration.jei;

import net.minecraft.resources.ResourceLocation;

public record JeiMachineRecipeTypeHint(
        ResourceLocation machineId,
        ResourceLocation recipeTypeId,
        int defaultTicks,
        double confidence,
        String source
) {
    public static final String SOURCE = "JEI_HINT";

    public JeiMachineRecipeTypeHint {
        defaultTicks = Math.max(1, defaultTicks);
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
    }
}
