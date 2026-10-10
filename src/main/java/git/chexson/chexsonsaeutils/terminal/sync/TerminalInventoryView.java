package git.chexson.chexsonsaeutils.terminal.sync;
import java.util.Collection;
/** 客户端精确库存投影，独立于 AE2 long Repo。 */
public interface TerminalInventoryView {
    /** 原子提交帧，基准缺失时返回 false 请求全量。 */
    boolean apply(TerminalFrame frame);
    /** 当前不可变条目集合，用于搜索、排序和详情。 */
    Collection<TerminalEntry> entries();
    /** 链接或读取失败后禁用操作。 */
    boolean active();
    /** 已提交帧版本；零表示尚未接收到快照。 */
    long revision();
    /** 已发现的节点是否均被精确覆盖。 */
    boolean covered();
}
