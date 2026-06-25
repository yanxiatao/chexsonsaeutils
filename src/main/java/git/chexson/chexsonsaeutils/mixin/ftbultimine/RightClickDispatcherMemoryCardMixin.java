package git.chexson.chexsonsaeutils.mixin.ftbultimine;

import appeng.api.implementations.items.IMemoryCard;
import dev.ftb.mods.ftbultimine.FTBUltiminePlayerData;
import dev.ftb.mods.ftbultimine.shape.ShapeContext;
import dev.ftb.mods.ftbultimine.utils.forge.PlatformMethodsImpl;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import git.chexson.chexsonsaeutils.integration.ftbultimine.AEMemoryCardHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlatformMethodsImpl.class, remap = false)
public abstract class RightClickDispatcherMemoryCardMixin {

    @Inject(method = "blockRightClick", at = @At("HEAD"), cancellable = true)
    private static void chexsonsaeutils$applyMemoryCardToUltimineSelection(
            ShapeContext shapeContext,
            ServerPlayer serverPlayer,
            InteractionHand hand,
            BlockPos clickPos,
            Direction face,
            FTBUltiminePlayerData data,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.FTB_ULTIMINE_MEMORY_CARD_ENABLED, "ftbUltimineMemoryCardEnabled")) {
            return;
        }
        if (!(serverPlayer.getItemInHand(hand).getItem() instanceof IMemoryCard)) {
            return;
        }

        int result = AEMemoryCardHandler.applySettings(serverPlayer, hand, data.cachedPositions());
        if (result > 0) {
            cir.setReturnValue(result);
        }
    }
}
