package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
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

import java.util.HashMap;
import java.util.Map;

/**
 * limitQty 样板的专用批量计算。
 *
 * <p>原生与现有快速路径对 limitQty 一律逐件模拟（O(n)）：因为直接批量
 * {@code request(inv, N)} 会把循环物品的 {@code requiredExtract} 记成 N×c 而非 c，
 * 导致 CPU 实际提取错误数量的物品。本类在“干净场景”下用专用记账一次性完成，把
 * O(n) 降到 O(子树)。支持两类循环物品：
 * <ul>
 *   <li><b>纯回流容器</b>（{@code getRemainingKey(k)==k}）：只按单次用量提取并记一次
 *       回流，{@code requiredExtract} 保持 c；缺失的 (N-1) 份字节单独补齐。</li>
 *   <li><b>纯自输出（催化物）</b>：输入原样出现在产出中且无 remainder
 *       （{@code getRemainingKey==null}）。本 mod 的直连处理机样板与合成机聚合模式
 *       的 {@code getRemainingKey} 恒为 null，它们的 limitQty 只会是这一类。产出
 *       不小于消耗时，逐件的“提取→回流”循环等价于“提取一次 + 产出按净增量修正”：
 *       回流经产出插入完成，插入量 {@code out×N - in×(N-1)} 使终态存量、
 *       {@code requiredExtract} 与逐件完全一致。</li>
 * </ul>
 * 其余输入按纯消耗处理：按 N 倍批量 {@code request}，完全复用 AE2 的提取/递归逻辑，
 * 天然正确。
 *
 * <p>该记账与逐件路径的最终结果（{@code requiredExtract}/{@code patternTimes}/字节/
 * 终态存量）逐位一致——差别仅在中间轨迹（逐件“取-还”N 次 vs 批量“取一次还一次”），
 * 不影响最终计划。
 *
 * <p>安全：仅在干净场景启用；耐久变形、模糊替换、可发射、自输出净消耗、自输出物即目标
 * 产物、容器与自输出叠加、异常等任何不确定情况都返回 {@code false}，由调用方回退到
 * 逐件路径。全程在子沙盒中进行，成功才 {@code applyDiff}，失败即丢弃，绝不留下半成品状态。
 */
public final class FastLimitQtyBatcher {

    /** 仅供等价性测试：置为 {@code true} 时强制走逐件参照路径。 */
    public static volatile boolean forcePerItemForTesting = false;

    private enum InputKind {
        /** 纯消耗：无回流，按 times 倍批量。 */
        CONSUME,
        /** 纯回流容器：getRemainingKey == 输入本身。 */
        RETURN_CONTAINER,
        /** 纯自输出（催化物）：输入原样出现在产出中，产出 >= 消耗。 */
        SELF_OUTPUT
    }

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
        IPatternDetails details = ((CraftingTreeProcessAccessor) pro).chexsonsaeutils$getDetails();
        // limitQty 只可能由容器回流或自输出触发；两者皆无（例如替换样板强制的
        // limitQty）时没有可批量化的循环物品，回退逐件。
        if (!processAccessor.chexsonsaeutils$hasContainerItems() && !hasSelfOutputTrigger(details)) {
            return false;
        }
        Map<CraftingTreeNode, Long> inputNodes = processAccessor.chexsonsaeutils$getNodes();
        if (inputNodes.isEmpty()) {
            return false;
        }

        // 预校验并归类：每个输入必须是纯消耗/纯回流容器/纯自输出，且无模糊替换/可发射/耐久变形。
        // 自输出物按键聚合消耗量（多个输入同为催化物的罕见情形）。
        InputKind[] kinds = new InputKind[inputNodes.size()];
        Map<AEKey, long[]> selfOutputs = new HashMap<>();
        int index = 0;
        for (Map.Entry<CraftingTreeNode, Long> entry : inputNodes.entrySet()) {
            InputKind kind = classifyInput(details, entry.getKey(), entry.getValue(), what, times, selfOutputs);
            if (kind == null) {
                return false;
            }
            kinds[index++] = kind;
        }

