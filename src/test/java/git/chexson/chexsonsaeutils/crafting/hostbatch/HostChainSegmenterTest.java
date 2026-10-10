package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.stacks.AEItemKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostChainSegmenterTest {

    @Test
    void componentSiblingWithoutExactDependencyStaysInSeparateSegment() {
        AEItemKey version1 = componentKey(Items.AMETHYST_SHARD, "version-1");
        AEItemKey version2 = componentKey(Items.AMETHYST_SHARD, "version-2");
        AEItemKey version3 = componentKey(Items.AMETHYST_SHARD, "version-3");
        AEItemKey wrongVersion2 = componentKey(Items.AMETHYST_SHARD, "wrong-version-2");

        var segments = HostChainSegmenter.splitPerPatternFormalAggregationSegments(Map.of(
                version2, List.of(version1, AEItemKey.of(Items.GOLD_INGOT)),
                version3, List.of(version2, AEItemKey.of(Items.DIAMOND)),
                wrongVersion2, List.of(version1, AEItemKey.of(Items.REDSTONE))
        ));

        assertEquals(2, segments.size());
        assertTrue(segments.contains(Set.of(version2, version3)));
        assertTrue(segments.contains(Set.of(wrongVersion2)));
    }

    private static AEItemKey componentKey(Item item, String value) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(value));
        return AEItemKey.of(stack);
    }
}
