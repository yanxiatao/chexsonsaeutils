package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid;
import git.chexson.chexsonsaeutils.crafting.hostcpu.ParallelCpuHost;
import git.chexson.chexsonsaeutils.integration.hostbatch.ProviderBatchCapabilitiesFactoryImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 不构造方块实体，直接验证宿主契约、AE2 设备身份与实时运行/选用规则。 */
final class ParallelCpuHostContractTest {
    @Test
    void widePlanReachesMaterialCheckForExplicitAndAutomaticParallelCpuSelection() {
        var output = AEItemKey.of(Items.IRON_INGOT);
        for (var required : List.of(BigInteger.valueOf(100_000_000_000L), BigInteger.TEN.pow(30))) {
            for (boolean autoSelect : new boolean[] { false, true }) {
                MEStorage emptyStorage = () -> Component.literal("空库存");
                var grid = new ParallelRecursiveFinalOutputDeliveryTest.TestGrid(emptyStorage);
                var host = new HostImpl();
                host.grid = grid;
                host.processing = true;
                var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
                var cpuGrid = new ParallelCraftingCpuGrid(grid);
                cpuGrid.setClusters(List.of(cluster));

                long clamped = required.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
                var used = new KeyCounter();
                used.add(output, clamped);
                var base = new CraftingPlan(new GenericStack(output, clamped), 0L, false, false,
                        used, new KeyCounter(), new KeyCounter(), Map.of());
                var wideUsed = new WideKeyCounter();
                wideUsed.setExact(output, required);
                var plan = new WideAmountCraftingPlan(base,
                        WideLedgerSnapshot.capture(null, wideUsed, null), null, Set.of());

                var result = cpuGrid.submitJob(plan, null,
                        autoSelect ? null : cluster.remainingCapacityCpu(), true, null);
                assertFalse(result.successful());
                assertEquals(CraftingSubmitErrorCode.MISSING_INGREDIENT, result.errorCode());
                assertEquals(output, assertInstanceOf(GenericStack.class, result.errorDetail()).what());
                assertEquals(0, cluster.activeLaneCount());
            }
        }
    }

    @Test
    void registeredInactiveCpuRejectsNormalAndPartialJobsWithoutCreatingLanes() {
        var grid = FakePlanGrid.FakeGrid.builder().build();
        var host = new HostImpl();
        host.grid = grid;
        host.node = grid.node;
        var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
        var cpuGrid = new ParallelCraftingCpuGrid(grid);
        cpuGrid.setClusters(List.of(cluster));
        assertTrue(cpuGrid.hasCpu(cluster.remainingCapacityCpu()));

        var normal = offlineState(cpuGrid.submitJob(
                plan(false), null, cluster.remainingCapacityCpu(), true, null));
        assertEquals("host-inactive", normal.reason());
        assertTrue(normal.providerReady());
        assertFalse(normal.canProcess());
        assertTrue(normal.nodePresent());
        assertEquals(Boolean.TRUE, normal.powered());
        assertEquals(Boolean.TRUE, normal.booted());
        assertEquals(Boolean.TRUE, normal.channelsSatisfied());
        assertTrue(normal.sameGrid());
        assertTrue(normal.attachedToSubmittingGrid());

        var partial = offlineState(cpuGrid.submitPartialJob(
                plan(true), null, cluster.remainingCapacityCpu(), true, null));
        assertEquals("partial-host-inactive", partial.reason());
        assertEquals(0, cluster.activeLaneCount());
    }

    @Test
    void removedCpuReportsRegistrationFailureInsteadOfInventingPowerFailure() {
        var grid = FakePlanGrid.FakeGrid.builder().build();
        var host = new HostImpl();
        var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
        var cpuGrid = new ParallelCraftingCpuGrid(grid);
        cpuGrid.setClusters(List.of(cluster));
        cpuGrid.setClusters(List.of());

        var state = offlineState(cpuGrid.submitJob(
                plan(false), null, cluster.remainingCapacityCpu(), true, null));
        assertEquals("cluster-not-registered", state.reason());
        assertFalse(state.nodePresent());
        assertNull(state.powered());
        assertNull(state.booted());
        assertNull(state.channelsSatisfied());
        assertFalse(state.attachedToSubmittingGrid());
        assertEquals(0, cluster.activeLaneCount());
    }

    private static ParallelCraftingCpuCluster.OfflineSubmissionState offlineState(ICraftingSubmitResult result) {
        assertFalse(result.successful());
        assertEquals(CraftingSubmitErrorCode.CPU_OFFLINE, result.errorCode());
        return assertInstanceOf(ParallelCraftingCpuCluster.OfflineSubmissionState.class, result.errorDetail());
    }

    private static CraftingPlan plan(boolean simulation) {
        return new CraftingPlan(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L), 0L, simulation, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    @Test
    void clusterUsesHostIdentityAndCurrentSelectionAndRunningState() {
        var host = new HostImpl();
        var cluster = new ParallelCraftingCpuCluster(host, new ProviderBatchCapabilitiesFactoryImpl());
        assertSame(host, cluster.source().machine().orElseThrow());
        assertTrue(cluster.canBeAutoSelectedFor(null));
        assertFalse(cluster.isPreferredFor(null));
        assertTrue(cluster.isActive());
        assertFalse(cluster.canProcessJobs());

        host.mode = CpuSelectionMode.MACHINE_ONLY;
        host.processing = true;
        assertTrue(cluster.canBeAutoSelectedFor(null));
        assertTrue(cluster.isPreferredFor(null));
        assertTrue(cluster.canProcessJobs());

        host.mode = CpuSelectionMode.PLAYER_ONLY;
        host.active = false;
        host.processing = false;
        assertFalse(cluster.canBeAutoSelectedFor(null));
        assertFalse(cluster.isPreferredFor(null));
        assertFalse(cluster.isActive());
        assertFalse(cluster.canProcessJobs());
    }

    /** 尚未入世界/接网的测试宿主；运行状态由场景独立驱动，不依赖方块构造副作用。 */
    private static final class HostImpl implements ParallelCpuHost {
        private CpuSelectionMode mode = CpuSelectionMode.ANY;
        private boolean active = true;
        private boolean processing;
        private IGrid grid;
        private IGridNode node;
        @Override
        public @Nullable IGrid getGrid() { return grid; }
        @Override
        public @Nullable Level getLevel() { return null; }
        @Override
        public @Nullable IGridNode getActionableNode() { return node; }
        @Override
        public CpuSelectionMode getSelectionMode() { return mode; }
        @Override
        public boolean isParallelCpuProviderActive() { return active; }
        @Override
        public boolean canProcessParallelCpuJobs() { return processing; }
    }
}
