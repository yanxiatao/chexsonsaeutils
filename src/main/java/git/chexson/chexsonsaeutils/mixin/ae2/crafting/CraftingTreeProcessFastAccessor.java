package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.stacks.AEKey;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@link CraftingTreeProcess} 的包私有成员，供快速计算的批量多分支探测使用。
 */
@Mixin(value = CraftingTreeProcess.class, remap = false)
public interface CraftingTreeProcessFastAccessor {

    @Accessor(value = "possible", remap = false)
    boolean chexsonsaeutils$isPossible();

    @Accessor(value = "possible", remap = false)
    void chexsonsaeutils$setPossible(boolean possible);

    @Invoker(value = "request", remap = false)
    void chexsonsaeutils$request(CraftingSimulationState inv, long times)
            throws CraftBranchFailure, InterruptedException;

    @Invoker(value = "getOutputCount", remap = false)
    long chexsonsaeutils$getOutputCount(AEKey what);

    @Invoker(value = "limitsQuantity", remap = false)
    boolean chexsonsaeutils$limitsQuantity();
}
