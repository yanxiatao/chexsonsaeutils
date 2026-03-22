package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public interface ExecutingCraftingJobAccessor {
    @Accessor(value = "link", remap = false)
    CraftingLink getLink();

    @Accessor(value = "waitingFor", remap = false)
    ListCraftingInventory getWaitingFor();

    @Accessor(value = "finalOutput", remap = false)
    GenericStack getFinalOutput();

    @Accessor(value = "remainingAmount", remap = false)
    long getRemainingAmount();

    @Accessor(value = "playerId", remap = false)
    @Nullable
    Integer getPlayerId();

    @Accessor(value = "timeTracker", remap = false)
    ElapsedTimeTracker getTimeTracker();
}
