package git.chexson.chexsonsaeutils.menu.custompatternprovider;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.core.definitions.AEItems;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.crafting.framepattern.FramePatternItem;
import git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogicHost;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigLocator;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigMenu;
import git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeLocator;
import git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeMenu;

/**
 * 定制样板供应器菜单（阶段 2，泛型版本）。
 * <p>
 * 泛型动机：框架版菜单直接绑定 {@link FramePatternProviderBlockEntity}，本类改为绑定
 * {@link FramePatternProviderLogicHost} + {@link appeng.api.upgrades.IUpgradeableObject}
 * 双边界——框架与定制两个 BE 都满足，菜单逻辑（槽位/翻页/配置/扩容）完全共享，
 * 未来第三种宿主无需再复制菜单。
 * <p>
 * 槽位构成：36 个样板槽/页（ENCODED_PATTERN，仅可放入 AE2 样板物品，容量 =
 * 配置最大页数 x 36）、9 格返回库存（STORAGE）、升级卡槽（UpgradeableMenu 自动添加）。
 * 左工具栏动作（照框架版去隔离模式）：主动抽取（pull_from_machine）、样板配置模式
 * （toggle_config_mode，需求 4b）、翻页（set_page，需求 5）、扩容（open_upgrade_gui，
 * 需求 5 阶段 5b）、输入过滤（toggle_filtered_import，需求 6a）。
 * <p>
 * MenuType 说明：MenuTypeBuilder 的 hostClass 用具体 BE 类而非共享接口——泛型 T 的
 * 双边界（FramePatternProviderLogicHost & IUpgradeableObject）使接口字面无法通过
 * 构造器引用推断（javac 实验确认 T := 接口不满足 IUpgradeableObject 边界），
 * 具体类同时满足双边界且 MenuOpener 打开时 host 解析行为等价。
 */
