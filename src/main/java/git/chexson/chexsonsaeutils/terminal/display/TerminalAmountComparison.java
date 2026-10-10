package git.chexson.chexsonsaeutils.terminal.display;

import appeng.menu.me.common.GridInventoryEntry;
import git.chexson.chexsonsaeutils.terminal.sync.TerminalEntry;
import java.math.BigInteger;

/** 按各键的单位量比较精确库存；交叉相乘避免流体等非物品单位被 long/double 截断。 */
public final class TerminalAmountComparison {
    private TerminalAmountComparison() {}
    /** 未确认条目保留原生排序读数，其展示仍明确标为问号，不宣称精确。 */
    public static int compare(TerminalEntry a, GridInventoryEntry nativeA, TerminalEntry b, GridInventoryEntry nativeB) {
        boolean infiniteA = a != null && a.amount().unbounded(), infiniteB = b != null && b.amount().unbounded();
        if (infiniteA || infiniteB) return Boolean.compare(infiniteA, infiniteB);
        var amountA = a == null ? BigInteger.valueOf(nativeA.getStoredAmount()) : a.amount().value();
        var amountB = b == null ? BigInteger.valueOf(nativeB.getStoredAmount()) : b.amount().value();
        return amountA.multiply(BigInteger.valueOf(nativeB.getWhat().getAmountPerUnit()))
                .compareTo(amountB.multiply(BigInteger.valueOf(nativeA.getWhat().getAmountPerUnit())));
    }
}
