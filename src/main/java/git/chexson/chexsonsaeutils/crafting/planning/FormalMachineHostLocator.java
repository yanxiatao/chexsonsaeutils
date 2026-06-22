package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.networking.IGrid;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractHighCapacityCraftingHostBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

public record FormalMachineHostLocator(BlockPos blockPos) {

    private static final String NBT_BLOCK_POS = "blockPos";

    public static FormalMachineHostLocator fromHost(IFormalMachinePlanningProvider host) {
        return new FormalMachineHostLocator(host.getBlockPos().immutable());
    }

    public CompoundTag writeToTag() {
        CompoundTag tag = new CompoundTag();
        if (blockPos != null) {
            tag.putLong(NBT_BLOCK_POS, blockPos.asLong());
        }
        return tag;
    }

    public static @Nullable FormalMachineHostLocator readFromTag(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(NBT_BLOCK_POS)) {
            return null;
        }
        return new FormalMachineHostLocator(BlockPos.of(tag.getLong(NBT_BLOCK_POS)));
    }

    public @Nullable AbstractHighCapacityCraftingHostBlockEntity resolve(@Nullable CraftingService craftingService) {
        if (craftingService == null || blockPos == null) {
            return null;
        }
        IGrid grid = ((CraftingServiceAccessor) craftingService).chexsonsaeutils$getGrid();
        if (grid == null) {
            return null;
        }
        for (AbstractHighCapacityCraftingHostBlockEntity host : grid.getMachines(HighCapacityCraftingMachineBlockEntity.class)) {
            if (host != null && blockPos.equals(host.getBlockPos())) {
                return host;
            }
        }
        return null;
    }

    public boolean matches(@Nullable IFormalMachinePlanningProvider host) {
        return host != null && blockPos != null && blockPos.equals(host.getBlockPos());
    }
}
