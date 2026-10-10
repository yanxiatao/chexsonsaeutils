package git.chexson.chexsonsaeutils.integration.hostbatch;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.core.AELog;
import git.chexson.chexsonsaeutils.crafting.hostbatch.DeclaredBatchHost;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchProvider;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HolderBatchCapability;
import git.chexson.chexsonsaeutils.crafting.hostbatch.ProviderBatchCapabilities;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerBatchClient;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerDeclaredHost;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessFurnaceBatchHost;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** 集中宿主能力适配；缓存按真实提供者身份认人，声明退避始终看到同一颗宿主壳。 */
public final class ProviderBatchCapabilitiesImpl implements ProviderBatchCapabilities {
    private final Supplier<IGrid> grid;
    /** 缓存属于单条产线；第 33 台新宿主到来时清空，避免攥住卸载的实体。 */
    private final Map<ICraftingProvider, UselessBigIntegerDeclaredHost> declaredHosts = new IdentityHashMap<>();

    public ProviderBatchCapabilitiesImpl(Supplier<IGrid> grid) {
        this.grid = grid;
    }

    @Override
    public @Nullable DeclaredBatchHost nativeDeclaredHost(ICraftingProvider provider, IPatternDetails pattern,
            Predicate<DeclaredBatchHost> ready) {
        if (provider instanceof DeclaredBatchHost nativeHost && nativeHost.supportsDeclaredBatch(pattern)
                && ready.test(nativeHost)) {
            return nativeHost;
        }
        return null;
    }

    @Override
    public @Nullable DeclaredBatchHost adaptedDeclaredHost(ICraftingProvider provider, IPatternDetails pattern,
            Predicate<DeclaredBatchHost> ready) {
        // 双接口提供者沿用原来的 else-if：原生 holder 已命中时不走 useless 折叠或 bigint 探测。
        if (provider instanceof HostBatchProvider || !(provider instanceof UselessFurnaceBatchHost)) {
            return null;
        }
        var host = bigintDeclaredHost(provider);
        return host.channelAvailable(pattern) && ready.test(host) ? host : null;
    }

    /** 同一会话同一提供者稳定身份；首次创建壳时才取此刻的网格。 */
    UselessBigIntegerDeclaredHost bigintDeclaredHost(ICraftingProvider provider) {
        var cached = declaredHosts.get(provider);
        if (cached != null) {
            return cached;
        }
        if (declaredHosts.size() >= 32) {
            declaredHosts.clear();
        }
        var created = new UselessBigIntegerDeclaredHost(provider, grid.get());
        declaredHosts.put(provider, created);
        return created;
    }

    @Override
    public @Nullable HolderBatchCapability holderCapability(ICraftingProvider provider) {
        if (provider instanceof HostBatchProvider host) {
            return new NativeHolderBatchCapabilityImpl(host);
        } else if (provider instanceof UselessFurnaceBatchHost host) {
            return new FoldedHolderBatchCapabilityImpl(host);
        }
        return null;
    }

    /** 原生 holder 准备阶段重读动态上限，保留探测后容量缩小的拒绝语义。 */
    private record NativeHolderBatchCapabilityImpl(HostBatchProvider host) implements HolderBatchCapability {
        @Override
        public long maxPushExecutions(IPatternDetails pattern) { return host.maxPushExecutions(pattern); }
        @Override
        public @Nullable IPatternDetails preparePushPattern(IPatternDetails pattern, long executions) {
            return executions > host.maxPushExecutions(pattern) ? null : pattern;
        }
        @Override
        public boolean foldsPattern() { return false; }
    }

    /** 折叠入口的准备只调用折叠口，不引入第二次动态上限读取。 */
    private record FoldedHolderBatchCapabilityImpl(UselessFurnaceBatchHost host) implements HolderBatchCapability {
        @Override
        public long maxPushExecutions(IPatternDetails pattern) {
            return host.chexsonsaeutils$maxFoldedPushExecutions(pattern);
        }
        @Override
        public @Nullable IPatternDetails preparePushPattern(IPatternDetails pattern, long executions) {
            return host.chexsonsaeutils$foldPushPattern(pattern, executions);
        }
        @Override
        public boolean foldsPattern() { return true; }
    }

    @Override
    public String queuePendingReadout() {
        var byIdentity = new LinkedHashMap<String, UselessBigIntegerBatchClient.Machine>();
        try {
            for (var host : declaredHosts.values()) {
                var machines = host.lastPushedMachines();
                if (machines == null) {
                    continue;
                }
                for (var machine : machines) {
                    if (machine != null) {
                        byIdentity.putIfAbsent(machine.machineIdentity(), machine);
                    }
                }
            }
        } catch (Throwable thrown) {
            AELog.error(thrown, "Parallel CPU batch machine identity readout failed.");
            return queuePendingReadout(List.of());
        }
        return queuePendingReadout(List.copyOf(byIdentity.values()));
    }

    /** 已推送机器的只读聚合口；不可读取者计入 total，不能以零冒充已读取。 */
    static String queuePendingReadout(@Nullable List<UselessBigIntegerBatchClient.Machine> machines) {
        long touched = 0L, shown = 0L, used = 0L, cap = 0L;
        boolean saturated = false;
        if (machines != null) {
            for (var machine : machines) {
                touched++;
                UselessBigIntegerBatchClient.QueuePending pending;
                try {
                    pending = machine == null ? null : machine.queuePending();
                } catch (Throwable thrown) {
                    AELog.error(thrown, "Parallel CPU batch queue depth readout failed.");
                    continue;
                }
                if (pending != null) {
                    shown++;
                    used += pending.entries();
                    cap += pending.cap();
                    saturated |= pending.saturated();
                }
            }
        }
        if (shown == 0L) {
            return " queuePending[useless侧]=? queuePendingCov=shown=0/total=" + touched;
        }
        return " queuePending[useless侧]=" + used + "/" + cap + (saturated ? "(full)" : "")
                + " queuePendingCov=shown=" + shown + "/total=" + touched;
    }
}
