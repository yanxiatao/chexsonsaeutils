package git.chexson.chexsonsaeutils.crafting.progress;

/** 确认菜单同步给自有预览屏的计算进度；不依赖合成续投是否开启。 */
public interface PlanningProgressMenu {
    /** 无进行中的任务时为空；空阶段让完成后的计划标题走正常显示。 */
    String planningPhase();
    /** 累计实际处理步骤，包括重试、回溯。 */
    long planningSteps();
    /** 当前计算耗时，单位毫秒。 */
    long planningElapsedMillis();
}
