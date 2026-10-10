package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.networking.IGrid;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.blockentity.crafting.AbstractExtremeAssemblerHostBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.ExtremeAssemblerBlockEntity;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

public record CraftingHostLocator(BlockPos blockPos) {

    private static final String NBT_BLOCK_POS = "blockPos";

    public static CraftingHostLocator fromHost(HostBatchProvider host) {
        return new CraftingHostLocator(host.getBlockPos().immutable());
    }

    public CompoundTag writeToTag() {
        CompoundTag tag = new CompoundTag();
        if (blockPos != null) {
            tag.putLong(NBT_BLOCK_POS, blockPos.asLong());
        }
        return tag;
    }

    public static @Nullable CraftingHostLocator readFromTag(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(NBT_BLOCK_POS)) {
            return null;
        }
        return new CraftingHostLocator(BlockPos.of(tag.getLong(NBT_BLOCK_POS)));
    }

    public @Nullable AbstractExtremeAssemblerHostBlockEntity resolve(@Nullable CraftingService craftingService) {
        if (craftingService == null || blockPos == null) {
            return null;
        }
        IGrid grid = ((CraftingServiceAccessor) craftingService).chexsonsaeutils$getGrid();
        if (grid == null) {
            return null;
        }
        for (AbstractExtremeAssemblerHostBlockEntity host : grid.getMachines(ExtremeAssemblerBlockEntity.class)) {
            if (host != null && blockPos.equals(host.getBlockPos())) {
                return host;
            }
        }
        return null;
    }

    public boolean matches(@Nullable HostBatchProvider host) {
        return host != null && blockPos != null && blockPos.equals(host.getBlockPos());
    }
}
