package git.chexson.chexsonsaeutils.menu.crafting;

import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import java.math.BigInteger;

/** 下单前把最终总量或等号补足请求换算为净新增；规划器只接收换算后的最小单位数量。 */
public final class WideCraftRequestAmounts {
    private WideCraftRequestAmounts() {}

    /** 无限供应视为无需补足；无限容量的有限库存仍按精确库存相减。 */
    public static BigInteger resolve(BigInteger requested, boolean missing, boolean finalTotal, StorageAmount stock) {
        if (requested.signum() <= 0) throw new IllegalArgumentException("下单数量必须为正");
        if (!missing && !finalTotal) return requested;
        return stock.unbounded() ? BigInteger.ZERO : requested.subtract(stock.value()).max(BigInteger.ZERO);
    }
}
