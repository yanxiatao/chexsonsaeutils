package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 通过实际提取验证池化状态的缓存重置、父库存切换和嵌套隔离。 */
final class FastSimStatePoolTest {
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    @Test
    void reusedStateDiscardsPreviousChangesAndReadsTheNewParent() {
        var originalParent = inventory(10L);
        var state = FastSimStatePool.acquire(originalParent);
        assertEquals(4L, state.extract(IRON, 4L, Actionable.MODULATE));
        state.insert(IRON, 100L, Actionable.MODULATE);
        FastSimStatePool.release(state);

        var newParent = inventory(20L);
        var reused = FastSimStatePool.acquire(newParent);
        try {
            assertSame(state, reused);
            assertEquals(20L, reused.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(20L, reused.extract(IRON, Long.MAX_VALUE, Actionable.MODULATE));
            assertEquals(0L, reused.extract(IRON, 1L, Actionable.SIMULATE));
            assertEquals(10L, originalParent.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(20L, newParent.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
        } finally {
            FastSimStatePool.release(reused);
        }
    }

    @Test
    void nestedStatesNeverShareChanges() {
        var parent = inventory(10L);
        var outer = FastSimStatePool.acquire(parent);
        var inner = FastSimStatePool.acquire(outer);
        try {
            assertNotSame(outer, inner);
            assertEquals(3L, inner.extract(IRON, 3L, Actionable.MODULATE));
            assertEquals(7L, inner.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(10L, outer.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            inner.applyDiff(outer);
            assertEquals(7L, outer.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
            assertEquals(10L, parent.extract(IRON, Long.MAX_VALUE, Actionable.SIMULATE));
        } finally {
            FastSimStatePool.release(inner);
            FastSimStatePool.release(outer);
        }
    }

    private static ListCraftingInventory inventory(long amount) {
        var inventory = new ListCraftingInventory(ignored -> { });
        inventory.insert(IRON, amount, Actionable.MODULATE);
        return inventory;
    }
}
