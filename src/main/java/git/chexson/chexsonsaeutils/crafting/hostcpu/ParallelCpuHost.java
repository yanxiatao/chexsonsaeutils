package git.chexson.chexsonsaeutils.crafting.hostcpu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import git.chexson.chexsonsaeutils.crafting.ParallelCpuProvider;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 并行 CPU 执行宿主：提供设备环境、选用规则与运行状态，使执行层不依赖具体方块实体。
 * 继承 AE2 安全宿主以保留 MachineSource 的设备身份；规划活跃条件复用 ParallelCpuProvider。
 */
public interface ParallelCpuHost extends IActionHost, ParallelCpuProvider {
    /** 执行状态改变时标记宿主存档需要保存，覆盖提交、进度、暂停与收尾。 */
    void saveChanges();

    /** 当前连接的网格；设备未入网时为空，执行层每次读取当前环境。 */
    @Nullable IGrid getGrid();

    /** 宿主所在世界；设备尚未放入世界时为空，供执行层读取 tick 和世界上下文。 */
    @Nullable Level getLevel();

    /** 设备当前 CPU 选择模式，控制玩家与机器请求的选择和优先级。 */
    CpuSelectionMode getSelectionMode();

    /** 节点已准备并连接网格；区别于 canProcessParallelCpuJobs 的通电运行条件。 */
    boolean isParallelCpuProviderActive();
}
