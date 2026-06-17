package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ParallelCpuWaitingIndex {

    private final Map<AEKey, Long> requestedAmounts = new HashMap<>();
    private final Map<AEKey, Set<ParallelCraftingLane>> lanesByKey = new HashMap<>();
    private final Map<ParallelCraftingLane, Map<AEKey, Long>> waitingByLane = new IdentityHashMap<>();
    private final Set<AEKey> changedKeys = new HashSet<>();
    private final Set<AEKey> changedPresenceKeys = new HashSet<>();
    private final Set<ParallelCraftingLane> inactiveLanes = newIdentityLaneSet();

    public void rebuild(Iterable<? extends ParallelCraftingLane> lanes) {
        Set<AEKey> previousKeys = new HashSet<>(requestedAmounts.keySet());
        requestedAmounts.clear();
        lanesByKey.clear();
        waitingByLane.clear();

        if (lanes != null) {
            for (ParallelCraftingLane lane : lanes) {
                replaceLaneSnapshot(lane, captureWaitingSnapshot(lane));
            }
        }

        changedKeys.addAll(previousKeys);
        changedKeys.addAll(requestedAmounts.keySet());
        changedPresenceKeys.addAll(previousKeys);
        changedPresenceKeys.addAll(requestedAmounts.keySet());
    }

    public void refreshLane(ParallelCraftingLane lane) {
        Map<AEKey, Long> nextSnapshot = captureWaitingSnapshot(lane);
        if (lane != null && !lane.isLaneActive()) {
            inactiveLanes.add(lane);
        }
        Map<AEKey, Long> previousSnapshot = waitingByLane.get(lane);
        if ((previousSnapshot == null || previousSnapshot.isEmpty()) && nextSnapshot.isEmpty()) {
            return;
        }
        if (nextSnapshot.equals(previousSnapshot)) {
            return;
        }
        replaceLaneSnapshot(lane, nextSnapshot);
    }

    public void removeLane(ParallelCraftingLane lane) {
        if (lane == null) {
            return;
        }
        inactiveLanes.remove(lane);
        removePreviousSnapshot(lane);
    }

    public long insertIntoLanes(
            @Nullable AEKey what,
            long amount,
            Actionable mode,
            @Nullable ParallelCpuMetrics metrics
    ) {
        return insertIntoLanesAndGetResult(what, amount, mode, metrics).physicalInserted();
    }

    public InsertResult insertIntoLanesAndGetResult(
            @Nullable AEKey what,
            long amount,
            Actionable mode,
            @Nullable ParallelCpuMetrics metrics
    ) {
        if (what == null || amount <= 0L || mode == null) {
            return InsertResult.EMPTY;
        }

        Set<ParallelCraftingLane> indexedLanes = lanesByKey.get(what);
        if (indexedLanes == null || indexedLanes.isEmpty()) {
            return InsertResult.EMPTY;
        }

        return mode == Actionable.MODULATE
                ? insertIntoLanesModulating(what, amount, metrics)
                : simulateInsertIntoLanes(what, amount, indexedLanes, metrics);
    }

    private InsertResult insertIntoLanesModulating(
            AEKey what,
            long amount,
            @Nullable ParallelCpuMetrics metrics
    ) {
        long physicalInserted = 0L;
        long accounted = 0L;
        while (accounted < amount) {
            Set<ParallelCraftingLane> indexedLanes = lanesByKey.get(what);
            if (indexedLanes == null || indexedLanes.isEmpty()) {
                break;
            }

            ParallelCraftingLane lane = indexedLanes.iterator().next();
            long remaining = amount - accounted;
            ParallelCraftingLane.WaitingInsertResult laneResult = lane.insertIntoWaitingAndGetResult(
                    what,
                    remaining,
                    Actionable.MODULATE
            );
            long acceptedPhysical = Math.min(Math.max(0L, laneResult.physicalInserted()), remaining);
            long acceptedAccounted = Math.min(Math.max(0L, laneResult.accounted()), remaining);
            if (acceptedAccounted > 0L) {
                physicalInserted = saturatedAdd(physicalInserted, acceptedPhysical);
                accounted = saturatedAdd(accounted, acceptedAccounted);
                if (metrics != null) {
                    metrics.recordIndexedInsert(acceptedAccounted);
                }
                if (lane instanceof ParallelCraftingLaneState laneState) {
                    laneState.cluster().wakeLane(laneState);
                }
            }

            refreshLane(lane);
            if (acceptedAccounted <= 0L) {
                break;
            }
        }
        return new InsertResult(physicalInserted, accounted);
    }

    private InsertResult simulateInsertIntoLanes(
            AEKey what,
            long amount,
            Set<ParallelCraftingLane> indexedLanes,
            @Nullable ParallelCpuMetrics metrics
    ) {
        long physicalInserted = 0L;
        long accounted = 0L;
        Iterator<ParallelCraftingLane> iterator = indexedLanes.iterator();
        while (iterator.hasNext() && accounted < amount) {
            long remaining = amount - accounted;
            long accepted = Math.min(
                    Math.max(0L, iterator.next()
                            .insertIntoWaiting(what, remaining, Actionable.SIMULATE)),
                    remaining
            );
            if (accepted > 0L) {
                physicalInserted = saturatedAdd(physicalInserted, accepted);
                accounted = saturatedAdd(accounted, accepted);
                if (metrics != null) {
                    metrics.recordIndexedInsert(accepted);
                }
            }
        }
        return new InsertResult(physicalInserted, accounted);
    }

    public record InsertResult(long physicalInserted, long accounted) {
        private static final InsertResult EMPTY = new InsertResult(0L, 0L);

        public InsertResult {
            physicalInserted = Math.max(0L, physicalInserted);
            accounted = Math.max(0L, accounted);
        }
    }

    public long getRequestedAmount(@Nullable AEKey what) {
        if (what == null) {
            return 0L;
        }
        return requestedAmounts.getOrDefault(what, 0L);
    }

    public boolean isRequesting(@Nullable AEKey what) {
        return getRequestedAmount(what) > 0L;
    }

    public boolean isRequestingAny() {
        return !requestedAmounts.isEmpty();
    }

    public Set<AEKey> consumeChangedKeys() {
        if (changedKeys.isEmpty()) {
            return Set.of();
        }
        Set<AEKey> result = Set.copyOf(changedKeys);
        changedKeys.clear();
        return result;
    }

    public Set<AEKey> consumeChangedPresenceKeys() {
        if (changedPresenceKeys.isEmpty()) {
            return Set.of();
        }
        Set<AEKey> result = Set.copyOf(changedPresenceKeys);
        changedPresenceKeys.clear();
        return result;
    }

    public boolean hasChangedPresenceKeys() {
        return !changedPresenceKeys.isEmpty();
    }

    public void appendRequestingKeys(Set<AEKey> target) {
        if (target == null || requestedAmounts.isEmpty()) {
            return;
        }
        target.addAll(requestedAmounts.keySet());
    }

    public Set<ParallelCraftingLane> consumeInactiveLanes() {
        if (inactiveLanes.isEmpty()) {
            return Set.of();
        }
        Set<ParallelCraftingLane> result = Set.copyOf(inactiveLanes);
        inactiveLanes.clear();
        return result;
    }

    public List<ParallelCraftingLane> getLanesWaitingFor(@Nullable AEKey what) {
        if (what == null) {
            return List.of();
        }
        Set<ParallelCraftingLane> lanes = lanesByKey.get(what);
        return lanes == null || lanes.isEmpty() ? List.of() : List.copyOf(lanes);
    }

    public int indexedKeyCount() {
        return requestedAmounts.size();
    }

    public int indexedLaneCount() {
        return waitingByLane.size();
    }

    public void copyMetricsTo(ParallelCpuMetrics metrics) {
        if (metrics != null) {
            metrics.setWaitingIndexGauges(indexedLaneCount(), indexedKeyCount());
        }
    }

    private void replaceLaneSnapshot(ParallelCraftingLane lane, Map<AEKey, Long> nextSnapshot) {
        if (lane == null) {
            return;
        }

        Map<AEKey, Long> previousSnapshot = waitingByLane.getOrDefault(lane, Map.of());
        Set<AEKey> keys = new HashSet<>(previousSnapshot.keySet());
        keys.addAll(nextSnapshot.keySet());

        for (AEKey key : keys) {
            long previousAmount = previousSnapshot.getOrDefault(key, 0L);
            long nextAmount = nextSnapshot.getOrDefault(key, 0L);
            if (previousAmount == nextAmount) {
                continue;
            }

            adjustRequestedAmount(key, nextAmount - previousAmount);
            if (nextAmount <= 0L) {
                Set<ParallelCraftingLane> lanes = lanesByKey.get(key);
                if (lanes != null) {
                    lanes.remove(lane);
                    if (lanes.isEmpty()) {
                        lanesByKey.remove(key);
                    }
                }
            } else {
                lanesByKey.computeIfAbsent(key, ignored -> newIdentityLaneSet()).add(lane);
            }
        }

        if (nextSnapshot.isEmpty()) {
            waitingByLane.remove(lane);
            return;
        }
        waitingByLane.put(lane, nextSnapshot);
    }

    private void removePreviousSnapshot(ParallelCraftingLane lane) {
        Map<AEKey, Long> previousSnapshot = waitingByLane.remove(lane);
        if (previousSnapshot == null || previousSnapshot.isEmpty()) {
            return;
        }

        for (Map.Entry<AEKey, Long> entry : previousSnapshot.entrySet()) {
            AEKey key = entry.getKey();
            adjustRequestedAmount(key, -entry.getValue());
            Set<ParallelCraftingLane> lanes = lanesByKey.get(key);
            if (lanes != null) {
                lanes.remove(lane);
                if (lanes.isEmpty()) {
                    lanesByKey.remove(key);
                }
            }
        }
    }

    private Map<AEKey, Long> captureWaitingSnapshot(ParallelCraftingLane lane) {
        if (lane == null || !lane.isLaneActive()) {
            return Collections.emptyMap();
        }

        Map<AEKey, Long> snapshot = new HashMap<>();
        Iterable<Object2LongMap.Entry<AEKey>> waitingStacks = lane.getWaitingStacks();
        if (waitingStacks == null) {
            return Collections.emptyMap();
        }

        for (Object2LongMap.Entry<AEKey> entry : waitingStacks) {
            if (entry == null || entry.getKey() == null || entry.getLongValue() <= 0L) {
                continue;
            }
            snapshot.merge(entry.getKey(), entry.getLongValue(), ParallelCpuWaitingIndex::saturatedAdd);
        }
        return snapshot.isEmpty() ? Collections.emptyMap() : Map.copyOf(snapshot);
    }

    private void adjustRequestedAmount(AEKey key, long delta) {
        if (key == null || delta == 0L) {
            return;
        }
        long previous = requestedAmounts.getOrDefault(key, 0L);
        long next = delta > 0L
                ? saturatedAdd(previous, delta)
                : previous <= -delta ? 0L : previous + delta;
        if (next <= 0L) {
            requestedAmounts.remove(key);
        } else {
            requestedAmounts.put(key, next);
        }
        if (previous != next) {
            changedKeys.add(key);
            if ((previous > 0L) != (next > 0L)) {
                changedPresenceKeys.add(key);
            }
        }
    }

    private static Set<ParallelCraftingLane> newIdentityLaneSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static long saturatedAdd(long left, long right) {
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
