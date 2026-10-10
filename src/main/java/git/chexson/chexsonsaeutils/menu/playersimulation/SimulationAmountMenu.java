package git.chexson.chexsonsaeutils.menu.playersimulation;

import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.guisync.GuiSync;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.InaccessibleSlot;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.util.inv.AppEngInternalInventory;
import git.chexson.chexsonsaeutils.parts.playersimulation.PlayerSimulation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** 面板专用库存数量子菜单；复用 AE2 数字控件与主菜单返回协议。 */
public final class SimulationAmountMenu extends AEBaseMenu implements ISubMenu {
    public static final MenuType<SimulationAmountMenu> TYPE = MenuTypeBuilder.create(SimulationAmountMenu::new,PlayerSimulation.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils","simulation_amount"));
    private final PlayerSimulation host;
    private final InaccessibleSlot display;
    private int slot;
    private AEKey key;
    @GuiSync(1) public int initialAmount = -1;
    @GuiSync(2) public int maxAmount = -1;
    public SimulationAmountMenu(int id,Inventory inv,PlayerSimulation host) {
        super(TYPE,id,inv,host);
        this.host = host;
        display = new InaccessibleSlot(new AppEngInternalInventory(1),0);
        addSlot(display,SlotSemantics.MACHINE_OUTPUT);
        registerClientAction("set_amount",Integer.class,this::confirm);
    }
    @Override public PlayerSimulation getHost() { return host; }
    /** 主菜单在服务端提供被编辑标记，展示物品不参与库存操作。 */
    public static void open(ServerPlayer player,MenuHostLocator locator,int slot,GenericStack stack) {
        MenuOpener.open(TYPE,player,locator);
        if (player.containerMenu instanceof SimulationAmountMenu menu) {
            menu.slot = slot;
            menu.key = stack.what();
            menu.initialAmount = (int)stack.amount();
            menu.maxAmount = (int)menu.host.getConfig().getMaxAmount(stack.what());
            menu.display.set(stack.what().wrapForDisplayOrFilter());
            menu.broadcastChanges();
        }
    }
    /** 客户端从只读展示槽识别数量输入单位。 */
    public AEKey displayedKey() {
        var stack = GenericStack.fromItemStack(display.getItem());
        return stack == null ? null : stack.what();
    }
    /** 保存时重新核对标记与容量，避免窗口打开后配置变动覆盖别的物品。 */
    public void confirm(int amount) {
        if (isClientSide()) { sendClientAction("set_amount",amount); return; }
        if (slot < host.availableSlots() && key != null && Objects.equals(key,host.getConfig().getKey(slot))) {
            int bounded = Math.max(0,Math.min(amount,(int)host.getConfig().getMaxAmount(key)));
            host.getConfig().setStack(slot,bounded == 0 ? null : new GenericStack(key,bounded));
        }
        host.returnToMainMenu(getPlayer(),this);
    }
}
