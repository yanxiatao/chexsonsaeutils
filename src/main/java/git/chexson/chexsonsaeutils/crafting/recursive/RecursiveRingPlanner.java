package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 递归压缩环规划协调层。
 *
 * 图规划的压缩环可用性判定，不改动 AE2 的执行链。
 */
public final class RecursiveRingPlanner {

    private RecursiveRingPlanner() {
    }

    public static boolean isCompressedRingCalculable(@Nullable CompressedRing ring) {
        return ring != null && ring.calculable();
    }

    public static boolean allowsRingReplacementCandidate(
            @Nullable CompressedRing ring,
            @Nullable AEKey entryPoint
    ) {
        return isCompressedRingCalculable(ring) && isTrueRecursiveRing(ring, entryPoint);
    }

    /**
     * 真递归判定：环内有催化物、入口在净产出中，且至少一个成员的产出是
     * 另一成员的输入。防止把「共享几个键但互不供养」的巧合簇当环执行。
     */
    public static boolean isTrueRecursiveRing(CompressedRing ring, @Nullable AEKey entryPoint) {
        if (ring == null
                || ring.catalysts().isEmpty()
                || entryPoint == null
                || !ring.entryPoints().contains(entryPoint)) {
            return false;
        }
        Set<IPatternDetails> members = ring.executionRatio().keySet();
        for (IPatternDetails producer : members) {
            for (GenericStack output : producer.getOutputs()) {
                if (output == null || output.what() == null) {
                    continue;
                }
                for (IPatternDetails consumer : members) {
                    // 自供给（consumer == producer，如 1 出 2 的自增殖样板）
                    // 同样是真递归：SCC 的互达性已保证环的图论成立，环是否
                    // 可算由净流量判定（A0 净增长门 + catalysts 非空），
                    // 不由成员数或"供养他人"的形态决定。
                    if (consumesInput(consumer, output.what())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean consumesInput(IPatternDetails pattern, AEKey key) {
        for (var input : pattern.getInputs()) {
            if (input == null || input.getPossibleInputs() == null) {
                continue;
            }
            for (var possibleInput : input.getPossibleInputs()) {
                if (possibleInput != null && key.matches(possibleInput)) {
                    return true;
                }
            }
        }
        return false;
    }

}
