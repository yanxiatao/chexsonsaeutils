package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.InputTemplate;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.inv.ICraftingInventory;
import git.chexson.chexsonsaeutils.pattern.replacement.PlanningReplacementSelector;
import git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 染色样板 planning node。
 *
 * 对齐 AE2 原生 crafting tree，并补充 AEA 风格的同色 ring replacement。
 */
final class DyeablePatternCraftingTreeNode {
    @Nullable
    private final IPatternDetails.IInput parentInput;
    private final DyeablePatternCraftingCalculation job;
    @Nullable
    private final DyeablePatternCraftingTreeProcess parent;
    private final AEKey what;
    private final long amount;
    private final boolean canEmit;
    private final int color;
    @Nullable
    private ArrayList<DyeablePatternCraftingTreeProcess> nodes;

    DyeablePatternCraftingTreeNode(
            ICraftingService craftingService,
            DyeablePatternCraftingCalculation job,
            AEKey what,
            long amount,
            @Nullable DyeablePatternCraftingTreeProcess parent,
            int slot
    ) {
        this.parent = parent;
        this.parentInput = slot == -1 || parent == null ? null : parent.details.getInputs()[slot];
        this.job = job;
        AEKey craftedStack = findCraftedStack(craftingService, what);
        if (ReplacementAwareProcessingPattern.matchesAnyPossibleInput(this.parentInput, what)
                && !what.matches(this.parentInput.getPossibleInputs()[0])) {
            craftedStack = what;
        }
        this.what = craftedStack;
        this.amount = amount;
        this.canEmit = craftingService.canEmitFor(this.what);
        this.color = parent == null ? -1 : PatternColorHelper.getPatternColor(parent.details);
    }

    private AEKey findCraftedStack(ICraftingService craftingService, AEKey candidate) {
        if (craftingService.canEmitFor(candidate)) {
            return candidate;
        }

        Collection<IPatternDetails> patterns = craftingService.getCraftingFor(candidate);
        if (patterns.isEmpty() && this.parentInput != null) {
            long acceptableAmount = this.parentInput.getPossibleInputs()[0].amount();
            for (GenericStack possibleInput : this.parentInput.getPossibleInputs()) {
                if (possibleInput.amount() != acceptableAmount) {
                    continue;
                }

                AEKey fuzzy = craftingService.getFuzzyCraftable(
                        possibleInput.what(),
                        fuzzyCandidate -> this.parentInput.isValid(fuzzyCandidate, job.level())
                );
                if (fuzzy != null) {
                    return fuzzy;
                }
            }
        }

        return candidate;
    }

    private void buildChildPatterns() {
        if (this.canEmit) {
            throw new IllegalStateException("Internal AE2 error: emitable node should not use patterns");
        }
        if (this.nodes != null) {
            return;
        }

        this.nodes = new ArrayList<>();
        var gridNode = this.job.simRequester.getGridNode();
        if (gridNode == null) {
            return;
        }

        ICraftingService craftingService = gridNode.getGrid().getCraftingService();
        Collection<IPatternDetails> allPatterns = craftingService.getCraftingFor(this.what);
        List<IPatternDetails> validPatterns = new ArrayList<>();
        for (IPatternDetails details : allPatterns) {
            if (this.parent == null || this.parent.notRecursive(details)) {
                validPatterns.add(details);
            }
        }

        if (this.color != -1) {
            validPatterns.sort(Comparator.comparingInt(
                    details -> PatternColorHelper.getPatternColor(details) == this.color ? 0 : 1
            ));
        }

        for (IPatternDetails details : validPatterns) {
            this.nodes.add(new DyeablePatternCraftingTreeProcess(craftingService, job, details, this));
        }
    }

    boolean notRecursive(IPatternDetails details) {
        for (GenericStack output : details.getOutputs()) {
            if (this.what.matches(output)) {
                return false;
            }
        }

        for (var input : details.getInputs()) {
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null) {
                continue;
            }
            for (GenericStack possibleInput : possibleInputs) {
                if (this.what.matches(possibleInput)) {
                    return false;
                }
            }
        }

