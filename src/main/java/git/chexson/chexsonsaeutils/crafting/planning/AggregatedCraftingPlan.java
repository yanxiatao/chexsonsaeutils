package git.chexson.chexsonsaeutils.crafting.planning;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;

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
) implements ICraftingPlan, DyeablePatternRecursivePlan {

    @Override
    public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
        return dyeableRecursivePlan;
    }

    @Override
    public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
        KeyCounter copy = new KeyCounter();
        if (dyeableRecursiveInitialItems != null) {
            copy.addAll(dyeableRecursiveInitialItems);
        }
        return copy;
    }

    @Override
    public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
        KeyCounter copy = new KeyCounter();
        if (dyeableRecursiveInternalItems != null) {
            copy.addAll(dyeableRecursiveInternalItems);
        }
        return copy;
    }

    @Override
    public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
        return dyeableRecursiveFinalOutputAmount;
    }
}
