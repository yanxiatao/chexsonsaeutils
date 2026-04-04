package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.AE2Button;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.CraftingContinuationMode;
import git.chexson.chexsonsaeutils.crafting.submit.CraftingContinuationSubmitBridge;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenContinuationMixin extends AEBaseScreen<CraftConfirmMenu> {
    @Unique
    private static final String chexsonContinuationMode = "chexsonContinuationMode";

    @Unique
    private static final String chexsonsaeutils$defaultModeKey = "gui.chexsonsaeutils.crafting_mode.default";

    @Unique
    private static final String chexsonsaeutils$ignoreMissingModeKey =
            "gui.chexsonsaeutils.crafting_mode.ignore_missing";

    @Shadow(remap = false)
    private Button start;

    @Shadow(remap = false)
    private Button selectCPU;

    @Unique
    private AE2Button chexsonsaeutils$continuationModeButton;

    protected CraftConfirmScreenContinuationMixin(CraftConfirmMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void chexsonsaeutils$installContinuationModeButton(CallbackInfo ci) {
        if (chexsonsaeutils$continuationModeButton == null) {
            chexsonsaeutils$continuationModeButton = widgets.addButton(
                    chexsonContinuationMode,
                    chexsonsaeutils$getModeLabel(CraftingContinuationMode.defaultMode()),
                    this::chexsonsaeutils$cycleContinuationMode);
        }
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$updateContinuationModeState(CallbackInfo ci) {
        if (chexsonsaeutils$continuationModeButton == null) {
            return;
        }

        CraftConfirmMenu menu = getMenu();
        CraftingContinuationMode mode = CraftingContinuationSubmitBridge.getConfirmMode(menu);
        chexsonsaeutils$continuationModeButton.setMessage(chexsonsaeutils$getModeLabel(mode));
        chexsonsaeutils$continuationModeButton.visible = true;
        chexsonsaeutils$continuationModeButton.active = true;

        CraftingPlanSummary plan = menu.getPlan();
        boolean planIsStartable = plan != null
                && (!plan.isSimulation() || CraftingContinuationSubmitBridge.allowsSimulationStart(menu, plan));
        start.active = !this.menu.hasNoCPU() && planIsStartable;
        selectCPU.active = planIsStartable;
    }

    @Unique
    private void chexsonsaeutils$cycleContinuationMode() {
        CraftConfirmMenu menu = getMenu();
        CraftingContinuationMode nextMode = CraftingContinuationSubmitBridge.getConfirmMode(menu)
                == CraftingContinuationMode.IGNORE_MISSING
                ? CraftingContinuationMode.defaultMode()
                : CraftingContinuationMode.IGNORE_MISSING;
        CraftingContinuationSubmitBridge.setConfirmMode(menu, nextMode);
        if (chexsonsaeutils$continuationModeButton != null) {
            chexsonsaeutils$continuationModeButton.setMessage(chexsonsaeutils$getModeLabel(nextMode));
        }
    }

    @Unique
    private static Component chexsonsaeutils$getModeLabel(CraftingContinuationMode mode) {
        return Component.translatable(mode == CraftingContinuationMode.IGNORE_MISSING
                ? chexsonsaeutils$ignoreMissingModeKey
                : chexsonsaeutils$defaultModeKey);
    }
}
