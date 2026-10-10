package git.chexson.chexsonsaeutils.crafting.executionwitness;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import java.util.Map;
import java.util.Objects;

/** 对原计划零改写的见证包装；原生 CPU 只会看到普通 ICraftingPlan 字段。 */
public record WitnessedCraftingPlan(ICraftingPlan delegate, PlanningInputWitness witness)
        implements ICraftingPlan, PlanningInputWitnessPlan {

    public WitnessedCraftingPlan {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(witness, "witness");
    }

    @Override
    public PlanningInputWitness chexsonsaeutils$planningInputWitness() {
        return witness;
    }

    @Override
    public GenericStack finalOutput() {
        return delegate.finalOutput();
    }

    @Override
    public long bytes() {
        return delegate.bytes();
    }

    @Override
    public boolean simulation() {
        return delegate.simulation();
    }

    @Override
    public boolean multiplePaths() {
        return delegate.multiplePaths();
    }

    @Override
    public KeyCounter usedItems() {
        return delegate.usedItems();
    }

    @Override
    public KeyCounter emittedItems() {
        return delegate.emittedItems();
    }

    @Override
    public KeyCounter missingItems() {
        return delegate.missingItems();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return delegate.patternTimes();
    }
}