        ChildCraftingSimulationState sandbox = FastSimStatePool.acquire(inv);
        try {
            KeyCounter containerItems = new KeyCounter();
            index = 0;
            for (Map.Entry<CraftingTreeNode, Long> entry : inputNodes.entrySet()) {
                CraftingTreeNode inputNode = entry.getKey();
                long mult = entry.getValue();
                var nodeAccessor = (CraftingTreeNodeFastAccessor) inputNode;
                AEKey nodeWhat = nodeAccessor.chexsonsaeutils$getWhat();
                switch (kinds[index++]) {
                    case CONSUME ->
                        // 纯消耗：按 times 倍批量，复用 AE2 提取/递归，天然正确。
                            ((CraftingTreeNodeInvoker) inputNode)
                                    .chexsonsaeutils$request(sandbox, mult * times, containerItems);
                    case RETURN_CONTAINER, SELF_OUTPUT -> {
                        // 循环物品：只按单次用量提取。容器回流经 containerItems 记账，
                        // 自输出回流经下方修正后的产出插入完成；字节补齐 (times-1) 份。
                        ((CraftingTreeNodeInvoker) inputNode)
                                .chexsonsaeutils$request(sandbox, mult, containerItems);
                        sandbox.addStackBytes(nodeWhat, nodeAccessor.chexsonsaeutils$getAmount(), mult * (times - 1));
                    }
                }
            }

            // 容器回流：逐件路径每件插入一次（净 0），等价于把回流量插入一次；字节记 times 份。
            for (var containerEntry : containerItems) {
                AEKey key = containerEntry.getKey();
                long val = containerEntry.getLongValue();
                sandbox.insert(key, val, Actionable.MODULATE);
                sandbox.addStackBytes(key, val, times);
            }

            // 产出：自输出物的插入量需扣除“少提取的 (times-1) 份消耗”，其余照常 times 倍。
            // 同键多输出栈时，首个匹配栈承载全部修正量，后续同键栈不再插入。
            for (var out : details.getOutputs()) {
                long insertAmount = out.amount() * times;
                long[] selfOutput = selfOutputs.get(out.what());
                if (selfOutput != null) {
                    if (selfOutput[1] > 0) {
                        insertAmount = selfOutput[0] * times - selfOutput[1] * (times - 1);
                        selfOutput[1] = -1;
                    } else {
                        insertAmount = 0;
                    }
                }
                sandbox.insert(out.what(), insertAmount, Actionable.MODULATE);
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

    /**
     * 与 AE2 {@code CraftingTreeProcess#updateLimitQty} 的自输出判定保持一致：
     * 任一输入的主候选出现在产出中即触发自输出型 limitQty。
     */
    private static boolean hasSelfOutputTrigger(IPatternDetails details) {
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0) {
                continue;
            }
            if (outputAmountOf(details, possibleInputs[0].what()) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归类单个输入；任何不确定情况返回 {@code null}（调用方回退逐件）。
     * 自输出输入会把 {@code [单次产出, 单次消耗]} 累加进 {@code selfOutputs}。
     */
    private static InputKind classifyInput(
            IPatternDetails details,
            CraftingTreeNode inputNode,
            long mult,
            AEKey requestedWhat,
            long times,
            Map<AEKey, long[]> selfOutputs
    ) {
        var nodeAccessor = (CraftingTreeNodeFastAccessor) inputNode;
        if (nodeAccessor.chexsonsaeutils$isCanEmit()) {
            return null;
        }
        IPatternDetails.IInput parentInput = nodeAccessor.chexsonsaeutils$getParentInput();
        if (parentInput == null) {
            return null;
        }
        var possibleInputs = parentInput.getPossibleInputs();
        if (possibleInputs == null || possibleInputs.length != 1) {
            return null; // 多候选 → 可能模糊替换，回退
        }
        AEKey nodeWhat = nodeAccessor.chexsonsaeutils$getWhat();
        if (nodeWhat == null || !nodeWhat.equals(possibleInputs[0].what())) {
            return null; // 发生了替换/模糊匹配，回退
        }
        if (mult > 0 && times > Long.MAX_VALUE / mult) {
            return null; // 溢出保护
        }

        AEKey remaining = parentInput.getRemainingKey(nodeWhat);
        if (remaining != null) {
            if (!remaining.equals(nodeWhat)) {
                return null; // 耐久变形（回流物≠原物）：记账复杂，回退
            }
            if (outputAmountOf(details, nodeWhat) > 0) {
                return null; // 容器回流与自输出叠加：语义交叠，回退
            }
            return InputKind.RETURN_CONTAINER;
        }

        long outputPerCraft = outputAmountOf(details, nodeWhat);
        if (outputPerCraft <= 0) {
            return InputKind.CONSUME;
        }

        // 自输出（催化物）：原生逐件让回流产物供给后续合成。批量等价的前提是
        // 每次执行产出 >= 消耗（循环自持），且目标产物不是该循环物品本身
        // （否则节点提取与回流交叠，逐件反馈无法批量复现）。
        if (nodeWhat.equals(requestedWhat)) {
            return null;
        }
        long nodeAmount = nodeAccessor.chexsonsaeutils$getAmount();
        if (nodeAmount <= 0 || mult > Long.MAX_VALUE / nodeAmount) {
            return null;
        }
        long inputPerCraft = mult * nodeAmount;
        if (outputPerCraft < inputPerCraft) {
            return null; // 净消耗：逐件记账非线性，回退
        }
        if (outputPerCraft > Long.MAX_VALUE / times) {
            return null; // 产出插入量溢出保护
        }
        // 同键多个自输出输入：消耗累加，且总消耗不得超过产出（否则循环不自持）。
        long[] existing = selfOutputs.get(nodeWhat);
        if (existing == null) {
            selfOutputs.put(nodeWhat, new long[]{outputPerCraft, inputPerCraft});
        } else {
            if (existing[1] > Long.MAX_VALUE - inputPerCraft) {
                return null;
            }
            long totalInputPerCraft = existing[1] + inputPerCraft;
            if (existing[0] < totalInputPerCraft) {
                return null;
            }
            existing[1] = totalInputPerCraft;
        }
        return InputKind.SELF_OUTPUT;
    }

    /** 产出中与 {@code nodeWhat} 相同的总量（饱和到 {@link Long#MAX_VALUE}）。 */
    private static long outputAmountOf(IPatternDetails details, AEKey nodeWhat) {
        long total = 0;
        for (GenericStack output : details.getOutputs()) {
            if (output == null || output.amount() <= 0 || !output.what().equals(nodeWhat)) {
                continue;
            }
            if (total > Long.MAX_VALUE - output.amount()) {
                return Long.MAX_VALUE;
            }
            total += output.amount();
        }
        return total;
    }
}
