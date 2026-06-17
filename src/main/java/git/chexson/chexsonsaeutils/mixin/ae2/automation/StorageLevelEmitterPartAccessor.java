package git.chexson.chexsonsaeutils.mixin.ae2.automation;

import appeng.api.networking.IStackWatcher;
import appeng.parts.automation.StorageLevelEmitterPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = StorageLevelEmitterPart.class, remap = false)
public interface StorageLevelEmitterPartAccessor {
    @Accessor(value = "storageWatcher", remap = false)
    IStackWatcher chexsonsaeutils$getStorageWatcher();

    @Accessor(value = "craftingWatcher", remap = false)
    IStackWatcher chexsonsaeutils$getCraftingWatcher();
}
