package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.client.gui.Ae2ByteDisplayFormatter;
import git.chexson.chexsonsaeutils.client.gui.Ae2CompactNumberFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenParallelCpuMixin extends AEBaseScreen<CraftConfirmMenu> {

    protected CraftConfirmScreenParallelCpuMixin(
            CraftConfirmMenu menu,
            Inventory playerInventory,
            Component title,
            ScreenStyle style
    ) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$formatSelectedCpuBytesSafely(CallbackInfo ci) {
        CraftingPlanSummary plan = getMenu().getPlan();
        if (plan == null || plan.isSimulation() || getMenu().getCpuAvailableBytes() <= 0L) {
            return;
        }

        setTextContent(
                "cpu_status",
                GuiText.ConfirmCraftCpuStatus.text(
                        Ae2ByteDisplayFormatter.component(getMenu().getCpuAvailableBytes()),
                        Ae2CompactNumberFormatter.component(getMenu().getCpuCoProcessors())
                )
        );
    }
}
