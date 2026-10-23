package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphInput;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 直接调用结构证明，验证不安全图拒绝、深度边界及共享 DAG 的线性检查量。 */
class BatchPlanningEligibilityTest {
    private static final AEKey TARGET = AEItemKey.of(Items.CLOCK);
    private static final AEKey CHILD = AEItemKey.of(Items.EMERALD);
    private static final AEKey RAW = AEItemKey.of(Items.IRON_INGOT);

    private static GraphInput input(AEKey key, long template) {
        return new GraphInput(key, template, 1, GraphInput.Mode.NORMAL, null, null, null, false);
    }

    private static GraphPattern pattern(AEKey key, long output, GraphInput... inputs) {
        return new GraphPattern(FakePattern.of(key, output, new FakeInput(RAW, 1)), key, output,
                List.of(new GenericStack(key, output)), List.of(inputs));
    }

    private static CraftGraph graph(AEKey target, List<GraphPattern> patterns) {
        var producers = new LinkedHashMap<AEKey, List<GraphPattern>>();
        var sources = new LinkedHashMap<appeng.api.crafting.IPatternDetails, GraphPattern>();
        for (var pattern : patterns) {
            producers.computeIfAbsent(pattern.outputKey(), ignored -> new ArrayList<>()).add(pattern);
            sources.put(pattern.source(), pattern);
        }
        return new CraftGraph(target, producers, sources, List.copyOf(sources.keySet()),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 1, 1);
    }

    @Test
    void rejectsChunkRoundingTemplateCompetitionAndNonDeterministicFlows() {
        var invalid = List.of(
                List.of(pattern(TARGET, 1, input(CHILD, 3)), pattern(CHILD, 2, input(RAW, 1))),
                List.of(pattern(TARGET, 1, input(CHILD, 1)),
                        pattern(CHILD, 1, input(RAW, 2), input(RAW, 3))),
                List.of(pattern(TARGET, 1, input(CHILD, 1)),
                        pattern(CHILD, 1, input(RAW, 1)), pattern(CHILD, 1, input(RAW, 1))),
                List.of(pattern(TARGET, 1, input(CHILD, 1)), new GraphPattern(
                        FakePattern.of(CHILD, 1, new FakeInput(RAW, 1)), CHILD, 1,
                        List.of(new GenericStack(CHILD, 1), new GenericStack(RAW, 1)), List.of(input(RAW, 1)))));
        for (var patterns : invalid) {
            BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(graph(TARGET, patterns), 16, () -> { });
            assertFalse(eligibility.allows(TARGET, 1, true, 16));
        }
        var unsafeInputs = new ArrayList<GraphInput>();
        for (var mode : GraphInput.Mode.values()) {
            if (mode != GraphInput.Mode.NORMAL) {
                unsafeInputs.add(new GraphInput(RAW, 1, 1, mode, RAW, null, null, false));
            }
        }
        unsafeInputs.add(new GraphInput(RAW, 1, 1, GraphInput.Mode.NORMAL, null,
                List.of(new GraphInput.Candidate(RAW, 1)), Set.of(RAW), false));
        unsafeInputs.add(new GraphInput(RAW, 1, 1, GraphInput.Mode.NORMAL, null, null, null, true));
        unsafeInputs.add(new GraphInput(RAW, 1, 0, GraphInput.Mode.NORMAL, null, null, null, false));
        unsafeInputs.add(new GraphInput(RAW, 0, 1, GraphInput.Mode.NORMAL, null, null, null, false));
        for (var input : unsafeInputs) {
            var graph = graph(TARGET, List.of(pattern(TARGET, 1, input(CHILD, 1)), pattern(CHILD, 1, input)));
            BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(graph, 16, () -> { });
            assertFalse(eligibility.allows(TARGET, 1, true, 16), input::toString);
        }
    }

    @Test
    void rootCeilingAndDepthChecksDoNotPolluteMemoizedChildEligibility() {
        var graph = graph(TARGET, List.of(pattern(TARGET, 2, input(CHILD, 3)), pattern(CHILD, 3, input(RAW, 1))));
        BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(graph, 16, () -> { });
        assertFalse(eligibility.allows(TARGET, 1, true, 2));
        assertTrue(eligibility.allows(TARGET, 1, true, 3));
        assertTrue(eligibility.allows(CHILD, 3, false, 2));
        assertFalse(eligibility.allows(CHILD, 1, false, 2));
        assertFalse(eligibility.allows(CHILD, 3, false, 1));
        assertFalse(eligibility.allows(TARGET, 0, true, 16));
    }

    @Test
    void repeatedDiamondChecksEachKeyAndEdgeOnlyConstantTimes() {
        int depth = 128;
        var keys = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
                .limit(depth + 1L).map(AEItemKey::of).toList();
        var patterns = new ArrayList<GraphPattern>();
        for (int index = 0; index < depth; index++) {
            patterns.add(pattern(keys.get(index), 1, input(keys.get(index + 1), 1), input(keys.get(index + 1), 1)));
        }
        int[] checks = {0};
        BatchPlanningEligibility eligibility = new BatchPlanningEligibilityImpl(
                graph(keys.getFirst(), patterns), depth + 1, () -> checks[0]++);
        assertTrue(eligibility.allows(keys.getFirst(), 1, true, depth + 1));
        assertTrue(checks[0] < depth * 10, "证明检查量必须随唯一节点及输入边增长");
        int before = checks[0];
        for (int index = 1; index < depth; index++) {
            assertTrue(eligibility.allows(keys.get(index), 1, false, depth + 1 - index));
        }
        assertEquals(before, checks[0], "共享子图的后续查询应复用证明");
    }
}
