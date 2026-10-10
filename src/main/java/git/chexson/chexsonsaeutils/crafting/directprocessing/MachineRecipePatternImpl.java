package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsTooltip;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;

/** 将机器实际支持的替换输入暴露给规划器，避免选择无法执行的原料后永久等待。 */
final class MachineRecipePatternImpl implements IPatternDetails {
    private final IPatternDetails pattern;
    private final IInput[] inputs;

    private MachineRecipePatternImpl(IPatternDetails pattern, IInput[] inputs) {
        this.pattern = pattern;
        this.inputs = inputs;
    }

    static IPatternDetails restrict(IPatternDetails pattern, List<RecipeSignature> signatures) {
        var inputs = pattern.getInputs();
        var restricted = new IInput[inputs.length];
        boolean changed = false;
        for (int slot = 0; slot < inputs.length; slot++) {
            var options = inputs[slot].getPossibleInputs();
            if (options.length == 1) {
                restricted[slot] = inputs[slot];
                continue;
            }
            var accepted = new ArrayList<GenericStack>();
            for (var candidate : options) {
                for (var signature : signatures) {
                    if (!DirectProcessingStackSupport.patternInputs(pattern, signature, slot, candidate.what()).isEmpty()) {
                        accepted.add(candidate);
                        break;
                    }
                }
            }
            if (accepted.isEmpty()) throw new IllegalArgumentException("Matched recipe has no input for slot " + slot);
            changed |= accepted.size() != inputs[slot].getPossibleInputs().length;
            restricted[slot] = new Input(inputs[slot], List.copyOf(accepted));
        }
        return changed ? new MachineRecipePatternImpl(pattern, restricted) : pattern;
    }

    @Override public AEItemKey getDefinition() { return pattern.getDefinition(); }
    @Override public IInput[] getInputs() { return inputs.clone(); }
    @Override public List<GenericStack> getOutputs() { return pattern.getOutputs(); }
    @Override public boolean supportsPushInputsToExternalInventory() { return pattern.supportsPushInputsToExternalInventory(); }
    @Override public void pushInputsToExternalInventory(KeyCounter[] holders, PatternInputSink sink) {
        IPatternDetails.super.pushInputsToExternalInventory(holders, sink);
    }
    @Override public PatternDetailsTooltip getTooltip(Level level, TooltipFlag flags) { return pattern.getTooltip(level, flags); }
    @Override public int hashCode() { return getDefinition().hashCode(); }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof MachineRecipePatternImpl matched
                && getDefinition().equals(matched.getDefinition());
    }

    private record Input(IInput original, List<GenericStack> options) implements IInput {
        @Override public GenericStack[] getPossibleInputs() { return options.toArray(GenericStack[]::new); }
        @Override public long getMultiplier() { return original.getMultiplier(); }
        @Override public boolean isValid(AEKey key, Level level) {
            return options.stream().anyMatch(candidate -> key.matches(candidate)) && original.isValid(key, level);
        }
        @Override public AEKey getRemainingKey(AEKey key) { return original.getRemainingKey(key); }
    }
}
