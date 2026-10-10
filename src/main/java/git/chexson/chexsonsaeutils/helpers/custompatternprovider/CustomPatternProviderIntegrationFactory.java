package git.chexson.chexsonsaeutils.helpers.custompatternprovider;

import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;

/** 在设备装配边界注入供应器集成创建策略，保持核心逻辑独立于适配实现。 */
public interface CustomPatternProviderIntegrationFactory {
    /** 按当前宿主、主节点和安全来源创建一个供应器独有的集成实例。 */
    CustomPatternProviderIntegration create(CustomPatternProviderLogicHost host,
            IManagedGridNode mainNode, IActionSource source);
}
