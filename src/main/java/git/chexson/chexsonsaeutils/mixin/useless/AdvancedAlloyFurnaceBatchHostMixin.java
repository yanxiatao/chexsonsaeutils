package git.chexson.chexsonsaeutils.mixin.useless;

import appeng.api.crafting.IPatternDetails;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessFurnaceBatchHost;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessSmartDoublingFold;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 给 useless_mod 的**单机高级合金炉**接上我们自己的批量折叠。
 *
 * <p>它的 {@code pushPattern} 只按"几次 push"记账：一次 push 落进 {@code PendingAEBatch}
 * 是一发料，攒够窗口才并成任务。想在一次交互里多推进几发，唯一通道是套成它认得的
 * {@code ScaledProcessingPattern(inner, k)}——算式见 {@link UselessSmartDoublingFold}。
 */
@Pseudo
@Mixin(
        targets = "com.sorrowmist.useless.content.blockentities.AdvancedAlloyFurnaceBlockEntity",
        remap = false
)
public abstract class AdvancedAlloyFurnaceBatchHostMixin implements UselessFurnaceBatchHost {

    @Override
    public long chexsonsaeutils$maxFoldedPushExecutions(IPatternDetails pattern) {
        return UselessSmartDoublingFold.maxFoldedPushExecutions(pattern);
    }

    @Nullable
    @Override
    public IPatternDetails chexsonsaeutils$foldPushPattern(IPatternDetails pattern, long executions) {
        return UselessSmartDoublingFold.foldPushPattern(pattern, executions);
    }
}
