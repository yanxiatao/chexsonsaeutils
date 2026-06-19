package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.me.service.helpers.NetworkCraftingProviders;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 染色样板索引服务。
 *
 * 只为规划层提供按颜色分组的样板视图，不改动 AE2 原生 craftable 索引。
 */
public class DyeablePatternCraftingProviders extends NetworkCraftingProviders {

    private final Map<Integer, Set<IPatternDetails>> patternsByColor = new HashMap<>();
    private final Map<Integer, DyeablePatternCompressedRing> compressedRingCache = new HashMap<>();
    private final Map<IGridNode, ProviderSnapshot> indexedNodeProviders = new HashMap<>();
    private final Map<ICraftingProvider, ProviderSnapshot> indexedGlobalProviders = new IdentityHashMap<>();

    @Override
    public void addProvider(IGridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        super.addProvider(node);
        if (provider != null) {
            this.indexedNodeProviders.put(node, snapshotProvider(provider));
            rebuildColorIndex();
        }
    }

    @Override
    public void addProvider(ICraftingProvider provider) {
        super.addProvider(provider);
        this.indexedGlobalProviders.put(provider, snapshotProvider(provider));
        rebuildColorIndex();
    }

    @Override
    public void removeProvider(IGridNode node) {
        super.removeProvider(node);
        if (this.indexedNodeProviders.remove(node) != null) {
            rebuildColorIndex();
        }
    }

    @Override
    public void removeProvider(ICraftingProvider provider) {
        super.removeProvider(provider);
        if (this.indexedGlobalProviders.remove(provider) != null) {
            rebuildColorIndex();
        }
    }

    @Override
    public Set<AEKey> getCraftableKeys() {
        synchronizeIndexedProviders();
        return super.getCraftableKeys();
    }

    @Override
    public Set<AEKey> getEmittableKeys() {
        synchronizeIndexedProviders();
        return super.getEmittableKeys();
    }

    @Override
    public Set<AEKey> getCraftables(AEKeyFilter filter) {
        synchronizeIndexedProviders();
        return super.getCraftables(filter);
    }

