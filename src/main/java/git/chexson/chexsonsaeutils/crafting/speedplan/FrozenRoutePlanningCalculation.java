package git.chexson.chexsonsaeutils.crafting.speedplan;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.NetworkCraftingSimulationStateAccessor;
import git.chexson.chexsonsaeutils.crafting.solve.OrderedPlanningSelector;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Parallel CPU 专用的冻结路线计算入口。 */
public final class FrozenRoutePlanningCalculation extends CraftingCalculation {
    private static final FrozenRoutePlanner PLANNER = new FrozenRoutePlanner();

    private final Level level;
    private final IGrid grid;
    private final GenericStack output;
    private final KeyCounter stock;
    private boolean simulation;
    private boolean multiplePaths;
    private final KeyCounter missing = new KeyCounter();

    public FrozenRoutePlanningCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester requester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        super(level, grid, requester, output, strategy);
        this.level = level;
        this.grid = grid;
        this.output = output;
        this.stock = resolveNetworkStock(grid);
    }

    @Override
    public ICraftingPlan run() {
        ICraftingPlan plan = runSingleAttempt();
        if (plan == null) {
            throw new FrozenRouteDeclinedException();
        }
        return plan;
    }

    /**
     * Runs the frozen route as one ordered attempt. Unsupported programs and
     * recoverable planner failures decline instead of aborting the request.
     */
    @Nullable
    public FrozenRoutePlanner.CompiledProgram prepareProgram() {
        try {
            return FrozenRoutePlannerCache.getOrCompile(
                    PLANNER, grid.getCraftingService(), output.what(), level);
        } catch (RuntimeException failure) {
            if (OrderedPlanningSelector.isCancellation(failure)) {
                throw failure;
            }
            return null;
        }
    }

    @Nullable
    public ICraftingPlan runSingleAttempt() {
        return runPreparedAttempt(prepareProgram());
    }

    @Nullable
    public ICraftingPlan runPreparedAttempt(@Nullable FrozenRoutePlanner.CompiledProgram program) {
        if (program == null || program.hasCycles()) {
            return null;
        }
        try {
            var result = PLANNER.solve(program, stock, output.amount()).plan();
            simulation = result.simulation();
            multiplePaths = result.multiplePaths();
            missing.reset();
            missing.addAll(result.missingItems());
            return result;
        } catch (RuntimeException failure) {
            if (OrderedPlanningSelector.isCancellation(failure)) {
                throw failure;
            }
            return null;
        }
    }

    /** Stackless control flow retained for direct legacy callers. */
    public static final class FrozenRouteDeclinedException extends RuntimeException {
        public FrozenRouteDeclinedException() {
            super(null, null, false, false);
        }
    }

    private KeyCounter resolveNetworkStock(IGrid grid) {
        NetworkCraftingSimulationState networkInventory = null;
        try {
            networkInventory = ((CraftingCalculationAccessor) (Object) this)
                    .chexsonsaeutils$getNetworkInv();
        } catch (ClassCastException | LinkageError ignored) {
        }
        if (networkInventory != null) {
            try {
                return ((NetworkCraftingSimulationStateAccessor) networkInventory)
                        .chexsonsaeutils$getList();
            } catch (ClassCastException | LinkageError ignored) {
            }
        }
        var snapshot = new KeyCounter();
        for (var stack : grid.getStorageService().getCachedInventory()) {
            if (stack.getLongValue() > 0L) {
                snapshot.add(stack.getKey(), stack.getLongValue());
            }
        }
        return snapshot;
    }

    @Override
    public boolean isSimulation() {
        return simulation;
    }

    @Override
    public AEKey getOutput() {
        return output.what();
    }

    @Override
    public KeyCounter getMissingItems() {
        return missing;
    }

    @Override
    public boolean hasMultiplePaths() {
        return multiplePaths;
    }

    @Override
    public boolean simulateFor(int micros) {
        return false;
    }
}
