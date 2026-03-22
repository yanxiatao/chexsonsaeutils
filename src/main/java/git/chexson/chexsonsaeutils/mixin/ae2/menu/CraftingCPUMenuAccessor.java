package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftingCPUMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingCPUMenu.class, remap = false)
public interface CraftingCPUMenuAccessor {
    @Accessor(value = "cpu", remap = false)
    CraftingCPUCluster chexsonsaeutils$getCpu();
}
