package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.NumberEntryType;
import appeng.client.gui.Icon;
import appeng.client.gui.implementations.AESubScreen;
import appeng.client.gui.style.StyleManager;
import appeng.client.gui.widgets.ToggleButton;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftAmountMenu;
import git.chexson.chexsonsaeutils.crafting.request.CraftingRequestCompatibilityAccess;
import git.chexson.chexsonsaeutils.network.crafting.WideCraftRequestPayload;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import java.math.BigInteger;
import java.util.List;

/** AE2 原布局的大数下单屏；无需 Data 或 AE2LT，也不使用其有损显示数量换算。 */
public final class WideCraftAmountScreen extends AEBaseScreen<CraftAmountMenu> {
    private final Button next;
    private final WideCraftingAmountWidget amount;
    private ToggleButton mode;
    private boolean initialized;
    private BigInteger restored;
    private boolean restoredMissing;
    public WideCraftAmountScreen(CraftAmountMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StyleManager.loadStyleDoc("/screens/craft_amount.json"));
        next = widgets.addButton("next", GuiText.Next.text(), this::confirm);
        AESubScreen.addBackButton(menu, "back", widgets);
        amount = new WideCraftingAmountWidget(style);
        widgets.add("amountToCraft", amount);
        amount.setTextFieldStyle(style.getWidget("amountToCraftInput"));
        amount.setOnConfirm(this::confirm);
        var compatibility = CraftingRequestCompatibilityAccess.get();
        if (compatibility.finalTotal(menu).isPresent()) {
            mode = new ToggleButton(Icon.SCHEDULING_ROUND_ROBIN, Icon.SCHEDULING_RANDOM,
                    netNew -> compatibility.finalTotal(menu, !netNew));
            mode.setTooltipOn(List.of(Component.translatable("gui.data_energistics.trinity_quantity.mode",
                    Component.translatable("gui.data_energistics.trinity_quantity.net_new"))));
            mode.setTooltipOff(List.of(Component.translatable("gui.data_energistics.trinity_quantity.mode",
                    Component.translatable("gui.data_energistics.trinity_quantity.final_total"))));
            addToLeftToolbar(mode);
        }
    }
    /** 返回数量选择页时接收服务端的精确回填。 */
    public void restore(BigInteger value, boolean missing) {
        restored = value; restoredMissing = missing; if (initialized) amount.restore(value, missing);
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        if (!initialized && menu.getWhatToCraft() != null) {
            var stack = menu.getWhatToCraft();
            amount.setType(NumberEntryType.of(stack.what()));
            amount.setExactValue(restored != null ? restored : CraftingRequestCompatibilityAccess.get().initialAmount(menu)
                    .orElse(BigInteger.valueOf(Math.max(1, stack.amount()))));
            if (restored != null) amount.restore(restored, restoredMissing);
            initialized = true;
        }
        if (mode != null) mode.setState(!CraftingRequestCompatibilityAccess.get().finalTotal(menu).orElseThrow());
        next.setMessage(hasShiftDown() ? GuiText.Start.text() : GuiText.Next.text());
        next.active = initialized && amount.exactValue().isPresent();
    }
    private void confirm() {
        if (!initialized) return;
        amount.exactValue().ifPresent(value -> PacketDistributor.sendToServer(new WideCraftRequestPayload(
                menu.containerId, value, amount.startsWithEquals(), hasShiftDown(),
                CraftingRequestCompatibilityAccess.get().finalTotal(menu).orElse(false))));
    }
}
