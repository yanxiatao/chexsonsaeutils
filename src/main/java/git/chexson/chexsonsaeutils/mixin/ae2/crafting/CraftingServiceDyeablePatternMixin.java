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
        // Dyeable ring-replacement must keep its dedicated planning for colored outputs.
        if (dyeableEnabled && DyeablePatternCraftingCalculation.isDyeableRequest(output)) {
            return new DyeablePatternCraftingCalculation(level, grid, simRequester, output, strategy);
        }
        // Parallel CPU fast path: full-speed native-identical planning with fallback.
        if (ParallelCpuFastPlanning.shouldUseFastPlanning(grid)) {
            return new FastCraftingCalculation(
                    level, grid, simRequester, output, strategy, ParallelCpuFastPlanning.budgetMillis());
        }
        if (dyeableEnabled) {
            return new DyeablePatternCraftingCalculation(level, grid, simRequester, output, strategy);
        }
        return new CraftingCalculation(level, grid, simRequester, output, strategy);
    }
}
