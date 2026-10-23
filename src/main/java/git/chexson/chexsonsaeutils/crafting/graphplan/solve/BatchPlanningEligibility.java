package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import appeng.api.stacks.AEKey;

/**
 * 确定子图的整批资格：证明合并 long 分轮不会改变整模板库存、发数或材料缺口。
 * 调用方仍通过现有求解状态和回滚日志结算；本接口只检查编译图结构。
 */
public interface BatchPlanningEligibility {
    /**
     * 检查一个站点能否整批展开，用于加速超 long 的生产需求。
     *
     * @param key 站点请求的精确材料键
     * @param template 每个需求单位的材料数量，用于证明无取整或余料可按整模板重新使用
     * @param root 根请求仅结算一次，允许向上取整产生余量
     * @param remainingDepth 当前调用尚可使用的递归深度，保留原求解器深度限制
     * @return 唯一路线、纯消耗、模板对齐且无共享模板竞争时为 true
     */
    boolean allows(AEKey key, long template, boolean root, int remainingDepth);
}
