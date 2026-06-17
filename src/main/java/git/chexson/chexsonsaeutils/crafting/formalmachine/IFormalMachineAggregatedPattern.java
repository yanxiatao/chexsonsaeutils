package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineAggregationStep;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineHostLocator;

import java.util.List;

/**
 * Virtual AE2 crafting pattern that collapses a formal-machine crafting subgraph into a single provider push.
 */
public interface IFormalMachineAggregatedPattern
        extends IMolecularAssemblerSupportedPattern, IFormalMachineDelegatingPattern {

    /**
     * Returns the target high-capacity crafting host that owns the aggregated provider pattern.
     */
    FormalMachineHostLocator hostLocator();

    /**
     * Returns the aggregate inputs that were used to build the virtual pattern boundary.
     */
    List<GenericStack> aggregatedInputs();

    /**
     * Returns the aggregate outputs that are visible outside the collapsed formal subgraph.
     */
    List<GenericStack> aggregatedOutputs();

    /**
     * Returns the aggregate non-primary outputs that remain visible outside the collapsed formal subgraph.
     */
    List<GenericStack> aggregatedRemainders();

    /**
     * Returns the ordered aggregation steps that describe the native AE2 formal subgraph.
     */
    List<FormalMachineAggregationStep> steps();

    /**
     * Returns the synthetic task duration used for host-side progress reporting.
     */
    int totalTicks();
}
