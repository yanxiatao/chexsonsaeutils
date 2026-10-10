package git.chexson.chexsonsaeutils.blockentity.custompatternprovider;


import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.ids.AEComponents;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNodeListener;
import appeng.api.orientation.BlockOrientation;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.crafting.PushDirection;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.util.SettingsFrom;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderState;


/**
 * 定制样板供应器方块实体（阶段 2）。
 * <p>
 * 机器即周围相邻方块（不包裹机器、无内部库存）：多方向模式由方块 PUSH_DIRECTION
 * 属性决定推送方向集合（getTargets），逻辑层
 * {@link CustomPatternProviderLogic#resolveMachineHandler} 遍历方向取第一个可用机器。
 * 共享功能（定制样板/翻页/扩容/输入过滤/appflux 灌电/样板配置）全部继承自
 * {@link CustomPatternProviderLogic} 与 {@link CustomPatternProviderLogicHost}；
 * 升级库存 + 样板逻辑 + 页数的持久化与掉落收敛在 {@link CustomPatternProviderState}，
 * 与面板版共用同一份实现。
 * <p>
 * 本类只保留方块侧差异：方块状态/朝向属性、按方向解析相邻机器 handler、
 * 外部 ITEM capability 的输入过滤包装、记忆卡设置导出导入。
 * <p>
 * 网格节点：REQUIRE_CHANNEL；ICraftingProvider 与 IGridTickable 服务由 logic 构造时注册
 * （字段初始化先于构造器体，logic 注册的服务不会被本类覆盖）。
 */
