package git.chexson.chexsonsaeutils.client.gui;

import appeng.core.localization.Tooltips;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.text.DecimalFormat;

public final class Ae2ByteDisplayFormatter {

    private static final String[] BYTE_UNITS = { "k", "M", "G", "T", "P", "E" };

    private Ae2ByteDisplayFormatter() {
    }

    public static String format(long bytes) {
        if (bytes == Long.MAX_VALUE) {
            return "∞";
        }
        if (bytes < 1024L) {
            return Long.toString(bytes);
        }

        double scaled = bytes;
        int unitIndex = -1;
        while (scaled >= 1000.0d && unitIndex < BYTE_UNITS.length - 1) {
            scaled /= 1024.0d;
            unitIndex++;
        }

        return formatScaled(scaled) + BYTE_UNITS[unitIndex];
    }

    public static MutableComponent component(long bytes) {
        if (bytes == Long.MAX_VALUE) {
            return Component.literal("∞").withStyle(style -> style.withColor(0x55FFFF));
        }
        return Component.literal(format(bytes)).withStyle(Tooltips.NUMBER_TEXT);
    }

    private static String formatScaled(double scaled) {
        if (scaled < 10.0d) {
            return new DecimalFormat("0.###").format(scaled);
        }
        if (scaled < 100.0d) {
            return new DecimalFormat("0.##").format(scaled);
        }
        return new DecimalFormat("0.#").format(scaled);
    }
}
