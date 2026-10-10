package git.chexson.chexsonsaeutils.client.gui.playersimulation;

import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.client.gui.implementations.UpgradeableScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import git.chexson.chexsonsaeutils.menu.playersimulation.PlayerSimulationMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;

/** 沿用成型面板布局，左侧工具栏只显示玩家交互功能。 */
public final class PlayerSimulationScreen extends UpgradeableScreen<PlayerSimulationMenu> {
    private final Map<String,SimulationIconButton> options = new LinkedHashMap<>();
    private final ServerSettingToggleButton<RedstoneMode> redstone;
    private final SimulationIconButton info;
    private final ServerSettingToggleButton<appeng.api.config.FuzzyMode> fuzzy;
    public PlayerSimulationScreen(PlayerSimulationMenu menu, Inventory inv, Component title) {
        super(menu,inv,title,StyleManager.loadStyleDoc("/screens/player_simulation_plane.json"));
        addOption("click",() -> SimulationIconButton.Glyph.valueOf(menu.click.name()));
        addOption("press",() -> SimulationIconButton.Glyph.valueOf(menu.press.name()));
        addOption("target",() -> SimulationIconButton.Glyph.valueOf(menu.target.name()));
        addOption("click_face",() -> SimulationIconButton.Glyph.valueOf(menu.clickFace.name()));
        addOption("selection",() -> SimulationIconButton.Glyph.valueOf(menu.selection.name()));
        addOption("cooldown",() -> SimulationIconButton.Glyph.valueOf(menu.cooldown.name()));
        addOption("hold",() -> menu.hold == git.chexson.chexsonsaeutils.parts.playersimulation.SimulationSettings.Hold.CONTINUOUS ? SimulationIconButton.Glyph.CONTINUOUS : SimulationIconButton.Glyph.TIMER);
        addOption("sneaking",() -> menu.sneaking ? SimulationIconButton.Glyph.SNEAK : SimulationIconButton.Glyph.STAND);
        redstone = new ServerSettingToggleButton<>(Settings.REDSTONE_CONTROLLED,RedstoneMode.IGNORE);
        addToLeftToolbar(redstone);
        fuzzy = new ServerSettingToggleButton<>(Settings.FUZZY_MODE,appeng.api.config.FuzzyMode.IGNORE_ALL);
        addToLeftToolbar(fuzzy);
        var returnButton = new SimulationIconButton(button -> menu.returnBuffersClient(),() -> SimulationIconButton.Glyph.RETURN);
        returnButton.setMessage(Component.translatable("gui.chexsonsaeutils.simulation.return_buffers"));
        addToLeftToolbar(returnButton);
        info = new SimulationIconButton(button -> net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(menu.identity),() -> SimulationIconButton.Glyph.IDENTITY);
        addToLeftToolbar(info);
    }
    private void addOption(String option,java.util.function.Supplier<SimulationIconButton.Glyph> glyph) {
        var button = new SimulationIconButton(pressed -> menu.cycleClient(option),glyph);
        options.put(option,button);
        addToLeftToolbar(button);
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        String[] values = {menu.click.name(),menu.press.name(),menu.target.name(),menu.clickFace.name(),menu.selection.name(),menu.cooldown.name(),menu.hold.name(),Boolean.toString(menu.sneaking)};
        int i = 0;
        for (var option : options.entrySet()) {
            option.getValue().setMessage(Component.translatable("gui.chexsonsaeutils.simulation." + option.getKey())
                    .append("\n").append(Component.translatable("gui.chexsonsaeutils.simulation.value." + values[i++].toLowerCase(java.util.Locale.ROOT))));
        }
        options.get("click_face").setVisibility(menu.target == git.chexson.chexsonsaeutils.parts.playersimulation.SimulationSettings.Target.BLOCK);
        options.get("hold").setVisibility(menu.press == git.chexson.chexsonsaeutils.parts.playersimulation.SimulationSettings.Press.HOLD);
        fuzzy.set(menu.getFuzzyMode());
        fuzzy.setVisibility(menu.hasUpgrade(appeng.core.definitions.AEItems.FUZZY_CARD));
        redstone.set(menu.getRedStoneMode());
        redstone.setVisibility(menu.hasUpgrade(appeng.core.definitions.AEItems.REDSTONE_CARD));
        info.setMessage(Component.translatable("gui.chexsonsaeutils.simulation.info")
                .append("\n").append(Component.translatable("gui.chexsonsaeutils.simulation.status." + menu.status))
                .append("\nUUID: " + menu.identity)
                .append("\n").append(Component.translatable("gui.chexsonsaeutils.simulation.quantity_hint")));
    }
    @Override protected void slotClicked(net.minecraft.world.inventory.Slot slot,int index,int button,net.minecraft.world.inventory.ClickType type) {
        if (button == 2 && slot instanceof appeng.menu.slot.FakeSlot && slot.hasItem()
                && ((appeng.menu.slot.AppEngSlot)slot).isActive()) {
            menu.openSetAmount(slot.getContainerSlot());
            return;
        }
        super.slotClicked(slot,index,button,type);
    }
    @Override protected void renderTooltip(GuiGraphics graphics,int mouseX,int mouseY) {
        if (hoveredSlot instanceof appeng.menu.slot.FakeSlot && hoveredSlot.hasItem()) {
            ItemStack stack = hoveredSlot.getItem();
            var lines = new java.util.ArrayList<>(getTooltipFromContainerItem(stack));
            int index = hoveredSlot.getContainerSlot();
            String[] amounts = menu.buffered.split(",");
            if (index < amounts.length) lines.add(Component.translatable("gui.chexsonsaeutils.simulation.buffer",amounts[index]));
            lines.add(Component.translatable("gui.chexsonsaeutils.simulation.quantity_hint"));
            graphics.renderTooltip(font,lines,java.util.Optional.empty(),mouseX,mouseY);
        } else super.renderTooltip(graphics,mouseX,mouseY);
    }
}
