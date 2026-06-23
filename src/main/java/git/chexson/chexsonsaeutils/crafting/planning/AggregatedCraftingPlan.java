package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import java.util.Map;

public record AggregatedCraftingPlan(
        GenericStack finalOutput,
        long bytes,
        boolean simulation,
        boolean multiplePaths,
        KeyCounter usedItems,
        KeyCounter emittedItems,
        KeyCounter missingItems,
        Map<IPatternDetails, Long> patternTimes,
        boolean dyeableRecursivePlan,
        KeyCounter dyeableRecursiveInitialItems,
        KeyCounter dyeableRecursiveInternalItems,
        long dyeableRecursiveFinalOutputAmount
) implements ICraftingPlan {
}
