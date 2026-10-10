package git.chexson.chexsonsaeutils.integration.extendedae_plus;

import appeng.helpers.patternprovider.PatternProviderLogic;
import com.extendedae_plus.bridge.InterfaceWirelessLinkBridge;

/**
 * EAP 无线链接桥接辅助类（类加载隔离）。
 * <p>
 * 动机（崩溃修复）：{@code CustomPatternProviderLogic$Ticker.tickingRequest} 是每 tick
 * 调用的热路径。若该方法字节码直接引用 EAP 接口类
 * （{@code instanceof InterfaceWirelessLinkBridge}），JVM JIT 编译该方法时解析常量池
 * 类引用——EAP 未加载时接口类不存在，即使 ModLoaded 门控短路（只防解释执行），
 * JIT 编译期解析仍抛 {@code NoClassDefFoundError} 导致玩家进存档崩溃。
 * <p>
 * 方案：Ticker 只调用本类（仅引用本项目类，恒存在），本类是唯一引用 EAP 接口的
 * 类；调用方 ModLoaded 门控通过时才执行桥接，EAP 未加载时本类永远不会被加载，
 * 热路径方法字节码不含 EAP 类引用，JIT 编译安全。
 * <p>
 * 成员作用：{@link #handleDelayedInit(PatternProviderLogic)} 镜像 EAP
 * {@code PatternProviderLogicTickerMixin.eap$tickHead}（延迟初始化）；
 * {@link #updateWirelessLink(PatternProviderLogic)} 镜像
 * {@code eap$tickTail}（无线状态刷新），返回值 = 是否需要保活
 * （{@code eap$shouldKeepTicking()}，有频道卡时设备不得进入 SLEEP）。
 */
public final class EapWirelessBridgeHelper {

    private EapWirelessBridgeHelper() {
    }

    /**
     * 延迟初始化（服务端，EAP 加载时由调用方门控保证）。
     * <p>
     * 防御：EAP 版本漂移容错——ModList.isLoaded 为 true 但接口类可能不存在于该
     * 版本（用户装的实际版本 1.4.3 是 {@code com.extendedae_plus.bridge} 包，
     * reference-sources 版本是 {@code com.extendedae_plus.api.bridge} 包，
     * 均以此处引用的版本为准）；捕获 NoClassDefFoundError 后静默跳过，功能降级
     * 不崩溃（跨 mod 集成容错，AGENTS.md 例外）。
     *
     * @param logic 样板供应逻辑实例（EAP mixin 已把接口实现到 PatternProviderLogic）
     */
    public static void handleDelayedInit(PatternProviderLogic logic) {
        try {
            if (logic instanceof InterfaceWirelessLinkBridge bridge) {
                bridge.eap$handleDelayedInit();
            }
        } catch (NoClassDefFoundError e) {
            // EAP 版本接口类不存在：无线链接功能降级，不阻断本 mod
        }
    }

    /**
     * 更新无线链接状态并返回是否应保活（照 eap$tickTail 语义）。
     *
     * @param logic 样板供应逻辑实例
     * @return true = 有频道卡需要慢速 tick 保活
     */
    public static boolean updateWirelessLink(PatternProviderLogic logic) {
        try {
            if (logic instanceof InterfaceWirelessLinkBridge bridge) {
                bridge.eap$updateWirelessLink();
                return bridge.eap$shouldKeepTicking();
            }
        } catch (NoClassDefFoundError e) {
            // EAP 版本接口类不存在：无线链接功能降级，不阻断本 mod
        }
        return false;
    }
}