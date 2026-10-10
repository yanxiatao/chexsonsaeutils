package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NetworkCraftingSimulationState.class, remap = false)
public interface NetworkCraftingSimulationStateAccessor {
    @Accessor(value = "list", remap = false)
    KeyCounter chexsonsaeutils$getList();
}
