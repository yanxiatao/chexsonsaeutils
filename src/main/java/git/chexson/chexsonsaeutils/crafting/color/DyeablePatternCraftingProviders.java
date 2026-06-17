package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.me.service.helpers.NetworkCraftingProviders;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 染色样板索引服务。
 *
 * 只为规划层提供按颜色分组的样板视图，不改动 AE2 原生 craftable 索引。
 */
public class DyeablePatternCraftingProviders extends NetworkCraftingProviders {

    private final Map<Integer, Set<IPatternDetails>> patternsByColor = new HashMap<>();
    private final Map<Integer, DyeablePatternCompressedRing> compressedRingCache = new HashMap<>();

    @Override
    public void addProvider(IGridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        super.addProvider(node);
        if (provider != null) {
            indexProvider(provider);
        }
    }

    @Override
    public void addProvider(ICraftingProvider provider) {
        super.addProvider(provider);
        indexProvider(provider);
    }

    @Override
    public void removeProvider(IGridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        super.removeProvider(node);
        if (provider != null) {
            unindexProvider(provider);
        }
    }

    @Override
    public void removeProvider(ICraftingProvider provider) {
        super.removeProvider(provider);
        unindexProvider(provider);
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

    public List<IPatternDetails> getCraftingForByColor(AEKey whatToCraft, int color) {
        return DyeablePatternCraftingPlanner.prioritizeSameColorPatterns(
                super.getCraftingFor(whatToCraft),
                getPatternsByColor(color),
                color
        );
    }

    private void indexProvider(ICraftingProvider provider) {
        for (var pattern : provider.getAvailablePatterns()) {
            int color = PatternColorHelper.getPatternColor(pattern);
            this.patternsByColor.computeIfAbsent(color, ignored -> new HashSet<>()).add(pattern);
        }
        this.compressedRingCache.clear();
    }

    private void unindexProvider(ICraftingProvider provider) {
        for (var pattern : provider.getAvailablePatterns()) {
            int color = PatternColorHelper.getPatternColor(pattern);
            this.patternsByColor.computeIfPresent(
                    color,
                    (ignored, patterns) -> {
                        patterns.remove(pattern);
                        return patterns.isEmpty() ? null : patterns;
                    }
            );
        }
        this.compressedRingCache.clear();
    }
}
