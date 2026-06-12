package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.crafting.IPatternDetails;

public interface IFormalMachineCraftingProvider {

    boolean supportsFastBatch(IPatternDetails patternDetails);

    boolean canAcceptBatchKey(FormalMachineBatchKey batchKey);

    int getDispatchBackpressure();

    int getDispatchOperationTicks();

    String getMachineIdentity();

    FormalMachineFastPathResult offerFastBatch(FormalMachineBatchRequest request);
}
