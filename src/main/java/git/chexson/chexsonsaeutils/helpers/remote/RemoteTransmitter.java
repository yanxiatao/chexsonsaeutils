package git.chexson.chexsonsaeutils.helpers.remote;
import appeng.util.ConfigInventory;
import git.chexson.chexsonsaeutils.crafting.remote.RemoteEndpoint;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import java.util.List;
/** 双网运行及设备配置契约，配置菜单只依赖该接口。 */
public interface RemoteTransmitter extends RemoteEndpoint {
    /** 每个世界 tick 推进有界任务，不同步等待计算线程。 */
    void tick();
    /** 输出过滤用标记槽；空表代表所有本地可合成主产物。 */
    ConfigInventory filter();
    /** 设置来源优先级并请求接收网刷新。 */
    void priority(int priority);
    /** 暂停接单，不丢弃实物。 */
    void pause(boolean paused);
    /** 当前暂停状态。 */
    boolean paused();
    /** 保存所有精确账及来源 links。 */
    void write(CompoundTag tag, HolderLookup.Provider registries);
    /** 从存档恢复，不重提交已存在来源 link。 */
    void read(CompoundTag tag, HolderLookup.Provider registries);
    /** 正常卸载只取消未提交计算；拆卸额外取消来源任务并保留已接收实物。 */
    void unload(boolean dismantle);
    /** 已恢复任务和待办的可读明细。 */
    List<String> orderLines();
    /** 用户明确请求后重试故障任务。 */
    void retry();
    /** 明确取消所有待办与在途来源任务；不删除实物。 */
    void cancel();
}
