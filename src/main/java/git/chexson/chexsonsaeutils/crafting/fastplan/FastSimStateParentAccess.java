package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.crafting.inv.ICraftingInventory;

/**
 * Duck interface mixed into {@code appeng.crafting.inv.ChildCraftingSimulationState}.
 *
 * <p>The child state's {@code parent} field is {@code final}; the fast planning
 * pool needs to re-point a pooled instance at a new parent when reusing it. This
 * accessor is mixed in with {@code @Mutable @Final} so no reflection is involved.
 */
public interface FastSimStateParentAccess {
    void chexsonsaeutils$setFastParent(ICraftingInventory parent);
}
