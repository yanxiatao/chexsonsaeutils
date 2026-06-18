package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PatternColorHelperTest {

    @Test
    void readsColorFromPatternDefinition() {
        TestPatternDetails pattern = new TestPatternDetails(
                AEItemKey.of(Items.PAPER),
                0xFF336699
        );

        assertEquals(0xFF336699, PatternColorHelper.getPatternColor(pattern));
    }

    @Test
    void returnsMinusOneWhenPatternHasNoColor() {
        TestPatternDetails pattern = new TestPatternDetails(AEItemKey.of(Items.PAPER), -1);

        assertEquals(-1, PatternColorHelper.getPatternColor(pattern));
    }

    @Test
    void detectsOnlyColorData() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x336699, true));

        assertTrue(PatternColorHelper.hasOnlyColorData(stack));
        assertEquals(0xFF336699, PatternColorHelper.getPatternColor(stack));
    }

    @Test
    void readsLegacyAeaDisplayColorFromCustomData() {
        ItemStack stack = new ItemStack(Items.PAPER);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag display = new CompoundTag();
            display.putInt("color", 0x336699);
            tag.put("display", display);
        });

        assertTrue(PatternColorHelper.hasOnlyColorData(stack));
        assertEquals(0xFF336699, PatternColorHelper.getPatternColor(stack));
    }

    @Test
    void rejectsLegacyColorDataWithAdditionalCustomData() {
        ItemStack stack = new ItemStack(Items.PAPER);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag display = new CompoundTag();
            display.putInt("color", 0x336699);
            tag.put("display", display);
            tag.putString("other", "value");
        });

        assertFalse(PatternColorHelper.hasOnlyColorData(stack));
    }

    @Test
    void rejectsStacksWithAdditionalData() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(0x336699, true));
        stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("x"));

        assertFalse(PatternColorHelper.hasOnlyColorData(stack));
    }

    @Test
    void ordersPreferredColorPatternsFirst() {
        TestPatternDetails blue = new TestPatternDetails(AEItemKey.of(Items.PAPER), 0xFF0000FF);
        TestPatternDetails red = new TestPatternDetails(AEItemKey.of(Items.PAPER), 0xFFFF0000);
        List<IPatternDetails> ordered = PatternColorHelper.orderPatternsByColor(List.of(blue, red), 0xFFFF0000);

        assertEquals(red, ordered.getFirst());
        assertEquals(blue, ordered.getLast());
    }

    @Test
    void prioritizesSameColorPatternsFromIndexBeforeFallbackPatterns() {
        TestPatternDetails blue = new TestPatternDetails(AEItemKey.of(Items.PAPER), 0xFF0000FF);
        TestPatternDetails red = new TestPatternDetails(AEItemKey.of(Items.PAPER), 0xFFFF0000);
        List<IPatternDetails> ordered = DyeablePatternCraftingPlanner.prioritizeSameColorPatterns(
                List.of(blue, red),
                List.of(red),
                0xFFFF0000
        );

        assertEquals(red, ordered.getFirst());
        assertEquals(blue, ordered.getLast());
    }

    private record TestPatternDetails(AEItemKey definition, int color) implements IPatternDetails,
            IPatternDetailsColorAccessor {
        @Override
        public AEItemKey getDefinition() {
            return definition;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of(new GenericStack(AEItemKey.of(Items.PAPER), 1L));
        }

        @Override
        public int chexsonsaeutils$getColor() {
            return color;
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
