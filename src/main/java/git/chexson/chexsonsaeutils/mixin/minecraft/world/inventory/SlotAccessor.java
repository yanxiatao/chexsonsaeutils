package git.chexson.chexsonsaeutils.mixin.minecraft.world.inventory;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Slot.class, remap = false)
public interface SlotAccessor {
    @Mutable
    @Accessor(value = "x", remap = false)
    void chexsonsaeutils$setX(int x);

    @Mutable
    @Accessor(value = "y", remap = false)
    void chexsonsaeutils$setY(int y);
}
