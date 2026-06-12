package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class FormalMachineBatchKey {

    private final ItemStack patternDefinition;
    private final ItemStack[] craftingGrid;
    private final int totalTicks;
    private final List<GenericStack> outputs;

    private FormalMachineBatchKey(
            ItemStack patternDefinition,
            ItemStack[] craftingGrid,
            int totalTicks,
            List<GenericStack> outputs
    ) {
        this.patternDefinition = patternDefinition.copy();
        this.craftingGrid = copyGrid(craftingGrid);
        this.totalTicks = Math.max(1, totalTicks);
        this.outputs = copyOutputs(outputs);
    }

    public static FormalMachineBatchKey fromCompiledTask(
            IMolecularAssemblerSupportedPattern pattern,
            CompiledTask compiledTask
    ) {
        return new FormalMachineBatchKey(
                compiledTask.getPatternDefinition(),
                compiledTask.getCraftingGridCopies(),
                compiledTask.getTotalTicks(),
                copyOutputs(pattern.getOutputs())
        );
    }

    public ItemStack getPatternDefinition() {
        return patternDefinition.copy();
    }

    public ItemStack[] getCraftingGridCopies() {
        return copyGrid(craftingGrid);
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public List<GenericStack> getOutputs() {
        return List.copyOf(outputs);
    }

    public boolean matchesCompiledTask(
            IMolecularAssemblerSupportedPattern pattern,
            CompiledTask compiledTask
    ) {
        if (compiledTask == null) {
            return false;
        }
        if (totalTicks != compiledTask.getTotalTicks()) {
            return false;
        }
        if (!sameStack(patternDefinition, compiledTask.getPatternDefinition())) {
            return false;
        }
        ItemStack[] otherGrid = compiledTask.getCraftingGridCopies();
        if (craftingGrid.length != otherGrid.length) {
            return false;
        }
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            if (!sameStack(craftingGrid[slot], otherGrid[slot])) {
                return false;
            }
        }
        List<GenericStack> otherOutputs = copyOutputs(pattern.getOutputs());
        return outputs.equals(otherOutputs);
    }

    public boolean matchesPatternInputs(
            IMolecularAssemblerSupportedPattern pattern,
            KeyCounter[] inputHolders,
            int operationTicks
    ) {
        if (pattern == null || inputHolders == null || totalTicks != Math.max(1, operationTicks)) {
            return false;
        }
        if (!sameStack(patternDefinition, pattern.getDefinition().toStack())) {
            return false;
        }
        ItemStack[] candidateGrid = new ItemStack[craftingGrid.length];
        Arrays.fill(candidateGrid, ItemStack.EMPTY);
        KeyCounter[] copiedCounters = copyCounters(inputHolders);
        pattern.fillCraftingGrid(copiedCounters, (slot, stack) -> candidateGrid[slot] = stack.copy());
        for (KeyCounter copiedCounter : copiedCounters) {
            copiedCounter.removeZeros();
            if (!copiedCounter.isEmpty()) {
                return false;
            }
        }
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            if (!sameStack(craftingGrid[slot], candidateGrid[slot])) {
                return false;
            }
        }
        return outputs.equals(copyOutputs(pattern.getOutputs()));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FormalMachineBatchKey that)) {
            return false;
        }
        if (totalTicks != that.totalTicks) {
            return false;
        }
        if (!sameStack(patternDefinition, that.patternDefinition)) {
            return false;
        }
        if (craftingGrid.length != that.craftingGrid.length) {
            return false;
        }
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            if (!sameStack(craftingGrid[slot], that.craftingGrid[slot])) {
                return false;
            }
        }
        return outputs.equals(that.outputs);
    }

    @Override
    public int hashCode() {
        int result = stackHash(patternDefinition);
        result = 31 * result + totalTicks;
        for (ItemStack stack : craftingGrid) {
            result = 31 * result + stackHash(stack);
        }
        result = 31 * result + outputs.hashCode();
        return result;
    }

    private static List<GenericStack> copyOutputs(List<GenericStack> rawOutputs) {
        List<GenericStack> copied = new ArrayList<>(rawOutputs.size());
        for (GenericStack rawOutput : rawOutputs) {
            if (rawOutput != null) {
                copied.add(new GenericStack(rawOutput.what(), rawOutput.amount()));
            }
        }
        return List.copyOf(copied);
    }

    private static ItemStack[] copyGrid(ItemStack[] rawGrid) {
        ItemStack[] copied = new ItemStack[rawGrid.length];
        for (int slot = 0; slot < rawGrid.length; slot++) {
            copied[slot] = rawGrid[slot] == null ? ItemStack.EMPTY : rawGrid[slot].copy();
        }
        return copied;
    }

    private static KeyCounter[] copyCounters(KeyCounter[] rawCounters) {
        KeyCounter[] copied = new KeyCounter[rawCounters.length];
        for (int index = 0; index < rawCounters.length; index++) {
            copied[index] = new KeyCounter();
            if (rawCounters[index] != null) {
                copied[index].addAll(rawCounters[index]);
            }
        }
        return copied;
    }

    private static boolean sameStack(@Nullable ItemStack left, @Nullable ItemStack right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    private static int stackHash(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return Objects.hash(stack.getItem(), stack.getCount(), stack.getComponents());
    }
}
