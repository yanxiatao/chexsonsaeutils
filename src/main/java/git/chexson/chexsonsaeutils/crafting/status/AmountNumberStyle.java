package git.chexson.chexsonsaeutils.crafting.status;

/**
 * 屏上数量的计数方式（配置 {@code craftingAmountNumberStyle} 的取值）。
 *
 * <p>只作用在<b>紧凑形式</b>（格子内那一列）：全量 tooltip 恒给逐位真值，不受选择影响。
 */
public enum AmountNumberStyle {

    /** AE2 观感：{@code 1.5K / 16Qi / 2De}，超出阶梯才退指数。 */
    ABBREVIATED,

    /** 指数计数法：{@code 1.50e3 / 1.62e19}，恒定 3 位有效数字。 */
    SCIENTIFIC;

    /** 认不出来就回缩写——配表写错时不该让界面显示成空。 */
    public static AmountNumberStyle parse(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase(SCIENTIFIC.name()) ? SCIENTIFIC : ABBREVIATED;
    }
}
