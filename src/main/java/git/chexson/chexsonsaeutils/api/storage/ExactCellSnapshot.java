package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 同一修订的不可变单元快照，用于跨终端共享真实库存并避免重复读取。
 * @param identity 底层单元 UUID，用于标识同一贡献，不能表示整个网格
 * @param revision 任意键数量变化都会递增的单元修订，用于缓存失效
 * @param amounts 完整正库存键及有限真值；构造时冻结，不随后续存取改变
 */
public record ExactCellSnapshot(UUID identity, long revision, Map<AEKey, BigInteger> amounts) {
    public ExactCellSnapshot {
        Objects.requireNonNull(identity, "存储身份");
        amounts = Map.copyOf(amounts);
        amounts.forEach((key, value) -> {
            if (value.signum() <= 0) throw new IllegalArgumentException("精确快照仅允许正库存");
        });
    }

    /** 缺失键按零库存读取，避免调用方另设兜底口径。 */
    public BigInteger amount(AEKey key) {
        return amounts.getOrDefault(key, BigInteger.ZERO);
    }
}
