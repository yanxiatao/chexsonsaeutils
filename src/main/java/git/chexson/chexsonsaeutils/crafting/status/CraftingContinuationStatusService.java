package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.config.Actionable;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.helpers.PlayerSource;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftingCPUMenu;
import git.chexson.chexsonsaeutils.crafting.persistence.CraftingContinuationSavedData;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.CraftingCpuLogicAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.ExecutingCraftingJobAccessor;
import git.chexson.chexsonsaeutils.mixin.ae2.menu.CraftingCPUMenuAccessor;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.ToLongFunction;

public final class CraftingContinuationStatusService {
    private static final Map<ServerLevel, CraftingContinuationStatusService> INSTANCES = new WeakHashMap<>();

    private final ServerLevel level;
    private final Map<UUID, CraftingContinuationWaitingDetail> trackedJobs = new LinkedHashMap<>();
    private final Map<UUID, Map<AEKey, Long>> observedAvailableWaitingStacks = new HashMap<>();

    private CraftingContinuationStatusService(ServerLevel level) {
        this.level = level;
        rebuildAfterLoad(level);
    }

    public static CraftingContinuationStatusService get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, CraftingContinuationStatusService::new);
    }

    public static void syncSelectedCpuDetailForMenu(CraftingCPUMenu menu) {
        ICraftingCPU selectedCpu = ((CraftingCPUMenuAccessor) menu).chexsonsaeutils$getCpu();
        if (!(menu instanceof SelectedCpuDetailHost host)) {
            return;
        }
        if (!(selectedCpu instanceof CraftingCPUCluster cpuCluster)
                || !(cpuCluster.getLevel() instanceof ServerLevel serverLevel)) {
            host.chexsonsaeutils$setSelectedCpuDetail(null);
            return;
        }

        get(serverLevel).syncSelectedCpuDetail(menu);
    }

    public void trackJob(CraftingContinuationWaitingDetail detail) {
        if (detail == null) {
            return;
        }

        trackedJobs.put(detail.craftId(), detail);
        CraftingContinuationSavedData.get(level).putWaitingDetail(detail.craftId(), detail);
    }

    public void clearCompletedJob(UUID jobId) {
        if (jobId == null) {
            return;
        }

        trackedJobs.remove(jobId);
        observedAvailableWaitingStacks.remove(jobId);
        CraftingContinuationSavedData.get(level).removeWaitingDetail(jobId);
    }

    public void rebuildAfterLoad(ServerLevel level) {
        trackedJobs.clear();
        observedAvailableWaitingStacks.clear();
        trackedJobs.putAll(CraftingContinuationSavedData.get(level).snapshotWaitingDetails());
    }

    public static void reconcileWaitingInputs(
            @Nullable IGrid grid,
            Iterable<CraftingCPUCluster> cpus,
            @Nullable AEKey changedStack,
            boolean retainLiveCrafts
    ) {
        if (grid == null || cpus == null) {
            return;
        }

        Map<ServerLevel, Set<UUID>> liveCraftIdsByLevel = new HashMap<>();
        for (CraftingCPUCluster cpu : cpus) {
            if (cpu == null || !(cpu.getLevel() instanceof ServerLevel serverLevel)) {
                continue;
            }

            liveCraftIdsByLevel.computeIfAbsent(serverLevel, ignored -> new HashSet<>());
            CraftingContinuationStatusService service = get(serverLevel);
            UUID craftId = service.reconcileWaitingInputs(grid, cpu, changedStack);
            if (craftId != null) {
                liveCraftIdsByLevel.get(serverLevel).add(craftId);
            }
        }

        if (!retainLiveCrafts) {
            return;
        }

        for (var entry : liveCraftIdsByLevel.entrySet()) {
            get(entry.getKey()).retainLiveCrafts(entry.getValue());
        }
    }

    public static void reconcileWaitingInputsOnServerEndTick(
            @Nullable IGrid grid,
            Iterable<CraftingCPUCluster> cpus
    ) {
        if (grid == null || cpus == null) {
            return;
        }

        var cachedInventory = grid.getStorageService().getCachedInventory();
        Map<ServerLevel, Set<UUID>> liveCraftIdsByLevel = new HashMap<>();
        boolean availabilityIncreased = false;

        for (CraftingCPUCluster cpu : cpus) {
            if (cpu == null || !(cpu.getLevel() instanceof ServerLevel serverLevel)) {
                continue;
            }

            liveCraftIdsByLevel.computeIfAbsent(serverLevel, ignored -> new HashSet<>());
            CraftingContinuationStatusService service = get(serverLevel);
            if (service.recordWaitingAvailability(cpu, cachedInventory, liveCraftIdsByLevel.get(serverLevel))) {
                availabilityIncreased = true;
            }
        }

        for (var entry : liveCraftIdsByLevel.entrySet()) {
            get(entry.getKey()).retainObservedAvailability(entry.getValue());
        }

        if (availabilityIncreased) {
            reconcileWaitingInputs(grid, cpus, null, false);
        }
    }

    public @Nullable CraftingContinuationStatusSnapshot buildSnapshot(CraftingCPUCluster cpu) {
        if (cpu == null) {
            return null;
        }

        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
        ExecutingCraftingJob job = logicAccessor.getJob();
        if (job == null) {
            return null;
        }

        ICraftingLink lastLink = cpu.craftingLogic.getLastLink();
        if (lastLink == null) {
            return null;
        }

        UUID craftId = lastLink.getCraftingID();
        CraftingContinuationWaitingDetail detail = getTrackedJob(craftId);
        if (detail == null) {
            return null;
        }

        return buildLiveSnapshot(job, detail);
    }

    public void syncSelectedCpuDetail(CraftingCPUMenu menu) {
        if (!(menu instanceof SelectedCpuDetailHost host)) {
            return;
        }

        ICraftingCPU selectedCpu = ((CraftingCPUMenuAccessor) menu).chexsonsaeutils$getCpu();
        if (!(selectedCpu instanceof CraftingCPUCluster cpuCluster)
                || !(cpuCluster.getLevel() instanceof ServerLevel serverLevel)) {
            host.chexsonsaeutils$setSelectedCpuDetail(null);
            return;
        }

        CraftingContinuationStatusSnapshot snapshot = get(serverLevel).buildSnapshot(cpuCluster);
        host.chexsonsaeutils$setSelectedCpuDetail(snapshot);
    }

    private @Nullable CraftingContinuationStatusSnapshot buildLiveSnapshot(
            ExecutingCraftingJob job,
            CraftingContinuationWaitingDetail detail
    ) {
        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) job;
        UUID craftId = jobAccessor.getLink().getCraftingID();
        Map<String, Long> liveWaitingStacks = snapshotWaitingStacks(jobAccessor);
        List<CraftingContinuationWaitingBranch> liveWaitingBranches = buildLiveWaitingBranches(
                detail.waitingBranches(),
                liveWaitingStacks
        );
        if (liveWaitingBranches.isEmpty()) {
            clearCompletedJob(craftId);
            return null;
        }

        CraftingContinuationWaitingDetail liveDetail = new CraftingContinuationWaitingDetail(
                craftId,
                resolveFinalOutputKey(jobAccessor, detail),
                resolveDisplayedAmount(jobAccessor, detail),
                liveWaitingBranches,
                detail.runningBranchLabels()
        );
        trackJob(liveDetail);
        return new CraftingContinuationStatusSnapshot(
                liveDetail.craftId(),
                liveDetail.finalOutputKey(),
                liveDetail.requestedAmount(),
                true,
                liveDetail.waitingBranches(),
                liveDetail.runningBranchLabels()
        );
    }

    private static Map<String, Long> snapshotWaitingStacks(ExecutingCraftingJobAccessor jobAccessor) {
        Map<String, Long> waitingStacks = new LinkedHashMap<>();
        for (var entry : jobAccessor.getWaitingFor().list) {
            waitingStacks.merge(describeKey(entry.getKey()), entry.getLongValue(), Long::sum);
        }
        return waitingStacks;
    }

    private static List<CraftingContinuationWaitingBranch> buildLiveWaitingBranches(
            List<CraftingContinuationWaitingBranch> trackedWaitingBranches,
            Map<String, Long> liveWaitingStacks
    ) {
        if (trackedWaitingBranches == null || trackedWaitingBranches.isEmpty() || liveWaitingStacks.isEmpty()) {
            return List.of();
        }

        Map<String, Long> remainingWaitingStacks = new LinkedHashMap<>(liveWaitingStacks);
        var liveWaitingBranches = new java.util.ArrayList<CraftingContinuationWaitingBranch>(trackedWaitingBranches.size());
        for (CraftingContinuationWaitingBranch trackedBranch : trackedWaitingBranches) {
            Map<String, Long> branchWaitingStacks = new LinkedHashMap<>();
            for (var missingStack : trackedBranch.missingStacks().entrySet()) {
                long remainingAmount = remainingWaitingStacks.getOrDefault(missingStack.getKey(), 0L);
                if (remainingAmount <= 0L) {
                    continue;
                }

                long liveAmount = Math.min(missingStack.getValue(), remainingAmount);
                branchWaitingStacks.put(missingStack.getKey(), liveAmount);
                if (liveAmount == remainingAmount) {
                    remainingWaitingStacks.remove(missingStack.getKey());
                } else {
                    remainingWaitingStacks.put(missingStack.getKey(), remainingAmount - liveAmount);
                }
            }

            if (!branchWaitingStacks.isEmpty()) {
                liveWaitingBranches.add(new CraftingContinuationWaitingBranch(
                        trackedBranch.branchLabel(),
                        trackedBranch.planOrder(),
                        branchWaitingStacks
                ));
            }
        }

        return List.copyOf(liveWaitingBranches);
    }

    private static String resolveFinalOutputKey(
            ExecutingCraftingJobAccessor jobAccessor,
            CraftingContinuationWaitingDetail detail
    ) {
        GenericStack finalOutput = jobAccessor.getFinalOutput();
        return finalOutput == null || finalOutput.what() == null
                ? detail.finalOutputKey()
                : describeKey(finalOutput.what());
    }

    private static long resolveDisplayedAmount(
            ExecutingCraftingJobAccessor jobAccessor,
            CraftingContinuationWaitingDetail detail
    ) {
        long remainingAmount = jobAccessor.getRemainingAmount();
        return remainingAmount > 0L ? remainingAmount : detail.requestedAmount();
    }

    private @Nullable UUID reconcileWaitingInputs(
            IGrid grid,
            CraftingCPUCluster cpu,
            @Nullable AEKey changedStack
    ) {
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
        ExecutingCraftingJob job = logicAccessor.getJob();
        if (job == null) {
            return null;
        }

        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) job;
        UUID craftId = jobAccessor.getLink().getCraftingID();
        CraftingContinuationWaitingDetail detail = getTrackedJob(craftId);
        if (detail == null) {
            return craftId;
        }

        Map<AEKey, Long> liveWaitingKeys = snapshotWaitingKeys(jobAccessor);
        if (liveWaitingKeys.isEmpty()) {
            clearCompletedJob(craftId);
            return craftId;
        }

        String changedKey = encodeKeyForSync(changedStack);
        var storage = grid.getStorageService().getInventory();
        IActionSource refillActionSource = resolveRefillActionSource(jobAccessor, cpu);
        for (CraftingContinuationWaitingBranch waitingBranch : buildLiveWaitingBranches(
                detail.waitingBranches(),
                snapshotWaitingStacks(jobAccessor)
        )) {
            for (var missingStack : waitingBranch.missingStacks().entrySet()) {
                if (changedStack != null && !missingStack.getKey().equals(changedKey)) {
                    continue;
                }

                AEKey liveKey = findLiveWaitingKey(liveWaitingKeys, missingStack.getKey());
                if (liveKey == null) {
                    continue;
                }

                long stillWaiting = liveWaitingKeys.getOrDefault(liveKey, 0L);
                if (stillWaiting <= 0L) {
                    continue;
                }

                long extracted = storage.extract(liveKey, stillWaiting, Actionable.MODULATE, refillActionSource);
                if (extracted <= 0L) {
                    continue;
                }

                long inserted = cpu.insert(liveKey, extracted, Actionable.MODULATE, refillActionSource);
                if (inserted < extracted) {
                    storage.insert(liveKey, extracted - inserted, Actionable.MODULATE, refillActionSource);
                }
                liveWaitingKeys.put(liveKey, Math.max(0L, stillWaiting - inserted));
            }
        }

        buildLiveSnapshot(job, detail);
        return craftId;
    }

    private @Nullable CraftingContinuationWaitingDetail getTrackedJob(UUID craftId) {
        CraftingContinuationWaitingDetail detail = trackedJobs.get(craftId);
        if (detail != null) {
            return detail;
        }

        detail = CraftingContinuationSavedData.get(level).getWaitingDetail(craftId);
        if (detail != null) {
            trackedJobs.put(craftId, detail);
        }
        return detail;
    }

    private boolean recordWaitingAvailability(
            CraftingCPUCluster cpu,
            appeng.api.stacks.KeyCounter cachedInventory,
            Set<UUID> liveCraftIds
    ) {
        CraftingCpuLogicAccessor logicAccessor = (CraftingCpuLogicAccessor) cpu.craftingLogic;
        ExecutingCraftingJob job = logicAccessor.getJob();
        if (job == null) {
            return false;
        }

        ExecutingCraftingJobAccessor jobAccessor = (ExecutingCraftingJobAccessor) job;
        UUID craftId = jobAccessor.getLink().getCraftingID();
        CraftingContinuationWaitingDetail detail = getTrackedJob(craftId);
        if (detail == null) {
            observedAvailableWaitingStacks.remove(craftId);
            return false;
        }

        liveCraftIds.add(craftId);
        Map<AEKey, Long> liveWaitingKeys = snapshotWaitingKeys(jobAccessor);
        if (liveWaitingKeys.isEmpty()) {
            observedAvailableWaitingStacks.remove(craftId);
            return false;
        }

        Map<AEKey, Long> currentAvailableWaitingStacks = snapshotAvailableWaitingStacks(
                liveWaitingKeys,
                cachedInventory::get
        );
        Map<AEKey, Long> previousAvailableWaitingStacks = observedAvailableWaitingStacks.put(
                craftId,
                currentAvailableWaitingStacks
        );
        return previousAvailableWaitingStacks == null
                ? !currentAvailableWaitingStacks.isEmpty()
                : hasAvailabilityIncrease(previousAvailableWaitingStacks, currentAvailableWaitingStacks);
    }

    private void retainLiveCrafts(Set<UUID> liveCraftIds) {
        Set<UUID> safeLiveCraftIds = liveCraftIds == null ? Set.of() : Set.copyOf(liveCraftIds);
        trackedJobs.keySet().removeIf(craftId -> !safeLiveCraftIds.contains(craftId));
        CraftingContinuationSavedData.get(level).retainLiveCrafts(safeLiveCraftIds);
    }

    private void retainObservedAvailability(Set<UUID> liveCraftIds) {
        Set<UUID> safeLiveCraftIds = liveCraftIds == null ? Set.of() : Set.copyOf(liveCraftIds);
        observedAvailableWaitingStacks.keySet().removeIf(craftId -> !safeLiveCraftIds.contains(craftId));
    }

    private static Map<AEKey, Long> snapshotWaitingKeys(ExecutingCraftingJobAccessor jobAccessor) {
        Map<AEKey, Long> waitingKeys = new LinkedHashMap<>();
        for (var entry : jobAccessor.getWaitingFor().list) {
            waitingKeys.merge(entry.getKey(), entry.getLongValue(), Long::sum);
        }
        return waitingKeys;
    }

    private static @Nullable AEKey findLiveWaitingKey(Map<AEKey, Long> liveWaitingKeys, String keyDescription) {
        for (AEKey liveWaitingKey : liveWaitingKeys.keySet()) {
            if (keyDescription.equals(describeKey(liveWaitingKey))) {
                return liveWaitingKey;
            }
        }
        return null;
    }

    private static IActionSource resolveRefillActionSource(
            ExecutingCraftingJobAccessor jobAccessor,
            CraftingCPUCluster cpu
    ) {
        Integer playerId = jobAccessor.getPlayerId();
        if (playerId != null && cpu.getLevel().getServer() != null) {
            var connectedPlayer = IPlayerRegistry.getConnected(cpu.getLevel().getServer(), playerId);
            if (connectedPlayer != null) {
                IActionHost actionHost = cpu.getSrc().machine().orElse(null);
                return actionHost == null
                        ? new PlayerSource(connectedPlayer)
                        : new PlayerSource(connectedPlayer, actionHost);
            }
        }

        return cpu.getSrc();
    }

    static <K> Map<K, Long> snapshotAvailableWaitingStacks(
            Map<K, Long> waitingStacks,
            ToLongFunction<K> availableAmountLookup
    ) {
        Map<K, Long> availableWaitingStacks = new LinkedHashMap<>();
        if (waitingStacks == null || waitingStacks.isEmpty()) {
            return availableWaitingStacks;
        }

        for (var entry : waitingStacks.entrySet()) {
            long availableAmount = Math.max(0L, availableAmountLookup.applyAsLong(entry.getKey()));
            long relevantAmount = Math.min(entry.getValue(), availableAmount);
            if (relevantAmount > 0L) {
                availableWaitingStacks.put(entry.getKey(), relevantAmount);
            }
        }

        return availableWaitingStacks;
    }

    static <K> boolean hasAvailabilityIncrease(Map<K, Long> previousAvailableStacks, Map<K, Long> currentAvailableStacks) {
        if (currentAvailableStacks == null || currentAvailableStacks.isEmpty()) {
            return false;
        }

        Map<K, Long> safePreviousAvailableStacks = previousAvailableStacks == null ? Map.of() : previousAvailableStacks;
        for (var entry : currentAvailableStacks.entrySet()) {
            if (entry.getValue() > safePreviousAvailableStacks.getOrDefault(entry.getKey(), 0L)) {
                return true;
            }
        }

        return false;
    }

    private static String describeKey(@Nullable AEKey key) {
        return encodeKeyForSync(key);
    }

    public static String encodeKeyForSync(@Nullable AEKey key) {
        return key == null ? "" : key.toString();
    }

    public interface SelectedCpuDetailHost {
        void chexsonsaeutils$setSelectedCpuDetail(@Nullable CraftingContinuationStatusSnapshot snapshot);

        boolean chexsonsaeutils$partialWaiting();

        String chexsonsaeutils$finalOutput();

        long chexsonsaeutils$requestedAmount();

        String chexsonsaeutils$waitingBranchLines();

        String chexsonsaeutils$waitingStackLines();
    }

    public interface WaitingStackProjectionHost {
        boolean chexsonsaeutils$partialWaiting();

        Map<String, Long> chexsonsaeutils$waitingStackAmounts();
    }
}
