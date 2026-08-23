package git.chexson.chexsonsaeutils.menu.custompatternprovider;

import java.util.Objects;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.core.definitions.AEItems;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.slot.AppEngSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternItem;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.custompatternprovider.CustomPatternProviderLogicHost;
import git.chexson.chexsonsaeutils.menu.custompatternconfig.CustomPatternConfigLocator;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeLocator;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu;
import git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPart;

/**
 * 定制样板供应器菜单（阶段 3：继承 AE2 原版 PatternProviderMenu）。
 * <p>
 * 泛型动机：框架版菜单直接绑定 {@link FramePatternProviderBlockEntity}，本类改为绑定
 * {@link CustomPatternProviderLogicHost} + {@link appeng.api.upgrades.IUpgradeableObject}
 * 双边界——框架与定制两个 BE 都满足，菜单逻辑（槽位/翻页/配置/扩容）完全共享，
 * 未来第三种宿主无需再复制菜单。
 * <p>
 * 槽位构成：父类构造器按 {@code logic.getPatternInv()} 建全部样板槽
 * （ENCODED_PATTERN，容量 = 配置最大页数 x 36，仅可放入 AE2 样板物品）、9 格返回库存
 * （STORAGE）。升级卡槽位支持由 appflux 自理（照 extendedae 模式，本项目不注册
 * 升级卡、不显示升级槽）。
 * 左工具栏动作（照框架版去隔离模式）：主动抽取（pull_from_machine）、样板配置模式
 * （toggle_config_mode，需求 4b）、翻页（set_page，需求 5）、扩容（open_upgrade_gui，
 * 需求 5 阶段 5b）、输入过滤（toggle_filtered_import，需求 6a）。
 * <p>
 * MenuType 说明：MenuTypeBuilder 的 hostClass 用共享接口
 * {@link CustomPatternProviderHost}（extends CustomPatternProviderLogicHost &
 * IUpgradeableObject）——泛型 T 的双边界使两个接口的交集无法作为 Class 字面量，
 * 单个接口字面又无法通过构造器引用推断（javac 实验确认 T := 接口不满足
 * IUpgradeableObject 边界），共享接口同时满足双边界，且 MenuOpener 打开时
 * 对方块（BlockEntityLocator）与面板（PartLocator）的 host 解析都通过 instanceof 校验。
 */
