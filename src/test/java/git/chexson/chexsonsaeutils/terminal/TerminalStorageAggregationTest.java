package git.chexson.chexsonsaeutils.terminal;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.cell.InfinityCellInventory;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class TerminalStorageAggregationTest {
    private static final AEKey COPPER = AEItemKey.of(Items.COPPER_INGOT);
    private static final AEKey GOLD = AEItemKey.of(Items.GOLD_INGOT);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private final TerminalInventoryReaderImpl reader = new TerminalInventoryReaderImpl(
            List.of(), List.of(new CellExactStorageAdapterImpl()));

    private InfinityCellInventory cell() {
        var cell = new InfinityCellInventory(new ItemStack(Items.STICK), null);
        cell.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        cell.insert(COPPER, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        cell.insert(GOLD, 7, Actionable.MODULATE, IActionSource.empty());
        return cell;
    }
    @Test void realCellsSumBeyondLongAndExposeSaturatedDecrement() {
        var a = cell(); var b = cell();
        var mounts = List.of(new StorageMount(a, a, a), new StorageMount(b, b, b));
        assertEquals(MAX.multiply(BigInteger.valueOf(4)), reader.aggregate(mounts, null).get(COPPER).value());
        var saved = a.readExactSnapshot();
        a.extract(COPPER, 64, Actionable.MODULATE, IActionSource.empty());
        assertEquals(Long.MAX_VALUE, a.getAvailableStacks().get(COPPER));
        assertEquals(MAX.multiply(BigInteger.valueOf(4)).subtract(BigInteger.valueOf(64)),
                reader.aggregate(mounts, null).get(COPPER).value());
        assertEquals(MAX.multiply(BigInteger.TWO), saved.amount(COPPER));
    }
    @Test void adapterReusesUnchangedRevisionAndInvalidatesAfterExactChange() {
        var cell = cell(); var adapter = new CellExactStorageAdapterImpl();
        var first = adapter.read(cell, null);
        assertSame(first, adapter.read(cell, null));
        cell.extract(COPPER, 64, Actionable.MODULATE, IActionSource.empty());
        var changed = adapter.read(cell, null);
        assertNotSame(first, changed);
        assertEquals(first.get(COPPER).value().subtract(BigInteger.valueOf(64)), changed.get(COPPER).value());
    }
    @Test void wrapperFiltersKeysAndDuplicateDiscoveryDoesNotCountTwice() {
        var cell = cell(); var visible = visible(Map.of(COPPER, Long.MAX_VALUE));
        var mount = new StorageMount(cell, cell, visible);
        var amounts = reader.aggregate(List.of(mount, mount), null);
        assertEquals(MAX.multiply(BigInteger.TWO), amounts.get(COPPER).value());
        assertFalse(amounts.containsKey(GOLD));
        assertEquals(7, cell.getAvailableStacks().get(GOLD));
    }
    @Test void quantityTransformingWrapperAndAdapterConflictRejectInsteadOfLying() {
        var cell = cell(); var bad = new StorageMount(cell, cell, visible(Map.of(COPPER, 5L)));
        assertThrows(IllegalStateException.class, () -> reader.aggregate(List.of(bad), null));
        var conflicting = new TerminalInventoryReaderImpl(List.of(),
                List.of(new CellExactStorageAdapterImpl(), new CellExactStorageAdapterImpl()));
        assertThrows(IllegalStateException.class, () -> conflicting.aggregate(List.of(new StorageMount(cell, cell, cell)), null));
    }
    @Test void reconcileUsesUnsaturatedPerMountBaselineAndKeepsOtherSources() {
        var cell = cell(); var mount = new StorageMount(cell, cell, cell);
        var domain = BigInteger.TEN.pow(100);
        var baseline = Map.of(COPPER, StorageAmount.finite(domain.add(MAX).add(BigInteger.valueOf(64))),
                GOLD, StorageAmount.finite(BigInteger.valueOf(7)));
        var result = reader.reconcile(baseline, List.of(mount), null);
        assertEquals(domain.add(MAX.multiply(BigInteger.TWO)).add(BigInteger.valueOf(64)), result.get(COPPER).value());
        assertEquals(domain.add(MAX).add(BigInteger.valueOf(64)), baseline.get(COPPER).value());
        assertEquals(StorageAmount.infinite(), reader.reconcile(Map.of(COPPER, StorageAmount.infinite(),
                GOLD, StorageAmount.finite(BigInteger.valueOf(7))), List.of(mount), null).get(COPPER));
    }
    @Test void unknownInventoryDoesNotBecomeConfirmedLongQuantity() {
        var unknown = visible(Map.of(COPPER, Long.MAX_VALUE));
        assertTrue(reader.aggregate(List.of(new StorageMount(unknown, unknown, unknown)), null).isEmpty());
    }
    @Test void locatedUnknownSourceOnlyMarksItsOwnKeys() {
        var cell = cell();
        var known = reader.aggregate(List.of(new StorageMount(cell, cell, cell)), null);
        var cached = new KeyCounter();
        cached.add(COPPER, Long.MAX_VALUE);
        cached.add(GOLD, 10);
        var snapshot = reader.confirmReadings(known, known, cached, java.util.Set.of(GOLD), true,
                java.util.Set.of(COPPER));
        assertNull(snapshot.unverified().get(COPPER));
        assertEquals(MAX.multiply(BigInteger.TWO), snapshot.amounts().get(COPPER).value());
        assertEquals(10L, snapshot.unverified().get(GOLD));
        assertEquals(BigInteger.valueOf(7), snapshot.amounts().get(GOLD).value());
        assertTrue(snapshot.craftables().contains(COPPER));
        assertFalse(snapshot.covered());
    }
    @Test void saturatedNetworkLongCannotProveUnknownSameKeyIsAbsent() {
        var cell = cell();
        var known = reader.aggregate(List.of(new StorageMount(cell, cell, cell)), null);
        var snapshot = reader.confirmReadings(known, known, cell.getAvailableStacks(), java.util.Set.of(),
                false, java.util.Set.of());
        assertEquals(Long.MAX_VALUE, snapshot.unverified().get(COPPER));
        assertEquals(MAX.multiply(BigInteger.TWO), snapshot.amounts().get(COPPER).value());
    }
    @Test void disappearedUnknownKeyAndCraftableZeroDoNotRemainUnverified() {
        var empty = reader.confirmReadings(Map.of(), Map.of(), new KeyCounter(), java.util.Set.of(),
                false, java.util.Set.of(COPPER));
        assertTrue(empty.amounts().isEmpty());
        assertTrue(empty.unverified().isEmpty());
        assertTrue(empty.craftables().contains(COPPER));
    }
    private static MEStorage visible(Map<AEKey, Long> counts) {
        return new MEStorage() {
            @Override public Component getDescription() { return Component.literal("只读可见库存"); }
            @Override public void getAvailableStacks(KeyCounter out) { counts.forEach(out::add); }
            @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                throw new AssertionError("显示不得插入物品");
            }
            @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                throw new AssertionError("显示不得提取物品");
            }
        };
    }
}
