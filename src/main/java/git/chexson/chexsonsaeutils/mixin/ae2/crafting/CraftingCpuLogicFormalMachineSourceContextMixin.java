package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineSourceCpuContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicFormalMachineSourceContextMixin {

    @Redirect(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern("
                            + "Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"
            ),
            remap = false
    )
    private boolean chexsonsaeutils$pushFormalMachinePatternWithSourceContext(
            ICraftingProvider provider,
            IPatternDetails patternDetails,
            KeyCounter[] inputHolder
    ) {
        return FormalMachineSourceCpuContext.withSourceCraftingId(
                chexsonsaeutils$currentCraftingId(),
                () -> provider.pushPattern(patternDetails, inputHolder)
        );
    }

    private @Nullable UUID chexsonsaeutils$currentCraftingId() {
        var job = ((CraftingCpuLogicAccessor) this).getJob();
        if (job == null) {
            return null;
        }
        var accessor = (ExecutingCraftingJobAccessor) job;
        return accessor.getLink() == null ? null : accessor.getLink().getCraftingID();
    }
}
