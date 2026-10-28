package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.NetworkStorage;
import com.cell4.common.item.InfinityItemCell;
import com.cell4.common.item.InfinityModIdCell;
import com.cell4.common.item.InfinityTagCell;
import com.cell4.common.storage.InfinityItemStorage;
import com.cell4.common.storage.InfinityModIdStorage;
import com.cell4.common.storage.InfinityTagStorage;
import com.cell4.common.util.Cell4Util;
import git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply;
import git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SDK 的同类行为统一验证，按原有集成环境显式运行。 */
final class Cell4StorageCategoryTest {
    @Test
    void verifyLoadedIntegration() {
        org.junit.jupiter.api.Assertions.assertAll("Cell4StorageCategoryTest",
                Cell4UnboundedSupplySdkCases::verify
        );
    }

    private static final class Cell4UnboundedSupplySdkCases {
        private static ItemStack cellStack(String name) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cell4", name));
            assertNotEquals(Items.AIR, item);
            return new ItemStack(item);
        }

        void itemCellSuppliesConfiguredFluidAndRespectsDrivePermissionAndRemoval() {
            var stack = cellStack("infinity_item_cell");
            InfinityItemCell.setIdentifiers(stack, List.of("minecraft:lava"));
            var cell = new InfinityItemStorage(stack);
            var lava = AEFluidKey.of(Fluids.LAVA);
            assertTrue(((UnboundedStorageSupply) cell).unboundedSupplyKeys().contains(lava));
            var network = new NetworkStorage(); var drive = new DriveWatcher(cell, () -> {});
            network.mount(0, drive);
            assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(lava));
            assertFalse(((ExactNetworkSupply) network).readExactSupply().unbounded().contains(AEItemKey.of(Items.DIAMOND)));
            drive.setExtractFiltering(true, true); drive.setAllowExtraction(false);
            assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().isEmpty());
            drive.setAllowExtraction(true); network.unmount(drive);
            assertTrue(((ExactNetworkSupply) network).readExactSupply().unbounded().isEmpty());
        }

        void tagCellUsesOriginalCombinedTagAndModConfigurationAndBlacklist() {
            var stack = cellStack("infinity_tag_cell");
            InfinityTagCell.setModIds(stack, List.of("minecraft"));
            var cell = new InfinityTagStorage(stack);
            assertTrue(((UnboundedStorageSupply) cell).unboundedSupplyKeys().contains(AEItemKey.of(Items.IRON_INGOT)));
            var custom = Cell4Util.getCustomTag(stack);
            var blacklist = new net.minecraft.nbt.ListTag(); blacklist.add(net.minecraft.nbt.StringTag.valueOf("minecraft:iron_ingot"));
            custom.put(Cell4Util.BLACKLIST_KEY, blacklist); Cell4Util.setCustomTag(stack, custom);
            var filtered = new InfinityTagStorage(stack);
            assertFalse(((UnboundedStorageSupply) filtered).unboundedSupplyKeys().contains(AEItemKey.of(Items.IRON_INGOT)));
        }

        void modCellSuppliesOnlyItsConfiguredNamespaceAndEmptyConfigurationSuppliesNothing() {
            var stack = cellStack("infinity_modid_cell");
            assertTrue(((UnboundedStorageSupply) new InfinityModIdStorage(stack)).unboundedSupplyKeys().isEmpty());
            InfinityModIdCell.setModIds(stack, List.of("minecraft"));
            var cell = new InfinityModIdStorage(stack);
            var keys = ((UnboundedStorageSupply) cell).unboundedSupplyKeys();
            assertTrue(keys.contains(AEItemKey.of(Items.IRON_INGOT)));
            assertTrue(keys.stream().allMatch(key -> key.getId().getNamespace().equals("minecraft")));
        }

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("Cell4UnboundedSupplySdkCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("itemCellSuppliesConfiguredFluidAndRespectsDrivePermissionAndRemoval", () -> new Cell4UnboundedSupplySdkCases().itemCellSuppliesConfiguredFluidAndRespectsDrivePermissionAndRemoval()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("tagCellUsesOriginalCombinedTagAndModConfigurationAndBlacklist", () -> new Cell4UnboundedSupplySdkCases().tagCellUsesOriginalCombinedTagAndModConfigurationAndBlacklist()),
                    () -> org.junit.jupiter.api.Assertions.assertAll("modCellSuppliesOnlyItsConfiguredNamespaceAndEmptyConfigurationSuppliesNothing", () -> new Cell4UnboundedSupplySdkCases().modCellSuppliesOnlyItsConfiguredNamespaceAndEmptyConfigurationSuppliesNothing())
            );
        }
    }
}
