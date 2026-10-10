package git.chexson.chexsonsaeutils.menu.patterntransmitter;
import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.FakeSlot;
import git.chexson.chexsonsaeutils.blockentity.patterntransmitter.PatternTransmitterBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

/** 配置操作服务端再次验证设备拥有者，接收网络用户可以只读查看状态。 */
public final class PatternTransmitterMenu extends AEBaseMenu {
    public static final MenuType<PatternTransmitterMenu> TYPE = MenuTypeBuilder
            .create(PatternTransmitterMenu::new, PatternTransmitterBlockEntity.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils", "pattern_transmitter"));
    private final PatternTransmitterBlockEntity host;
    @GuiSync(30) public int priority;
    @GuiSync(31) public boolean paused;
    @GuiSync(32) public boolean editable;
    @GuiSync(33) public String status = "";
    @GuiSync(34) public String orders = "";
    @GuiSync(35) public int page;
    @GuiSync(36) public int pages;
    public PatternTransmitterMenu(int id, Inventory inventory, PatternTransmitterBlockEntity host) {
        super(TYPE, id, inventory, host); this.host = host;
        registerClientAction("priority", Integer.class, value -> { if (authorized()) host.transmitter().priority(value); });
        registerClientAction("pause", () -> { if (authorized()) host.transmitter().pause(!host.transmitter().paused()); });
        registerClientAction("retry", () -> { if (authorized()) host.transmitter().retry(); });
        registerClientAction("cancel", () -> { if (authorized()) host.transmitter().cancel(); });
        registerClientAction("page", Integer.class, value -> page = Math.max(0, Math.min(value, Math.max(0, pages - 1))));
        for (int i = 0; i < 18; i++) {
            var slot = new FakeSlot(host.transmitter().filter().createMenuWrapper(), i);
            addSlot(slot, appeng.menu.SlotSemantics.CONFIG);
        }
        createPlayerInventorySlots(inventory);
    }
    private boolean authorized() { return stillValid(getPlayer()) && host.canConfigure(getPlayer()); }
    @Override public boolean stillValid(Player player) { return !host.isRemoved() && player.distanceToSqr(host.getBlockPos().getCenter()) < 64; }
    @Override public void broadcastChanges() {
        if (isServerSide()) {
            priority = host.transmitter().priority(); paused = host.transmitter().paused(); editable = authorized(); status = host.transmitter().status();
            var lines = host.transmitter().orderLines(); pages = Math.max(1, (lines.size() + 2) / 3); page = Math.min(page, pages - 1);
            orders = String.join("\n", lines.subList(Math.min(lines.size(), page * 3), Math.min(lines.size(), (page + 1) * 3)));
        }
        super.broadcastChanges();
    }
    /** 屏幕只发送固定动作，所有业务在宿主接口中执行。 */
    public void action(String action) { sendClientAction(action); }
    public void setPriority(int value) { sendClientAction("priority", value); }
    public void setPage(int value) { sendClientAction("page", value); }
    @Override public void clicked(int slot, int button, net.minecraft.world.inventory.ClickType type, Player player) {
        if (!isClientSide() && !authorized() && (slot >= 0 && slot < 18
                || type == net.minecraft.world.inventory.ClickType.QUICK_MOVE || type == net.minecraft.world.inventory.ClickType.QUICK_CRAFT)) return;
        super.clicked(slot, button, type, player);
    }
    @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int slot) {
        if (!isClientSide() && !authorized()) return net.minecraft.world.item.ItemStack.EMPTY;
        return super.quickMoveStack(player, slot);
    }
    @Override public void doAction(net.minecraft.server.level.ServerPlayer player, appeng.helpers.InventoryAction action, int slot, long id) {
        if (!authorized() && (slot < 18 || action == appeng.helpers.InventoryAction.MOVE_REGION)) return;
        super.doAction(player, action, slot, id);
    }
    @Override public void setFilter(int slot, net.minecraft.world.item.ItemStack stack) {
        if (slot < 18 && !isClientSide() && !authorized()) return;
        super.setFilter(slot, stack);
    }
}
