package git.chexson.chexsonsaeutils.mixin.useless;

import appeng.api.crafting.IPatternDetails;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessFurnaceBatchHost;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessSmartDoublingFold;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 给 useless_mod 的 **ME 样板组装多方块**接上批量折叠：实机万象样板的推送全部落在这里。
 *
 * <p>网格把 {@code MePatternAssemblyBlockEntity} 当作 {@code ICraftingProvider}，它的
 * {@code pushPattern}/{@code isBusy} 只是转交给多方块核心与同一个
 * {@code AdvancedAlloyFurnaceAeManager}。只 mixin 单机炉子的那一层壳接不到这条通道
 * （实机读数：{@code MePatternAssemblyBlockEntity[r=257,busy=0,push=132304]} 而 {@code foldHost=0}）。
 */
@Pseudo
@Mixin(
        targets = "com.sorrowmist.useless.content.blockentities.multiblock.MePatternAssemblyBlockEntity",
        remap = false
)
public abstract class MePatternAssemblyBatchHostMixin implements UselessFurnaceBatchHost {

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
