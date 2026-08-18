package git.chexson.chexsonsaeutils.integration.appflux;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModList;

import appeng.api.upgrades.Upgrades;
import com.glodblock.github.appflux.common.AFSingletons;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * appflux 兼容注册（需求 7）：感应卡可安装到框架样板供应器。
 * <p>
 * 动机：仿 extendedae {@code AFCommonLoad} 先例——appflux 是 compileOnly 依赖，
 * 注册必须 ModLoaded 门控 + try-catch(Throwable) 防御（appflux 版本 API 变化时
 * 不阻断本 mod 加载）。
 * <p>
 * 成员作用：{@link #registerUpgrade()} 在 onCommonSetup 中调用，把感应卡
 * （{@code AFSingletons.INDUCTION_CARD}）注册为框架样板供应器物品的升级项
 * （最多 1 张，tooltip 分组用方块名）。
 */
public final class AppFluxCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AppFluxCompat() {
    }

    /**
     * 注册感应卡升级项；appflux 未加载时直接返回。
     */
    public static void registerUpgrade() {
        if (!ModList.get().isLoaded("appflux")) {
            return;
        }
        try {
            Upgrades.add(AFSingletons.INDUCTION_CARD, ChexsonsaeutilsContent.FRAME_PATTERN_PROVIDER_ITEM.get(), 1,
                    "block.chexsonsaeutils.frame_pattern_provider");
            // 定制样板供应器同样支持感应卡（共享 FramePatternProviderLogicHost.getUpgrades() 判定）
            Upgrades.add(AFSingletons.INDUCTION_CARD, ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_ITEM.get(), 1,
                    "block.chexsonsaeutils.custom_pattern_provider");
            // 定制样板供应器面板同样支持感应卡（阶段 3，面板有升级槽）
            Upgrades.add(AFSingletons.INDUCTION_CARD, ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_PART_ITEM.get(), 1,
                    "item.chexsonsaeutils.custom_pattern_provider_part");
        } catch (Throwable e) {
            // appflux 版本 API 变化时降级：感应卡功能不可用，但不阻断本 mod
            LOGGER.warn("Failed to register appflux induction card upgrade for frame pattern provider", e);
        }
    }
}