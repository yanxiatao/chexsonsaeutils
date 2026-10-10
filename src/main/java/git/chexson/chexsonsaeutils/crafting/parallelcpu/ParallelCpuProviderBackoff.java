package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ParallelCpuProviderBackoff {

    private final Map<ICraftingProvider, ProviderState> busyProviderStates = new IdentityHashMap<>();
    private final Map<ICraftingProvider, Map<IPatternDetails, ProviderState>> rejectedPatternStates =
            new IdentityHashMap<>();
    private final int baseBackoffTicks;
    private final int maxBackoffTicks;

    public ParallelCpuProviderBackoff(int baseBackoffTicks, int maxBackoffTicks) {
        this.baseBackoffTicks = Math.max(1, baseBackoffTicks);
        this.maxBackoffTicks = Math.max(this.baseBackoffTicks, maxBackoffTicks);
    }

    public ProviderAvailability checkProvider(
            ICraftingProvider provider,
            IPatternDetails pattern,
            long currentTick,
            ParallelCpuGridBudgetLedger budgetLedger
    ) {
        if (provider == null) {
            return ProviderAvailability.BACKED_OFF;
        }

        ProviderState busyState = busyProviderStates.get(provider);
        if (busyState != null && busyState.shouldSkip(currentTick)) {
            return ProviderAvailability.BACKED_OFF;
        }
        Map<IPatternDetails, ProviderState> patternStates = rejectedPatternStates.get(provider);
        ProviderState rejectedState = patternStates == null ? null : patternStates.get(pattern);
        if (rejectedState != null && rejectedState.shouldSkip(currentTick)) {
            return ProviderAvailability.BACKED_OFF;
        }

        if (budgetLedger != null && !budgetLedger.tryClaimProviderCheck()) {
            return ProviderAvailability.BUDGET_EXHAUSTED;
        }

        if (provider.isBusy()) {
            recordBusy(provider, currentTick);
            return ProviderAvailability.BUSY;
        }

        recordReady(provider);
        return ProviderAvailability.READY;
    }

    public void recordPushRejected(ICraftingProvider provider, IPatternDetails pattern, long currentTick) {
        Map<IPatternDetails, ProviderState> patternStates = rejectedPatternStates.computeIfAbsent(
                provider, ignored -> new HashMap<>());
        recordUnavailable(patternStates.computeIfAbsent(pattern, ignored -> new ProviderState()), currentTick);
    }

    public void recordPushAccepted(ICraftingProvider provider, IPatternDetails pattern) {
        Map<IPatternDetails, ProviderState> patternStates = rejectedPatternStates.get(provider);
        if (patternStates == null) {
            return;
        }
        patternStates.remove(pattern);
        if (patternStates.isEmpty()) {
            rejectedPatternStates.remove(provider);
        }
    }

    public void clear() {
        busyProviderStates.clear();
        rejectedPatternStates.clear();
    }

    public int trackedProviderCount() {
        java.util.Set<ICraftingProvider> providers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        providers.addAll(busyProviderStates.keySet());
        providers.addAll(rejectedPatternStates.keySet());
        return providers.size();
    }

    private void recordBusy(ICraftingProvider provider, long currentTick) {
        ProviderState state = busyProviderStates.computeIfAbsent(provider, ignored -> new ProviderState());
        recordUnavailable(state, currentTick);
    }

    private void recordUnavailable(ProviderState state, long currentTick) {
        if (state.lastBusyTick == currentTick) {
            state.unavailableUntilTick = Math.max(state.unavailableUntilTick, currentTick + 1L);
            return;
        }

        if (state.lastBusyTick >= 0L && currentTick <= state.unavailableUntilTick + 1L) {
            state.consecutiveBusy = Math.min(30, state.consecutiveBusy + 1);
        } else {
            state.consecutiveBusy = 1;
        }

        int delay = calculateDelay(state.consecutiveBusy);
        state.lastBusyTick = currentTick;
        state.unavailableUntilTick = Math.max(state.unavailableUntilTick, currentTick + delay);
    }

    private void recordReady(ICraftingProvider provider) {
        busyProviderStates.remove(provider);
    }

    private int calculateDelay(int consecutiveBusy) {
        int shift = Math.min(20, Math.max(0, consecutiveBusy - 1));
        long delay = (long) baseBackoffTicks << shift;
        return (int) Math.min(maxBackoffTicks, delay);
    }

    public enum ProviderAvailability {
        READY,
        BUSY,
        BACKED_OFF,
        BUDGET_EXHAUSTED
    }

    private static final class ProviderState {
        private long lastBusyTick = -1L;
        private long unavailableUntilTick = -1L;
        private int consecutiveBusy;

        private boolean shouldSkip(long currentTick) {
            return lastBusyTick == currentTick || currentTick < unavailableUntilTick;
        }
    }
}
