package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "appeng.crafting.execution.ExecutingCraftingJob$TaskProgress", remap = false)
public interface ExecutingCraftingJobTaskProgressAccessor {

    @Accessor(value = "value", remap = false)
    long getValue();

    @Accessor(value = "value", remap = false)
    void setValue(long value);
}
