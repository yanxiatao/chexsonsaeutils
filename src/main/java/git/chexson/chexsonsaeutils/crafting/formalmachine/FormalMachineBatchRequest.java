package git.chexson.chexsonsaeutils.crafting.formalmachine;

import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record FormalMachineBatchRequest(
        CompiledTask compiledTask,
        FormalMachineBatchKey batchKey,
        int logicalExecutions,
        @Nullable UUID jobId,
        String sourceCpuId,
        @Nullable UUID jobLinkId,
        int requestedTickBudget
) {
}
