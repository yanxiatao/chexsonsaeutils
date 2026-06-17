package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.formalmachine.IFormalMachineDelegatingPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceGetProvidersFormalScaledPatternMixin {

    @ModifyArg(
            method = "getProviders(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/me/service/helpers/NetworkCraftingProviders;getMediums(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/Iterable;"
            ),
            index = 0,
            remap = false
    )
    private IPatternDetails chexsonsaeutils$unwrapFormalScaledPattern(IPatternDetails original) {
        if (original instanceof IFormalMachineDelegatingPattern delegatingPattern) {
            return delegatingPattern.basePattern();
        }
        return original;
    }
}
