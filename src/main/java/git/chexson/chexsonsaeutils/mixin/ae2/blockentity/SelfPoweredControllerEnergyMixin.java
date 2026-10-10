package git.chexson.chexsonsaeutils.mixin.ae2.blockentity;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.blockentity.powersink.AEBasePoweredBlockEntity;
import git.chexson.chexsonsaeutils.block.controller.SelfPoweredController;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 只为标记方块切换创造能源语义，原实体仍是网络能源服务的拥有者。 */
@Mixin(value = AEBasePoweredBlockEntity.class, remap = false)
public abstract class SelfPoweredControllerEnergyMixin implements IAEPowerStorage {
    // 容量按 Long.MAX_VALUE 申报，保留多控制器汇总余量；供能仍沿用创造能源语义。
    @Unique
    private static final double chexsonsaeutils$creativeCapacity = Long.MAX_VALUE;

    @Unique
    private boolean chexsonsaeutils$isSelfPowered() {
        return ((BlockEntity) (Object) this).getBlockState().getBlock() instanceof SelfPoweredController;
    }

    @Inject(method = "injectAEPower", at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$acceptPower(double amount, Actionable mode, CallbackInfoReturnable<Double> cir) {
        if (chexsonsaeutils$isSelfPowered()) cir.setReturnValue(0.0);
    }

    @Inject(method = {"getAEMaxPower", "getAECurrentPower"}, at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$creativeCapacity(CallbackInfoReturnable<Double> cir) {
        if (chexsonsaeutils$isSelfPowered()) cir.setReturnValue(chexsonsaeutils$creativeCapacity);
    }

    @Inject(method = "extractAEPower(DLappeng/api/config/Actionable;Lappeng/api/config/PowerMultiplier;)D",
            at = @At("HEAD"), cancellable = true)
    private void chexsonsaeutils$supplyPower(double amount, Actionable mode, PowerMultiplier multiplier,
                                            CallbackInfoReturnable<Double> cir) {
        if (chexsonsaeutils$isSelfPowered()) cir.setReturnValue(amount);
    }

    @Override
    public int getPriority() {
        return chexsonsaeutils$isSelfPowered() ? Integer.MAX_VALUE : 0;
    }
}
