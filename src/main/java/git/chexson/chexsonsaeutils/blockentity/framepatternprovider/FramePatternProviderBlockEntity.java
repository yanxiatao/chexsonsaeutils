package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNodeListener;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.blockentity.ServerTickingBlockEntity;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.frame.FrameDimensionImpl;
import git.chexson.chexsonsaeutils.frame.FrameStorageImpl;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 框架样板供应器方块实体。
 * <p>
 * 负责持久化被包裹的原方块信息：原方块 BlockState、原 BE 类型注册名与原 BE 的 NBT 数据。
 * 样板供应逻辑由 {@link FramePatternProviderLogic}（fork 自 AE2 PatternProviderLogic）承担：
 * 36 样板槽、推送私有维度机器、返回库存回收机器输出（需求 8）。
 * <p>
 * 网格节点：REQUIRE_CHANNEL；ICraftingProvider 与 IGridTickable 服务由 logic 在构造时注册
 * （字段初始化先于构造器体，logic 注册的服务不会被本类覆盖）。
 * 虚拟连接：{@link FrameLinkManager} 负责与私有维度机器的跨维度连接（非隔离并入主网格，
 * 隔离仅共享能量），连接状态由 serverTick 每 tick 驱动。
 */
public class FramePatternProviderBlockEntity extends AENetworkedBlockEntity
        implements FramePatternProviderLogicHost, IUpgradeableObject, ServerTickingBlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_CAPTURED_STATE = "capturedState";
    private static final String NBT_MACHINE_BACKUP = "machineBackup";
    private static final String NBT_FRAME_ID = "frameId";
    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_ISOLATED = "isolated";
    private static final String NBT_PAGES = "pages";
    private static final int UPGRADE_SLOTS = 5;
    /** 每页样板槽数量：4 行 x 9 列，与 GUI 布局一致。 */
    public static final int PATTERN_SLOTS_PER_PAGE = 36;

    private final IUpgradeInventory upgrades;
    /**
     * 样板供应逻辑（fork 自 AE2 PatternProviderLogic）：拥有 36 样板槽 + 9 格返回库存，
     * 推送目标为私有维度机器。字段初始化创建（与 AE2 PatternProviderBlockEntity 一致）——
     * 网格节点在 onReady 时才创建，logic 的 addService 必须在节点创建前生效。
     */
    private final FramePatternProviderLogic logic = createLogic();

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
    /** 隔离模式：true 时私有网格只与主网格共享能量（overlay 桥），不合并网格、不占频道。 */
    private boolean isolated;
    /** 已解锁样板页数（需求 5）：默认 1，范围 [1, maxFramePatternPages()]；5b 阶段由扩容物品增加。 */
    private int pages = 1;
    /** 跨维度虚拟连接管理器（与私有维度机器的网格连接）。 */
    private final FrameLinkManager linkManager = new FrameLinkManagerImpl(this);
    /** 跨维度机器访问 API（私有维度机器的库存/能量 capability 查询）。 */
    private final FrameMachineAccess machineAccess = new FrameMachineAccessImpl(this);

    public FramePatternProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(
                () -> ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get(),
                UPGRADE_SLOTS,
                this::saveChanges
        );
        // ICraftingProvider 与 IGridTickable 服务由 logic 注册（字段初始化先于本构造器体）
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.0);
    }

    /**
     * 创建样板供应逻辑：容量 = 配置的最大页数 x 每页 36 槽（固定，翻页只切换可见性）。
     */
    protected FramePatternProviderLogic createLogic() {
        return new FramePatternProviderLogic(this.getMainNode(), this,
                ChexsonsaeutilsCompatibilityConfig.maxFramePatternPages() * PATTERN_SLOTS_PER_PAGE);
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        // 样板库存/返回库存由 logic 写入（key: patterns/returnInv）
        logic.writeToNBT(data, registries);
        if (capturedState != null) {
            data.put(NBT_CAPTURED_STATE, NbtUtils.writeBlockState(capturedState));
        }
        if (machineBackup != null) {
            data.put(NBT_MACHINE_BACKUP, machineBackup);
        }
        if (frameId != null) {
            data.putUUID(NBT_FRAME_ID, frameId);
        }
        data.putBoolean(NBT_ISOLATED, isolated);
        data.putInt(NBT_PAGES, pages);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        logic.readFromNBT(data, registries);
        // 阶段 2 旧存档兼容：patternInventory/returnInventory → patterns/returnInv
        logic.migrateLegacyInventory(data, registries);
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
        isolated = data.getBoolean(NBT_ISOLATED);
        // 旧存档无 pages key：getInt 为 0，clamp 到 1
        int rawPages = data.getInt(NBT_PAGES);
        this.pages = clampPages(rawPages);
    }

    /**
     * 捕获目标方块：把原机器（方块 + BE NBT）搬移到私有维度真实运行，主世界替换为框架方块。
     * <p>
     * 协议：saveWithId 备份 → 私有维度 allocateNextPosition 放置机器 → 登记 frameId 映射并强制加载
     * → 主世界先 removeBlockEntity 摘除机器 BE → setBlock 替换为框架方块。
     * <p>
     * 时序说明（B1 修复）：1.21.1 服务端 LevelChunk.setBlockState 先调原方块 onRemove（此时 BE 仍在
     * 映射表），BE 移除发生在 onRemove 内部。熔炉等带 dropContents 的机器若直接 setBlock，内部物品会
     * 掉主世界，而 machineTag（saveWithId 含物品）已写入私有维度 → 物品复制。故 setBlock 前必须先
     * removeBlockEntity 摘除 BE，使其 onRemove 中 getBlockEntity 返回 null，不触发掉落。
     * <p>
     * 调用前提：pos 处仍是目标方块（本方法由
     * {@link git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem#useOn} 在服务端调用）。
     *
     * @param level 目标方块所在世界（必须为服务端）
     * @param pos   目标方块位置
     * @param pages 掉落物品携带的已解锁样板页数（拆除保留闭环，clamp 到 [1, maxFramePatternPages()]）
     */
    public static void captureBlock(Level level, BlockPos pos, int pages) {
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
        // 3. 先摘除主世界机器 BE 再替换为框架方块（B1 修复：防 onRemove 掉落内部物品导致复制）
        level.removeBlockEntity(pos);
        level.setBlock(
                pos,
                ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        );
        if (level.getBlockEntity(pos) instanceof FramePatternProviderBlockEntity frameBlockEntity) {
            frameBlockEntity.capturedState = targetState;
            frameBlockEntity.machineBackup = machineTag;
            frameBlockEntity.frameId = frameId;
            frameBlockEntity.setPages(pages);
            frameBlockEntity.saveChanges();
            // 捕获后立即创建虚拟节点（机器节点需等私有维度首 tick 就绪，连接由 serverTick 补建）
            frameBlockEntity.linkManager.ensureVirtualNode();
        } else {
            LOGGER.error("捕获失败：替换为框架方块后未找到框架 BE，位置 {}", pos);
        }
    }

    /**
     * 恢复被包裹的原方块（含 BE NBT），并清空捕获数据。
     * <p>
     * 先 setBlock 恢复原方块（此过程会移除本框架 BE，触发本方块 onRemove 但 BE 已不存在，不会递归），
     * 再按捕获的 BE 类型注册名重建原 BE 并写入 NBT。
     * <p>
     * 样板库存/返回库存的掉落由 onRemove 路径的 {@link #addAdditionalDrops} 处理
     * （挖掘、爆炸、拆除 setBlock 均触发 onRemove）。
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
            capturedState = null;
            machineBackup = null;
            frameId = null;
            saveChanges();
            // 机器已搬回主世界，销毁跨维度虚拟连接
            linkManager.teardownLink();
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
            if (hasCapturedContent()) {
                linkManager.ensureVirtualNode();
            }
        }
    }

    @Override
    public void onReady() {
        super.onReady();
        // 节点就绪后刷新样板列表（loadTag 已由 logic 读取，此处触发 requestUpdate 让网格感知）
        this.logic.updatePatterns();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        this.logic.onMainNodeStateChanged();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        linkManager.teardownLink();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // logic 无独立资源（mainNode 由 super.setRemoved 销毁），仅销毁跨维度虚拟连接
        linkManager.teardownLink();
    }

    @Override
    public void serverTick() {
        linkManager.tick();
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

    /**
     * @return 框架唯一 ID（FrameStorage 映射键），未捕获时为 null
     */
    @Nullable
    public UUID getFrameId() {
        return frameId;
    }

    /**
     * @return true 表示隔离模式（私有网格只与主网格共享能量）
     */
    public boolean isIsolated() {
        return isolated;
    }

    /**
     * 切换隔离模式并重建跨维度连接拓扑。
     * <p>
     * 非隔离：机器并入主网格（共享频道与存储）；隔离：仅经 overlay 桥共享能量。
     *
     * @param isolated 目标隔离状态
     */
    public void setIsolated(boolean isolated) {
        if (this.isolated == isolated) {
            return;
        }
        this.isolated = isolated;
        saveChanges();
        linkManager.rebuild();
    }

    /**
     * @return 已解锁样板页数（默认 1，clamp 到 [1, maxFramePatternPages()]）
     */
    public int getPages() {
        return pages;
    }

    /**
     * 设置已解锁样板页数（clamp 到 [1, maxFramePatternPages()]，越界值收敛并告警）。
     * <p>
     * 注意：loadTag 路径不调用本方法（加载期间 saveChanges 会触发过早写盘），直接赋值字段。
     */
    public void setPages(int pages) {
        this.pages = clampPages(pages);
        saveChanges();
    }

    /**
     * 页数收敛到 [1, maxFramePatternPages()]；发生截断时输出告警日志（I1 修复，loadTag/setPages 共用）。
     *
     * @param rawPages 待收敛的原始页数
     * @return 收敛后的页数
     */
    private int clampPages(int rawPages) {
        int max = ChexsonsaeutilsCompatibilityConfig.maxFramePatternPages();
        int clamped = Math.max(1, Math.min(rawPages, max));
        if (clamped != rawPages) {
            LOGGER.warn("样板页数截断：old={}, new={}, maxPages={}", rawPages, clamped, max);
        }
        return clamped;
    }

    /**
     * @return 跨维度机器访问 API（私有维度机器的库存/能量 capability 查询）
     */
    public FrameMachineAccess getMachineAccess() {
        return machineAccess;
    }

    /**
     * 外部 ITEM capability 透传：服务端返回私有维度机器的 IItemHandler，客户端返回空实现。
     * <p>
     * 样板库存/返回库存是 AE2 内部库存（走网格存储），capability 透传只针对外部管道访问；
     * 客户端无法访问服务端私有维度，Waila/Jade 等客户端查询场景后置处理（阶段 6），
     * 此处返回空实现避免 NPE。
     */
    public IItemHandler getMachineItemHandler() {
        if (level == null || level.isClientSide()) {
            return FrameMachineAccessImpl.EMPTY_ITEM_HANDLER;
        }
        IItemHandler handler = machineAccess.getMachineItemHandler();
        return handler != null ? handler : FrameMachineAccessImpl.EMPTY_ITEM_HANDLER;
    }

    /**
     * 外部 ENERGY capability 透传：服务端返回私有维度机器的 IEnergyStorage，客户端返回空实现。
     * <p>
     * appflux 灌电路径基础（阶段 6 用）；客户端无法访问服务端私有维度，返回空实现避免 NPE。
     */
    public IEnergyStorage getMachineEnergyHandler() {
        if (level == null || level.isClientSide()) {
            return FrameMachineAccessImpl.EMPTY_ENERGY_STORAGE;
        }
        IEnergyStorage handler = machineAccess.getMachineEnergyHandler();
        return handler != null ? handler : FrameMachineAccessImpl.EMPTY_ENERGY_STORAGE;
    }

    @Override
    public FramePatternProviderLogic getLogic() {
        return logic;
    }

    @Override
    public EnumSet<Direction> getTargets() {
        // 需求 8：输入输出隔离——机器在私有维度，周围无方块，不向周围发/收
        return EnumSet.noneOf(Direction.class);
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get());
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        // 样板库存/返回库存/sendList 掉落（挖掘、爆炸、拆除 setBlock 均经 onRemove 触发）
        this.logic.addDrops(drops);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        this.logic.clearContent();
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }
}