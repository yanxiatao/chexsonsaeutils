package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineAggregationStep;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineHostLocator;

import java.util.List;

public interface IFormalMachineAggregatedPattern
        extends IMolecularAssemblerSupportedPattern, IFormalMachineDelegatingPattern {

    FormalMachineHostLocator hostLocator();

    List<GenericStack> aggregatedInputs();

    List<GenericStack> aggregatedOutputs();

    List<GenericStack> aggregatedRemainders();

    List<FormalMachineAggregationStep> steps();

    int totalTicks();
}
