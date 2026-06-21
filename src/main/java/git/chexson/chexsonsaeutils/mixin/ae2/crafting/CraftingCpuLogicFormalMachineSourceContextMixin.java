package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import git.chexson.chexsonsaeutils.config.EnhancedCraftingStatusFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachineCraftingDispatchFeatureGate;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingBlockedTracker;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineSourceCpuContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicFormalMachineSourceContextMixin implements EnhancedCraftingBlockedTracker {

    @Unique
    private final appeng.api.stacks.KeyCounter chexsonsaeutils$blockedTasks = new appeng.api.stacks.KeyCounter();

    @Unique
    private IPatternDetails chexsonsaeutils$currentPatternDetails;

    @Unique
    private boolean chexsonsaeutils$pushedCurrentPattern;

    @Inject(method = "executeCrafting", at = @At("HEAD"), remap = false)
    private void chexsonsaeutils$clearEnhancedStatusBlockedTasks(
            int maxPatterns,
            appeng.me.service.CraftingService craftingService,
            appeng.api.networking.energy.IEnergyService energyService,
            net.minecraft.world.level.Level level,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()) {
            chexsonsaeutils$blockedTasks.reset();
            chexsonsaeutils$currentPatternDetails = null;
            chexsonsaeutils$pushedCurrentPattern = false;
        }
    }

    @ModifyVariable(method = "executeCrafting", at = @At(value = "STORE"), name = "details", remap = false)
    private IPatternDetails chexsonsaeutils$captureEnhancedStatusPattern(IPatternDetails details) {
        chexsonsaeutils$currentPatternDetails = details;
        chexsonsaeutils$pushedCurrentPattern = false;
        return details;
    }

    @Inject(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs("
                            + "Lappeng/api/crafting/IPatternDetails;"
                            + "Lappeng/crafting/inv/ICraftingInventory;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lappeng/api/stacks/KeyCounter;"
                            + "Lappeng/api/stacks/KeyCounter;)"
                            + "[Lappeng/api/stacks/KeyCounter;",
                    ordinal = 1
            ),
            remap = false
    )
    private void chexsonsaeutils$resetEnhancedStatusPushFlagForRepeatedPattern(
            int maxPatterns,
            appeng.me.service.CraftingService craftingService,
            appeng.api.networking.energy.IEnergyService energyService,
            net.minecraft.world.level.Level level,
            CallbackInfoReturnable<Integer> cir
    ) {
        chexsonsaeutils$pushedCurrentPattern = false;
    }

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
        boolean pushed = provider.pushPattern(patternDetails, inputHolder);
        if (pushed && EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()) {
            chexsonsaeutils$pushedCurrentPattern = true;
        }
        return pushed;
    }

    @Inject(
            method = "executeCrafting",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuHelper;reinjectPatternInputs("
                            + "Lappeng/crafting/inv/ICraftingInventory;"
                            + "[Lappeng/api/stacks/KeyCounter;)V"
            ),
            remap = false
    )
    private void chexsonsaeutils$recordEnhancedStatusBlockedPattern(
            int maxPatterns,
            appeng.me.service.CraftingService craftingService,
            appeng.api.networking.energy.IEnergyService energyService,
            net.minecraft.world.level.Level level,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (EnhancedCraftingStatusFeatureGate.isEnabledAtStartup()
                && !chexsonsaeutils$pushedCurrentPattern
                && chexsonsaeutils$currentPatternDetails != null) {
            chexsonsaeutils$recordBlockedPattern(chexsonsaeutils$currentPatternDetails);
        }
    }

    @Override
    public void chexsonsaeutils$clearBlockedTasks() {
        chexsonsaeutils$blockedTasks.reset();
    }

    @Override
    public long chexsonsaeutils$blockedAmount(AEKey what) {
        return chexsonsaeutils$blockedTasks.get(what);
    }

    @Override
    public appeng.api.stacks.KeyCounter chexsonsaeutils$blockedTasks() {
        return chexsonsaeutils$blockedTasks;
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
