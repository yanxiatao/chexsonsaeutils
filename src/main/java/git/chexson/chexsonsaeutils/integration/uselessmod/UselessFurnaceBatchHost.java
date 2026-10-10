package git.chexson.chexsonsaeutils.integration.uselessmod;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;

/**
 * useless_mod 合金炉侧"一次 push 推进多发"的宿主接口（执行侧，不参与规划聚合）。
 *
 * <p>有两个网格入口都实现它：单机 {@code AdvancedAlloyFurnaceBlockEntity} 与多方块
 * {@code MePatternAssemblyBlockEntity}（实机万象样板的推送全在后者）。两者把 push 转交给
 * 同一个 {@code AdvancedAlloyFurnaceAeManager}，因此折叠算式只有一份
 * （{@link UselessSmartDoublingFold}）。
 *
 * <p>该 manager 只认两件事：它自己发布过的样板实例，或套在实例上的
 * {@code ScaledProcessingPattern(inner, k)}——后者让它把一次 push 记成 k 发执行，
 * 每发仍只吃一发的料。它没有别的批量入口，也不会按 holder 里的总量反推发数：
 * 用未缩放的样板推 k 倍的料，它只会做一发并把余料留在输入槽。
 */
public interface UselessFurnaceBatchHost {

    /** 一次 push 最多能记几发：炉子自身对样板量的 long 上界。 */
    long chexsonsaeutils$maxFoldedPushExecutions(IPatternDetails pattern);

    /** 把 k 发的提取量折成炉子认得的推送样板；折不动时返回 null（调用方退回单发）。 */
    @Nullable
    IPatternDetails chexsonsaeutils$foldPushPattern(IPatternDetails pattern, long executions);
}
