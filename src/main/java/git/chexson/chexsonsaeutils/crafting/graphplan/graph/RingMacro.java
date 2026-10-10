package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;

/**
 * One compressed SCC ring compiled onto the graph as a virtual producer route
 * for its entry key. The ring's identity is the {@link RingId}.
 *
 * <p>One cycle consumes {@code netInputs} plus the {@code catalysts} seed pool
 * and produces {@code netOutputs}, mirroring the tree planner's
 * {@code requestRingDependencies} + {@code unpackRingOperations} pair:
 * catalyst seeds are reserved from stock (the tree planner's
 * {@code trackRingUsage}), the seed shortfall is requested recursively like a
 * fresh root node, {@code executionRatio × ceil(scale)} lands in
 * {@code patternTimes} with zero bytes, and {@code netOutputs × floor(scale)}
 * is inserted with zero bytes. The solver scales a cycle by
 * {@code ceil(need / netOutputs(entryKey))}, matching the tree planner's
 * double division exactly.
 */
public record RingMacro(
        RingId ringId,
        KeyCounter netInputs,
        KeyCounter netOutputs,
        KeyCounter catalysts,
        java.util.Map<AEKey, Long> startupSeeds,
        java.util.Map<IPatternDetails, Long> executionRatio,
        boolean rootOnly) {

    public RingMacro(
            RingId ringId,
            KeyCounter netInputs,
            KeyCounter netOutputs,
            KeyCounter catalysts,
            java.util.Map<IPatternDetails, Long> executionRatio,
            boolean rootOnly) {
        this(ringId, netInputs, netOutputs, catalysts,
                startupSeedsOf(catalysts), executionRatio, rootOnly);
    }

    private static java.util.Map<AEKey, Long> startupSeedsOf(KeyCounter catalysts) {
        java.util.Map<AEKey, Long> result = new java.util.LinkedHashMap<>();
        for (var stack : catalysts) {
            result.put(stack.getKey(), stack.getLongValue());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public AEKey entryKey() {
        return ringId.entryPoint();
    }

    public long entryOutputPerCycle() {
        return netOutputs.get(ringId.entryPoint());
    }
}
