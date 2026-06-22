package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.networking.crafting.ICraftingProvider;

import java.util.IdentityHashMap;
import java.util.Map;

public final class ParallelCpuProviderBackoff {

    public static final int DEFAULT_BASE_BACKOFF_TICKS = 2;
    public static final int DEFAULT_MAX_BACKOFF_TICKS = 40;

    private final Map<ICraftingProvider, ProviderState> providerStates = new IdentityHashMap<>();
    private final int baseBackoffTicks;
    private final int maxBackoffTicks;

    public ParallelCpuProviderBackoff() {
        this(DEFAULT_BASE_BACKOFF_TICKS, DEFAULT_MAX_BACKOFF_TICKS);
    }

    public ParallelCpuProviderBackoff(int baseBackoffTicks, int maxBackoffTicks) {
        this.baseBackoffTicks = Math.max(1, baseBackoffTicks);
        this.maxBackoffTicks = Math.max(this.baseBackoffTicks, maxBackoffTicks);
    }

    public ProviderAvailability checkProvider(
            ICraftingProvider provider,
            long currentTick,
            ParallelCpuGridBudgetLedger budgetLedger,
    ) {
        if (provider == null) {
            return ProviderAvailability.BACKED_OFF;
        }

        ProviderState state = providerStates.get(provider);
        if (state != null && state.shouldSkip(currentTick)) {
            if (metrics != null) {
                metrics.recordBusyProviderSkip();
            }
            return ProviderAvailability.BACKED_OFF;
        }

        if (budgetLedger != null && !budgetLedger.tryClaimProviderCheck()) {
            return ProviderAvailability.BUDGET_EXHAUSTED;
        }

        if (metrics != null) {
            metrics.recordProviderScan();
        }

        if (provider.isBusy()) {
            recordBusy(provider, currentTick);
            if (metrics != null) {
                metrics.recordBusyProviderSkip();
            }
            return ProviderAvailability.BUSY;
        }

        recordReady(provider);
        return ProviderAvailability.READY;
    }

    public void recordPushRejected(ICraftingProvider provider, long currentTick) {
        recordBusy(provider, currentTick);
    }

    public void recordPushAccepted(ICraftingProvider provider) {
        recordReady(provider);
    }

    public void clear() {
        providerStates.clear();
    }

    public int trackedProviderCount() {
        return providerStates.size();
    }

    private void recordBusy(ICraftingProvider provider, long currentTick) {
        ProviderState state = providerStates.computeIfAbsent(provider, ignored -> new ProviderState());
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
        ProviderState state = providerStates.get(provider);
        if (state == null) {
            return;
        }
        state.consecutiveBusy = 0;
        state.unavailableUntilTick = -1L;
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