public class CustomPatternProviderMenu<T extends CustomPatternProviderLogicHost & appeng.api.upgrades.IUpgradeableObject>
        extends PatternProviderMenu {

    public static final MenuType<CustomPatternProviderMenu<?>> TYPE = MenuTypeBuilder
            .<CustomPatternProviderMenu<?>, CustomPatternProviderHost>create(
                    CustomPatternProviderMenu::new, CustomPatternProviderHost.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":custom_pattern_provider")
            ));

    private final T host;

    /** 样板配置模式状态（需求 4b）：服务端翻转并同步，配置模式下点击处理样板槽位打开配置 GUI。 */
    @GuiSync(2)
    public boolean configMode = false;

    /** 当前样板页（需求 5）：服务端权威，clamp 到 [0, pages-1]，翻页只切换槽位可见性。 */
    @GuiSync(8)
    public int page = 0;

    /** 已解锁样板页数（需求 5）：来自宿主，服务端广播。 */
    @GuiSync(9)
    public int pages = 1;

    /** 输入过滤开关（需求 6a）：服务端权威（Logic NBT 持久化），客户端按钮据此显示。 */
    @GuiSync(10)
    public boolean filteredImport = false;

    /** 主动抽取开关（需求 8 toggle）：服务端权威（Logic NBT 持久化），客户端按钮据此显示。 */
    @GuiSync(11)
    public boolean activeExtract = false;

    public CustomPatternProviderMenu(int id, Inventory playerInventory, T host) {
        super(TYPE, id, playerInventory, host);
        this.host = host;
        registerClientAction("pull_from_machine", () -> getHost().getLogic().pullFromMachine());
        registerClientAction("toggle_config_mode", () -> configMode = !configMode);
        registerClientAction("open_config_for_slot", Integer.class, this::openConfigForSlot);
        registerClientAction("set_page", Integer.class, this::setPageFromClient);
        registerClientAction("open_upgrade_gui", this::openUpgradeGui);
        registerClientAction("toggle_filtered_import",
                () -> getHost().getLogic().setFilteredImport(!getHost().getLogic().isFilteredImport()));
        registerClientAction("toggle_active_extract",
                () -> getHost().getLogic().setActiveExtract(!getHost().getLogic().isActiveExtract()));
        // 父类构造器已建全部样板槽，此处按初始页启用槽位
        // 注意：构造器内 pages 字段默认 1，必须先读宿主真实页数再刷新槽位
        // （否则扩容后打开菜单，第二页槽位从未被 setSlotEnabled(true)）
        this.pages = getHost().getPages();
        // 仅服务端执行：客户端 BE 的 pages 无网络同步（恒为默认 1），此处执行会用
        // pages=1 把扩容后槽位 setSlotEnabled(false) 污染状态且无人恢复（Screen 只
        // setActive）→ 客户端 isActive()=false → slotClicked 拦截 → 第二页放不了。
        // 客户端槽位状态由 Screen 每帧 updatePageSlotActivity 管理，交互防护服务端承担。
        if (isServerSide()) {
            updateSlotActivity();
        }
    }

    /**
     * @return 宿主（方块实体或面板）
     */
    public T getHost() {
        return host;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            pages = getHost().getPages();
            filteredImport = getHost().getLogic().isFilteredImport();
            activeExtract = getHost().getLogic().isActiveExtract();
            if (page >= pages) {
                // 页数收缩（配置调低/5b 降级）时收敛当前页
                page = Math.max(0, pages - 1);
            }
            // 页数可能变化（扩容/降级），每次广播都按最新页数刷新槽位启用状态
            // （旧实现只在 page >= pages 时刷新，扩容后第二页槽位从未被 setSlotEnabled(true)）
            updateSlotActivity();
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
     * @return 主动抽取开关（需求 8 toggle，客户端同步值）
     */
    public boolean isActiveExtract() {
        return activeExtract;
    }

    /**
     * 客户端按钮点击入口：发送 toggle_active_extract 动作到服务端切换主动抽取（需求 8）。
     */
    public void toggleActiveExtract() {
        sendClientAction("toggle_active_extract");
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
     * <p>
     * 动机（遍历计数器）：AppEngSlot 未覆写 getSlotIndex()（继承 Slot.getSlotIndex() →
     * this.index），而 AbstractContainerMenu.addSlot 会覆盖 slot.index = slots.size()
     * （菜单槽位序号）——父类构造器先建 36 个玩家槽（index 0-35），样板槽从菜单
     * index 36 起，getSlotIndex() = 36 + 库存索引，页号计算偏移错乱。
     * 故改为遍历 ENCODED_PATTERN 槽位列表用计数器 0..N-1 算页号（照 ExtendedAE_Plus
     * ContainerExPatternProviderMixin.eap$showPage 写法）。
     * <p>
     * 未解锁槽位（slotId >= unlockedSlots）setSlotEnabled(false) 禁用交互；
     * 已解锁非当前页仅 setActive(false) 隐藏渲染（交互防护由 isSlotEnabled 承担）。
     */
    private void updateSlotActivity() {
        var slots = getSlots(SlotSemantics.ENCODED_PATTERN);
        int unlockedSlots = Math.min(slots.size(), this.pages * CustomPatternProviderPart.PATTERN_SLOTS_PER_PAGE);
        int slotId = 0;
        for (var slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot) {
                int slotPage = slotId / CustomPatternProviderPart.PATTERN_SLOTS_PER_PAGE;
                boolean unlocked = slotId < unlockedSlots;
                appEngSlot.setSlotEnabled(unlocked);
                appEngSlot.setActive(unlocked && slotPage == this.page);
                ++slotId;
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
     * 服务端入口：打开扩容 GUI（locator 携带当前供应器位置，扩容直接作用于本供应器；
     * 面板宿主附带方向，locator 据此解析面板而非方块实体）。
     */
    private void openUpgradeGui() {
        if (!isServerSide()) {
            return;
        }
        var blockEntity = getHost().getBlockEntity();
        Direction partSide = null;
        if (getHost() instanceof CustomPatternProviderPart part) {
            partSide = part.getSide();
        }
        MenuOpener.open(CustomPatternUpgradeMenu.TYPE, getPlayer(),
                new CustomPatternUpgradeLocator(blockEntity.getBlockPos(), getPlayer().level().dimension(), partSide));
    }

    /**
     * 服务端入口：配置模式下点击某槽位，若槽内是 AE2 处理样板或框架样板则打开编码 GUI
     * （携带供应器位置 + 样板槽序号，直接编辑原样板，见 {@link CustomPatternConfigLocator}）。
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
                && !(stack.getItem() instanceof CustomPatternItem)) {
            return;
        }
        MenuOpener.open(CustomPatternEncoderMenu.TYPE, getPlayer(),
                new CustomPatternConfigLocator(getHost().getBlockEntity().getBlockPos(),
                        getHost().getBlockEntity().getLevel().dimension(), slot.getSlotIndex(), true));
    }

    /**
     * 客户端槽位点击入口（Screen 拦截）：配置模式下点击处理样板槽位时发送动作，
     * 由服务端校验并打开配置 GUI（需求 4b）。
     */
    public void openConfigForSlotClient(int slotIndex) {
        sendClientAction("open_config_for_slot", slotIndex);
    }
}