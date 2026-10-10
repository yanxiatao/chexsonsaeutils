package git.chexson.chexsonsaeutils.crafting.recursive;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RingIdTest {

    private static AEItemKey key(Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    private static FakePattern pattern(Item definitionItem, Item outputItem) {
        return FakePattern.colored(
                -1,
                AEItemKey.of(new ItemStack(definitionItem)),
                List.of(new GenericStack(key(outputItem), 1L))
        );
    }

    @Test
    void sameEntrySameMembersMatchRegardlessOfIterationOrder() {
        var a = pattern(Items.STONE, Items.PAPER);
        var b = pattern(Items.COBBLESTONE, Items.IRON_INGOT);
        var entryPoint = key(Items.PAPER);

        assertEquals(
                RingId.of(entryPoint, List.of(a, b)),
                RingId.of(entryPoint, List.of(b, a)));
    }

    @Test
    void differentTopologiesProduceDifferentIds() {
        var entryPoint = key(Items.PAPER);
        var a = pattern(Items.STONE, Items.PAPER);
        var b = pattern(Items.COBBLESTONE, Items.IRON_INGOT);

        assertNotEquals(RingId.of(entryPoint, List.of(a)), RingId.of(entryPoint, List.of(b)));
        assertNotEquals(RingId.of(entryPoint, List.of(a)), RingId.of(entryPoint, List.of(a, b)));
    }

    @Test
    void colorDoesNotDistinguishIds() {
        var uncolored = FakePattern.colored(
                -1,
                key(Items.PAPER),
                List.of(new GenericStack(key(Items.PAPER), 1L)));
        var red = FakePattern.colored(
                0xFFFF0000,
                git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.dyedKey(Items.PAPER, 0xFFFF0000),
                List.of(new GenericStack(key(Items.PAPER), 1L)));
        var blue = FakePattern.colored(
                0xFF0000FF,
                git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.dyedKey(Items.PAPER, 0xFF0000FF),
                List.of(new GenericStack(key(Items.PAPER), 1L)));
        var uncoloredId = RingId.of(key(Items.PAPER), List.of(uncolored));

        assertEquals(uncoloredId, RingId.of(key(Items.PAPER), List.of(red)));
        assertEquals(uncoloredId, RingId.of(key(Items.PAPER), List.of(blue)));
        assertEquals(
                RingId.of(key(Items.PAPER), List.of(uncolored, red)),
                RingId.of(key(Items.PAPER), List.of(red, blue)));
    }

    @Test
    void nullMembersAreStableAndDistinctFromNonEmpty() {
        var entryPoint = key(Items.PAPER);
        var empty = RingId.of(entryPoint, null);

        assertEquals(empty, RingId.of(entryPoint, null));
        assertEquals(empty, RingId.of(entryPoint, List.of()));
        assertNotEquals(empty, RingId.of(entryPoint, List.of(pattern(Items.STONE, Items.PAPER))));
    }
}
