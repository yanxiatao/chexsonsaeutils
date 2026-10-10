package git.chexson.chexsonsaeutils.crafting.request;

import appeng.client.gui.MathExpressionParser;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.util.Optional;
import java.util.regex.Pattern;

/** 复用 AE2 表达式求值；只扩展科学记数写法与 BigInteger 单位换算。 */
public final class CraftingAmountExpressionImpl implements CraftingAmountExpression {
    private static final Pattern EXPONENT = Pattern.compile("[eE]([+-]?)([0-9]+)");

    @Override
    public Optional<BigInteger> parse(String expression, long amountPerUnit) {
        if (amountPerUnit <= 0) throw new IllegalArgumentException("单位比例必须为正");
        if (expression == null || expression.length() > MAX_INPUT_LENGTH) return Optional.empty();
        try {
            var normalized = normalize(expression);
            if (normalized.isEmpty()) return Optional.empty();
            // 与 NumberEntryWidget 使用相同的本地化格式及原版运算优先级、除法和乘方规则。
            var format = new BoundedDecimalFormat();
            format.setParseBigDecimal(true);
            format.setNegativePrefix("-");
            var parsed = MathExpressionParser.parse(normalized.get(), format);
            if (parsed.isEmpty()) return Optional.empty();
            var value = parsed.get();
            if (value.signum() <= 0 || (amountPerUnit == 1 && value.scale() > 0)) return Optional.empty();
            // 不沿用原版的 DECIMAL128 和 longValue，避免宽域数量被舍入或截断。
            var scaled = value.multiply(BigDecimal.valueOf(amountPerUnit));
            if ((long) scaled.precision() - scaled.scale() > MAX_LENGTH) return Optional.empty();
            var amount = scaled.setScale(0, RoundingMode.UP).toBigIntegerExact();
            return amount.signum() > 0 ? Optional.of(amount) : Optional.empty();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            // 未完成表达式属于正常编辑状态，由界面显示验证失败，避免逐按键输出日志。
            return Optional.empty();
        }
    }

    @Override
    public String format(BigInteger amount, long amountPerUnit) {
        if (amount.signum() < 0 || amount.toString().length() > MAX_LENGTH || amountPerUnit <= 0) throw new IllegalArgumentException("数量或单位比例非法");
        int decimals = Long.toString(amountPerUnit).length() + 1;
        var value = new BigDecimal(amount).divide(BigDecimal.valueOf(amountPerUnit), decimals, RoundingMode.DOWN);
        var format = new DecimalFormat("0", new DecimalFormatSymbols());
        format.setGroupingUsed(false);
        format.setMaximumIntegerDigits(MAX_LENGTH);
        format.setMaximumFractionDigits(decimals);
        return format.format(value.stripTrailingZeros());
    }

    /** 保留 AE2 求值算法，在其真实 BigDecimal 运算入口限制中间结果的展开预算。 */
    private static final class BoundedDecimalFormat extends DecimalFormat {
        BoundedDecimalFormat() { super("#.######", new DecimalFormatSymbols()); }
        @Override public Number parse(String source, ParsePosition position) {
            var value = super.parse(source, position);
            return value instanceof BigDecimal decimal ? new BudgetDecimal(decimal) : value;
        }
    }

    private static final class BudgetDecimal extends BigDecimal {
        BudgetDecimal(BigDecimal value) {
            super(value.unscaledValue(), value.scale());
            budget(value.precision(), value.scale());
        }
        private static void budget(long precision, long scale) {
            if (precision > MAX_INPUT_LENGTH || Math.abs(scale) > MAX_INPUT_LENGTH)
                throw new IllegalArgumentException("表达式中间数量超过展开预算");
        }
        private static long integerDigits(BigDecimal value) { return (long) value.precision() - value.scale(); }
        private void additionBudget(BigDecimal value) {
            long scale = Math.max(scale(), value.scale());
            budget(Math.max(integerDigits(this), integerDigits(value)) + scale + 1, scale);
        }
        @Override public BigDecimal add(BigDecimal value) {
            additionBudget(value);
            return new BudgetDecimal(super.add(value));
        }
        @Override public BigDecimal subtract(BigDecimal value) {
            additionBudget(value);
            return new BudgetDecimal(super.subtract(value));
        }
        @Override public BigDecimal multiply(BigDecimal value) {
            budget((long) precision() + value.precision(), (long) scale() + value.scale());
            return new BudgetDecimal(super.multiply(value));
        }
        @Override public BigDecimal divide(BigDecimal value, int scale, RoundingMode mode) {
            budget(integerDigits(this) - integerDigits(value) + scale + 1, scale);
            return new BudgetDecimal(super.divide(value, scale, mode));
        }
        @Override public BigDecimal pow(int exponent) {
            budget((long) precision() * exponent, (long) scale() * exponent);
            return new BudgetDecimal(super.pow(exponent));
        }
        @Override public BigDecimal negate() { return new BudgetDecimal(super.negate()); }
        @Override public BigDecimal stripTrailingZeros() { return new BudgetDecimal(super.stripTrailingZeros()); }
    }

    private static Optional<String> normalize(String expression) {
        int nesting = 0;
        for (int i = 0; i < expression.length(); i++) {
            char token = expression.charAt(i);
            if (token == '(' && ++nesting > 128) return Optional.empty();
            if (token == ')' && --nesting < 0) return Optional.empty();
        }
        var matcher = EXPONENT.matcher(expression);
        var result = new StringBuilder();
        while (matcher.find()) {
            // 在 DecimalFormat 解析前限制指数，避免超大 scale 引发无界整数展开。
            if (new BigInteger(matcher.group(2)).compareTo(BigInteger.valueOf(MAX_LENGTH)) > 0)
                return Optional.empty();
            matcher.appendReplacement(result, "E" + ("-".equals(matcher.group(1)) ? "-" : "") + matcher.group(2));
        }
        matcher.appendTail(result);
        return Optional.of(result.toString());
    }
}
