package git.chexson.chexsonsaeutils.mixin.neoecoae;

import git.chexson.chexsonsaeutils.client.gui.crafting.WideAmountCraftConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 自有计划预览保持菜单与屏实例，避免 NeoEco 初始化或迟到数据帧将其替换。 */
@Mixin(value = cn.dancingsnow.neoecoae.client.ECOCraftConfirmScreenIntegration.class, remap = false)
public abstract class NeoEcoOwnPlanPreviewMixin {
    @Inject(method = "route", at = @At("HEAD"), cancellable = true, remap = false)
    private static void chexsonsaeutils$keepOwnPreview(Screen screen, boolean renderFallback, CallbackInfo ci) {
        if (screen instanceof WideAmountCraftConfirmScreen) ci.cancel();
    }
}
