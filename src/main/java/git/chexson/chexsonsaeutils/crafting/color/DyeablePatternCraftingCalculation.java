package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.NetworkCraftingSimulationState;
import appeng.hooks.ticking.TickHandler;
import com.google.common.base.Stopwatch;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 染色样板 planning calculation 入口。
 *
 * 在 AE2 原生计算外补充同色样板 ring replacement，用于带催化物种子的自循环样板。
 */
public class DyeablePatternCraftingCalculation extends CraftingCalculation {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final NetworkCraftingSimulationState networkInv;
    private final Level level;
    private final KeyCounter missing = new KeyCounter();
    private final Object monitor = new Object();
    private final Stopwatch watch = Stopwatch.createUnstarted();
    private final DyeablePatternCraftingTreeNode tree;
    private final AEKey output;
    private final long requestedAmount;
    private final CalculationStrategy strategy;
    final ICraftingSimulationRequester simRequester;
    private final GenericStack requestedOutput;
    private final int requestedColor;
    private final Map<Integer, Set<AEKey>> failedRingReplacements = new HashMap<>();
    private final Set<Integer> ringsBeingReplaced = new HashSet<>();
    private final KeyCounter ringExtractions = new KeyCounter();
    private boolean simulate = false;
    private boolean running = false;
    private boolean done = false;
    private int time = 5;
    private int incTime = Integer.MAX_VALUE;

    public DyeablePatternCraftingCalculation(
            Level level,
            IGrid grid,
            ICraftingSimulationRequester simRequester,
            GenericStack output,
            CalculationStrategy strategy
    ) {
        super(level, grid, simRequester, output, strategy);
        this.level = level;
        this.output = output.what();
        this.requestedAmount = output.amount();
        this.strategy = strategy;
        this.simRequester = simRequester;
        this.requestedOutput = output;
        this.requestedColor = resolveRequestedColor(output);
        this.networkInv = new NetworkCraftingSimulationState(
                grid.getStorageService(),
                simRequester.getActionSource()
        );
        this.tree = new DyeablePatternCraftingTreeNode(
                grid.getCraftingService(),
                this,
                this.output,
                1L,
                null,
                -1
        );
    }

    public GenericStack chexsonsaeutils$getRequestedOutput() {
        return this.requestedOutput;
    }

    public int chexsonsaeutils$getRequestedColor() {
        return this.requestedColor;
    }

    public boolean chexsonsaeutils$hasRequestedColor() {
        return this.requestedColor != -1;
    }

    @Nullable
    public DyeablePatternCompressedRing chexsonsaeutils$getPreparedCompressedRing(
            @Nullable DyeablePatternCraftingProviders providers
    ) {
        return resolvePreparedCompressedRing(this.requestedOutput, providers);
    }

    public boolean chexsonsaeutils$canPrepareRingPlanning(@Nullable DyeablePatternCraftingProviders providers) {
        return DyeablePatternCraftingPlanner.canPlanRingReplacementWithoutSwallowingReplacement(
                chexsonsaeutils$getPreparedCompressedRing(providers)
        );
    }

    @Override
    public ICraftingPlan run() {
        try {
            TickHandler.instance().registerCraftingSimulation(this.level, this);
            this.handlePausing();
            return computePlan();
        } catch (Exception exception) {
            LOGGER.warn("Dyeable pattern crafting calculation failed for {}", this.requestedOutput, exception);
            throw new RuntimeException(exception);
        } finally {
            this.finish();
        }
    }

