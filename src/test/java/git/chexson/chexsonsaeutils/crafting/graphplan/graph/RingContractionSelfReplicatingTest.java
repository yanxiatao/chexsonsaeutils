package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自增殖样板的环收缩（modpack 场景：锻造模板 1 出 2，输入=自己的输出）：
 * 单样板自环必须收缩为环宏——此前 selfBlocked 挡建边 / isTrueRecursiveRing
 * 排除自供给 / RouteOrdering 丢无色宏，三处任一断掉模板站就 routes=0。
 */
class RingContractionSelfReplicatingTest {

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    @Test
    void selfReplicatingPatternContractsToMacro() {
        var template = key(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        // 1 出 2 自增殖：输入 template×1 → 输出 template×2
        FakePattern doubling = FakePattern.of(template, 2, new FakeInput(template, 1L));

        // 编译产物形态：CONSUMED_RETURNING 自喂槽（selfBlocked=false，豁免后）
        var graphPattern = new GraphPattern(
                doubling,
                template,
                2L,
                List.of(new appeng.api.stacks.GenericStack(template, 2L)),
                List.of(new GraphInput(template, 1L, 1L,
                        GraphInput.Mode.CONSUMED_RETURNING, null, null, null, false)));
        // 层 1：净流量压缩
        var ring = git.chexson.chexsonsaeutils.crafting.recursive.CompressedRing
                .calculate(List.of(doubling));
        assertTrue(ring != null && ring.calculable(), "CompressedRing 应可算");
        assertTrue(ring.catalysts().get(template) > 0, "模板应为催化物");

        // 层 2：真递归判定（自供给放行）
        assertTrue(git.chexson.chexsonsaeutils.crafting.recursive.RecursiveRingPlanner
                .isTrueRecursiveRing(ring, template), "自供给应为真递归");

        // 层 3：收缩
        List<RingMacro> macros = RingContraction.contract(
                Map.of(template, List.of(graphPattern)));

        assertFalse(macros.isEmpty(), "自增殖样板必须收缩为环宏");
        var macro = macros.getFirst();
        assertTrue(macro.netOutputs().get(template) > 0, "净输出含模板");
        assertFalse(macro.catalysts().isEmpty(), "模板是催化物（种子）");
        assertFalse(macro.rootOnly(), "非根专用");
        assertTrue(macro.executionRatio().containsKey(doubling), "执行比含自增殖样板");
    }

    @Test
    void mixedColorRecursiveRingContractsWithoutColorQualification() {
        var firstKey = key(Items.STONE);
        var secondKey = key(Items.COBBLESTONE);
        var first = FakePattern.colored(
                3,
                key(Items.PAPER),
                List.of(new appeng.api.stacks.GenericStack(firstKey, 2L)),
                new FakeInput(secondKey, 1L));
        var second = FakePattern.colored(
                4,
                key(Items.BOOK),
                List.of(new appeng.api.stacks.GenericStack(secondKey, 1L)),
                new FakeInput(firstKey, 1L));

        List<RingMacro> macros = RingContraction.contractPatterns(List.of(first, second));

        assertFalse(macros.isEmpty(), "异色真递归 SCC 也必须收缩");
        RingMacro macro = macros.stream()
                .filter(candidate -> firstKey.equals(candidate.entryKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, macro.executionRatio().size());
        assertTrue(macro.executionRatio().containsKey(first));
        assertTrue(macro.executionRatio().containsKey(second));
    }

    @Test
    void nonUniformRatioKeepsEveryInternalSeed() {
        var a = key(Items.STICK);
        var b = key(Items.COAL);
        var leaf = key(Items.REDSTONE);
        var producer = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(a, 2L)},
                new GenericStack(b, 3L));
        var recycler = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(b, 1L)},
                new GenericStack(a, 1L), new GenericStack(leaf, 1L));

        CompressedRing ring = CompressedRing.calculate(List.of(producer, recycler));

        assertTrue(ring != null && ring.calculable());
        assertEquals(1L, ring.executionRatio().get(producer));
        assertEquals(2L, ring.executionRatio().get(recycler));
        assertEquals(2L, ring.catalysts().get(a));
        assertEquals(2L, ring.catalysts().get(b));
        assertEquals(1L, ring.netOutputs().get(b));
        assertEquals(2L, ring.netOutputs().get(leaf));

        var macros = RingContraction.contractPatterns(List.of(producer, recycler));
        assertFalse(macros.isEmpty());
        for (RingMacro macro : macros) {
            // 数值逐字不变（2）；读数域随 #218 起步种量进 PlanAmount。
            assertEquals(Map.of(a, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(2L)),
                    macro.startupSeeds());
        }
    }

    @Test
    void startupPlanRequestsEverySeedNeededByFirstFiring() {
        var a = key(Items.STICK);
        var b = key(Items.COAL);
        var intermediate = key(Items.IRON_INGOT);
        var output = key(Items.DIAMOND);
        var starter = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(a, 1L), new FakeInput(b, 1L)},
                new GenericStack(intermediate, 1L));
        var recycler = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(intermediate, 1L)},
                new GenericStack(a, 2L), new GenericStack(b, 2L),
                new GenericStack(output, 1L));

        var macros = RingContraction.contractPatterns(List.of(starter, recycler));

        assertFalse(macros.isEmpty());
        for (RingMacro macro : macros) {
            // 数值逐字不变（1, 1）；读数域随 #218 起步种量进 PlanAmount。
            assertEquals(Map.of(a, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(1L),
                    b, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(1L)),
                    macro.startupSeeds());
        }
    }

    @Test
    void coupledMultiKeyRatioIsStableAcrossMemberOrder() {
        var a = key(Items.STICK);
        var b = key(Items.COAL);
        var c = key(Items.REDSTONE);
        var leaf = key(Items.DIAMOND);
        var first = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(a, 5L)},
                new GenericStack(b, 3L));
        var second = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(b, 2L)},
                new GenericStack(c, 3L));
        var third = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(c, 2L)},
                new GenericStack(a, 3L), new GenericStack(leaf, 1L));

        CompressedRing forward = CompressedRing.calculate(List.of(first, second, third));
        CompressedRing shuffled = CompressedRing.calculate(List.of(third, first, second));

        assertTrue(forward != null && forward.calculable());
        assertTrue(shuffled != null && shuffled.calculable());
        assertEquals(Map.of(first, 2L, second, 3L, third, 4L), forward.executionRatio());
        assertEquals(forward.executionRatio(), shuffled.executionRatio());
        assertEquals(10L, forward.catalysts().get(a));
        assertEquals(6L, forward.catalysts().get(b));
        assertEquals(8L, forward.catalysts().get(c));
        assertEquals(2L, forward.netOutputs().get(a));
        assertEquals(1L, forward.netOutputs().get(c));
        assertEquals(4L, forward.netOutputs().get(leaf));
    }

    @Test
    void ringWithoutPositiveInternalContributionIsNotCalculable() {
        var a = key(Items.STICK);
        var leaf = key(Items.REDSTONE);
        var consumingLoop = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(a, 2L)},
                new GenericStack(a, 1L), new GenericStack(leaf, 1L));

        CompressedRing ring = CompressedRing.calculate(List.of(consumingLoop));

        assertTrue(ring != null);
        assertFalse(ring.calculable());
        assertTrue(ring.executionRatio().isEmpty());
    }

    @Test
    void infeasibleCoupledFlowTerminatesAsNotCalculable() {
        var a = key(Items.STICK);
        var b = key(Items.COAL);
        var c = key(Items.REDSTONE);
        var first = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(a, 4L)},
                new GenericStack(b, 1L));
        var second = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(b, 2L)},
                new GenericStack(c, 3L));
        var third = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(c, 4L)},
                new GenericStack(a, 5L));

        CompressedRing ring = CompressedRing.calculate(List.of(first, second, third));

        assertTrue(ring != null);
        assertFalse(ring.calculable());
        assertTrue(ring.executionRatio().isEmpty());
    }

    @Test
    void ratioOverflowTerminatesAsNotCalculable() {
        var a = key(Items.STICK);
        var b = key(Items.COAL);
        var first = FakePattern.withOutputs(
                new FakeInput[]{FakeInput.stack(a, Long.MAX_VALUE)},
                new GenericStack(b, 1L));
        var second = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(b, 1L)},
                new GenericStack(a, 1L));

        CompressedRing ring = CompressedRing.calculate(List.of(first, second));

        assertTrue(ring != null);
        assertFalse(ring.calculable());
        assertTrue(ring.executionRatio().isEmpty());
    }

    @Test
    void balancedCycleIsReportedAsUnsupported() {
        var a = key(Items.STICK);
        var b = key(Items.COAL);
        var first = FakePattern.of(a, 1L, new FakeInput(b, 1L));
        var second = FakePattern.of(b, 1L, new FakeInput(a, 1L));

        RingContraction.Analysis analysis = RingContraction.analyzePatterns(List.of(first, second));

        assertEquals(CraftGraph.CycleKind.UNSUPPORTED_CYCLE, analysis.cycleKind());
        assertTrue(analysis.macros().isEmpty());
    }
}
