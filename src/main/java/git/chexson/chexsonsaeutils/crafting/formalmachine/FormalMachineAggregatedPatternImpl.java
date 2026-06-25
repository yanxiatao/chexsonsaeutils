package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.EncodedPatternItem;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AECraftingPattern;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineAggregationStep;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineHostLocator;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Host-bound virtual AE2 crafting pattern that exposes an aggregated formal-machine subgraph.
 */
public final class FormalMachineAggregatedPatternImpl implements FormalMachineAggregatedPattern {

    public static final String CUSTOM_DATA_ROOT = "chexsonsaeutils_formal_virtual_pattern";
    private static final String NBT_HOST = "host";
    private static final String NBT_INPUTS = "inputs";
    private static final String NBT_OUTPUTS = "outputs";
    private static final String NBT_AGGREGATED_INPUTS = "aggregatedInputs";
    private static final String NBT_AGGREGATED_OUTPUTS = "aggregatedOutputs";
    private static final String NBT_AGGREGATED_REMAINDERS = "aggregatedRemainders";
    private static final String NBT_STEPS = "steps";
    private static final String NBT_TOTAL_TICKS = "totalTicks";
    private static final int CRAFTING_GRID_SLOTS = 9;

    private final AEItemKey definition;
    private final IPatternDetails basePattern;
    private final FormalMachineHostLocator hostLocator;
    private final IPatternDetails.IInput[] inputs;
    private final List<GenericStack> outputs;
    private final List<GenericStack> aggregatedInputs;
    private final List<GenericStack> aggregatedOutputs;
    private final List<GenericStack> aggregatedRemainders;
    private final List<FormalMachineAggregationStep> steps;
    private final int totalTicks;

    private FormalMachineAggregatedPatternImpl(
            AEItemKey definition,
            IPatternDetails basePattern,
            FormalMachineHostLocator hostLocator,
            List<GenericStack> exposedInputStacks,
            List<GenericStack> outputStacks,
            List<GenericStack> aggregatedInputs,
            List<GenericStack> aggregatedOutputs,
            List<GenericStack> aggregatedRemainders,
            List<FormalMachineAggregationStep> steps,
            int totalTicks
    ) {
        this.definition = Objects.requireNonNull(definition);
        this.basePattern = Objects.requireNonNull(basePattern);
        this.hostLocator = Objects.requireNonNull(hostLocator);
        this.inputs = buildInputs(exposedInputStacks);
        this.outputs = copyStacks(outputStacks);
        this.aggregatedInputs = copyStacks(aggregatedInputs);
        this.aggregatedOutputs = copyStacks(aggregatedOutputs);
        this.aggregatedRemainders = copyStacks(aggregatedRemainders);
        this.steps = List.copyOf(steps == null ? List.of() : steps);
        this.totalTicks = Math.max(1, totalTicks);
    }

    public static @Nullable FormalMachineAggregatedPatternImpl create(
            IPatternDetails basePattern,
            FormalMachineHostLocator hostLocator,
            List<GenericStack> inputStacks,
            List<GenericStack> aggregatedOutputs,
            List<GenericStack> aggregatedRemainders,
            List<FormalMachineAggregationStep> steps,
            int totalTicks
    ) {
        if (basePattern == null
                || hostLocator == null
                || aggregatedOutputs == null
                || aggregatedOutputs.isEmpty()
                || steps == null
                || steps.isEmpty()) {
            return null;
        }
        ItemStack encodedDefinition = basePattern.getDefinition().toStack();
        if (encodedDefinition.isEmpty()) {
            return null;
        }
        List<GenericStack> copiedAggregatedInputs = copyStacks(inputStacks);
        List<GenericStack> copiedExposedInputs = buildVirtualInputs(copiedAggregatedInputs);
        List<GenericStack> copiedOutputs = copyStacks(mergeStacks(aggregatedOutputs, aggregatedRemainders));
        List<GenericStack> copiedAggregatedOutputs = copyStacks(aggregatedOutputs);
        List<GenericStack> copiedAggregatedRemainders = copyStacks(aggregatedRemainders);
        writeMetadata(
                encodedDefinition,
                hostLocator,
                copiedExposedInputs,
                copiedOutputs,
                copiedAggregatedInputs,
                copiedAggregatedOutputs,
                copiedAggregatedRemainders,
                steps,
                totalTicks
        );
        AEItemKey definition = AEItemKey.of(encodedDefinition);
        if (definition == null) {
            return null;
        }
        return new FormalMachineAggregatedPatternImpl(
                definition,
                basePattern,
                hostLocator,
                copiedExposedInputs,
                copiedOutputs,
                copiedAggregatedInputs,
                copiedAggregatedOutputs,
                copiedAggregatedRemainders,
                steps,
                totalTicks
        );
    }

