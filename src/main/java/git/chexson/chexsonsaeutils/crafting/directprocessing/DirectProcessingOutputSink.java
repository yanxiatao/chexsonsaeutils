package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.networking.storage.IStorageService;
import appeng.me.helpers.MachineSource;
import appeng.me.service.CraftingService;
import git.chexson.chexsonsaeutils.crafting.AeCpuIngressRouter;
import appeng.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class DirectProcessingOutputSink {

    public List<GenericStack> tryReturnPayload(
            @Nullable CraftingService craftingService,
            @Nullable IStorageService storageService,
            MachineSource actionSource,
            List<GenericStack> payload
    ) {
        return AeCpuIngressRouter.routePayload(
                craftingService,
                storageService,
                actionSource,
                payload,
                null
        ).remainingPayload();
    }
}
