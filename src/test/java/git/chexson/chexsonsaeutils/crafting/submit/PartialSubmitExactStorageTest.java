package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PartialSubmitExactStorageTest {
    @Test void ignoreMissingSubmissionRecordsActualWideReceiptInFamilyLedger() {
        var key = AEItemKey.of(Items.COPPER_INGOT);
        var demand = BigInteger.TEN.pow(100);
        var used = new WideKeyCounter(); used.setExact(key, demand);
        var projected = new KeyCounter(); projected.add(key, Long.MAX_VALUE);
        var base = new CraftingPlan(new GenericStack(key, 1), 0, false, false,
                projected, new KeyCounter(), new KeyCounter(), Map.of());
        var plan = new WideAmountCraftingPlan(base, WideLedgerSnapshot.capture(null, used, null), null, Set.of());
        for (var have : java.util.List.of(demand, demand.divide(BigInteger.TWO))) {
            var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
            cell.insertExact(key, have, Actionable.MODULATE, IActionSource.empty());
            var pool = new WideCraftingInventory(ignored -> {});
            var arrivals = new CraftingContinuationPartialSubmit.FamilyArrivalLedger();
            assertTrue(CraftingContinuationPartialSubmit.extractAvailableInitialItemsFromStorage(
                    plan, cell, pool, IActionSource.empty(), arrivals).isEmpty());
            assertEquals(have, pool.amount(key));
            assertEquals(BigInteger.ZERO, cell.exactAmount(key));
            assertEquals(1, arrivals.families().size());
            var family = arrivals.families().iterator().next();
            assertEquals(have, family.arrived().exactValue());
            assertEquals(demand.subtract(have), family.outsideArrivals().exactValue());
        }
    }
}
