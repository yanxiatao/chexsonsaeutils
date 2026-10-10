package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import java.math.BigInteger;

/** 存储接收精确实物的可选能力；普通 AE2 库存每次接收一个 long 窗口。 */
public interface ExactStorageInsertion {
    /** 返回实际接收量；模拟不得写库存，权限、过滤和优先级由存储实现负责。 */
    BigInteger insertExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source);

    /** 对具备能力的库存交出完整数量，否则只交一个窗口；调用方按实收扣原缓冲。 */
    static BigInteger insert(MEStorage storage, AEKey what, BigInteger amount,
            Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储插入量不得为负");
        if (amount.signum() == 0) return BigInteger.ZERO;
        BigInteger accepted = storage instanceof ExactStorageInsertion exact
                ? exact.insertExact(what, amount, mode, source)
                : BigInteger.valueOf(storage.insert(what,
                        amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(), mode, source));
        if (accepted == null || accepted.signum() < 0 || accepted.compareTo(amount) > 0)
            throw new IllegalStateException("存储实收量越界：" + accepted + " / " + amount);
        return accepted;
    }

    /** 沿网络原有优先级及优先存储两遍路由，一次交完整余量；普通库存只处理一个窗口。 */
    static BigInteger insertIntoMounts(Iterable<? extends Iterable<MEStorage>> mounts,
            java.util.function.Predicate<MEStorage> removed, AEKey what, BigInteger amount,
            Actionable mode, IActionSource source) {
        BigInteger remaining = amount;
        var secondPass = new java.util.ArrayList<MEStorage>();
        for (var group : mounts) {
            secondPass.clear();
            for (MEStorage storage : group) {
                if (remaining.signum() == 0) return amount;
                if (removed.test(storage)) continue;
                if (storage.isPreferredStorageFor(what, source))
                    remaining = remaining.subtract(insert(storage, what, remaining, mode, source));
                else secondPass.add(storage);
            }
            for (MEStorage storage : secondPass) {
                if (remaining.signum() == 0) return amount;
                if (!removed.test(storage))
                    remaining = remaining.subtract(insert(storage, what, remaining, mode, source));
            }
        }
        return amount.subtract(remaining);
    }
}
