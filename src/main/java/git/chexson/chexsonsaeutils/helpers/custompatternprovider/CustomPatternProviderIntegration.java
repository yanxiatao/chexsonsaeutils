package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import appeng.helpers.patternprovider.PatternProviderLogic;
import git.chexson.chexsonsaeutils.integration.CustomPatternEnergyInjector;
import org.jetbrains.annotations.Nullable;

/** 供应器跨模组行为入口；核心逻辑不识别第三方类型、不负责可选模组装配。 */
public interface CustomPatternProviderIntegration {
    /** 当前可用的灌电能力；未装对应模组时不存在。 */
    @Nullable CustomPatternEnergyInjector energyInjector();
    /** 服务端 tick 的无线延迟初始化；由适配层处理模组在场条件。 */
    void handleDelayedInit(PatternProviderLogic logic);
    /** 更新无线链接并返回是否需要保活；未安装无线集成时为 false。 */
    boolean updateWirelessLink(PatternProviderLogic logic);
}
