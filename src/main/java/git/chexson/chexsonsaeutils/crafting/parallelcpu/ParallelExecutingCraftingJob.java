package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

final class ParallelExecutingCraftingJob {

    private static final Method ADD_MAX_ITEMS_METHOD = findTrackerMethod("addMaxItems");
    private static final Method DECREMENT_ITEMS_METHOD = findTrackerMethod("decrementItems");

    final CraftingLink link;
    final ListCraftingInventory waitingFor;
    final Map<IPatternDetails, TaskProgress> tasks = new LinkedHashMap<>();
    final ElapsedTimeTracker timeTracker = new ElapsedTimeTracker();
    GenericStack finalOutput;
    long remainingAmount;
    @Nullable
    Integer playerId;
    boolean suspended;

    @FunctionalInterface
    interface CraftingDifferenceListener {
        void onCraftingDifference(appeng.api.stacks.AEKey what);
    }

    ParallelExecutingCraftingJob(
            ICraftingPlan plan,
            CraftingDifferenceListener postCraftingDifference,
            CraftingLink link,
            @Nullable Integer playerId
    ) {
        this.finalOutput = plan.finalOutput();
        this.remainingAmount = this.finalOutput == null ? 0L : this.finalOutput.amount();
        this.waitingFor = new ListCraftingInventory(postCraftingDifference::onCraftingDifference);

        for (var entry : plan.emittedItems()) {
            waitingFor.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            addMaxItems(timeTracker, entry.getLongValue(), entry.getKey().getType());
        }
        for (var entry : plan.patternTimes().entrySet()) {
            long executions = Math.max(0L, entry.getValue());
            if (executions <= 0L) {
                continue;
            }
            tasks.computeIfAbsent(entry.getKey(), ignored -> new TaskProgress()).value += executions;
            for (var output : entry.getKey().getOutputs()) {
                long amount = output.amount() * executions * output.what().getAmountPerUnit();
                addMaxItems(timeTracker, amount, output.what().getType());
            }
        }

        this.link = link;
        this.playerId = playerId;
        this.suspended = false;
    }

    static void addMaxItems(ElapsedTimeTracker tracker, long itemDiff, @Nullable AEKeyType keyType) {
        if (tracker == null || itemDiff <= 0L || keyType == null) {
            return;
        }
        invokeTrackerMethod(ADD_MAX_ITEMS_METHOD, tracker, itemDiff, keyType);
    }

    static void decrementItems(ElapsedTimeTracker tracker, long itemDiff, @Nullable AEKeyType keyType) {
        if (tracker == null || itemDiff <= 0L || keyType == null) {
            return;
        }
        invokeTrackerMethod(DECREMENT_ITEMS_METHOD, tracker, itemDiff, keyType);
    }

    private static Method findTrackerMethod(String name) {
        try {
            Method method = ElapsedTimeTracker.class.getDeclaredMethod(name, long.class, AEKeyType.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to access ElapsedTimeTracker." + name, exception);
        }
    }

    private static void invokeTrackerMethod(
            Method method,
            ElapsedTimeTracker tracker,
            long itemDiff,
            AEKeyType keyType
    ) {
        try {
            method.invoke(tracker, itemDiff, keyType);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Failed to invoke ElapsedTimeTracker." + method.getName(),
                    exception
            );
        }
    }

    static final class TaskProgress {
        long value;
    }
}
