package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import git.chexson.chexsonsaeutils.bigint.WideCeilings;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 屏上数量格式化。计数方式（缩写阶梯 / 指数计数法）由调用方显式传
 * {@link AmountNumberStyle}——配置只在渲染入口读一次（{@link #currentStyle()}），
 * 这些纯函数因此可以离线逐样式断言，不需要加载配置。
 *
 * <p>计数方式只作用在<b>紧凑形式</b> {@code SLOT}；{@code FULL}（tooltip）恒给逐位真值。
 */
public final class EnhancedCraftingStatusFormatting {

    /**
     * {@code long} 上限：宽域值绝对值不超过它就委托 AE2/现有 long 路径显示，
     * 观感与今天完全一致；超过它才走下面的宽域阶梯 / 科学计数 / 全量千分位。
     */
    private static final BigInteger LONG_MAX = WideCeilings.LONG_ACCOUNT;

    /**
     * 宽域 SLOT 缩写阶梯（3 位一组）：{@code K} 起，一路续到 {@code De}（10^33）。
     * 下标 i 对应 {@code 1000^(i+1)}。超出最后一格退科学计数。
     */
    private static final String[] WIDE_SUFFIXES = {
            "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "De"
    };

    /** 科学计数兜底阈值：顶格 De（10^33）再上一档 10^36 起，无后缀可用。 */
    private static final BigDecimal SCIENTIFIC_THRESHOLD =
            BigDecimal.TEN.pow((WIDE_SUFFIXES.length + 1) * 3);

    /** 指数计数法起用阈值：一千以下写 {@code 5.00e2} 反而不如直读，保留纯整数。 */
    private static final BigDecimal SCIENTIFIC_FROM = BigDecimal.TEN.pow(3);

    private EnhancedCraftingStatusFormatting() {
    }

    /** 配置里选的计数方式（渲染入口读一次，逐行传下去）。 */
    public static AmountNumberStyle currentStyle() {
        return AmountNumberStyle.parse(ChexsonsaeutilsCompatibilityConfig.craftingAmountNumberStyleValue());
    }

    public static String formatAmount(long amount, AmountFormat format, AmountNumberStyle style) {
        if (style == AmountNumberStyle.SCIENTIFIC && format == AmountFormat.SLOT) {
            return amount >= 1_000L || amount <= -1_000L
                    ? formatScientific(BigInteger.valueOf(amount))
                    : Long.toString(amount);
        }
        return switch (format) {
            case FULL -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(amount);
            case SLOT -> amount >= 1_000_000_000_000L || amount <= -1_000_000_000_000L
                    ? formatWideSlot(BigDecimal.valueOf(amount)) : formatSlotAmount(amount);
            default -> Long.toString(amount);
        };
    }

    /**
     * 宽域数量格式化（不带键单位）：能塞进 {@code long} 的一律委托现有
     * {@link #formatAmount(long, AmountFormat, AmountNumberStyle)}，只有真值超过 {@code long}
     * 上限才走宽域阶梯 / 科学计数 / 全量千分位（FULL 恒给全量真值，不缩写）。
     */
    public static String formatAmount(BigInteger amount, AmountFormat format, AmountNumberStyle style) {
        if (amount == null) {
            return "";
        }
        if (amount.abs().compareTo(LONG_MAX) <= 0) {
            return formatAmount(amount.longValue(), format, style);
        }
        return switch (format) {
            case FULL -> NumberFormat.getIntegerInstance(Locale.getDefault())
                    .format(new BigDecimal(amount));
            case SLOT -> formatCompact(new BigDecimal(amount), style);
            default -> amount.toString();
        };
    }

    /**
     * 带键单位的宽域格式化：按 {@link AEKey#getAmountPerUnit()} 换算，单位符号
     * （{@link AEKey#getUnitSymbol()}）只在 {@code FULL} 后挂——AE2 的紧凑列
     * （{@code formatShortAmount}）本来就不带单位，我们不比它多占格宽。
     * 未选指数法且能塞 long 时直接委托 AE2 原生路径（观感零变）；否则先按单位换算再续
     * 紧凑形式。{@code key} 为空则退回无单位版本。
     */
    public static String formatAmount(AEKey key, BigInteger amount, AmountFormat format,
            AmountNumberStyle style) {
        if (amount == null) {
            return "";
        }
        if (key == null) {
            return formatAmount(amount, format, style);
        }
        boolean scientificSlot = style == AmountNumberStyle.SCIENTIFIC && format == AmountFormat.SLOT;
        if (!scientificSlot && amount.abs().compareTo(LONG_MAX) <= 0) {
            return key.formatAmount(amount.longValue(), format);
        }
        int amountPerUnit = Math.max(1, key.getAmountPerUnit());
        BigDecimal displayValue = new BigDecimal(amount)
                .divide(BigDecimal.valueOf(amountPerUnit), amountPerUnit > 1 ? 2 : 0, RoundingMode.DOWN);
        String unit = format == AmountFormat.FULL ? key.getUnitSymbol() : null;
        String body = switch (format) {
            case FULL -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(displayValue);
            case SLOT -> formatCompact(displayValue, style);
            default -> displayValue.toPlainString();
        };
        return unit == null ? body : body + " " + unit;
    }

    /** 紧凑形式按计数方式分岔：指数法走 {@code 1.62e19}，缩写走阶梯（顶格后也退指数）。 */
    private static String formatCompact(BigDecimal value, AmountNumberStyle style) {
        if (style == AmountNumberStyle.SCIENTIFIC) {
            if (value.abs().compareTo(SCIENTIFIC_FROM) >= 0) {
                return formatScientific(value.toBigInteger());
            }
            // 一千以下（含按桶换算出来的小数）给可读原值，不写成 1.50e0。
            BigDecimal rounded = value.setScale(2, RoundingMode.DOWN).stripTrailingZeros();
            return rounded.signum() == 0 ? "0" : rounded.toPlainString();
        }
        return formatWideSlot(value);
    }

    /** 宽域 SLOT 缩写：{@code >=100} 或整数量取 0 位小数，否则 1 位小数；超出阶梯退科学计数。 */
    private static String formatWideSlot(BigDecimal value) {
        BigDecimal absolute = value.abs();
        if (absolute.compareTo(SCIENTIFIC_THRESHOLD) >= 0) {
            return formatScientific(value.toBigInteger());
        }
        for (int index = WIDE_SUFFIXES.length - 1; index >= 0; index--) {
            BigDecimal scale = BigDecimal.TEN.pow((index + 1) * 3);
            if (absolute.compareTo(scale) >= 0) {
                BigDecimal scaled = absolute.divide(scale, 10, RoundingMode.DOWN);
                double magnitude = scaled.doubleValue();
                boolean integral = scaled.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0;
                String number = (magnitude >= 100D || integral)
                        ? String.format(Locale.ROOT, "%.0f", magnitude)
                        : String.format(Locale.ROOT, "%.1f", magnitude);
                return (value.signum() < 0 ? "-" : "") + number + WIDE_SUFFIXES[index];
            }
        }
        // 小于 1000 的宽域值理论上不会到这（已在委托路径处理），保险给纯整数。
        return value.setScale(0, RoundingMode.DOWN).toPlainString();
    }

    /**
     * 科学计数：3 位有效数字 + {@code e<指数>}，例 {@code 1.62e36}。
     */
    static String formatScientific(BigInteger amount) {
        int exponent = amount.abs().toString().length() - 1;
        BigDecimal mantissa = new BigDecimal(amount).movePointLeft(exponent)
                .setScale(2, RoundingMode.DOWN);
        return String.format(Locale.ROOT, "%se%d", mantissa.toPlainString(), exponent);
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
