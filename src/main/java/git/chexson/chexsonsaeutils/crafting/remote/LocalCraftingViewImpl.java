package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.AEKeyFilter;
import com.google.common.collect.ImmutableSet;
import net.minecraft.world.level.Level;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Future;

/** 来源规划的本地能力视图，整个依赖树排除跨网样板；其余真实 AE2 服务行为直接委托。 */
public final class LocalCraftingViewImpl implements ICraftingService,
        git.chexson.chexsonsaeutils.crafting.graphplan.graph.ProviderPatternOrder.PatternPrioritySource {
    private final ICraftingService delegate;
    private final appeng.api.networking.IGrid grid;
    public LocalCraftingViewImpl(ICraftingService delegate, appeng.api.networking.IGrid grid) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.grid = java.util.Objects.requireNonNull(grid);
    }
    @Override public Collection<IPatternDetails> getCraftingFor(AEKey key) {
        return delegate.getCraftingFor(key).stream().filter(p -> !(p instanceof RemotePattern)).toList();
    }
    @Override public int patternPriority(IPatternDetails pattern) {
        return git.chexson.chexsonsaeutils.crafting.graphplan.graph.ProviderPatternOrder.observedPriority(delegate, pattern);
    }
    @Override public void refreshNodeCraftingProvider(IGridNode node) { delegate.refreshNodeCraftingProvider(node); }
    @Override public void addGlobalCraftingProvider(ICraftingProvider provider) { delegate.addGlobalCraftingProvider(provider); }
    @Override public void removeGlobalCraftingProvider(ICraftingProvider provider) { delegate.removeGlobalCraftingProvider(provider); }
    @Override public void refreshGlobalCraftingProvider(ICraftingProvider provider) { delegate.refreshGlobalCraftingProvider(provider); }
    @Override public AEKey getFuzzyCraftable(AEKey key, AEKeyFilter filter) {
        return getCraftables(filter).stream().filter(k -> k.fuzzyEquals(key, appeng.api.config.FuzzyMode.IGNORE_ALL)).findFirst().orElse(null);
    }
    @Override public Future<ICraftingPlan> beginCraftingCalculation(Level level, ICraftingSimulationRequester requester,
            AEKey key, long amount, CalculationStrategy strategy) {
        var stock = new appeng.api.stacks.KeyCounter();
        grid.getStorageService().getInventory().getAvailableStacks(stock);
        return RemoteCalculations.submit(() -> RemotePlanning.plan(level,
                delegate, key, java.math.BigInteger.valueOf(amount), grid, stock, null, strategy));
    }
    @Override public ICraftingSubmitResult submitJob(ICraftingPlan plan, ICraftingRequester requester,
            ICraftingCPU cpu, boolean prioritizePower, IActionSource source) {
        if (plan.patternTimes().keySet().stream().anyMatch(RemotePattern.class::isInstance))
            throw new IllegalArgumentException("来源计划不允许再次跨网");
        return delegate.submitJob(plan, requester, cpu, prioritizePower, source);
    }
    @Override public ImmutableSet<ICraftingCPU> getCpus() { return delegate.getCpus(); }
    @Override public boolean canEmitFor(AEKey key) { return delegate.canEmitFor(key); }
    @Override public Set<AEKey> getCraftables(AEKeyFilter filter) {
        return delegate.getCraftables(filter).stream().filter(k -> !getCraftingFor(k).isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public boolean isRequesting(AEKey key) { return delegate.isRequesting(key); }
    @Override public long getRequestedAmount(AEKey key) { return delegate.getRequestedAmount(key); }
    @Override public boolean isRequestingAny() { return delegate.isRequestingAny(); }
}
