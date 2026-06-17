package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceDyeablePatternMixin {

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lappeng/me/service/helpers/NetworkCraftingProviders;"),
            remap = false
    )
    private NetworkCraftingProviders chexsonsaeutils$replaceCraftingProviders() {
        return new DyeablePatternCraftingProviders();
    }
}
