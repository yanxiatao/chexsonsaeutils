package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/** 从实际网络挂载读取精确供给，供规划器使用，避免按饱和 long 推测库存。 */
public interface ExactNetworkSupply {
    /** 一次采集形成完整快照；无限供给与有限库存分开保存，读取不得写库存。 */
    Snapshot readExactSupply();

    /** 有限量是实际库存总和；无限键表示真正无限供给，不代表盘容量无限。 */
    record Snapshot(Map<AEKey, BigInteger> finite, Set<AEKey> unbounded) {
        public Snapshot {
            finite = Map.copyOf(finite);
            unbounded = Set.copyOf(unbounded);
            if (finite.values().stream().anyMatch(amount -> amount.signum() < 0))
                throw new IllegalArgumentException("网络库存不得为负");
        }
    }
}
