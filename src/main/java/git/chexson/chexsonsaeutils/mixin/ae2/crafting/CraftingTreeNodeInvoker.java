package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.api.stacks.KeyCounter;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@link CraftingTreeNode} 的包私有方法给快速计算路径使用。快速计算复用
 * AE2 原生树/状态算法以保证计划结果与原生完全一致，但这些方法为 appeng.crafting
 * 包私有，需经 Invoker 拓宽可见性。
 */
@Mixin(value = CraftingTreeNode.class, remap = false)
public interface CraftingTreeNodeInvoker {

    @Invoker(value = "request", remap = false)
    void chexsonsaeutils$request(
            CraftingSimulationState inv,
            long requestedAmount,
            @Nullable KeyCounter containerItems
    ) throws CraftBranchFailure, InterruptedException;

    @Invoker(value = "getNodeCount", remap = false)
    long chexsonsaeutils$getNodeCount();

    @Invoker(value = "hasMultiplePaths", remap = false)
    boolean chexsonsaeutils$hasMultiplePaths();
}
