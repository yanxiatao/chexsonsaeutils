package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import appeng.items.contents.CellConfig;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.NetworkStorage;
import cn.dancingsnow.neoecoae.impl.storage.ECOCreativeCell;
import cn.dancingsnow.neoecoae.impl.storage.ECOInfiniteResourceCell;
import com.glodblock.github.extendedae.common.inventory.InfinityCellInventory;
import com.glodblock.github.extendedae.common.items.ItemInfinityCell;
import git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply;
import git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoInfinityCellInventory;
import java.math.BigInteger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原始模组库存、Mixin 身份与真实网络挂载过滤共同验证，有限库存不得被当作生成器。 */
final class NeoEcoUnboundedSupplySdkTest {
    private static final AEKey LAVA = AEFluidKey.of(Fluids.LAVA);

    @Test void extendedAeCustomFluidCellRetainsUnboundedIdentityThroughDrive() {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("extendedae", "infinity_water_cell"));
        assertInstanceOf(ItemInfinityCell.class, item);
        var cell = new InfinityCellInventory(new ItemStack(item));
        var water = AEFluidKey.of(Fluids.WATER);
        assertEquals((long) Integer.MAX_VALUE * water.getAmountPerUnit(), cell.getAvailableStacks().get(water));
        checkMountedSupply(new DriveWatcher(cell, () -> {}), water);
    }

    @Test void neoEcoCreativeCellRetainsSupplyIdentityThroughDrive() {
        var stack = AEItems.CREATIVE_CELL.stack();
        CellConfig.create(stack).setStack(0, new appeng.api.stacks.GenericStack(AEItemKey.of(Items.IRON_INGOT), 1));
        var cell = ECOCreativeCell.Handler.INSTANCE.getCellInventory(stack, null);
        assertNotNull(cell);
        checkMountedSupply(new DriveWatcher(cell, () -> {}), AEItemKey.of(Items.IRON_INGOT));
    }

    @Test void neoEcoBaseResourceMatrixDeclaresUnboundedFluidSupply() {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("neoecoae", "eco_infinite_item_cell"));
        var cell = new ECOInfiniteResourceCell(new ItemStack(item), null);
        checkMountedSupply(new DriveWatcher(cell, () -> {}), LAVA);
    }

    @Test void unlimitedCapacityWithFiniteContentsDoesNotDeclareUnboundedSupply() {
        var cell = new NeoEcoInfinityCellInventory(new ItemStack(Items.STICK), null);
        BigInteger stored = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN);
        cell.insertExact(LAVA, stored, Actionable.MODULATE, IActionSource.empty());
        var network = new NetworkStorage(); network.mount(0, new DriveWatcher(cell, () -> {}));
        var snapshot = ((ExactNetworkSupply) network).readExactSupply();
        assertTrue(snapshot.unbounded().isEmpty());
        assertEquals(stored, snapshot.finite().get(LAVA));
        assertEquals(stored, cell.exactAmount(LAVA));
    }

    private static void checkMountedSupply(DriveWatcher drive, AEKey key) {
        var network = new NetworkStorage(); network.mount(0, drive);
        assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
        drive.setExtractFiltering(true, true);
        drive.setAllowExtraction(false);
        assertFalse(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
        drive.setAllowExtraction(true);
        assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
        network.unmount(drive);
        assertFalse(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(key));
    }
}
