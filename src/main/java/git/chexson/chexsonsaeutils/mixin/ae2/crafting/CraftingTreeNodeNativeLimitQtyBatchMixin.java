package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.crafting.solve.FastLimitQtyBatcher;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 原生 AE2 计算路径的递归（limitQty）样板批量优化：把"每件合成一次"的
 * O(n) 逐件试探替换为 {@link FastLimitQtyBatcher} 的净流量批量记账
 * （O(子树)）。批量记账结果与逐件路径逐位一致，且不适用时自动回退逐件。
 *
 * <p>仅当请求命中"单分支 + limitQty 样板 + 批量记账干净场景"时接管
 * {@code request}；接管后先复刻原生前置步骤（记账、模板提取、容器物品），
 * 批量失败则在取消后的副本里执行与原生逐位一致的逐件循环。其余任何场景
 * （多分支、可发射、非 limitQty 等）不取消注入，完全走 AE2 原生逻辑。
 *
 * <p>快速计算由 {@link CraftingTreeNodeFastBatchMixin} 整体接管并自带批量；
 * 染色计算使用独立的树节点类，不会经过本类。因此按 job 的确切类型分流，
 * 三者互不重叠。
 */
@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class CraftingTreeNodeNativeLimitQtyBatchMixin {

    @Shadow(remap = false)
    private AEKey what;
    @Shadow(remap = false)
    private long amount;
    @Shadow(remap = false)
    private ArrayList<CraftingTreeProcess> nodes;
    @Shadow(remap = false)
    private boolean canEmit;
    @Shadow(remap = false)
    private CraftingCalculation job;

    @Shadow(remap = false)
    abstract void buildChildPatterns();

    @Shadow(remap = false)
    abstract Iterable<InputTemplate> getValidItemTemplates(ICraftingInventory inv);

    @Shadow(remap = false)
    abstract void addContainerItems(AEKey template, long multiplier, @Nullable KeyCounter outputList);

    @Inject(method = "request", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$nativeLimitQtyBatch(
            CraftingSimulationState inv,
            long requestedAmount,
            @Nullable KeyCounter containerItems,
            CallbackInfo ci
    ) throws CraftBranchFailure, InterruptedException {
        if (ci.isCancelled() || this.job.getClass() != CraftingCalculation.class) {
            return;
        }
        if (this.canEmit) {
            return;
        }
        // 预检：仅"单分支 + limitQty"场景值得接管；buildChildPatterns 是幂等缓存，
        // 未接管时原生随后的调用为空操作。
        buildChildPatterns();
        if (this.nodes == null || this.nodes.size() != 1) {
            return;
        }
        CraftingTreeProcess pro = this.nodes.get(0);
        var processAccessor = (CraftingTreeProcessFastAccessor) pro;
        if (!processAccessor.chexsonsaeutils$limitsQuantity()
                || !processAccessor.chexsonsaeutils$isPossible()) {
            return;
        }
        var craftedPerPattern = processAccessor.chexsonsaeutils$getOutputCount(this.what);
        if (craftedPerPattern <= 0) {
            return;
        }

        // 接管：以下为原生前置步骤的逐位复刻。
        ci.cancel();
        ((CraftingCalculationAccessor) this.job).chexsonsaeutils$handlePausing();
        inv.addStackBytes(this.what, this.amount, requestedAmount);

        for (var template : getValidItemTemplates(inv)) {
            long extracted = CraftingCpuHelper.extractTemplates(inv, template, requestedAmount);
            if (extracted > 0) {
                requestedAmount -= extracted;
                addContainerItems(template.key(), extracted, containerItems);
                if (requestedAmount == 0) {
                    return;
                }
            }
        }
        addContainerItems(this.what, requestedAmount, containerItems);

        long totalRequestedItems = requestedAmount * this.amount;
        if (totalRequestedItems > 0
                && FastLimitQtyBatcher.tryBatch(inv, pro, this.what, totalRequestedItems, craftedPerPattern)) {
            var available = inv.extract(this.what, totalRequestedItems, Actionable.MODULATE);
            if (available != 0) {
                totalRequestedItems -= available;
                if (totalRequestedItems <= 0) {
                    return;
                }
            }
        }
        // 批量未命中：回退逐件循环。预检已保证 limitsQuantity，逐件 times 恒为 1，
        // 与原生该分支逐位一致。
        while (processAccessor.chexsonsaeutils$isPossible() && totalRequestedItems > 0) {
            long times = 1;
            processAccessor.chexsonsaeutils$request(inv, times);
            var available = inv.extract(this.what, totalRequestedItems, Actionable.MODULATE);
            if (available != 0) {
                totalRequestedItems -= available;
                if (totalRequestedItems <= 0) {
                    return;
                }
            } else {
                IPatternDetails details = ((CraftingTreeProcessAccessor) pro).chexsonsaeutils$getDetails();
                String outputs = details.getOutputs()
                        .stream()
                        .map(GenericStack::toString)
                        .collect(Collectors.joining(", "));
                String errorMessage = """
                        Unexpected error in the crafting calculation: can't find created items.
                        This is an AE2 bug, please report it, with the following important information:

                        - Found none of %s. Remaining request: %d of %d*%d.
                        - Tried crafting %d times the pattern %s.
                        - Pattern outputs: %s.
                        """.formatted(what, totalRequestedItems, requestedAmount, amount, times, details.getDefinition(), outputs);
                throw new UnsupportedOperationException(errorMessage);
            }
        }

        if (this.job.isSimulation()) {
            ((CraftingCalculationAccessor) this.job).chexsonsaeutils$addMissing(this.what, totalRequestedItems);
        } else {
            throw new CraftBranchFailure(this.what, totalRequestedItems);
        }
    }
}
