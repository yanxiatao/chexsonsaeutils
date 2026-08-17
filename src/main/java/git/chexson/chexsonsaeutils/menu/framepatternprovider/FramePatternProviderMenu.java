package git.chexson.chexsonsaeutils.menu.framepatternprovider;

import appeng.core.definitions.AEItems;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.RestrictedInputSlot;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigLocator;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import java.util.Objects;

/**
 * 框架样板供应器菜单。
 * <p>
 * 槽位构成：36 个样板槽（ENCODED_PATTERN，仅可放入 AE2 样板物品，来自
 * {@link git.chexson.chexsonsaeutils.helpers.framepatternprovider.FramePatternProviderLogic#getPatternInv()}）、
 * 9 格返回库存（STORAGE，来自 logic 的 returnInv）、升级卡槽（由 {@link UpgradeableMenu} 自动添加）。
 * 左工具栏动作：隔离模式切换（toggle_isolated）、主动抽取（pull_from_machine，需求 8）
 * 与样板配置模式切换（toggle_config_mode，需求 4b）。
 * <p>
 * 打开方式：由 {@link git.chexson.chexsonsaeutils.block.framepatternprovider.FramePatternProviderBlock}
 * 在非潜行右击路径调用 MenuOpener 打开。
 */
public class FramePatternProviderMenu extends UpgradeableMenu<FramePatternProviderBlockEntity> {

    public static final MenuType<FramePatternProviderMenu> TYPE = MenuTypeBuilder
            .create(FramePatternProviderMenu::new, FramePatternProviderBlockEntity.class)
            .buildUnregistered(Objects.requireNonNull(
                    ResourceLocation.tryParse(Chexsonsaeutils.MODID + ":frame_pattern_provider")
            ));

    /** 隔离模式状态（服务端广播到客户端，客户端按钮据此显示）。 */
    @GuiSync(3)
    public boolean isolated = false;

    /** 样板配置模式状态（需求 4b）：服务端翻转并同步，配置模式下点击处理样板槽位打开配置 GUI。 */
    @GuiSync(4)
    public boolean configMode = false;

    public FramePatternProviderMenu(int id, Inventory playerInventory, FramePatternProviderBlockEntity host) {
        super(TYPE, id, playerInventory, host);
        registerClientAction("toggle_isolated", () -> getHost().setIsolated(!getHost().isIsolated()));
        registerClientAction("pull_from_machine", () -> getHost().getLogic().pullFromMachine());
        registerClientAction("toggle_config_mode", () -> configMode = !configMode);
        registerClientAction("open_config_for_slot", Integer.class, this::openConfigForSlot);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            isolated = getHost().isIsolated();
        }
        super.broadcastChanges();
    }

    /**
     * @return 当前隔离模式状态（客户端同步值）
     */
    public boolean isIsolated() {
        return isolated;
    }

    /**
     * @return 当前样板配置模式状态（客户端同步值）
     */
    public boolean isConfigMode() {
        return configMode;
    }

    /**
     * 客户端按钮点击入口：发送 toggle_isolated 动作到服务端切换隔离模式。
     */
    public void toggleIsolated() {
        sendClientAction("toggle_isolated");
    }

    /**
     * 客户端按钮点击入口：发送 pull_from_machine 动作到服务端，
     * 主动抽取私有维度机器输出到返回库存（需求 8）。
     */
    public void pullFromMachine() {
        sendClientAction("pull_from_machine");
    }

    /**
     * 客户端按钮点击入口：发送 toggle_config_mode 动作到服务端切换配置模式（需求 4b）。
     */
    public void toggleConfigMode() {
        sendClientAction("toggle_config_mode");
    }

    /**
     * 服务端入口：配置模式下点击某槽位，若槽内是 AE2 处理样板则打开配置 GUI
     * （携带样板副本，见 {@link FramePatternConfigLocator}）。
     */
    private void openConfigForSlot(int slotIndex) {
        if (!isServerSide() || !configMode) {
            return;
        }
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return;
        }
        // I1 修复：只允许供应器样板槽（ENCODED_PATTERN 语义），背包槽位不允许
        // （防止客户端伪造动作劫持背包物品移动语义）
        var slot = getSlot(slotIndex);
        if (!getSlots(SlotSemantics.ENCODED_PATTERN).contains(slot)) {
            return;
        }
        var stack = slot.getItem();
        if (stack.getItem() != AEItems.PROCESSING_PATTERN.asItem()) {
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