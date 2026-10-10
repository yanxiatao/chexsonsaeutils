package git.chexson.chexsonsaeutils.client.gui.playersimulation;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.implementations.AESubScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.core.localization.GuiText;
import git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.Component;

/** 与接口相同的数字输入、步进按钮、回车保存及返回操作。 */
public final class SimulationAmountScreen extends AEBaseScreen<SimulationAmountMenu> {
    private final NumberEntryWidget amount;
    private boolean initialized;
    public SimulationAmountScreen(SimulationAmountMenu menu,Inventory inv,Component title) {
        super(menu,inv,title,StyleManager.loadStyleDoc("/screens/set_stock_amount.json"));
        widgets.addButton("save",GuiText.Set.text(),this::confirm);
        AESubScreen.addBackButton(menu,"back",widgets);
        amount = widgets.addNumberEntryWidget("amountToStock",NumberEntryType.UNITLESS);
        amount.setTextFieldStyle(style.getWidget("amountToStockInput"));
        amount.setMinValue(0);
        amount.setHideValidationIcon(true);
        amount.setOnConfirm(this::confirm);
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        if (!initialized && menu.displayedKey() != null && menu.initialAmount >= 0) {
            amount.setType(NumberEntryType.of(menu.displayedKey()));
            amount.setMaxValue(menu.maxAmount);
            amount.setLongValue(menu.initialAmount);
            initialized = true;
        }
    }
    private void confirm() { amount.getIntValue().ifPresent(menu::confirm); }
}
