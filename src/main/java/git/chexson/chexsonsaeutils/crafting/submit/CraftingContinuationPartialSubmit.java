package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.api.features.IPlayerRegistry;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationStatusService;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingBranch;
import git.chexson.chexsonsaeutils.crafting.status.CraftingContinuationWaitingDetail;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CraftingContinuationPartialSubmit {
    private CraftingContinuationPartialSubmit() {
    }

    public static ICraftingSubmitResult submitPartialJob(
            IGrid grid,
            ICraftingPlan plan,
            @Nullable ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src
    ) {
        if (!(target instanceof CraftingCPUCluster cpuCluster)) {
            return CraftingSubmitResult.NO_CPU_FOUND;
        }

        CraftingCpuLogic logic = cpuCluster.craftingLogic;
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) logic;
        if (logic.hasJob()) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!cpuCluster.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (cpuCluster.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }
        if (!logicAccessor.getInventory().list.isEmpty()) {
            AELog.warn("Crafting CPU inventory is not empty yet a continuation job was submitted.");
        }

        KeyCounter missingInitialItems = extractAvailableInitialItems(plan, grid, logicAccessor.getInventory(), src);
        CraftingLink link = new CraftingLink(
                CraftingCpuHelper.generateLinkData(UUID.randomUUID(), true, false),
                cpuCluster);
        ExecutingCraftingJob job = createExecutingJob(plan, link, src, logicAccessor);

        logicAccessor.setJob(job);
        seedInitialWaitingFor(job, missingInitialItems);
        cpuCluster.updateOutput(plan.finalOutput());
        cpuCluster.markDirty();
        logicAccessor.invokeNotifyJobOwner(job, CraftingJobStatusPacket.Status.STARTED);
        recordWaitingDetail(cpuCluster, plan, job, missingInitialItems);

        return CraftingSubmitResult.successful(null);
    }

    static KeyCounter extractAvailableInitialItems(
            ICraftingPlan plan,
            IGrid grid,
            ListCraftingInventory cpuInventory,
            IActionSource src
    ) {
        KeyCounter requiredInitialItems = new KeyCounter();
        requiredInitialItems.addAll(plan.usedItems());
        requiredInitialItems.addAll(plan.missingItems());
        KeyCounter missingInitialItems = new KeyCounter();
        var storage = grid.getStorageService().getInventory();

        for (var entry : requiredInitialItems) {
            AEKey what = entry.getKey();
            long toExtract = entry.getLongValue();
            long extracted = storage.extract(what, toExtract, Actionable.MODULATE, src);
            cpuInventory.insert(what, extracted, Actionable.MODULATE);
            if (extracted < toExtract) {
                missingInitialItems.add(what, toExtract - extracted);
            }
        }

        return missingInitialItems;
    }

    static void seedInitialWaitingFor(ExecutingCraftingJob job, KeyCounter missingInitialItems) {
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            return;
        }

        ExecutingCraftingJobAccessor accessor = (ExecutingCraftingJobAccessor) job;
        for (var entry : missingInitialItems) {
            accessor.getWaitingFor().insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            addTrackedWaitingTime(accessor, entry.getLongValue(), entry.getKey());
        }
    }

    static void recordWaitingDetail(
            CraftingCPUCluster cpuCluster,
            ICraftingPlan plan,
            ExecutingCraftingJob job,
            KeyCounter missingInitialItems
    ) {
        if (!(cpuCluster.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID craftId = ((ExecutingCraftingJobAccessor) job).getLink().getCraftingID();
        if (missingInitialItems == null || missingInitialItems.isEmpty()) {
            CraftingContinuationStatusService.get(serverLevel).clearCompletedJob(craftId);
            return;
        }

        List<CraftingContinuationWaitingBranch> waitingBranches = new ArrayList<>();
        int order = 0;
        for (var entry : missingInitialItems) {
            waitingBranches.add(new CraftingContinuationWaitingBranch(
                    entry.getKey().getDisplayName().getString(),
                    order++,
                    Map.of(CraftingContinuationStatusService.encodeKeyForSync(entry.getKey()), entry.getLongValue())
            ));
        }

        List<String> runningBranchLabels = new ArrayList<>();
        for (var entry : plan.patternTimes().entrySet()) {
            for (var output : entry.getKey().getOutputs()) {
                runningBranchLabels.add(output.what().getDisplayName().getString());
                break;
            }
        }

        CraftingContinuationStatusService.get(serverLevel).trackJob(new CraftingContinuationWaitingDetail(
                craftId,
                CraftingContinuationStatusService.encodeKeyForSync(plan.finalOutput().what()),
                plan.finalOutput().amount(),
                waitingBranches,
                runningBranchLabels
        ));
    }

    private static ExecutingCraftingJob createExecutingJob(
            ICraftingPlan plan,
            CraftingLink link,
            IActionSource src,
            CraftingCpuLogicAccessor logicAccessor
    ) {
        try {
            Class<?> listenerType = Class.forName(
                    "appeng.crafting.execution.ExecutingCraftingJob$CraftingDifferenceListener");
            Object listener = Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (proxy, method, args) -> {
                        if ("onCraftingDifference".equals(method.getName()) && args != null && args.length == 1) {
                            logicAccessor.invokePostChange((AEKey) args[0]);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });

            Constructor<ExecutingCraftingJob> constructor = ExecutingCraftingJob.class.getDeclaredConstructor(
                    ICraftingPlan.class,
                    listenerType,
                    CraftingLink.class,
                    Integer.class);
            constructor.setAccessible(true);
            return constructor.newInstance(plan, listener, link, resolvePlayerId(src));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to construct partial AE2 crafting job", exception);
        }
    }

    private static @Nullable Integer resolvePlayerId(IActionSource src) {
        return src.player()
                .map(player -> player instanceof ServerPlayer serverPlayer
                        ? IPlayerRegistry.getPlayerId(serverPlayer)
                        : null)
                .orElse(null);
    }

    private static void addTrackedWaitingTime(ExecutingCraftingJobAccessor accessor, long amount, AEKey key) {
        try {
            var timeTracker = accessor.getTimeTracker();
            Method addMaxItems = timeTracker.getClass()
                    .getDeclaredMethod("addMaxItems", long.class, AEKeyType.class);
            addMaxItems.setAccessible(true);
            addMaxItems.invoke(timeTracker, amount, key.getType());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to extend AE2 time tracker for continuation waiting", exception);
        }
    }
}
