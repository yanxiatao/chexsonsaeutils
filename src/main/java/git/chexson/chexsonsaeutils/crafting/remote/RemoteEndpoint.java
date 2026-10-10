package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.level.Level;
import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;

/** 一个真实传递器的窄路由入口；目录不依赖方块实体实现，也不能凭代理物品获得授权。 */
public interface RemoteEndpoint {
    /** 持久化设备身份，用于同优先级稳定排序与重复设备检测。 */
    UUID identity();
    /** 显示给接收网络的来源名称。 */
    String label();
    /** 当前真实来源网络；离线时为空。 */
    IGrid sourceGrid();
    /** 当前真实接收网络；路由只能在这个网格内选择来源。 */
    IGrid receiverGrid();
    /** 只读的本地可合成输出快照，已应用设备过滤。 */
    Set<AEKey> outputs();
    /** 是否允许接单，包括两端供电、频道、暂停和故障判断。 */
    boolean ready();
    /** 玩家配置的路由优先级，数值越大越优先。 */
    int priority();
    /** 当前待办总量，作为同优先级负载均衡依据，不代替实物量。 */
    BigInteger load();
    /** 服务端接单并持久化需求，返回 false 时不得发生接单副作用。 */
    boolean accept(AEKey output, BigInteger amount);
    /** 在真实来源快照上计算一个目标，返回真实可提交或缺料计划。 */
    ICraftingPlan preview(AEKey output, BigInteger amount, KeyCounter stock);
    /** 设备调度的可读状态，供接收侧显示。 */
    String status();
    /** 所属世界，用于计算和身份隔离。 */
    Level world();
}
