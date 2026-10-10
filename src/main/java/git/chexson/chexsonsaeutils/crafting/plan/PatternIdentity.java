package git.chexson.chexsonsaeutils.crafting.plan;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;

/** 规划与执行共用的样板指纹；只用于诊断，绝不参与材料或任务判据。 */
public final class PatternIdentity {
    private PatternIdentity() { }

    /** 组合实例身份与稳定摘要，供计划和执行诊断对照同一枚样板。 */
    public static String patternIdentityTag(@Nullable IPatternDetails details) {
        return "#i" + (details == null
                ? "?" : Integer.toHexString(System.identityHashCode(details)))
                + "#s" + patternStableDigest(details);
    }

    /**
     * 样板身份<b>摘要</b>（{@code #s} 那一格，纯指纹）：对身份性内容取摘要——
     * {@code definition} ＋ 有序的输出（键原文+数量）＋ 有序的输入槽（倍率+每个可能输入的键原文+数量）。
     * 用 SHA-256 十六进制前 12 位。同一张样板配方跨会话恒定，故 {@code #s} 可比；
     * 只作对照读数，任何判据都不许读它。{@code null} 交回 {@code ?}。
     */
    public static String patternStableDigest(@Nullable IPatternDetails details) {
        if (details == null) {
            return "?";
        }
        var description = new StringBuilder();
        description.append("D=").append(details.getDefinition());
        var outputs = details.getOutputs();
        if (outputs != null) {
            description.append(";O=[");
            for (var stack : outputs) {
                description.append(stack == null ? "<null>" : stack.what() + "x" + stack.amount()).append(',');
            }
            description.append(']');
        }
        var inputs = details.getInputs();
        if (inputs != null) {
            description.append(";I=[");
            for (var input : inputs) {
                if (input == null) {
                    description.append("<null>,");
                    continue;
                }
                description.append(input.getMultiplier()).append('{');
                var possible = input.getPossibleInputs();
                if (possible != null) {
                    for (var stack : possible) {
                        description.append(stack == null ? "<null>" : stack.what() + "x" + stack.amount())
                                .append('/');
                    }
                }
                description.append("},");
            }
            description.append(']');
        }
        return sha256HexPrefix(description.toString(), 12);
    }

    /** SHA-256 十六进制取前 {@code limit} 位；JVM 缺少标准算法时记录错误并立即失败。 */
    private static String sha256HexPrefix(String text, int limit) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, Math.min(limit, hex.length()));
        } catch (java.security.NoSuchAlgorithmException failure) {
            org.slf4j.LoggerFactory.getLogger(PatternIdentity.class).error("JVM 缺少 SHA-256 实现", failure);
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", failure);
        }
    }

}
