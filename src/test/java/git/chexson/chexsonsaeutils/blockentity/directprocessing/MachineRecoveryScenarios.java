package git.chexson.chexsonsaeutils.blockentity.directprocessing;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.util.SettingsFrom;
import git.chexson.chexsonsaeutils.crafting.directprocessing.PendingOutputBatch;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingExecutionQueue;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingTask;
import git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingTaskCompletionHost;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;

public final class MachineRecoveryScenarios {
    private MachineRecoveryScenarios() {}

    public static void verify(Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("直连加工机器拆除恢复",
                MachineRecoveryScenarios::runningQueuedAndCompletedWorkSurvivesItemRoundTrip,
                MachineRecoveryScenarios::memoryCardCannotCopyWork);
    }

    private static void runningQueuedAndCompletedWorkSurvivesItemRoundTrip() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var firstSource = UUID.randomUUID();
        var secondSource = UUID.randomUUID();
        var completedSource = UUID.randomUUID();
        var queue = new ProcessingExecutionQueue(8);
        var first = task(5, 3, firstSource);
        var second = task(2, 2, secondSource);
        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        var beforeRemoval = new CompletionSink();
        queue.tick(beforeRemoval, 1);
        assertEquals(1, queue.runningTaskCount());
        assertEquals(1, queue.queuedTaskCount());
        assertTrue(beforeRemoval.completed.isEmpty());
        var pending = new ArrayDeque<PendingOutputBatch>();
        pending.add(new PendingOutputBatch(List.of(new GenericStack(AEItemKey.of(Items.DIAMOND), 7)), null, completedSource));

        var components = DataComponentMap.builder();
        AEDirectProcessingMachineBlockEntity.exportDismantleWork(SettingsFrom.DISMANTLE_ITEM,
                components, queue, pending, registries);
        var carrier = new ItemStack(Items.CHEST);
        carrier.applyComponents(components.build());
        var itemNbt = carrier.saveOptional(registries);
        queue.clear();
        pending.clear();
        var restoredItem = ItemStack.parse(registries, itemNbt).orElseThrow();
        assertEquals(1, restoredItem.getCount(), "已接单任务不会展开成掉落物堆");
        var restoredQueue = new ProcessingExecutionQueue(8);
        var restoredPending = new ArrayDeque<PendingOutputBatch>();
        assertTrue(AEDirectProcessingMachineBlockEntity.importDismantleWork(SettingsFrom.DISMANTLE_ITEM,
                restoredItem.getComponents(), restoredQueue, restoredPending, registries));
        assertEquals(2, restoredQueue.totalTaskCount());
        assertEquals(1, restoredPending.size());
        assertEquals(completedSource, restoredPending.getFirst().sourceCraftingId());
        assertEquals(List.of(new GenericStack(AEItemKey.of(Items.DIAMOND), 7)), restoredPending.getFirst().payload());

        var resumed = new CompletionSink();
        for (int tick = 0; tick < 6; tick++) restoredQueue.tick(resumed, 1);
        assertTrue(restoredQueue.isIdle(), "只需原有剩余 4 + 2 tick，不得重启完整工时");
        assertEquals(2, resumed.completed.size());
        var bySource = resumed.completed.stream().collect(java.util.stream.Collectors.toMap(
                ProcessingTask::sourceCraftingId, ProcessingTask::buildOutputPayload));
        assertEquals(List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 6)), bySource.get(firstSource));
        assertEquals(List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 4)), bySource.get(secondSource));
        restoredQueue.tick(resumed, 1);
        assertEquals(2, resumed.completed.size(), "恢复任务只完成一次");
    }

    private static void memoryCardCannotCopyWork() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var queue = new ProcessingExecutionQueue(1);
        assertTrue(queue.offer(task(3, 1, UUID.randomUUID())));
        var pending = new ArrayDeque<PendingOutputBatch>();
        var memoryCard = DataComponentMap.builder();
        AEDirectProcessingMachineBlockEntity.exportDismantleWork(SettingsFrom.MEMORY_CARD,
                memoryCard, queue, pending, registries);
        assertFalse(memoryCard.build().has(DataComponents.CUSTOM_DATA));

        var recovery = DataComponentMap.builder();
        AEDirectProcessingMachineBlockEntity.exportDismantleWork(SettingsFrom.DISMANTLE_ITEM,
                recovery, queue, pending, registries);
        var destination = new ProcessingExecutionQueue(1);
        assertFalse(AEDirectProcessingMachineBlockEntity.importDismantleWork(SettingsFrom.MEMORY_CARD,
                recovery.build(), destination, new ArrayDeque<>(), registries));
        assertTrue(destination.isIdle());
        assertEquals(1, queue.totalTaskCount(), "导出只创建拆除快照，读取本身不消耗工作");
    }

    private static ProcessingTask task(long ticks, long executions, UUID source) {
        var task = new ProcessingTask(new ItemStack(Items.PAPER),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_ORE), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2)),
                ticks, ticks, executions);
        task.setSourceCraftingId(source);
        return task;
    }

    private static final class CompletionSink implements ProcessingTaskCompletionHost {
        private final List<ProcessingTask> completed = new ArrayList<>();
        @Override public void completeProcessingTask(ProcessingTask task) { completed.add(task); }
        @Override public boolean isWaitingForOutputReturn() { return false; }
        @Override public int getProcessingWorkUnitsPerTick() { return 1; }
    }
}
