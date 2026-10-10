package git.chexson.chexsonsaeutils.crafting.parallelcpu;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ParallelCpuProviderBackoff {

    private final Map<ICraftingProvider, ProviderState> busyProviderStates = new IdentityHashMap<>();
    /**
     * 拒收退避表：键是<b>腿 + 样板</b>（{@link RejectedPushKey}）。申报批与 k 倍 holder 是两种能力，
     * "这台机器现在不接申报批"必须只记在申报腿那一桶里——同一 tick 的 holder 路照旧要判 READY
     * （实机 lane 09a75f4a 那一型：申报腿的拒收把 holder 路一起堵死，于是 {@code pushed=0}）。
     */
    private final Map<ICraftingProvider, Map<RejectedPushKey, ProviderState>> rejectedPushStates =
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
            PushLeg leg,
            long currentTick
    ) {
        if (provider == null) {
            return ProviderAvailability.BACKED_OFF;
        }

        ProviderState busyState = busyProviderStates.get(provider);
        if (busyState != null && busyState.shouldSkip(currentTick)) {
            return ProviderAvailability.BACKED_OFF;
        }
        Map<RejectedPushKey, ProviderState> pushStates = rejectedPushStates.get(provider);
        ProviderState rejectedState = pushStates == null ? null : pushStates.get(key(leg, pattern));
        if (rejectedState != null && rejectedState.shouldSkip(currentTick)) {
            return ProviderAvailability.BACKED_OFF;
        }

        if (provider.isBusy()) {
            recordBusy(provider, currentTick);
            return ProviderAvailability.BUSY;
        }

        recordReady(provider);
        return ProviderAvailability.READY;
    }

    public void recordPushRejected(
            ICraftingProvider provider, IPatternDetails pattern, PushLeg leg, long currentTick) {
        Map<RejectedPushKey, ProviderState> pushStates = rejectedPushStates.computeIfAbsent(
                provider, ignored -> new HashMap<>());
        recordUnavailable(pushStates.computeIfAbsent(
                key(leg, pattern), ignored -> new ProviderState()), currentTick);
    }

    public void recordPushAccepted(ICraftingProvider provider, IPatternDetails pattern, PushLeg leg) {
        Map<RejectedPushKey, ProviderState> pushStates = rejectedPushStates.get(provider);
        if (pushStates == null) {
            return;
        }
        // 清账只清<b>这一条腿</b>那一桶：holder 推成功不许顺手解掉申报腿的退避，反向同理。
        pushStates.remove(key(leg, pattern));
        if (pushStates.isEmpty()) {
            rejectedPushStates.remove(provider);
        }
    }

    public void clear() {
        busyProviderStates.clear();
        rejectedPushStates.clear();
    }

    public int trackedProviderCount() {
        java.util.Set<ICraftingProvider> providers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        providers.addAll(busyProviderStates.keySet());
        providers.addAll(rejectedPushStates.keySet());
        return providers.size();
    }

    /** 退避表的拒收键：先认腿再认样板；样板那一级仍用 {@code IPatternDetails} 的 equals/hashCode（与改前同尺）。 */
    private static RejectedPushKey key(PushLeg leg, IPatternDetails pattern) {
        return new RejectedPushKey(leg, pattern);
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

    /**
     * 推送的两条<b>能力腿</b>：退避表按这一维分桶。
     *
     * <ul>
     *   <li>{@link #DECLARED}＝「单发原型 + 份数声明」批（{@code DeclaredBatchHost#pushDeclaredBatch}）；</li>
     *   <li>{@link #HOLDER}＝k 倍 holder 与 NeoEco 批（{@code ICraftingProvider#pushPattern} 那一形态）。</li>
     * </ul>
     *
     * <p>机器忙（{@code isBusy}）那一本表<b>不分腿</b>：它是机器此刻的物理状态，两条腿都进不去。
     */
    public enum PushLeg {
        DECLARED,
        HOLDER
    }

    public enum ProviderAvailability {
        READY,
        BUSY,
        BACKED_OFF
    }

    private record RejectedPushKey(PushLeg leg, IPatternDetails pattern) {
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
