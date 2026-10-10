package git.chexson.chexsonsaeutils.terminal.storage;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import java.util.Map;
/** 可选网络精确采集入口，必须逐挂载读取，不能把饱和总量当真值。 */
public interface NetworkExactInventory {
    /**
     * 冻结实际网络的逐挂载和：精确接口使用真值，其他挂载保留各自的 long 读数。
     * 累加必须使用宽域、覆盖全部实际挂载并保持非负贡献，不能先对网络总量饱和。
     * 未适配挂载内部的饱和不能在此恢复；调用方校正已适配贡献，并独立判定每个键是否确认。
     */
    Map<AEKey, StorageAmount> read(IGrid grid);
    /** 声明实际网络采集中已精确解释的主机，避免为它重放挂载。 */
    boolean covers(IGridNode node);
    /** 该顶层可见挂载是否已被网络桥按精确接口计数，用于避免再次替换已精确的贡献。 */
    boolean observesExactAmounts(appeng.api.storage.MEStorage visible);
}
