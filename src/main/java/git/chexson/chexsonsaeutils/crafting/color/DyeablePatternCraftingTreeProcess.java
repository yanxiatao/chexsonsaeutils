package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.inv.CraftingSimulationState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 染色样板 planning process。
 *
 * 复用 AE2 原生输入/输出模拟语义，只把递归样板的 ring 捕获信号透传给上层 node。
 */
final class DyeablePatternCraftingTreeProcess {
    final DyeablePatternCraftingTreeNode parent;
    final IPatternDetails details;
    private final DyeablePatternCraftingCalculation job;
    private final Map<DyeablePatternCraftingTreeNode, Long> nodes = new LinkedHashMap<>();
    private boolean containerItems;
    private boolean limitQty;

    DyeablePatternCraftingTreeProcess(
            ICraftingService craftingService,
            DyeablePatternCraftingCalculation job,
            IPatternDetails details,
            DyeablePatternCraftingTreeNode parent
    ) {
        this.parent = parent;
        this.details = details;
        this.job = job;
        updateLimitQty();

        IPatternDetails.IInput[] inputs = this.details.getInputs();
        for (int index = 0; index < inputs.length; index++) {
            var input = inputs[index];
            var firstInput = input.getPossibleInputs()[0];
            AEKey childKey = firstInput.what();
            if (this.details instanceof git.chexson.chexsonsaeutils.pattern.replacement.ReplacementAwareProcessingPattern) {
                childKey = DyeablePatternCraftingTreeNode.selectReplacementAwarePlanningStack(
                        input,
                        firstInput.amount(),
                        job,
                        craftingService,
                        childKey
                );
            }
            this.nodes.put(
                    new DyeablePatternCraftingTreeNode(
                            craftingService,
                            job,
                            childKey,
                            firstInput.amount(),
                            this,
                            index
                    ),
                    input.getMultiplier()
            );
        }
    }

    boolean notRecursive(IPatternDetails details) {
        return this.parent.notRecursive(details);
    }

    boolean limitsQuantity() {
        return this.limitQty;
    }

    private void updateLimitQty() {
        for (IPatternDetails.IInput input : details.getInputs()) {
            var primaryInput = input.getPossibleInputs()[0];
            boolean inputAlsoOutput = false;

            for (var output : details.getOutputs()) {
                if (output.what().matches(primaryInput)) {
                    inputAlsoOutput = true;
                    break;
                }
            }

            if (inputAlsoOutput) {
                this.limitQty = true;
            }

            if (input.getRemainingKey(primaryInput.what()) != null) {
                this.limitQty = true;
                this.containerItems = true;
            }
        }
    }

    void request(CraftingSimulationState inventory, long times, boolean captureRing)
            throws CraftBranchFailure, InterruptedException {
        this.job.handlePausing();
        KeyCounter containerItems = this.containerItems ? new KeyCounter() : null;

        for (var entry : this.nodes.entrySet()) {
            entry.getKey().request(inventory, entry.getValue() * times, containerItems, captureRing);
        }

        if (containerItems != null) {
            for (var stack : containerItems) {
                inventory.insert(stack.getKey(), stack.getLongValue(), Actionable.MODULATE);
                inventory.addStackBytes(stack.getKey(), stack.getLongValue(), 1L);
            }
        }

        for (var output : this.details.getOutputs()) {
            inventory.insert(output.what(), output.amount() * times, Actionable.MODULATE);
        }

        inventory.addCrafting(details, times);
        inventory.addBytes(times);
    }

    long getNodeCount() {
        long total = 0L;
        for (DyeablePatternCraftingTreeNode node : this.nodes.keySet()) {
            total += node.getNodeCount();
        }
        return total;
    }

    long getOutputCount(AEKey what) {
        long total = 0L;
        for (var output : this.details.getOutputs()) {
            if (what.matches(output)) {
                total += output.amount();
            }
        }
        return total;
    }

    boolean hasMultiplePaths() {
        for (DyeablePatternCraftingTreeNode node : this.nodes.keySet()) {
            if (node.hasMultiplePaths()) {
                return true;
            }
        }
        return false;
    }
}
