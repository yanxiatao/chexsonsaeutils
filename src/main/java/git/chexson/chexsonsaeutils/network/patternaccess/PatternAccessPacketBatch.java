package git.chexson.chexsonsaeutils.network.patternaccess;

import appeng.core.network.clientbound.PatternAccessTerminalPacket;
import java.util.List;

/** 样板管理终端同步批次；复用 AE2 原生包，避免一个提供器的槽位数超过包内集合限制。 */
public interface PatternAccessPacketBatch {
    /** 首片保留全量声明，其余片为同 inventoryId 的增量；增量中的空栈必须保留。 */
    List<PatternAccessTerminalPacket> split(PatternAccessTerminalPacket packet);
}
