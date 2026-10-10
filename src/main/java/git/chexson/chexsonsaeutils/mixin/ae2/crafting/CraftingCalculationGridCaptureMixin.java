package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchArmed;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE2 原生 {@code CraftingCalculation} 不持有 grid（ctor 用完即弃）——
 * ctor TAIL 捕获供宿主批次聚合的宿主探测；duck 默认 armed=true
 * （原生路径无 preliminary 快照），递归子类覆写 armed 精确控制。
 */
@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationGridCaptureMixin implements HostBatchArmed {

    @Unique
    private IGrid chexsonsaeutils$capturedGrid;

    @Unique
    private Boolean chexsonsaeutils$hostPresence;

    @Unique
    private git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPlanningCache
            chexsonsaeutils$planningCache;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$captureGrid(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy,
            CallbackInfo ci
    ) {
        this.chexsonsaeutils$capturedGrid = grid;
    }

    @Override
    public boolean chexsonsaeutils$hostBatchArmed() {
        return true;
    }

    @Override
    @Nullable
    public IGrid chexsonsaeutils$planningGrid() {
        return this.chexsonsaeutils$capturedGrid;
    }

    @Override
    @Nullable
    public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
        return null;
    }

    @Override
    @Nullable
    public java.util.Map<appeng.api.stacks.AEItemKey, java.util.Map<appeng.api.stacks.AEKey, Long>>
    chexsonsaeutils$dyeableSelectedPatternInputs() {
        return null;
    }

    @Override
    @Nullable
    public Boolean chexsonsaeutils$hostPresenceCache() {
        return this.chexsonsaeutils$hostPresence;
    }

    @Override
    public void chexsonsaeutils$setHostPresenceCache(@Nullable Boolean present) {
        this.chexsonsaeutils$hostPresence = present;
    }

    @Override
    @Nullable
    public git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPlanningCache
    chexsonsaeutils$hostBatchPlanningCache() {
        return this.chexsonsaeutils$planningCache;
    }

    @Override
    public void chexsonsaeutils$setHostBatchPlanningCache(
            @Nullable git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPlanningCache cache) {
        this.chexsonsaeutils$planningCache = cache;
    }
}
