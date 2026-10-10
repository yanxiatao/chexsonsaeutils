package git.chexson.chexsonsaeutils.crafting.progress;

/** 请求独占的求解进度读口；工作量未知时只报告真实步骤，不伪造百分比。 */
public interface PlanningProgress {
    /** 原子读取阶段、累计步骤和耗时；累计数包含试探和回溯的工作。 */
    Snapshot snapshot();
    /** 传输只含简单字段，不持有图、计划或库存。 */
    record Snapshot(String phase, long steps, long elapsedMillis) {}
}
