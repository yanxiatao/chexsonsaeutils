package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {

    @Invoker(value = "addMaxItems", remap = false)
    void invokeAddMaxItems(long itemDiff, AEKeyType keyType);

    @Invoker(value = "decrementItems", remap = false)
    void invokeDecrementItems(long itemDiff, AEKeyType keyType);
}
