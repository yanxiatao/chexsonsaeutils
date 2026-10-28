package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import org.jetbrains.annotations.Nullable;
import java.math.BigInteger;

/** 为预览表与 CPU 监控复用数量文本，缓存只影响显示，不能参与物料记账。 */
public interface CraftingAmountText {
    /** 按键单位、精确数量、显示形式和计数样式取得文本；null 键表示无单位数量。 */
    String format(@Nullable AEKey key, BigInteger amount, AmountFormat format, AmountNumberStyle style);
}
