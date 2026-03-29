package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.CraftingCalculation;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CraftingCalculation.class, remap = false)
public interface CraftingCalculationAccessor {
    @Accessor(value = "networkInv", remap = false)
    NetworkCraftingSimulationState chexsonsaeutils$getNetworkInv();
}
