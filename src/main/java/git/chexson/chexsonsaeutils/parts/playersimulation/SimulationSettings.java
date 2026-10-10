package git.chexson.chexsonsaeutils.parts.playersimulation;

/** 玩家模拟面板的持久化选项；枚举名称也是存档中的稳定标识。 */
public final class SimulationSettings {
    private SimulationSettings() {}
    /** 左键包括按正常工具速度持续挖掘，右键包括物品持续使用。 */
    public enum Click { LEFT, RIGHT }
    /** 目标限定在面板正前方一个方块的范围内。 */
    public enum Target { BLOCK, ENTITY, AIR }
    /** 自动沿用射线命中面；固定方向只改变目标方块的被点击面，不改变目标位置。 */
    public enum ClickFace {
        AUTO(null), EAST(net.minecraft.core.Direction.EAST), SOUTH(net.minecraft.core.Direction.SOUTH),
        WEST(net.minecraft.core.Direction.WEST), NORTH(net.minecraft.core.Direction.NORTH),
        UP(net.minecraft.core.Direction.UP), DOWN(net.minecraft.core.Direction.DOWN);
        private final net.minecraft.core.Direction direction;
        ClickFace(net.minecraft.core.Direction direction) { this.direction = direction; }
        /** 将配置解析为事件和方块交互使用的实际面。 */
        public net.minecraft.core.Direction resolve(net.minecraft.core.Direction detected) { return direction == null ? detected : direction; }
    }
    /** 顺序模式保持当前槽位，直到其物品库存耗尽。 */
    public enum Selection { RANDOM, ROUND_ROBIN, SEQUENTIAL }
    /** 忽略冷却只跳过物品与攻击冷却，不改变加速卡间隔或挖掘速度。 */
    public enum Cooldown { MATCH, IGNORE }
    /** 点击只执行一次并松开；按住保留按键状态直到定时释放或外部停止。 */
    public enum Press { TAP, HOLD }
    /** 按住时长；持续模式不设置自动释放期限。 */
    public enum Hold { SHORT(10), NORMAL(20), LONG(40), EXTENDED(200), CONTINUOUS(-1);
        public final int ticks;
        Hold(int ticks) { this.ticks = ticks; }
    }
    /** 循环按钮只允许切换已定义选项。 */
    public static <T extends Enum<T>> T next(T value, T[] values) {
        return values[(value.ordinal() + 1) % values.length];
    }
}
