package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import net.minecraft.world.level.Level;

/**
 * 染色样板 planning calculation 入口。
 *
 * 当前版本先保持 AE2 原生计算行为，只把后续染色 planning 扩展挂到独立 calculation 类型，
 * 避免后续继续挤占原生 CraftingCalculation 的构造入口。
 */
public class DyeablePatternCraftingCalculation extends CraftingCalculation {

    public DyeablePatternCraftingCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        super(level, grid, simRequester, output, strategy);
    }
}
