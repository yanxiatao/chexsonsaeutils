package git.chexson.chexsonsaeutils.crafting.progress;

/** 计算对象与 Future 暴露同一请求的进度，菜单无需窥探线程或任务内部状态。 */
public interface PlanningProgressSource {
    /** 当前请求的只读进度，生命周期与 Future 相同。 */
    PlanningProgress planningProgress();
}
