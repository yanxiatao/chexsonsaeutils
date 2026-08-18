package git.chexson.chexsonsaeutils.parts.custompatternprovider;

import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

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
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.AECableType;
import appeng.core.AppEngBase;
import appeng.items.parts.PartModels;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.util.SettingsFrom;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FrameMachineAccessImpl;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.CustomPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogic;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * 定制样板供应器面板（阶段 3）。
 * <p>
 * 与方块版 {@link git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity}
 * 的差异：附着于线缆（AEBasePart 生命周期），机器即面板朝向（{@link #getSide()}）
 * 的相邻方块——getTargets() 恒为单方向集合，逻辑层多方向分支只遍历该方向。
 * 共享功能（定制样板/翻页/扩容/输入过滤/appflux 灌电/样板配置）全部继承自
 * {@link FramePatternProviderLogic} 与 {@link FramePatternProviderLogicHost}。
 * <p>
 * 升级槽：方块版与面板版均实现 {@link IUpgradeableObject}（appflux 感应卡是升级卡，
 * 与 extendedae 面板无升级槽的差异点），5 槽库存 NBT 持久化并随拆除掉落。
 * <p>
 * 网格节点：REQUIRE_CHANNEL 由 {@link FramePatternProviderLogic} 构造器设置
 * （字段初始化先于构造器体），面板构造器不重复设置。
 */
public class CustomPatternProviderPart extends AEBasePart implements CustomPatternProviderHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NBT_UPGRADES = "upgrades";
    private static final String NBT_PAGES = "pages";
    private static final int UPGRADE_SLOTS = 5;
    /** 每页样板槽数量：与方块版一致（4 行 x 9 列）。 */
    public static final int PATTERN_SLOTS_PER_PAGE = 36;

    /** 面板模型清单：base 为本 mod 自建（parent ae2:part/pattern_provider_base），状态灯复用 ae2 接口三态。 */
    public static final List<ResourceLocation> MODELS = List.of(
            ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "part/custom_pattern_provider_base"),
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

    private final IUpgradeInventory upgrades;
    /**
     * 样板供应逻辑（与方块版一致）：36 x 最大页数样板槽 + 9 格返回库存，
     * 推送目标经 getTargets() 方向集合解析（面板恒单方向）。
     * 字段初始化创建——网格节点在 addToWorld 时才创建，logic 的 addService
     * 必须在节点创建前生效。
     */
    private final FramePatternProviderLogic logic = createLogic();

    /** 已解锁样板页数（需求 5）：默认 1，范围 [1, maxFramePatternPages()]。 */
    private int pages = 1;

    public CustomPatternProviderPart(IPartItem<?> partItem) {
        super(partItem);
        this.upgrades = UpgradeInventories.forMachine(
                () -> ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_PART_ITEM.get(),
                UPGRADE_SLOTS,
                this::saveChanges
        );
        // REQUIRE_CHANNEL 由 logic 构造器设置（字段初始化先于本构造器体），此处不重复设置
    }

    /**
     * 创建样板供应逻辑：容量 = 配置的最大页数 x 每页 36 槽（与方块版一致）。
     */
    protected FramePatternProviderLogic createLogic() {
        return new FramePatternProviderLogic(this.getMainNode(), this,
                ChexsonsaeutilsCompatibilityConfig.maxFramePatternPages() * PATTERN_SLOTS_PER_PAGE);
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        this.logic.onMainNodeStateChanged();
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(2.0, 2.0, 14.0, 14.0, 14.0, 16.0);
        bch.addBox(5.0, 5.0, 12.0, 11.0, 11.0, 14.0);
    }

    @Override
    public void readFromNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.readFromNBT(data, registries);
        upgrades.readFromNBT(data, NBT_UPGRADES, registries);
        // 样板库存/返回库存由 logic 写入（key: patterns/returnInv）
        this.logic.readFromNBT(data, registries);
        // 旧存档无 pages key：getInt 为 0，clamp 到 1
        this.pages = clampPages(data.getInt(NBT_PAGES));
    }

    @Override
    public void writeToNBT(CompoundTag data, HolderLookup.Provider registries) {
        super.writeToNBT(data, registries);
        upgrades.writeToNBT(data, NBT_UPGRADES, registries);
        this.logic.writeToNBT(data, registries);
        data.putInt(NBT_PAGES, pages);
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        // 节点就绪后刷新样板列表（readFromNBT 已由 logic 读取，此处触发 requestUpdate 让网格感知）
        this.logic.updatePatterns();
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops, boolean wrenched) {
        super.addAdditionalDrops(drops, wrenched);
        // 样板库存/返回库存/sendList 掉落（拆除经 CableBus 触发）
        this.logic.addDrops(drops);
        // 升级库存掉落（与方块版一致）
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
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 4.0F;
    }

    @Override
    public void exportSettings(SettingsFrom mode, DataComponentMap.Builder builder) {
        super.exportSettings(mode, builder);
        if (mode == SettingsFrom.MEMORY_CARD) {
            this.logic.exportSettings(builder);
        }
    }

    @Override
    public void importSettings(SettingsFrom mode, DataComponentMap input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.MEMORY_CARD) {
            this.logic.importSettings(input, player);
        }
    }

    @Override
    public void onNeighborChanged(BlockGetter level, BlockPos pos, BlockPos neighbor) {
        this.logic.updateRedstoneState();
    }

    @Override
    public boolean onUseWithoutItem(Player player, Vec3 pos) {
        if (!player.getCommandSenderWorld().isClientSide()) {
            MenuOpener.open(CustomPatternProviderMenu.TYPE, player, MenuLocators.forPart(this));
        }
        return true;
    }

    @Override
    public void returnToMainMenu(Player player, ISubMenu subMenu) {
        MenuOpener.returnTo(CustomPatternProviderMenu.TYPE, player, subMenu.getLocator());
    }

    @Override
    public FramePatternProviderLogic getLogic() {
        return logic;
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
        return new ItemStack(ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_PART_ITEM.get());
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
        return upgrades;
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
     * 注意：readFromNBT 路径不调用本方法（加载期间 saveChanges 会触发过早写盘），直接赋值字段。
     */
    public void setPages(int pages) {
        this.pages = clampPages(pages);
        saveChanges();
    }

    /**
     * 页数收敛到 [1, maxFramePatternPages()]；发生截断时输出告警日志（readFromNBT/setPages 共用）。
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
     * 逻辑层不调用本方法（getTargets() 恒非空，恒走多方向分支）；外部 ITEM capability
     * 透传与兜底路径使用——遍历方向取第一个可用 handler，全不可用时返回空实现。
     */
    @Override
    public IItemHandler getMachineItemHandler() {
        if (isClientSide()) {
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
     * 遍历方向取第一个可用能量 handler，全不可用时返回 null（注入器据此跳过灌电）。
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
                (part, context) -> part.logic.getReturnInv(),
                CustomPatternProviderPart.class
        );
    }
}