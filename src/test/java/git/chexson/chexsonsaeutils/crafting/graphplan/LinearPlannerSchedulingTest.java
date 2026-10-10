package git.chexson.chexsonsaeutils.crafting.graphplan;

import java.util.List;
import java.util.Map;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 唯一生产求解器 {@code LinearPlanner} 的四张<b>绝对</b>读数：模糊槽变体顺位、带组件的两级链发数、
 * 多路线最大可行批的拆分点、非均匀环的配比发数表（producer 9 / recycler 24 / consumer 8）。
 *
 * <p>本类原是 {@code graphplan/search/ResidualBestFirstPlannerTest}：那个类的判据是「独立残量搜索
 * 与 LinearPlanner 逐字段对拍」，而四条用例都在对拍之下另钉了一份绝对值。搜索那一头摘掉后
 * 对拍失去对手，绝对值本身却是 LinearPlanner 当时的读数（对拍逐字段相等就是它相等）——
 * 所以这里<b>只换被测入口，一个字也没放松</b>，不是把期望改成新引擎自己的输出。
 *
 * <p>"与 AE2 真树等价"这一头不在本类：那是 {@code GraphPlanningEquivalenceTest} 的 48 条树对拍
 * （{@code FakePlanGrid.assertPlansEqual} 含 {@code bytes} 逐位）。本类钉的是换了引擎也不许变的
 * <b>分配结果</b>，树对拍钉的是<b>与模仿对象同一份账</b>，两件事不互相顶替。
 */
final class LinearPlannerSchedulingTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey MID = AEItemKey.of(Items.GUNPOWDER);
    private static final AEItemKey LEAF = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);

    private static CraftGraph compile(FakeGrid grid) {
        return compile(grid, TOP);
    }

    private static CraftGraph compile(FakeGrid grid, AEKey target) {
        CraftGraph graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        assertFalse(graph.hasDeclined(), () -> "compile declined: " + graph.declineReason());
        return graph;
    }

    private static SolveResult solve(FakeGrid grid, long amount, CalculationStrategy strategy) {
        SolveResult result = LinearPlanner.solve(compile(grid), strategy, amount);
        assertFalse(result.hasDeclined(), () -> "solve declined: " + result.declineReason());
        return result;
    }

    /** 模糊槽同一物品两档损伤：先吃受损那一枚、余额再吃无损那一枚，顺位逐字不变。 */
    @Test
    void fuzzyInputKeepsStableGreedyVariantOrder() {
        AEItemKey pristine = AEItemKey.of(Items.IRON_PICKAXE);
        var damagedStack = new ItemStack(Items.IRON_PICKAXE);
        damagedStack.setDamageValue(50);
        AEItemKey damaged = AEItemKey.of(damagedStack);
        var grid = FakeGrid.builder()
                .storage(Map.of(pristine, 10L, damaged, 5L))
                .pattern(TOP, FakePattern.of(TOP, 1L, FakeInput.anyVariant(pristine, 1L)))
                .build();

        SolveResult result = solve(grid, 12L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(5L, result.state().usedItems().get(damaged));
        assertEquals(7L, result.state().usedItems().get(pristine));
    }

    /** 两级链各带组件键：逐级发数与网络提取腿逐字不变。 */
    @Test
    void componentSpecificChainBooksPerStageFirings() {
        AEItemKey version1 = customDataKey(1);
        AEItemKey version2 = customDataKey(2);
        AEItemKey version3 = customDataKey(3);
        FakePattern firstStage = FakePattern.of(version2, 1L,
                new FakeInput(version1, 1L), new FakeInput(LEAF, 1L));
        FakePattern secondStage = FakePattern.of(version3, 1L,
                new FakeInput(version2, 1L), new FakeInput(OTHER, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(version1, 4L, LEAF, 4L, OTHER, 4L))
                .pattern(version2, firstStage)
                .pattern(version3, secondStage)
                .build();

        SolveResult result = LinearPlanner.solve(compile(grid, version3),
                CalculationStrategy.REPORT_MISSING_ITEMS, 4L);
        assertFalse(result.hasDeclined(), () -> "solve declined: " + result.declineReason());
        assertFalse(result.simulation());
        assertEquals(4L, result.state().crafts().get(firstStage));
        assertEquals(4L, result.state().crafts().get(secondStage));
        assertEquals(4L, result.state().usedItems().get(version1));
        assertEquals(0L, result.state().usedItems().get(version2));
    }

    private static AEItemKey customDataKey(int version) {
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", version);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return AEItemKey.of(stack);
    }

    /** 台账 #140：两条路线抢一格时，拆分点必须落在最大可行批上（吃铁那条先被首触）。 */
    @Test
    void boundedMultiProducerSplitsAtMaximumFeasibleBatch() {
        // 这一档要的是"吃铁的那条路线先被首触"，用递减的提供者优先级表达，
        // 而不是指望并列清单的喂入序——AE2 真网格上并列那一档交的是 HashSet 遍历序。
        FakePattern scarceRoute = FakePattern.of(TOP, 1L, new FakeInput(LEAF, 1L)).withPriority(1);
        FakePattern fallbackRoute = FakePattern.of(TOP, 1L, new FakeInput(OTHER, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L, OTHER, 100L))
                .pattern(TOP, scarceRoute, fallbackRoute)
                .build();

        SolveResult result = solve(grid, 6L, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(2L, result.state().crafts().get(scarceRoute));
        assertEquals(4L, result.state().crafts().get(fallbackRoute));
    }

    /** 非均匀环（1 发产 3 枚 / 1 枚换 1+1 枚）的配比发数表：全仓只有这里按绝对值钉。 */
    @Test
    void nonUniformRingRatioBooksProducerRecyclerConsumerFirings() {
        FakePattern producer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(LEAF, 2L)}, new GenericStack(OTHER, 3L));
        FakePattern recycler = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(OTHER, 1L)},
                new GenericStack(LEAF, 1L), new GenericStack(MID, 1L));
        FakePattern consumer = FakePattern.of(TOP, 1L, new FakeInput(MID, 1L));
        var grid = FakeGrid.builder()
                .storage(Map.of(LEAF, 2L))
                .pattern(LEAF, recycler)
                .pattern(OTHER, producer)
                .pattern(MID, recycler)
                .pattern(TOP, consumer)
                .build();

        CraftGraph graph = compile(grid);
        assertEquals(CraftGraph.CycleKind.COMPRESSIBLE, graph.cycleKind());
        var macros = graph.ringMacrosByEntry().getOrDefault(MID, List.of());
        assertFalse(macros.isEmpty());
        assertEquals(1L, macros.getFirst().executionRatio().get(producer));
        assertEquals(2L, macros.getFirst().executionRatio().get(recycler));
        SolveResult result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 8L);
        assertFalse(result.hasDeclined(), () -> "solve declined: " + result.declineReason());
        // 还种轮数只看"从网络起步预留里实际借走了几枚留种催化物"：这里被扣的起步种是另一种
        // 催化物，留种键（该环的净输出键）在网络里是 0 枚 —— 一枚都没借走就不加发，
        // 多算那一发既不还出任何东西又抢主链的料。所以就是刚够交付那一版（每轮 1+2 发）。
        // 真借走种的形状由 OrderedPlanningRecursiveCapabilityTest
        // .ringSeedBorrowedByOtherEntryIsRepaidToNetwork 守着。
        assertEquals(9L, result.state().crafts().get(producer));
        assertEquals(24L, result.state().crafts().get(recycler));
        assertEquals(8L, result.state().crafts().get(consumer));
    }
}
