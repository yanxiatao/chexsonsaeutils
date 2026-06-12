package git.chexson.chexsonsaeutils.crafting.directprocessing;

import appeng.api.crafting.IPatternDetails;
import org.jetbrains.annotations.Nullable;

public record PatternCompatibility(
        MachineSupportStatus status,
        MachineSupportReasonCode reasonCode,
        @Nullable IPatternDetails pattern,
        @Nullable RecipeSignature signature
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
        return new PatternCompatibility(status, reasonCode, null, null);
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
        return new PatternCompatibility(effectiveStatus, MachineSupportReasonCode.NONE, pattern, signature);
    }

    public static PatternCompatibility supportedGeneric(IPatternDetails pattern, RecipeSignature signature) {
        return supported(MachineSupportStatus.SUPPORTED_GENERIC, pattern, signature);
    }
}
