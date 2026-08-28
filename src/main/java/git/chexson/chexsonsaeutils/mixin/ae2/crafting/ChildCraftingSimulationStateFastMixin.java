package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastSimStateParentAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets the fast planning pool re-point a recycled {@link ChildCraftingSimulationState}
 * at a new parent. The {@code parent} field is {@code final}; {@code @Mutable @Final}
 * relaxes that for the accessor without any reflection.
 *
 * <p>Only a new setter is added; existing behavior is untouched.
 */
@Mixin(value = ChildCraftingSimulationState.class, remap = false)
public abstract class ChildCraftingSimulationStateFastMixin implements FastSimStateParentAccess {

    @Mutable
    @Final
    @Shadow(remap = false)
    private ICraftingInventory parent;

    @Override
    public void chexsonsaeutils$setFastParent(ICraftingInventory parent) {
        this.parent = parent;
    }
}
