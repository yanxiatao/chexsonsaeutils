package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.solve.OrderedPlanningCalculation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceOrderedPlanningMixin {

    @Redirect(
            method = "beginCraftingCalculation",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/Level;Lappeng/api/networking/IGrid;"
                            + "Lappeng/api/networking/crafting/ICraftingSimulationRequester;"
                            + "Lappeng/api/stacks/GenericStack;Lappeng/api/networking/crafting/CalculationStrategy;)"
                            + "Lappeng/crafting/CraftingCalculation;"
            ),
            remap = false
    )
    private CraftingCalculation chexsonsaeutils$redirectOrderedCraftingCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        long requestStartNanos = System.nanoTime();
        return new OrderedPlanningCalculation(level, grid, simRequester, output, strategy, requestStartNanos);
    }
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "beginCraftingCalculation",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;"), remap = false)
    private java.util.concurrent.Future<appeng.api.networking.crafting.ICraftingPlan> chexsonsaeutils$attachPlanningProgress(
            java.util.concurrent.ExecutorService executor,
            java.util.concurrent.Callable<appeng.api.networking.crafting.ICraftingPlan> work,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.util.concurrent.Future<appeng.api.networking.crafting.ICraftingPlan>> original,
            @com.llamalad7.mixinextras.sugar.Local CraftingCalculation job) {
        var future = original.call(executor, work);
        return job instanceof git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressSource source
                ? new git.chexson.chexsonsaeutils.crafting.progress.PlanningProgressFuture<>(future, source.planningProgress())
                : future;
    }
}
