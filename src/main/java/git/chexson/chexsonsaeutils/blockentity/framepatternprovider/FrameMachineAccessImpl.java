package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

import git.chexson.chexsonsaeutils.frame.FrameDimensionImpl;
import git.chexson.chexsonsaeutils.frame.FrameStorageImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@link FrameMachineAccess} 实现：经 FrameStorage 映射解析私有维度机器并查询 capability。
 * <p>
 * 机器位置已 forceload（捕获时登记），正常可访问；仍保留 isLoaded 守卫防止
 * 卸载窗口期（拆除流程中）访问未加载 chunk。
 */
public class FrameMachineAccessImpl implements FrameMachineAccess {

    /** 空 ITEM handler：机器无 handler 或客户端查询时返回，避免外部管道 NPE。 */
    public static final IItemHandler EMPTY_ITEM_HANDLER = new IItemHandler() {
        @Override
        public int getSlots() {
            return 0;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    };

    /** 空 ENERGY handler：机器无 handler 或客户端查询时返回，避免外部访问 NPE。 */
    public static final IEnergyStorage EMPTY_ENERGY_STORAGE = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return 0;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    };

    private final FramePatternProviderBlockEntity blockEntity;

    public FrameMachineAccessImpl(FramePatternProviderBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    @Nullable
    public ServerLevel getMachineLevel() {
        if (!(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            return null;
        }
        MinecraftServer server = serverLevel.getServer();
        return FrameDimensionImpl.instance().getLevel(server);
    }

    @Override
    @Nullable
    public BlockPos getMachinePos() {
        ServerLevel frameLevel = getMachineLevel();
        UUID frameId = blockEntity.getFrameId();
        if (frameLevel == null || frameId == null) {
            return null;
        }
        return FrameStorageImpl.instance().getFramePosition(frameLevel, frameId);
    }

    @Override
    @Nullable
    public BlockEntity getMachineBlockEntity() {
        ServerLevel frameLevel = getMachineLevel();
        BlockPos framePos = getMachinePos();
        if (frameLevel == null || framePos == null) {
            return null;
        }
        // chunk 未加载守卫：拆除流程卸载窗口期可能短暂不可访问
        if (!frameLevel.isLoaded(framePos)) {
            return null;
        }
        return frameLevel.getBlockEntity(framePos);
    }

    @Override
    @Nullable
    public IItemHandler getMachineItemHandler() {
        BlockEntity machine = getMachineBlockEntity();
        if (machine == null || machine.getLevel() == null) {
            return null;
        }
        return machine.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, machine.getBlockPos(), null);
    }

    @Override
    @Nullable
    public IEnergyStorage getMachineEnergyHandler() {
        BlockEntity machine = getMachineBlockEntity();
        if (machine == null || machine.getLevel() == null) {
            return null;
        }
        return machine.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, machine.getBlockPos(), null);
    }
}