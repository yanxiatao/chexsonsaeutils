package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecursiveTaskOrderingTest {

    private static final AEItemKey SEED = AEItemKey.of(Items.IRON_INGOT);
    private static final AEItemKey BRIDGE = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.CLOCK);

    @Test
    void consumerKeepsConfiguredInternalReserve() {
        var consumer = FakePattern.of(OUTPUT, 1L, new FakeInput(SEED, 1L));
        var internal = counter(SEED, 4L);
        var inventory = inventory(SEED, 4L);

        assertEquals(4L, RecursiveTaskOrdering.requiredPostExecutionReserve(
                consumer, internal, Map.of(consumer, progress(1L))).get(SEED));
        assertTrue(RecursiveTaskOrdering.shouldDeferConsumer(
                consumer, internal, inventory, Map.of(consumer, progress(1L))));
    }

    @Test
    void pendingReturnPathMayUseSeedToUnlockProducer() {
        var consumer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(SEED, 1L)},
                new GenericStack(BRIDGE, 1L)
        );
        var producer = FakePattern.withOutputs(
                new FakeInput[]{new FakeInput(BRIDGE, 1L)},
                new GenericStack(SEED, 2L),
                new GenericStack(OUTPUT, 1L)
        );
        Map<appeng.api.crafting.IPatternDetails, RecursiveTaskOrdering.ParallelTaskProgressView> tasks =
                Map.of(consumer, progress(1L), producer, progress(1L));
        var internal = counter(SEED, 1L);
        var inventory = inventory(SEED, 1L);

        assertEquals(0L, RecursiveTaskOrdering.requiredPostExecutionReserve(
                consumer, internal, tasks).get(SEED));
        assertFalse(RecursiveTaskOrdering.shouldDeferConsumer(
                consumer, internal, inventory, tasks));
    }

    private static RecursiveTaskOrdering.ParallelTaskProgressView progress(long value) {
        return () -> value;
    }

    private static KeyCounter counter(AEItemKey key, long amount) {
        var counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    private static ListCraftingInventory inventory(AEItemKey key, long amount) {
        var inventory = new ListCraftingInventory(ignored -> {
        });
        inventory.insert(key, amount, Actionable.MODULATE);
        return inventory;
    }
}
