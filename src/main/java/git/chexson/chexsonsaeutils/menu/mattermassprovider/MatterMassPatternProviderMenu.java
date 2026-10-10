package git.chexson.chexsonsaeutils.menu.mattermassprovider;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.AEBaseMenu;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.mattermassprovider.MatterMassPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.helpers.mattermassprovider.MatterMassPatternProviderHost;
import git.chexson.chexsonsaeutils.helpers.mattermassprovider.ReturnMode;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeLocator;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu;

/**
 * 物质团供应器菜单（专用 {@link AEBaseMenu}，不继承 PatternProviderMenu）。
 * <p>
 * 动机：PatternProviderMenu 会强制创建 9 格返回库存槽（STORAGE）并同步阻塞/锁定等
 * 本机不适用的设置；物质团供应器无返回栏、无对外推送，仅需样板槽 + 玩家背包。
 * <p>
 * 槽位构成：样板槽（PROVIDER_PATTERN，容量 = 配置最大页数 x 36，按页启用）+
 * 玩家背包。客户端动作：翻页（set_page）、返回模式切换（toggle_return_mode）、
 * 扩容（open_upgrade_gui，复用定制样板供应器扩容体系）。
 */
public class MatterMassPatternProviderMenu extends AEBaseMenu {

    public static final MenuType<MatterMassPatternProviderMenu> TYPE = MenuTypeBuilder
            .create(MatterMassPatternProviderMenu::new, MatterMassPatternProviderHost.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":matter_mass_pattern_provider")
            ));

    private final MatterMassPatternProviderHost mmHost;

    /**
     * 当前样板页（服务端权威，clamp 到 [0, pages-1]，翻页只切换槽位可见性）。
     * 注：同步 id 使用高位唯一段——ExtendedAE 系列会用 mixin 向
     * PatternProviderMenu 注入低位 @GuiSync 字段，低位 id 存在冲突风险。
     */
    @GuiSync(32600)
    public int page = 0;

    /** 已解锁样板页数（来自宿主，服务端广播）。 */
    @GuiSync(32601)
    public int pages = 1;

    /** 产物返回目标模式序号（{@link ReturnMode} 序，服务端权威）：0=网络 1=玩家 2=返还原料。 */
    @GuiSync(32602)
    public int returnMode = 0;

    public MatterMassPatternProviderMenu(int id, Inventory playerInventory, MatterMassPatternProviderHost host) {
        super(TYPE, id, playerInventory, host);
        this.mmHost = host;
        this.createPlayerInventorySlots(playerInventory);

        // 样板槽：仅 ENCODED_PATTERN 语义，无返回栏
        var patternInv = host.getLogic().getPatternInv();
        for (int x = 0; x < patternInv.size(); x++) {
            this.addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.PROVIDER_PATTERN,
                    patternInv, x), SlotSemantics.ENCODED_PATTERN);
        }

        registerClientAction("set_page", Integer.class, this::setPageFromClient);
        registerClientAction("toggle_return_mode", this::toggleReturnMode);
        registerClientAction("open_upgrade_gui", this::openUpgradeGui);

        this.pages = host.getPages();
        this.returnMode = host.getReturnMode().ordinal();
        if (isServerSide()) {
            updateSlotActivity();
        }
    }

    public MatterMassPatternProviderHost getMatterMassHost() {
        return mmHost;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            pages = mmHost.getPages();
            returnMode = mmHost.getReturnMode().ordinal();
            if (page >= pages) {
                page = Math.max(0, pages - 1);
            }
            updateSlotActivity();
        }
        super.broadcastChanges();
    }

    public int getPage() {
        return page;
    }

    public int getPages() {
        return pages;
    }

    public int getReturnMode() {
        return returnMode;
    }

    /** 客户端翻页按钮点击入口。 */
    public void setPage(int newPage) {
        sendClientAction("set_page", newPage);
    }

    /** 客户端返回模式切换按钮点击入口。 */
    public void toggleReturnModeClient() {
        sendClientAction("toggle_return_mode");
    }

    /** 客户端扩容按钮点击入口。 */
    public void openUpgradeGuiClient() {
        sendClientAction("open_upgrade_gui");
    }

    /** 服务端入口：翻页动作处理，clamp 到 [0, pages-1] 后按页启用/禁用样板槽。 */
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

    /** 服务端入口：循环切换产物返回目标（网络 -> 玩家 -> 返还原料 -> 网络）。 */
    private void toggleReturnMode() {
        if (!isServerSide()) {
            return;
        }
        if (mmHost instanceof MatterMassPatternProviderBlockEntity blockEntity) {
            var values = ReturnMode.values();
            var next = values[(blockEntity.getReturnMode().ordinal() + 1) % values.length];
            blockEntity.setReturnMode(next);
        }
    }

    /** 服务端入口：打开扩容 GUI（复用定制样板供应器扩容体系）。 */
    private void openUpgradeGui() {
        if (!isServerSide()) {
            return;
        }
        var blockEntity = mmHost.getBlockEntity();
        MenuOpener.open(CustomPatternUpgradeMenu.TYPE, getPlayer(),
                new CustomPatternUpgradeLocator(blockEntity.getBlockPos(), getPlayer().level().dimension(), null));
    }

    /**
     * 服务端按当前页启用/禁用样板槽（每页 36 槽，遍历计数器算页号，
     * 照 CustomPatternProviderMenu.updateSlotActivity：AppEngSlot.getSlotIndex()
     * 被 addSlot 覆盖为菜单槽位序号，不可用作库存序号）。
     */
    private void updateSlotActivity() {
        var slots = getSlots(SlotSemantics.ENCODED_PATTERN);
        int unlockedSlots = Math.min(slots.size(),
                this.pages * MatterMassPatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE);
        int slotId = 0;
        for (var slot : slots) {
            if (slot instanceof AppEngSlot appEngSlot) {
                int slotPage = slotId / MatterMassPatternProviderBlockEntity.PATTERN_SLOTS_PER_PAGE;
                boolean unlocked = slotId < unlockedSlots;
                appEngSlot.setSlotEnabled(unlocked);
                appEngSlot.setActive(unlocked && slotPage == this.page);
                ++slotId;
            }
        }
    }
}
