package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;

public record PatternCompatibility(
        MachineSupportStatus status,
        MachineSupportReasonCode reasonCode,
        @Nullable IPatternDetails pattern,
        @Nullable RecipeSignature signature,
        java.util.List<RecipeSignature> signatures
) {
    public boolean supported() {
        return status == MachineSupportStatus.SUPPORTED_GENERIC
                || status == MachineSupportStatus.SUPPORTED_CONFIG
                || status == MachineSupportStatus.SUPPORTED_EXPLICIT;
    }

    public static PatternCompatibility unsupported(MachineSupportReasonCode reasonCode) {
        return unsupported(MachineSupportStatus.UNSUPPORTED_UNREADABLE, reasonCode);
    }

    public static PatternCompatibility unsupported(MachineSupportStatus status, MachineSupportReasonCode reasonCode) {
        return new PatternCompatibility(status, reasonCode, null, null, java.util.List.of());
    }

    public static PatternCompatibility supported(
            MachineSupportStatus status,
            IPatternDetails pattern,
            RecipeSignature signature
    ) {
        MachineSupportStatus effectiveStatus = status == MachineSupportStatus.SUPPORTED_EXPLICIT
                || status == MachineSupportStatus.SUPPORTED_CONFIG
                ? status
                : MachineSupportStatus.SUPPORTED_GENERIC;
        return new PatternCompatibility(effectiveStatus, MachineSupportReasonCode.NONE, pattern, signature, java.util.List.of(signature));
    }

    /** 保留替换输入对应的全部合法配方，推送时按实收材料选取。 */
    public static PatternCompatibility supported(MachineSupportStatus status, IPatternDetails pattern,
                                                  java.util.List<RecipeSignature> signatures) {
        var first = supported(status, pattern, signatures.getFirst());
        var restricted = MachineRecipePatternImpl.restrict(pattern, signatures);
        return new PatternCompatibility(first.status(), first.reasonCode(), restricted, first.signature(),
                java.util.List.copyOf(signatures));
    }

    public static PatternCompatibility supportedGeneric(IPatternDetails pattern, RecipeSignature signature) {
        return supported(MachineSupportStatus.SUPPORTED_GENERIC, pattern, signature);
    }
}
