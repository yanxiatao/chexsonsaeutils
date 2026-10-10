package git.chexson.chexsonsaeutils.integration;

/**
 * 定制样板供应器的能量注入器接口（需求 7：appflux 感应卡灌电）。
 * <p>
 * 动机：appflux 是 compileOnly 依赖，运行时可能缺失——直接引用 appflux 类会导致
 * NoClassDefFoundError。本接口把「感应卡检测 + 灌电」抽象为独立行为，逻辑层
 * （CustomPatternProviderLogic）只依赖本接口，appflux 引用全部集中在实现类中。
 * <p>
 * 成员作用：
 * <ul>
 *   <li>{@link #isInstalled()}：感应卡（appflux 感应卡）是否已安装在宿主升级槽中；
 *       未安装时逻辑层不调用灌电。</li>
 *   <li>{@link #injectEnergy(int)}：从网格 FE 存储提取能量灌入相邻机器，
 *       返回实际注入量（FE）；机器不可达 / 网格无能量 / 未安装时返回 0。</li>
 * </ul>
 */
public interface CustomPatternEnergyInjector {

    /**
     * @return 感应卡是否已安装（宿主升级库存检测）
     */
    boolean isInstalled();

    /**
     * 从网格 FE 存储灌电到相邻机器（强行灌满，绕过机器自身限制）。
     *
     * @param maxAmount 单次注入上限（FE）；实现内部还会受 appflux 配置
     *                  {@code AFConfig.getFluxAccessorIO()} 约束
     * @return 实际注入量（FE），0 表示未注入
     */
    int injectEnergy(int maxAmount);
}