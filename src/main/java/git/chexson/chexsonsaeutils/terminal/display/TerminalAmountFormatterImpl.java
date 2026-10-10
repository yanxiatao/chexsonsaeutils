package git.chexson.chexsonsaeutils.terminal.display;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
/** 用十进制字符直接格式化，避免 double 对相邻大数的精度损失。 */
public final class TerminalAmountFormatterImpl implements TerminalAmountFormatter {
    @Override public String compact(appeng.api.stacks.AEKey key, StorageAmount amount) {
        if (amount.unbounded() || key.getAmountPerUnit() == 1) return compact(amount);
        var units = new java.math.BigDecimal(amount.value()).divide(java.math.BigDecimal.valueOf(key.getAmountPerUnit()),
                new java.math.MathContext(3, java.math.RoundingMode.DOWN));
        if (units.compareTo(java.math.BigDecimal.valueOf(1000)) < 0) return units.stripTrailingZeros().toPlainString();
        int exponent = units.precision() - units.scale() - 1;
        String[] suffixes = {"", "k", "M", "G", "T", "P", "E"};
        int group = exponent / 3;
        int shift = group < suffixes.length ? group * 3 : exponent;
        return units.movePointLeft(shift).stripTrailingZeros().toPlainString()
                + (group < suffixes.length ? suffixes[group] : "e" + exponent);
    }
    @Override public String full(appeng.api.stacks.AEKey key, StorageAmount amount) {
        if (amount.unbounded()) return full(amount);
        int perUnit = key.getAmountPerUnit();
        if (perUnit <= 0) throw new IllegalArgumentException("键的显示单位比例必须为正数");
        String value;
        if (perUnit == 1) value = full(amount);
        else {
            int denominator = perUnit;
            while (denominator % 2 == 0) denominator /= 2;
            while (denominator % 5 == 0) denominator /= 5;
            value = denominator == 1 ? new java.math.BigDecimal(amount.value())
                    .divide(java.math.BigDecimal.valueOf(perUnit)).stripTrailingZeros().toPlainString()
                    : full(amount) + "/" + perUnit;
        }
        return key.getUnitSymbol() == null ? value : value + " " + key.getUnitSymbol();
    }
    @Override public String compact(StorageAmount amount) {
        if (amount.unbounded()) return "∞";
        var value = amount.value().toString(); int digits = value.length();
        if (digits <= 3) return value;
        String[] units = {"", "k", "M", "G", "T", "P", "E"};
        int group = (digits - 1) / 3;
        int head = group < units.length ? digits - group * 3 : 1;
        int decimals = Math.max(0, 3 - head);
        var label = value.substring(0, head);
        if (decimals > 0) label += "." + value.substring(head, head + decimals);
        return label + (group < units.length ? units[group] : "e" + (digits - 1));
    }
    @Override public String full(StorageAmount amount) {
        if (amount.unbounded()) return "∞";
        var value = amount.value().toString(); var out = new StringBuilder(value.length() + value.length() / 3);
        for (int i = 0; i < value.length(); i++) { if (i > 0 && (value.length() - i) % 3 == 0) out.append(','); out.append(value.charAt(i)); }
        return out.toString();
    }
}
