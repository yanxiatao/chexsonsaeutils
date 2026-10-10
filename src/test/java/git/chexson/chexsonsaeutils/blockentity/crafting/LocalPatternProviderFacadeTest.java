package git.chexson.chexsonsaeutils.blockentity.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

class LocalPatternProviderFacadeTest {

    @Test
    void colorDoesNotChangeUniqueProducerSetOrOrder() {
        var output = AEItemKey.of(Items.IRON_INGOT);
        var uncolored = FakePattern.colored(
                -1,
                AEItemKey.of(Items.PAPER),
                List.of(new GenericStack(output, 1)));
        var red = FakePattern.colored(
                0xFFFF0000,
                FakePlanGrid.dyedKey(Items.PAPER, 0xFFFF0000),
                List.of(new GenericStack(output, 1)));
        var blue = FakePattern.colored(
                0xFF0000FF,
                FakePlanGrid.dyedKey(Items.PAPER, 0xFF0000FF),
                List.of(new GenericStack(output, 1)));
        var distinct = FakePattern.colored(
                -1,
                AEItemKey.of(Items.COBBLESTONE),
                List.of(new GenericStack(output, 1)));

        var unique = LocalPatternProviderFacade.uniquePatternsInOrder(
                List.of(red, uncolored, distinct, blue));

        assertEquals(2, unique.size());
        assertSame(red, unique.get(0));
        assertSame(distinct, unique.get(1));
    }
}
