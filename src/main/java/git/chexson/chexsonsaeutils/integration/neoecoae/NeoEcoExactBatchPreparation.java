package git.chexson.chexsonsaeutils.integration.neoecoae;

import cn.dancingsnow.neoecoae.api.me.provider.ECOBatchDispatchContext;
import cn.dancingsnow.neoecoae.api.me.provider.ECOFastPathDispatchProvider;
import java.math.BigInteger;
import org.jetbrains.annotations.Nullable;

/** 精确准备阶段只申报份数，真实产物写入发生在 NeoEco 扣料事务调用派发时。 */
public final class NeoEcoExactBatchPreparation {
    private NeoEcoExactBatchPreparation() {}
    public static @Nullable ECOFastPathDispatchProvider.ExactPreparation prepare(
            NeoEcoBatchHost host, ECOBatchDispatchContext context, BigInteger requested) {
        if (!(host instanceof NeoEcoExactBatchHost exact) || requested.signum() <= 0
                || exact.chexsonsaeutils$getNeoEcoBatchCapacity(context.pattern()) <= 0L) return null;
        return new ECOFastPathDispatchProvider.ExactPreparation(requested,
                () -> exact.chexsonsaeutils$pushNeoEcoExactBatch(context.pattern(), context.inputItems(),
                        context.outputs(), context.containerItems(), context.craftingJobId(), requested));
    }
}
