package git.chexson.chexsonsaeutils.menu.playersimulation;

import appeng.api.util.IConfigManager;
import appeng.api.config.Settings;
import appeng.core.definitions.AEItems;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.implementations.UpgradeableMenu;
import git.chexson.chexsonsaeutils.parts.playersimulation.PlayerSimulation;
import git.chexson.chexsonsaeutils.parts.playersimulation.SimulationSettings;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 成型面板布局的数量标记菜单；选项修改由 AE2 客户端动作包校验并在服务端执行。 */
public final class PlayerSimulationMenu extends UpgradeableMenu<PlayerSimulation> {
    public static final MenuType<PlayerSimulationMenu> TYPE = MenuTypeBuilder
            .create(PlayerSimulationMenu::new, PlayerSimulation.class)
            .buildUnregistered(ResourceLocation.fromNamespaceAndPath("chexsonsaeutils","player_simulation_plane"));
    @GuiSync(7) public SimulationSettings.Click click = SimulationSettings.Click.RIGHT;
    @GuiSync(8) public SimulationSettings.Target target = SimulationSettings.Target.BLOCK;
    @GuiSync(9) public SimulationSettings.Selection selection = SimulationSettings.Selection.ROUND_ROBIN;
    @GuiSync(10) public SimulationSettings.Cooldown cooldown = SimulationSettings.Cooldown.MATCH;
    @GuiSync(11) public SimulationSettings.Hold hold = SimulationSettings.Hold.NORMAL;
    @GuiSync(12) public boolean sneaking;
    @GuiSync(13) public String identity = "";
    @GuiSync(14) public String status = "idle";
    @GuiSync(15) public String buffered = "";
    @GuiSync(16) public SimulationSettings.Press press = SimulationSettings.Press.TAP;
    @GuiSync(17) public SimulationSettings.ClickFace clickFace = SimulationSettings.ClickFace.AUTO;

    public PlayerSimulationMenu(int id, Inventory inv, PlayerSimulation host) {
        super(TYPE,id,inv,host);
        // 不接收任意字符串或枚举索引，客户端只能触发固定动作。
        for (String option : new String[]{"click","target","click_face","selection","cooldown","press","hold","sneaking"})
            registerClientAction(option, () -> host.cycle(option));
        registerClientAction("return_buffers", host::returnBuffers);
        registerClientAction("set_amount",Integer.class,this::openSetAmount);
    }
    @Override public void setFilter(int index,net.minecraft.world.item.ItemStack item) {
        // JEI 等拖入只建立标记，初始数量为一；精确数量统一由中键子菜单配置。
        super.setFilter(index,item.isEmpty() ? item : item.copyWithCount(1));
    }
    @Override public void doAction(net.minecraft.server.level.ServerPlayer player,appeng.helpers.InventoryAction action,int index,long id) {
        if (index >= 0 && index < slots.size() && slots.get(index) instanceof appeng.menu.slot.FakeSlot slot) {
            if (!slot.isSlotEnabled()) return;
            switch (action) {
                case PICKUP_OR_SET_DOWN, PLACE_SINGLE -> {
                    var carried = getCarried();
                    var key = appeng.api.stacks.AEItemKey.of(carried);
                    getHost().getConfig().setStack(slot.getContainerSlot(),key == null ? null : new appeng.api.stacks.GenericStack(key,1));
                    return;
                }
                case SPLIT_OR_PLACE_SINGLE -> {
                    getHost().getConfig().setStack(slot.getContainerSlot(),null);
                    return;
                }
                default -> { }
            }
        }
        super.doAction(player,action,index,id);
    }
    /** 中键打开当前有效标记的数量编辑窗口，服务端验证槽位。 */
    public void openSetAmount(int slot) {
        if (slot < 0 || slot >= 63) throw new IllegalArgumentException("数量槽位越界: " + slot);
        if (isClientSide()) { sendClientAction("set_amount",slot); return; }
        if (slot >= getHost().availableSlots()) throw new IllegalArgumentException("数量槽位未启用: " + slot);
        var stack = getHost().getConfig().getStack(slot);
        if (stack != null) SimulationAmountMenu.open((net.minecraft.server.level.ServerPlayer)getPlayer(),getLocator(),slot,stack);
    }
    /** 客户端请求切换固定选项。 */
    public void cycleClient(String option) { sendClientAction(option); }
    /** 客户端请求退回缓存。 */
    public void returnBuffersClient() { sendClientAction("return_buffers"); }
    @Override protected void setupConfig() { addExpandableConfigSlots(getHost().getConfig(),2,9,5); }
    @Override public boolean isSlotEnabled(int row) { return getUpgrades().getInstalledUpgrades(AEItems.CAPACITY_CARD) > row; }
    @Override protected void loadSettingsFromHost(IConfigManager manager) {
        setRedStoneMode(manager.getSetting(Settings.REDSTONE_CONTROLLED));
        setFuzzyMode(manager.getSetting(Settings.FUZZY_MODE));
        click = getHost().click(); target = getHost().target(); clickFace = getHost().clickFace(); selection = getHost().selection();
        cooldown = getHost().cooldown(); hold = getHost().hold(); press = getHost().press(); sneaking = getHost().sneaking();
        identity = getHost().identity().toString(); status = getHost().status();
        buffered = IntStream.range(0,63).mapToObj(i -> Integer.toString(getHost().bufferedAmount(i))).collect(Collectors.joining(","));
    }
}
