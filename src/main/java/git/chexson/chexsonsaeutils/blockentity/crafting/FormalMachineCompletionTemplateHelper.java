package git.chexson.chexsonsaeutils.blockentity.crafting;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.menu.AutoCraftingMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FormalMachineCompletionTemplateHelper {

    private FormalMachineCompletionTemplateHelper() {
    }

    public static boolean supportsTemplateForPattern(@Nullable IMolecularAssemblerSupportedPattern pattern) {
        if (!(pattern instanceof AECraftingPattern aeCraftingPattern)) {
            return false;
        }
        return !aeCraftingPattern.canSubstitute() && !aeCraftingPattern.canSubstituteFluids();
    }

    @Nullable
    public static CompletionTemplate probeStableTemplate(
            @Nullable Level level,
            @Nullable IMolecularAssemblerSupportedPattern pattern,
            @Nullable CompiledTask compiledTask
    ) {
        if (level == null || pattern == null || compiledTask == null || !supportsTemplateForPattern(pattern)) {
            return null;
        }
        SingleExecutionResult first = executeSingleCompletion(level, pattern, compiledTask);
        if (first == null) {
            return null;
        }
        SingleExecutionResult second = executeSingleCompletion(level, pattern, compiledTask);
        if (second == null || !first.matches(second)) {
            return null;
        }
        return new CompletionTemplate(first.primary(), first.remainders());
    }

    @Nullable
    private static SingleExecutionResult executeSingleCompletion(
            Level level,
            IMolecularAssemblerSupportedPattern pattern,
            CompiledTask compiledTask
    ) {
        TransientCraftingContainer craftingContainer = new TransientCraftingContainer(new AutoCraftingMenu(), 3, 3);
        ItemStack[] craftingGrid = compiledTask.getCraftingGridCopies();
        for (int slot = 0; slot < craftingGrid.length; slot++) {
            craftingContainer.setItem(slot, craftingGrid[slot]);
        }
        var positionedInput = craftingContainer.asPositionedCraftInput();
        var craftingInput = positionedInput.input();
        ItemStack output = pattern.assemble(craftingInput, level);
        if (output.isEmpty()) {
            return null;
        }
        GenericStack primary = normalizePrimaryOutput(pattern, output);
        if (primary == null) {
            return null;
        }
        Map<AEItemKey, Long> remainderTotals = new LinkedHashMap<>();
        for (ItemStack remainder : pattern.getRemainingItems(craftingInput)) {
            if (remainder.isEmpty()) {
                continue;
            }
            GenericStack genericRemainder = GenericStack.fromItemStack(remainder.copy());
            if (genericRemainder == null || !(genericRemainder.what() instanceof AEItemKey itemKey)) {
                return null;
            }
            remainderTotals.merge(itemKey, genericRemainder.amount(), Long::sum);
        }
        return new SingleExecutionResult(primary, Map.copyOf(remainderTotals));
    }

    public record CompletionTemplate(GenericStack primary, Map<AEItemKey, Long> remainders) {
    }

    @Nullable
    public static GenericStack normalizePrimaryOutput(
            @Nullable IMolecularAssemblerSupportedPattern pattern,
            @Nullable ItemStack output
    ) {
        if (pattern == null || output == null || output.isEmpty()) {
            return null;
        }
        GenericStack assembled = GenericStack.fromItemStack(output.copy());
        if (assembled == null) {
            return null;
        }
        GenericStack primaryOutput = pattern.getPrimaryOutput();
        if (primaryOutput == null || primaryOutput.what() == null || assembled.what() == null) {
            return assembled;
        }
        if (primaryOutput.what().getType() != assembled.what().getType()) {
            return assembled;
        }
        return new GenericStack(primaryOutput.what(), assembled.amount());
    }

    private record SingleExecutionResult(GenericStack primary, Map<AEItemKey, Long> remainders) {
        private boolean matches(SingleExecutionResult other) {
            if (other == null || primary == null || other.primary == null) {
                return false;
            }
            return primary.what().equals(other.primary.what())
                    && primary.amount() == other.primary.amount()
                    && remainders.equals(other.remainders);
        }
    }
}
