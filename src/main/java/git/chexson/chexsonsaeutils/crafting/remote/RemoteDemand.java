package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
/** 从原始计划和宽域份数账提取远程中间需求；多来源代理共享输出身份，不重复相加代表值。 */
public final class RemoteDemand {
    private RemoteDemand() {}
    public static Map<AEKey, BigInteger> from(ICraftingPlan plan) {
        var result = new LinkedHashMap<AEKey, BigInteger>();
        var wide = WideAmountPlanCarrier.resolveWideCrafts(plan).entries();
        var patterns = new java.util.LinkedHashSet<>(plan.patternTimes().keySet()); patterns.addAll(wide.keySet());
        for (var pattern : patterns) if (pattern instanceof RemotePattern remote) {
            var times = wide.getOrDefault(pattern, BigInteger.valueOf(plan.patternTimes().getOrDefault(pattern, 0L)));
            if (times.signum() > 0) result.merge(remote.output(), times.multiply(BigInteger.valueOf(remote.getPrimaryOutput().amount())), BigInteger::add);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
