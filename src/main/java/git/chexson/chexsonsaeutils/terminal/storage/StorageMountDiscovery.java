package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.networking.IGridNode;
import java.util.List;

/** 主机只读发现契约；禁止向任意存储提供者重放挂载操作。 */
public interface StorageMountDiscovery {
    /** 是否能解释该节点的全部挂载贡献。 */
    boolean supports(IGridNode node);
    /** 返回当前在线节点真正挂载的贡献，未挂载时返回空列表。 */
    List<StorageMount> discover(IGridNode node);
}
