package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = CraftingCalculation.class, remap = false)
public interface CraftingCalculationAccessor {
    @Accessor(value = "level", remap = false)
    Level chexsonsaeutils$getLevel();

    @Accessor(value = "networkInv", remap = false)
    NetworkCraftingSimulationState chexsonsaeutils$getNetworkInv();

    @Accessor(value = "tree", remap = false)
    CraftingTreeNode chexsonsaeutils$getTree();

    @Invoker(value = "handlePausing", remap = false)
    void chexsonsaeutils$handlePausing() throws InterruptedException;

    @Invoker(value = "addMissing", remap = false)
    void chexsonsaeutils$addMissing(AEKey what, long amount);
}
