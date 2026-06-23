package git.chexson.chexsonsaeutils.network.directprocessing;

import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigImportRequest;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.function.Function;

// ponytail: data class only. Network layer disabled - Forge 1.20.1 uses SimpleChannel not CustomPacketPayload.
public record DirectProcessingJeiImportPayload(
        int containerId,
        MachineRecipeConfigImportRequest request
) {
    static boolean tryApplyToMenu(AbstractContainerMenu currentMenu, DirectProcessingJeiImportPayload payload) {
        if (payload == null || payload.request() == null) {
            return false;
        }
        if (!(currentMenu instanceof AEDirectProcessingMachineMenu menu)) {
            return false;
        }
        return tryApplyToTarget(menu.getContainerIdForPayload(), payload, menu::applyJeiImportRequestFromClient);
    }

    static boolean tryApplyToTarget(
            int currentContainerId,
            DirectProcessingJeiImportPayload payload,
            Function<MachineRecipeConfigImportRequest, Boolean> applier
    ) {
        if (payload == null || payload.request() == null || applier == null) {
            return false;
        }
        if (currentContainerId != payload.containerId()) {
            return false;
        }
        return Boolean.TRUE.equals(applier.apply(payload.request()));
    }
}
