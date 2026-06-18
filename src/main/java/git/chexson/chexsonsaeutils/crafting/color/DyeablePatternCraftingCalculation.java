package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
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
import appeng.me.service.CraftingService;
import com.google.common.base.Stopwatch;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.config.DyeablePatternRecursiveConfig;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingServiceDyeablePatternAccessor;
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
    ICraftingPlan runCraftAttempt(boolean simulate, long amount) throws InterruptedException {
        this.simulate = simulate;
        this.ringExtractions.reset();

        ChildCraftingSimulationState craftingInventory = new ChildCraftingSimulationState(networkInv);
        craftingInventory.ignore(this.output);

        try {
            this.tree.request(craftingInventory, amount, null);
            CraftingPlan preliminaryPlan = CraftingSimulationState.buildCraftingPlan(craftingInventory, this, amount);
            retainRecursiveCatalysts(craftingInventory, preliminaryPlan);
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

    private ICraftingPlan mergeRingExtractions(CraftingPlan basePlan) {
        return createRecursivePlanForRingExtractions(basePlan, this.ringExtractions);
    }

    static ICraftingPlan createRecursivePlanForRingExtractions(
            CraftingPlan basePlan,
            KeyCounter ringExtractions
    ) {
        KeyCounter combinedUsedItems = new KeyCounter();
        addRewrittenUsedItems(basePlan, combinedUsedItems);
        mergeRecursiveInitialItems(combinedUsedItems, ringExtractions);

        CraftingPlan merged = new CraftingPlan(
                basePlan.finalOutput(),
                basePlan.bytes(),
                basePlan.simulation(),
                basePlan.multiplePaths(),
                combinedUsedItems,
                basePlan.emittedItems(),
                basePlan.missingItems(),
                basePlan.patternTimes()
        );
        return new RecursiveCraftingPlan(
                merged,
                copyCounter(ringExtractions),
                copyCounter(ringExtractions),
                basePlan.finalOutput().amount()
        );
    }

    private static void addRewrittenUsedItems(CraftingPlan basePlan, KeyCounter target) {
        if (basePlan == null || target == null || basePlan.usedItems() == null) {
            return;
        }
        AEKey finalKey = basePlan.finalOutput() == null ? null : basePlan.finalOutput().what();
        for (var entry : basePlan.usedItems()) {
            if (entry.getKey() == null || entry.getLongValue() <= 0L) {
                continue;
            }
            if (finalKey != null && finalKey.equals(entry.getKey())) {
                continue;
            }
            target.add(entry.getKey(), entry.getLongValue());
        }
    }

    private void retainRecursiveCatalysts(
            CraftingSimulationState inventory,
            CraftingPlan preliminaryPlan
    ) throws CraftBranchFailure, InterruptedException {
        long retainedAmount = DyeablePatternRecursiveConfig.retainedCatalystAmount();
        if (retainedAmount <= 0L || preliminaryPlan == null) {
            return;
        }

        var gridNode = this.simRequester.getGridNode();
        if (gridNode == null) {
            return;
        }
        ICraftingService craftingService = gridNode.getGrid().getCraftingService();
        DyeablePatternCraftingProviders providers = getDyeableProviders(craftingService);
        if (providers == null) {
            return;
        }

        Set<AEKey> candidateKeys = collectRetainedCatalystCandidates(preliminaryPlan, this.ringExtractions);
        for (AEKey candidateKey : candidateKeys) {
            DyeablePatternCompressedRing retainingRing = providers.getRetainingRing(candidateKey);
            if (!DyeablePatternCraftingPlanner.isCompressedRingCalculable(retainingRing)) {
                continue;
            }
            long projectedAmount = projectNetworkAmountAfterPlan(preliminaryPlan, candidateKey);
            long deficit = retainedAmount - projectedAmount;
            if (deficit <= 0L) {
                continue;
            }
            applySupplementalRing(inventory, craftingService, retainingRing, candidateKey, deficit);
            preliminaryPlan = CraftingSimulationState.buildCraftingPlan(inventory, this, preliminaryPlan.finalOutput().amount());
        }
    }

    static Set<AEKey> collectRetainedCatalystCandidates(
            CraftingPlan plan,
            KeyCounter recursiveInitialItems
    ) {
        Set<AEKey> keys = new HashSet<>();
        collectCounterKeys(recursiveInitialItems, keys);
        if (plan != null) {
            collectCounterKeys(plan.usedItems(), keys);
        }
        return keys;
    }

    private static void collectCounterKeys(KeyCounter counter, Set<AEKey> target) {
        if (counter == null || target == null) {
            return;
        }
        for (var entry : counter) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                target.add(entry.getKey());
            }
        }
    }

    private long projectNetworkAmountAfterPlan(CraftingPlan plan, AEKey key) {
        long projected = this.networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        projected += totalPatternOutputs(plan, key);
        projected -= totalPatternInputs(plan, key);
        if (plan.finalOutput() != null && key.equals(plan.finalOutput().what())) {
            projected -= plan.finalOutput().amount();
        }
        return Math.max(0L, projected);
    }

    private void applySupplementalRing(
            CraftingSimulationState inventory,
            ICraftingService craftingService,
            DyeablePatternCompressedRing ring,
            AEKey retainedKey,
            long deficit
    ) throws CraftBranchFailure, InterruptedException {
        long netOutput = ring.netOutputs().get(retainedKey);
        if (netOutput <= 0L) {
            return;
        }

        double scale = (double) deficit / netOutput;
        requestRingDependencies(inventory, craftingService, ring, scale);
        unpackRingOperations(inventory, ring, scale);
    }

    void requestRingDependencies(
            CraftingSimulationState sandbox,
            ICraftingService craftingService,
            DyeablePatternCompressedRing ring,
            double scale
    ) throws CraftBranchFailure, InterruptedException {
        for (var stack : ring.catalysts()) {
            AEKey key = stack.getKey();
            long amountNeeded = stack.getLongValue();
            long requiredAmount = Math.max(0L, amountNeeded - this.ringExtractions.get(key));
            if (requiredAmount <= 0L) {
                continue;
            }

            long extracted = trackRingUsage(key, requiredAmount);
            if (extracted > 0L) {
                sandbox.insert(key, extracted, Actionable.MODULATE);
            }
            if (extracted < requiredAmount) {
                new DyeablePatternCraftingTreeNode(craftingService, this, key, 1L, null, -1)
                        .request(sandbox, requiredAmount - extracted, null);
            }
        }

        for (var stack : ring.netInputs()) {
            long required = (long) Math.ceil(stack.getLongValue() * scale);
            if (required > 0L) {
                new DyeablePatternCraftingTreeNode(craftingService, this, stack.getKey(), 1L, null, -1)
                        .request(sandbox, required, null);
            }
        }
    }

    void unpackRingOperations(
            CraftingSimulationState sandbox,
            DyeablePatternCompressedRing ring,
            double scale
    ) {
        for (var entry : ring.executionRatio().entrySet()) {
            long times = (long) Math.ceil(entry.getValue() * scale);
            if (times > 0L) {
                sandbox.addCrafting(entry.getKey(), times);
            }
        }

        for (var stack : ring.netOutputs()) {
            long produced = (long) Math.floor(stack.getLongValue() * scale);
            if (produced > 0L) {
                sandbox.insert(stack.getKey(), produced, Actionable.MODULATE);
            }
        }
    }

    private static void mergeRecursiveInitialItems(KeyCounter target, KeyCounter recursiveInitialItems) {
        if (target == null || recursiveInitialItems == null) {
            return;
        }
        for (var entry : recursiveInitialItems) {
            if (entry.getKey() != null && entry.getLongValue() > 0L) {
                target.set(entry.getKey(), Math.max(target.get(entry.getKey()), entry.getLongValue()));
            }
        }
    }

    private static long totalPatternInputs(CraftingPlan plan, AEKey key) {
        long total = 0L;
        if (plan == null || plan.patternTimes() == null || key == null) {
            return 0L;
        }
        for (var entry : plan.patternTimes().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            for (var input : entry.getKey().getInputs()) {
                if (input == null || input.getPossibleInputs() == null) {
                    continue;
                }
                GenericStack selected = selectInputForAccounting(input, key);
                if (selected != null && selected.what() != null && key.matches(selected)) {
                    total += selected.amount() * input.getMultiplier() * entry.getValue();
                }
            }
        }
        return total;
    }

    private static long totalPatternOutputs(CraftingPlan plan, AEKey key) {
        long total = 0L;
        if (plan == null || plan.patternTimes() == null || key == null) {
            return 0L;
        }
        for (var entry : plan.patternTimes().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            for (GenericStack outputStack : entry.getKey().getOutputs()) {
                if (outputStack != null && outputStack.what() != null && key.matches(outputStack)) {
                    total += outputStack.amount() * entry.getValue();
                }
            }
        }
        return total;
    }

    @Nullable
    private static GenericStack selectInputForAccounting(
            appeng.api.crafting.IPatternDetails.IInput input,
            AEKey key
    ) {
        GenericStack[] possibleInputs = input.getPossibleInputs();
        if (possibleInputs == null || possibleInputs.length == 0) {
            return null;
        }
        for (GenericStack possibleInput : possibleInputs) {
            if (possibleInput != null && possibleInput.what() != null && key.matches(possibleInput)) {
                return possibleInput;
            }
        }
        return possibleInputs[0];
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
        long available = this.networkInv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        long reservable = Math.max(0L, available - this.ringExtractions.get(key));
        long extracted = Math.min(amount, reservable);
        if (extracted > 0L) {
            this.ringExtractions.add(key, extracted);
        }
        return extracted;
    }

    KeyCounter copyRingExtractions() {
        return copyCounter(this.ringExtractions);
    }

    void restoreRingExtractions(KeyCounter snapshot) {
        this.ringExtractions.reset();
        if (snapshot != null) {
            this.ringExtractions.addAll(snapshot);
        }
    }

    @Nullable
    DyeablePatternCompressedRing getCompressedRing(ICraftingService craftingService, int color) {
        return getCompressedRing(craftingService, color, null);
    }

    @Nullable
    DyeablePatternCompressedRing getCompressedRing(
            ICraftingService craftingService,
            int color,
            @Nullable AEKey entryPoint
    ) {
        DyeablePatternCraftingProviders providers = getDyeableProviders(craftingService);
        return providers == null ? null : providers.getOrCalculateCompressedRing(color, entryPoint);
    }

    @Nullable
    DyeablePatternCraftingProviders getDyeableProviders(ICraftingService craftingService) {
        if (!(craftingService instanceof CraftingService craftingServiceImpl)) {
            return null;
        }

        var providers = ((CraftingServiceDyeablePatternAccessor) craftingServiceImpl)
                .chexsonsaeutils$getCraftingProviders();
        return providers instanceof DyeablePatternCraftingProviders dyeableProviders ? dyeableProviders : null;
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
        return providers.getOrCalculateCompressedRing(color, output.what());
    }

    private static KeyCounter copyCounter(KeyCounter original) {
        KeyCounter copy = new KeyCounter();
        if (original != null) {
            copy.addAll(original);
        }
        return copy;
    }

    private record RecursiveCraftingPlan(
            CraftingPlan delegate,
            KeyCounter recursiveInitialItems,
            KeyCounter recursiveInternalItems,
            long recursiveFinalOutputAmount
    )
            implements ICraftingPlan, DyeablePatternRecursivePlan {

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
        public Map<appeng.api.crafting.IPatternDetails, Long> patternTimes() {
            return delegate.patternTimes();
        }

        @Override
        public boolean chexsonsaeutils$usesDyeableRecursivePlanning() {
            return true;
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInitialItems() {
            return copyCounter(recursiveInitialItems);
        }

        @Override
        public KeyCounter chexsonsaeutils$dyeableRecursiveInternalItems() {
            return copyCounter(recursiveInternalItems);
        }

        @Override
        public long chexsonsaeutils$dyeableRecursiveFinalOutputAmount() {
            return recursiveFinalOutputAmount;
        }
    }
}
