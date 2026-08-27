package git.chexson.chexsonsaeutils.crafting.fastplan;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.color.DyeablePatternCraftingCalculation;
import net.minecraft.world.level.Level;

/**
 * Chexson's AE Utils — fast variant of the dyeable recursive crafting
 * calculation, used by the parallel CPU.
 *
 * <p>{@link DyeablePatternCraftingCalculation} reuses AE2's time-sliced pausing,
 * which throttles recursive (ring-replacement) planning to a few ms per tick.
 * This subclass keeps all recursive logic intact but reroutes the pausing hook
 * (via {@code DyeablePatternCraftingCalculationFastPausingMixin}) to a cheap
 * budget check and detaches from AE2's per-tick pumping, so recursive plans run
 * to completion at full speed on the crafting thread.
 *
 * <p>Safety mirrors {@link FastCraftingCalculation}: a wall-clock budget bounds
 * the background thread, and on overflow (or any failure) it falls back to the
 * throttled native dyeable calculation, so correctness is always preserved.
 */
public class FastDyeablePatternCraftingCalculation extends DyeablePatternCraftingCalculation {

    private final Level fastLevel;
    private final IGrid fastGrid;
    private final ICraftingSimulationRequester fastRequester;
    private final GenericStack fastOutput;
    private final CalculationStrategy fastStrategy;

    private final long budgetNanos;
    private final long startNanos = System.nanoTime();
    private int pauseChecks;

    public FastDyeablePatternCraftingCalculation(
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
            // super.run() drives the recursive algorithm; handlePausing is
            // intercepted by the mixin to a budget check, so it runs full-speed.
            return super.run();
        } catch (RuntimeException failure) {
            if (isCancellation(failure)) {
                throw failure;
            }
            return runNativeFallback();
        }
    }

    /**
     * Full-speed replacement for the pausing hook, invoked by the mixin. No
     * monitor synchronization; only respects interruption and the wall-clock
     * budget, checked sparsely to keep overhead negligible.
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
            throw new FastCraftingCalculation.FastPlanningBudgetExceededException();
        }
    }

    /**
     * Never blocks the server thread; the fast recursive path is self-sufficient
     * on its background thread and does not rely on per-tick pumping.
     */
    @Override
    public boolean simulateFor(int micros) {
        return false;
    }

    private ICraftingPlan runNativeFallback() {
        DyeablePatternCraftingCalculation nativeCalculation = new DyeablePatternCraftingCalculation(
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
