package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import java.math.BigInteger;
import java.util.*;

/** 合成预览的精确显示口径：库存加直接发出量，缺料独立，合成量加全部样板产出。 */
public record PlanSummaryAmounts(Map<AEKey, BigInteger> stored, Map<AEKey, BigInteger> missing,
        Map<AEKey, BigInteger> crafting) {
    public PlanSummaryAmounts {
        stored = Map.copyOf(stored); missing = Map.copyOf(missing); crafting = Map.copyOf(crafting);
    }
    /** 从原计划和宽域侧账取值，禁止把 long 饱和值作为合计输入。 */
    public static PlanSummaryAmounts fromPlan(ICraftingPlan plan) {
        if (plan == null) return new PlanSummaryAmounts(Map.of(), Map.of(), Map.of());
        var wide = WideAmountPlanCarrier.resolveWideLedgers(plan);
        var stored = read(plan.usedItems(), wide.entries(WideAmountKind.USED));
        var missing = read(plan.missingItems(), wide.entries(WideAmountKind.MISSING));
        var emitted = read(plan.emittedItems(), wide.entries(WideAmountKind.EMIT));
        emitted.forEach((key, amount) -> stored.merge(key, amount, BigInteger::add));
        var crafting = new HashMap<>(emitted);
        var times = new HashMap<>(WideAmountPlanCarrier.resolveWideCrafts(plan).entries());
        plan.patternTimes().forEach((pattern, count) -> times.putIfAbsent(pattern, BigInteger.valueOf(count)));
        times.forEach((pattern, count) -> {
            for (var output : pattern.getOutputs())
                crafting.merge(output.what(), count.multiply(BigInteger.valueOf(output.amount())), BigInteger::add);
        });
        return new PlanSummaryAmounts(stored, missing, crafting);
    }
    private static HashMap<AEKey, BigInteger> read(KeyCounter narrow, Map<AEKey, BigInteger> wide) {
        var result = new HashMap<>(wide);
        for (var entry : narrow) result.putIfAbsent(entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
        return result;
    }
    /** 沿用既有三分量同步协议，但载荷为预览合计，避免把单类账误当行总量。 */
    public WideLedgerSnapshot displaySnapshot() {
        return WideLedgerSnapshot.capture(counter(missing), counter(stored), counter(crafting));
    }
    private static WideKeyCounter counter(Map<AEKey, BigInteger> amounts) {
        var result = new WideKeyCounter(); amounts.forEach(result::add); return result;
    }
    /** NeoEco 可原地替换的原生条目，long 只作为排序与可见性投影。 */
    public List<CraftingPlanSummaryEntry> entries() {
        var keys = new HashSet<>(stored.keySet()); keys.addAll(missing.keySet()); keys.addAll(crafting.keySet());
        var entries = new ArrayList<CraftingPlanSummaryEntry>();
        for (var key : keys) entries.add(new CraftingPlanSummaryEntry(key, narrow(missing.get(key)),
                narrow(stored.get(key)), narrow(crafting.get(key))));
        Collections.sort(entries);
        return List.copyOf(entries);
    }
    private static long narrow(BigInteger amount) {
        return amount == null ? 0 : Ae2Amounts.toLong(PlanAmount.of(amount));
    }
}
