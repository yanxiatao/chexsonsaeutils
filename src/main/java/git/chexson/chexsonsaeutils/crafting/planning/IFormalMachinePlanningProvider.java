package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.networking.crafting.ICraftingProvider;
import net.minecraft.core.BlockPos;

public interface IFormalMachinePlanningProvider extends ICraftingProvider {
    int getCurrentOperationTicks();
    BlockPos getBlockPos();
}
