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
import git.chexson.chexsonsaeutils.frame.FrameDimensionImpl;
import git.chexson.chexsonsaeutils.frame.FrameStorageImpl;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

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
    private static final String NBT_MACHINE_BACKUP = "machineBackup";
    private static final String NBT_FRAME_ID = "frameId";
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

    /** 被包裹的原方块状态，null 表示未包裹任何方块（渲染与收敛用）。 */
    @Nullable
    private BlockState capturedState;
    /** 机器完整 NBT 备份（saveWithId 输出，含 BE 类型 id）：崩溃中间态幂等收敛时用于在私有维度重物化。 */
    @Nullable
    private CompoundTag machineBackup;
    /** 框架唯一 ID：主世界框架 ↔ 私有维度机器的关联键（FrameStorage 映射表）。 */
    @Nullable
    private UUID frameId;
    /** 搬回协议执行中标志：防止嵌套 setBlock 触发本方块 onRemove 时递归恢复（B1 时序教训）。 */
    private boolean restoring;

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
        if (machineBackup != null) {
            data.put(NBT_MACHINE_BACKUP, machineBackup);
        }
        if (frameId != null) {
            data.putUUID(NBT_FRAME_ID, frameId);
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
        machineBackup = data.contains(NBT_MACHINE_BACKUP) ? data.getCompound(NBT_MACHINE_BACKUP) : null;
        if (machineBackup == null && data.contains("capturedTypeId") && data.contains("capturedData")) {
            // 阶段 1 旧存档兼容：capturedTypeId（字符串）+ capturedData（saveAdditional 输出，不含 id）
            // 组装为 saveWithId 格式 {id: "...", ...业务字段}，loadStatic 依赖 id 字段解析 BE 类型
            CompoundTag legacyBackup = new CompoundTag();
            legacyBackup.putString("id", data.getString("capturedTypeId"));
            legacyBackup.merge(data.getCompound("capturedData"));
            machineBackup = legacyBackup;
        }
        frameId = data.contains(NBT_FRAME_ID) ? data.getUUID(NBT_FRAME_ID) : null;
    }

    /**
     * 捕获目标方块：把原机器（方块 + BE NBT）搬移到私有维度真实运行，主世界替换为框架方块。
     * <p>
     * 协议：saveWithId 备份 → 私有维度 allocateNextPosition 放置机器 → 登记 frameId 映射并强制加载
     * → 主世界 setBlock 替换为框架方块。原机器 BE 在 setBlock 前已被 LevelChunk 移出映射表，
     * 其 onRemove 中 getBlockEntity 返回 null，不会掉落内部物品。
     * <p>
     * 调用前提：pos 处仍是目标方块（本方法由
     * {@link git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem#useOn} 在服务端调用）。
     *
     * @param level 目标方块所在世界（必须为服务端）
     * @param pos   目标方块位置
     */
    public static void captureBlock(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("捕获必须在服务端执行，位置 " + pos);
        }
        BlockState targetState = level.getBlockState(pos);
        BlockEntity targetBlockEntity = level.getBlockEntity(pos);
        CompoundTag machineTag = null;
        if (targetBlockEntity != null) {
            // saveWithId 包含 BE 类型 id 与 saveAdditional 数据，私有维度重建与崩溃收敛都可用
            machineTag = targetBlockEntity.saveWithId(level.registryAccess());
        }
        MinecraftServer server = serverLevel.getServer();
        ServerLevel frameLevel = FrameDimensionImpl.instance().getLevel(server);
        FrameStorageImpl storage = FrameStorageImpl.instance();
        // 1. 分配私有维度位置并放置机器（真实运行，ticking 由 FrameTicketController 驱动）
        BlockPos framePos = storage.allocateNextPosition(frameLevel);
        frameLevel.setBlock(framePos, targetState, Block.UPDATE_ALL);
        if (machineTag != null) {
            BlockEntity machine = BlockEntity.loadStatic(framePos, targetState, machineTag, frameLevel.registryAccess());
            if (machine != null) {
                frameLevel.setBlockEntity(machine);
            } else {
                LOGGER.error("捕获失败：私有维度无法重建机器 BE，类型 {}，位置 {}", machineTag.getString("id"), framePos);
            }
        }
        // 2. 登记映射 + 强制加载（ticking=true，机器在私有维度持续运行）
        UUID frameId = UUID.randomUUID();
        storage.putFramePosition(frameLevel, frameId, framePos);
        storage.forceload(frameLevel, pos, framePos.getX() >> 4, framePos.getZ() >> 4, true);
        // 3. 主世界替换为框架方块（原机器 BE 已出映射表，其 onRemove 不会掉落内部物品）
        level.setBlock(
                pos,
                ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        );
        if (level.getBlockEntity(pos) instanceof FramePatternProviderBlockEntity frameBlockEntity) {
            frameBlockEntity.capturedState = targetState;
            frameBlockEntity.machineBackup = machineTag;
            frameBlockEntity.frameId = frameId;
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
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        restoring = true;
        try {
            MinecraftServer server = serverLevel.getServer();
            ServerLevel frameLevel = FrameDimensionImpl.instance().getLevel(server);
            FrameStorageImpl storage = FrameStorageImpl.instance();
            UUID currentFrameId = frameId;
            CompoundTag machineTag = machineBackup;
            if (currentFrameId != null) {
                BlockPos framePos = storage.getFramePosition(frameLevel, currentFrameId);
                if (framePos != null) {
                    BlockEntity machine = frameLevel.getBlockEntity(framePos);
                    if (machine != null) {
                        machineTag = machine.saveWithId(frameLevel.registryAccess());
                    }
                    // 先摘除私有维度 BE 再移除方块，避免原方块 onRemove 把内部物品掉在私有维度
                    frameLevel.removeBlockEntity(framePos);
                    frameLevel.setBlock(framePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    storage.removeFramePosition(frameLevel, currentFrameId);
                    storage.forceload(frameLevel, pos, framePos.getX() >> 4, framePos.getZ() >> 4, false);
                } else {
                    LOGGER.warn("拆除：frameId {} 无私有维度映射，使用框架内备份恢复，位置 {}", currentFrameId, pos);
                }
            }
            // 主世界恢复机器方块 + BE（嵌套 onRemove 因 restoring 标志跳过恢复逻辑）
            level.setBlock(pos, capturedState, Block.UPDATE_ALL);
            if (machineTag != null) {
                BlockEntity restoredBlockEntity = BlockEntity.loadStatic(pos, capturedState, machineTag, level.registryAccess());
                if (restoredBlockEntity != null) {
                    level.setBlockEntity(restoredBlockEntity);
                } else {
                    LOGGER.warn("恢复原 BE 失败：备份数据无法创建实例，位置 {}", pos);
                }
            }
            // TODO(阶段3): 拆除时框架自身样板/返回库存的迁移或丢弃处理
            capturedState = null;
            machineBackup = null;
            frameId = null;
            saveChanges();
        } finally {
            restoring = false;
        }
    }

    /**
     * 服务端加载时收敛捕获状态：崩溃中间态（映射缺失 / 私有维度位置无机器）从备份幂等重物化。
     * <p>
     * 收敛规则：frameId 无映射 → 从 machineBackup 重物化到新分配位置并重建映射；
     * 有映射但位置无机器 → 从 machineBackup 重物化到原位置；位置有机器 → 正常，无需处理。
     * 备份也缺失时记录 error 日志（数据不可恢复，Fail Fast）。
     */
    private void reconcileCapturedMachine() {
        if (capturedState == null || frameId == null) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        ServerLevel frameLevel = FrameDimensionImpl.instance().getLevel(server);
        FrameStorageImpl storage = FrameStorageImpl.instance();
        BlockPos framePos = storage.getFramePosition(frameLevel, frameId);
        if (framePos == null) {
            if (machineBackup == null) {
                LOGGER.error("收敛失败：frameId {} 无映射且无备份，机器数据不可恢复，位置 {}", frameId, worldPosition);
                return;
            }
            BlockPos newPos = storage.allocateNextPosition(frameLevel);
            materializeMachine(frameLevel, newPos, capturedState, machineBackup);
            storage.putFramePosition(frameLevel, frameId, newPos);
            storage.forceload(frameLevel, worldPosition, newPos.getX() >> 4, newPos.getZ() >> 4, true);
            LOGGER.warn("收敛：frameId {} 映射缺失，已从备份重物化到 {}", frameId, newPos);
            return;
        }
        BlockState privateState = frameLevel.getBlockState(framePos);
        if (privateState.isAir() || !privateState.is(capturedState.getBlock())) {
            if (machineBackup == null) {
                LOGGER.error("收敛失败：frameId {} 位置 {} 无机器且无备份，位置 {}", frameId, framePos, worldPosition);
                return;
            }
            materializeMachine(frameLevel, framePos, capturedState, machineBackup);
            LOGGER.warn("收敛：frameId {} 位置 {} 无机器，已从备份重物化", frameId, framePos);
        }
    }

    /**
     * 在私有维度按备份重建机器（方块 + BE），供捕获与收敛共用。
     *
     * @param frameLevel 私有维度
     * @param framePos   目标位置
     * @param state      机器方块状态
     * @param machineTag 机器 BE 完整备份（saveWithId 输出）
     */
    private static void materializeMachine(ServerLevel frameLevel, BlockPos framePos, BlockState state, CompoundTag machineTag) {
        frameLevel.setBlock(framePos, state, Block.UPDATE_ALL);
        if (machineTag != null) {
            BlockEntity machine = BlockEntity.loadStatic(framePos, state, machineTag, frameLevel.registryAccess());
            if (machine != null) {
                frameLevel.setBlockEntity(machine);
            } else {
                LOGGER.error("重物化失败：机器 BE 无法创建实例，位置 {}", framePos);
            }
        }
    }

    /**
     * @return true 表示搬回协议执行中（嵌套 onRemove 时用于跳过恢复逻辑）
     */
    public boolean isRestoring() {
        return restoring;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            reconcileCapturedMachine();
        }
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