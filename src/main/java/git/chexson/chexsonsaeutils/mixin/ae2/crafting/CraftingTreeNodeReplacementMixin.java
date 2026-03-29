package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class CraftingTreeNodeReplacementMixin {
    @org.spongepowered.asm.mixin.Unique
    private long chexsonsaeutils$currentRequestedAmount;

    @Mutable
    @Shadow(remap = false)
    @Final
    private AEKey what;

    @Mutable
    @Shadow(remap = false)
    @Final
    private boolean canEmit;

    @Shadow(remap = false)
    private IPatternDetails.IInput parentInput;

    @Shadow(remap = false)
    protected abstract Iterable<InputTemplate> getValidItemTemplates(ICraftingInventory inv);

    @Inject(method = "request", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$captureRequestedAmount(
            appeng.crafting.inv.CraftingSimulationState inv,
            long requestedAmount,
            @org.jetbrains.annotations.Nullable appeng.api.stacks.KeyCounter containerItems,
            CallbackInfo ci
    ) {
        this.chexsonsaeutils$currentRequestedAmount = requestedAmount;
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$preserveSelectedReplacementRepresentative(
            ICraftingService cc,
            appeng.crafting.CraftingCalculation job,
            AEKey requestedWhat,
            long amount,
            appeng.crafting.CraftingTreeProcess parent,
            int slot,
            CallbackInfo ci
    ) {
        if (!ReplacementAwareProcessingPattern.matchesAnyPossibleInput(this.parentInput, requestedWhat)) {
            return;
        }

        GenericStack[] possibleInputs = this.parentInput.getPossibleInputs();
        if (possibleInputs.length == 0 || requestedWhat.matches(possibleInputs[0])) {
            return;
        }

        this.what = requestedWhat;
        this.canEmit = cc.canEmitFor(requestedWhat);
    }

    @Inject(method = "notRecursive", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$blockRecursionForReplacementAwareSubstitutes(
            IPatternDetails details,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(details instanceof ReplacementAwareProcessingPattern)) {
            return;
        }

        for (var input : details.getInputs()) {
            GenericStack[] possibleInputs = input.getPossibleInputs();
            for (int index = 1; index < possibleInputs.length; index++) {
                if (this.what.matches(possibleInputs[index])) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        }
    }

    @Redirect(
            method = "request",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/CraftingTreeNode;getValidItemTemplates(Lappeng/crafting/inv/ICraftingInventory;)Ljava/lang/Iterable;"
            ),
            remap = false
    )
    private Iterable<InputTemplate> chexsonsaeutils$preferRepresentativeTemplatesWhenFullyAvailable(
            CraftingTreeNode instance,
            ICraftingInventory inventory
    ) {
        if (!ReplacementAwareProcessingPattern.matchesAnyPossibleInput(this.parentInput, this.what)) {
            return getValidItemTemplates(inventory);
        }

        for (GenericStack possibleInput : this.parentInput.getPossibleInputs()) {
            if (!this.what.matches(possibleInput)) {
                continue;
            }

            long requiredAmount = possibleInput.amount() * this.chexsonsaeutils$currentRequestedAmount;
            if (inventory.extract(this.what, requiredAmount, Actionable.SIMULATE) < requiredAmount) {
                return getValidItemTemplates(inventory);
            }

            return List.of(new InputTemplate(this.what, possibleInput.amount()));
        }

        return getValidItemTemplates(inventory);
    }
}
