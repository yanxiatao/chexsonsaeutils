package git.chexson.chexsonsaeutils.integration.buildinggadgets2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.direwolf20.buildinggadgets2.setup.Registration;
import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts Building Gadgets 2 copy-paste material requirements into a standard AE2 processing pattern.
 */
public final class BuildingGadgets2PatternEncoder {

    public static final int MAX_PROCESSING_INPUTS = 81;
    public static final long SOURCE_FLUID_AMOUNT = 1_000L;

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildingGadgets2PatternEncoder() {
    }

    public static BuildingGadgets2PatternEncodingResult encode(
            Iterable<BlockState> states,
            BuildingGadgets2StateDrops drops,
            @Nullable String templateName
    ) {
        return encode(states, drops, Registration.Template.get(), templateName);
    }

    static BuildingGadgets2PatternEncodingResult encode(
            Iterable<BlockState> states,
            BuildingGadgets2StateDrops drops,
            ItemLike outputItem,
            @Nullable String templateName
    ) {
        Map<AEKey, Long> counts = new LinkedHashMap<>();
        int skippedStates = 0;

        for (BlockState state : states) {
            if (state == null || state.isAir()) {
                skippedStates++;
                continue;
            }
            Map<AEKey, Long> stateRequirements = collectStateRequirements(state, drops);
            if (stateRequirements.isEmpty()) {
                skippedStates++;
                LOGGER.debug("BG2 pattern encoding skipped state {} because it produced no encodable material", state);
                continue;
            }
            stateRequirements.forEach((key, amount) -> counts.merge(key, amount, Long::sum));
        }

        if (counts.isEmpty()) {
            LOGGER.warn("BG2 pattern encoding skipped template '{}' because no encodable materials were found",
                    templateName);
            return new BuildingGadgets2PatternEncodingResult(ItemStack.EMPTY, 0, 0, skippedStates);
        }

        List<GenericStack> sortedInputs = counts.entrySet().stream()
                .map(entry -> new GenericStack(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparingLong(GenericStack::amount)
                        .reversed()
                        .thenComparing(stack -> stack.what().toString()))
                .toList();
        List<GenericStack> encodedInputs = sortedInputs.stream()
                .limit(MAX_PROCESSING_INPUTS)
                .toList();

        if (sortedInputs.size() > MAX_PROCESSING_INPUTS) {
            LOGGER.warn(
                    "BG2 pattern encoding truncated template '{}' from {} input types to AE2 limit {}",
                    templateName,
                    sortedInputs.size(),
                    MAX_PROCESSING_INPUTS
            );
        }

        ItemStack output = createTemplateOutput(outputItem, templateName);
        GenericStack outputStack = GenericStack.fromItemStack(output);
        if (outputStack == null) {
            LOGGER.warn("BG2 pattern encoding failed because template output '{}' could not be encoded", output);
            return new BuildingGadgets2PatternEncodingResult(
                    ItemStack.EMPTY,
                    sortedInputs.size(),
                    encodedInputs.size(),
                    skippedStates
            );
        }

        ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(
                encodedInputs,
                List.of(outputStack)
        );
        return new BuildingGadgets2PatternEncodingResult(
                pattern,
                sortedInputs.size(),
                encodedInputs.size(),
                skippedStates
        );
    }

    private static Map<AEKey, Long> collectStateRequirements(
            BlockState state,
            BuildingGadgets2StateDrops drops
    ) {
        if (!state.getFluidState().isEmpty() && state.getFluidState().isSource()) {
            return Map.of(AEFluidKey.of(state.getFluidState().getType()), SOURCE_FLUID_AMOUNT);
        }

        List<ItemStack> droppedStacks = drops.dropsFor(state);
        if (droppedStacks.isEmpty()) {
            return Map.of();
        }

        Map<AEKey, Long> requirements = new LinkedHashMap<>();
        for (ItemStack stack : droppedStacks) {
            if (stack.isEmpty()) {
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) {
                LOGGER.debug("BG2 pattern encoding skipped unencodable drop {}", stack);
                continue;
            }
            requirements.merge(key, (long) stack.getCount(), Long::sum);
        }
        return requirements;
    }

    private static ItemStack createTemplateOutput(ItemLike outputItem, @Nullable String templateName) {
        ItemStack output = new ItemStack(outputItem);
        if (templateName != null && !templateName.isBlank()) {
            output.set(DataComponents.CUSTOM_NAME, Component.literal(templateName));
        }
        return output;
    }

    public static BuildingGadgets2StateDrops fixedDrops(Map<BlockState, List<ItemStack>> dropsByState) {
        return state -> new ArrayList<>(dropsByState.getOrDefault(state, List.of()));
    }
}
