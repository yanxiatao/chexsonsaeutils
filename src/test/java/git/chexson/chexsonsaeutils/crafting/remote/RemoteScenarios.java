package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.config.Actionable;
import appeng.api.networking.*;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.*;
import appeng.api.util.AEColor;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.crafting.graphplan.diag.GraphPlanningBudget;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanningSession;
import git.chexson.chexsonsaeutils.crafting.material.PlanningSupplyTruth;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.*;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeGrid;
import git.chexson.chexsonsaeutils.helpers.remote.*;
import java.math.BigInteger;
import java.util.*;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import static org.junit.jupiter.api.Assertions.*;

/** 跨网合成的行为场景；由类别测试统一调用。 */
public final class RemoteScenarios {
    private RemoteScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.crafting.remote",
                () -> RemoteLedgerCases.verify(temporaryRoot),
                () -> RemotePlanningCases.verify(temporaryRoot),
                () -> RemoteReceiptPersistenceCases.verify(temporaryRoot),
                () -> RemoteRoutesCases.verify(temporaryRoot)
        );
    }

    /** RemoteLedgerCases 的独立场景夹具，按类别统一执行。 */
    public static final class RemoteLedgerCases {
        void exactWideDemandAndPartialDeliverySurviveRestore() {
            var ledger = new RemoteLedgerImpl<String>(4, 100);
            var demand = BigInteger.TEN.pow(30);
            assertTrue(ledger.enqueue("iron", demand));
            var id = UUID.randomUUID();
            ledger.submitted(id, "iron", 70);
            assertEquals(70, ledger.receive(id, "iron", 90, true));
            assertTrue(ledger.buffered().isEmpty());
            assertEquals(40, ledger.receive(id, "iron", 40, false));
            assertEquals(30, ledger.batch("iron", 100));
            ledger.delivered("iron", 15);
            var restored = RemoteLedgerImpl.restore(4, 100, ledger.pending(), ledger.buffered(), ledger.reservations());
            assertEquals(30, restored.receive(id, "iron", 80, false));
            restored.close(id, false);
            assertEquals(BigInteger.valueOf(55), restored.buffered().get("iron"));
            assertEquals(demand.subtract(BigInteger.valueOf(70)), restored.pending().get("iron"));
            restored.delivered("iron", 55);
            assertTrue(restored.buffered().isEmpty());
            assertEquals(100, restored.batch("iron", 100));
        }
        void completionDoesNotManufactureOutputAndCancelledSourceOnlyRetriesDeficit() {
            var ledger = new RemoteLedgerImpl<String>(2, 100);
            ledger.enqueue("fluid", BigInteger.valueOf(80));
            var id = UUID.randomUUID();
            ledger.submitted(id, "fluid", 80);
            ledger.receive(id, "fluid", 20, false);
            ledger.close(id, true);
            assertEquals(BigInteger.valueOf(20), ledger.buffered().get("fluid"));
            assertEquals(BigInteger.valueOf(60), ledger.pending().get("fluid"));
            assertEquals(0, ledger.receive(id, "fluid", 60, false));
            ledger.cancelPending();
            assertEquals(BigInteger.valueOf(20), ledger.buffered().get("fluid"));
        }
        void keysInFlightCountTowardCapacityAndMismatchedReceiptsCannotStealReservation() {
            var ledger = new RemoteLedgerImpl<String>(1, 10);
            ledger.enqueue("a", BigInteger.TEN);
            var id = UUID.randomUUID();
            ledger.submitted(id, "a", 10);
            assertFalse(ledger.enqueue("b", BigInteger.ONE));
            assertEquals(0, ledger.receive(id, "b", 10, false));
            assertEquals(0, ledger.batch("a", 10));
            assertThrows(IllegalStateException.class, () -> ledger.submitted(id, "a", 1));
            ledger.receive(id, "a", 10, false);
            assertFalse(ledger.enqueue("b", BigInteger.ONE));
            assertThrows(IllegalStateException.class, () -> ledger.delivered("a", 11));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RemoteLedgerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteLedgerCases.exactWideDemandAndPartialDeliverySurviveRestore", () -> new RemoteLedgerCases().exactWideDemandAndPartialDeliverySurviveRestore()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteLedgerCases.completionDoesNotManufactureOutputAndCancelledSourceOnlyRetriesDeficit", () -> new RemoteLedgerCases().completionDoesNotManufactureOutputAndCancelledSourceOnlyRetriesDeficit()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteLedgerCases.keysInFlightCountTowardCapacityAndMismatchedReceiptsCannotStealReservation", () -> new RemoteLedgerCases().keysInFlightCountTowardCapacityAndMismatchedReceiptsCannotStealReservation())
            );
        }
    }

    /** RemotePlanningCases 的独立场景夹具，按类别统一执行。 */
    public static final class RemotePlanningCases {
        private static final AEItemKey RAW = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey INTERMEDIATE = AEItemKey.of(Items.IRON_BLOCK);
        private static final AEItemKey FINAL = AEItemKey.of(Items.CAULDRON);
        private static RemotePattern remote() { return new RemotePatternImpl(AEItemKey.of(Items.PAPER), INTERMEDIATE); }
        void receiverIntermediateHasNoRemoteMaterialDemandButSourceDoes() {
            var remote = remote();
            var localFinal = FakePattern.crafting(FINAL, 1, Map.of(INTERMEDIATE, 2L));
            var receiver = FakeGrid.builder().pattern(INTERMEDIATE, remote).pattern(FINAL, localFinal).storage(Map.of(RAW, 999L)).build();
            var session = GraphPlanningSession.create(receiver.crafting, receiver.storage.getCachedInventory(), FINAL,
                    CalculationStrategy.REPORT_MISSING_ITEMS, new GraphPlanningBudget(5000), null, false);
            var result = session.solve(3);
            assertTrue(result.handled());
            assertFalse(result.plan().simulation());
            assertEquals(0, result.plan().usedItems().get(RAW));
            assertEquals(BigInteger.valueOf(6), RemoteDemand.from(result.plan()).get(INTERMEDIATE));
            var source = FakeGrid.builder().pattern(INTERMEDIATE, FakePattern.crafting(INTERMEDIATE, 1, Map.of(RAW, 9L))).build();
            var sourcePlan = RemotePlanning.plan(null, source.crafting, INTERMEDIATE, BigInteger.valueOf(6), source, new KeyCounter());
            assertTrue(sourcePlan.simulation());
            assertEquals(54, sourcePlan.missingItems().get(RAW));
            assertFalse(sourcePlan.patternTimes().containsKey(remote));
        }
        void sourceCannotReexportRemoteDependencyEvenWhenItCouldProduceMissingIntermediate() {
            var source = FakeGrid.builder().pattern(INTERMEDIATE, remote()).pattern(FINAL, FakePattern.crafting(FINAL, 1, Map.of(INTERMEDIATE, 2L))).build();
            var plan = RemotePlanning.plan(null, source.crafting, FINAL, BigInteger.ONE, source, new KeyCounter());
            assertTrue(plan.simulation());
            assertEquals(2, plan.missingItems().get(INTERMEDIATE));
            assertTrue(plan.patternTimes().keySet().stream().noneMatch(RemotePattern.class::isInstance));
        }
        void finiteWideSourceIsDeductedExactlyAcrossMultipleTargets() {
            var wide = BigInteger.TEN.pow(30);
            var stock = new KeyCounter(); stock.add(RAW, Long.MAX_VALUE);
            var shared = new RemoteStock(stock, PlanningSupplyTruth.of(Map.of(RAW, PlanAmount.of(wide))));
            shared.reserve(Map.of(RAW, PlanAmount.of(wide.subtract(BigInteger.valueOf(10)))));
            assertEquals(10, shared.snapshot().get(RAW));
            assertEquals(BigInteger.TEN, shared.truth().supplyOf(RAW, Long.MAX_VALUE).exactValue());
            shared.reserve(Map.of(RAW, PlanAmount.of(7)));
            assertEquals(BigInteger.valueOf(3), shared.truth().supplyOf(RAW, shared.snapshot().get(RAW)).exactValue());
            assertThrows(IllegalStateException.class, () -> shared.reserve(Map.of(RAW, PlanAmount.of(4))));
            assertEquals(3, shared.snapshot().get(RAW));
        }
        void executionCanProducePartialBatchWithoutWeakeningFullPreview() {
            var source = FakeGrid.builder().pattern(INTERMEDIATE,
                    FakePattern.crafting(INTERMEDIATE, 1, Map.of(RAW, 9L))).storage(Map.of(RAW, 27L)).build();
            var stock = source.storage.getCachedInventory();
            var full = RemotePlanning.plan(null, source.crafting, INTERMEDIATE, BigInteger.TEN, source, stock);
            assertTrue(full.simulation());
            assertEquals(63, full.missingItems().get(RAW));
            var partial = RemotePlanning.plan(null, source.crafting, INTERMEDIATE, BigInteger.TEN, source, stock,
                    null, CalculationStrategy.CRAFT_LESS);
            assertFalse(partial.simulation());
            assertEquals(3, partial.finalOutput().amount());
            assertEquals(27, partial.usedItems().get(RAW));
            assertEquals(10, full.finalOutput().amount());
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RemotePlanningCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RemotePlanningCases.receiverIntermediateHasNoRemoteMaterialDemandButSourceDoes", () -> new RemotePlanningCases().receiverIntermediateHasNoRemoteMaterialDemandButSourceDoes()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemotePlanningCases.sourceCannotReexportRemoteDependencyEvenWhenItCouldProduceMissingIntermediate", () -> new RemotePlanningCases().sourceCannotReexportRemoteDependencyEvenWhenItCouldProduceMissingIntermediate()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemotePlanningCases.finiteWideSourceIsDeductedExactlyAcrossMultipleTargets", () -> new RemotePlanningCases().finiteWideSourceIsDeductedExactlyAcrossMultipleTargets()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemotePlanningCases.executionCanProducePartialBatchWithoutWeakeningFullPreview", () -> new RemotePlanningCases().executionCanProducePartialBatchWithoutWeakeningFullPreview())
            );
        }
    }

    /** RemoteReceiptPersistenceCases 的独立场景夹具，按类别统一执行。 */
    public static final class RemoteReceiptPersistenceCases {
        private static final HolderLookup.Provider REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        private static final AEKey KEY = AEItemKey.of(Items.IRON_INGOT);
        void requesterIsRegisteredOnlyOnSourceAndReceiptRestoresWithoutResubmitOrManufacturedItems() {
            var host = new Host(); var runtime = new RemoteTransmitterImpl(host);
            assertTrue(host.source.services.containsKey(appeng.api.networking.crafting.ICraftingRequester.class));
            assertFalse(host.receiver.services.containsKey(appeng.api.networking.crafting.ICraftingRequester.class));
            assertTrue(host.receiver.services.containsKey(appeng.api.networking.crafting.ICraftingProvider.class));
            assertSame(host.source.getNode(), runtime.getActionableNode());
            var id = UUID.randomUUID(); var tag = stored(id, 70);
            runtime.read(tag, REGISTRIES);
            var link = runtime.getRequestedJobs().iterator().next();
            assertEquals(id, link.getCraftingID());
            assertEquals(70, runtime.insertCraftedItems(link, KEY, 100, Actionable.SIMULATE));
            assertTrue(runtime.orderLines().stream().noneMatch(s -> s.startsWith("待交付")));
            assertEquals(25, runtime.insertCraftedItems(link, KEY, 25, Actionable.MODULATE));
            var saved = new CompoundTag(); runtime.write(saved, REGISTRIES);
            var restored = new RemoteTransmitterImpl(new Host()); restored.read(saved, REGISTRIES);
            var restoredLink = restored.getRequestedJobs().iterator().next();
            assertEquals(id, restoredLink.getCraftingID());
            assertEquals(45, restored.insertCraftedItems(restoredLink, KEY, 100, Actionable.MODULATE));
            restored.jobStateChange(restoredLink);
            assertTrue(restored.getRequestedJobs().isEmpty());
            assertTrue(restored.orderLines().stream().anyMatch(s -> s.startsWith("待交付") && s.endsWith("70")));
            var complete = new CompoundTag(); restored.write(complete, REGISTRIES);
            assertTrue(complete.getList("tasks", Tag.TAG_COMPOUND).isEmpty());
            assertTrue(complete.getList("pending", Tag.TAG_COMPOUND).isEmpty());
            restored.cancel();
            assertTrue(restored.orderLines().stream().anyMatch(s -> s.startsWith("待交付") && s.endsWith("70")));
            assertTrue(host.saves > 0);
        }
        void internalDefinitionIncludesGenericTypeAndReconstructsSamePatternIdentity() {
            var stack = new ItemStack(Items.PAPER); var data = new CompoundTag();
            data.putInt("version", 1); data.put("output", KEY.toTagGeneric(REGISTRIES));
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
            var definition = AEItemKey.of(stack);
            var decoded = RemotePatternItem.decodeDefinition(definition, REGISTRIES);
            assertNotNull(decoded); assertEquals(KEY, decoded.output());
            assertEquals(new RemotePatternImpl(definition, KEY), decoded);
            assertEquals(0, decoded.getInputs().length);
            assertEquals(1, decoded.getOutputs().size());
        }
        private static CompoundTag stored(UUID id, long remaining) {
            var tag = new CompoundTag(); tag.putUUID("identity", UUID.randomUUID()); tag.putString("status", "来源执行中");
            var link = new CompoundTag(); link.putUUID("craftId", id); link.putBoolean("req", true); link.putBoolean("standalone", false);
            var task = new CompoundTag(); task.putUUID("id", id); task.put("key", KEY.toTagGeneric(REGISTRIES)); task.putLong("remaining", remaining); task.put("link", link);
            var tasks = new ListTag(); tasks.add(task); tag.put("tasks", tasks);
            return tag;
        }
        private static final class Host implements RemoteTransmitterHost {
            final Node source = new Node(), receiver = new Node(); int saves;
            @Override public IManagedGridNode sourceNode() { return source; }
            @Override public IManagedGridNode receiverNode() { return receiver; }
            @Override public Level world() { return null; }
            @Override public String remoteLabel() { return "测试传递器"; }
            @Override public void saveRemoteChanges() { saves++; }
        }
        private static final class Node implements IManagedGridNode {
            final FakeGrid grid = FakeGrid.builder().build(); final Map<Class<?>, Object> services = new HashMap<>();
            @Override public <T extends IGridNodeService> IManagedGridNode addService(Class<T> type, T service) { services.put(type, service); return this; }
            @Override public IGridNode getNode() { return grid.node; }
            @Override public boolean isReady() { return true; }
            @Override public boolean isActive() { return true; }
            @Override public boolean isOnline() { return true; }
            @Override public boolean isPowered() { return true; }
            @Override public boolean hasGridBooted() { return true; }
            @Override public void destroy() { services.clear(); }
            @Override public void create(Level level, BlockPos pos) { throw new UnsupportedOperationException("测试节点由 fixture 构造"); }
            @Override public void loadFromNBT(CompoundTag tag) { throw new UnsupportedOperationException("本用例只验证 requester 存档"); }
            @Override public void saveToNBT(CompoundTag tag) { throw new UnsupportedOperationException("本用例只验证 requester 存档"); }
            @Override public IManagedGridNode setFlags(GridFlags... flags) { return this; }
            @Override public IManagedGridNode setExposedOnSides(Set<Direction> sides) { return this; }
            @Override public IManagedGridNode setIdlePowerUsage(double amount) { return this; }
            @Override public IManagedGridNode setVisualRepresentation(AEItemKey item) { return this; }
            @Override public IManagedGridNode setInWorldNode(boolean value) { return this; }
            @Override public IManagedGridNode setTagName(String name) { return this; }
            @Override public IManagedGridNode setGridColor(AEColor color) { return this; }
            @Override public void setOwningPlayerId(int id) { throw new UnsupportedOperationException("本用例不创建玩家"); }
            @Override public void setOwningPlayer(Player player) { throw new UnsupportedOperationException("本用例不创建玩家"); }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RemoteReceiptPersistenceCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteReceiptPersistenceCases.requesterIsRegisteredOnlyOnSourceAndReceiptRestoresWithoutResubmitOrManufacturedItems", () -> new RemoteReceiptPersistenceCases().requesterIsRegisteredOnlyOnSourceAndReceiptRestoresWithoutResubmitOrManufacturedItems()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteReceiptPersistenceCases.internalDefinitionIncludesGenericTypeAndReconstructsSamePatternIdentity", () -> new RemoteReceiptPersistenceCases().internalDefinitionIncludesGenericTypeAndReconstructsSamePatternIdentity())
            );
        }
    }

    /** RemoteRoutesCases 的独立场景夹具，按类别统一执行。 */
    public static final class RemoteRoutesCases {
        private static final AEKey KEY = AEItemKey.of(Items.IRON_INGOT);
        void sameOutputRoutesByPriorityAndLoadWithoutCrossingReceiverBoundary() {
            var receiver = FakeGrid.builder().build();
            var otherReceiver = FakeGrid.builder().build();
            var first = new Endpoint(receiver, 10, true);
            var second = new Endpoint(receiver, 10, true);
            var foreign = new Endpoint(otherReceiver, 100, true);
            var offline = new Endpoint(receiver, 1000, false);
            var devices = List.of(first, second, foreign, offline);
            devices.forEach(RemoteRoutes::register);
            try {
                assertTrue(RemoteRoutes.enqueue(receiver, KEY, BigInteger.TEN));
                assertTrue(RemoteRoutes.enqueue(receiver, KEY, BigInteger.ONE));
                assertEquals(BigInteger.valueOf(11), first.accepted.add(second.accepted));
                assertTrue(first.accepted.signum() > 0 && second.accepted.signum() > 0);
                assertEquals(BigInteger.ZERO, foreign.accepted);
                assertEquals(BigInteger.ZERO, offline.accepted);
                assertEquals(3, RemoteRoutes.sources(receiver, KEY).size());
                first.reject = true; second.reject = true;
                assertFalse(RemoteRoutes.enqueue(receiver, KEY, BigInteger.ONE));
                assertEquals(BigInteger.ZERO, foreign.accepted);
            } finally { devices.forEach(RemoteRoutes::unregister); }
        }
        void successfulUnsubmittedRerouteMovesOnlyThatDeficitAndKeepsAlreadyReceivedItems() {
            var old = new RemoteLedgerImpl<String>(2, 100);
            var next = new RemoteLedgerImpl<String>(2, 100);
            old.enqueue("target", BigInteger.valueOf(50));
            var id = UUID.randomUUID(); old.submitted(id, "target", 20); old.receive(id, "target", 10, false);
            assertTrue(next.enqueue("target", BigInteger.valueOf(30)));
            old.movePending("target", BigInteger.valueOf(30));
            assertTrue(old.pending().isEmpty());
            assertEquals(BigInteger.TEN, old.buffered().get("target"));
            assertEquals(10, old.reservations().get(id).remaining());
            assertEquals(BigInteger.valueOf(30), next.pending().get("target"));
            assertThrows(IllegalStateException.class, () -> old.movePending("target", BigInteger.ONE));
        }
        void reroutingSkipsRejectingCandidateAndTriesRemainingSources() {
            var receiver = FakeGrid.builder().build();
            var original = new Endpoint(receiver, 100, true);
            var refusing = new Endpoint(receiver, 50, true); refusing.reject = true;
            var available = new Endpoint(receiver, 10, true);
            var devices = List.of(original, refusing, available); devices.forEach(RemoteRoutes::register);
            try {
                assertTrue(RemoteRoutes.enqueueAlternative(receiver, KEY, original.identity(), BigInteger.TEN));
                assertEquals(BigInteger.ZERO, original.accepted);
                assertEquals(BigInteger.ZERO, refusing.accepted);
                assertEquals(BigInteger.TEN, available.accepted);
                available.reject = true;
                assertFalse(RemoteRoutes.enqueueAlternative(receiver, KEY, original.identity(), BigInteger.ONE));
            } finally { devices.forEach(RemoteRoutes::unregister); }
        }
        private static final class Endpoint implements RemoteEndpoint {
            final UUID id = UUID.randomUUID(); final IGrid receiver; final IGrid source = FakeGrid.builder().build();
            final int priority; final boolean ready; boolean reject; BigInteger accepted = BigInteger.ZERO;
            Endpoint(IGrid receiver, int priority, boolean ready) { this.receiver = receiver; this.priority = priority; this.ready = ready; }
            @Override public UUID identity() { return id; }
            @Override public String label() { return id.toString(); }
            @Override public IGrid sourceGrid() { return source; }
            @Override public IGrid receiverGrid() { return receiver; }
            @Override public Set<AEKey> outputs() { return Set.of(KEY); }
            @Override public boolean ready() { return ready; }
            @Override public int priority() { return priority; }
            @Override public BigInteger load() { return accepted; }
            @Override public boolean accept(AEKey key, BigInteger amount) {
                if (reject || !ready || !outputs().contains(key)) return false;
                accepted = accepted.add(amount); return true;
            }
            @Override public ICraftingPlan preview(AEKey key, BigInteger amount, KeyCounter stock) {
                return RemotePlanning.plan(null, source.getCraftingService(), key, amount, source, stock);
            }
            @Override public String status() { return ready ? "就绪" : "离线"; }
            @Override public Level world() { return null; }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("RemoteRoutesCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteRoutesCases.sameOutputRoutesByPriorityAndLoadWithoutCrossingReceiverBoundary", () -> new RemoteRoutesCases().sameOutputRoutesByPriorityAndLoadWithoutCrossingReceiverBoundary()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteRoutesCases.successfulUnsubmittedRerouteMovesOnlyThatDeficitAndKeepsAlreadyReceivedItems", () -> new RemoteRoutesCases().successfulUnsubmittedRerouteMovesOnlyThatDeficitAndKeepsAlreadyReceivedItems()),
                () -> org.junit.jupiter.api.Assertions.assertAll("RemoteRoutesCases.reroutingSkipsRejectingCandidateAndTriesRemainingSources", () -> new RemoteRoutesCases().reroutingSkipsRejectingCandidateAndTriesRemainingSources())
            );
        }
    }
}
