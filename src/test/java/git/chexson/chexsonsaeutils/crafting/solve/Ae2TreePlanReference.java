package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeInvoker;
import org.jetbrains.annotations.Nullable;

/**
 * 离线用例用的「AE2 原生树」参照：一条计划都不加，跑的就是 AE2 自己的
 * {@code CraftingTreeNode} + {@code CraftingSimulationState}，只把「服务端每个 tick
 * 给计算线程分一点时间」换成一次给足的泵线程——单测没有真服务端可泵
 * {@code TickHandler}，而生产回落腿要的正是这棵树本身。
 *
 * <p>刻意不放在 {@code appeng.crafting} 包内：那是 ae2 模块已导出的包，同名分包会被
 * JPMS 判成 split package（{@code Modules chexsonsaeutils and ae2 export package
 * appeng.crafting}）而整个测试执行器起不来。所以 AE2 的包私有钩子
 * （{@code handlePausing}/{@code addMissing}）一概留给 AE2 自己，本类只经 mixin 的
 * Accessor/Invoker 触达网络快照与树，缺料账也仍由 AE2 自己的计数器记账。
 *
 * <p>本 mod 的原生树改写（{@code CraftingTreeNodeNativeLimitQtyBatchMixin}）按 job 的
 * <b>确切类型</b>分流，子类不接管 ⇒ 这里读到的是未经我们批量化的 AE2 逐件语义。
 */
// 非 final：mixin 只在运行期把 Accessor/Invoker 接口织进 CraftingCalculation，
// javac 对 final 类会直接判"不兼容的类型"。
public class Ae2TreePlanReference extends CraftingCalculation {

    private final GenericStack output;
    private final CalculationStrategy strategy;
    private boolean simulate;

    public Ae2TreePlanReference(
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        super(null, grid, simRequester, output, strategy);
        this.output = output;
        this.strategy = strategy;
    }

    @Override
    public ICraftingPlan run() {
        // AE2 的树每 101 次 request 才核对一次时间片：泵线程按它自己的 simulateFor
        // 协议一次把预算给足，整棵树于是能在本线程上一路跑完，不需要 TickHandler。
        // 计划算完后泵线程仍停在 monitor.wait——它不带栈、不占载体，测试 JVM 退出即散。
        Thread.ofVirtual().name("ae2-tree-oracle-pump").start(() -> {
            try {
                simulateFor(Integer.MAX_VALUE);
            } catch (RuntimeException ignored) {
                // 泵只管给预算；给不出去就由本类自己失败来报。
            }
        });
        long requestedAmount = this.output.amount();
        ICraftingPlan fullAmountPlan = runCraftAttempt(false, requestedAmount);
        if (fullAmountPlan != null) {
            return fullAmountPlan;
        }
        if (this.strategy == CalculationStrategy.CRAFT_LESS) {
            long successfulAmount = 0L;
            ICraftingPlan successfulPlan = null;
            for (long increment = Long.highestOneBit(requestedAmount); increment > 0L; increment /= 2L) {
                long testAmount = successfulAmount + increment;
                if (testAmount < requestedAmount) {
                    ICraftingPlan plan = runCraftAttempt(false, testAmount);
                    if (plan != null) {
                        successfulAmount = testAmount;
                        successfulPlan = plan;
                    }
                }
            }
            if (successfulPlan != null) {
                return successfulPlan;
            }
        }
        return runCraftAttempt(true, requestedAmount);
    }

    @Nullable
    private ICraftingPlan runCraftAttempt(boolean simulate, long amount) {
        this.simulate = simulate;
        CraftingCalculationAccessor calculation = (CraftingCalculationAccessor) this;
        CraftingTreeNode tree = calculation.chexsonsaeutils$getTree();
        CraftingTreeNodeInvoker invoker = (CraftingTreeNodeInvoker) tree;
        ChildCraftingSimulationState craftingInventory =
                new ChildCraftingSimulationState(calculation.chexsonsaeutils$getNetworkInv());
        craftingInventory.ignore(this.output.what());
        try {
            invoker.chexsonsaeutils$request(craftingInventory, amount, null);
        } catch (CraftBranchFailure failure) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
        craftingInventory.addBytes(invoker.chexsonsaeutils$getNodeCount() * 8);
        return CraftingSimulationState.buildCraftingPlan(craftingInventory, this, amount);
    }

    @Override
    public boolean isSimulation() {
        return this.simulate;
    }

    @Override
    public boolean hasMultiplePaths() {
        return ((CraftingTreeNodeInvoker) ((CraftingCalculationAccessor) this).chexsonsaeutils$getTree())
                .chexsonsaeutils$hasMultiplePaths();
    }
}