    // ponytail: direct instanceof check avoids recursion through PatternDetailsHelper decoder loop
    public static boolean isEncodedDefinition(@Nullable ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() instanceof EncodedPatternItem
                && getMetadataTag(stack) != null;
    }

    public static boolean isEncodedDefinition(@Nullable AEItemKey definition) {
        if (definition == null) {
            return false;
        }
        ItemStack stack = definition.toStack();
        return isEncodedDefinition(stack);
    }

    public static @Nullable FormalMachineAggregatedPatternImpl decode(
            @Nullable AEItemKey definition,
            @Nullable Level level
    ) {
        if (definition == null || level == null) {
            return null;
        }
        CompoundTag metadata = getMetadataTag(definition);
        if (metadata == null) {
            return null;
        }
        IPatternDetails basePattern = decodeBasePattern(definition, level);
        if (basePattern == null) {
            return null;
        }
        FormalMachineHostLocator locator = FormalMachineHostLocator.readFromTag(metadata.getCompound(NBT_HOST));
        if (locator == null) {
            return null;
        }
        return new FormalMachineAggregatedPatternImpl(
                definition,
                basePattern,
                locator,
                readStacks(metadata.getList(NBT_INPUTS, Tag.TAG_COMPOUND)),
                readStacks(metadata.getList(NBT_OUTPUTS, Tag.TAG_COMPOUND)),
                readStacks(metadata.getList(NBT_AGGREGATED_INPUTS, Tag.TAG_COMPOUND)),
                readStacks(metadata.getList(NBT_AGGREGATED_OUTPUTS, Tag.TAG_COMPOUND)),
                readStacks(metadata.getList(NBT_AGGREGATED_REMAINDERS, Tag.TAG_COMPOUND)),
                readSteps(metadata.getList(NBT_STEPS, Tag.TAG_COMPOUND)),
                metadata.getInt(NBT_TOTAL_TICKS)
        );
    }

    @Override
    public IPatternDetails basePattern() {
        return basePattern;
    }

    @Override
    public FormalMachineHostLocator hostLocator() {
        return hostLocator;
    }

    @Override
    public List<GenericStack> aggregatedInputs() {
        return aggregatedInputs;
    }

    @Override
    public List<GenericStack> aggregatedOutputs() {
        return aggregatedOutputs;
    }

    @Override
    public List<GenericStack> aggregatedRemainders() {
        return aggregatedRemainders;
    }

    @Override
    public List<FormalMachineAggregationStep> steps() {
        return steps;
    }

