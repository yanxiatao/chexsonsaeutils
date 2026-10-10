package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.stacks.AEItemKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class GraphCompilerCacheKeyTest {

    @Test
    void collidingStockHashesStillUseExactComponentKeySet() {
        AEItemKey first = collidingNameKey("Aa");
        AEItemKey second = collidingNameKey("BB");

        assertNotEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        var firstKey = new GraphCompiler.CacheKey(null, 1L, AEItemKey.of(Items.CLOCK), Set.of(first));
        var secondKey = new GraphCompiler.CacheKey(null, 1L, AEItemKey.of(Items.CLOCK), Set.of(second));

        assertNotEquals(firstKey, secondKey);
    }

    private static AEItemKey collidingNameKey(String name) {
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }
}
