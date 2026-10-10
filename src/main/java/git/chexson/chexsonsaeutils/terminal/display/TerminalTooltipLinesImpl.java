package git.chexson.chexsonsaeutils.terminal.display;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** 对连续大数字按实际字体宽度换行，每次只测量一个有界片段。 */
public final class TerminalTooltipLinesImpl implements TerminalTooltipLines {
    @Override public List<String> wrap(String text, int width, ToIntFunction<String> measure) {
        if (width <= 0) throw new IllegalArgumentException("tooltip 宽度必须为正数");
        var result = new ArrayList<String>();
        for (int start = 0; start < text.length();) {
            int minimum = Character.charCount(text.codePointAt(start));
            int low = minimum, high = (int) Math.min(text.length() - (long) start, Math.max(minimum, width));
            int length = minimum;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (measure.applyAsInt(text.substring(start, start + middle)) <= width) {
                    length = middle; low = middle + 1;
                } else high = middle - 1;
            }
            if (start + length < text.length() && Character.isHighSurrogate(text.charAt(start + length - 1))
                    && Character.isLowSurrogate(text.charAt(start + length))) length--;
            result.add(text.substring(start, start + length)); start += length;
        }
        return List.copyOf(result);
    }
}
