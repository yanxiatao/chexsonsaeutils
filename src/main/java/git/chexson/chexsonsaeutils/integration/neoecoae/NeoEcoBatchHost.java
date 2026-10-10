package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;

import java.util.List;
import java.util.UUID;

public interface NeoEcoBatchHost {

    long chexsonsaeutils$getNeoEcoBatchCapacity(IPatternDetails pattern);

    boolean chexsonsaeutils$pushNeoEcoBatch(
            IPatternDetails pattern,
            List<GenericStack> inputs,
            List<GenericStack> outputs,
            List<GenericStack> remainders,
            UUID craftingJobId,
            long count
    );
}
