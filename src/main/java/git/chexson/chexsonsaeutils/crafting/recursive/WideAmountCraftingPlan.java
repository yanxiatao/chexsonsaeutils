package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideCraftSnapshot;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import java.util.Map;
import java.util.Objects;

/**
 * 非环图计划的<b>透明</b>宽域载体：只有计划真的含溢出键时才套上，用来把 {@link WideLedgerSnapshot}
 * 带在计划对象上。
 *
 * <p>为什么需要它：{@code Ae2PlanConverter.toPlan} 的无环分支（{@code cycleKind == NONE}）同样
 * 从宽域账本出账，产出的 {@code appeng.crafting.CraftingPlan} 一样被 {@code Ae2Amounts.toLong}
 * 饱和成 {@code Long.MAX_VALUE}——实机形态 {@code ae2:creative_storage_cell x 1000000} 的
 * lava 1.6e19 缺料就是这条分支（无环、纯放大）。AE2 的 {@code CraftingPlan} 是它的 record，
 * 我们不能给它加字段，也不为它开第三个 mixin，因此在出口附带精确账本与根请求量。
 *
 * <p><b>刻意不实现 {@link RecursivePlanContract}</b>：递归语义的判据在全部消费点都是
 * {@code instanceof RecursivePlanContract}（CPU 任务重排、催化物延迟消费、useless 折叠短路、
 * 计划摘要剥壳……）。这层壳不启用递归执行语义；精确根请求由并行 CPU 使用，AE2 侧只按 {@link ICraftingPlan}
 * 接口使用计划（AE2 全仓无 {@code instanceof CraftingPlan}），八个方法逐字转发同一份实例。
 *
 * <p>纯内存对象，不参与任何 NBT 与存档。
 */
public record WideAmountCraftingPlan(
        CraftingPlan delegate,
        WideLedgerSnapshot wideLedgers,
        WideCraftSnapshot wideCrafts,
        java.util.Set<AEKey> unboundedKeys,
        git.chexson.chexsonsaeutils.bigint.PlanAmount exactFinalOutput) implements ICraftingPlan, WideAmountPlanCarrier {

    public WideAmountCraftingPlan(CraftingPlan delegate, WideLedgerSnapshot wideLedgers,
            WideCraftSnapshot wideCrafts, java.util.Set<AEKey> unboundedKeys) {
        this(delegate, wideLedgers, wideCrafts, unboundedKeys, null);
    }

    @Override public git.chexson.chexsonsaeutils.bigint.PlanAmount chexsonsaeutils$exactFinalOutput() {
        return exactFinalOutput;
    }

    public WideAmountCraftingPlan {
        Objects.requireNonNull(delegate, "delegate");
        wideLedgers = wideLedgers == null ? WideLedgerSnapshot.empty() : wideLedgers;
        wideCrafts = wideCrafts == null ? WideCraftSnapshot.empty() : wideCrafts;
        unboundedKeys = unboundedKeys == null ? java.util.Set.of() : java.util.Set.copyOf(unboundedKeys);
    }

    @Override
    public WideLedgerSnapshot chexsonsaeutils$wideLedgers() {
        return wideLedgers;
    }

    @Override
    public WideCraftSnapshot chexsonsaeutils$wideCrafts() {
        return wideCrafts;
    }

    @Override
    public java.util.Set<AEKey> chexsonsaeutils$unboundedKeys() {
        return unboundedKeys;
    }

    @Override
    public GenericStack finalOutput() {
        return delegate.finalOutput();
    }

    @Override
    public long bytes() {
        return delegate.bytes();
    }

    @Override
    public boolean simulation() {
        return delegate.simulation();
    }

    @Override
    public boolean multiplePaths() {
        return delegate.multiplePaths();
    }

    @Override
    public KeyCounter usedItems() {
        return delegate.usedItems();
    }

    @Override
    public KeyCounter emittedItems() {
        return delegate.emittedItems();
    }

    @Override
    public KeyCounter missingItems() {
        return delegate.missingItems();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return delegate.patternTimes();
    }

    /** 精确根请求参与值语义，避免两个不同的大数订单因 long 投影相同而混淆。 */
    @Override
    public boolean equals(Object other) {
        return other instanceof WideAmountCraftingPlan plan && delegate.equals(plan.delegate)
                && Objects.equals(exactFinalOutput, plan.exactFinalOutput);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate, exactFinalOutput);
    }

    @Override
    public String toString() {
        return "WideAmountCraftingPlan[" + delegate.finalOutput() + ", wideLedgers=" + wideLedgers
                + ", wideCrafts=" + wideCrafts + "]";
    }
}
