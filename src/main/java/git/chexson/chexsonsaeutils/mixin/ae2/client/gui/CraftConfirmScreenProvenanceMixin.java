package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;
import git.chexson.chexsonsaeutils.crafting.solve.MenuProvenanceHost;
import git.chexson.chexsonsaeutils.crafting.solve.PlanningProvenance;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.text.NumberFormat;

/**
 * Replaces the confirm-dialog title's plain "bytes used" line with the
 * planning provenance: wall-clock calculation time, bytes and solving level.
 */
@Mixin(value = CraftConfirmScreen.class, remap = false, priority = 1500)
public abstract class CraftConfirmScreenProvenanceMixin extends AEBaseScreen<CraftConfirmMenu> {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Unique
    private boolean chexsonsaeutils$loggedMissingProvenance = false;

    protected CraftConfirmScreenProvenanceMixin(CraftConfirmMenu menu, Inventory playerInventory, Component title,
            ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$overwriteTitleWithProvenance(CallbackInfo ci) {
        CraftingPlanSummary plan = getMenu().getPlan();
        if (plan == null) {
            return;
        }
        // 优先菜单 @GuiSync（跨规划 mod 可靠），回退 summary 携带（单 mod 路径）
        long millis = -1L;
        String solverId = "";
        if (getMenu() instanceof MenuProvenanceHost host
                && !host.chexsonsaeutils$menuProvenanceSolver().isEmpty()) {
            millis = host.chexsonsaeutils$menuProvenanceMillis();
            solverId = host.chexsonsaeutils$menuProvenanceSolver();
        } else if (plan instanceof EnhancedCraftingPlanSummary enhanced
                && !enhanced.chexsonsaeutils$solver().isEmpty()) {
            millis = enhanced.chexsonsaeutils$planningMillis();
            solverId = enhanced.chexsonsaeutils$solver();
        }
        if (solverId.isEmpty()) {
            if (!this.chexsonsaeutils$loggedMissingProvenance) {
                this.chexsonsaeutils$loggedMissingProvenance = true;
                LOGGER.debug("Craft confirm summary carries no planning provenance; keeping AE2 title");
            }
            return;
        }
        Component solver = Component.translatable(PlanningProvenance.solverTranslationKey(solverId));
        Component provenance = Component.translatable(
                "gui.chexsonsaeutils.crafting_plan.provenance",
                NumberFormat.getInstance().format(Math.max(0L, millis)),
                chexsonsaeutils$formatBytes(plan.getUsedBytes()),
                solver);
        setTextContent(TEXT_ID_DIALOG_TITLE, GuiText.CraftingPlan.text(provenance));
    }

    @Unique
    private static String chexsonsaeutils$formatBytes(long bytes) {
        if (bytes < 1000L) {
            return bytes + " B";
        }
        String[] units = { "K", "M", "G", "T", "P" };
        double value = bytes;
        int unit = -1;
        while (value >= 1000.0 && unit < units.length - 1) {
            value /= 1000.0;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f%s B", value, units[unit]);
    }
}
