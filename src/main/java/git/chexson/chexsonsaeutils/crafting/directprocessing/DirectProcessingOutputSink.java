package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import git.chexson.chexsonsaeutils.crafting.AeCpuIngressRouter;
import git.chexson.chexsonsaeutils.crafting.SourceCpuHandle;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class DirectProcessingOutputSink {

    public List<GenericStack> tryReturnPayload(
            @Nullable IStorageService storageService,
            IActionSource actionSource,
            List<GenericStack> payload,
            @Nullable SourceCpuHandle sourceCpu
    ) {
        return AeCpuIngressRouter.routePayload(
                storageService,
                actionSource,
                payload,
                sourceCpu
        ).remainingPayload();
    }
}
