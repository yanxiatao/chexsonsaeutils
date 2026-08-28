package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeFastAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeInvoker;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeProcessFastAccessor;

import java.util.Map;

/**
 * limitQty（容器/耐久/不消耗物品）样板的专用批量计算。
 *
 * <p>原生与现有快速路径对 limitQty 一律逐件模拟（O(n)）：因为直接批量
 * {@code request(inv, N)} 会把循环容器的 {@code requiredExtract} 记成 N×c 而非 c，
 * 导致 CPU 实际提取错误数量的物品。本类在“干净场景”下用专用记账一次性完成，把
 * O(n) 降到 O(子树)：
 * <ul>
 *   <li><b>纯回流容器</b>（{@code getRemainingKey(k)==k}）：只按单次用量提取并记一次
 *       回流，{@code requiredExtract} 保持 c；缺失的 (N-1) 份字节单独补齐。</li>
 *   <li><b>纯消耗输入</b>（{@code getRemainingKey==null}）：按 N 倍批量 {@code request}，
 *       完全复用 AE2 的提取/递归逻辑，天然正确。</li>
 * </ul>
 *
 * <p>该记账与逐件路径的最终结果（{@code requiredExtract}/{@code patternTimes}/字节）逐位
 * 一致——差别仅在中间轨迹（逐件“取-还”N 次 vs 批量“取一次还一次”），不影响最终计划。
 *
 * <p>安全：仅在干净场景启用；耐久变形、模糊替换、可发射、自输出、异常等任何不确定情况
 * 都返回 {@code false}，由调用方回退到逐件路径。全程在子沙盒中进行，成功才
 * {@code applyDiff}，失败即丢弃，绝不留下半成品状态。
 */
public final class FastLimitQtyBatcher {

    /** 仅供等价性测试：置为 {@code true} 时强制走逐件参照路径。 */
    public static volatile boolean forcePerItemForTesting = false;

    private FastLimitQtyBatcher() {
    }

    /**
     * 尝试以专用批量方式完成单个 limitQty 样板 {@code times} 次合成。
     *
     * @return {@code true} 表示已在 {@code inv} 中完成（产出已插入、记账已合并），调用方
     *         随后提取目标物品即可；{@code false} 表示应回退逐件路径，{@code inv} 未被改动。
     */
    public static boolean tryBatch(
            CraftingSimulationState inv,
            CraftingTreeProcess pro,
            AEKey what,
            long totalRequestedItems,
            long craftedPerPattern
    ) throws InterruptedException {
        if (forcePerItemForTesting) {
            return false;
        }
        if (craftedPerPattern <= 0 || totalRequestedItems <= 0) {
            return false;
        }
        long times = (totalRequestedItems + craftedPerPattern - 1) / craftedPerPattern;
        if (times <= 1) {
            return false; // 单件无批量收益，交给逐件路径
        }

        var processAccessor = (CraftingTreeProcessFastAccessor) pro;
        if (!processAccessor.chexsonsaeutils$hasContainerItems()) {
            return false; // 自输出等非容器触发的 limitQty：回退
        }
        IPatternDetails details = ((CraftingTreeProcessAccessor) pro).chexsonsaeutils$getDetails();
        Map<CraftingTreeNode, Long> inputNodes = processAccessor.chexsonsaeutils$getNodes();
        if (inputNodes.isEmpty()) {
            return false;
        }

        // 预校验：每个输入必须是“纯消耗”或“纯回流容器”，且无模糊替换/可发射/耐久变形。
        for (Map.Entry<CraftingTreeNode, Long> entry : inputNodes.entrySet()) {
            var nodeAccessor = (CraftingTreeNodeFastAccessor) entry.getKey();
            if (nodeAccessor.chexsonsaeutils$isCanEmit()) {
                return false;
            }
            IPatternDetails.IInput parentInput = nodeAccessor.chexsonsaeutils$getParentInput();
            if (parentInput == null) {
                return false;
            }
            var possibleInputs = parentInput.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length != 1) {
                return false; // 多候选 → 可能模糊替换，回退
            }
            AEKey nodeWhat = nodeAccessor.chexsonsaeutils$getWhat();
            if (nodeWhat == null || !nodeWhat.equals(possibleInputs[0].what())) {
                return false; // 发生了替换/模糊匹配，回退
            }
            AEKey remaining = parentInput.getRemainingKey(nodeWhat);
            if (remaining != null && !remaining.equals(nodeWhat)) {
                return false; // 耐久变形（回流物≠原物）：记账复杂，回退
            }
            long mult = entry.getValue();
            if (mult > 0 && times > Long.MAX_VALUE / mult) {
                return false; // 溢出保护
            }
        }

        ChildCraftingSimulationState sandbox = FastSimStatePool.acquire(inv);
        try {
            KeyCounter containerItems = new KeyCounter();
            for (Map.Entry<CraftingTreeNode, Long> entry : inputNodes.entrySet()) {
                CraftingTreeNode inputNode = entry.getKey();
                long mult = entry.getValue();
                var nodeAccessor = (CraftingTreeNodeFastAccessor) inputNode;
                AEKey nodeWhat = nodeAccessor.chexsonsaeutils$getWhat();
                AEKey remaining = nodeAccessor.chexsonsaeutils$getParentInput().getRemainingKey(nodeWhat);
                if (remaining == null) {
                    // 纯消耗：按 times 倍批量，复用 AE2 提取/递归，天然正确。
                    ((CraftingTreeNodeInvoker) inputNode)
                            .chexsonsaeutils$request(sandbox, mult * times, containerItems);
                } else {
                    // 纯回流容器：只按单次用量提取并记一次回流。
                    ((CraftingTreeNodeInvoker) inputNode).chexsonsaeutils$request(sandbox, mult, containerItems);
                    // 补齐 (times-1) 份该输入节点本应累计的 stack 字节。
                    sandbox.addStackBytes(nodeWhat, nodeAccessor.chexsonsaeutils$getAmount(), mult * (times - 1));
                }
            }

            // 容器回流：逐件路径每件插入一次（净 0），等价于把回流量插入一次；字节记 times 份。
            for (var containerEntry : containerItems) {
                AEKey key = containerEntry.getKey();
                long val = containerEntry.getLongValue();
                sandbox.insert(key, val, Actionable.MODULATE);
                sandbox.addStackBytes(key, val, times);
            }

            // 产出
            for (var out : details.getOutputs()) {
                sandbox.insert(out.what(), out.amount() * times, Actionable.MODULATE);
            }
            sandbox.addCrafting(details, times);
            sandbox.addBytes(times);

            sandbox.applyDiff(inv);
            return true;
        } catch (CraftBranchFailure failure) {
            return false; // 沙盒丢弃，回退逐件
        } catch (RuntimeException failure) {
            return false;
        } finally {
            FastSimStatePool.release(sandbox);
        }
    }
}
