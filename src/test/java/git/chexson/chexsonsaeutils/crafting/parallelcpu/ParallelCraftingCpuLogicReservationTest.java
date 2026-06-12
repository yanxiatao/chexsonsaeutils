package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import com.google.common.collect.ImmutableSet;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.support.TestKeySupport;
import git.chexson.chexsonsaeutils.support.TestKeySupport.DummyKey;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCraftingCpuLogicReservationTest {

    @Test
    void synchronousReturnedPayloadOnlyEntersLaneAfterWaitingIsReserved() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_final_output");
        DummyKey intermediate = new DummyKey("parallel_intermediate");
        DummyKey container = new DummyKey("parallel_container");
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        ParallelExecutingCraftingJob job = newJob(finalOutput);
        setJob(logic, job);

        assertEquals(0L, logic.insert(intermediate, 2L, Actionable.MODULATE));
        assertEquals(0L, logic.getWaitingFor(intermediate));
        assertEquals(0L, logic.getStored(intermediate));

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(intermediate, 2L);
        KeyCounter expectedContainerItems = new KeyCounter();
        expectedContainerItems.add(container, 1L);
        reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);

        assertEquals(2L, logic.getWaitingFor(intermediate));
        assertEquals(1L, logic.getWaitingFor(container));

        assertEquals(2L, logic.insert(intermediate, 2L, Actionable.MODULATE));
        assertEquals(0L, logic.getWaitingFor(intermediate));
        assertEquals(2L, logic.getStored(intermediate));

        assertEquals(1L, logic.insert(container, 1L, Actionable.MODULATE));
        assertEquals(0L, logic.getWaitingFor(container));
        assertEquals(1L, logic.getStored(container));
    }

    @Test
    void rollbackClearsRejectedWaitingReservation() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_rejected_final_output");
        DummyKey intermediate = new DummyKey("parallel_rejected_intermediate");
        DummyKey container = new DummyKey("parallel_rejected_container");
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        ParallelExecutingCraftingJob job = newJob(finalOutput);
        setJob(logic, job);

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(intermediate, 3L);
        KeyCounter expectedContainerItems = new KeyCounter();
        expectedContainerItems.add(container, 2L);
        reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);

        assertEquals(3L, logic.getWaitingFor(intermediate));
        assertEquals(2L, logic.getWaitingFor(container));

        rollbackExpectedWaiting(job, expectedOutputs, expectedContainerItems);

        assertEquals(0L, logic.getWaitingFor(intermediate));
        assertEquals(0L, logic.getWaitingFor(container));
        assertEquals(0L, logic.getStored(intermediate));
        assertEquals(0L, logic.getStored(container));
    }

    @Test
    void finalOutputDoesNotCloseJobBeforeSynchronousRecyclePayloadReturns() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_sync_final_output");
        DummyKey container = new DummyKey("parallel_sync_container");
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        TestRequester requester = new TestRequester();
        ParallelExecutingCraftingJob job = newJob(finalOutput, requester);
        setJob(logic, job);

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(finalOutput, 1L);
        KeyCounter expectedContainerItems = new KeyCounter();
        expectedContainerItems.add(container, 1L);
        reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);

        invokeLogicHelper(logic, "beginSynchronousProviderPush");
        assertEquals(1L, logic.insert(finalOutput, 1L, Actionable.MODULATE));
        assertTrue(logic.hasJob());
        assertEquals(1L, requester.acceptedAmount(finalOutput));
        assertEquals(0L, logic.getWaitingFor(finalOutput));
        assertEquals(1L, logic.getWaitingFor(container));

        assertEquals(1L, logic.insert(container, 1L, Actionable.MODULATE));
        assertTrue(logic.hasJob());
        assertEquals(0L, logic.getWaitingFor(container));
        assertEquals(1L, logic.getStored(container));

        invokeLogicHelper(logic, "endSynchronousProviderPush");
        assertFalse(logic.hasJob());
        assertEquals(1L, logic.getStored(container));
    }

    @Test
    void lateRecyclePayloadKeepsJobActiveUntilWaitingClears() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_async_final_output");
        DummyKey container = new DummyKey("parallel_async_container");
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        TestRequester requester = new TestRequester();
        ParallelExecutingCraftingJob job = newJob(finalOutput, requester);
        setJob(logic, job);

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(finalOutput, 1L);
        KeyCounter expectedContainerItems = new KeyCounter();
        expectedContainerItems.add(container, 1L);
        reserveExpectedWaiting(job, expectedOutputs, expectedContainerItems);

        assertEquals(1L, logic.insert(finalOutput, 1L, Actionable.MODULATE));
        assertTrue(logic.hasJob());
        assertEquals(1L, requester.acceptedAmount(finalOutput));
        assertEquals(0L, logic.getWaitingFor(finalOutput));
        assertEquals(1L, logic.getWaitingFor(container));
        assertEquals(0L, logic.getStored(container));

        assertEquals(1L, logic.insert(container, 1L, Actionable.MODULATE));
        assertFalse(logic.hasJob());
        assertEquals(0L, logic.getWaitingFor(container));
        assertEquals(1L, logic.getStored(container));
    }

    @Test
    void externalIngressFinalOutputSimulationUsesWaitingInsteadOfRequesterEstimate() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_external_ingress_simulation");

        assertExternalIngressSimulation(finalOutput, 4L, 0L, 0L, 4L);
        assertExternalIngressSimulation(finalOutput, 4L, 2L, 2L, 4L);
        assertExternalIngressSimulation(finalOutput, 4L, 4L, 4L, 4L);
    }

    @Test
    void externalIngressFinalOutputModulateBuffersInsideCpuWhenRequested() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_external_ingress_modulate");
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        TestRequester requester = new TestRequester(
                (what, amount, mode) -> mode == Actionable.SIMULATE ? 0L : Math.max(0L, amount)
        );
        ParallelExecutingCraftingJob job = newJob(finalOutput, 4L, requester);
        setJob(logic, job);

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(finalOutput, 4L);
        reserveExpectedWaiting(job, expectedOutputs, new KeyCounter());

        assertEquals(4L, logic.insert(finalOutput, 4L, Actionable.MODULATE, true));
        assertEquals(0L, requester.acceptedAmount(finalOutput));
        assertEquals(0L, logic.getWaitingFor(finalOutput));
        assertEquals(4L, logic.getStored(finalOutput));
        assertFalse(logic.hasJob());
    }

    @Test
    void finalOutputPartialRequesterAcceptanceKeepsRemainingReservationUntilFullyDelivered() {
        TestKeySupport.ensureAeKeyTypeRegistryInitialized();
        DummyKey finalOutput = new DummyKey("parallel_partial_accept_final_output");
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        TestRequester requester = new TestRequester(new InsertPolicy() {
            private int modulateCalls;

            @Override
            public long accept(AEKey what, long amount, Actionable mode) {
                if (mode != Actionable.MODULATE) {
                    return Math.max(0L, amount);
                }
                modulateCalls++;
                return modulateCalls == 1 ? Math.min(2L, Math.max(0L, amount)) : Math.max(0L, amount);
            }
        });
        ParallelExecutingCraftingJob job = newJob(finalOutput, 4L, requester);
        setJob(logic, job);

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(finalOutput, 4L);
        reserveExpectedWaiting(job, expectedOutputs, new KeyCounter());

        assertEquals(2L, logic.insert(finalOutput, 4L, Actionable.MODULATE));
        assertTrue(logic.hasJob());
        assertEquals(2L, requester.acceptedAmount(finalOutput));
        assertEquals(2L, logic.getWaitingFor(finalOutput));
        assertEquals(2L, getRemainingAmount(job));

        assertEquals(2L, logic.insert(finalOutput, 4L, Actionable.MODULATE));
        assertEquals(4L, requester.acceptedAmount(finalOutput));
        assertEquals(0L, logic.getWaitingFor(finalOutput));
        assertFalse(logic.hasJob());
    }

    private static ParallelCraftingCpuLogic newDetachedLogic() {
        AE2ParallelCpuToolBlockEntity owner = allocateInstance(AE2ParallelCpuToolBlockEntity.class);
        setFinalObjectField(owner, appeng.blockentity.grid.AENetworkedBlockEntity.class,
                "mainNode", newManagedGridNodeStub());
        ParallelCraftingCpuCluster cluster = new ParallelCraftingCpuCluster(owner);
        ParallelCraftingLaneState lane = new ParallelCraftingLaneState(cluster, UUID.randomUUID(), 0L);
        return lane.logic();
    }

    private static ParallelExecutingCraftingJob newJob(AEKey finalOutput) {
        return newJob(finalOutput, new TestRequester());
    }

    private static ParallelExecutingCraftingJob newJob(AEKey finalOutput, ICraftingRequester requester) {
        return newJob(finalOutput, 1L, requester);
    }

    private static ParallelExecutingCraftingJob newJob(AEKey finalOutput, long finalAmount, ICraftingRequester requester) {
        ParallelExecutingCraftingJob job = allocateInstance(ParallelExecutingCraftingJob.class);
        setFinalObjectField(job, ParallelExecutingCraftingJob.class, "link", newCpuSideLink(requester));
        setFinalObjectField(
                job,
                ParallelExecutingCraftingJob.class,
                "waitingFor",
                new ListCraftingInventory(ignored -> {
                })
        );
        setFinalObjectField(job, ParallelExecutingCraftingJob.class, "tasks", new LinkedHashMap<>());
        setFinalObjectField(job, ParallelExecutingCraftingJob.class, "timeTracker", newElapsedTimeTracker());
        setField(job, ParallelExecutingCraftingJob.class, "finalOutput", new GenericStack(finalOutput, finalAmount));
        setLongField(job, ParallelExecutingCraftingJob.class, "remainingAmount", finalAmount);
        setField(job, ParallelExecutingCraftingJob.class, "playerId", null);
        setBooleanField(job, ParallelExecutingCraftingJob.class, "suspended", false);
        return job;
    }

    private static void assertExternalIngressSimulation(
            DummyKey finalOutput,
            long amount,
            long requesterSimulatedAcceptance,
            long expectedDefaultSimulation,
            long expectedExternalIngressSimulation
    ) {
        ParallelCraftingCpuLogic logic = newDetachedLogic();
        TestRequester requester = new TestRequester(
                (what, acceptedAmount, mode) -> mode == Actionable.SIMULATE
                        ? Math.min(Math.max(0L, requesterSimulatedAcceptance), acceptedAmount)
                        : Math.max(0L, acceptedAmount)
        );
        ParallelExecutingCraftingJob job = newJob(finalOutput, amount, requester);
        setJob(logic, job);

        KeyCounter expectedOutputs = new KeyCounter();
        expectedOutputs.add(finalOutput, amount);
        reserveExpectedWaiting(job, expectedOutputs, new KeyCounter());

        assertEquals(expectedDefaultSimulation, logic.insert(finalOutput, amount, Actionable.SIMULATE));
        assertEquals(expectedExternalIngressSimulation, logic.insert(finalOutput, amount, Actionable.SIMULATE, true));
        assertEquals(amount, logic.getWaitingFor(finalOutput));
    }

    private static void setJob(ParallelCraftingCpuLogic logic, ParallelExecutingCraftingJob job) {
        setField(logic, ParallelCraftingCpuLogic.class, "job", job);
    }

    private static long getRemainingAmount(ParallelExecutingCraftingJob job) {
        try {
            Field field = ParallelExecutingCraftingJob.class.getDeclaredField("remainingAmount");
            field.setAccessible(true);
            return field.getLong(job);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read field remainingAmount", exception);
        }
    }

    private static void reserveExpectedWaiting(
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        invokeWaitingReservationHelper("reserveExpectedWaiting", job, expectedOutputs, expectedContainerItems);
    }

    private static void rollbackExpectedWaiting(
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        invokeWaitingReservationHelper("rollbackReservedWaiting", job, expectedOutputs, expectedContainerItems);
    }

    private static void invokeWaitingReservationHelper(
            String methodName,
            ParallelExecutingCraftingJob job,
            KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems
    ) {
        try {
            Method method = ParallelCraftingCpuLogic.class.getDeclaredMethod(
                    methodName,
                    ParallelExecutingCraftingJob.class,
                    KeyCounter.class,
                    KeyCounter.class
            );
            method.setAccessible(true);
            method.invoke(null, job, expectedOutputs, expectedContainerItems);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to invoke parallel waiting helper " + methodName, exception);
        }
    }

    private static void invokeLogicHelper(ParallelCraftingCpuLogic logic, String methodName) {
        try {
            Method method = ParallelCraftingCpuLogic.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(logic);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to invoke parallel logic helper " + methodName, exception);
        }
    }

    private static CraftingLink newCpuSideLink(ICraftingRequester requester) {
        UUID craftId = UUID.randomUUID();
        CraftingLink cpuLink = new CraftingLink(newLinkData(craftId, false, false), new TestCpu());
        CraftingLink requesterLink = new CraftingLink(newLinkData(craftId, false, true), requester);
        CraftingLinkNexus nexus = new CraftingLinkNexus(craftId);
        cpuLink.setNexus(nexus);
        requesterLink.setNexus(nexus);
        return cpuLink;
    }

    private static CompoundTag newLinkData(UUID craftId, boolean standalone, boolean requester) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("craftId", craftId);
        tag.putBoolean("canceled", false);
        tag.putBoolean("done", false);
        tag.putBoolean("standalone", standalone);
        tag.putBoolean("req", requester);
        return tag;
    }

    private static IManagedGridNode newManagedGridNodeStub() {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getGrid".equals(name) || "getNode".equals(name)) {
                return null;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == int.class) {
                return 0;
            }
            if (method.getReturnType() == double.class) {
                return 0D;
            }
            if (method.getReturnType() == long.class) {
                return 0L;
            }
            if (method.getReturnType() == IManagedGridNode.class) {
                return proxy;
            }
            return null;
        };
        return (IManagedGridNode) Proxy.newProxyInstance(
                ParallelCraftingCpuLogicReservationTest.class.getClassLoader(),
                new Class<?>[]{IManagedGridNode.class},
                handler
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return (T) allocateInstance.invoke(unsafe, type);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate test instance for " + type.getName(), exception);
        }
    }

    private static ElapsedTimeTracker newElapsedTimeTracker() {
        ElapsedTimeTracker tracker = allocateInstance(ElapsedTimeTracker.class);
        setLongField(tracker, ElapsedTimeTracker.class, "lastTime", System.nanoTime());
        setLongField(tracker, ElapsedTimeTracker.class, "elapsedTime", 0L);
        setFinalObjectField(
                tracker,
                ElapsedTimeTracker.class,
                "startedWorkByType",
                new Reference2LongOpenHashMap<>()
        );
        setFinalObjectField(
                tracker,
                ElapsedTimeTracker.class,
                "completedWorkByType",
                new Reference2LongOpenHashMap<>()
        );
        return tracker;
    }

    private static void setField(Object target, Class<?> owner, String fieldName, Object value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to set field " + owner.getSimpleName() + "." + fieldName, exception);
        }
    }

    private static void setLongField(Object target, Class<?> owner, String fieldName, long value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setLong(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to set long field " + owner.getSimpleName() + "." + fieldName, exception);
        }
    }

    private static void setBooleanField(Object target, Class<?> owner, String fieldName, boolean value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "Unable to set boolean field " + owner.getSimpleName() + "." + fieldName,
                    exception
            );
        }
    }

    private static void setFinalObjectField(Object target, Class<?> owner, String fieldName, Object value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Object unsafe = theUnsafeField.get(null);
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            long offset = (long) objectFieldOffset.invoke(unsafe, field);
            Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            putObject.invoke(unsafe, target, offset, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "Unable to set final field " + owner.getSimpleName() + "." + fieldName,
                    exception
            );
        }
    }

    private static final class TestCpu implements ICraftingCPU {
        @Override
        public boolean isBusy() {
            return true;
        }

        @Override
        public CraftingJobStatus getJobStatus() {
            return null;
        }

        @Override
        public void cancelJob() {
        }

        @Override
        public long getAvailableStorage() {
            return 0L;
        }

        @Override
        public int getCoProcessors() {
            return 0;
        }

        @Override
        public Component getName() {
            return null;
        }

        @Override
        public CpuSelectionMode getSelectionMode() {
            return CpuSelectionMode.ANY;
        }
    }

    private static final class TestRequester implements ICraftingRequester {
        private final LinkedHashMap<AEKey, Long> acceptedAmounts = new LinkedHashMap<>();
        private final InsertPolicy insertPolicy;

        private TestRequester() {
            this((what, amount, mode) -> Math.max(0L, amount));
        }

        private TestRequester(InsertPolicy insertPolicy) {
            this.insertPolicy = insertPolicy == null
                    ? (what, amount, mode) -> Math.max(0L, amount)
                    : insertPolicy;
        }

        long acceptedAmount(AEKey key) {
            return acceptedAmounts.getOrDefault(key, 0L);
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return ImmutableSet.of();
        }

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
            long accepted = Math.min(
                    Math.max(0L, insertPolicy.accept(what, amount, mode)),
                    Math.max(0L, amount)
            );
            if (mode == Actionable.MODULATE && what != null && accepted > 0L) {
                acceptedAmounts.merge(what, accepted, Long::sum);
            }
            return accepted;
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
        }

        @Override
        public IGridNode getActionableNode() {
            return null;
        }
    }

    @FunctionalInterface
    private interface InsertPolicy {
        long accept(AEKey what, long amount, Actionable mode);
    }

}
