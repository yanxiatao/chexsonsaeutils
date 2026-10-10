package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * A pattern compiled onto the graph, keyed by the output it was discovered for
 * (the pattern's primary output). {@code outputPerCraft} is the total amount of
 * {@code outputKey} produced per firing; {@code allOutputs} keeps every output
 * stack so firing can insert byproducts exactly like the tree planner.
 */
public record GraphPattern(
        IPatternDetails source,
        AEKey outputKey,
        long outputPerCraft,
        List<GenericStack> allOutputs,
        List<GraphInput> inputs,
        boolean hasSelfFeedingInput) {

    public GraphPattern(IPatternDetails source, AEKey outputKey, long outputPerCraft,
            List<GenericStack> allOutputs, List<GraphInput> inputs) {
        this(source, outputKey, outputPerCraft, allOutputs, inputs, computeSelfFeeding(inputs));
    }

    /**
     * 是否含自喂（CONSUMED_RETURNING）输入——决定发射时产出侧是否需要走
     * 闭式修正（宽域路径）。record 组件，编译期一次算好（发射热路径每次都判）。
     */
    public static boolean computeSelfFeeding(List<GraphInput> inputs) {
        for (var input : inputs) {
            if (input.mode() == GraphInput.Mode.CONSUMED_RETURNING) {
                return true;
            }
        }
        return false;
    }

    /**
     * 身份即实例：每个 (键, 样板) 在编译期只建一次，全部容器都按同一实例查表。
     *
     * <p>record 自动生成的深比较会走 {@code inputs} × {@code extractCandidates}
     * × {@code validVariants} 集合——一次 hashCode 能摊出上百次 AEKey 哈希。
     * 实机夹具采样显示这条深哈希（经 {@code slotBundles} 查表触发）占求解自耗
     * 三成以上，改成身份语义后归零。
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
