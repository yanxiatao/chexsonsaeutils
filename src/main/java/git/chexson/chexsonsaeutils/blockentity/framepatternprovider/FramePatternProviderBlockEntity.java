package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.util.inv.AppEngInternalInventory;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * 框架样板供应器方块实体。
 * <p>
 * 负责持久化被包裹的原方块信息：原方块 BlockState、原 BE 类型注册名与原 BE 的 NBT 数据。
 * 阶段 3 将在此 BE 上接入 AE2 PatternProviderLogic（样板供应、推送、终端展示）。
 * <p>
 * 网格节点：REQUIRE_CHANNEL + ICraftingProvider 服务 + IGridTickable 骨架（阶段 3 填充）。
 */
public class FramePatternProviderBlockEntity extends AENetworkedBlockEntity
        implements ICraftingProvider, PatternContainer, IUpgradeableObject, IGridTickable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_CAPTURED_STATE = "capturedState";
    private static final String NBT_CAPTURED_TYPE_ID = "capturedTypeId";
    private static final String NBT_CAPTURED_DATA = "capturedData";
    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_PATTERN_INVENTORY = "patternInventory";
    private static final String NBT_RETURN_INVENTORY = "returnInventory";
    private static final int UPGRADE_SLOTS = 5;
    /** 样板槽数量：4 行 x 9 列，与 GUI 布局一致。 */
    private static final int PATTERN_SLOTS = 36;
    /** 返回库存格数：存放样板推送后未被目标机器接收的产物。 */
    private static final int RETURN_SLOTS = 9;

    private final IUpgradeInventory upgrades;
    /** 样板库存：阶段 2 仅提供 GUI 存取，阶段 3 接入 PatternProviderLogic 后由逻辑层接管。 */
    private final AppEngInternalInventory patternInventory = new AppEngInternalInventory(PATTERN_SLOTS);
    /** 返回库存：阶段 2 仅提供 GUI 存取，阶段 3 接入推送逻辑后使用。 */
    private final AppEngInternalInventory returnInventory = new AppEngInternalInventory(RETURN_SLOTS);

    /** 被包裹的原方块状态，null 表示未包裹任何方块。 */
    @Nullable
    private BlockState capturedState;
    /** 被包裹的原 BE 类型注册名，原方块无 BE 时为 null。 */
    @Nullable
    private ResourceLocation capturedTypeId;
    /** 被包裹的原 BE 的 saveAdditional 数据，原方块无 BE 时为 null。 */
    @Nullable
    private CompoundTag capturedData;

    public FramePatternProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(
                () -> ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get(),
                UPGRADE_SLOTS,
                this::saveChanges
        );
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.0)
                .addService(ICraftingProvider.class, this)
                .addService(IGridTickable.class, this);
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        patternInventory.writeToNBT(data, NBT_PATTERN_INVENTORY, registries);
        returnInventory.writeToNBT(data, NBT_RETURN_INVENTORY, registries);
        if (capturedState != null) {
            data.put(NBT_CAPTURED_STATE, NbtUtils.writeBlockState(capturedState));
        }
        if (capturedTypeId != null) {
            data.putString(NBT_CAPTURED_TYPE_ID, capturedTypeId.toString());
        }
        if (capturedData != null) {
            data.put(NBT_CAPTURED_DATA, capturedData);
        }
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        patternInventory.readFromNBT(data, NBT_PATTERN_INVENTORY, registries);
        returnInventory.readFromNBT(data, NBT_RETURN_INVENTORY, registries);
        capturedState = data.contains(NBT_CAPTURED_STATE)
                ? NbtUtils.readBlockState(
                        registries.lookupOrThrow(Registries.BLOCK),
                        data.getCompound(NBT_CAPTURED_STATE)
                )
                : null;
        capturedTypeId = data.contains(NBT_CAPTURED_TYPE_ID)
                ? ResourceLocation.tryParse(data.getString(NBT_CAPTURED_TYPE_ID))
                : null;
        capturedData = data.contains(NBT_CAPTURED_DATA) ? data.getCompound(NBT_CAPTURED_DATA) : null;
    }

    /**
     * 捕获目标方块：读取原方块 BlockState 与原 BE 的 NBT，替换为框架方块，并把捕获数据写入新框架 BE。
     * <p>
     * 调用前提：pos 处仍是目标方块（本方法由
     * {@link git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem#useOn} 在服务端调用）。
     * 原方块掉落物不产生：先移除原 BE 再 setBlock，原方块 onRemove 中 getBlockEntity 返回 null。
     *
     * @param level 目标方块所在世界
     * @param pos   目标方块位置
     */
    public static void captureBlock(Level level, BlockPos pos) {
        BlockState targetState = level.getBlockState(pos);
        BlockEntity targetBlockEntity = level.getBlockEntity(pos);
        ResourceLocation typeId = null;
        CompoundTag data = null;
        if (targetBlockEntity != null) {
            typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(targetBlockEntity.getType());
            // saveWithFullMetadata 包含 BE 类型 id 与 saveAdditional 数据，恢复时可直接 loadStatic
            data = targetBlockEntity.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(pos);
        }
        level.setBlock(
                pos,
                ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        );
        if (level.getBlockEntity(pos) instanceof FramePatternProviderBlockEntity frameBlockEntity) {
            frameBlockEntity.capturedState = targetState;
            frameBlockEntity.capturedTypeId = typeId;
            frameBlockEntity.capturedData = data;
            frameBlockEntity.saveChanges();
        } else {
            LOGGER.error("捕获失败：替换为框架方块后未找到框架 BE，位置 {}", pos);
        }
    }

    /**
     * 恢复被包裹的原方块（含 BE NBT），并清空捕获数据。
     * <p>
     * 先 setBlock 恢复原方块（此过程会移除本框架 BE，触发本方块 onRemove 但 BE 已不存在，不会递归），
     * 再按捕获的 BE 类型注册名重建原 BE 并写入 NBT。
     *
     * @param level 所在世界
     * @param pos   本框架方块位置（即原方块位置）
     */
    public void restoreCapturedBlock(Level level, BlockPos pos) {
        if (capturedState == null) {
            return;
        }
        level.setBlock(pos, capturedState, Block.UPDATE_ALL);
        if (capturedTypeId != null && capturedData != null) {
            // loadStatic 从 capturedData 中的 BE 类型 id 解析类型并调用 loadAdditional，
            // 数据损坏时内部捕获异常并返回 null（保留 setBlock 创建的默认 BE）
            BlockEntity restoredBlockEntity = BlockEntity.loadStatic(pos, capturedState, capturedData, level.registryAccess());
            if (restoredBlockEntity == null) {
                LOGGER.warn("恢复原 BE 失败：类型 {} 无法创建实例，位置 {}", capturedTypeId, pos);
            } else {
                level.setBlockEntity(restoredBlockEntity);
            }
        }
        capturedState = null;
        capturedTypeId = null;
        capturedData = null;
        saveChanges();
    }

    /**
     * @return 被包裹的原方块状态，未包裹时为 null
     */
    @Nullable
    public BlockState getCapturedState() {
        return capturedState;
    }

    /**
     * @return true 表示当前包裹了原方块
     */
    public boolean hasCapturedContent() {
        return capturedState != null;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        // TODO(阶段3): 接入 PatternProviderLogic 后返回真实样板列表
        return List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        // TODO(阶段3): 接入 PatternProviderLogic 后实现样板推送
        return false;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    @Nullable
    public IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return patternInventory;
    }

    /**
     * @return 返回库存（9 格），存放样板推送后未被目标机器接收的产物
     */
    public InternalInventory getReturnInventory() {
        return returnInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return new PatternContainerGroup(
                AEItemKey.of(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get()),
                getName(),
                List.of()
        );
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        // TODO(阶段3): 接入 PatternProviderLogic 后按需返回 tick 请求
        return new TickingRequest(1, 20, true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        // TODO(阶段3): 接入 PatternProviderLogic 后实现网格 tick
        return TickRateModulation.SLEEP;
    }
}