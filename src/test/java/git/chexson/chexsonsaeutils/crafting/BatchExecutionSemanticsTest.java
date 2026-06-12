package git.chexson.chexsonsaeutils.crafting;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import git.chexson.chexsonsaeutils.blockentity.crafting.BatchExecutionMode;
import git.chexson.chexsonsaeutils.blockentity.crafting.CompiledTask;
import git.chexson.chexsonsaeutils.blockentity.crafting.DynamicExecutionBudgetModel;
import git.chexson.chexsonsaeutils.blockentity.crafting.LocalExecutionQueue;
import git.chexson.chexsonsaeutils.blockentity.crafting.PendingCompletionWork;
import git.chexson.chexsonsaeutils.blockentity.crafting.PendingAeReturn;
import git.chexson.chexsonsaeutils.blockentity.crafting.QueueBudgetContext;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskCompletionRoute;
import git.chexson.chexsonsaeutils.blockentity.crafting.TaskState;
import git.chexson.chexsonsaeutils.crafting.formalmachine.BulkPatternExtractionPlanner;
import git.chexson.chexsonsaeutils.support.TestKeySupport;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static appeng.api.config.Actionable.MODULATE;
import static appeng.api.config.Actionable.SIMULATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchExecutionSemanticsTest {

    @Test
    void compiledTaskCanCoalesceWhenQueuedAndCompatible() {
        CompiledTask base = newTask(20, 1);
        CompiledTask other = newTask(20, 1);

        assertTrue(base.canCoalesceWith(other, 4));
        base.appendExecutionCount(other.getExecutionCount());
        assertEquals(2, base.getExecutionCount());
    }

    @Test
    void compiledTaskDoesNotCoalesceWhenTicksDifferOrCapExceeded() {
        CompiledTask base = newTask(20, 3);
        CompiledTask differentTicks = newTask(40, 1);
        CompiledTask overflow = newTask(20, 2);

        assertFalse(base.canCoalesceWith(differentTicks, 4));
        assertFalse(base.canCoalesceWith(overflow, 4));
    }

    @Test
    void compiledTaskDrainCoalescesWithRunningCompatibleTask() {
        CompiledTask running = newTask(20, 1);
        CompiledTask incoming = newTask(20, 1);

        running.advance(5);

        assertFalse(running.canCoalesceWith(incoming, 1024));
        assertTrue(running.canDrainCoalesceWith(incoming, 1024));
        running.appendExecutionCount(incoming.getExecutionCount());
        assertEquals(2, running.getExecutionCount());
    }

    @Test
    void compiledTaskDrainDoesNotCoalesceWithReadyOutputTask() {
        CompiledTask ready = newTask(20, 1);
        CompiledTask incoming = newTask(20, 1);

        ready.advance(20);

        assertFalse(ready.canDrainCoalesceWith(incoming, 1024));
    }

    @Test
    void executionCountDoesNotChangePhysicalProgressRate() {
        CompiledTask single = newTask(20, 1);
        CompiledTask batch = newTask(20, 8);

        single.advance(1);
        batch.advance(1);

        assertEquals(19, single.getRemainingTicks());
        assertEquals(19, batch.getRemainingTicks());
    }

    @Test
    void localExecutionQueueOnlyCoalescesPendingTail() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        CompiledTask first = newTask(20, 1);
        CompiledTask second = newTask(20, 1);
        CompiledTask third = newTask(40, 1);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(first, BatchExecutionMode.SAME_PATTERN_COALESCE, 4));
        assertEquals(LocalExecutionQueue.CoalesceOfferResult.COALESCED,
                queue.offerOrCoalesce(second, BatchExecutionMode.SAME_PATTERN_COALESCE, 4));
        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(third, BatchExecutionMode.SAME_PATTERN_COALESCE, 4));

        assertEquals(2, queue.queuedTaskCount());
        assertEquals(2, queue.getAllTasksForTest().get(0).getExecutionCount());
        assertEquals(1, queue.getAllTasksForTest().get(1).getExecutionCount());
    }

    @Test
    void localExecutionQueueDrainCoalescesAcrossNonTailMatches() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        CompiledTask first = newTask(20, 1);
        CompiledTask second = newTask(40, 1);
        CompiledTask third = newTask(20, 1);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(first, BatchExecutionMode.SAME_PATTERN_DRAIN, 1));
        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(second, BatchExecutionMode.SAME_PATTERN_DRAIN, 1));
        assertEquals(LocalExecutionQueue.CoalesceOfferResult.COALESCED,
                queue.offerOrCoalesce(third, BatchExecutionMode.SAME_PATTERN_DRAIN, 1));

        assertEquals(2, queue.queuedTaskCount());
        assertEquals(2, queue.getAllTasksForTest().get(0).getExecutionCount());
        assertEquals(1, queue.getAllTasksForTest().get(1).getExecutionCount());
    }

    @Test
    void localExecutionQueueDrainCoalescesIntoUnstartedActiveMatch() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        CompiledTask first = newTask(20, 1);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(first, BatchExecutionMode.SAME_PATTERN_DRAIN, 1));
        queue.reconfigureLaneCount(1);
        assignIdleLanes(queue);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.COALESCED,
                queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 1));

        assertEquals(0, queue.queuedTaskCount());
        assertEquals(2, queue.getActiveTasksForTest().get(0).getExecutionCount());
    }

    @Test
    void localExecutionQueueDrainCoalescesIntoRunningMatch() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        CompiledTask first = newTask(20, 1);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(first, BatchExecutionMode.SAME_PATTERN_DRAIN, 1));
        queue.reconfigureLaneCount(1);
        assignIdleLanes(queue);
        CompiledTask active = queue.getActiveTasksForTest().get(0);
        active.advance(5);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.COALESCED,
                queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 1));

        assertEquals(0, queue.queuedTaskCount());
        assertEquals(2, queue.getActiveTasksForTest().get(0).getExecutionCount());
    }

    @Test
    void localExecutionQueueDrainCoalescesIntoRunningMatchAtTaskCapacity() {
        LocalExecutionQueue queue = new LocalExecutionQueue(1);
        CompiledTask first = newTask(20, 1);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(first, BatchExecutionMode.SAME_PATTERN_DRAIN, 1));
        queue.reconfigureLaneCount(1);
        assignIdleLanes(queue);
        queue.getActiveTasksForTest().get(0).advance(5);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.COALESCED,
                queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 1));

        assertEquals(1, queue.runningTaskCount());
        assertEquals(2, queue.getActiveTasksForTest().get(0).getExecutionCount());
    }

    @Test
    void localExecutionQueueDrainAggregatesLargeBurstBeyondLaneCount() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);

        for (int index = 0; index < 250; index++) {
            LocalExecutionQueue.CoalesceOfferResult result = queue.offerOrCoalesce(
                    newTask(20, 1),
                    BatchExecutionMode.SAME_PATTERN_DRAIN,
                    1
            );
            assertEquals(index == 0
                    ? LocalExecutionQueue.CoalesceOfferResult.ACCEPTED
                    : LocalExecutionQueue.CoalesceOfferResult.COALESCED, result);
        }

        assertEquals(1, queue.queuedTaskCount());
        assertEquals(250, queue.getAllTasksForTest().get(0).getExecutionCount());
    }

    @Test
    void localExecutionQueueDrainSpreadsLargeBurstAcrossLaneWindow() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);

        for (int index = 0; index < 250; index++) {
            LocalExecutionQueue.CoalesceOfferResult result = queue.offerOrCoalesce(
                    newTask(20, 1),
                    BatchExecutionMode.SAME_PATTERN_DRAIN,
                    4
            );
            assertEquals(index % 4 == 0 && index < 16
                    ? LocalExecutionQueue.CoalesceOfferResult.ACCEPTED
                    : LocalExecutionQueue.CoalesceOfferResult.COALESCED, result);
        }

        assertEquals(4, queue.queuedTaskCount());
        assertEquals(250, queue.getAllTasksForTest().stream().mapToInt(CompiledTask::getExecutionCount).sum());
        assertEquals(63, queue.getAllTasksForTest().stream().mapToInt(CompiledTask::getExecutionCount).max().orElse(0));
    }

    @Test
    void localExecutionQueueDrainOpensParallelTasksAfterSmallBatchThreshold() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        queue.reconfigureLaneCount(4);

        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 4));
        assignIdleLanes(queue);

        for (int index = 0; index < 3; index++) {
            assertEquals(LocalExecutionQueue.CoalesceOfferResult.COALESCED,
                    queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 4));
            assignIdleLanes(queue);
        }
        assertEquals(LocalExecutionQueue.CoalesceOfferResult.ACCEPTED,
                queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 4));
        assignIdleLanes(queue);
        for (int index = 0; index < 11; index++) {
            queue.offerOrCoalesce(newTask(20, 1), BatchExecutionMode.SAME_PATTERN_DRAIN, 4);
            assignIdleLanes(queue);
        }

        assertEquals(4, queue.runningTaskCount());
        assertEquals(0, queue.queuedTaskCount());
        assertEquals(16, queue.getActiveTasksForTest().stream().mapToInt(CompiledTask::getExecutionCount).sum());
    }

    @Test
    void localExecutionQueueDrainCanGrowBeyondDynamicPhysicalTaskBudget() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 1, 1, 1, 1, 2_048, 8, 0, 1, false);
        QueueBudgetContext budgetContext = new QueueBudgetContext(
                BatchExecutionMode.SAME_PATTERN_DRAIN,
                model,
                1
        );
        int requestedExecutions = model.physicalTaskExecutionBudget() + 1;

        for (int index = 0; index < requestedExecutions; index++) {
            queue.offerOrCoalesce(newTask(20, 1), budgetContext);
        }

        assertEquals(1, queue.queuedTaskCount());
        assertEquals(requestedExecutions, queue.getAllTasksForTest().stream()
                .mapToInt(CompiledTask::getExecutionCount)
                .sum());
        assertEquals(requestedExecutions, queue.getAllTasksForTest().get(0).getExecutionCount());
    }

    @Test
    void formalMachineDrainBatchCanGrowBeyondLegacyFixedCapWhenDynamicBudgetAllows() {
        LocalExecutionQueue queue = new LocalExecutionQueue(16);
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(
                128,
                128,
                1,
                1,
                1,
                1,
                4_096,
                0,
                0,
                1,
                false
        );
        QueueBudgetContext budgetContext = new QueueBudgetContext(
                BatchExecutionMode.SAME_PATTERN_DRAIN,
                model,
                1
        );
        int legacyFixedCap = 250;
        int requestedExecutions = legacyFixedCap + 1;

        for (int index = 0; index < requestedExecutions; index++) {
            LocalExecutionQueue.CoalesceOfferResult result = queue.offerOrCoalesce(newTask(20, 1), budgetContext);

            assertEquals(index == 0
                    ? LocalExecutionQueue.CoalesceOfferResult.ACCEPTED
                    : LocalExecutionQueue.CoalesceOfferResult.COALESCED, result);
        }

        assertTrue(model.physicalTaskExecutionBudget() > legacyFixedCap);
        assertEquals(1, queue.queuedTaskCount());
        assertEquals(requestedExecutions, queue.countQueuedLogicalExecutions());
        assertEquals(requestedExecutions, queue.getAllTasksForTest().get(0).getExecutionCount());
    }

    @Test
    void laneActivationGateDoesNotConsumeCompletionBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 3, 8, 32, 8, 0, 4, false);

        int softBefore = model.remainingSoftBudget();
        int hardBefore = model.remainingHardBudget();

        assertTrue(model.canActivateLane(0, true));
        assertTrue(model.canActivateLane(1, true));
        assertEquals(softBefore, model.remainingSoftBudget());
        assertEquals(hardBefore, model.remainingHardBudget());
    }

    @Test
    void dispatchGateDoesNotConsumeCompletionBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 3, 8, 32, 8, 0, 4, false);

        int softBefore = model.remainingSoftBudget();
        int hardBefore = model.remainingHardBudget();

        assertTrue(model.canDispatchExecution());
        assertTrue(model.canDispatchExecution());
        assertEquals(softBefore, model.remainingSoftBudget());
        assertEquals(hardBefore, model.remainingHardBudget());
    }

    @Test
    void fastPathGateDoesNotConsumeAnyDynamicBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 3, 8, 32, 8, 0, 4, false);

        int softBefore = model.remainingSoftBudget();
        int hardBefore = model.remainingHardBudget();
        int completionSliceBefore = model.remainingCompletionSliceBudget();
        int extractionBudget = model.fastPathExtractionBudget();

        for (int index = 0; index < extractionBudget + 1; index++) {
            assertTrue(model.tryClaimFastPathExtractionExecution());
        }
        assertEquals(extractionBudget, model.remainingFastPathExtractionBudget());
        assertEquals(completionSliceBefore, model.remainingCompletionSliceBudget());
        assertEquals(softBefore, model.remainingSoftBudget());
        assertEquals(hardBefore, model.remainingHardBudget());
    }

    @Test
    void fastPathGateDoesNotConsumeCompletionSliceBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 3, 8, 32, 8, 0, 4, false);

        int completionSliceBefore = model.remainingCompletionSliceBudget();
        int extractionBudget = model.fastPathExtractionBudget();

        for (int index = 0; index < extractionBudget; index++) {
            assertTrue(model.tryClaimFastPathExtractionExecution());
        }

        assertEquals(completionSliceBefore, model.remainingCompletionSliceBudget());
    }

    @Test
    void blockedFastPathExtractionBudgetDoesNotClaim() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 3, 8, 32, 8, 0, 4, true);

        assertFalse(model.tryClaimFastPathExtractionExecution());
        assertEquals(model.fastPathExtractionBudget(), model.remainingFastPathExtractionBudget());
    }

    @Test
    void batchAppendStillConsumesBatchAssemblyBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 3, 8, 1, 8, 0, 4, false);

        assertTrue(model.canAppendBatchExecution(false));
        assertFalse(model.canAppendBatchExecution(false));
    }

    @Test
    void completionStillConsumesCompletionBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 4, 8, 32, 8, 0, 4, false);

        int softBefore = model.remainingSoftBudget();
        int hardBefore = model.remainingHardBudget();

        assertTrue(model.canCompleteAnotherTask(0));
        assertTrue(model.remainingSoftBudget() < softBefore);
        assertTrue(model.remainingHardBudget() < hardBefore);
    }

    @Test
    void completionSliceClaimConsumesSliceBudget() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 4, 8, 32, 8, 0, 4, false);

        int initialSliceBudget = model.remainingCompletionSliceBudget();
        int claimed = model.claimCompletionSliceExecutions(64);

        assertTrue(claimed > 0);
        assertTrue(model.remainingCompletionSliceBudget() < initialSliceBudget);
        assertTrue(model.remainingHardBudget() < model.hardBudget());
        assertTrue(model.remainingSoftBudget() < model.softBudget());
    }

    @Test
    void laneActivationTargetPreventsUnboundedLaneExpansion() {
        DynamicExecutionBudgetModel model = new DynamicExecutionBudgetModel(24, 32, 4, 6, 4, 8, 32, 16, 0, 4, false);

        assertTrue(model.canActivateLane(0, true));
        assertTrue(model.canActivateLane(5, false));
        assertFalse(model.canActivateLane(6, false));
    }

    @Test
    void pendingAeReturnKeepsAggregatedPayload() {
        TestKey planks = new TestKey("oak_planks");
        TestKey bucket = new TestKey("bucket");
        PendingAeReturn pending = new PendingAeReturn(
                new GenericStack(planks, 128),
                List.of(
                        new GenericStack(bucket, 2),
                        new GenericStack(new TestKey("stick"), 16)
                ),
                4
        );

        assertNotNull(pending);
        assertEquals(4, pending.logicalExecutionCount());
        assertEquals(128, pending.primaryResult().amount());
        assertEquals(2, pending.remainingItems().size());
        assertEquals(bucket, pending.remainingItems().get(0).what());
        assertEquals(2, pending.remainingItems().get(0).amount());
    }

    @Test
    void pendingCompletionWorkKeepsTemplateAndAggregatedPayload() {
        TestKey pickaxe = new TestKey("wooden_pickaxe");
        CompiledTask task = newTask(20, 8);
        task.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        task.setSupportsTemplatedCompletion(true);
        UUID sourceCraftingId = UUID.randomUUID();
        task.setSourceCraftingId(sourceCraftingId);

        PendingCompletionWork work = new PendingCompletionWork(task);
        assertNull(work.toPendingAeReturn());

        work.setTemplate(new GenericStack(pickaxe, 1), java.util.Map.of());
        work.appendPrimary(new GenericStack(pickaxe, 8));
        work.advanceExecutions(8);
        work.markSliceProcessed(8);

        assertTrue(work.isComplete());
        assertTrue(work.hasTemplate());
        assertEquals(TaskCompletionRoute.CPU_WAITING, work.completionRoute());
        assertEquals(8, work.completedExecutions());
        assertEquals(8, work.totalExecutions());
        assertEquals(8, work.lastSliceSize());
        assertEquals(1, work.unsavedSliceCounter());
        assertTrue(work.aggregatedRemainders().isEmpty());
        assertNotNull(work.templatePrimary());
        assertEquals(1L, work.templatePrimary().amount());
        assertNotNull(work.aggregatedPrimary());
        assertEquals(8L, work.aggregatedPrimary().amount());

        PendingAeReturn pending = work.toPendingAeReturn();
        assertNotNull(pending);
        assertEquals(TaskCompletionRoute.CPU_WAITING, pending.completionRoute());
        assertEquals(8, pending.logicalExecutionCount());
        assertEquals(8L, pending.primaryResult().amount());
        assertEquals(sourceCraftingId, pending.sourceCraftingId());
        assertTrue(pending.remainingItems().isEmpty());
    }

    @Test
    void compiledTaskDoesNotShareBatchKeyAcrossDifferentSourceCraftingIds() {
        CompiledTask left = newTask(20, 1);
        CompiledTask right = newTask(20, 1);
        left.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        right.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        left.setSourceCraftingId(UUID.randomUUID());
        right.setSourceCraftingId(UUID.randomUUID());

        assertNotEquals(left.getSourceCraftingId(), right.getSourceCraftingId());
        assertFalse(left.hasSameBatchKey(right));
        assertFalse(left.canCoalesceWith(right, 4));
    }

    @Test
    void compiledTaskStoresAndClearsCompletionTemplateMetadata() {
        DummyKey output = new DummyKey("stick_output");
        CompiledTask task = newTask(20, 8);
        task.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        task.setCompletionTemplate(
                new GenericStack(output, 2L),
                Map.of()
        );

        assertTrue(task.supportsTemplatedCompletion());
        assertTrue(task.hasCompletionTemplate());
        assertNotNull(task.getCompletionTemplatePrimary());
        assertEquals(output, task.getCompletionTemplatePrimary().what());
        assertEquals(2L, task.getCompletionTemplatePrimary().amount());
        assertTrue(task.getCompletionTemplateRemainders().isEmpty());
        assertEquals(TaskCompletionRoute.CPU_WAITING, task.getCompletionRoute());

        task.clearCompletionTemplate();

        assertFalse(task.hasCompletionTemplate());
        assertNull(task.getCompletionTemplatePrimary());
        assertTrue(task.getCompletionTemplateRemainders().isEmpty());
    }

    @Test
    void pendingCompletionWorkInheritsCompiledTaskTemplateMetadata() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey output = new DummyKey("oak_planks_output");
        CompiledTask task = newTask(20, 4);
        task.setCompletionRoute(TaskCompletionRoute.CPU_WAITING);
        task.setCompletionTemplate(
                new GenericStack(output, 4L),
                Map.of()
        );

        PendingCompletionWork work = new PendingCompletionWork(task);

        assertTrue(work.hasTemplate());
        assertNotNull(work.templatePrimary());
        assertEquals(output, work.templatePrimary().what());
        assertEquals(4L, work.templatePrimary().amount());
        assertTrue(work.templateRemainders().isEmpty());
        assertEquals(TaskCompletionRoute.CPU_WAITING, work.completionRoute());
    }

    @Test
    void bulkPatternExtractionPlannerEstimatesMaximumAdditionalExecutions() {
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        TestKey primary = new TestKey("oak_log");
        TestKey secondary = new TestKey("stick");
        KeyCounter slotA = new KeyCounter();
        slotA.add(primary, 2L);
        KeyCounter slotB = new KeyCounter();
        slotB.add(secondary, 4L);
        inventory.insert(primary, 12L, MODULATE);
        inventory.insert(secondary, 24L, MODULATE);

        int estimated = BulkPatternExtractionPlanner.estimateMaxAdditionalExecutions(
                inventory,
                new KeyCounter[]{slotA, slotB},
                10
        );

        assertEquals(6, estimated);
    }

    @Test
    void bulkPatternExtractionPlannerExtractsScaledInputsInOnePass() {
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {
        });
        TestKey primary = new TestKey("oak_log");
        TestKey secondary = new TestKey("stick");
        KeyCounter slotA = new KeyCounter();
        slotA.add(primary, 2L);
        KeyCounter slotB = new KeyCounter();
        slotB.add(secondary, 4L);
        inventory.insert(primary, 12L, MODULATE);
        inventory.insert(secondary, 24L, MODULATE);

        BulkPatternExtractionPlanner.BulkExtractionResult result = BulkPatternExtractionPlanner.extractAdditionalExecutions(
                inventory,
                new KeyCounter[]{slotA, slotB},
                5
        );

        assertNotNull(result);
        assertEquals(5, result.logicalExecutions());
        assertEquals(1, result.reinjectableInputs().length);
        assertEquals(10L, result.reinjectableInputs()[0].get(primary));
        assertEquals(20L, result.reinjectableInputs()[0].get(secondary));
        assertEquals(2L, inventory.extract(primary, Long.MAX_VALUE, SIMULATE));
        assertEquals(4L, inventory.extract(secondary, Long.MAX_VALUE, SIMULATE));
    }

    @Test
    void bulkPatternExtractionPlannerCapsBeforeIntegerOverflow() {
        CompiledTask task = newTask(20, Integer.MAX_VALUE - 3);

        int capped = BulkPatternExtractionPlanner.capAdditionalExecutionsForTask(task, Long.MAX_VALUE);

        assertEquals(3, capped);
    }

    private static CompiledTask newTask(int ticks, int executionCount) {
        try {
            Constructor<CompiledTask> constructor = CompiledTask.class.getDeclaredConstructor(
                    UUID.class,
                    ItemStack.class,
                    ItemStack[].class,
                    int.class,
                    int.class,
                    int.class,
                    TaskState.class,
                    appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                    UUID.randomUUID(),
                    null,
                    new ItemStack[9],
                    ticks,
                    executionCount,
                    ticks,
                    TaskState.QUEUED,
                    null
            );
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to construct CompiledTask for queue semantics test", exception);
        }
    }

    private static void assignIdleLanes(LocalExecutionQueue queue) {
        try {
            Method method = LocalExecutionQueue.class.getDeclaredMethod("assignIdleLanes");
            method.setAccessible(true);
            method.invoke(queue);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to assign idle lanes for queue semantics test", exception);
        }
    }

    private static final class TestKey extends AEKey {

        private static final MapCodec<TestKey> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(TestKey::id)
        ).apply(instance, TestKey::new));

        private final String id;

        private TestKey(String id) {
            this.id = id;
        }

        private String id() {
            return id;
        }

        @Override
        public AEKeyType getType() {
            return TestKeyType.INSTANCE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag(HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            return tag;
        }

        @Override
        public Object getPrimaryKey() {
            return id;
        }

        @Override
        public int getFuzzySearchValue() {
            return 0;
        }

        @Override
        public int getFuzzySearchMaxValue() {
            return 0;
        }

        @Override
        public ResourceLocation getId() {
            return Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:" + id));
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
            data.writeUtf(id);
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof TestKey testKey && Objects.equals(id, testKey.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static final class TestKeyType extends AEKeyType {

        private static final TestKeyType INSTANCE = new TestKeyType();

        private TestKeyType() {
            super(
                    Objects.requireNonNull(ResourceLocation.tryParse("chexsonsaeutils:test")),
                    TestKey.class,
                    Component.literal("Test")
            );
        }

        @Override
        public MapCodec<? extends AEKey> codec() {
            return TestKey.CODEC;
        }

        @Override
        public AEKey readFromPacket(RegistryFriendlyByteBuf input) {
            return new TestKey(input.readUtf());
        }

        @Override
        public AEKey loadKeyFromTag(HolderLookup.Provider provider, CompoundTag tag) {
            return new TestKey(tag.getString("id"));
        }
    }
}