    private ICraftingPlan computePlan() throws InterruptedException {
        ICraftingPlan fullAmountPlan = runCraftAttempt(false, requestedAmount);
        if (fullAmountPlan != null) {
            return fullAmountPlan;
        }

        if (strategy == CalculationStrategy.CRAFT_LESS) {
            long successfulAmount = 0L;
            ICraftingPlan successfulPlan = null;
            for (long increment = Long.highestOneBit(requestedAmount); increment > 0L; increment /= 2L) {
                long testAmount = successfulAmount + increment;
                if (testAmount < requestedAmount) {
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

        return runCraftAttempt(true, requestedAmount);
    }

    @Nullable
    private CraftingPlan runCraftAttempt(boolean simulate, long amount) throws InterruptedException {
        this.simulate = simulate;
        this.ringExtractions.reset();

        ChildCraftingSimulationState craftingInventory = new ChildCraftingSimulationState(networkInv);
        craftingInventory.ignore(this.output);

        try {
            this.tree.request(craftingInventory, amount, null, false);
        } catch (CraftBranchFailure failure) {
            LOGGER.debug("Dyeable pattern craft attempt failed for {} x{}", this.output, amount, failure);
            return null;
        }

        craftingInventory.addBytes(this.tree.getNodeCount() * 8.0D);
        CraftingPlan basePlan = CraftingSimulationState.buildCraftingPlan(craftingInventory, this, amount);
        if (this.ringExtractions.isEmpty()) {
            return basePlan;
        }
        return mergeRingExtractions(basePlan);
    }

    private CraftingPlan mergeRingExtractions(CraftingPlan basePlan) {
        KeyCounter combinedUsedItems = new KeyCounter();
        combinedUsedItems.addAll(basePlan.usedItems());
        combinedUsedItems.addAll(this.ringExtractions);

        return new CraftingPlan(
                resolveFinalOutput(basePlan),
                basePlan.bytes(),
                basePlan.simulation(),
                basePlan.multiplePaths(),
                combinedUsedItems,
                basePlan.emittedItems(),
                basePlan.missingItems(),
                basePlan.patternTimes()
        );
    }

    private GenericStack resolveFinalOutput(CraftingPlan basePlan) {
        AEKey targetKey = basePlan.finalOutput().what();
        long totalOutput = 0L;
        for (var entry : basePlan.patternTimes().entrySet()) {
            long times = entry.getValue();
            for (GenericStack outputStack : entry.getKey().getOutputs()) {
                if (outputStack.what().equals(targetKey)) {
                    totalOutput += outputStack.amount() * times;
                }
            }
        }
        return totalOutput > basePlan.finalOutput().amount()
                ? new GenericStack(targetKey, totalOutput)
                : basePlan.finalOutput();
    }

    void addMissing(AEKey what, long amount) {
        this.missing.add(what, amount);
    }

    void handlePausing() throws InterruptedException {
        if (this.incTime > 100) {
            this.incTime = 0;

            synchronized (this.monitor) {
                if (this.watch.elapsed(TimeUnit.MICROSECONDS) > this.time) {
                    this.running = false;
                    this.watch.stop();
                    this.monitor.notify();
                }

                if (!this.running) {
                    while (!this.running) {
                        this.monitor.wait();
                    }
                }
            }

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
        this.incTime++;
    }

    private void finish() {
        synchronized (this.monitor) {
            this.running = false;
            this.done = true;
            this.monitor.notify();
        }
    }

    @Override
    public boolean isSimulation() {
        return this.simulate;
    }

    @Override
    public AEKey getOutput() {
        return output;
    }

    @Override
    public KeyCounter getMissingItems() {
        return missing;
    }

    @Override
    public boolean simulateFor(int micros) {
        this.time = micros;
        synchronized (this.monitor) {
            if (this.done) {
                return false;
            }

            this.watch.reset();
            this.watch.start();
            this.running = true;
            this.monitor.notify();

            while (this.running) {
                try {
                    this.monitor.wait();
                } catch (InterruptedException exception) {
                    LOGGER.warn("Interrupted while simulating dyeable pattern crafting for {}", this.requestedOutput,
                            exception);
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean hasMultiplePaths() {
        return this.tree.hasMultiplePaths();
    }

    Level level() {
        return this.level;
    }

    NetworkCraftingSimulationState networkInventory() {
        return this.networkInv;
    }

    boolean hasRingReplacementFailed(int color, AEKey entryPoint) {
        Set<AEKey> failures = this.failedRingReplacements.get(color);
        return failures != null && failures.contains(entryPoint);
    }

    void markRingReplacementAsFailed(int color, AEKey entryPoint) {
        this.failedRingReplacements.computeIfAbsent(color, ignored -> new HashSet<>()).add(entryPoint);
    }

    Set<Integer> ringsBeingReplaced() {
        return this.ringsBeingReplaced;
    }

    long trackRingUsage(AEKey key, long amount) {
        long extracted = this.networkInv.extract(key, amount, Actionable.MODULATE);
        if (extracted > 0L) {
            this.ringExtractions.add(key, extracted);
        }
        return extracted;
    }

    static int resolveRequestedColor(@Nullable GenericStack output) {
        if (output == null || output.what() == null) {
            return -1;
        }
        return PatternColorHelper.getPatternColor(output.what().wrapForDisplayOrFilter());
    }

    @Nullable
    static DyeablePatternCompressedRing resolvePreparedCompressedRing(
            @Nullable GenericStack output,
            @Nullable DyeablePatternCraftingProviders providers
    ) {
        if (providers == null) {
            return null;
        }
        int color = resolveRequestedColor(output);
        if (color == -1) {
            return null;
        }
        return providers.getOrCalculateCompressedRing(color);
    }
}
