package git.chexson.chexsonsaeutils.crafting.recursive;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 环身份：入口键 + 成员规划拓扑指纹。
 *
 * <p>同一入口下成员规划拓扑不同的环拥有不同的 {@code RingId}。
 * 成员指纹按输出、输入倍率、候选和 remainder 计算并排序组合，
 * 不读取样板 definition 或颜色，内容稳定且与成员迭代顺序无关。
 */
public record RingId(@Nullable AEKey entryPoint, int memberFingerprint) {

    public static RingId of(
            @Nullable AEKey entryPoint,
            @Nullable Collection<? extends IPatternDetails> members
    ) {
        return new RingId(entryPoint, fingerprintOf(members));
    }

    private static int fingerprintOf(@Nullable Collection<? extends IPatternDetails> members) {
        if (members == null || members.isEmpty()) {
            return 0;
        }
        List<Integer> memberHashes = new ArrayList<>(members.size());
        for (IPatternDetails pattern : members) {
            memberHashes.add(pattern == null ? 0 : planningFingerprint(pattern));
        }
        memberHashes.sort(Integer::compareTo);
        int fingerprint = 0;
        for (int hash : memberHashes) {
            fingerprint = fingerprint * 31 + hash;
        }
        return fingerprint;
    }

    private static int planningFingerprint(IPatternDetails pattern) {
        int fingerprint = 1;
        for (var output : pattern.getOutputs()) {
            fingerprint = fingerprint * 31 + (output == null ? 0 : output.hashCode());
        }
        for (var input : pattern.getInputs()) {
            fingerprint = fingerprint * 31 + Long.hashCode(input.getMultiplier());
            for (var candidate : input.getPossibleInputs()) {
                fingerprint = fingerprint * 31 + (candidate == null ? 0 : candidate.hashCode());
                if (candidate != null) {
                    AEKey remainder = input.getRemainingKey(candidate.what());
                    fingerprint = fingerprint * 31 + (remainder == null ? 0 : remainder.hashCode());
                }
            }
        }
        return fingerprint;
    }
}
