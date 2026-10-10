package git.chexson.chexsonsaeutils.helpers.remote;
import appeng.api.networking.IManagedGridNode;
import net.minecraft.world.level.Level;
/** 传递器运行逻辑所需的方块宿主能力；不依赖实体或菜单的具体实现。 */
public interface RemoteTransmitterHost {
    /** 材料提取及 requester 所属的 A 节点。 */
    IManagedGridNode sourceNode();
    /** provider 及产物插入所属的 B 节点。 */
    IManagedGridNode receiverNode();
    /** 当前世界，只允许服务端调度。 */
    Level world();
    /** 玩家命名或位置标签，供多来源列表区分。 */
    String remoteLabel();
    /** 任何接单、回执或交付后标记方块保存。 */
    void saveRemoteChanges();
}
