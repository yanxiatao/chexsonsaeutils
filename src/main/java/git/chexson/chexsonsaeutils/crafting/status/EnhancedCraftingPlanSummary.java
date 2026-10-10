package git.chexson.chexsonsaeutils.crafting.status;

/**
 * 随计划摘要同步的规划耗时、求解档位与精确字节，保证标题读取同一份计划。
 */
public interface EnhancedCraftingPlanSummary {

    /** 与当前摘要绑定的生产图，空图表示尚未取得原计划。 */
    PlanGraph chexsonsaeutils$graph();

    /** 原计划转换摘要时挂载生产图，客户端禁止从汇总行猜测关系。 */
    void chexsonsaeutils$setGraph(PlanGraph graph);

    /** 与计划摘要同包同步的字节真值，null 表示摘要来自原生有限 long 计划。 */
    @org.jetbrains.annotations.Nullable java.math.BigInteger chexsonsaeutils$exactBytes();

    /** 在原计划仍完整时挂载字节，禁止从饱和值重建。 */
    void chexsonsaeutils$setExactBytes(java.math.BigInteger bytes);

    long chexsonsaeutils$planningMillis();

    String chexsonsaeutils$solver();

    void chexsonsaeutils$setProvenance(long millis, String solver);
}
