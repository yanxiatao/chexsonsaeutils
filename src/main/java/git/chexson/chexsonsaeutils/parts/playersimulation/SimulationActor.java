package git.chexson.chexsonsaeutils.parts.playersimulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** 服务端玩家行为接口；运行部件不直接操作假玩家实现。 */
public interface SimulationActor {
    /** 交互前将眼睛放在面板表面并面向正前方；被点击面的配置不改变视线。 */
    void position(BlockPos host, Direction face, SimulationSettings.ClickFace clickFace, boolean sneaking);
    /** 提供主手和辅助库存，支持箭矢等使用行为。 */
    Inventory inventory();
    /** 设置主手，并更新工具属性。 */
    void hold(ItemStack stack);
    /** 推进冷却计时，不重复执行服务端世界实体 tick。 */
    void tickClock();
    /** 当前动作是否仍在挖掘或持续使用。 */
    boolean busy();
    /** 执行点击或推进持续动作，返回可显示的状态标识。 */
    String act(SimulationSettings.Click click, SimulationSettings.Target target,
               SimulationSettings.Cooldown cooldown, SimulationSettings.Press press, int holdTicks, boolean repeatAllowed);
    /** 取消挖掘和持续使用，清除裂纹。 */
    void cancel();
    /** 结算后解除临时手持引用，但保留持续使用计时。 */
    void suspendHand();
    /** 保存玩家模组数据和食物等状态，不持有背包缓存副本。 */
    CompoundTag save();
}
