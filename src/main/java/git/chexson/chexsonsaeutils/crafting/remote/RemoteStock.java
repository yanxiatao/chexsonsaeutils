package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.networking.crafting.ICraftingPlan;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import java.util.HashMap;
import java.util.Map;

/** 同一真实来源网络的共享模拟库存，宽域扣账不重新读取整张真实盘。 */
public final class RemoteStock {
    private final KeyCounter stock = new KeyCounter();
    private PlanningSupplyTruth truth;
    public RemoteStock(KeyCounter original, PlanningSupplyTruth truth) { stock.addAll(original); this.truth = truth; }
    public KeyCounter snapshot() { var copy = new KeyCounter(); copy.addAll(stock); return copy; }
    public PlanningSupplyTruth truth() { return truth; }
    public void reserve(ICraftingPlan plan) {
        var used = new HashMap<AEKey, PlanAmount>();
        var wide = WideAmountPlanCarrier.resolveWideLedgers(plan).entries(WideAmountKind.USED);
        plan.usedItems().forEach(e -> used.put(e.getKey(), PlanAmount.of(wide.getOrDefault(e.getKey(), java.math.BigInteger.valueOf(e.getLongValue())))));
        wide.forEach((key, amount) -> used.put(key, PlanAmount.of(amount)));
        reserve(used);
    }
    /** 按实际计划的精确 used 占用；缺料计划不应调用此入口。 */
    public void reserve(Map<AEKey, PlanAmount> used) {
        var remaining = new HashMap<AEKey, PlanAmount>();
        used.forEach((key, amount) -> {
            if (truth.unboundedSupplies().contains(key)) return;
            var total = truth.supplyOf(key, stock.get(key));
            if (amount.compareTo(PlanAmount.ZERO) < 0 || total.compareTo(amount) < 0) throw new IllegalStateException("来源共享预览库存不足：" + key);
            var next = total.subtract(amount); remaining.put(key, next);
        });
        remaining.forEach((key, amount) -> stock.set(key, amount.exactValue().min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValueExact()));
        truth = truth.withRemaining(remaining);
    }
}
