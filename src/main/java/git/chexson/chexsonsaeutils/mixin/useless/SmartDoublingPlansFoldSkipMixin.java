package git.chexson.chexsonsaeutils.mixin.useless;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * 递归计划不进 useless_mod 的全表折叠：{@code SmartDoublingPlans.rewriteForSubmission}
 * 把整个任务表折成 {@code ScaledProcessingPattern} 时，一个键的发数会被压到 1，
 * 每发输入变成原来几十亿发的合计（实机：玻璃单发 17,737,744,384、黑紫水晶单发 6,426,620,608）。
 *
 * <p>比例守恒，但**原子性变了**：一发要么整份凑齐、要么整份推进不了，池子里再多的零碎料也
 * 喂不动它——规划期"边产边吃"的流水被换成"一次吞下全表份额"，尾段必然饿死。
 * 我们的并行 CPU 自己按现场量决定一次推进几发（{@code ParallelBatchExtraction}），
 * 不需要靠折叠换吞吐，所以对 {@link RecursiveCraftingPlan} 直接短路。
 *
 * <p>只短路我们自己的计划：非递归计划照旧让它折叠。
 */
@Pseudo
@Mixin(
        targets = "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.SmartDoublingPlans",
        remap = false
)
public abstract class SmartDoublingPlansFoldSkipMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "rewriteForSubmission", at = @At("HEAD"), cancellable = true, remap = false)
    private static void chexsonsaeutils$keepRecursivePlanUnfolded(
            ICraftingPlan plan,
            Function<IPatternDetails, Iterable<ICraftingProvider>> providerLookup,
            CallbackInfoReturnable<ICraftingPlan> cir
    ) {
        // 带规划定版配给表的计划一律不折：折叠会换掉样板实例，按实例挂的配给当场失效，
        // 而"一发吞下全表份额"本身就是规划配给封顶要禁止的消耗形态。
        if (plan instanceof RecursiveCraftingPlan shell) {
            cir.setReturnValue(shell);
            LOGGER.info("[chexsonsaeutils-fold] skipped plan={} patterns={}",
                    System.identityHashCode(shell), shell.patternTimes().size());
        }
    }
}
