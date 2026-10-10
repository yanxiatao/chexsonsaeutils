package git.chexson.chexsonsaeutils.parts.playersimulation;

import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;

/** 与游戏环境分离的选物与间隔规则，保证无库存时明确返回空手。 */
public final class SimulationSchedule {
    private SimulationSchedule() {}
    /** 根据策略寻找有库存的槽位；游标表示上一次选中的槽，首次为 -1。 */
    public static int select(SimulationSettings.Selection mode, int size, int cursor,
                             IntPredicate available, RandomGenerator random) {
        if (size < 1 || cursor < -1 || cursor >= size) throw new IllegalArgumentException("无效槽位范围");
        if (mode == SimulationSettings.Selection.RANDOM) {
            int chosen = -1;
            int count = 0;
            for (int i = 0; i < size; i++) {
                if (available.test(i) && random.nextInt(++count) == 0) chosen = i;
            }
            return chosen;
        }
        int start = cursor < 0 ? 0 : mode == SimulationSettings.Selection.SEQUENTIAL ? cursor : (cursor + 1) % size;
        for (int offset = 0; offset < size; offset++) {
            int slot = (start + offset) % size;
            if (available.test(slot)) return slot;
        }
        return -1;
    }
    /** 0～4 张加速卡分别对应 20、10、5、2、1 tick；一次间隔只开始一次动作。 */
    public static int interval(int speedCards) {
        return switch (speedCards) {
            case 0 -> 20;
            case 1 -> 10;
            case 2 -> 5;
            case 3 -> 2;
            case 4 -> 1;
            default -> throw new IllegalArgumentException("加速卡数量越界: " + speedCards);
        };
    }
}
