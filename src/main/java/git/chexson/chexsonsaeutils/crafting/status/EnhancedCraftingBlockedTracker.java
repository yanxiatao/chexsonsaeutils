package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;

public interface EnhancedCraftingBlockedTracker {

    void chexsonsaeutils$clearBlockedTasks();

    long chexsonsaeutils$blockedAmount(AEKey what);

    default void chexsonsaeutils$recordBlockedPattern(IPatternDetails patternDetails) {
        if (patternDetails == null) {
            return;
        }

        KeyCounter blockedTasks = chexsonsaeutils$blockedTasks();
        CraftingCpuLogicAccessor accessor = (CraftingCpuLogicAccessor) this;
        for (var output : patternDetails.getOutputs()) {
            if (output.what() == null || output.amount() <= 0L) {
                continue;
            }
            blockedTasks.add(output.what(), output.amount());
            accessor.invokePostChange(output.what());
        }
    }

    KeyCounter chexsonsaeutils$blockedTasks();

    static EnhancedCraftingBlockedTracker from(CraftingCpuLogic logic) {
        return (EnhancedCraftingBlockedTracker) logic;
    }
}
