package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCalculationAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingTreeNodeInvoker;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Chexson's AE Utils — fast crafting-plan calculation used by the parallel CPU.
 *
 * <p>This reuses AE2's exact planning tree and simulation state (so the produced
 * plan is bit-identical to the native one) but re-drives it without AE2's
 * per-tick time slicing, monitor synchronization, or {@code TickHandler}
 * registration. The pausing hook is intercepted by
 * {@code CraftingCalculationFastPausingMixin} and rerouted to
 * {@link #fastHandlePausing()}, so the calculation runs to completion at full
 * speed on the crafting thread instead of being throttled to a few ms per tick.
 *
 * <p>Safety:
 * <ul>
 *   <li>It never blocks the server thread: {@link #simulateFor(int)} returns
 *       immediately instead of waiting on the simulation monitor.</li>
 *   <li>A wall-clock budget bounds how long the background thread may spin; on
 *       overflow (or any failure) it falls back to the throttled native AE2
 *       calculation, so correctness is always preserved.</li>
 * </ul>
 */
public class FastCraftingCalculation extends CraftingCalculation {

    /**
     * Thrown when the fast-path budget is exceeded. It is a
     * {@link RuntimeException} so the catch in {@link #run()} converts it into a
     * native fallback. Stack traces are disabled because it is control flow.
     */
    public static final class FastPlanningBudgetExceededException extends RuntimeException {
        public FastPlanningBudgetExceededException() {
            super(null, null, false, false);
        }
    }

    private final Level fastLevel;
    private final IGrid fastGrid;
    private final ICraftingSimulationRequester fastRequester;
    private final GenericStack fastOutput;
    private final CalculationStrategy fastStrategy;
    private final long fastRequestedAmount;

    private final NetworkCraftingSimulationState fastNetworkInv;
    private final CraftingTreeNode fastTree;
    private final KeyCounter fastMissing = new KeyCounter();
    private boolean fastSimulate = false;

    private final long budgetNanos;
    private final long startNanos = System.nanoTime();
    private int pauseChecks;

    public FastCraftingCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy,
            long budgetMillis
    ) {
        super(level, grid, simRequester, output, strategy);
        this.fastLevel = level;
        this.fastGrid = grid;
        this.fastRequester = simRequester;
        this.fastOutput = output;
        this.fastStrategy = strategy;
        this.fastRequestedAmount = output.amount();
        this.budgetNanos = budgetMillis <= 0L ? -1L : budgetMillis * 1_000_000L;
        // Reuse the snapshot and search tree the super constructor just built on the
        // server thread; building a second pair doubled the per-request main-thread
        // cost. Unit tests run without mixins, so fall back to dedicated instances.
        NetworkCraftingSimulationState networkInv = null;
        CraftingTreeNode tree = null;
        try {
            CraftingCalculationAccessor accessor = (CraftingCalculationAccessor) this;
            networkInv = accessor.chexsonsaeutils$getNetworkInv();
            tree = accessor.chexsonsaeutils$getTree();
        } catch (ClassCastException | LinkageError ignored) {
            // Mixin layer absent (unit tests).
        }
        if (networkInv == null || tree == null) {
            networkInv = new NetworkCraftingSimulationState(
                    grid.getStorageService(),
                    simRequester.getActionSource()
            );
            tree = new CraftingTreeNode(grid.getCraftingService(), this, output.what(), 1, null, -1);
        }
        this.fastNetworkInv = networkInv;
        this.fastTree = tree;
    }

    @Override
    public ICraftingPlan run() {
        try {
            // Mirrors AE2's CraftingCalculation#computePlan but without TickHandler
            // registration or monitor-based pausing; the tree runs full-speed.
            return computePlan();
        } catch (RuntimeException failure) {
            if (isCancellation(failure)) {
                throw failure;
            }
            return runNativeFallback();
        }
    }

    private ICraftingPlan computePlan() {
        ICraftingPlan fullAmountPlan = runCraftAttempt(false, this.fastRequestedAmount);
        if (fullAmountPlan != null) {
            return fullAmountPlan;
        }
        if (this.fastStrategy == CalculationStrategy.CRAFT_LESS) {
            long successfulAmount = 0L;
            ICraftingPlan successfulPlan = null;
            for (long increment = Long.highestOneBit(this.fastRequestedAmount); increment > 0L; increment /= 2L) {
                long testAmount = successfulAmount + increment;
                if (testAmount < this.fastRequestedAmount) {
                    ICraftingPlan plan = runCraftAttempt(false, testAmount);
                    if (plan != null) {
                        successfulAmount = testAmount;
                        successfulPlan = plan;
                    }
                }
            }
            if (successfulPlan != null) {
                return successfulPlan;
            }
        }
        return runCraftAttempt(true, this.fastRequestedAmount);
    }

    @Nullable
    private ICraftingPlan runCraftAttempt(boolean simulate, long amount) {
        this.fastSimulate = simulate;

        ChildCraftingSimulationState craftingInventory = new ChildCraftingSimulationState(this.fastNetworkInv);
        craftingInventory.ignore(this.fastOutput.what());

        try {
            ((CraftingTreeNodeInvoker) this.fastTree).chexsonsaeutils$request(craftingInventory, amount, null);
        } catch (CraftBranchFailure failure) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }

        long nodeCount = ((CraftingTreeNodeInvoker) this.fastTree).chexsonsaeutils$getNodeCount();
        craftingInventory.addBytes(nodeCount * 8);
        return CraftingSimulationState.buildCraftingPlan(craftingInventory, this, amount);
    }

    @Override
    public boolean isSimulation() {
        return this.fastSimulate;
    }

    @Override
    public AEKey getOutput() {
        return this.fastOutput.what();
    }

    @Override
    public KeyCounter getMissingItems() {
        return this.fastMissing;
    }

    @Override
    public boolean hasMultiplePaths() {
        return ((CraftingTreeNodeInvoker) this.fastTree).chexsonsaeutils$hasMultiplePaths();
    }

    /** Rerouted from the package-private {@code addMissing} by the mixin. */
    public void fastAddMissing(AEKey what, long amount) {
        this.fastMissing.add(what, amount);
    }

    /**
     * Full-speed replacement for AE2's pausing hook, invoked by the mixin instead
     * of the native monitor-based pausing. It performs no monitor synchronization;
     * it only respects thread interruption and enforces the wall-clock budget. The
     * budget is checked sparsely to keep overhead negligible.
     */
    public void fastHandlePausing() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (this.budgetNanos <= 0L) {
            return;
        }
        if ((++this.pauseChecks & 63) != 0) {
            return;
        }
        if (System.nanoTime() - this.startNanos > this.budgetNanos) {
            throw new FastPlanningBudgetExceededException();
        }
    }

    /**
     * Never blocks the server thread. The fast path is self-sufficient on its
     * background thread and does not rely on per-tick pumping, so it is detached
     * from AE2's time-slicing immediately.
     */
    @Override
    public boolean simulateFor(int micros) {
        return false;
    }

    private ICraftingPlan runNativeFallback() {
        CraftingCalculation nativeCalculation = new CraftingCalculation(
                this.fastLevel,
                this.fastGrid,
                this.fastRequester,
                this.fastOutput,
                this.fastStrategy
        );
        return nativeCalculation.run();
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof InterruptedException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }
}
