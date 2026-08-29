package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.config.Actionable;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import org.jetbrains.annotations.Nullable;

public final class BulkPatternExtractionPlanner {

    private BulkPatternExtractionPlanner() {
    }

    public static int estimateMaxAdditionalExecutions(
            @Nullable ListCraftingInventory inventory,
            @Nullable KeyCounter[] perExecutionInputs,
            int maxAdditionalExecutions
    ) {
        if (inventory == null || perExecutionInputs == null || maxAdditionalExecutions <= 0) {
            return 0;
        }
        KeyCounter requiredPerExecution = flattenInputs(perExecutionInputs);
        if (requiredPerExecution.isEmpty()) {
            return 0;
        }
        long allowedExecutions = maxAdditionalExecutions;
        for (var entry : requiredPerExecution) {
            long requiredAmount = entry.getLongValue();
            if (requiredAmount <= 0L) {
                return 0;
            }
            long available = inventory.extract(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            allowedExecutions = Math.min(allowedExecutions, available / requiredAmount);
            if (allowedExecutions <= 0L) {
                return 0;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, allowedExecutions);
    }

    @Nullable
    public static BulkExtractionResult extractAdditionalExecutions(
            @Nullable ListCraftingInventory inventory,
            @Nullable KeyCounter[] perExecutionInputs,
            int requestedAdditionalExecutions
    ) {
        if (inventory == null || perExecutionInputs == null || requestedAdditionalExecutions <= 0) {
            return null;
        }
        KeyCounter requiredPerExecution = flattenInputs(perExecutionInputs);
        if (requiredPerExecution.isEmpty()) {
            return null;
        }
        int allowedExecutions = estimateMaxAdditionalExecutions(inventory, perExecutionInputs, requestedAdditionalExecutions);
        if (allowedExecutions <= 0) {
            return null;
        }
        KeyCounter extractedInputs = new KeyCounter();
        for (var entry : requiredPerExecution) {
            long amount = Math.multiplyExact(entry.getLongValue(), (long) allowedExecutions);
            long extracted = inventory.extract(entry.getKey(), amount, Actionable.MODULATE);
            if (extracted != amount) {
                reinjectCounters(inventory, extractedInputs);
                if (extracted > 0L) {
                    inventory.insert(entry.getKey(), extracted, Actionable.MODULATE);
                }
                return null;
            }
            extractedInputs.add(entry.getKey(), extracted);
        }
        return new BulkExtractionResult(allowedExecutions, new KeyCounter[]{extractedInputs});
    }

    private static KeyCounter flattenInputs(KeyCounter[] inputs) {
        KeyCounter flattened = new KeyCounter();
        for (KeyCounter input : inputs) {
            if (input == null) {
                continue;
            }
            for (var entry : input) {
                flattened.add(entry.getKey(), entry.getLongValue());
            }
        }
        return flattened;
    }

    private static void reinjectCounters(ListCraftingInventory inventory, KeyCounter counters) {
        for (var entry : counters) {
            inventory.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
        }
    }

    public record BulkExtractionResult(int logicalExecutions, KeyCounter[] reinjectableInputs) {
    }
}
