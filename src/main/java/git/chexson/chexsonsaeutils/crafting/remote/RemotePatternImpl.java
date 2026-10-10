package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.List;
import java.util.Objects;

/** 一份代理输出为一个最小存储单位，使普通 CPU 和宽域 CPU 的账本单位一致。 */
public final class RemotePatternImpl implements RemotePattern {
    private static final IInput[] INPUTS = new IInput[0];
    private final AEItemKey definition;
    private final AEKey output;
    private final List<GenericStack> outputs;
    public RemotePatternImpl(AEItemKey definition, AEKey output) {
        this.definition = Objects.requireNonNull(definition);
        this.output = Objects.requireNonNull(output);
        this.outputs = List.of(new GenericStack(output, 1));
    }
    @Override public AEItemKey getDefinition() { return definition; }
    @Override public AEKey output() { return output; }
    @Override public IInput[] getInputs() { return INPUTS; }
    @Override public List<GenericStack> getOutputs() { return outputs; }
    @Override public boolean supportsPushInputsToExternalInventory() { return false; }
    @Override public boolean equals(Object other) {
        return other instanceof RemotePatternImpl pattern && definition.equals(pattern.definition);
    }
    @Override public int hashCode() { return definition.hashCode(); }
}
