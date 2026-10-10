package git.chexson.chexsonsaeutils.mixin.ae2.client.gui;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.me.crafting.CraftConfirmMenu;
import git.chexson.chexsonsaeutils.client.gui.crafting.PlanGraphScreen;
import git.chexson.chexsonsaeutils.client.gui.crafting.PlanGraphOpenButton;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 所有 AE2 确认界面独立提供合成图入口，不依赖 AE2CT。 */
@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenGraphMixin extends AEBaseScreen<CraftConfirmMenu> {
    protected CraftConfirmScreenGraphMixin(CraftConfirmMenu menu, Inventory inventory, Component title, ScreenStyle style) { super(menu, inventory, title, style); }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void install(CraftConfirmMenu menu, Inventory inventory, Component title, ScreenStyle style, CallbackInfo ci) {
        addToLeftToolbar(new PlanGraphOpenButton(() -> {
            if (getMenu().getPlan() != null) switchToScreen(new PlanGraphScreen((CraftConfirmScreen) (Object) this));
        }));
    }
}
