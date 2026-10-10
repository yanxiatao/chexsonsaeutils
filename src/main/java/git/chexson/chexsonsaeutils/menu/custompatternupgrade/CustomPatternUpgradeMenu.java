package git.chexson.chexsonsaeutils.menu.custompatternupgrade;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.api.inventories.InternalInventory;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.integration.extendedae.ExtendedAeCompat;

/**
 * 扩容 GUI 菜单（需求 5 阶段 5b）。
 * <p>
 * 槽位构成（仿 CondenserMenu 1 槽 + 玩家背包）：槽 0 = 输入槽（MACHINE_INPUT，
 * 仅可放入 ExtendedAE 扩展样板供应器物品 {@code extendedae:ex_pattern_provider}，
 * 见 {@link ExtendedAeCompat}）+ 玩家背包槽位。
 * <p>
 * 扩容目标：打开本菜单的供应器（方块实体或面板，经
 * {@link CustomPatternUpgradeLocator} 解析绑定，见 {@link CustomPatternUpgradeHost#getPageHolder()}）。
 * 服务端行为（仿物质聚合器自动消耗）：槽位变化（玩家拖放经 slotsChanged、
 * 外部插入经宿主库存回调）时自动检测——输入槽有扩展物品 + 页数未达上限 →
 * 循环消耗输入槽扩展物品，宿主页数逐次 +1（宿主 setPages 持久化），直到页数达上限
 * 或输入槽空；条件不满足（如页数已满）不消耗。无确认按钮，页数经 @GuiSync
 * 同步到客户端（提示文本显示）。
 * <p>
 * 打开方式：FramePatternProviderScreen / CustomPatternProviderScreen 左工具栏扩展按钮
 * → MenuOpener.open（locator 为 {@link CustomPatternUpgradeLocator}）。
 */
public class CustomPatternUpgradeMenu extends AEBaseMenu {

    public static final MenuType<CustomPatternUpgradeMenu> TYPE = MenuTypeBuilder
            .create(CustomPatternUpgradeMenu::new, CustomPatternUpgradeHost.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(Chexsonsaeutils.MODID, "custom_pattern_upgrade"));

    /** 宿主库存变更回调（由宿主转发，见 {@link CustomPatternUpgradeHost#setInventoryChangedHandler}）。 */
    public interface InventoryChangedHandler {
        void handleChange(InternalInventory inv, int slot);
    }

    private final CustomPatternUpgradeHost host;
    private final net.minecraft.world.inventory.Slot inputSlot;

    /** 当前供应器页数（客户端同步显示，来自宿主）。 */
    @GuiSync(1)
    public int pages = 1;

    public CustomPatternUpgradeMenu(int id, Inventory playerInventory, CustomPatternUpgradeHost host) {
        // AEBaseMenu 要求宿主为 BlockEntity/IPart/ItemMenuHost，本菜单宿主为瞬态对象，
        // 传 null 绕过校验（本菜单不使用 IActionHost 功能）。
        super(TYPE, id, playerInventory, null);
        this.host = host;
        this.createPlayerInventorySlots(playerInventory);
        // 输入槽过滤器在宿主库存层实现（仅 ExtendedAE 扩展样板供应器，见 CustomPatternUpgradeHost）
        this.addSlot(this.inputSlot = new AppEngSlot(host.getInventory(), 0), SlotSemantics.MACHINE_INPUT);

        this.host.setInventoryChangedHandler(this::onHostInventoryChanged);
        updateState();
    }

    /**
     * 槽位变化回调（玩家拖放路径）：服务端侧重算同步状态并尝试自动扩容。
     * <p>
     * 幂等性：扩容消耗输入槽物品后条件即不满足（输入槽空或页数满），
     * 重复触发（槽位变化 + 每 tick 同步）不会重复消耗。
     */
    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (isServerSide()) {
            updateState();
            tryAutoExpand();
        }
    }

    /**
     * 自动扩容入口（仿物质聚合器消耗语义）：条件满足时循环消耗输入槽扩展物品，
     * 每次宿主页数 +1，直到页数达上限或输入槽空。
     * <p>
     * 幂等性：每次 {@link #expand()} 消耗 1 个输入槽物品，循环条件 isExpandable()
     * 在消耗后重新判定（输入槽空/页数满即停止），重复触发不会重复消耗。
     */
    private void tryAutoExpand() {
        if (!isServerSide()) {
            return;
        }
        while (isExpandable()) {
            expand();
        }
    }

    @Override
    public void broadcastChanges() {
        updateState();
        super.broadcastChanges();
    }

    /**
     * 服务端重算同步状态（库存变更与每 tick 广播共用）：页数来自宿主。
     */
    private void updateState() {
        if (!isServerSide()) {
            return;
        }
        this.pages = Math.max(1, this.host.getPageHolder().getPages());
    }

    /**
     * @return 是否满足扩容条件：输入槽是 ExtendedAE 扩展样板供应器 + 页数未达上限
     */
    private boolean isExpandable() {
        if (!ExtendedAeCompat.isExPatternProvider(this.inputSlot.getItem())) {
            return false;
        }
        return this.pages < ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages();
    }

    /**
     * 执行一次扩容：消耗 1 个扩展物品，宿主页数 +1（达上限不消耗）。
     * <p>
     * I1 修复：重新读取实际页数后再次校验上限（Fail Fast），与 isExpandable 判定一致，
     * 防止条件竞态绕过（isExpandable 基于同步缓存，此处以宿主实际值为准）。
     */
    private void expand() {
        if (!isServerSide() || !isExpandable()) {
            return;
        }
        var holder = this.host.getPageHolder();
        int pages = Math.max(1, holder.getPages());
        if (pages >= ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages()) {
            return;
        }
        holder.setPages(pages + 1);
        this.inputSlot.getItem().shrink(1);
        updateState();
    }

    /**
     * @return 当前供应器页数（客户端同步值）
     */
    public int getPages() {
        return this.pages;
    }

    /**
     * @return 配置允许的最大页数（客户端直接读配置，无需同步）
     */
    public int getMaxPages() {
        return ChexsonsaeutilsCompatibilityConfig.maxCustomPatternPages();
    }

    /**
     * @return 输入槽扩展物品数量（客户端读槽位物品）
     */
    public int getInputCount() {
        return this.inputSlot.getItem().getCount();
    }

    private void onHostInventoryChanged(InternalInventory inv, int slot) {
        // 库存变更（服务端侧）即重算同步状态并尝试自动扩容；客户端侧无行为（宿主 isClientSide 恒 false）
        updateState();
        tryAutoExpand();
    }
}