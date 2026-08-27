package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import net.minecraft.world.level.Level;

/**
 * Chexson's AE Utils — fast crafting-plan calculation used by the parallel CPU.
 *
 * <p>This reuses AE2's exact planning algorithm (so the produced plan is
 * bit-identical to the native one) but removes the per-tick time slicing and
 * monitor synchronization that AE2's {@code CraftingCalculation#handlePausing}
 * imposes. The pausing hook is intercepted by
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
     * {@link RuntimeException} so AE2's {@code run()} catch block treats it as a
     * failed attempt, which {@link #run()} converts into a native fallback.
     * Stack traces are disabled because it is used for control flow.
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
        this.budgetNanos = budgetMillis <= 0L ? -1L : budgetMillis * 1_000_000L;
    }

    @Override
    public ICraftingPlan run() {
        try {
            // fastHandlePausing() replaces AE2's pausing hook (via mixin), so this
            // runs the native algorithm full-speed instead of a few ms per tick.
            return super.run();
        } catch (RuntimeException failure) {
            if (isCancellation(failure)) {
                throw failure;
            }
            return runNativeFallback();
        }
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
