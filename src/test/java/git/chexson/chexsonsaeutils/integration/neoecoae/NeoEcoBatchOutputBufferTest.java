package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NeoEcoBatchOutputBufferTest {

    private static final AEItemKey OUTPUT = AEItemKey.of(Items.CLOCK);
    private static final AEItemKey REMAINDER = AEItemKey.of(Items.BUCKET);

    @Test
    void oneDispatchBuffersEveryExecutionAndRemainder() {
        var target = new KeyCounter();

        assertTrue(NeoEcoBatchOutputBuffer.append(
                target,
                List.of(new GenericStack(OUTPUT, 2L)),
                List.of(new GenericStack(REMAINDER, 1L)),
                1024L
        ));
        assertEquals(2048L, target.get(OUTPUT));
        assertEquals(1024L, target.get(REMAINDER));
    }

    @Test
    void keepsSiblingComponentOutputsSeparate() {
        AEItemKey first = namedOutput("first");
        AEItemKey second = namedOutput("second");
        var target = new KeyCounter();

        assertTrue(NeoEcoBatchOutputBuffer.append(
                target,
                List.of(new GenericStack(first, 1L), new GenericStack(second, 2L)),
                List.of(),
                3L
        ));
        assertEquals(3L, target.get(first));
        assertEquals(6L, target.get(second));
        assertEquals(2, target.size());
    }

    private static AEItemKey namedOutput(String name) {
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }

    @Test
    void rejectsNonPositiveExecutionCount() {
        assertFalse(NeoEcoBatchOutputBuffer.append(
                new KeyCounter(),
                List.of(new GenericStack(OUTPUT, 1L)),
                List.of(),
                0L
        ));
    }

    @Test
    void accumulatedOutputSaturatesInsteadOfOverflowing() {
        var target = new KeyCounter();
        target.add(OUTPUT, Long.MAX_VALUE - 5L);

        assertTrue(NeoEcoBatchOutputBuffer.append(
                target,
                List.of(new GenericStack(OUTPUT, 4L)),
                List.of(),
                2L
        ));
        assertEquals(Long.MAX_VALUE, target.get(OUTPUT));
    }
}