    @Override
    public Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
        synchronizeIndexedProviders();
        return super.getCraftingFor(whatToCraft);
    }

    @Override
    public @Nullable AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
        synchronizeIndexedProviders();
        return super.getFuzzyCraftable(whatToCraft, filter);
    }

    @Override
    public boolean canEmitFor(AEKey someItem) {
        synchronizeIndexedProviders();
        return super.canEmitFor(someItem);
    }

    @Override
    public Iterable<ICraftingProvider> getMediums(IPatternDetails key) {
        synchronizeIndexedProviders();
        return super.getMediums(key);
    }

    @Override
    public long getLastModifiedOnTick() {
        synchronizeIndexedProviders();
        return super.getLastModifiedOnTick();
    }

    public Collection<IPatternDetails> getPatternsByColor(int color) {
        synchronizeIndexedProviders();
        var patterns = this.patternsByColor.get(color);
        return patterns == null ? Collections.emptySet() : Collections.unmodifiableSet(patterns);
    }

    public DyeablePatternCompressedRing getOrCalculateCompressedRing(int color) {
        if (color == -1) {
            return null;
        }
        synchronizeIndexedProviders();
        return this.compressedRingCache.computeIfAbsent(
                color,
                key -> DyeablePatternCompressedRing.calculate(this.patternsByColor.get(key))
        );
    }

    @Nullable
    public DyeablePatternCompressedRing getOrCalculateCompressedRing(int color, @Nullable AEKey entryPoint) {
        synchronizeIndexedProviders();
        if (entryPoint == null) {
            return getOrCalculateCompressedRing(color);
        }
        return DyeablePatternCompressedRing.calculate(collectConnectedPatterns(color, entryPoint));
    }

    public List<IPatternDetails> getCraftingForByColor(AEKey whatToCraft, int color) {
        DyeablePatternCompressedRing ring = getOrCalculateCompressedRing(color, whatToCraft);
        if (ring == null) {
            return DyeablePatternCraftingPlanner.prioritizeSameColorFallback(
                    super.getCraftingFor(whatToCraft),
                    color
            );
        }
        return DyeablePatternCraftingPlanner.prioritizeSameColorPatterns(
                super.getCraftingFor(whatToCraft),
                ring.executionRatio().keySet(),
                color
        );
    }

    public DyeablePatternCompressedRing getRetainingRing(AEKey catalyst) {
        RetainingRing retainingRing = getRetainingRingCandidate(catalyst);
        return retainingRing == null ? null : retainingRing.ring();
    }

    public RetainingRing getRetainingRingCandidate(AEKey catalyst) {
        if (catalyst == null) {
            return null;
        }
        synchronizeIndexedProviders();
        for (Map.Entry<Integer, Set<IPatternDetails>> entry : this.patternsByColor.entrySet()) {
            if (entry.getKey() == -1) {
                continue;
            }
            DyeablePatternCompressedRing ring = DyeablePatternCompressedRing.calculate(
                    collectConnectedPatterns(entry.getKey(), catalyst)
            );
            if (ring != null
                    && ring.catalysts().get(catalyst) > 0L
                    && ring.netOutputs().get(catalyst) > 0L
                    && ring.entryPoints().contains(catalyst)) {
                return new RetainingRing(entry.getKey(), ring);
            }
        }
        return null;
    }

    public record RetainingRing(int color, DyeablePatternCompressedRing ring) {
    }

    private static ProviderSnapshot snapshotProvider(ICraftingProvider provider) {
        List<IPatternDetails> patterns = List.copyOf(provider.getAvailablePatterns());
        List<PatternFingerprint> patternFingerprints = new ArrayList<>(patterns.size());
        for (IPatternDetails pattern : patterns) {
            patternFingerprints.add(PatternFingerprint.capture(pattern));
        }
        return new ProviderSnapshot(
                patterns,
                Collections.unmodifiableList(patternFingerprints),
                Set.copyOf(provider.getEmitableItems()),
                provider.getPatternPriority()
        );
    }

    private record ProviderSnapshot(
            List<IPatternDetails> patterns,
            List<PatternFingerprint> patternFingerprints,
            Set<AEKey> emitableItems,
            int priority
    ) {
        private boolean sameProviderState(ProviderSnapshot other) {
            return other != null
                    && this.patternFingerprints.equals(other.patternFingerprints)
                    && this.emitableItems.equals(other.emitableItems)
                    && this.priority == other.priority;
        }
    }

    private record PatternFingerprint(
            Class<?> type,
            AEKey definition,
            int color,
            List<InputFingerprint> inputs,
            List<GenericStack> outputs
    ) {
        private static PatternFingerprint capture(IPatternDetails pattern) {
            return new PatternFingerprint(
                    pattern.getClass(),
                    pattern.getDefinition(),
                    PatternColorHelper.getPatternColor(pattern),
                    captureInputs(pattern),
                    copyStacks(pattern.getOutputs())
            );
        }

        private static List<InputFingerprint> captureInputs(IPatternDetails pattern) {
            IPatternDetails.IInput[] inputs = pattern.getInputs();
            if (inputs == null || inputs.length == 0) {
                return List.of();
            }
            List<InputFingerprint> fingerprints = new ArrayList<>(inputs.length);
            for (IPatternDetails.IInput input : inputs) {
                fingerprints.add(InputFingerprint.capture(input));
            }
            return Collections.unmodifiableList(fingerprints);
        }
    }

    private record InputFingerprint(long multiplier, List<GenericStack> possibleInputs) {
        private static InputFingerprint capture(IPatternDetails.IInput input) {
            if (input == null) {
                return new InputFingerprint(1L, List.of());
            }
            return new InputFingerprint(input.getMultiplier(), copyStacks(input.getPossibleInputs()));
        }
    }

    private static List<GenericStack> copyStacks(@Nullable GenericStack[] stacks) {
        if (stacks == null || stacks.length == 0) {
            return List.of();
        }
        List<GenericStack> copy = new ArrayList<>(stacks.length);
        Collections.addAll(copy, stacks);
        return Collections.unmodifiableList(copy);
    }

    private static List<GenericStack> copyStacks(@Nullable Collection<GenericStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(stacks));
    }

    private void synchronizeIndexedProviders() {
        boolean changed = false;
        if (!this.indexedNodeProviders.isEmpty()) {
            for (Map.Entry<IGridNode, ProviderSnapshot> entry
                    : new ArrayList<>(this.indexedNodeProviders.entrySet())) {
                IGridNode node = entry.getKey();
                ICraftingProvider provider = node.getService(ICraftingProvider.class);
                if (provider == null) {
                    continue;
                }
                ProviderSnapshot currentSnapshot = snapshotProvider(provider);
                if (!entry.getValue().sameProviderState(currentSnapshot)) {
                    refreshIndexedNodeProvider(node, currentSnapshot);
                    changed = true;
                }
            }
        }

        if (!this.indexedGlobalProviders.isEmpty()) {
            for (Map.Entry<ICraftingProvider, ProviderSnapshot> entry
                    : new ArrayList<>(this.indexedGlobalProviders.entrySet())) {
                ICraftingProvider provider = entry.getKey();
                ProviderSnapshot currentSnapshot = snapshotProvider(provider);
                if (!entry.getValue().sameProviderState(currentSnapshot)) {
                    refreshIndexedGlobalProvider(provider, currentSnapshot);
                    changed = true;
                }
            }
        }

        if (changed) {
            rebuildColorIndex();
        }
    }

    private void refreshIndexedNodeProvider(IGridNode node, ProviderSnapshot snapshot) {
        super.removeProvider(node);
        super.addProvider(node);
        this.indexedNodeProviders.put(node, snapshot);
    }

    private void refreshIndexedGlobalProvider(ICraftingProvider provider, ProviderSnapshot snapshot) {
        super.removeProvider(provider);
        super.addProvider(provider);
        this.indexedGlobalProviders.put(provider, snapshot);
    }

    private void rebuildColorIndex() {
        this.patternsByColor.clear();
        for (ProviderSnapshot snapshot : this.indexedNodeProviders.values()) {
            indexPatterns(snapshot.patterns());
        }
        for (ProviderSnapshot snapshot : this.indexedGlobalProviders.values()) {
            indexPatterns(snapshot.patterns());
        }
        this.compressedRingCache.clear();
    }

    private void indexPatterns(Collection<IPatternDetails> patterns) {
        for (var pattern : patterns) {
            int color = PatternColorHelper.getPatternColor(pattern);
            this.patternsByColor.computeIfAbsent(color, ignored -> new HashSet<>()).add(pattern);
        }
    }

    private static boolean containsEntryPoint(IPatternDetails pattern, AEKey entryPoint) {
        if (pattern == null || entryPoint == null) {
            return false;
        }
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null && entryPoint.matches(output)) {
                return true;
            }
        }
        for (var input : pattern.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            for (var possibleInput : input.getPossibleInputs()) {
                if (possibleInput != null && possibleInput.what() != null && entryPoint.matches(possibleInput)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Collection<IPatternDetails> collectConnectedPatterns(int color, AEKey entryPoint) {
        Set<IPatternDetails> remaining = new HashSet<>(getPatternsByColor(color));
        Set<AEKey> producedKeys = collectProducedKeys(remaining);
        Set<IPatternDetails> connected = new HashSet<>();
        Set<AEKey> connectedKeys = new HashSet<>();
        connectedKeys.add(entryPoint);

        boolean changed;
        do {
            changed = false;
            for (var iterator = remaining.iterator(); iterator.hasNext();) {
                IPatternDetails pattern = iterator.next();
                if (!sharesAnyKey(pattern, connectedKeys)) {
                    continue;
                }
                iterator.remove();
                connected.add(pattern);
                collectPatternConnectorKeys(pattern, producedKeys, connectedKeys);
                changed = true;
            }
        } while (changed);

        return connected;
    }

    private static Set<AEKey> collectProducedKeys(Collection<IPatternDetails> patterns) {
        Set<AEKey> producedKeys = new HashSet<>();
        if (patterns == null) {
            return producedKeys;
        }
        for (IPatternDetails pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            for (var output : pattern.getOutputs()) {
                if (output != null && output.what() != null) {
                    producedKeys.add(output.what());
                }
            }
        }
        return producedKeys;
    }

    private static boolean sharesAnyKey(IPatternDetails pattern, Set<AEKey> keys) {
        if (pattern == null || keys == null || keys.isEmpty()) {
            return false;
        }
        for (AEKey key : keys) {
            if (containsEntryPoint(pattern, key)) {
                return true;
            }
        }
        return false;
    }

    private static void collectPatternConnectorKeys(
            IPatternDetails pattern,
            Set<AEKey> producedKeys,
            Set<AEKey> target
    ) {
        if (pattern == null || producedKeys == null || target == null) {
            return;
        }
        for (var output : pattern.getOutputs()) {
            if (output != null && output.what() != null) {
                target.add(output.what());
            }
        }
        for (var input : pattern.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            for (var possibleInput : input.getPossibleInputs()) {
                if (possibleInput != null
                        && possibleInput.what() != null
                        && producedKeys.contains(possibleInput.what())) {
                    target.add(possibleInput.what());
                }
            }
        }
    }

}
