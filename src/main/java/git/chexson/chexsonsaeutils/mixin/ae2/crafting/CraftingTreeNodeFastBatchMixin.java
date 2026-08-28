package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastCraftingCalculation;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastLimitQtyBatcher;
import git.chexson.chexsonsaeutils.crafting.fastplan.FastSimStatePool;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * 直接优化 plan 生成算法：对快速计算（{@link FastCraftingCalculation}）将 AE2
 * 多分支「逐件合成」（O(n)，每件新建一个子模拟状态）替换为「指数批量探测」
 * （O(log n)）。单分支、可发射、存储提取等特判保持与原生一致，因此计划结果
 * （patternTimes/usedItems）与原生相同，只是工作量大幅减少——既快又不会占满 CPU。
 *
 * <p>仅对 {@link FastCraftingCalculation} 生效；原生计算走 AE2 原逻辑，不受影响。
 */
@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class CraftingTreeNodeFastBatchMixin {

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
    private Level level;

    @Shadow(remap = false)
    abstract void buildChildPatterns();

    @Shadow(remap = false)
    abstract Iterable<InputTemplate> getValidItemTemplates(ICraftingInventory inv);

    @Shadow(remap = false)
    abstract void addContainerItems(AEKey template, long multiplier, @Nullable KeyCounter outputList);

    @Inject(method = "request", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$fastBatchRequest(
            CraftingSimulationState inv,
            long requestedAmount,
            @Nullable KeyCounter containerItems,
            CallbackInfo ci
    ) throws CraftBranchFailure, InterruptedException {
        if (!(this.job instanceof FastCraftingCalculation fast)) {
            return; // 原生路径不变
        }
        ci.cancel();

        fast.fastHandlePausing();
        inv.addStackBytes(this.what, this.amount, requestedAmount);

        // 1) 从库存提取（特判：完全满足则直接返回，不再展开合成树）
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

        // 2) 可发射物品（特判）
        if (this.canEmit) {
            inv.emitItems(this.what, this.amount * requestedAmount);
            return;
        }

        // 3) 使用样板
        buildChildPatterns();
        long totalRequestedItems = requestedAmount * this.amount;

        if (this.nodes.size() == 1) {
            // 单分支：与原生一致（本身已批量）
            final CraftingTreeProcess pro = this.nodes.get(0);
            var processAccessor = (CraftingTreeProcessFastAccessor) pro;
            var craftedPerPattern = processAccessor.chexsonsaeutils$getOutputCount(this.what);

            // limitQty（容器/耐久/不消耗）专用批量：干净场景一次性完成，把逐件 O(n) 降为 O(子树)。
            // 失败或不满足干净条件时返回 false，落到下方逐件循环，结果保持不变。
            if (processAccessor.chexsonsaeutils$limitsQuantity()
                    && processAccessor.chexsonsaeutils$isPossible()
                    && totalRequestedItems > 0
                    && FastLimitQtyBatcher.tryBatch(inv, pro, this.what, totalRequestedItems, craftedPerPattern)) {
                var available = inv.extract(this.what, totalRequestedItems, Actionable.MODULATE);
                if (available != 0) {
                    totalRequestedItems -= available;
                    if (totalRequestedItems <= 0) {
                        return;
                    }
                }
            }

            while (processAccessor.chexsonsaeutils$isPossible() && totalRequestedItems > 0) {
                long times = processAccessor.chexsonsaeutils$limitsQuantity()
                        ? 1
                        : (totalRequestedItems + craftedPerPattern - 1) / craftedPerPattern;
                processAccessor.chexsonsaeutils$request(inv, times);
                var available = inv.extract(this.what, totalRequestedItems, Actionable.MODULATE);
                if (available != 0) {
                    totalRequestedItems -= available;
                    if (totalRequestedItems <= 0) {
                        return;
                    }
                } else {
                    throw new UnsupportedOperationException(
                            "Unexpected error in the crafting calculation: can't find created items.");
                }
            }
        } else if (this.nodes.size() > 1) {
            // 多分支：指数批量探测（O(log n)），替代原生逐件（O(n)）
            for (CraftingTreeProcess pro : this.nodes) {
                var accessor = (CraftingTreeProcessFastAccessor) pro;
                if (!accessor.chexsonsaeutils$isPossible() || totalRequestedItems <= 0) {
                    continue;
                }
                long craftedPer = Math.max(1L, accessor.chexsonsaeutils$getOutputCount(this.what));
                long probe = 1;
                while (accessor.chexsonsaeutils$isPossible() && totalRequestedItems > 0) {
                    long maxByRemaining = (totalRequestedItems + craftedPer - 1) / craftedPer;
                    long testAmount = Math.min(probe, Math.max(1L, maxByRemaining));
                    var child = FastSimStatePool.acquire(inv);
                    try {
                        accessor.chexsonsaeutils$request(child, testAmount);
                        var available = child.extract(this.what, totalRequestedItems, Actionable.MODULATE);
                        if (available != 0) {
                            child.applyDiff(inv);
                            totalRequestedItems -= available;
                            if (totalRequestedItems <= 0) {
                                return;
                            }
                            probe = Math.min(probe * 2, Math.max(1L,
                                    (totalRequestedItems + craftedPer - 1) / craftedPer));
                        } else {
                            accessor.chexsonsaeutils$setPossible(false);
                        }
                    } catch (CraftBranchFailure fail) {
                        if (testAmount <= 1) {
                            // 单件都失败：保留原生语义，尝试下一分支
                            accessor.chexsonsaeutils$setPossible(true);
                            break;
                        }
                        probe = Math.max(1L, probe / 2);
                    } finally {
                        FastSimStatePool.release(child);
                    }
                }
            }
        }

        if (this.job.isSimulation()) {
            fast.fastAddMissing(this.what, totalRequestedItems);
        } else {
            throw new CraftBranchFailure(this.what, totalRequestedItems);
        }
    }
}
