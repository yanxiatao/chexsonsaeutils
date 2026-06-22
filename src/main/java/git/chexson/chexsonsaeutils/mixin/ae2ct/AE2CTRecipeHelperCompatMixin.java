package git.chexson.chexsonsaeutils.mixin.ae2ct;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.neuvillette.ae2ct.api.ICraftingPlanSummary;
import com.neuvillette.ae2ct.api.RecipeHelper;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternRecursivePlan;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPattern;
import git.chexson.chexsonsaeutils.crafting.planning.AggregatedCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineAggregationStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(value = CraftingPlanSummary.class, priority = 1200, remap = false)
public abstract class AE2CTRecipeHelperCompatMixin {

    @Inject(
            method = "fromJob",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void chexsonsaeutils$handleAggregatedCraftingPlan(
            IGrid grid,
            IActionSource actionSource,
            ICraftingPlan job,
            CallbackInfoReturnable<CraftingPlanSummary> cir
    ) {
        if (!(job instanceof AggregatedCraftingPlan aggregated)) {
            return;
        }

        List<RecipeHelper.Recipe> recipes = new ArrayList<>();

        for (Map.Entry<IPatternDetails, Long> entry : aggregated.patternTimes().entrySet()) {
            IPatternDetails pattern = entry.getKey();
            Long times = entry.getValue();

            if (pattern instanceof FormalMachineAggregatedPattern aggregatedPattern) {
                recipes.add(new RecipeHelper.Recipe(
                        aggregatedPattern.aggregatedInputs(),
                        aggregatedPattern.aggregatedOutputs(),
                        times
                ));
            } else if (!(pattern instanceof DyeablePatternRecursivePlan)) {
                List<GenericStack> inputs = new ArrayList<>();
                for (var input : pattern.getInputs()) {
                    var possibleInputs = input.getPossibleInputs();
                    var multiplier = input.getMultiplier();
                    if (possibleInputs.length > 0) {
                        var stack = possibleInputs[0];
                        inputs.add(new GenericStack(stack.what(), stack.amount() * multiplier));
                    }
                }
                recipes.add(new RecipeHelper.Recipe(
                        inputs,
                        pattern.getOutputs(),
                        times
                ));
            }
        }

        RecipeHelper helper = new RecipeHelper(aggregated.finalOutput(), recipes);

        var plan = new HashMap<AEKey, long[]>();
        for (var used : job.usedItems()) {
            plan.computeIfAbsent(used.getKey(), k -> new long[2])[0] += used.getLongValue();
        }
        for (var missing : job.missingItems()) {
            plan.computeIfAbsent(missing.getKey(), k -> new long[2])[0] += missing.getLongValue();
        }
        for (var emitted : job.emittedItems()) {
            var entry = plan.computeIfAbsent(emitted.getKey(), k -> new long[2]);
            entry[0] += emitted.getLongValue();
            entry[1] += emitted.getLongValue();
        }
        for (var recipe : recipes) {
            for (var out : recipe.outputs()) {
                plan.computeIfAbsent(out.what(), k -> new long[2])[1] += out.amount() * recipe.times();
            }
        }

        var entries = new ArrayList<CraftingPlanSummaryEntry>();
        var storage = grid.getStorageService().getInventory();
        var crafting = grid.getCraftingService();

        for (var out : plan.entrySet()) {
            long missingAmount;
            long storedAmount;
            if (job.simulation() && !crafting.canEmitFor(out.getKey())) {
                storedAmount = storage.extract(out.getKey(), out.getValue()[0], Actionable.SIMULATE, actionSource);
                missingAmount = out.getValue()[0] - storedAmount;
            } else {
                storedAmount = out.getValue()[0];
                missingAmount = 0;
            }
            long craftAmount = out.getValue()[1];

            entries.add(new CraftingPlanSummaryEntry(
                    out.getKey(),
                    missingAmount,
                    storedAmount,
                    craftAmount));
        }

        Collections.sort(entries);

        CraftingPlanSummary summary = new CraftingPlanSummary(job.bytes(), job.simulation(), List.copyOf(entries));
        ((ICraftingPlanSummary) summary).setJob(helper);

        CraftingStatusEnhancer.attachPatternTimes(summary, job);

        cir.setReturnValue(summary);
    }
}
