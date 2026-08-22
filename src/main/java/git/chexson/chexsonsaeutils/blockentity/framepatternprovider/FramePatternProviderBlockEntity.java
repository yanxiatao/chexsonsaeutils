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
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * 框架样板供应器方块实体（原位包装架构）。
 * <p>
 * 捕获后机器 BE 实例不销毁、不转移维度与坐标，由本框架 BE 持有并代理运行：
 * 机器在原维度原位置继续 tick（经 {@link #machineTicker} 转发原方块的 ticker）、
 * 外部 capability 访问经 {@link #getCapability} 转发、内部区域右击经 MenuProvider
 * 打开机器原生 GUI——满足特定维度限制机器与多方块主方块的结构检查/IO 需求。
 * <p>
 * 网格节点：REQUIRE_CHANNEL；ICraftingProvider 与 IGridTickable 服务由 logic 在构造时注册。
 * 样板供应逻辑由 {@link FramePatternProviderLogic} 承担：36 样板槽 x N 页、推送目标为
 * 包装机器的库存 capability、返回库存回收机器输出。
 */
public class FramePatternProviderBlockEntity extends AENetworkedBlockEntity
        implements FramePatternProviderLogicHost, IUpgradeableObject, ServerTickingBlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_CAPTURED_STATE = "capturedState";
    private static final String NBT_MACHINE_BACKUP = "machineBackup";
    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_PAGES = "pages";
    private static final int UPGRADE_SLOTS = 5;
    /** 每页样板槽数量：4 行 x 9 列，与 GUI 布局一致。 */
    public static final int PATTERN_SLOTS_PER_PAGE = 36;

    private final IUpgradeInventory upgrades;
    /**
     * 样板供应逻辑（fork 自 AE2 PatternProviderLogic）：拥有 36 样板槽 x N 页 + 9 格返回库存，
     * 推送目标为包装机器的库存 capability。字段初始化创建——网格节点在 onReady 时才创建，
     * logic 的 addService 必须在节点创建前生效。
     */
    private final FramePatternProviderLogic logic = createLogic();

    /** 被包裹的原方块状态，null 表示未包裹任何方块（渲染与收敛用）。 */
    @Nullable
    private BlockState capturedState;

    /**
     * 原位包装的机器 BE 实例：捕获后由本框架持有并代理运行（见类注释）。
     * null 表示未包裹。
     */
    @Nullable
    private BlockEntity wrappedMachine;

    /** 机器 BE 嵌套备份（saveWithId 输出）：存档加载时重建 wrappedMachine 的兜底数据。 */
    @Nullable
    private CompoundTag machineBackup;

    /**
     * 机器 tick 转发器：捕获时从原方块 getTicker(level, capturedState, 机器BE类型) 取得，
     * serverTick 中驱动 wrappedMachine 运行。raw 强转安全——ticker 实现自带 instanceof 校验。
     */
    @Nullable
    private BlockEntityTicker<BlockEntity> machineTicker;

    /** 搬回协议执行中标志：防止嵌套 setBlock 触发本方块 onRemove 时递归恢复（B1 时序教训）。 */
    private boolean restoring;
    /** 已解锁样板页数（需求 5）：默认 1，范围 [1, maxFramePatternPages()]；5b 阶段由扩容物品增加。 */
    private int pages = 1;

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
        // appflux 加载时 "upgrades" 键由 af_upgrades 独占（logic NBT 与 BE NBT 同层平铺，
        // appflux mixin 在 logic.writeToNBT TAIL 写同键，后写覆盖）——跳过本机库存写盘避免冲突
        if (!ModList.get().isLoaded("appflux")) {
            upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        }
        // 样板库存/返回库存由 logic 写入（key: patterns/returnInv）
        logic.writeToNBT(data, registries);
        if (capturedState != null) {
            data.put(NBT_CAPTURED_STATE, NbtUtils.writeBlockState(capturedState));
        }
        if (wrappedMachine != null) {
            // 嵌套备份：saveWithId 含 BE 类型 id 与完整业务数据，崩溃恢复兜底可重建
            data.put(NBT_MACHINE_BACKUP, wrappedMachine.saveWithId(registries));
        }
        data.putInt(NBT_PAGES, pages);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        // 与 saveAdditional 对称：appflux 加载时跳过本机库存读盘（"upgrades" 键归 af_upgrades），
        // 本机库存保持空（死库存），避免把 af_upgrades 数据脏读进本机库存导致重复掉落
        if (!ModList.get().isLoaded("appflux")) {
            upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        }
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
        pages = clampPages(data.getInt(NBT_PAGES));
        // wrappedMachine 重建延后到 onLoad（此时 level 已就绪，clearRemoved/setLevel 可安全调用）
    }

    /**
     * 捕获目标方块（原位包装）：机器 BE 实例由框架持有并代理运行，主世界该位置替换为框架方块。
     * <p>
     * 协议：saveWithId 备份（崩溃恢复兜底）→ removeBlockEntity 摘除机器 BE（B1 修复：防
     * onRemove 掉落内部物品导致复制）→ setBlock 替换为框架方块 → 机器 BE clearRemoved +
     * setLevel 复活并交由框架持有 → 缓存机器 ticker 供 serverTick 转发。
     * <p>
     * 调用前提：pos 处仍是目标方块（本方法由
     * {@link git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem#useOn} 在服务端调用）。
     *
     * @param level 目标方块所在世界（必须为服务端）
     * @param pos   目标方块位置
     * @param pages 掉落物品携带的已解锁样板页数（拆除保留闭环，clamp 到 [1, maxFramePatternPages()]）
     */
    public static void captureBlock(Level level, BlockPos pos, int pages) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            throw new IllegalStateException("捕获必须在服务端执行，位置 " + pos);
        }
        BlockState targetState = level.getBlockState(pos);
        BlockEntity targetBlockEntity = level.getBlockEntity(pos);
        CompoundTag machineTag = null;
        if (targetBlockEntity != null) {
            // saveWithId 包含 BE 类型 id 与 saveAdditional 数据：NBT 兜底（wrappedMachine 丢失时重建）
            machineTag = targetBlockEntity.saveWithId(level.registryAccess());
        }
        // B1 修复：先摘除机器 BE 再替换方块，使其 onRemove 中 getBlockEntity 返回 null，不触发掉落
        level.removeBlockEntity(pos);
        level.setBlock(
                pos,
                ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL
        );
        if (!(level.getBlockEntity(pos) instanceof FramePatternProviderBlockEntity frameBlockEntity)) {
            // Fail Fast：框架 BE 缺失属放置异常，把机器 BE 落回原位避免数据丢失
            LOGGER.error("捕获失败：替换为框架方块后未找到框架 BE，恢复原方块，位置 {}", pos);
            level.setBlock(pos, targetState, Block.UPDATE_ALL);
            if (targetBlockEntity != null) {
                level.setBlockEntity(targetBlockEntity);
            }
            return;
        }
        frameBlockEntity.capturedState = targetState;
        frameBlockEntity.wrappedMachine = targetBlockEntity;
        if (targetBlockEntity != null) {
            // 复活机器 BE：setRemoved 已标记移除，此处解除并重新挂接维度与 capability
            targetBlockEntity.clearRemoved();
            targetBlockEntity.setLevel(level);
            frameBlockEntity.refreshMachineTicker();
        }
        frameBlockEntity.setPages(pages);
        frameBlockEntity.saveChanges();
    }

    /**
     * 从原方块取得机器 ticker（raw 强转安全：ticker 实现自带 instanceof 类型校验）。
     * 有 BE 的方块必然实现 EntityBlock，其 getTicker 按机器 BE 类型分发对应 ticker。
     */
    private void refreshMachineTicker() {
        if (capturedState == null || wrappedMachine == null || level == null
                || !(capturedState.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) {
            machineTicker = null;
            return;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        var ticker = (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(
                level, capturedState, (BlockEntityType) wrappedMachine.getType());
        machineTicker = ticker;
        if (ticker == null) {
            LOGGER.warn("机器方块未提供 ticker，包装机器将不会自行运转：{}", capturedState.getBlock());
        }
    }

    /**
     * 恢复被包裹的原方块（含机器 BE 实例落回），并清空捕获数据。
     * <p>
     * 先 setBlock 恢复原方块（此过程会移除本框架 BE，触发本方块 onRemove 但 restoring
     * 标志跳过递归），再把持有的机器 BE 实例写回该位置（内存数据最新，无需 NBT 重建）；
     * wrappedMachine 缺失时回退 machineBackup/loadStatic 兜底。
     *
     * @param level 所在世界
     * @param pos   本框架方块位置（即原方块位置）
     */
    public void restoreCapturedBlock(Level level, BlockPos pos) {
        if (capturedState == null) {
            return;
        }
        restoring = true;
        try {
            level.setBlock(pos, capturedState, Block.UPDATE_ALL);
            if (wrappedMachine != null) {
                level.setBlockEntity(wrappedMachine);
            } else if (machineBackup != null && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                // 兜底：实例缺失（崩溃恢复场景）从嵌套备份重建
                BlockEntity restored = BlockEntity.loadStatic(pos, capturedState, machineBackup,
                        serverLevel.registryAccess());
                if (restored != null) {
                    level.setBlockEntity(restored);
                } else {
                    LOGGER.warn("恢复原 BE 失败：备份数据无法创建实例，位置 {}", pos);
                }
            }
            capturedState = null;
            wrappedMachine = null;
            machineTicker = null;
            saveChanges();
        } finally {
            restoring = false;
        }
    }

    /**
     * @return true 表示搬回协议执行中（嵌套 onRemove 时用于跳过恢复逻辑）
     */
    public boolean isRestoring() {
        return restoring;
    }

    @Override
    public void onReady() {
        super.onReady();
        // 节点就绪后刷新样板列表（loadTag 已由 logic 读取，此处触发 requestUpdate 让网格感知）
        this.logic.updatePatterns();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 存档加载：从嵌套备份重建包装机器实例（wrappedMachine 实例不随 BE 序列化，
        // 必须由此重建），随后复活并缓存机器 ticker
        if (capturedState != null && wrappedMachine == null && machineBackup != null && level != null) {
            var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    worldPosition, capturedState, machineBackup, level.registryAccess());
            if (restored != null) {
                wrappedMachine = restored;
                restored.clearRemoved();
                restored.setLevel(level);
                refreshMachineTicker();
            } else {
                LOGGER.error("包装机器重建失败：备份无法创建实例，位置 {}", worldPosition);
            }
        }
    }

    @Override
    public void serverTick() {
        // 机器 tick 转发：包装机器不在 chunk tick 列表（被框架 BE 顶替），由本 ticker 驱动
        if (machineTicker != null && wrappedMachine != null && capturedState != null && level != null) {
            machineTicker.tick(level, worldPosition, capturedState, wrappedMachine);
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

    /**
     * @return 原位包装的机器 BE 实例（未包裹时为 null）；供方块层做 GUI/右键交互转发
     */
    @Nullable
    public BlockEntity getWrappedMachine() {
        return wrappedMachine;
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

    /** 空 ITEM handler：机器缺失或客户端查询时返回，避免外部管道 NPE。 */
    private static final net.neoforged.neoforge.items.IItemHandler EMPTY_ITEM_HANDLER =
            new net.neoforged.neoforge.items.IItemHandler() {
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

    /** 空 ENERGY handler：机器缺失或客户端查询时返回，避免 NPE。 */
    private static final net.neoforged.neoforge.energy.IEnergyStorage EMPTY_ENERGY_STORAGE =
            new net.neoforged.neoforge.energy.IEnergyStorage() {
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

    /**
     * 外部 ITEM capability 透传：返回包装机器的 IItemHandler（管道/漏斗等外部访问直达机器）。
     * 客户端或机器缺失时返回空实现避免 NPE。
     */
    @Override
    public net.neoforged.neoforge.items.IItemHandler getMachineItemHandler() {
        if (level == null || level.isClientSide() || wrappedMachine == null) {
            return EMPTY_ITEM_HANDLER;
        }
        var handler = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                worldPosition, null);
        return handler != null ? handler : EMPTY_ITEM_HANDLER;
    }

    /**
     * 外部 ENERGY capability 透传：返回包装机器的 IEnergyStorage。
     * 客户端或机器缺失时返回空实现避免 NPE。
     */
    @Override
    public net.neoforged.neoforge.energy.IEnergyStorage getMachineEnergyHandler() {
        if (level == null || level.isClientSide() || wrappedMachine == null) {
            return EMPTY_ENERGY_STORAGE;
        }
        var handler = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                worldPosition, null);
        return handler != null ? handler : EMPTY_ENERGY_STORAGE;
    }

    @Override
    public FramePatternProviderLogic getLogic() {
        return logic;
    }

    @Override
    public java.util.EnumSet<Direction> getTargets() {
        // 单 handler 模式：推送目标统一走 getMachineItemHandler（包装机器 capability）
        return java.util.EnumSet.noneOf(Direction.class);
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
        // appflux 加载时 logic 被 MixinPatternProviderLogic 注入为 IUpgradeableObject
        // （af_upgrades 库存）——委托给 logic 使 Menu 升级槽与 EAP 频道卡查询指向同一库存；
        // 未加载 appflux 时回落本机 5 槽库存（行为不变）。
        var logic = getLogic();
        return logic instanceof IUpgradeableObject uo ? uo.getUpgrades() : upgrades;
    }
}
