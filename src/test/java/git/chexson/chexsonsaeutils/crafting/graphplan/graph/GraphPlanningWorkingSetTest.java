package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.Ae2TreePlanReference;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 收窄编号范围后仍保留库存快照、相关模糊类与新鲜库存数量的行为契约。 */
class GraphPlanningWorkingSetTest {
    @Test
    void unrelatedStockDoesNotExpandLedgersAndRefreshedStockStillLimitsCrafting() {
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var stock = new LinkedHashMap<AEKey, Long>();
        stock.put(gold, 2000L);
        for (int color = 0; color < 1000; color++) {
            stock.put(FakePlanGrid.dyedKey(Items.LEATHER_HELMET, color), 1L);
        }
        var grid = FakeGrid.builder().storage(stock)
                .pattern(iron, FakePattern.crafting(iron, 1L, Map.of(gold, 1L))).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), iron, null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        assertEquals(stock, graph.stock(), "完整库存快照仍供其他判据使用");
        assertEquals(2, graph.keyIndex().size(), "账本只需铁、金两个索引");
        var result = LinearPlanner.solve(graph, CalculationStrategy.CRAFT_LESS, 1000L);
        assertFalse(result.hasDeclined(), result::declineReason);
        var plan = Ae2PlanConverter.toPlan(result, graph);
        var nativePlan = new Ae2TreePlanReference(grid, grid.requester,
                new GenericStack(iron, 1000L), CalculationStrategy.CRAFT_LESS).run();
        FakePlanGrid.assertPlansEqual(nativePlan, plan);
        stock.put(gold, 500L);
        var refreshed = graph.withStock(stock);
        var partial = LinearPlanner.solve(refreshed, CalculationStrategy.CRAFT_LESS, 1000L);
        assertFalse(partial.hasDeclined(), partial::declineReason);
        var partialPlan = Ae2PlanConverter.toPlan(partial, refreshed);
        assertEquals(500L, partialPlan.finalOutput().amount());
        assertEquals(Map.of(gold, 500L), FakePlanGrid.toPlain(partialPlan.usedItems()));
        assertEquals(2, refreshed.keyIndex().size());
    }

    @Test
    void relevantVariantsShareReadOnlyViewsAndRetainExtractionOrder() {
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var stock = new LinkedHashMap<AEKey, Long>();
        for (int color = 0; color < 31; color++) {
            stock.put(FakePlanGrid.dyedKey(Items.GOLD_INGOT, color), 1L);
        }
        var grid = FakeGrid.builder().storage(stock)
                .pattern(iron, FakePattern.of(iron, 1L, FakeInput.anyVariant(gold, 1L))).build();
        var graph = GraphCompiler.compile(grid.crafting, grid.storage.getCachedInventory(), iron, null);
        assertFalse(graph.hasDeclined(), graph::declineReason);
        var members = graph.classVariants().get(gold);
        assertEquals(32, members.size());
        var ids = graph.denseKeyView().classMemberIds(graph.keyIndex().lookup(gold));
        for (int index = 0; index < members.size(); index++) {
            var member = members.get(index);
            assertSame(members, graph.classVariants().get(member));
            assertSame(ids, graph.denseKeyView().classMemberIds(graph.keyIndex().lookup(member)));
            assertEquals(member, graph.keyIndex().keyOf(ids[index]));
        }
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 20L);
        assertFalse(result.hasDeclined(), result::declineReason);
        var plan = Ae2PlanConverter.toPlan(result, graph);
        assertFalse(plan.simulation());
        assertEquals(20L, FakePlanGrid.toPlain(plan.usedItems()).values().stream()
                .mapToLong(Long::longValue).sum());
        assertTrue(stock.keySet().containsAll(FakePlanGrid.toPlain(plan.usedItems()).keySet()));
        var expectedUsed = new LinkedHashMap<AEKey, Long>();
        for (var member : members) {
            if (stock.containsKey(member) && expectedUsed.size() < 20) {
                expectedUsed.put(member, 1L);
            }
        }
        assertEquals(expectedUsed, FakePlanGrid.toPlain(plan.usedItems()), "按既有模糊类顺位取前二十枚");
        // 非耐久物品的原生哈希顺位与图的模糊类顺位不同；全量提取逐键对拍。
        var full = Ae2PlanConverter.toPlan(
                LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, 31L), graph);
        var nativePlan = new Ae2TreePlanReference(grid, grid.requester,
                new GenericStack(iron, 31L), CalculationStrategy.REPORT_MISSING_ITEMS).run();
        FakePlanGrid.assertPlansEqual(nativePlan, full);
    }
}
