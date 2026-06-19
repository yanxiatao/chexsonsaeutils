package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.me.service.helpers.NetworkCraftingProviders;
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
    private final Map<IGridNode, List<IPatternDetails>> indexedNodeProviders = new HashMap<>();
    private final Map<ICraftingProvider, List<IPatternDetails>> indexedGlobalProviders = new IdentityHashMap<>();

    @Override
    public void addProvider(IGridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        super.addProvider(node);
        if (provider != null) {
            replaceIndexedNodeProvider(node, snapshotPatterns(provider));
        }
    }

    @Override
    public void addProvider(ICraftingProvider provider) {
        super.addProvider(provider);
        replaceIndexedGlobalProvider(provider, snapshotPatterns(provider));
    }

    @Override
    public void removeProvider(IGridNode node) {
        super.removeProvider(node);
        unindexNodeProvider(node);
    }

    @Override
    public void removeProvider(ICraftingProvider provider) {
        super.removeProvider(provider);
        unindexGlobalProvider(provider);
    }

    public Collection<IPatternDetails> getPatternsByColor(int color) {
        var patterns = this.patternsByColor.get(color);
        return patterns == null ? Collections.emptySet() : Collections.unmodifiableSet(patterns);
    }

    public DyeablePatternCompressedRing getOrCalculateCompressedRing(int color) {
        if (color == -1) {
            return null;
        }
        return this.compressedRingCache.computeIfAbsent(
                color,
                key -> DyeablePatternCompressedRing.calculate(this.patternsByColor.get(key))
        );
    }

    @Nullable
    public DyeablePatternCompressedRing getOrCalculateCompressedRing(int color, @Nullable AEKey entryPoint) {
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
        if (catalyst == null) {
            return null;
        }
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
                return ring;
            }
        }
        return null;
    }

    private static List<IPatternDetails> snapshotPatterns(ICraftingProvider provider) {
        return List.copyOf(provider.getAvailablePatterns());
    }

    private void replaceIndexedNodeProvider(IGridNode node, List<IPatternDetails> patterns) {
        List<IPatternDetails> previousPatterns = this.indexedNodeProviders.put(node, patterns);
        if (previousPatterns != null) {
            unindexPatterns(previousPatterns);
        }
        indexPatterns(patterns);
    }

    private void replaceIndexedGlobalProvider(ICraftingProvider provider, List<IPatternDetails> patterns) {
        List<IPatternDetails> previousPatterns = this.indexedGlobalProviders.put(provider, patterns);
        if (previousPatterns != null) {
            unindexPatterns(previousPatterns);
        }
        indexPatterns(patterns);
    }

    private void indexPatterns(Collection<IPatternDetails> patterns) {
        for (var pattern : patterns) {
            int color = PatternColorHelper.getPatternColor(pattern);
            this.patternsByColor.computeIfAbsent(color, ignored -> new HashSet<>()).add(pattern);
        }
        this.compressedRingCache.clear();
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

    private void unindexNodeProvider(IGridNode node) {
        List<IPatternDetails> patterns = this.indexedNodeProviders.remove(node);
        if (patterns != null) {
            unindexPatterns(patterns);
        }
    }

    private void unindexGlobalProvider(ICraftingProvider provider) {
        List<IPatternDetails> patterns = this.indexedGlobalProviders.remove(provider);
        if (patterns != null) {
            unindexPatterns(patterns);
        }
    }

    private void unindexPatterns(Collection<IPatternDetails> patterns) {
        for (var pattern : patterns) {
            int color = PatternColorHelper.getPatternColor(pattern);
            this.patternsByColor.computeIfPresent(
                    color,
                    (ignored, indexedPatterns) -> {
                        indexedPatterns.remove(pattern);
                        return indexedPatterns.isEmpty() ? null : indexedPatterns;
                    }
            );
        }
        this.compressedRingCache.clear();
    }

}
