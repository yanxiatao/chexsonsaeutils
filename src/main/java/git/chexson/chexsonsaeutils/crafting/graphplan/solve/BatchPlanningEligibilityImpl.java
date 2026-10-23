package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;

/**
 * 多输入确定 DAG 的结构证明，每个键与输入边最多检查常数次。
 *
 * <p>子需求模板与每发产量必须互有整除关系：模板是产量整数倍时无需生产取整，
 * 产量是模板整数倍时取整余料能被后续请求按整模板重新使用。同一材料的消费模板一致，
 * 任意分轮和槽顺序下
 * 可花的整模板库存总量一致。配合单产出、唯一生产和无退料，可将分轮的材料流量
 * 相加，不改变发数、库存或缺口。共享子图允许复用这一证明，多输入本身不再拒绝。
 *
 * <p>库存和根请求数量不参与缓存，资格在一次求解的多次部分合成尝试之间可复用。
 */
public final class BatchPlanningEligibilityImpl implements BatchPlanningEligibility {
    private final CraftGraph graph;
    private final int maxDepth;
    private final Runnable checkBudget;
    /** 图中每个键的唯一消费模板；0 表示模板冲突，拒绝改变既有分轮竞争。 */
    private final Map<AEKey, Long> templates = new HashMap<>();
    /** 包含终端的最大站点深度；0 表示无法证明整批等价。 */
    private final Map<AEKey, Integer> heights = new HashMap<>();

    /** 将证明绑定到本次编译图和现有预算检查口，不额外读取网络或建立槽位。 */
    public BatchPlanningEligibilityImpl(CraftGraph graph, int maxDepth, Runnable checkBudget) {
        this.graph = graph;
        this.maxDepth = maxDepth;
        this.checkBudget = checkBudget;
        for (var pattern : graph.patternsBySource().values()) {
            for (var input : pattern.inputs()) {
                checkBudget.run();
                templates.merge(input.key(), input.amountPerCraft(), (left, right) -> left.equals(right) ? left : 0L);
            }
        }
    }

    @Override
    public boolean allows(AEKey key, long template, boolean root, int remainingDepth) {
        if (template <= 0 || remainingDepth <= 0 || graph.cycleKind() != CraftGraph.CycleKind.NONE) return false;
        if (!root && (templates.getOrDefault(key, 0L) != template || !aligned(key, template))) return false;
        int height = height(key);
        return height > 0 && height <= remainingDepth;
    }

    private boolean terminal(AEKey key) {
        return graph.emittable().contains(key)
                || (!graph.producersByOutput().containsKey(key) && !graph.ringMacrosByEntry().containsKey(key));
    }

    private boolean aligned(AEKey key, long template) {
        if (terminal(key)) return true;
        var producers = graph.producersByOutput().get(key);
        if (producers == null || producers.size() != 1 || producers.getFirst().outputPerCraft() <= 0) return false;
        long output = producers.getFirst().outputPerCraft();
        return template % output == 0 || output % template == 0;
    }

    /** 显式栈避免资格检查本身消耗 JVM 调用栈；汇合子图读取已缓存的高度。 */
    private int height(AEKey key) {
        var cached = heights.get(key);
        if (cached != null) return cached;
        var pending = new ArrayDeque<Frame>();
        var active = new HashSet<AEKey>();
        pending.push(new Frame(key));
        active.add(key);
        while (!pending.isEmpty()) {
            checkBudget.run();
            var frame = pending.peek();
            if (frame.pattern == null) {
                if (terminal(frame.key)) {
                    finish(pending, active, frame, 1);
                    continue;
                }
                var producers = graph.producersByOutput().get(frame.key);
                if (producers == null || producers.size() != 1
                        || !graph.ringMacrosByEntry().getOrDefault(frame.key, java.util.List.of()).isEmpty()) {
                    finish(pending, active, frame, 0);
                    continue;
                }
                var pattern = producers.getFirst();
                if (pattern.outputPerCraft() <= 0 || pattern.allOutputs().size() != 1
                        || !pattern.allOutputs().getFirst().what().equals(frame.key)) {
                    finish(pending, active, frame, 0);
                    continue;
                }
                frame.pattern = pattern;
            }
            if (frame.index == frame.pattern.inputs().size()) {
                finish(pending, active, frame, frame.height);
                continue;
            }
            var input = frame.pattern.inputs().get(frame.index);
            if (input.mode() != GraphInput.Mode.NORMAL || input.extractCandidates() != null
                    || input.remainderKey() != null || input.selfBlocked() || input.multiplier() <= 0
                    || input.amountPerCraft() <= 0
                    || templates.getOrDefault(input.key(), 0L) != input.amountPerCraft()
                    || !aligned(input.key(), input.amountPerCraft())) {
                finish(pending, active, frame, 0);
                continue;
            }
            var childHeight = heights.get(input.key());
            if (childHeight != null) {
                if (childHeight == 0 || childHeight >= maxDepth) {
                    finish(pending, active, frame, 0);
                } else {
                    frame.height = Math.max(frame.height, childHeight + 1);
                    frame.index++;
                }
            } else if (active.contains(input.key())) {
                finish(pending, active, frame, 0);
            } else {
                pending.push(new Frame(input.key()));
                active.add(input.key());
            }
        }
        return heights.get(key);
    }

    private void finish(ArrayDeque<Frame> pending, HashSet<AEKey> active, Frame frame, int height) {
        heights.put(frame.key, height);
        pending.pop();
        active.remove(frame.key);
    }

    private static final class Frame {
        private final AEKey key;
        private GraphPattern pattern;
        private int index;
        private int height = 1;

        private Frame(AEKey key) {
            this.key = key;
        }
    }
}
