package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.crafting.CraftingLink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingLink.class, remap = false)
public interface CraftingLinkAccessor {

    @Accessor(value = "cpu", remap = false)
    ICraftingCPU getCpu();
}
