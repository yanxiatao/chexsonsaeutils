package git.chexson.chexsonsaeutils.integration.extendedae_plus;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModList;

import appeng.api.upgrades.Upgrades;
import com.extendedae_plus.init.ModItems;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;

/**
 * ExtendedAE_Plus 兼容注册：频道卡可安装到框架样板供应器。
 * <p>
 * 动机：ExtendedAE_Plus 是 compileOnly 依赖，注册必须 ModLoaded 门控 +
 * try-catch(Throwable) 防御（版本 API 变化时不阻断本 mod 加载）。
 * group 参数照 ExtendedAE_Plus 对样板供应器的用法（"group.pattern_provider.name"）。
 * <p>
 * 成员作用：{@link #registerUpgrade()} 在 onCommonSetup 中调用，把频道卡
 * （{@code ModItems.CHANNEL_CARD}）注册为框架样板供应器物品的升级项（最多 1 张）。
 */
public final class ExtendedAePlusCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ExtendedAePlusCompat() {
    }

    /**
     * 注册频道卡升级项；ExtendedAE_Plus 未加载时直接返回。
     */
    public static void registerUpgrade() {
        if (!ModList.get().isLoaded("extendedae_plus")) {
            return;
        }
        try {
            String patternProviderGroup = "group.pattern_provider.name";
            Upgrades.add(ModItems.CHANNEL_CARD.get(), ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_ITEM.get(), 1,
                    patternProviderGroup);
            Upgrades.add(ModItems.CHANNEL_CARD.get(), ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_PART_ITEM.get(), 1,
                    patternProviderGroup);
        } catch (Throwable e) {
            // ExtendedAE_Plus 版本 API 变化时降级：频道卡功能不可用，但不阻断本 mod
            LOGGER.warn("Failed to register extendedae_plus channel card upgrade for frame pattern provider", e);
        }
    }
}