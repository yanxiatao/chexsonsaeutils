package git.chexson.chexsonsaeutils.mixin.minecraft.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * M2：拿到 {@link MenuScreens} 的静态绑定表，用于把 AE2 的
 * {@code CraftConfirmMenu.TYPE -> CraftConfirmScreen} 覆盖成我们的宽域确认屏。
 *
 * <p>为什么用 map 覆盖而非 {@code RegisterMenuScreensEvent.register}：该事件里
 * {@code register} 遇到已存在键会 {@code throw IllegalStateException}，而 AE2 已在同一
 * 事件注册了 {@code CraftConfirmMenu.TYPE}——事件里再注册必崩。故在事件应用完之后
 * （{@code FMLClientSetupEvent} 的 enqueueWork）直接改这张表，覆盖同键值。
 * 做法与 neoecoae 一致（{@code MenuScreens.SCREENS}）。
 */
@Mixin(value = MenuScreens.class, remap = false)
public interface MenuScreensAccessor {

    @Accessor(value = "SCREENS", remap = false)
    static Map<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>> chexsonsaeutils$getScreens() {
        throw new AssertionError("mixin accessor; body replaced at transform time");
    }
}
