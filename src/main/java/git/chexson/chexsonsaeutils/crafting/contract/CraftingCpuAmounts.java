package git.chexson.chexsonsaeutils.crafting.contract;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;

/** CPU 监控的只读精确快照；三个分量均为 AEKey 最小单位，不依赖显示模组。 */
public record CraftingCpuAmounts(Map<AEKey, BigInteger> stored, Map<AEKey, BigInteger> active,
        Map<AEKey, BigInteger> pending) {
    public CraftingCpuAmounts {
        stored = Map.copyOf(stored); active = Map.copyOf(active); pending = Map.copyOf(pending);
    }
}
