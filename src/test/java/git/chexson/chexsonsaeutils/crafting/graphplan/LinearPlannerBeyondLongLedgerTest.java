package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code > long} 的需求在唯一生产的图求解器（树式 {@link LinearPlanner}）那一趟上必须记真值，
 * 不许退成 {@code Long.MAX_VALUE} 哨兵：需求饱和在哪一本账上，供给就跟着少记哪一截，
 * 而守恒闸门的需求侧是精确宽乘——两侧读出两个数即假拒。
 *
 * <p>本类原是聚合求解器与树路径的逐字段对拍。聚合核（原 {@code aggregate/AggregateSolver}）随
 * 「规划模块收成单个图算法求解器」摘掉后，11 条纯对拍一起消失；留下的这两条把<b>被测入口</b>
 * 换成 {@code LinearPlanner} 单腿，期望值沿用对拍时钉住的那一份读数（1e19 枚、发数＝请求量），
 * 断言语义一字未放松。方法名保留退役前的名字，便于对着迁移表核账。
 */
final class LinearPlannerBeyondLongLedgerTest {

    private static final AEItemKey TOP = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey OTHER = AEItemKey.of(Items.GOLD_INGOT);
    /** 每发 1 枚 TOP 吃 10 枚金 ⇒ 1e18 发放大成 1e19 枚，正好越出 long。 */
    private static final long REQUEST = 1_000_000_000_000_000_000L;
    private static final java.math.BigInteger WIDE_UNITS = new java.math.BigInteger("10000000000000000000");

    private static CraftGraph compile(FakeGrid grid, AEKey target) {
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), target, null);
        assertFalse(graph.hasDeclined(), () -> "unexpected compile decline: " + graph.declineReason());
        return graph;
    }

    private static Map<String, Long> craftTimes(GraphPlanningState state) {
        var map = new HashMap<String, Long>();
        for (var entry : state.crafts().entrySet()) {
            map.merge(String.valueOf(entry.getKey().getDefinition()), entry.getValue(), Long::sum);
        }
        return map;
    }

    /**
     * 存量那一腿：网络里存着 1.5e19 枚金（无限盘真值），配得平的一单不许被读成
     * 「供给 9.22e18 &lt; 需求 1e19」的假缺口。
     */
    @Test
    void beyondLongAggregateDemandBooksExactSupplyInsteadOfSentinel() {
        var grid = FakeGrid.builder()
                .storage(Map.of(OTHER, Long.MAX_VALUE))
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(OTHER, 10L)))
                .build();

        var tree = LinearPlanner.solve(withTruth(compile(grid, TOP)),
                CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
        assertFalse(tree.hasDeclined(),
                () -> "网络里真有 1.5e19 枚金，树趟不该被自己那本饱和账拒掉: " + tree.declineReason());
        assertNotNull(tree.state());
        assertEquals(WIDE_UNITS, tree.state().used().getExact(OTHER),
                "供给侧（used）必须记下 1e19 枚的真值，而不是天花板");
        assertTrue(tree.state().missingItems().isEmpty(),
                () -> "池子里有这么多料，不许报缺料: " + tree.state().missingItems());
        var times = craftTimes(tree.state());
        long firings = times.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(1L, times.size(), () -> "只有一张配方，发数表就该只有一格: " + times);
        assertEquals(REQUEST, firings, "每发一枚 TOP ⇒ 发数必须等于请求量，不许在饱和读数上截断");
    }

    /** 可发射（emittable）那一腿同样不许把需求截成天花板：这本账（emitted）是闸门供给侧的一条腿。 */
    @Test
    void beyondLongEmittableDemandBooksExactEmittedAmount() {
        var grid = FakeGrid.builder()
                .pattern(TOP, FakePattern.of(TOP, 1L, new FakeInput(OTHER, 10L)))
                .emittable(OTHER)
                .build();

        var tree = LinearPlanner.solve(compile(grid, TOP),
                CalculationStrategy.REPORT_MISSING_ITEMS, REQUEST);
        assertFalse(tree.hasDeclined(),
                () -> "发射口是无界的，树趟不该被自己那本饱和账拒掉: " + tree.declineReason());
        assertNotNull(tree.state());
        assertEquals(WIDE_UNITS, tree.state().emitted().getExact(OTHER),
                "发射量 1e19 枚必须记真值：闸门的需求侧读到的就是这份数");
    }

    /** 把无限盘溢出键的宽域供给真值挂到编译图上（与 {@code GraphPlanningSession} 同一入口）。 */
    private static CraftGraph withTruth(CraftGraph graph) {
        graph.attachSupplyTruth(git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth.of(
                java.util.Map.of(OTHER, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(
                        new java.math.BigInteger("15000000000000000000")))));
        return graph;
    }
}
