package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOExactInventory;
import java.math.BigInteger;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

/** 先经原生接收口完成任务校验和调度通知，再转移同键等待账的精确余量。 */
public final class NeoEcoExactOutputTransfer {
    private NeoEcoExactOutputTransfer() {}
    /** 取消后按网络实收退还精确库存；未接收部分继续留在原 NeoEco 持久化池。 */
    public static void returnInventory(ECOExactInventory inventory, appeng.api.storage.MEStorage storage,
            appeng.api.networking.security.IActionSource source) {
        for (var entry : inventory.snapshot().entrySet()) {
            BigInteger accepted = git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion.insert(
                    storage, entry.getKey(), entry.getValue(), appeng.api.config.Actionable.MODULATE, source);
            if (accepted.signum() > 0 && !inventory.debit(Map.of(entry.getKey(), accepted)))
                throw new IllegalStateException("NeoEco 退网期间库存账改变");
        }
    }

    public static BigInteger receive(ECOExactInventory waiting, ECOExactInventory inventory,
            AEKey key, BigInteger available, LongUnaryOperator originalInsert,
            Consumer<BigInteger> completed) {
        if (!waiting.isEnabled() || !inventory.isEnabled() || available.signum() <= 0) return BigInteger.ZERO;
        BigInteger offered = available.min(waiting.amount(key));
        if (offered.signum() == 0) return BigInteger.ZERO;
        long window = offered.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        long inserted = originalInsert.applyAsLong(window);
        if (inserted < 0 || inserted > window) throw new IllegalStateException("NeoEco 原生接收量越界");
        BigInteger accepted = BigInteger.valueOf(inserted);
        if (inserted < window) return accepted;
        BigInteger rest = offered.subtract(accepted).min(waiting.amount(key));
        if (rest.signum() > 0) {
            var transfer = Map.of(key, rest);
            if (!waiting.debit(transfer)) throw new IllegalStateException("NeoEco 等待账在接收期间改变");
            inventory.restore(transfer);
            completed.accept(rest);
            accepted = accepted.add(rest);
        }
        return accepted;
    }
}
