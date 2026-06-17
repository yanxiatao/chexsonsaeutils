package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 染色样板 planning calculation 入口。
 *
 * 当前版本先保持 AE2 原生计算行为，只把后续染色 planning 扩展挂到独立 calculation 类型，
 * 避免后续继续挤占原生 CraftingCalculation 的构造入口。
 */
public class DyeablePatternCraftingCalculation extends CraftingCalculation {

    private final GenericStack requestedOutput;
    private final int requestedColor;

    public DyeablePatternCraftingCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        super(level, grid, simRequester, output, strategy);
        this.requestedOutput = output;
        this.requestedColor = resolveRequestedColor(output);
    }

    public GenericStack chexsonsaeutils$getRequestedOutput() {
        return this.requestedOutput;
    }

    public int chexsonsaeutils$getRequestedColor() {
        return this.requestedColor;
    }

    public boolean chexsonsaeutils$hasRequestedColor() {
        return this.requestedColor != -1;
    }

    @Nullable
    public DyeablePatternCompressedRing chexsonsaeutils$getPreparedCompressedRing(
            @Nullable DyeablePatternCraftingProviders providers
    ) {
        return resolvePreparedCompressedRing(this.requestedOutput, providers);
    }

    public boolean chexsonsaeutils$canPrepareRingPlanning(@Nullable DyeablePatternCraftingProviders providers) {
        return DyeablePatternCraftingPlanner.canPlanRingReplacementWithoutSwallowingReplacement(
                chexsonsaeutils$getPreparedCompressedRing(providers)
        );
    }

    static int resolveRequestedColor(@Nullable GenericStack output) {
        if (output == null || output.what() == null) {
            return -1;
        }
        return PatternColorHelper.getPatternColor(output.what().wrapForDisplayOrFilter());
    }

    @Nullable
    static DyeablePatternCompressedRing resolvePreparedCompressedRing(
            @Nullable GenericStack output,
            @Nullable DyeablePatternCraftingProviders providers
    ) {
        if (providers == null) {
            return null;
        }
        int color = resolveRequestedColor(output);
        if (color == -1) {
            return null;
        }
        return providers.getOrCalculateCompressedRing(color);
    }
}
