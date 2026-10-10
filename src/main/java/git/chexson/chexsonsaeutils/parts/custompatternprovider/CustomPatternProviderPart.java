package git.chexson.chexsonsaeutils.parts.custompatternprovider;


import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.AECapabilities;
import appeng.api.networking.IGridNodeListener;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.parts.RegisterPartCapabilitiesEvent;
import appeng.api.stacks.AEItemKey;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.AECableType;
import appeng.core.AppEngBase;
import appeng.items.parts.PartModels;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.util.SettingsFrom;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogic;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderState;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternRegistration;

/**
 * 定制样板供应器面板（阶段 3）。
 * <p>
 * 与方块版 {@link git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity}
 * 的差异：附着于线缆（AEBasePart 生命周期），机器即面板朝向（{@link #getSide()}）
 * 的相邻方块——getTargets() 恒为单方向集合，逻辑层多方向分支只遍历该方向。
 * 共享功能（定制样板/翻页/扩容/输入过滤/appflux 灌电/样板配置）全部继承自
 * {@link CustomPatternProviderLogic} 与 {@link CustomPatternProviderLogicHost}；
 * 升级库存 + 样板逻辑 + 页数的持久化与掉落收敛在 {@link CustomPatternProviderState}，
 * 与方块版共用同一份实现。
 * <p>
 * 本类只保留面板侧差异：碰撞盒与模型、线缆连接长度、面板朝向的机器 handler 解析、
 * 拆除/放置的页数闭环（CUSTOM_PATTERN_PAGES 组件）、返回库存能力注册。
 * <p>
 * 升级槽：方块版与面板版均实现 {@link appeng.api.upgrades.IUpgradeableObject}
 * （appflux 感应卡是升级卡），5 槽库存 NBT 持久化并随拆除掉落。
 * <p>
 * 网格节点：REQUIRE_CHANNEL 由 {@link CustomPatternProviderLogic} 构造器设置
 * （字段初始化先于构造器体），面板构造器不重复设置。
 */
public class CustomPatternProviderPart extends AEBasePart implements CustomPatternProviderHost {

    /** 面板模型清单：base 为本 mod 自建（parent ae2:part/pattern_provider_base），状态灯复用 ae2 接口三态。 */
    public static final List<ResourceLocation> MODELS = List.of(
            ResourceLocation.fromNamespaceAndPath(ModIds.MODID, "part/custom_pattern_provider_base"),
            ResourceLocation.fromNamespaceAndPath(AppEngBase.MOD_ID, "part/interface_on"),
            ResourceLocation.fromNamespaceAndPath(AppEngBase.MOD_ID, "part/interface_off"),
            ResourceLocation.fromNamespaceAndPath(AppEngBase.MOD_ID, "part/interface_has_channel")
    );

    @PartModels
    public static final PartModel MODELS_OFF = new PartModel(MODELS.get(0), MODELS.get(2));
    @PartModels
    public static final PartModel MODELS_ON = new PartModel(MODELS.get(0), MODELS.get(1));
    @PartModels
    public static final PartModel MODELS_HAS_CHANNEL = new PartModel(MODELS.get(0), MODELS.get(3));

    private final CustomPatternProviderState patternState = createState();

    public CustomPatternProviderPart(IPartItem<?> partItem) {
        super(partItem);
        // REQUIRE_CHANNEL 由 logic 构造器设置（字段初始化先于本构造器体），此处不重复设置
    }

    /**
     * 创建共用状态壳：升级库存取本面板物品，页数/样板库存持久化由状态壳统一承担。
     */
    private CustomPatternProviderState createState() {
        return new CustomPatternProviderState(CustomPatternProviderPart.class, this.getMainNode(), this,
                UpgradeInventories.forMachine(
                        () -> getPartItem().asItem(),
                        CustomPatternProviderState.UPGRADE_SLOTS,
                        this::saveChanges
                ), new git.chexson.chexsonsaeutils.integration.patternprovider.CustomPatternProviderIntegrationFactoryImpl());
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        this.getLogic().onMainNodeStateChanged();
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(2.0, 2.0, 14.0, 14.0, 14.0, 16.0);
        bch.addBox(5.0, 5.0, 12.0, 11.0, 11.0, 14.0);
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        patternState.readNbt(data, registries);
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        patternState.writeNbt(data, registries);
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        // 节点就绪后刷新样板列表（readFromNBT 已由 logic 读取，此处触发 requestUpdate 让网格感知）
        this.getLogic().updatePatterns();
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        // 样板库存/返回库存/sendList + 升级库存掉落
        patternState.addDrops(drops);
    }

