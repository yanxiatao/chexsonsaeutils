package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 染色样板规划协调层。
 *
 * 只负责把同色样板优先放到前面，不改动 AE2 的执行链。
 */
public final class DyeablePatternCraftingPlanner {

    private DyeablePatternCraftingPlanner() {
    }

    public static List<IPatternDetails> prioritizeSameColorPatterns(
            @Nullable Collection<? extends IPatternDetails> patterns,
            @Nullable Collection<? extends IPatternDetails> sameColorPatterns,
            int preferredColor
    ) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        if (preferredColor == -1 || sameColorPatterns == null || sameColorPatterns.isEmpty()) {
            return PatternColorHelper.orderPatternsByColor(patterns, preferredColor);
        }

        Set<? extends IPatternDetails> sameColorSet =
                sameColorPatterns instanceof Set<? extends IPatternDetails> set ? set : Set.copyOf(sameColorPatterns);

        List<IPatternDetails> prioritized = new ArrayList<>(patterns.size());
        List<IPatternDetails> fallback = new ArrayList<>(patterns.size());
        for (var pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            if (sameColorSet.contains(pattern) || PatternColorHelper.getPatternColor(pattern) == preferredColor) {
                prioritized.add(pattern);
            } else {
                fallback.add(pattern);
            }
        }
        prioritized.addAll(fallback);
        return List.copyOf(prioritized);
    }
}
