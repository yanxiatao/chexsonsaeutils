package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.pattern.AECraftingPattern;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FormalMachineScaledPatternImpl implements FormalMachineScaledPattern {

    private final AECraftingPattern basePattern;
    private final int multiplier;
    private final IPatternDetails.IInput[] scaledInputs;
    private final List<GenericStack> scaledOutputs;
    private final ItemStack[] scaledCraftingGrid;
    @Nullable
    private final GenericStack templatePrimary;
    private final Map<AEItemKey, Long> templateRemainders;

    public FormalMachineScaledPatternImpl(
            AECraftingPattern basePattern,
            int multiplier,
            @Nullable GenericStack templatePrimary,
            Map<AEItemKey, Long> templateRemainders
    ) {
        if (basePattern == null) {
            throw new IllegalArgumentException("basePattern");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException("multiplier");
        }
        this.basePattern = basePattern;
        this.multiplier = multiplier;
        this.scaledInputs = scaleInputs(basePattern.getInputs(), multiplier);
        this.scaledOutputs = scaleOutputs(basePattern.getOutputs(), multiplier);
        this.scaledCraftingGrid = buildScaledCraftingGrid(basePattern, multiplier);
        this.templatePrimary = copyGenericStack(templatePrimary);
        this.templateRemainders = copyRemainders(templateRemainders);
    }

    @Override
    public AECraftingPattern basePattern() {
        return basePattern;
    }

    @Override
    public int multiplier() {
        return multiplier;
    }

    @Override
    public AEItemKey getDefinition() {
        return basePattern.getDefinition();
    }

    @Override
    public IInput[] getInputs() {
        return scaledInputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return scaledOutputs;
    }

    @Override
    public ItemStack assemble(CraftingInput input, Level level) {
        return basePattern.assemble(input, level);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        return basePattern.getRemainingItems(input);
    }

    @Override
    public boolean isItemValid(int slot, AEItemKey key, Level level) {
        return basePattern.isItemValid(slot, key, level);
    }

    @Override
    public boolean isSlotEnabled(int slot) {
        return basePattern.isSlotEnabled(slot);
    }

    @Override
    public void fillCraftingGrid(KeyCounter[] table, CraftingGridAccessor gridAccessor) {
        for (int slot = 0; slot < scaledCraftingGrid.length; slot++) {
            ItemStack stack = scaledCraftingGrid[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            long needed = stack.getCount();
            consumeFromCounters(table, key, needed);
            gridAccessor.set(slot, stack.copy());
        }
    }

    @Override
    public ItemStack[] getScaledCraftingGridCopies() {
        ItemStack[] copies = new ItemStack[scaledCraftingGrid.length];
        for (int slot = 0; slot < scaledCraftingGrid.length; slot++) {
            ItemStack stack = scaledCraftingGrid[slot];
            copies[slot] = stack == null ? ItemStack.EMPTY : stack.copy();
        }
        return copies;
    }

    @Override
    public @Nullable GenericStack templatePrimary() {
        return copyGenericStack(templatePrimary);
    }

    @Override
    public Map<AEItemKey, Long> templateRemainders() {
        return Map.copyOf(templateRemainders);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FormalMachineScaledPatternImpl that)) {
            return false;
        }
        return multiplier == that.multiplier
                && basePattern.getDefinition().equals(that.basePattern.getDefinition());
    }

    @Override
    public int hashCode() {
        return Objects.hash(basePattern.getDefinition(), multiplier);
    }

    @Override
    public String toString() {
        return "FormalMachineScaledPatternImpl[" + basePattern.getDefinition() + " x" + multiplier + "]";
    }

    private static IPatternDetails.IInput[] scaleInputs(IPatternDetails.IInput[] inputs, int multiplier) {
        IPatternDetails.IInput[] scaled = new IPatternDetails.IInput[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            scaled[index] = new ScaledInput(inputs[index], multiplier);
        }
        return scaled;
    }

    private static List<GenericStack> scaleOutputs(List<GenericStack> outputs, int multiplier) {
        List<GenericStack> scaled = new ArrayList<>(outputs.size());
        for (GenericStack output : outputs) {
            if (output != null) {
                scaled.add(new GenericStack(output.what(), safeMultiply(output.amount(), multiplier)));
            }
        }
        return List.copyOf(scaled);
    }

    private static ItemStack[] buildScaledCraftingGrid(AECraftingPattern basePattern, int multiplier) {
        ItemStack[] grid = new ItemStack[9];
        Arrays.fill(grid, ItemStack.EMPTY);
        List<GenericStack> sparseInputs = basePattern.getSparseInputs();
        for (int slot = 0; slot < Math.min(9, sparseInputs.size()); slot++) {
            GenericStack sparse = sparseInputs.get(slot);
            if (sparse == null || !(sparse.what() instanceof AEItemKey itemKey)) {
                continue;
            }
            ItemStack stack = itemKey.toStack();
            stack.setCount(scaleStackCount(sparse.amount(), multiplier));
            grid[slot] = stack;
        }
        return grid;
    }

    private static void consumeFromCounters(KeyCounter[] counters, AEItemKey key, long amount) {
        long remaining = amount;
        for (KeyCounter counter : counters) {
            if (counter == null || remaining <= 0L) {
                continue;
            }
            long available = counter.get(key);
            if (available <= 0L) {
                continue;
            }
            long taken = Math.min(available, remaining);
            counter.remove(key, taken);
            remaining -= taken;
            if (remaining <= 0L) {
                break;
            }
        }
        if (remaining > 0L) {
            throw new IllegalStateException("Scaled crafting grid inputs were not fully consumed");
        }
    }

    private static int scaleStackCount(long amount, int multiplier) {
        long scaled = safeMultiply(amount, multiplier);
        if (scaled <= 0L || scaled > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Scaled stack count overflow");
        }
        return (int) scaled;
    }

    private static long safeMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static @Nullable GenericStack copyGenericStack(@Nullable GenericStack stack) {
        return stack == null ? null : new GenericStack(stack.what(), stack.amount());
    }

    private static Map<AEItemKey, Long> copyRemainders(Map<AEItemKey, Long> remainders) {
        Map<AEItemKey, Long> copied = new LinkedHashMap<>();
        if (remainders != null) {
            for (Map.Entry<AEItemKey, Long> entry : remainders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                    copied.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return Map.copyOf(copied);
    }

    private record ScaledInput(IPatternDetails.IInput original, int multiplier) implements IPatternDetails.IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return original.getPossibleInputs();
        }

        @Override
        public long getMultiplier() {
            return safeMultiply(original.getMultiplier(), multiplier);
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return original.isValid(input, level);
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return original.getRemainingKey(template);
        }
    }
}
