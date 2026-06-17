package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.EncodedProcessingPattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildingGadgets2PatternEncoderTest {

    @Test
    void aggregatesAndSortsMaterialsByAmount() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        List<BlockState> states = List.of(stone, dirt, stone);
        Map<BlockState, List<ItemStack>> drops = Map.of(
                stone, List.of(new ItemStack(Items.COBBLESTONE, 2)),
                dirt, List.of(new ItemStack(Items.DIRT, 5))
        );

        BuildingGadgets2PatternEncodingResult result = BuildingGadgets2PatternEncoder.encode(
                states,
                BuildingGadgets2PatternEncoder.fixedDrops(drops),
                Items.PAPER,
                "build"
        );

        assertTrue(result.encoded());
        List<GenericStack> inputs = encodedInputs(result.stack());
        assertEquals(AEItemKey.of(Items.DIRT), inputs.get(0).what());
        assertEquals(5L, inputs.get(0).amount());
        assertEquals(AEItemKey.of(Items.COBBLESTONE), inputs.get(1).what());
        assertEquals(4L, inputs.get(1).amount());
    }

    @Test
    void sourceFluidUsesOneBucketAmount() {
        BuildingGadgets2PatternEncodingResult result = BuildingGadgets2PatternEncoder.encode(
                List.of(Blocks.WATER.defaultBlockState()),
                BuildingGadgets2PatternEncoder.fixedDrops(Map.of()),
                Items.PAPER,
                "fluid"
        );

        assertTrue(result.encoded());
        List<GenericStack> inputs = encodedInputs(result.stack());
        assertEquals(AEFluidKey.of(Blocks.WATER.defaultBlockState().getFluidState().getType()), inputs.getFirst().what());
        assertEquals(BuildingGadgets2PatternEncoder.SOURCE_FLUID_AMOUNT, inputs.getFirst().amount());
    }

    @Test
    void emptyBuildListDoesNotEncodePattern() {
        BuildingGadgets2PatternEncodingResult result = BuildingGadgets2PatternEncoder.encode(
                List.of(),
                BuildingGadgets2PatternEncoder.fixedDrops(Map.of()),
                Items.PAPER,
                "empty"
        );

        assertFalse(result.encoded());
        assertEquals(0, result.totalInputTypes());
        assertEquals(0, result.encodedInputTypes());
    }

    @Test
    void limitsInputsToAe2ProcessingPatternCapacity() {
        List<BlockState> states = new ArrayList<>();
        for (int index = 0; index < 90; index++) {
            states.add(Blocks.STONE.defaultBlockState());
        }
        AtomicInteger index = new AtomicInteger();

        BuildingGadgets2PatternEncodingResult result = BuildingGadgets2PatternEncoder.encode(
                states,
                state -> List.of(uniqueStack(index.getAndIncrement())),
                Items.PAPER,
                "large"
        );

        assertTrue(result.encoded());
        assertTrue(result.truncated());
        assertEquals(90, result.totalInputTypes());
        assertEquals(BuildingGadgets2PatternEncoder.MAX_PROCESSING_INPUTS, result.encodedInputTypes());
        assertEquals(BuildingGadgets2PatternEncoder.MAX_PROCESSING_INPUTS, encodedInputs(result.stack()).size());
    }

    @Test
    void outputUsesRequestedTemplateItem() {
        BuildingGadgets2PatternEncodingResult result = BuildingGadgets2PatternEncoder.encode(
                List.of(Blocks.STONE.defaultBlockState()),
                BuildingGadgets2PatternEncoder.fixedDrops(Map.of(
                        Blocks.STONE.defaultBlockState(), List.of(new ItemStack(Items.COBBLESTONE))
                )),
                Items.PAPER,
                "named-template"
        );

        List<GenericStack> outputs = encodedOutputs(result.stack());
        AEItemKey outputKey = assertInstanceOf(AEItemKey.class, outputs.getFirst().what());
        assertEquals(Items.PAPER, outputKey.getItem());
        assertEquals(1L, outputs.getFirst().amount());
    }

    private static List<GenericStack> encodedInputs(ItemStack stack) {
        EncodedProcessingPattern encoded = stack.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        return encoded == null ? List.of() : encoded.sparseInputs().stream()
                .filter(input -> input != null)
                .toList();
    }

    private static List<GenericStack> encodedOutputs(ItemStack stack) {
        EncodedProcessingPattern encoded = stack.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        return encoded == null ? List.of() : encoded.sparseOutputs().stream()
                .filter(output -> output != null)
                .toList();
    }

    private static ItemStack uniqueStack(int index) {
        ItemStack stack = new ItemStack(Items.STONE, index + 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("bg2-input-" + index));
        return stack;
    }
}
