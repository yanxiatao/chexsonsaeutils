package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.me.service.CraftingService;

public final class FormalMachineCraftingDispatchService {

    public static void onSubmitJobTail(
            CraftingService craftingService,
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            ICraftingSubmitResult result
    ) {
        // ponytail: removed dispatch feature, stub remains for mixin compatibility
    }

    private FormalMachineCraftingDispatchService() {
    }
}
