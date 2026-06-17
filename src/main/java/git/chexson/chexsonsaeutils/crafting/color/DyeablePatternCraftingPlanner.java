package git.chexson.chexsonsaeutils.crafting.color;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 染色样板规划协调层。
 *
 * 只负责 planning 阶段的同色样板选择与压缩环可用性判定，不改动 AE2 的执行链。
 */
public final class DyeablePatternCraftingPlanner {

    private DyeablePatternCraftingPlanner() {
    }

    public static boolean isCompressedRingCalculable(@Nullable DyeablePatternCompressedRing ring) {
        return ring != null && ring.calculable();
    }

    public static boolean allowsRingReplacementCandidate(
            @Nullable DyeablePatternCompressedRing ring,
            int processColor,
            @Nullable AEKey entryPoint
    ) {
        return processColor != -1
                && entryPoint != null
                && isCompressedRingCalculable(ring)
                && ring.entryPoints().contains(entryPoint);
    }

    public static boolean canPlanRingReplacementWithoutSwallowingReplacement(
            @Nullable DyeablePatternCompressedRing ring
    ) {
        if (!isCompressedRingCalculable(ring)) {
            return false;
        }
        for (IPatternDetails pattern : ring.executionRatio().keySet()) {
            if (hasReplacementAwareAlternatives(pattern)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasReplacementAwareAlternatives(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs != null && possibleInputs.length > 1) {
                return true;
            }
        }
        return false;
    }

    public static List<IPatternDetails> prioritizeSameColorFallback(
            @Nullable Collection<? extends IPatternDetails> patterns,
            int preferredColor
    ) {
        return PatternColorHelper.orderPatternsByColor(patterns, preferredColor);
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
