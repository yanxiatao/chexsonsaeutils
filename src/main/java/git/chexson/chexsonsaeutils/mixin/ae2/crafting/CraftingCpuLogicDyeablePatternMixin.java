package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursiveCounterNbt;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursiveTaskOrdering;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicDyeablePatternMixin {

    @Unique
    private static final String CHEXSONSAEUTILS_DYEABLE_RECURSIVE_PLAN = "chexsonsaeutils$dyeableRecursivePlan";

    @Unique
    private static final String CHEXSONSAEUTILS_DYEABLE_RECURSIVE_FINAL_AMOUNT =
            "chexsonsaeutils$dyeableRecursiveFinalAmount";

    @Unique
    private static final String CHEXSONSAEUTILS_DYEABLE_RECURSIVE_INTERNAL_ITEMS =
            "chexsonsaeutils$dyeableRecursiveInternalItems";

    @Shadow
    private ExecutingCraftingJob job;

    @Shadow
    @Final
    private ListCraftingInventory inventory;

    @Shadow
    @Final
    private CraftingCPUCluster cluster;

    @Unique
    private boolean chexsonsaeutils$submittedDyeableRecursivePlan;

    @Unique
    private boolean chexsonsaeutils$dyeableRecursivePlan;

    @Unique
    private long chexsonsaeutils$dyeableRecursiveFinalOutputAmount = -1L;

    @Unique
    private long chexsonsaeutils$submittedDyeableRecursiveFinalOutputAmount = -1L;

    @Unique
    private KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems = new KeyCounter();

    @Unique
    private KeyCounter chexsonsaeutils$submittedDyeableRecursiveInternalItems = new KeyCounter();

    @Unique
    private IPatternDetails chexsonsaeutils$currentDyeableRecursivePattern;

    @Inject(method = "trySubmitJob", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$captureDyeableRecursivePlan(
            appeng.api.networking.IGrid grid,
            ICraftingPlan plan,
            appeng.api.networking.security.IActionSource src,
            appeng.api.networking.crafting.ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        if (plan instanceof DyeablePatternRecursivePlan recursivePlan
                && recursivePlan.chexsonsaeutils$usesDyeableRecursivePlanning()) {
            this.chexsonsaeutils$submittedDyeableRecursivePlan = true;
            this.chexsonsaeutils$submittedDyeableRecursiveFinalOutputAmount =
                    recursivePlan.chexsonsaeutils$dyeableRecursiveFinalOutputAmount();
            this.chexsonsaeutils$submittedDyeableRecursiveInternalItems =
                    recursivePlan.chexsonsaeutils$dyeableRecursiveInternalItems();
            return;
        }

        this.chexsonsaeutils$submittedDyeableRecursivePlan = false;
        this.chexsonsaeutils$submittedDyeableRecursiveFinalOutputAmount = -1L;
        this.chexsonsaeutils$submittedDyeableRecursiveInternalItems = new KeyCounter();
    }

    @Inject(method = "trySubmitJob", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$bindDyeableRecursivePlanAfterSubmit(
            appeng.api.networking.IGrid grid,
            ICraftingPlan plan,
            appeng.api.networking.security.IActionSource src,
            appeng.api.networking.crafting.ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        ICraftingSubmitResult result = cir.getReturnValue();
        if (result != null && result.successful()) {
            this.chexsonsaeutils$dyeableRecursivePlan = this.chexsonsaeutils$submittedDyeableRecursivePlan;
            this.chexsonsaeutils$dyeableRecursiveFinalOutputAmount =
                    this.chexsonsaeutils$submittedDyeableRecursiveFinalOutputAmount;
            this.chexsonsaeutils$dyeableRecursiveInternalItems =
                    copyCounter(this.chexsonsaeutils$submittedDyeableRecursiveInternalItems);
            if (this.chexsonsaeutils$dyeableRecursivePlan && this.job != null) {
                DyeablePatternRecursiveTaskOrdering.reorderMixinTaskMap(
                        ((ExecutingCraftingJobAccessor) this.job).getTasks(),
                        this.chexsonsaeutils$dyeableRecursiveInternalItems
                );
            }
        }
        this.chexsonsaeutils$submittedDyeableRecursivePlan = false;
        this.chexsonsaeutils$submittedDyeableRecursiveFinalOutputAmount = -1L;
        this.chexsonsaeutils$submittedDyeableRecursiveInternalItems = new KeyCounter();
    }

    @ModifyVariable(method = "executeCrafting", at = @At(value = "STORE"), name = "details", remap = false)
    private IPatternDetails chexsonsaeutils$captureDyeableRecursiveCurrentPattern(IPatternDetails details) {
        this.chexsonsaeutils$currentDyeableRecursivePattern = details;
        return details;
    }

    @Redirect(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs("
                            + "Lappeng/api/crafting/IPatternDetails;"
                            + "Lappeng/crafting/inv/ICraftingInventory;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lappeng/api/stacks/KeyCounter;"
                            + "Lappeng/api/stacks/KeyCounter;)"
                            + "[Lappeng/api/stacks/KeyCounter;"
            ),
            remap = false
    )
    private KeyCounter[] chexsonsaeutils$deferRecursiveInternalConsumer(
            IPatternDetails details,
            ICraftingInventory sourceInv,
            net.minecraft.world.level.Level level,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        IPatternDetails pattern = this.chexsonsaeutils$currentDyeableRecursivePattern == null
                ? details
                : this.chexsonsaeutils$currentDyeableRecursivePattern;
        if (this.chexsonsaeutils$dyeableRecursivePlan
                && DyeablePatternRecursiveTaskOrdering.hasPendingProducer(
                        ((ExecutingCraftingJobAccessor) this.job).getTasks(),
                        this.chexsonsaeutils$dyeableRecursiveInternalItems
                )
                && DyeablePatternRecursiveTaskOrdering.shouldDeferConsumer(
                        pattern,
                        this.chexsonsaeutils$dyeableRecursiveInternalItems,
                        sourceInv,
                        ((ExecutingCraftingJobAccessor) this.job).getTasks()
                )) {
            return null;
        }
        return CraftingCpuHelper.extractPatternInputs(
                details,
                sourceInv,
                level,
                expectedOutputs,
                expectedContainerItems
        );
    }

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$insertDyeableRecursiveIntermediateOutput(
            AEKey what,
            long amount,
            Actionable type,
            CallbackInfoReturnable<Long> cir
    ) {
        if (!this.chexsonsaeutils$shouldHandleRecursiveIntermediateOutput(what, amount)) {
            return;
        }

        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) this.job;
        long waitingFor = jobAccessor.getWaitingFor().extract(what, amount, Actionable.SIMULATE);
        if (waitingFor <= 0L) {
            cir.setReturnValue(0L);
            return;
        }
        long accepted = Math.min(amount, waitingFor);
        if (type == Actionable.MODULATE) {
            ((ElapsedTimeTrackerAccessor) jobAccessor.getTimeTracker()).invokeDecrementItems(
                    accepted,
                    what.getType()
            );
            jobAccessor.getWaitingFor().extract(what, accepted, Actionable.MODULATE);
            this.inventory.insert(what, accepted, Actionable.MODULATE);
            this.cluster.markDirty();
            this.chexsonsaeutils$updateRecursiveDisplayedOutput(jobAccessor);
            chexsonsaeutils$finishRecursiveJobIfComplete();
        }
        cir.setReturnValue(accepted);
    }

    @Inject(method = "finishJob", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$flushDyeableRecursiveFinalOutput(boolean success, CallbackInfo ci) {
        if (!success || !this.chexsonsaeutils$dyeableRecursivePlan || this.job == null) {
            return;
        }

        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) this.job;
        if (jobAccessor.getFinalOutput() == null
                || jobAccessor.getFinalOutput().what() == null) {
            return;
        }

        AEKey finalKey = jobAccessor.getFinalOutput().what();
        long amountToFlush = chexsonsaeutils$finalOutputAmountToFlush(jobAccessor);
        if (amountToFlush <= 0L) {
            return;
        }

        long available = this.inventory.extract(finalKey, amountToFlush, Actionable.SIMULATE);
        if (available <= 0L) {
            return;
        }

        long accepted = jobAccessor.getLink().insert(finalKey, available, Actionable.MODULATE);
        if (accepted > 0L) {
            this.inventory.extract(finalKey, accepted, Actionable.MODULATE);
        }
    }

    @Inject(method = "finishJob", at = @At("TAIL"), remap = false)
    private void chexsonsaeutils$clearDyeableRecursivePlan(boolean success, CallbackInfo ci) {
        this.chexsonsaeutils$dyeableRecursivePlan = false;
        this.chexsonsaeutils$submittedDyeableRecursivePlan = false;
        this.chexsonsaeutils$dyeableRecursiveFinalOutputAmount = -1L;
        this.chexsonsaeutils$submittedDyeableRecursiveFinalOutputAmount = -1L;
        this.chexsonsaeutils$dyeableRecursiveInternalItems = new KeyCounter();
        this.chexsonsaeutils$submittedDyeableRecursiveInternalItems = new KeyCounter();
        this.chexsonsaeutils$currentDyeableRecursivePattern = null;
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$readDyeableRecursiveState(CompoundTag data, CallbackInfo ci) {
        this.chexsonsaeutils$dyeableRecursivePlan =
                this.job != null && data.getBoolean(CHEXSONSAEUTILS_DYEABLE_RECURSIVE_PLAN);
        this.chexsonsaeutils$dyeableRecursiveFinalOutputAmount = this.chexsonsaeutils$dyeableRecursivePlan
                ? data.getLong(CHEXSONSAEUTILS_DYEABLE_RECURSIVE_FINAL_AMOUNT)
                : -1L;
        this.chexsonsaeutils$dyeableRecursiveInternalItems = this.chexsonsaeutils$dyeableRecursivePlan
                ? DyeablePatternRecursiveCounterNbt.read(
                        data.getList(CHEXSONSAEUTILS_DYEABLE_RECURSIVE_INTERNAL_ITEMS, Tag.TAG_COMPOUND)
                )
                : new KeyCounter();
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = false)
    private void chexsonsaeutils$writeDyeableRecursiveState(CompoundTag data, CallbackInfo ci) {
        if (this.job == null || !this.chexsonsaeutils$dyeableRecursivePlan) {
            return;
        }

        data.putBoolean(CHEXSONSAEUTILS_DYEABLE_RECURSIVE_PLAN, true);
        data.putLong(
                CHEXSONSAEUTILS_DYEABLE_RECURSIVE_FINAL_AMOUNT,
                this.chexsonsaeutils$dyeableRecursiveFinalOutputAmount
        );
        data.put(
                CHEXSONSAEUTILS_DYEABLE_RECURSIVE_INTERNAL_ITEMS,
                DyeablePatternRecursiveCounterNbt.write(this.chexsonsaeutils$dyeableRecursiveInternalItems)
        );
    }

    @Unique
    private long chexsonsaeutils$finalOutputAmountToFlush(ExecutingCraftingJobAccessor jobAccessor) {
        if (this.chexsonsaeutils$dyeableRecursiveFinalOutputAmount > 0L) {
            return this.chexsonsaeutils$dyeableRecursiveFinalOutputAmount;
        }

        return jobAccessor.getFinalOutput() == null
                ? 0L
                : jobAccessor.getFinalOutput().amount();
    }

    @Unique
    private boolean chexsonsaeutils$shouldHandleRecursiveIntermediateOutput(AEKey what, long amount) {
        if (!this.chexsonsaeutils$dyeableRecursivePlan || what == null || amount <= 0L || this.job == null) {
            return false;
        }
        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) this.job;
        return chexsonsaeutils$isRecursiveInternalItem(what)
                || jobAccessor.getFinalOutput() != null
                        && what.matches(jobAccessor.getFinalOutput());
    }

    @Unique
    private boolean chexsonsaeutils$recursiveJobReadyToFinish(ExecutingCraftingJobAccessor jobAccessor) {
        return !DyeablePatternRecursiveTaskOrdering.hasPendingTasks(jobAccessor.getTasks())
                && jobAccessor.getWaitingFor().list.isEmpty();
    }

    @Unique
    private void chexsonsaeutils$finishRecursiveJobIfComplete() {
        if (!this.chexsonsaeutils$dyeableRecursivePlan || this.job == null) {
            return;
        }
        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) this.job;
        if (!chexsonsaeutils$recursiveJobReadyToFinish(jobAccessor)) {
            return;
        }
        jobAccessor.setRemainingAmount(0L);
        this.cluster.updateOutput((GenericStack) null);
        ((CraftingCpuLogicAccessor) this).invokeFinishJob(true);
    }

    @Unique
    private void chexsonsaeutils$updateRecursiveDisplayedOutput(ExecutingCraftingJobAccessor jobAccessor) {
        GenericStack finalOutput = jobAccessor.getFinalOutput();
        if (finalOutput == null || finalOutput.what() == null) {
            return;
        }
        long available = this.inventory.extract(finalOutput.what(), Long.MAX_VALUE, Actionable.SIMULATE);
        long amountToFlush = chexsonsaeutils$finalOutputAmountToFlush(jobAccessor);
        long remaining = Math.max(0L, amountToFlush - available);
        jobAccessor.setRemainingAmount(remaining);
        if (remaining <= 0L) {
            this.cluster.updateOutput((GenericStack) null);
        } else {
            this.cluster.updateOutput(new GenericStack(finalOutput.what(), remaining));
        }
    }

    @Unique
    private boolean chexsonsaeutils$isRecursiveInternalItem(AEKey what) {
        for (var entry : this.chexsonsaeutils$dyeableRecursiveInternalItems) {
            if (entry.getKey() != null && entry.getLongValue() > 0L && what.equals(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static KeyCounter copyCounter(KeyCounter source) {
        KeyCounter copy = new KeyCounter();
        if (source != null) {
            copy.addAll(source);
        }
        return copy;
    }
}