    @Override
    public int totalTicks() {
        return totalTicks;
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public GenericStack[] getOutputs() {
        return outputs.toArray(new GenericStack[0]);
    }

    @Override
    public ItemStack assemble(net.minecraft.world.Container input, Level level) {
        if (!aggregatedOutputs.isEmpty() && aggregatedOutputs.get(0).what() instanceof AEItemKey itemKey) {
            ItemStack stack = itemKey.toStack();
            if (!stack.isEmpty()) {
                stack.setCount(clampStackCount(aggregatedOutputs.get(0).amount()));
                return stack;
            }
        }
        if (basePattern instanceof AECraftingPattern craftingPattern) {
            return craftingPattern.assemble(input, level);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer input) {
        return NonNullList.withSize(CRAFTING_GRID_SLOTS, ItemStack.EMPTY);
    }

    @Override
    public boolean isItemValid(int slot, AEItemKey key, Level level) {
        if (slot < 0 || slot >= inputs.length || key == null) {
            return false;
        }
        for (GenericStack possibleInput : inputs[slot].getPossibleInputs()) {
            if (possibleInput != null && key.matches(possibleInput)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSlotEnabled(int slot) {
        return slot >= 0 && slot < inputs.length;
    }

    @Override
    public void fillCraftingGrid(KeyCounter[] table, CraftingGridAccessor gridAccessor) {
        if (table == null || gridAccessor == null) {
            return;
        }
        int slotCount = Math.min(table.length, inputs.length);
        for (int slot = 0; slot < slotCount; slot++) {
            KeyCounter slotInputs = table[slot];
            if (slotInputs == null || slotInputs.isEmpty()) {
                continue;
            }
            GenericStack[] possibleInputs = inputs[slot].getPossibleInputs();
            GenericStack selectedInput = possibleInputs.length == 0
                    ? null
                    : possibleInputs[0];
            if (selectedInput == null || selectedInput.what() == null || selectedInput.amount() <= 0L) {
                continue;
            }
            long available = slotInputs.get(selectedInput.what());
            if (available < selectedInput.amount()) {
                continue;
            }
            if (slot < CRAFTING_GRID_SLOTS) {
                ItemStack gridStack = toGridStack(selectedInput);
                if (!gridStack.isEmpty()) {
                    gridAccessor.set(slot, gridStack);
                }
            }
            slotInputs.remove(selectedInput.what(), selectedInput.amount());
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FormalMachineAggregatedPatternImpl that)) {
            return false;
        }
        return definition.equals(that.definition);
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    @Override
    public String toString() {
        return "FormalMachineAggregatedPatternImpl[" + hostLocator.blockPos() + ", outputs=" + outputs + "]";
    }

    private static void writeMetadata(
            ItemStack definitionStack,
            FormalMachineHostLocator hostLocator,
            List<GenericStack> inputs,
            List<GenericStack> outputs,
            List<GenericStack> aggregatedInputs,
            List<GenericStack> aggregatedOutputs,
            List<GenericStack> aggregatedRemainders,
            List<FormalMachineAggregationStep> steps,
            int totalTicks
    ) {
        CompoundTag metadata = new CompoundTag();
        metadata.put(NBT_HOST, hostLocator.writeToTag());
        metadata.put(NBT_INPUTS, writeStacks(inputs));
        metadata.put(NBT_OUTPUTS, writeStacks(outputs));
        metadata.put(NBT_AGGREGATED_INPUTS, writeStacks(aggregatedInputs));
        metadata.put(NBT_AGGREGATED_OUTPUTS, writeStacks(aggregatedOutputs));
        metadata.put(NBT_AGGREGATED_REMAINDERS, writeStacks(aggregatedRemainders));
        metadata.put(NBT_STEPS, writeSteps(steps));
        metadata.putInt(NBT_TOTAL_TICKS, Math.max(1, totalTicks));
        definitionStack.getOrCreateTag().put(CUSTOM_DATA_ROOT, metadata);
    }

    private static @Nullable IPatternDetails decodeBasePattern(AEItemKey definition, Level level) {
        ItemStack sanitizedDefinitionStack = definition.toStack();
        if (sanitizedDefinitionStack.isEmpty()) {
            return null;
        }
        CompoundTag stackTag = sanitizedDefinitionStack.getTag();
        if (stackTag != null) {
            stackTag.remove(CUSTOM_DATA_ROOT);
            if (stackTag.isEmpty()) {
                sanitizedDefinitionStack.setTag(null);
            }
        }
        return PatternDetailsHelper.decodePattern(sanitizedDefinitionStack, level);
    }

    private static @Nullable CompoundTag getMetadataTag(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag stackTag = stack.getTag();
        if (stackTag == null) {
            return null;
        }
        return stackTag.contains(CUSTOM_DATA_ROOT, Tag.TAG_COMPOUND)
                ? stackTag.getCompound(CUSTOM_DATA_ROOT).copy()
                : null;
    }

    private static @Nullable CompoundTag getMetadataTag(@Nullable AEItemKey definition) {
        if (definition == null) {
            return null;
        }
        ItemStack stack = definition.toStack();
        return getMetadataTag(stack);
    }

    private static IPatternDetails.IInput[] buildInputs(List<GenericStack> inputStacks) {
        IPatternDetails.IInput[] built = new IPatternDetails.IInput[inputStacks == null ? 0 : inputStacks.size()];
        if (inputStacks == null) {
            return built;
        }
        for (int index = 0; index < inputStacks.size(); index++) {
            GenericStack input = inputStacks.get(index);
            built[index] = new ExactInput(input);
        }
        return built;
    }

    private static List<GenericStack> buildVirtualInputs(@Nullable List<GenericStack> aggregatedInputs) {
        if (aggregatedInputs == null || aggregatedInputs.isEmpty()) {
            return List.of();
        }
        List<GenericStack> exposedInputs = new ArrayList<>(aggregatedInputs.size());
        for (GenericStack stack : aggregatedInputs) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            exposedInputs.add(new GenericStack(stack.what(), stack.amount()));
        }
        return List.copyOf(exposedInputs);
    }

    private static List<GenericStack> mergeStacks(List<GenericStack> first, List<GenericStack> second) {
        List<GenericStack> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return List.copyOf(merged);
    }

    private static List<GenericStack> copyStacks(@Nullable List<GenericStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        List<GenericStack> copied = new ArrayList<>(stacks.size());
        for (GenericStack stack : stacks) {
            if (stack != null && stack.what() != null && stack.amount() > 0L) {
                copied.add(new GenericStack(stack.what(), stack.amount()));
            }
        }
        return List.copyOf(copied);
    }

    private static ListTag writeStacks(List<GenericStack> stacks) {
        ListTag tag = new ListTag();
        for (GenericStack stack : copyStacks(stacks)) {
            tag.add(GenericStack.writeTag(stack));
        }
        return tag;
    }

    private static List<GenericStack> readStacks(ListTag tag) {
        List<GenericStack> stacks = new ArrayList<>();
        for (Tag entry : tag) {
            if (entry instanceof CompoundTag compoundTag) {
                GenericStack stack = GenericStack.readTag(compoundTag);
                if (stack != null && stack.what() != null && stack.amount() > 0L) {
                    stacks.add(stack);
                }
            }
        }
        return List.copyOf(stacks);
    }

    private static ListTag writeSteps(List<FormalMachineAggregationStep> steps) {
        ListTag tag = new ListTag();
        if (steps == null) {
            return tag;
        }
        for (FormalMachineAggregationStep step : steps) {
            if (step != null) {
                tag.add(step.writeToTag());
            }
        }
        return tag;
    }

    private static List<FormalMachineAggregationStep> readSteps(ListTag tag) {
        List<FormalMachineAggregationStep> steps = new ArrayList<>();
        for (Tag entry : tag) {
            if (entry instanceof CompoundTag compoundTag) {
                steps.add(FormalMachineAggregationStep.readFromTag(compoundTag));
            }
        }
        return List.copyOf(steps);
    }

    private static int clampStackCount(long amount) {
        if (amount <= 0L) {
            return 1;
        }
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    private static ItemStack toGridStack(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey itemKey) || stack.amount() <= 0L) {
            return ItemStack.EMPTY;
        }
        ItemStack itemStack = itemKey.toStack();
        if (itemStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int count = Math.max(1, Math.min(clampStackCount(stack.amount()), itemStack.getMaxStackSize()));
        itemStack.setCount(count);
        return itemStack;
    }

    private record ExactInput(@Nullable GenericStack stack) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                return new GenericStack[0];
            }
            return new GenericStack[]{new GenericStack(stack.what(), stack.amount())};
        }

        @Override
        public long getMultiplier() {
            return 1L;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return stack != null && stack.what() != null && input != null && input.matches(stack);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
