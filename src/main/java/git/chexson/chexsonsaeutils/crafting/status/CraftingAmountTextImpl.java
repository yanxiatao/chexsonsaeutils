package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 每个渲染线程独占的有界 LRU，避免静止界面逐帧做大整数进制转换和单位除法。 */
public final class CraftingAmountTextImpl implements CraftingAmountText {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_CHARACTERS = 262_144;
    private static final int MAX_INPUT_BITS = 131_072;
    private final Map<Key, String> entries = new LinkedHashMap<>(32, 0.75f, true);
    private Locale locale = Locale.getDefault();
    private int characters;

    @Override
    public String format(AEKey key, BigInteger amount, AmountFormat format, AmountNumberStyle style) {
        Locale current = Locale.getDefault();
        if (!locale.equals(current)) {
            entries.clear();
            characters = 0;
            locale = current;
        }
        // 极端输入仍完整显示，但不长期占用缓存；输入和输出都设内存边界。
        if (amount.bitLength() > MAX_INPUT_BITS) return render(key, amount, format, style);
        var cacheKey = new Key(key, amount, format, style);
        String cached = entries.get(cacheKey);
        if (cached != null) return cached;
        String rendered = render(key, amount, format, style);
        if (rendered.length() <= MAX_CHARACTERS) {
            entries.put(cacheKey, rendered);
            characters += rendered.length();
            while (entries.size() > MAX_ENTRIES || characters > MAX_CHARACTERS) {
                var oldest = entries.entrySet().iterator();
                characters -= oldest.next().getValue().length();
                oldest.remove();
            }
        }
        return rendered;
    }

    private static String render(AEKey key, BigInteger amount, AmountFormat format, AmountNumberStyle style) {
        return key == null
                ? EnhancedCraftingStatusFormatting.formatUncached(amount, format, style)
                : EnhancedCraftingStatusFormatting.formatUncached(key, amount, format, style);
    }

    private record Key(AEKey key, BigInteger amount, AmountFormat format, AmountNumberStyle style) {}
}
