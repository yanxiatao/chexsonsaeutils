package git.chexson.chexsonsaeutils.mixin.ae2.blockentity;

import appeng.block.networking.ControllerBlock;
import git.chexson.chexsonsaeutils.block.controller.SelfPoweredController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 让普通控制器与自供能控制器双向识别柱体外观，结构验证仍交给 AE2。 */
@Mixin(value = ControllerBlock.class, remap = false)
public abstract class SelfPoweredControllerConnectionMixin {
    @Inject(method = "isController", at = @At("HEAD"), cancellable = true)
    private static void chexsonsaeutils$connectController(LevelAccessor level, int x, int y, int z,
                                                         CallbackInfoReturnable<Boolean> cir) {
        var block = level.getBlockState(new BlockPos(x, y, z)).getBlock();
        if (block instanceof ControllerBlock && block instanceof SelfPoweredController) cir.setReturnValue(true);
    }
}
