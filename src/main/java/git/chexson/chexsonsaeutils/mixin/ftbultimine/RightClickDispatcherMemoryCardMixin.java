package git.chexson.chexsonsaeutils.mixin.ftbultimine;

import appeng.api.implementations.items.IMemoryCard;
import dev.ftb.mods.ftbultimine.FTBUltiminePlayerData;
import dev.ftb.mods.ftbultimine.api.shape.ShapeContext;
import dev.ftb.mods.ftbultimine.rightclick.RightClickDispatcher;
import git.chexson.chexsonsaeutils.config.FtbUltimineMemoryCardFeatureGate;
import git.chexson.chexsonsaeutils.integration.ftbultimine.AEMemoryCardHandler;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RightClickDispatcher.class, remap = false)
public abstract class RightClickDispatcherMemoryCardMixin {

    @Inject(method = "dispatchRightClick", at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$applyMemoryCardToUltimineSelection(
            ShapeContext shapeContext,
            InteractionHand hand,
            FTBUltiminePlayerData data,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!FtbUltimineMemoryCardFeatureGate.isEnabledAtStartup()) {
            return;
        }
        if (!(shapeContext.player().getItemInHand(hand).getItem() instanceof IMemoryCard)) {
            return;
        }

        int result = AEMemoryCardHandler.applySettings(shapeContext.player(), hand, data.cachedPositions());
        if (result > 0) {
            cir.setReturnValue(result);
        }
    }
}
