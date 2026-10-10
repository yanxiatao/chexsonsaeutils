package git.chexson.chexsonsaeutils.menu.crafting;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.*;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphCompiler;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.LinearPlanner;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.*;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import java.math.BigInteger;
import java.util.Map;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 下单数量换算的行为验证，包括越过 long 的库存与循环订单。 */
final class WideCraftRequestAmountsTest {
    @Test void totalAndEqualsSubtractStockOnceWhileNetNewKeepsEnteredAmount() {
        var stock = StorageAmount.finite(BigInteger.valueOf(80));
        var requested = BigInteger.valueOf(100);
        assertEquals(BigInteger.valueOf(20), WideCraftRequestAmounts.resolve(requested, false, true, stock));
        assertEquals(BigInteger.valueOf(20), WideCraftRequestAmounts.resolve(requested, true, true, stock));
        assertEquals(BigInteger.valueOf(20), WideCraftRequestAmounts.resolve(requested, true, false, stock));
        assertEquals(requested, WideCraftRequestAmounts.resolve(requested, false, false, stock));
        assertEquals(BigInteger.ZERO, WideCraftRequestAmounts.resolve(BigInteger.valueOf(50), false, true, stock));
        assertEquals(BigInteger.ZERO, WideCraftRequestAmounts.resolve(BigInteger.valueOf(80), false, true, stock));
    }

    @Test void finiteWideStockAndUnlimitedSupplyRemainDistinct() {
        var stock = StorageAmount.finite(BigInteger.TEN.pow(30));
        var requested = stock.value().add(BigInteger.valueOf(17));
        assertEquals(BigInteger.valueOf(17), WideCraftRequestAmounts.resolve(requested, false, true, stock));
        assertEquals(BigInteger.ZERO, WideCraftRequestAmounts.resolve(requested, false, true, StorageAmount.infinite()));
        assertEquals(requested, WideCraftRequestAmounts.resolve(requested, false, false, StorageAmount.infinite()));
        assertThrows(IllegalArgumentException.class,
                () -> WideCraftRequestAmounts.resolve(BigInteger.ZERO, false, true, stock));
    }

    @Test void resolvedOrderCraftsOnlyTheShortfallAndLeavesStoredTargetInNetwork() {
        var target = AEItemKey.of(Items.DIAMOND);
        var raw = AEItemKey.of(Items.COAL);
        var pattern = FakePattern.of(target, 1, new FakeInput(raw, 1));
        var stock = new KeyCounter(); stock.add(target, 80); stock.add(raw, 20);
        var grid = FakeGrid.builder().storage(Map.of(target, 80L, raw, 20L)).pattern(target, pattern).build();
        var graph = GraphCompiler.compile(grid.crafting, stock, target, null);
        var needed = WideCraftRequestAmounts.resolve(BigInteger.valueOf(100), false, true,
                StorageAmount.finite(BigInteger.valueOf(80)));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(needed), false, null);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(20, result.finalAmount());
        assertEquals(20, result.state().craftsWide().getAmount(pattern).fastValue());
        assertEquals(0, result.state().usedAmount(target));
        assertEquals(20, result.state().usedAmount(raw));
    }

    @Test void resolvedWideRequestKeepsExactAmountWhenSentToPlanner() {
        var target = AEItemKey.of(Items.DIAMOND);
        var raw = AEItemKey.of(Items.COAL);
        var pattern = FakePattern.of(target, 2, new FakeInput(raw, 1));
        var stock = new KeyCounter(); stock.add(raw, Long.MAX_VALUE);
        var grid = FakeGrid.builder().storage(Map.of(raw, Long.MAX_VALUE)).pattern(target, pattern).build();
        var graph = GraphCompiler.compile(grid.crafting, stock, target, null);
        var wide = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        var requested = wide.add(BigInteger.TEN.pow(30));
        var needed = WideCraftRequestAmounts.resolve(requested, false, true,
                StorageAmount.finite(BigInteger.TEN.pow(30)));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(needed), false, null);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(wide, result.exactFinalAmount().exactValue());
        assertEquals(wide.divide(BigInteger.TWO), result.state().craftsWide().getAmount(pattern).exactValue());
    }

    @Test void cycleOrderStillBootstrapsFromOnePowderLotAfterAmountResolution() {
        var seed = AEItemKey.of(Items.WHEAT_SEEDS);
        var pure = AEItemKey.of(Items.DIAMOND);
        var powder = AEItemKey.of(Items.REDSTONE);
        var target = AEItemKey.of(Items.EMERALD);
        var grow = FakePattern.of(pure, 1, new FakeInput(seed, 1));
        var grind = FakePattern.of(powder, 1, new FakeInput(pure, 1));
        var recycle = FakePattern.of(seed, 8, new FakeInput(powder, 4));
        var finish = FakePattern.of(target, 1, new FakeInput(pure, 1));
        var stock = new KeyCounter(); stock.add(powder, 4); stock.add(target, 80);
        var grid = FakeGrid.builder().storage(Map.of(powder, 4L, target, 80L))
                .pattern(pure, grow).pattern(powder, grind).pattern(seed, recycle).pattern(target, finish).build();
        var graph = GraphCompiler.compile(grid.crafting, stock, target, null);
        var needed = WideCraftRequestAmounts.resolve(BigInteger.valueOf(100), false, true,
                StorageAmount.finite(BigInteger.valueOf(80)));
        var result = LinearPlanner.solve(graph, CalculationStrategy.REPORT_MISSING_ITEMS, PlanAmount.of(needed), false, null);
        assertFalse(result.hasDeclined(), result::declineReason);
        assertFalse(result.simulation());
        assertEquals(20, result.finalAmount());
        assertEquals(4, result.state().usedAmount(powder));
        assertEquals(0, result.state().usedAmount(target));
        assertTrue(result.state().craftsWide().getAmount(recycle).signum() > 0);
    }
}
