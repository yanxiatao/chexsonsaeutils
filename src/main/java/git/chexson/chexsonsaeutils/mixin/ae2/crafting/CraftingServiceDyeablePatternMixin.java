package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingProviders;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastDyeablePatternCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.fastplan.ParallelCpuFastPlanning;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceDyeablePatternMixin {

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lappeng/me/service/helpers/NetworkCraftingProviders;"),
            remap = false
    )
    private NetworkCraftingProviders chexsonsaeutils$replaceCraftingProviders() {
        return new DyeablePatternCraftingProviders();
    }

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
    private CraftingCalculation chexsonsaeutils$redirectDyeableCraftingCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        boolean dyeableEnabled = FeatureGates.isEnabled(
                ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED, "dyeablePatternsEnabled");
        boolean fastPlanning = ParallelCpuFastPlanning.shouldUseFastPlanning(grid);
        long budgetMillis = ParallelCpuFastPlanning.budgetMillis();
        // Dyeable ring-replacement (recursive) planning keeps its dedicated logic; the
        // parallel CPU fast path accelerates it without changing its behavior.
        if (dyeableEnabled) {
            return fastPlanning
                    ? new FastDyeablePatternCraftingCalculation(
                            level, grid, simRequester, output, strategy, budgetMillis)
                    : new DyeablePatternCraftingCalculation(level, grid, simRequester, output, strategy);
        }
        // Non-dyeable: parallel CPU fast path runs the native-identical algorithm
        // full-speed with fallback; otherwise the vanilla AE2 calculation.
        return fastPlanning
                ? new FastCraftingCalculation(level, grid, simRequester, output, strategy, budgetMillis)
                : new CraftingCalculation(level, grid, simRequester, output, strategy);
    }
}
