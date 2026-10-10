package git.chexson.chexsonsaeutils.integration.hostbatch;

import appeng.api.networking.IGrid;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchTerminalDrops;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerReceipt;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilities;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilitiesFactory;
import java.util.function.Supplier;

/** 宿主能力会话的装配实现；每次创建都给产线独立的适配身份缓存。 */
public final class ProviderBatchCapabilitiesFactoryImpl implements ProviderBatchCapabilitiesFactory {
    @Override
    public ProviderBatchCapabilities create(Supplier<IGrid> grid) {
        return new ProviderBatchCapabilitiesImpl(grid);
    }
    @Override
    public DeclaredBatchTerminalDrops terminalDrops() {
        return new DeclaredBatchTerminalDrops(UselessBigIntegerReceipt.terminalDroppedNoLedger(),
                UselessBigIntegerReceipt.terminalDroppedIdMismatch());
    }
}
