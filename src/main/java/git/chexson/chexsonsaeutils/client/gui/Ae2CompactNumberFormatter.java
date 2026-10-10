package git.chexson.chexsonsaeutils.client.gui;

import appeng.core.localization.Tooltips;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Ae2CompactNumberFormatter {

    private static final String[] UNITS = { "k", "M", "G", "T", "P", "E" };
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.#",
            DecimalFormatSymbols.getInstance(Locale.ROOT));

    private Ae2CompactNumberFormatter() {
    }

    public static String format(long value) {
        if (value == Integer.MAX_VALUE || value == Long.MAX_VALUE) {
            return "∞";
        }
        long absolute = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        if (absolute < 1000L) {
            return Long.toString(value);
        }

        double scaled = absolute;
        int unitIndex = -1;
        while (scaled >= 1000.0d && unitIndex < UNITS.length - 1) {
            scaled /= 1000.0d;
            unitIndex++;
        }

        String prefix = value < 0L ? "-" : "";
        return prefix + ONE_DECIMAL.format(scaled) + UNITS[unitIndex];
    }

    public static MutableComponent component(long value) {
        return Component.literal(format(value)).withStyle(Tooltips.NUMBER_TEXT);
    }
}
