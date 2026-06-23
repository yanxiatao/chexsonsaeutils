package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.IGrid;
import appeng.me.service.CraftingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingService.class, remap = false)
public interface CraftingServiceAccessor {

    @Accessor("grid")
    IGrid chexsonsaeutils$getGrid();
}
