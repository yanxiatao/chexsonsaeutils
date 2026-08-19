package git.chexson.chexsonsaeutils.blockentity.custompatternprovider;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.ids.AEComponents;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNodeListener;
import appeng.api.orientation.BlockOrientation;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.block.crafting.PushDirection;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.util.SettingsFrom;
import git.chexson.chexsonsaeutils.block.custompatternprovider.CustomPatternProviderBlock;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FrameMachineAccessImpl;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.CustomPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 定制样板供应器方块实体（阶段 2）。
 * <p>
 * 与框架样板供应器的差异：不包裹机器（无私有维度/隔离/搬移），机器即周围相邻方块——
 * 多方向模式由方块 PUSH_DIRECTION 属性决定推送方向集合（getTargets），逻辑层
 * {@link FramePatternProviderLogic#resolveMachineHandler()} 遍历方向取第一个可用机器。
 * 共享功能（定制样板/翻页/扩容/输入过滤/appflux 灌电/样板配置）全部继承自
 * {@link FramePatternProviderLogic} 与 {@link FramePatternProviderLogicHost}。
 * <p>
 * 网格节点：REQUIRE_CHANNEL；ICraftingProvider 与 IGridTickable 服务由 logic 构造时注册
 * （字段初始化先于构造器体，logic 注册的服务不会被本类覆盖）。
 */
public class CustomPatternProviderBlockEntity extends AENetworkedBlockEntity
        implements CustomPatternProviderHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_PAGES = "pages";
    private static final int UPGRADE_SLOTS = 5;
    /** 每页样板槽数量：4 行 x 9 列，与 GUI 布局一致。 */
    public static final int PATTERN_SLOTS_PER_PAGE = 36;

    private final IUpgradeInventory upgrades;
    /**
     * 样板供应逻辑（fork 自 AE2 PatternProviderLogic）：拥有 36 x 最大页数样板槽 +
     * 9 格返回库存，推送目标经 getTargets() 方向集合解析（多方向模式）。
     * 字段初始化创建（与 AE2 PatternProviderBlockEntity 一致）——网格节点在 onReady
     * 时才创建，logic 的 addService 必须在节点创建前生效。
     */
    private final FramePatternProviderLogic logic = createLogic();

    /** 已解锁样板页数（需求 5）：默认 1，范围 [1, maxFramePatternPages()]。 */
    private int pages = 1;

    public CustomPatternProviderBlockEntity(BlockPos pos, BlockState blockState) {
        super(ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(), pos, blockState);
        this.upgrades = UpgradeInventories.forMachine(
                () -> ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_ITEM.get(),
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
        // 旧存档无 pages key：getInt 为 0，clamp 到 1
        this.pages = clampPages(data.getInt(NBT_PAGES));
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
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        // 样板库存/返回库存/sendList 掉落（挖掘、爆炸均经 onRemove 触发）
        this.logic.addDrops(drops);
        // 升级库存掉落（S4 补充，与面板版一致）
        for (int slot = 0; slot < upgrades.size(); slot++) {
            var stack = upgrades.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        this.logic.clearContent();
        // S1 修复：升级库存随内容清空（与面板版对齐，AE2 UpgradeablePart 惯例）
        this.upgrades.clear();
    }

    /**
     * @return 已解锁样板页数（默认 1，clamp 到 [1, maxFramePatternPages()]）
     */
    @Override
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
     * 页数收敛到 [1, maxFramePatternPages()]；发生截断时输出告警日志（loadTag/setPages 共用）。
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
            return FrameMachineAccessImpl.EMPTY_ITEM_HANDLER;
        }
        for (var direction : Direction.values()) {
            var handler = getMachineItemHandler(direction);
            if (handler != null) {
                return handler;
            }
        }
        return FrameMachineAccessImpl.EMPTY_ITEM_HANDLER;
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
        var pushDirection = getBlockState().getValue(CustomPatternProviderBlock.PUSH_DIRECTION);
        if (pushDirection == PushDirection.ALL) {
            return EnumSet.allOf(Direction.class);
        }
        return EnumSet.of(pushDirection.getDirection());
    }

    @Override
    public FramePatternProviderLogic getLogic() {
        return logic;
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        // appflux 加载时 logic 被 MixinPatternProviderLogic 注入为 IUpgradeableObject
        // （af_upgrades 库存）——委托给 logic 使 Menu 升级槽与 EAP 频道卡查询指向同一库存；
        // 未加载 appflux 时回落本机 5 槽库存（行为不变）。
        // 副作用：appflux 加载时本机库存成死库存（旧存档装在本机库存的感应卡不再显示，
        // 本项目不注册升级卡，仅感应卡受影响，可接受；NBT 读写已跳过，见 saveAdditional/loadTag）。
        var logic = getLogic();
        return logic instanceof IUpgradeableObject uo ? uo.getUpgrades() : upgrades;
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_ITEM.get());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_ITEM.get());
    }

    /**
     * 网格连接侧（S1 修复，照 AE2 PatternProviderBlockEntity 先例）：
     * ALL 模式全部 6 侧可连网格；定向模式排除目标侧（目标侧留给机器）。
     */
    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        var pushDirection = getBlockState().getValue(CustomPatternProviderBlock.PUSH_DIRECTION).getDirection();
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
            logic.exportSettings(builder);
            var pushDirection = getBlockState().getValue(CustomPatternProviderBlock.PUSH_DIRECTION);
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
            logic.importSettings(input, player);
            var pushDirection = input.get(AEComponents.EXPORTED_PUSH_DIRECTION);
            if (pushDirection != null) {
                var level = getLevel();
                if (level != null) {
                    level.setBlockAndUpdate(getBlockPos(), getBlockState().setValue(
                            CustomPatternProviderBlock.PUSH_DIRECTION, pushDirection));
                }
            }
        }
    }

    /**
     * 子菜单返回主菜单：打开定制样板供应器菜单（替代共享接口默认的框架菜单）。
     */
    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(CustomPatternProviderMenu.TYPE, player, subMenu.getLocator());
    }
}
