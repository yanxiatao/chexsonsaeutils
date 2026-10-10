package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.menu.me.crafting.CraftConfirmMenu;
import git.chexson.chexsonsaeutils.mixin.minecraft.client.MenuScreensAccessor;
import net.minecraft.client.gui.screens.MenuScreens;
import org.slf4j.Logger;

/**
 * M2 客户端注册：把 AE2 的 {@code CraftConfirmMenu.TYPE} 绑定的屏覆盖成我们的
 * {@link WideAmountCraftConfirmScreen}，让超 {@code long} 的缺料能显示真值。
 *
 * <p>必须在 {@code FMLClientSetupEvent} 的 {@code enqueueWork} 里跑（此时
 * {@code RegisterMenuScreensEvent} 已把 AE2 的注册应用到 {@code MenuScreens} 表），
 * 数量输入始终覆盖同键；原生确认页始终使用我们的预览屏，计算进度不依赖合成续投开关。
 */
public final class WideAmountCraftConfirmRegistration {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private WideAmountCraftConfirmRegistration() {
    }

    public static void register() {
        MenuScreens.ScreenConstructor<appeng.menu.me.crafting.CraftAmountMenu, WideCraftAmountScreen> amountConstructor = WideCraftAmountScreen::new;
        MenuScreensAccessor.chexsonsaeutils$getScreens().put(appeng.menu.me.crafting.CraftAmountMenu.TYPE, amountConstructor);
        git.chexson.chexsonsaeutils.network.crafting.CraftingAmountRestorePayload.bind(value -> {
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof WideCraftAmountScreen screen)
                screen.restore(value.amount(), value.missing());
        });
        try {
            MenuScreens.ScreenConstructor<CraftConfirmMenu, WideAmountCraftConfirmScreen> constructor =
                    WideAmountCraftConfirmScreen::new;
            MenuScreensAccessor.chexsonsaeutils$getScreens().put(CraftConfirmMenu.TYPE, constructor);
            LOGGER.info("[chexsonsaeutils] 合成确认屏已替换为宽域显示版（WideAmountCraftConfirmScreen）");
        } catch (RuntimeException e) {
            // 覆盖失败不致命：退回 AE2 原生确认屏，只是超 long 数量仍按 Long.MAX 显示。
            LOGGER.warn("[chexsonsaeutils] 宽域合成确认屏注册失败，沿用 AE2 原生屏", e);
        }
    }
}
