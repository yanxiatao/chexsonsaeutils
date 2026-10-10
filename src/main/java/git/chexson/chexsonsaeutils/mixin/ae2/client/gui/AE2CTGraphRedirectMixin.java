package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 移除确认屏的 AE2CT 旧入口，使用独立合成图按钮；不构造旧合成树或链接可选类。 */
@Mixin(value = AEBaseScreen.class, remap = false)
public abstract class AE2CTGraphRedirectMixin {
    @Inject(method = "addToLeftToolbar", at = @At("HEAD"), cancellable = true)
    private void redirect(Button button, CallbackInfoReturnable<Button> cir) {
        if ((Object) this instanceof CraftConfirmScreen
                && button.getClass().getName().equals("com.neuvillette.ae2ct.gui.ChangeButton"))
            cir.setReturnValue(button);
    }
}
