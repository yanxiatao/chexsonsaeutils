package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AmountFormat;

import java.text.NumberFormat;
import java.util.Locale;

public final class EnhancedCraftingStatusFormatting {

    private EnhancedCraftingStatusFormatting() {
    }

    public static String formatAmount(long amount, AmountFormat format) {
        return switch (format) {
            case FULL -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(amount);
            case SLOT -> formatSlotAmount(amount);
            default -> Long.toString(amount);
        };
    }

    private static String formatSlotAmount(long amount) {
        long absoluteAmount = Math.abs(amount);
        if (absoluteAmount < 1_000L) {
            return Long.toString(amount);
        }
        if (absoluteAmount < 1_000_000L) {
            return formatScaled(amount, 1_000D, "K");
        }
        if (absoluteAmount < 1_000_000_000L) {
            return formatScaled(amount, 1_000_000D, "M");
        }
        return formatScaled(amount, 1_000_000_000D, "B");
    }

    private static String formatScaled(long amount, double scale, String suffix) {
        double value = amount / scale;
        if (Math.abs(value) >= 100D || value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f%s", value, suffix);
        }
        return String.format(Locale.ROOT, "%.1f%s", value, suffix);
    }
}