public class CustomPatternProviderBlockEntity extends AENetworkedBlockEntity
        implements CustomPatternProviderHost {

    /** 推送方向属性（复用 AE2 的属性实例，blockstates 变体名 push_direction）。 */
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<PushDirection> PUSH_DIRECTION = PatternProviderBlock.PUSH_DIRECTION;

    private final CustomPatternProviderState patternState = createState();

    public CustomPatternProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(((appeng.block.AEBaseEntityBlock<?>) blockState.getBlock()).getBlockEntityType(), pos, blockState);
        // ICraftingProvider 与 IGridTickable 服务由 logic 注册（字段初始化先于本构造器体）
        this.getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(0.0);
    }

    /**
     * 创建共用状态壳：升级库存取本方块物品，页数/样板库存持久化由状态壳统一承担。
     */
    private CustomPatternProviderState createState() {
        return new CustomPatternProviderState(CustomPatternProviderBlockEntity.class, this.getMainNode(), this,
                UpgradeInventories.forMachine(
                        () -> getItemFromBlockEntity(),
                        CustomPatternProviderState.UPGRADE_SLOTS,
                        this::saveChanges
                ), new git.chexson.chexsonsaeutils.integration.patternprovider.CustomPatternProviderIntegrationFactoryImpl());
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        patternState.writeNbt(data, registries);
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        patternState.readNbt(data, registries);
    }

    @Override
    public void onReady() {
        super.onReady();
        // 节点就绪后刷新样板列表（loadTag 已由 logic 读取，此处触发 requestUpdate 让网格感知）
        this.getLogic().updatePatterns();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        this.getLogic().onMainNodeStateChanged();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        // 样板库存/返回库存/sendList + 升级库存掉落
        patternState.addDrops(drops);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        patternState.clearContent();
    }

    /**
     * @return 已解锁样板页数（默认 1，clamp 到 [1, maxCustomPatternPages()]）
     */
    @Override
    public int getPages() {
        return patternState.pages();
    }

    /**
     * 设置已解锁样板页数（clamp 到 [1, maxCustomPatternPages()]，越界值收敛并告警）。
     * <p>
     * 注意：loadTag 路径不调用本方法（加载期间 saveChanges 会触发过早写盘），
     * 由 {@link CustomPatternProviderState#readNbt} 直接赋值。
     */
    public void setPages(int pages) {
        patternState.setPages(pages);
    }

    /**
     * 按方向解析相邻机器的物品 handler（多方向模式）。
     * <p>
     * 服务端查询该方向相邻方块的 ITEM capability（能力方向 = 供应器所在侧的反向），
     * 无相邻方块或无 capability 时返回 null——逻辑层 {@code resolveMachineHandler()}
     * 据此跳过该方向寻找下一个可用方向（阶段 1 约定：null = 该方向无机器）。
     * 客户端不参与推送，直接返回 null。
     *
     * @param direction 目标方向
     * @return 该方向的机器物品 handler；该方向无机器时返回 null
     */
    @Override
    @Nullable
    public IItemHandler getMachineItemHandler(Direction direction) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        BlockEntity neighbor = level.getBlockEntity(worldPosition.relative(direction));
        if (neighbor == null) {
            return null;
        }
        return level.getCapability(Capabilities.ItemHandler.BLOCK,
                neighbor.getBlockPos(), neighbor.getBlockState(), neighbor, direction.getOpposite());
    }

    /**
     * 无参版物品 handler（兜底，接口约定永不返回 null）。
     * <p>
     * 逻辑层不调用本方法（getTargets() 恒非空，恒走多方向分支）；外部 ITEM capability
     * 透传与兜底路径使用——遍历方向取第一个可用 handler，全不可用时返回空实现。
     */
    @Override
    public IItemHandler getMachineItemHandler() {
        if (level == null || level.isClientSide()) {
            return CustomPatternProviderLogicHost.EMPTY_ITEM_HANDLER;
        }
        for (var direction : Direction.values()) {
            var handler = getMachineItemHandler(direction);
            if (handler != null) {
                return handler;
            }
        }
        return CustomPatternProviderLogicHost.EMPTY_ITEM_HANDLER;
    }

    /**
     * 外部 ITEM capability 专用入口（需求 6a 扩展）：输入过滤开启时对插入做白名单过滤，
     * 只放行已配置样板的输出物品；抽取与查询直通。过滤关闭时返回原始 handler（零开销）。
     * <p>
     * 与 {@link #getMachineItemHandler()} 分离的动机：逻辑层推送/抽取走内部路径必须直通，
     * 不能被输入过滤拦截；仅外部主动 IO（管道/漏斗经 BLOCK capability）走本入口。
     */
    public IItemHandler getFilteredExternalItemHandler() {
        var raw = getMachineItemHandler();
        if (level == null || level.isClientSide() || !getLogic().isFilteredImport()) {
            return raw;
        }
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return raw.getSlots();
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return raw.getStackInSlot(slot);
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                var key = stack.isEmpty() ? null : AEItemKey.of(stack);
                if (key == null || !getLogic().passesInputFilter(key)) {
                    return stack; // 不符合白名单：拒绝插入
                }
                return raw.insertItem(slot, stack, simulate);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return raw.extractItem(slot, amount, simulate);
            }

            @Override
            public int getSlotLimit(int slot) {
                return raw.getSlotLimit(slot);
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                var key = stack.isEmpty() ? null : AEItemKey.of(stack);
                return key != null && getLogic().passesInputFilter(key) && raw.isItemValid(slot, stack);
            }
        };
    }

    /**
     * 按方向解析相邻机器的能量 handler（appflux 灌电目标）。
     * <p>
     * 服务端查询该方向相邻方块的 ENERGY capability（能力方向 = 供应器所在侧的反向），
     * 无相邻方块或无 capability 时返回 null（该方向不可灌电）。
     *
     * @param direction 目标方向
     * @return 该方向的机器能量 handler；该方向无机器时返回 null
     */
    @Override
    @Nullable
    public IEnergyStorage getMachineEnergyHandler(Direction direction) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        BlockEntity neighbor = level.getBlockEntity(worldPosition.relative(direction));
        if (neighbor == null) {
            return null;
        }
        return level.getCapability(Capabilities.EnergyStorage.BLOCK,
                neighbor.getBlockPos(), neighbor.getBlockState(), neighbor, direction.getOpposite());
    }

    /**
     * 无参版能量 handler（appflux 灌电目标，接口约定：新方块实现方向逻辑）。
     * <p>
     * 遍历方向取第一个可用能量 handler，全不可用时返回 null（注入器据此跳过灌电）。
     */
    @Override
    @Nullable
    public IEnergyStorage getMachineEnergyHandler() {
        if (level == null || level.isClientSide()) {
            return null;
        }
        for (var direction : Direction.values()) {
            var handler = getMachineEnergyHandler(direction);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /**
     * @return 推送目标方向集合（多方向模式）：PUSH_DIRECTION=ALL 时为全部 6 方向，
     *         定向时为该方向。恒非空（逻辑层据此走多方向分支）。
     */
    @Override
    public EnumSet<Direction> getTargets() {
        var pushDirection = getBlockState().getValue(PUSH_DIRECTION);
        if (pushDirection == PushDirection.ALL) {
            return EnumSet.allOf(Direction.class);
        }
        return EnumSet.of(pushDirection.getDirection());
    }

    @Override
    public CustomPatternProviderLogic getLogic() {
        return patternState.logic();
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return patternState.upgrades();
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(getItemFromBlockEntity());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getItemFromBlockEntity());
    }

    /**
     * 网格连接侧（S1 修复，照 AE2 PatternProviderBlockEntity 先例）：
     * ALL 模式全部 6 侧可连网格；定向模式排除目标侧（目标侧留给机器）。
     */
    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        var pushDirection = getBlockState().getValue(PUSH_DIRECTION).getDirection();
        if (pushDirection == null) {
            return EnumSet.allOf(Direction.class);
        }
        return EnumSet.complementOf(EnumSet.of(pushDirection));
    }

    /**
     * 方块状态变化（扳手旋转 PUSH_DIRECTION）：刷新网格节点暴露侧（S1 修复）。
     */
    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        onGridConnectableSidesChanged();
    }

    /**
     * 记忆卡设置导出（I2 修复，照 AE2 PatternProviderBlockEntity 先例）：
     * MEMORY_CARD 模式导出 logic 设置（样板/锁定模式/过滤）与 PUSH_DIRECTION。
     */
    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder, @Nullable Player player) {
        super.exportSettings(mode, builder, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            getLogic().exportSettings(builder);
            var pushDirection = getBlockState().getValue(PUSH_DIRECTION);
            builder.set(AEComponents.EXPORTED_PUSH_DIRECTION, pushDirection);
        }
    }

    /**
     * 记忆卡设置导入（I2 修复，照 AE2 PatternProviderBlockEntity 先例）：
     * MEMORY_CARD 模式导入 logic 设置并恢复 PUSH_DIRECTION 方块状态。
     */
    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            getLogic().importSettings(input, player);
            var pushDirection = input.get(AEComponents.EXPORTED_PUSH_DIRECTION);
            if (pushDirection != null) {
                var level = getLevel();
                if (level != null) {
                    level.setBlockAndUpdate(getBlockPos(), getBlockState().setValue(
                            PUSH_DIRECTION, pushDirection));
                }
            }
        }
    }
    @Override
    public net.minecraft.world.inventory.MenuType<? extends appeng.menu.AEBaseMenu> getMainMenuType() {
        return git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu.TYPE;
    }

}
