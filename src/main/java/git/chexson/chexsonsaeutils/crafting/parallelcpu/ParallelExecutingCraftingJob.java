package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

final class ParallelExecutingCraftingJob {

    private static final String NBT_LINK = "link";
    private static final String NBT_PLAYER_ID = "playerId";
    private static final String NBT_FINAL_OUTPUT = "finalOutput";
    private static final String NBT_WAITING_FOR = "waitingFor";
    private static final String NBT_TIME_TRACKER = "timeTracker";
    private static final String NBT_REMAINING_AMOUNT = "remainingAmount";
    private static final String NBT_TASKS = "tasks";
    private static final String NBT_SUSPENDED = "suspended";
    private static final String NBT_CRAFTING_PROGRESS = "#craftingProgress";

    final CraftingLink link;
    final ListCraftingInventory waitingFor;
    final Map<IPatternDetails, TaskProgress> tasks = new HashMap<>();
    final ParallelElapsedTimeTracker timeTracker;
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
        this.timeTracker = new ParallelElapsedTimeTracker();

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

    ParallelExecutingCraftingJob(
            CompoundTag data,
            HolderLookup.Provider registries,
            CraftingDifferenceListener postCraftingDifference,
            ParallelCraftingLaneState lane
    ) {
        this.link = new CraftingLink(data.getCompound(NBT_LINK), lane.linkCpu());
        this.finalOutput = GenericStack.readTag(registries, data.getCompound(NBT_FINAL_OUTPUT));
        this.remainingAmount = data.getLong(NBT_REMAINING_AMOUNT);
        this.waitingFor = new ListCraftingInventory(postCraftingDifference::onCraftingDifference);
        this.waitingFor.readFromNBT(data.getList(NBT_WAITING_FOR, Tag.TAG_COMPOUND), registries);
        this.timeTracker = new ParallelElapsedTimeTracker(data.getCompound(NBT_TIME_TRACKER));
        this.playerId = data.contains(NBT_PLAYER_ID, Tag.TAG_INT) ? data.getInt(NBT_PLAYER_ID) : null;

        ListTag tasksTag = data.getList(NBT_TASKS, Tag.TAG_COMPOUND);
        for (int index = 0; index < tasksTag.size(); index++) {
            CompoundTag item = tasksTag.getCompound(index);
            AEItemKey pattern = AEItemKey.fromTag(registries, item);
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, lane.cluster().level());
            if (details != null) {
                TaskProgress progress = new TaskProgress();
                progress.value = item.getLong(NBT_CRAFTING_PROGRESS);
                this.tasks.put(details, progress);
            }
        }

        this.suspended = data.getBoolean(NBT_SUSPENDED);
    }

    CompoundTag writeToNBT(HolderLookup.Provider registries) {
        CompoundTag data = new CompoundTag();
        CompoundTag linkData = new CompoundTag();
        link.writeToNBT(linkData);
        data.put(NBT_LINK, linkData);
        if (finalOutput != null) {
            data.put(NBT_FINAL_OUTPUT, GenericStack.writeTag(registries, finalOutput));
        }
        data.put(NBT_WAITING_FOR, waitingFor.writeToNBT(registries));
        data.put(NBT_TIME_TRACKER, timeTracker.writeToNBT());

        ListTag taskList = new ListTag();
        for (var entry : tasks.entrySet()) {
            CompoundTag item = entry.getKey().getDefinition().toTag(registries);
            item.putLong(NBT_CRAFTING_PROGRESS, entry.getValue().value);
            taskList.add(item);
        }
        data.put(NBT_TASKS, taskList);
        data.putLong(NBT_REMAINING_AMOUNT, remainingAmount);
        if (playerId != null) {
            data.putInt(NBT_PLAYER_ID, playerId);
        }
        data.putBoolean(NBT_SUSPENDED, suspended);
        return data;
    }

    static void addMaxItems(ParallelElapsedTimeTracker tracker, long itemDiff, @Nullable AEKeyType keyType) {
        if (tracker == null || itemDiff <= 0L || keyType == null) {
            return;
        }
        tracker.addMaxItems(itemDiff, keyType);
    }

    static void decrementItems(ParallelElapsedTimeTracker tracker, long itemDiff, @Nullable AEKeyType keyType) {
        if (tracker == null || itemDiff <= 0L || keyType == null) {
            return;
        }
        tracker.decrementItems(itemDiff, keyType);
    }

    static final class TaskProgress {
        long value;
    }
}
