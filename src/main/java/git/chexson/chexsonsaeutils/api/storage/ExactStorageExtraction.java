package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import java.math.BigInteger;

/** 实物库存的精确提取能力；无限容量仍须扣除真实库存，不代表无限供应。 */
public interface ExactStorageExtraction {
    /** 返回实际提取量；模拟不得改变库存，来源权限及过滤由实现保留。 */
    BigInteger extractExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source);

    /** 精确库存一次交完整请求；只有 long API 的库存只执行一个窗口，不循环搬空。 */
    static BigInteger extract(MEStorage storage, AEKey what, BigInteger amount,
            Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储提取量不得为负");
        if (amount.signum() == 0) return BigInteger.ZERO;
        BigInteger extracted = storage instanceof ExactStorageExtraction exact
                ? exact.extractExact(what, amount, mode, source)
                : BigInteger.valueOf(storage.extract(what,
                        amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(), mode, source));
        if (extracted == null || extracted.signum() < 0 || extracted.compareTo(amount) > 0)
            throw new IllegalStateException("存储实取量越界：" + extracted + " / " + amount);
        return extracted;
    }

    /** 按 AE2 提取优先级遍历挂载一次，跳过待卸载盘；请求余量全程保持精确值。 */
    static BigInteger extractFromMounts(Iterable<? extends Iterable<MEStorage>> mounts,
            java.util.function.Predicate<MEStorage> removed, MountedStorageExtraction policy,
            AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        BigInteger remaining = amount;
        for (var group : mounts) {
            for (MEStorage storage : group) {
                if (remaining.signum() == 0) return amount;
                if (removed.test(storage)) continue;
                BigInteger got = policy == null ? extract(storage, what, remaining, mode, source)
                        : policy.extractMountedExact(storage, what, remaining, mode, source);
                if (got == null || got.signum() < 0 || got.compareTo(remaining) > 0)
                    throw new IllegalStateException("挂载实取量越界：" + got + " / " + remaining);
                remaining = remaining.subtract(got);
            }
        }
        return amount.subtract(remaining);
    }
}