        return this.parent == null || this.parent.notRecursive(details);
    }

    void request(
            CraftingSimulationState inventory,
            long requestedAmount,
            @Nullable KeyCounter containerItems
    ) throws CraftBranchFailure, InterruptedException {
        this.job.handlePausing();
        inventory.addStackBytes(this.what, this.amount, requestedAmount);

        for (InputTemplate template : getValidItemTemplates(inventory, requestedAmount)) {
            long extracted = CraftingCpuHelper.extractTemplates(inventory, template, requestedAmount);
            if (extracted > 0L) {
                requestedAmount -= extracted;
                addContainerItems(template.key(), extracted, containerItems);
                if (requestedAmount == 0L) {
                    return;
                }
            }
        }

        addContainerItems(this.what, requestedAmount, containerItems);

        if (this.canEmit) {
            inventory.emitItems(this.what, this.amount * requestedAmount);
            return;
        }

        buildChildPatterns();
        long totalRequestedItems = requestedAmount * this.amount;
        long fulfilledByRootRing = tryRootRingReplacement(inventory, totalRequestedItems);
        if (fulfilledByRootRing > 0L) {
            totalRequestedItems -= fulfilledByRootRing;
            if (totalRequestedItems <= 0L) {
                return;
            }
        }

        for (DyeablePatternCraftingTreeProcess process : Objects.requireNonNull(this.nodes)) {
            if (this.job.isSimulation()) {
                totalRequestedItems -= tryProcessRingReplacement(inventory, process, totalRequestedItems);
                if (totalRequestedItems <= 0L) {
                    return;
                }
            }

            totalRequestedItems -= executeProcess(inventory, process, totalRequestedItems);
            if (!this.job.isSimulation() && totalRequestedItems > 0L) {
                totalRequestedItems -= tryProcessRingReplacement(inventory, process, totalRequestedItems);
            }

            if (totalRequestedItems <= 0L) {
                return;
            }
        }

        if (totalRequestedItems > 0L) {
            if (this.job.isSimulation()) {
                this.job.addMissing(this.what, totalRequestedItems);
            } else {
                throw new CraftBranchFailure(this.what, totalRequestedItems);
            }
        }
    }

    private long tryRootRingReplacement(CraftingSimulationState inventory, long requestedAmount)
            throws InterruptedException {
        if (this.parent != null || requestedAmount <= 0L) {
            return 0L;
        }

        int requestedColor = this.job.chexsonsaeutils$getRequestedColor();
        if (requestedColor == -1 || this.job.hasRingReplacementFailed(requestedColor, this.what)) {
            return 0L;
        }

        return tryRingReplacement(inventory, requestedAmount, requestedColor);
    }

    private long executeProcess(
            CraftingSimulationState inventory,
            DyeablePatternCraftingTreeProcess process,
            long neededAmount
    ) throws InterruptedException {
        long totalProduced = 0L;
        while (neededAmount > 0L) {
            long producedInAttempt = 0L;
            try {
                ChildCraftingSimulationState attemptInventory = new ChildCraftingSimulationState(inventory);
                long craftedPerPattern = process.getOutputCount(this.what);
                if (craftedPerPattern <= 0L) {
                    break;
                }

                long times = process.limitsQuantity()
                        ? 1L
                        : (neededAmount + craftedPerPattern - 1L) / craftedPerPattern;
                process.request(attemptInventory, times);

                producedInAttempt = attemptInventory.extract(this.what, neededAmount, Actionable.MODULATE);
                if (producedInAttempt > 0L) {
                    attemptInventory.applyDiff(inventory);
                    neededAmount -= producedInAttempt;
                    totalProduced += producedInAttempt;
                }
            } catch (CraftBranchFailure failure) {
                break;
            }

            if (producedInAttempt == 0L) {
                break;
            }
        }
        return totalProduced;
    }

    private long tryProcessRingReplacement(
            CraftingSimulationState inventory,
            DyeablePatternCraftingTreeProcess process,
            long requestedAmount
    ) throws InterruptedException {
        int processColor = PatternColorHelper.getPatternColor(process.details);
        boolean previousFailure = this.job.hasRingReplacementFailed(processColor, this.what);
        if (requestedAmount <= 0L || previousFailure) {
            return 0L;
        }

        var gridNode = this.job.simRequester.getGridNode();
        if (gridNode == null) {
            return 0L;
        }

        ICraftingService craftingService = gridNode.getGrid().getCraftingService();
        DyeablePatternCompressedRing ring = this.job.getCompressedRing(craftingService, processColor, this.what);
        if (!DyeablePatternCraftingPlanner.shouldTryProcessRingReplacement(
                this.color,
                processColor,
                ring,
                this.what,
                previousFailure
        )) {
            return 0L;
        }

        return tryRingReplacement(inventory, requestedAmount, processColor);
    }

    private long tryRingReplacement(CraftingSimulationState inventory, long requestedAmount, int ringColor)
            throws InterruptedException {
        var gridNode = this.job.simRequester.getGridNode();
        if (gridNode == null) {
            return 0L;
        }

        ICraftingService craftingService = gridNode.getGrid().getCraftingService();
        DyeablePatternCompressedRing ring = this.job.getCompressedRing(craftingService, ringColor, this.what);
        if (!DyeablePatternCraftingPlanner.isCompressedRingCalculable(ring)) {
            this.job.markRingReplacementAsFailed(ringColor, this.what);
            return 0L;
        }

        Set<Integer> ringsBeingReplaced = this.job.ringsBeingReplaced();
        if (ringsBeingReplaced.contains(ringColor)) {
            return 0L;
        }
        ringsBeingReplaced.add(ringColor);

        DyeablePatternCraftingTreeNode entryNode = findEntryNode(ring);
        if (entryNode == null) {
            ringsBeingReplaced.remove(ringColor);
            return 0L;
        }

        ChildCraftingSimulationState sandbox = new ChildCraftingSimulationState(inventory);
        KeyCounter ringExtractionsSnapshot = this.job.copyRingExtractions();
        Map<Integer, Set<AEKey>> failedRingReplacementsSnapshot = this.job.copyFailedRingReplacements();
        KeyCounter missingItemsSnapshot = this.job.copyMissingItems();
        boolean applied = false;
        try {
            long ringNetOutputAmount = ring.netOutputs().get(entryNode.what);
            if (ringNetOutputAmount <= 0L) {
                this.job.markRingReplacementAsFailed(ringColor, this.what);
                return 0L;
            }

            double scale = (double) requestedAmount / ringNetOutputAmount;
            this.job.requestRingDependencies(sandbox, craftingService, ring, scale);
            this.job.unpackRingOperations(sandbox, ring, scale);

            long fulfilled = sandbox.extract(this.what, requestedAmount, Actionable.MODULATE);
            if (fulfilled > 0L) {
                sandbox.applyDiff(inventory);
                applied = true;
                return fulfilled;
            }
        } catch (CraftBranchFailure failure) {
            this.job.markRingReplacementAsFailed(ringColor, this.what);
        } finally {
            if (!applied) {
                this.job.restoreRingExtractions(ringExtractionsSnapshot);
                this.job.restoreFailedRingReplacements(failedRingReplacementsSnapshot);
                this.job.restoreMissingItems(missingItemsSnapshot);
            }
            ringsBeingReplaced.remove(ringColor);
        }

        return 0L;
    }

    @Nullable
    private DyeablePatternCraftingTreeNode findEntryNode(DyeablePatternCompressedRing ring) {
        DyeablePatternCraftingTreeNode entryNode = null;
        DyeablePatternCraftingTreeNode cursor = this;
        while (cursor != null) {
            if (ring.entryPoints().contains(cursor.what)) {
                entryNode = cursor;
            }
            cursor = cursor.parent != null ? cursor.parent.parent : null;
        }
        return entryNode;
    }

    private Iterable<InputTemplate> getValidItemTemplates(ICraftingInventory inventory, long requestedAmount) {
        if (this.parentInput == null) {
            return List.of(new InputTemplate(this.what, 1L));
        }

        if (ReplacementAwareProcessingPattern.matchesAnyPossibleInput(this.parentInput, this.what)) {
            List<InputTemplate> groupedTemplates = git.chexson.chexsonsaeutils.pattern.replacement
                    .ReplacementGroupTemplateSelector.selectTemplates(
                            this.parentInput,
                            requestedAmount,
                            (candidate, requiredAmount) ->
                                    inventory.extract(candidate, requiredAmount, Actionable.SIMULATE) >= requiredAmount
                    );
            if (!groupedTemplates.isEmpty()) {
                return groupedTemplates;
            }
        }

        return CraftingCpuHelper.getValidItemTemplates(inventory, this.parentInput, this.job.level());
    }

    private void addContainerItems(AEKey template, long multiplier, @Nullable KeyCounter outputList) {
        if (outputList == null || this.parentInput == null) {
            return;
        }

        AEKey containerItem = this.parentInput.getRemainingKey(template);
        if (containerItem != null) {
            outputList.add(containerItem, multiplier);
        }
    }

    static AEKey selectReplacementAwarePlanningStack(
            IPatternDetails.IInput input,
            long amount,
            DyeablePatternCraftingCalculation job,
            ICraftingService craftingService,
            AEKey fallback
    ) {
        AEKey replacement = PlanningReplacementSelector.selectPlanningStack(
                input,
                amount,
                null,
                (candidate, requiredAmount) ->
                        job.networkInventory().extract(candidate, requiredAmount, Actionable.SIMULATE)
                                >= requiredAmount,
                craftingService::canEmitFor,
                whatToCraft -> !craftingService.getCraftingFor(whatToCraft).isEmpty(),
                craftingService::getFuzzyCraftable
        );
        return replacement == null ? fallback : replacement;
    }

    long getNodeCount() {
        long total = 1L;
        if (this.nodes != null) {
            for (DyeablePatternCraftingTreeProcess process : this.nodes) {
                total += process.getNodeCount();
            }
        }
        return total;
    }

    boolean hasMultiplePaths() {
        if (this.nodes == null) {
            return false;
        }
        if (this.nodes.size() > 1) {
            return true;
        }
        for (DyeablePatternCraftingTreeProcess process : this.nodes) {
            if (process.hasMultiplePaths()) {
                return true;
            }
        }
        return false;
    }
}
