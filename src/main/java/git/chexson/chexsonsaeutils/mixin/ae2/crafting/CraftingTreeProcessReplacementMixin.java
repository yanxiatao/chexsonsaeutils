package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import git.chexson.chexsonsaeutils.pattern.replacement.PlanningReplacementSelector;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CraftingTreeProcess.class, remap = false)
public abstract class CraftingTreeProcessReplacementMixin {
    @Shadow(remap = false)
    @Final
    IPatternDetails details;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lappeng/api/networking/crafting/ICraftingService;Lappeng/crafting/CraftingCalculation;Lappeng/api/stacks/AEKey;JLappeng/crafting/CraftingTreeProcess;I)Lappeng/crafting/CraftingTreeNode;"
            ),
            remap = false
    )
    private CraftingTreeNode chexsonsaeutils$seedReplacementAwareChildNode(
            ICraftingService cc,
            CraftingCalculation job,
            AEKey what,
            long amount,
            CraftingTreeProcess parent,
            int slot
    ) {
        if (!(this.details instanceof ReplacementAwareProcessingPattern)) {
            return new CraftingTreeNode(cc, job, what, amount, parent, slot);
        }

        if (slot < 0) {
            return new CraftingTreeNode(cc, job, what, amount, parent, slot);
        }

        IPatternDetails.IInput input = this.details.getInputs()[slot];
        var simulationInventory = ((CraftingCalculationAccessor) job).chexsonsaeutils$getNetworkInv();
        AEKey replacement = PlanningReplacementSelector.selectPlanningStack(
                input,
                null,
                (candidate, requiredAmount) -> simulationInventory.extract(candidate, requiredAmount, Actionable.SIMULATE) >= requiredAmount,
                cc::canEmitFor,
                whatToCraft -> !cc.getCraftingFor(whatToCraft).isEmpty(),
                cc::getFuzzyCraftable
        );
        return new CraftingTreeNode(cc, job, replacement != null ? replacement : what, amount, parent, slot);
    }
}
