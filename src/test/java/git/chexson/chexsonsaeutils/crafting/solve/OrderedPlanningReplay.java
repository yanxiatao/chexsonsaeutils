package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.List;

public final class OrderedPlanningReplay {
    private OrderedPlanningReplay() {
    }

    public static OrderedPlanningCalculation create(
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy,
            PlanningAlgorithmId first
    ) {
        var order = new ArrayList<>(List.of(PlanningAlgorithmId.values()));
        order.remove(first);
        order.addFirst(first);
        return new OrderedPlanningCalculation(
                null, grid, requester, output, strategy, order,
                true, 0L);
    }
}