    @Override
    public void clearContent() {
        super.clearContent();
        patternState.clearContent();
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 4.0F;
    }

    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder) {
        super.exportSettings(mode, builder);
        if (mode == SettingsFrom.MEMORY_CARD) {
            this.getLogic().exportSettings(builder);
        } else if (mode == SettingsFrom.DISMANTLE_ITEM) {
            // I1 修复：拆除写回——IPart.addPartDrop 默认实现经本方法生成掉落栈组件，
            // 把当前 pages 写入 CUSTOM_PATTERN_PAGES（闭环保留，与放置读回对称）
            builder.set(CustomPatternRegistration.CUSTOM_PATTERN_PAGES.get(), patternState.pages());
        }
    }

    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            this.getLogic().importSettings(input, player);
        } else if (mode == SettingsFrom.DISMANTLE_ITEM) {
            // I1 修复：放置读回——PartPlacement.placePart 以放置物品的组件映射调用本方法，
            // 把扩容后的 CUSTOM_PATTERN_PAGES 注入面板（方块版对应 updateCustomBlockEntityTag 读回）
            patternState.initPages(input.getOrDefault(
                    CustomPatternRegistration.CUSTOM_PATTERN_PAGES.get(), 1));
        }
    }

    @Override
    public void onNeighborChanged(BlockGetter level, BlockPos pos, BlockPos neighbor) {
        this.getLogic().updateRedstoneState();
    }

    @Override
    public boolean onUseWithoutItem(Player player, Vec3 pos) {
        if (!player.getCommandSenderWorld().isClientSide()) {
            MenuOpener.open(CustomPatternProviderMenu.TYPE, player, MenuLocators.forPart(this));
        }
        return true;
    }

    @Override
    public CustomPatternProviderLogic getLogic() {
        return patternState.logic();
    }

    @Override
    public EnumSet<Direction> getTargets() {
        // 面板恒单方向：推送目标 = 面板朝向（恒非空，逻辑层走多方向分支）
        return EnumSet.of(this.getSide());
    }

    @Override
    public void saveChanges() {
        this.getHost().markForSave();
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(this.getPartItem());
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(getPartItem().asItem());
    }

    @Override
    public IPartModel getStaticModels() {
        if (this.isActive() && this.isPowered()) {
            return MODELS_HAS_CHANNEL;
        } else if (this.isPowered()) {
            return MODELS_ON;
        } else {
            return MODELS_OFF;
        }
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return patternState.upgrades();
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
     * 注意：readFromNBT 路径不调用本方法（加载期间 saveChanges 会触发过早写盘），
     * 由 {@link CustomPatternProviderState#readNbt} 直接赋值。
     */
    @Override
    public void setPages(int pages) {
        patternState.setPages(pages);
    }

    /**
     * 按面板朝向解析相邻机器的物品 handler。
     * <p>
     * 服务端查询面板朝向相邻方块的 ITEM capability（能力方向 = 面板所在侧的反向），
     * 无相邻方块或无 capability 时返回 null——逻辑层 {@code resolveMachineHandler()}
     * 据此跳过该方向（面板恒单方向，跳过即拒绝推送）。客户端不参与推送，直接返回 null。
     *
     * @param direction 目标方向（面板忽略该参数，恒用面板朝向）
     * @return 该方向的机器物品 handler；该方向无机器时返回 null
     */
    @Override
    @Nullable
    public IItemHandler getMachineItemHandler(Direction direction) {
        if (isClientSide()) {
            return null;
        }
        BlockEntity neighbor = getBlockEntity().getLevel()
                .getBlockEntity(getBlockEntity().getBlockPos().relative(getSide()));
        if (neighbor == null) {
            return null;
        }
        return getBlockEntity().getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                neighbor.getBlockPos(), neighbor.getBlockState(), neighbor, getSide().getOpposite());
    }

    /**
     * 无参版物品 handler（兜底，接口约定永不返回 null）。
     * <p>
     * 带方向版忽略 direction 参数、恒查面板朝向（getSide() 邻居）；本无参版遍历
     * 6 方向实际等价于单次查询（每方向都返回同一朝向的 handler），语义为兜底——
     * 外部 ITEM capability 透传路径使用，全不可用时返回空实现。
     */
    @Override
    public IItemHandler getMachineItemHandler() {
        if (isClientSide()) {
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
     * 按面板朝向解析相邻机器的能量 handler（appflux 灌电目标）。
     * <p>
     * 服务端查询面板朝向相邻方块的 ENERGY capability（能力方向 = 面板所在侧的反向），
     * 无相邻方块或无 capability 时返回 null（该方向不可灌电）。
     *
     * @param direction 目标方向（面板忽略该参数，恒用面板朝向）
     * @return 该方向的机器能量 handler；该方向无机器时返回 null
     */
    @Override
    @Nullable
    public IEnergyStorage getMachineEnergyHandler(Direction direction) {
        if (isClientSide()) {
            return null;
        }
        BlockEntity neighbor = getBlockEntity().getLevel()
                .getBlockEntity(getBlockEntity().getBlockPos().relative(getSide()));
        if (neighbor == null) {
            return null;
        }
        return getBlockEntity().getLevel().getCapability(Capabilities.EnergyStorage.BLOCK,
                neighbor.getBlockPos(), neighbor.getBlockState(), neighbor, getSide().getOpposite());
    }

    /**
     * 无参版能量 handler（appflux 灌电目标，接口约定：新方块实现方向逻辑）。
     * <p>
     * 带方向版忽略 direction 参数、恒查面板朝向（getSide() 邻居）；本无参版遍历
     * 6 方向实际等价于单次查询（每方向都返回同一朝向的 handler），语义为兜底——
     * 全不可用时返回 null（注入器据此跳过灌电）。
     */
    @Override
    @Nullable
    public IEnergyStorage getMachineEnergyHandler() {
        if (isClientSide()) {
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
     * 注册面板能力：返回库存透传（外部管道/存储总线可访问 9 格返回库存）。
     */
    @SuppressWarnings("UnstableApiUsage")
    public static void registerCapability(RegisterPartCapabilitiesEvent event) {
        event.register(
                AECapabilities.GENERIC_INTERNAL_INV,
                (part, context) -> part.getLogic().getReturnInv(),
                CustomPatternProviderPart.class
        );
    }
    @Override
    public net.minecraft.world.inventory.MenuType<? extends appeng.menu.AEBaseMenu> getMainMenuType() {
        return CustomPatternProviderMenu.TYPE;
    }

    @Override
    public appeng.menu.locator.MenuHostLocator getMainMenuLocator() {
        return appeng.menu.locator.MenuLocators.forPart(this);
    }

    @Override
    public Direction getPartSide() { return getSide(); }

}
