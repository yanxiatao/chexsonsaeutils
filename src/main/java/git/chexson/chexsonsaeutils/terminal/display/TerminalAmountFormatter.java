package git.chexson.chexsonsaeutils.terminal.display;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
/** 格内缩略量与完整数量格式契约，全程保留整数精度。 */
public interface TerminalAmountFormatter {
    /** 有效数字限制后的缩略数量，界面再按像素宽度缩放。 */
    String compact(StorageAmount amount);
    /** 按三位分组的完整数量，用于详情；复制接口使用原始十进制。 */
    String full(StorageAmount amount);
    /** 按键的单位比例缩略显示，流体等通道不能把底层最小单位当成物品数。 */
    String compact(appeng.api.stacks.AEKey key, StorageAmount amount);
    /** 带类型单位的精确显示；不可有限展开的单位比例使用分数，不丢弃余数。 */
    String full(appeng.api.stacks.AEKey key, StorageAmount amount);
}
