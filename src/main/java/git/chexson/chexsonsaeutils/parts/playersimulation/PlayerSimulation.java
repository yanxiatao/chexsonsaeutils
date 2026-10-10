package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.util.IConfigurableObject;
import appeng.helpers.IConfigInvHost;
import java.util.UUID;

/** 菜单所需的面板契约，避免运行逻辑依赖具体菜单或客户端类。 */
public interface PlayerSimulation extends IUpgradeableObject, IConfigurableObject, IConfigInvHost, appeng.api.storage.ISubMenuHost {
    /** 数量编辑只接受当前容量范围内的标记槽。 */
    int availableSlots();
    /** 查询当前操作类型，驱动左侧操作按钮。 */
    SimulationSettings.Click click();
    /** 查询前方目标类型。 */
    SimulationSettings.Target target();
    /** 查询目标方块的被点击面；目标始终位于面板前方。 */
    SimulationSettings.ClickFace clickFace();
    /** 查询缓存物品选用策略。 */
    SimulationSettings.Selection selection();
    /** 查询冷却约束。 */
    SimulationSettings.Cooldown cooldown();
    /** 查询当前左右键独立保存的点击或按住模式。 */
    SimulationSettings.Press press();
    /** 查询持续使用的主动释放时长。 */
    SimulationSettings.Hold hold();
    /** 查询潜行状态，允许模拟潜行右键。 */
    boolean sneaking();
    /** 查询独立模拟玩家身份。 */
    UUID identity();
    /** 查询最近运行状态，用于界面诊断。 */
    String status();
    /** 查询一个标记槽的实际缓存数量。 */
    int bufferedAmount(int slot);
    /** 循环切换指定选项；服务端只接受定义好的按钮名称。 */
    void cycle(String option);
    /** 手动将所有缓存归还网络；拒绝回收的部分在前方掉落。 */
    void returnBuffers();
}
