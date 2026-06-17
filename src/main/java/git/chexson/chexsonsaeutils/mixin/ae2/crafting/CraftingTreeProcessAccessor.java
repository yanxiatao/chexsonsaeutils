package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingTreeProcess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingTreeProcess.class, remap = false)
public interface CraftingTreeProcessAccessor {

    @Accessor(value = "details", remap = false)
    IPatternDetails chexsonsaeutils$getDetails();
}
