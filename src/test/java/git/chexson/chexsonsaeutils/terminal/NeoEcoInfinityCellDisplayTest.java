package git.chexson.chexsonsaeutils.terminal;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountCollector;
import git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.*;
import git.chexson.chexsonsaeutils.terminal.storage.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class NeoEcoInfinityCellDisplayTest {
    @Test void nativeCollectorReadsExactItemsAndFluidsAfterSaturatedDecrement() {
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
        var item = AEItemKey.of(Items.COPPER_INGOT);
        var fluid = AEFluidKey.of(Fluids.WATER);
        for (var key : List.of(item, fluid)) {
            cell.insert(key, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
            cell.insert(key, Long.MAX_VALUE, Actionable.MODULATE, IActionSource.empty());
        }
        cell.extract(fluid, 64, Actionable.MODULATE, IActionSource.empty());
        ExactAmountCollector.begin();
        try {
            ExactAmountCollector.observe(cell, cell.getAvailableStacks());
            var exact = ExactAmountCollector.finish();
            var twice = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
            assertEquals(twice, exact.get(item).value());
            assertEquals(twice.subtract(BigInteger.valueOf(64)), exact.get(fluid).value());
            assertFalse(exact.get(item).infinite());
            assertEquals(Long.MAX_VALUE, cell.getAvailableStacks().get(fluid));
            var neo = new NeoEcoExactStorageAdapterImpl();
            assertFalse(neo.supports(cell));
            var network = new NeoEcoNetworkExactInventoryImpl();
            assertTrue(network.observesExactAmounts(cell));
            var baseline = network.readFrom(new appeng.api.storage.MEStorage() {
                @Override public net.minecraft.network.chat.Component getDescription() { return cell.getDescription(); }
                @Override public void getAvailableStacks(appeng.api.stacks.KeyCounter out) {
                    var contribution = cell.getAvailableStacks();
                    ExactAmountCollector.observe(cell, contribution);
                    out.addAll(contribution);
                }
            });
            var reader = new TerminalInventoryReaderImpl(List.of(), List.of(new CellExactStorageAdapterImpl(), neo), network);
            assertEquals(baseline, reader.reconcile(baseline, List.of(new StorageMount(cell, cell, cell)), null));
            assertEquals(twice, reader.aggregate(List.of(new StorageMount(cell, cell, cell)), null).get(item).value());
        } finally {
            ExactAmountCollector.abort();
        }
    }
}
