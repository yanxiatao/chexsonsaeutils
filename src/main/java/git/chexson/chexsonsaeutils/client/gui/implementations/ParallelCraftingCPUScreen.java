package git.chexson.chexsonsaeutils.client.gui.implementations;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Settings;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.ServerSettingToggleButton;
import appeng.client.gui.widgets.SettingToggleButton;
import git.chexson.chexsonsaeutils.client.gui.widgets.ParallelCpuSelectionList;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ParallelCraftingCPUScreen extends CraftingCPUScreen<ParallelCraftingCPUMenu> {

    private final SettingToggleButton<CpuSelectionMode> selectionMode;

    public ParallelCraftingCPUScreen(
            ParallelCraftingCPUMenu menu,
            Inventory playerInventory,
            Component title,
            ScreenStyle style
    ) {
        super(menu, playerInventory, title, style);
        this.selectionMode = new ServerSettingToggleButton<>(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY);
        addToLeftToolbar(this.selectionMode);

        Scrollbar scrollbar = widgets.addScrollBar("selectCpuScrollbar", Scrollbar.BIG);
        widgets.add("selectCpuList", new ParallelCpuSelectionList(menu, scrollbar, style));
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        this.selectionMode.set(this.menu.getSelectionMode());
    }

    @Override
    protected Component getGuiDisplayName(Component in) {
        return in;
    }
}