public class CustomPatternProviderMenu<T extends FramePatternProviderLogicHost & appeng.api.upgrades.IUpgradeableObject>
        extends UpgradeableMenu<T> {

    public static final MenuType<CustomPatternProviderMenu<?>> TYPE = MenuTypeBuilder
            .<CustomPatternProviderMenu<?>, CustomPatternProviderBlockEntity>create(
                    CustomPatternProviderMenu::new, CustomPatternProviderBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":custom_pattern_provider")
            ));

    /** 样板配置模式状态（需求 4b）：服务端翻转并同步，配置模式下点击处理样板槽位打开配置 GUI。 */
    @GuiSync(2)
    public boolean configMode = false;

    /** 当前样板页（需求 5）：服务端权威，clamp 到 [0, pages-1]，翻页只切换槽位可见性。 */
    @GuiSync(3)
    public int page = 0;

    /** 已解锁样板页数（需求 5）：来自宿主，服务端广播。 */
    @GuiSync(4)
    public int pages = 1;

    /** 输入过滤开关（需求 6a）：服务端权威（Logic NBT 持久化），客户端按钮据此显示。 */
    @GuiSync(7)
    public boolean filteredImport = false;

    public CustomPatternProviderMenu(int id, Inventory playerInventory, T host) {
        super(TYPE, id, playerInventory, host);
        registerClientAction("pull_from_machine", () -> getHost().getLogic().pullFromMachine());
        registerClientAction("toggle_config_mode", () -> configMode = !configMode);
        registerClientAction("open_config_for_slot", Integer.class, this::openConfigForSlot);
        registerClientAction("set_page", Integer.class, this::setPageFromClient);
        registerClientAction("open_upgrade_gui", this::openUpgradeGui);
        registerClientAction("toggle_filtered_import",
                () -> getHost().getLogic().setFilteredImport(!getHost().getLogic().isFilteredImport()));
        // setupInventorySlots 已由 UpgradeableMenu 构造执行，此处按初始页启用槽位
        updateSlotActivity();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            pages = getHost().getPages();
            filteredImport = getHost().getLogic().isFilteredImport();
            if (page >= pages) {
                // 页数收缩（配置调低/5b 降级）时收敛当前页
                page = Math.max(0, pages - 1);
                updateSlotActivity();
            }
        }
        super.broadcastChanges();
    }

    /**
     * @return 当前样板配置模式状态（客户端同步值）
     */
    public boolean isConfigMode() {
        return configMode;
    }

    /**
     * 客户端按钮点击入口：发送 pull_from_machine 动作到服务端，
     * 主动抽取相邻机器输出到返回库存（需求 8）。
     */
    public void pullFromMachine() {
        sendClientAction("pull_from_machine");
    }

    /**
     * @return 输入过滤开关（需求 6a，客户端同步值）
     */
    public boolean isFilteredImport() {
        return filteredImport;
    }

    /**
     * 客户端按钮点击入口：发送 toggle_filtered_import 动作到服务端切换输入过滤（需求 6a）。
     */
    public void toggleFilteredImport() {
        sendClientAction("toggle_filtered_import");
    }

    /**
     * 客户端按钮点击入口：发送 toggle_config_mode 动作到服务端切换配置模式（需求 4b）。
     */
    public void toggleConfigMode() {
        sendClientAction("toggle_config_mode");
    }

    /**
     * 客户端翻页按钮点击入口：发送 set_page 动作到服务端（需求 5）。
     */
    public void setPage(int newPage) {
        sendClientAction("set_page", newPage);
    }

    /**
     * 服务端入口：翻页动作处理，clamp 到 [0, pages-1] 后按页启用/禁用样板槽。
     * <p>
     * 交互防护：AppEngSlot 的 mayPlace/mayPickup 只查 isSlotEnabled（active 仅影响渲染），
     * 服务端必须按页禁用非当前页槽位，防止伪造点击操作其他页的样板。
     */
    private void setPageFromClient(int newPage) {
        if (!isServerSide()) {
            return;
        }
        int clamped = Math.max(0, Math.min(newPage, pages - 1));
        if (clamped == page) {
            return;
        }
        page = clamped;
        updateSlotActivity();
    }

    /**
     * 服务端按当前页启用/禁用样板槽（每页 36 槽）。
     */
    private void updateSlotActivity() {
        for (var slot : getSlots(SlotSemantics.ENCODED_PATTERN)) {
            if (slot instanceof AppEngSlot appEngSlot) {
                int slotPage = appEngSlot.getSlotIndex() / CustomPatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE;
                appEngSlot.setSlotEnabled(slotPage == page);
            }
        }
    }

    /**
     * @return 当前样板页（客户端同步值，0 起）
     */
    public int getPage() {
        return page;
    }

    /**
     * @return 已解锁样板页数（客户端同步值）
     */
    public int getPages() {
        return pages;
    }

    /**
     * 客户端扩展按钮点击入口：发送 open_upgrade_gui 动作到服务端（需求 5 阶段 5b）。
     */
    public void openUpgradeGuiClient() {
        sendClientAction("open_upgrade_gui");
    }

    /**
     * 服务端入口：打开扩容 GUI（瞬态宿主 locator，玩家自行放入物品）。
     */
    private void openUpgradeGui() {
        if (!isServerSide()) {
            return;
        }
        MenuOpener.open(FramePatternUpgradeMenu.TYPE, getPlayer(), new FramePatternUpgradeLocator());
    }

    /**
     * 服务端入口：配置模式下点击某槽位，若槽内是 AE2 处理样板或框架样板则打开配置 GUI
     * （携带样板副本，见 {@link FramePatternConfigLocator}）。
     */
    private void openConfigForSlot(int slotIndex) {
        if (!isServerSide() || !configMode) {
            return;
        }
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return;
        }
        // 只允许供应器样板槽（ENCODED_PATTERN 语义），背包槽位不允许
        // （防止客户端伪造动作劫持背包物品移动语义）
        var slot = getSlot(slotIndex);
        if (!getSlots(SlotSemantics.ENCODED_PATTERN).contains(slot)) {
            return;
        }
        var stack = slot.getItem();
        // 定制供应器接受两类样板：AE2 处理样板与框架样板（4b 需求扩展）
        if (stack.getItem() != AEItems.PROCESSING_PATTERN.asItem()
                && !(stack.getItem() instanceof FramePatternItem)) {
            return;
        }
        MenuOpener.open(FramePatternConfigMenu.TYPE, getPlayer(), new FramePatternConfigLocator(stack.copy()));
    }

    /**
     * 客户端槽位点击入口（Screen 拦截）：配置模式下点击处理样板槽位时发送动作，
     * 由服务端校验并打开配置 GUI（需求 4b）。
     */
    public void openConfigForSlotClient(int slotIndex) {
        sendClientAction("open_config_for_slot", slotIndex);
    }

    @Override
    protected void setupInventorySlots() {
        var logic = getHost().getLogic();
        var patternInventory = logic.getPatternInv();
        for (int slot = 0; slot < patternInventory.size(); slot++) {
            addSlot(
                    new RestrictedInputSlot(
                            RestrictedInputSlot.PlacableItemType.PROVIDER_PATTERN,
                            patternInventory,
                            slot
                    ),
                    SlotSemantics.ENCODED_PATTERN
            );
        }
        var returnInventory = logic.getReturnInv().createMenuWrapper();
        for (int slot = 0; slot < returnInventory.size(); slot++) {
            addSlot(new AppEngSlot(returnInventory, slot), SlotSemantics.STORAGE);
        }
    }
}