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
import git.chexson.chexsonsaeutils.crafting.recursive.RecursivePlanContract;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPattern;
import git.chexson.chexsonsaeutils.crafting.status.CraftingStatusEnhancer;
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
        // 门槛是「这条计划 AE2 与 ae2ct 认不认得」，不是「含不含聚合样板」：
        // ae2ct 在 fromJob 的 TAIL 上无条件把 ICraftingPlan 强转成 appeng.crafting.CraftingPlan，
        // 我们的 RecursiveCraftingPlan / WideAmountCraftingPlan 都不是它的子类——
        // 让它跑下去要么 CCE 被 AE2 吞成「Failed to start crafting job」（预览根本出不来），
        // 要么留下一个没填 helper 的 summary，在 write 的 TAIL 上把整条连接掐断。
        // 所以：原生计划照旧放行，非原生的全部由这条兼容链负责（自己造 summary + 自己填 helper）。
        if (!git.chexson.chexsonsaeutils.integration.ae2ct.Ae2ctJobBridge.takesOver(job)) {
            return;
        }
        ICraftingPlan aggregated = job;

        List<RecipeHelper.Recipe> recipes = new ArrayList<>();

        for (Map.Entry<IPatternDetails, Long> entry : aggregated.patternTimes().entrySet()) {
            IPatternDetails pattern = entry.getKey();
            Long times = entry.getValue();

            if (pattern instanceof HostBatchPattern aggregatedPattern) {
                recipes.add(new RecipeHelper.Recipe(
                        aggregatedPattern.aggregatedInputs(),
                        aggregatedPattern.aggregatedOutputs(),
                        times
                ));
            } else if (!(pattern instanceof RecursivePlanContract)) {
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
